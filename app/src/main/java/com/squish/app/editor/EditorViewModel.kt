package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectSnapshot
import com.squish.app.data.SquishRepositories
import com.squish.app.media.EncoderCeiling
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.ExportStage
import com.squish.app.media.ExportsInFlight
import com.squish.app.media.MediaCompat
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.StillClips
import com.squish.app.media.GallerySaver
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.video.FilmstripLoader
import com.squish.app.media.audio.WaveformBuilder
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.ZOOM_MAX
import com.squish.app.timeline.ZOOM_MIN
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import com.squish.app.editor.edits.AnalysisEdits
import com.squish.app.editor.edits.AudioEdits
import com.squish.app.editor.edits.ClipEdits
import com.squish.app.editor.edits.EditHost
import com.squish.app.editor.edits.LayerEdits
import com.squish.app.editor.edits.TextEdits
import kotlinx.coroutines.CoroutineScope

class EditorViewModel(
    application: Application,
    /**
     * Survives process death with the screen's back-stack entry, which is how a
     * fresh view model can tell "the app was killed under this editor" from
     * "this clip was opened again from the dashboard".
     */
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val processor = VideoProcessor(application)
    private val historyRepository = SquishRepositories.history(application)
    private val autosave = SquishRepositories.autosave(application)

    private var loadedUri: Uri? = null
    private var proxyJob: Job? = null

    /**
     * The view model's own steps, lent to the areas of edits below. Everything an
     * area does goes through these - the same state, the same undo stack - so
     * splitting the edits across files changed where they are written, not what
     * they do.
     */
    private val host = object : EditHost {
        override val app: Application get() = getApplication()
        override val state: MutableStateFlow<EditorUiState> get() = _state
        override val scope: CoroutineScope get() = viewModelScope
        override val history: UndoStack<EditSnapshot> get() = this@EditorViewModel.history
        override fun record(label: String, gesture: String?, holdMs: Long, tag: String?, change: () -> Unit) =
            this@EditorViewModel.record(label, gesture, holdMs, tag, change)
        override fun recordLate(
            label: String,
            edit: (EditSnapshot) -> EditSnapshot,
            alongside: (EditorUiState) -> EditorUiState
        ) = this@EditorViewModel.recordLate(label, edit, alongside)
        override fun edited() = this@EditorViewModel.edited()
        override fun publishHistory() = this@EditorViewModel.publishHistory()
        override fun mutateTimeline(block: (TimelineState) -> TimelineState) = this@EditorViewModel.mutateTimeline(block)
        override fun recomputeEstimate() = this@EditorViewModel.recomputeEstimate()
        override fun displayNameOf(uri: Uri): String? = this@EditorViewModel.displayNameOf(uri)
        override fun checkDecodable(uri: Uri) = this@EditorViewModel.checkDecodable(uri)
        override fun ensureProxies(uris: Collection<Uri>) = this@EditorViewModel.ensureProxies(uris)
    }

    /** Clips and the timeline: add, cut, move, trim, speed, placement, look, effects. */
    internal val clips = ClipEdits(host)

    /** Sound, and the beat grid. */
    internal val audio = AudioEdits(host)

    /** Titles, lines, stickers and captions. */
    internal val text = TextEdits(host)

    /** Overlays, transitions, green screen and masks. */
    internal val layers = LayerEdits(host)

    /** Background removal, reframing, tracking and stabilization. */
    internal val analysis = AnalysisEdits(host, clips)

    /**
     * The timeline as it stood the moment its clip finished loading.
     *
     * Opening a video is not an edit. Saving from that moment on put every video
     * that was merely opened and backed out of on the dashboard as "pick up where
     * you left off". The edit is only saved once it differs from this.
     */
    @Volatile
    private var baseline: String? = null

    /** Whether this session has put a draft on disk, so undoing back to nothing can take it off. */
    @Volatile
    private var wroteDraft = false

    /**
     * The offered edit, when editing the bare clip set it aside: what it was, and
     * the bin entry it went to. Kept so that undoing back to the bare clip puts
     * it back and offers it again - the first edit retired it, so the undo of
     * that edit has to un-retire it, or an accidental drag and its undo quietly
     * moved a two-hour project into the bin.
     */
    private var setAside: Pair<ProjectSnapshot, String>? = null

    /**
     * This session's own draft, when undoing back to the bare clip moved it into
     * the bin. The next edit takes it back out before saving, so undo and redo
     * past the bare clip move one draft back and forth instead of leaving a copy
     * in the bin every time.
     */
    private var undoneDraft: String? = null

    /**
     * Held by every step that decides what the slot holds - a save, retiring or
     * re-offering the saved edit, accepting it. The ticker runs them on IO and a
     * flush on leaving runs them on Main, and two of them interleaved could bin
     * the draft the other had just written.
     */
    private val slotLock = Any()

    /**
     * True from the moment a clip is opened until the process dies, and true
     * again the instant a view model is rebuilt from the saved entry afterwards.
     * That second case is the only way a brand-new view model finds it set.
     */
    private val restoredAfterDeath: Boolean = savedState.get<Boolean>(KEY_OPENED) == true

    init {
        // Aggressive by design. Each save is atomic, and skipped entirely when
        // nothing changed, so the cost of a tick is one string comparison, and the
        // worst case after a kill is a second and a half of lost work - and
        // leaving the editor flushes the rest, see saveNow.
        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL)
                // Off the main thread. viewModelScope is Main, so encoding the
                // timeline to JSON and fsyncing it were both happening on the
                // frame loop, every second and a half, for the whole session -
                // which is exactly the kind of thing that makes a scrub stutter
                // for no visible reason.
                withContext(Dispatchers.IO) { saveNow() }
            }
        }
        // Told from the state rather than at each place an export starts or
        // stops - there are five - so nothing can be left counted as exporting.
        viewModelScope.launch {
            _state.map { it.isExporting }.distinctUntilChanged().collect { ExportsInFlight.set(this@EditorViewModel, it) }
        }
    }

    /**
     * Writes the edit to disk now, on the calling thread, if it has changed.
     *
     * The ticker calls this every second and a half; leaving the screen, the app
     * going to the background and the view model being cleared each call it once
     * more, so the last edit before a back press is on disk and not in the
     * one-and-a-half-second gap it used to fall into.
     *
     * Also while an export runs. It used to skip them, on the grounds that the
     * export flushed for itself before it started - but the editor stays usable
     * under a render, and "exporting" now lasts through the gallery copy too, so
     * a caption changed during a long render and the app sent behind something
     * during the copy was on disk nowhere when the process was killed. Every
     * step takes the slot lock, so a save here and the export's own cannot
     * interleave.
     */
    fun saveNow() {
        persist()
    }

    /**
     * The save itself, behind [saveNow] and the export's own flushes.
     *
     * While a saved edit is on offer, the document behind the offer is the one
     * in the slot, and saving the bare clip there would write over it. So the
     * offer is answered first - and it is answered by any change at all to the
     * bare clip, not only by the changes that go through undo: a caption typed
     * or a beat grid found under the banner used to be saved nowhere, and lost
     * on leaving. The offered document goes to the bin, the new work is saved,
     * and undoing back to the bare clip reverses both.
     *
     * The modal offer, after the app was killed, blocks the editor, so nothing
     * can have changed under it; it is left alone.
     */
    private fun persist(): Unit = synchronized(slotLock) {
        val current = _state.value
        val uri = current.sourceUri ?: return
        val start = baseline ?: return
        val offer = current.recovery
        if (offer?.modal == true) return
        val untouched = autosave.editKey(current) == start

        if (untouched) {
            if (offer != null) return
            // Undone all the way back to the untouched clip. This session's draft
            // is set aside rather than removed - the undo history that would
            // restore it lives only in memory - and a saved edit the first change
            // retired is put back and offered again.
            if (wroteDraft) {
                undoneDraft = autosave.clear(uri) ?: undoneDraft
                wroteDraft = false
            }
            setAside?.let { (snapshot, trashId) ->
                setAside = null
                if (autosave.restore(trashId)) {
                    _state.update { it.copy(recovery = RecoveryOffer(snapshot), setAsideNotice = false) }
                }
            }
            return
        }

        if (offer != null) {
            val trashId = autosave.clear(offer.snapshot.sourceUri)
            // Left standing if the move failed: the new work is not saved over a
            // document that could not be moved out of its way, and the next tick
            // tries again.
            if (trashId == null && autosave.peek(offer.snapshot.sourceUri) != null) return
            setAside = trashId?.let { offer.snapshot to it }
            _state.update { it.copy(recovery = null, setAsideNotice = trashId != null) }
        }
        // Back past the bare clip after an undo to it: the draft that undo set
        // aside comes back out of the bin first, and this save goes on top of it.
        undoneDraft?.let { trashId ->
            undoneDraft = null
            autosave.restore(trashId)
        }
        if (autosave.save(_state.value)) wroteDraft = true
    }

    /** The notice that editing set the saved edit aside, read and closed. */
    fun dismissSetAsideNotice() = _state.update { it.copy(setAsideNotice = false) }

    /**
     * Names the project, or with a blank name un-names it. Saved at once rather
     * than at the next tick: the rename dialog is often the last thing done
     * before leaving.
     *
     * Not while a saved edit is on offer. The name is part of what a save
     * compares, so naming the bare clip counted as working on it: the offered
     * edit went to the bin and the bare clip was saved in its slot - and since a
     * name is not an undo step, undo could never bring the offer back. The
     * header does not offer the rename then; this is the rule behind it.
     */
    fun renameProject(raw: String) {
        if (_state.value.recovery != null) return
        val name = ProjectName.clean(raw)
        if (name == _state.value.projectName) return
        _state.update { it.copy(projectName = name) }
        viewModelScope.launch(Dispatchers.IO) { saveNow() }
    }

    /**
     * Opens a video. With [resume] - a draft chosen from the drafts list - its
     * saved edit is applied at once instead of being offered: picking a draft is
     * already the answer to "restore it?".
     */
    fun load(uri: Uri, resume: Boolean = false) {
        if (loadedUri == uri) return
        loadedUri?.let(OpenEditors::closed)
        loadedUri = uri
        // Known to be open from here until cleared, so "Open with" on this
        // video comes back here rather than opening a second editor of it.
        OpenEditors.opened(uri)
        savedState[KEY_OPENED] = true

        _state.update { it.copy(sourceUri = uri, isLoadingSource = true) }

        viewModelScope.launch {
            // Read before anything else writes. The ticker is already running,
            // but it cannot write until the load below finishes and sets the
            // baseline, so the document is still the one to recover - and this
            // read is off the main thread, where a draft with a few thousand
            // motion samples in it was a visible hitch on entry.
            val recoverable = withContext(Dispatchers.IO) { autosave.peek(uri) }
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            val originalSize = runCatching {
                getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)

            val name = displayNameOf(uri) ?: "Clip 1"
            _state.update {
                it.copy(
                    durationMs = meta.durationMs,
                    // The shape the picture is seen in, not the shape it is stored
                    // in. A portrait clip is a 1920x1080 stream with a rotation tag;
                    // taking the stored numbers made the preview box landscape and
                    // letterboxed the export into a landscape frame.
                    sourceWidth = meta.displayWidth,
                    sourceHeight = meta.displayHeight,
                    sourceHasAudio = meta.hasAudio,
                    fps = meta.fps,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    isLoadingSource = false,
                    // Fitted the moment the clip is known. At the default zoom a
                    // ten-minute video is twenty-five thousand dp of strip, so it
                    // opened somewhere off the right-hand edge and stayed there.
                    fitNonce = it.fitNonce + 1,
                    originalSizeBytes = originalSize,
                    videoClips = listOf(
                        Clip(
                            kind = ClipKind.Video,
                            uri = uri,
                            label = name,
                            sourceInMs = 0,
                            sourceOutMs = meta.durationMs,
                            timelineStartMs = 0,
                            sourceDurationMs = meta.durationMs
                        )
                    )
                )
            }
            recomputeEstimate()
            baseline = autosave.editKey(_state.value)
            // Every real video has a length. None means the file could not be read
            // - gone, or handed over without permission - and an empty editor with
            // nothing said is the worst way to learn that.
            if (meta.durationMs <= 0L) _state.update { it.copy(failure = SquishError.FileUnreadable()) }
            checkDecodable(uri)
            offerRecovery(recoverable, uri)
            if (resume && _state.value.recovery?.snapshot?.sourceUri == uri) {
                acceptRecovery()
                return@launch
            }
            ensureProxies(listOf(uri))
            confirmSourceAudio(uri)
        }
    }

    /**
     * A second opinion on whether the opened file has sound, asked only when the
     * probe said it had none.
     *
     * A failed decode is not proof of silence - decodeMono gives up for plenty of
     * reasons that are not "there is no audio here" - so it can confirm a track
     * but never deny one. It used to decode the first minute of every file opened
     * and build a waveform nothing ever drew; two seconds, and only when the
     * container's answer was no, settles the same question.
     */
    private suspend fun confirmSourceAudio(uri: Uri) {
        if (_state.value.sourceHasAudio) return
        val pcm = PcmDecoder.decodeMono(getApplication(), uri, maxDurationMs = 2_000L)
        if (pcm != null) _state.update { it.copy(sourceHasAudio = true) }
    }

    /** The playhead advancing under playback. Never moves the players. */
    fun setPlayhead(ms: Long) =
        _state.update { it.copy(playheadMs = ms.coerceIn(0L, it.timelineDurationMs)) }

    /**
     * A deliberate jump - scrubbing the ruler, a nudge, a snap to a marker. The
     * nonce is what tells the preview engine to actually go there, which is how a
     * scrub is distinguished from the playhead simply moving on its own.
     */
    /**
     * Moves the playhead, pulling it onto anything it lands near.
     *
     * A finger on a phone screen is about nine millimetres wide and a frame at
     * a normal zoom is well under one, so landing exactly on a cut by aim alone
     * is not a thing anyone can do. Snapping makes the common intentions — the
     * start of a shot, the end of one, a beat, a marker — free, and a drag of
     * more than the threshold still goes wherever it is put.
     */
    fun scrubTo(ms: Long) = _state.update { current ->
        val target = ms.coerceIn(0L, current.timelineDurationMs)
        val snapped = if (current.snapToMarkers) snapToAnything(target, current) else target
        current.copy(playheadMs = snapped, scrubNonce = current.scrubNonce + 1)
    }

    /**
     * The transport's frame buttons: [frames] frames on from the one showing,
     * counted in the frames of the shot under the playhead - see
     * [Timecode.frameStep]. Not snapped, unlike a scrub: a step that landed on
     * a nearby cut instead would not be a frame.
     */
    fun stepFrames(frames: Int) = _state.update {
        val shot = it.baseClipAt(it.playheadMs)
        val target = if (shot == null) Timecode.frameStep(it.playheadMs, frames, it.fps)
        else Timecode.frameStep(
            it.playheadMs, frames, it.fps,
            shot.timelineStartMs, shot.timelineEndMs, shot::sourceAt, shot::timelineAtSource
        )
        it.copy(playheadMs = target.coerceIn(0L, it.timelineDurationMs), scrubNonce = it.scrubNonce + 1)
    }

    /**
     * The playhead exactly where it is put, not snapped - the full-screen scrub
     * bar. Snapping there went by the strip's zoom, not the bar's, so on a
     * fitted strip the picture stuck to a cut across a second of bar while the
     * thumb moved on without it.
     */
    fun seekTo(ms: Long) = _state.update {
        it.copy(playheadMs = ms.coerceIn(0L, it.timelineDurationMs), scrubNonce = it.scrubNonce + 1)
    }

    /**
     * The nearest thing worth landing on: a clip edge, a marker, or the start.
     *
     * Clip edges are included because they are what people actually aim at. A
     * marker grid exists only if someone made one; the cuts are always there.
     */
    private fun snapToAnything(ms: Long, current: EditorUiState): Long {
        val threshold = snapThreshold(current)
        val candidates = sequence {
            yield(0L)
            yield(current.timelineDurationMs)
            current.markers.forEach { yield(it) }
            (current.videoClips + current.audioClips).forEach { clip ->
                yield(clip.timelineStartMs)
                yield(clip.timelineEndMs)
            }
        }
        val nearest = candidates.minByOrNull { abs(it - ms) } ?: return ms
        return if (abs(nearest - ms) <= threshold) nearest else ms
    }

    /**
     * How close counts as near, in milliseconds.
     *
     * Derived from the zoom rather than the clip length: what matters is how far
     * the finger moved on screen, and eight device-independent pixels is about a
     * third of a fingertip whatever the timeline is showing.
     */
    private fun snapThreshold(current: EditorUiState): Long =
        (SNAP_DP / current.pixelsPerSecond * 1000f).toLong().coerceIn(20L, 500L)

    /** A third of a fingertip, in dp. */
    private val SNAP_DP = 8f

    fun setPlaying(playing: Boolean) = _state.update { it.copy(isPlaying = playing) }

    // ---- Export settings ------------------------------------------------------

    /** Picks an output size, which also leaves fit-to-size: the two are rival answers. */
    fun setOutputP(p: Int) {
        _state.update { it.copy(outputP = p, fitToSize = false) }
        recomputeEstimate()
    }

    fun setFitToSize(enabled: Boolean) {
        _state.update { it.copy(fitToSize = enabled) }
        recomputeEstimate()
    }

    fun setTargetSizeMb(mb: Int) {
        _state.update { it.copy(targetSizeMb = mb) }
        recomputeEstimate()
    }

    /** Which size is being asked about, and which answer to keep; see [ProbeGate]. */
    private val probes = ProbeGate<ExportPresets.Resolution> { _state.value.encoderAnswer?.asked == it }

    /**
     * Asks the phone's encoder what it will write for the size now chosen, so
     * the sheet can say so. Off the main thread: it opens the codec list. An
     * answer for a size no longer chosen is dropped - the size chosen since was
     * asked about when it was chosen, and its own answer is on its way.
     */
    fun probeEncoder() {
        val asked = _state.value.outputResolution
        if (!probes.ask(asked)) return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked) }
            if (!probes.keep(asked, _state.value.outputResolution)) return@launch
            _state.update { it.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written)) }
            recomputeEstimate()
        }
    }

    /**
     * Asks whether this phone can decode [uri], and says so straight away if not -
     * rather than showing a black preview, or letting an export start that cannot
     * finish. The answer is kept for the export's preflight. See [MediaCompat].
     */
    private fun checkDecodable(uri: Uri) {
        viewModelScope.launch {
            val report = MediaCompat.check(getApplication(), uri) ?: return@launch
            // Whether an overlay has sound is only known from here on, and the
            // size estimate sets bits aside for it (EditorUiState.hasAnyAudio).
            recomputeEstimate()
            val problem = report.videoProblem?.let { SquishError.UnsupportedCodec(it) }
                ?: report.audioProblem?.let { SquishError.UnsupportedAudio(it) }
                ?: return@launch
            _state.update { it.copy(failure = problem) }
        }
    }

    /**
     * Selects a clip, caption or effect - or, with null, nothing. Null is the way
     * out of a selection: tapping empty timeline or empty picture, the toolbar's
     * back chevron. Not an undo step: choosing what to work on is not an edit.
     */
    fun selectClip(clipId: String?) = _state.update { it.copy(selectedClipId = clipId) }

    /** Sets the zoom directly, which is how the strip answers a fit request. */
    // One pair of limits for the zoom, shared with the strip. They were written
    // out again here, so the pinch and the buttons disagreed about how far in you
    // could go - and the strip's own ceiling had moved.
    fun setPixelsPerSecond(value: Float) =
        _state.update { it.copy(pixelsPerSecond = value.coerceIn(ZOOM_MIN, ZOOM_MAX)) }

    /** Asks the strip to fit the whole edit across its width. */
    fun fitTimeline() = _state.update { it.copy(fitNonce = it.fitNonce + 1) }

    // ---- Undo -------------------------------------------------------------------

    private val history = UndoStack<EditSnapshot>()

    /** How deep in [record] the current change is; see there. */
    private var recordDepth = 0

    /**
     * Makes a change, and files where the edit was before it as one undo step.
     *
     * Every edit goes through here - which is the point: about twenty-five of them
     * did not, and each was silently folded into whatever step came before it, so
     * "Undo: Cut" after adding a dissolve took the dissolve *and* the cut.
     *
     * [label] is what the button says. [gesture] names a continuing edit - one
     * drag, one slider, one run of typing - whose many changes are one step; a
     * discrete action leaves it null and is never merged with anything, however
     * quickly the next one comes. See [UndoStack].
     *
     * A change that changes nothing records nothing, so the button never offers
     * to undo an edit that did not happen. A change made from inside another
     * recorded change - Delete removing a caption - is part of the outer step,
     * not a second one.
     */
    private fun record(
        label: String,
        gesture: String? = null,
        holdMs: Long = UndoStack.COALESCE_MS,
        /** Names the step, so it can be taken back out if it turns out to be nothing; see [UndoStack.drop]. */
        tag: String? = null,
        change: () -> Unit
    ) {
        if (recordDepth > 0) {
            change()
            return
        }
        val before = _state.value.editSnapshot
        val now = System.currentTimeMillis()
        // Asked before the change: whether this push joins the step still open
        // is what [mutateTimeline] needs to fit effects from where the gesture
        // began, and a lifted finger ends that as surely as it ends the step.
        recordingGesture = gesture
        gestureContinues = gesture != null && history.continues(gesture, now)
        recordDepth++
        try {
            change()
        } finally {
            recordDepth--
            recordingGesture = null
            gestureContinues = false
        }
        if (_state.value.editSnapshot == before) return
        history.record(label, before, now, gesture, holdMs, tag)
        edited()
    }

    /** What follows every change that reaches the history. */
    private fun edited() {
        publishHistory()
        // Editing the bare clip while its saved edit is still on offer is the
        // answer to the offer: this is a new project. That is settled by the
        // save, which covers every kind of change - see persist - and is asked
        // for now rather than at the next tick, so the offer goes the moment
        // the edit is made instead of standing a second longer over it.
        if (_state.value.recovery?.modal == false) {
            viewModelScope.launch(Dispatchers.IO) { saveNow() }
        }
    }

    /**
     * Files a result that finished in the background - a measurement, a match, a
     * beat grid - as an undo step of its own.
     *
     * Through [record] it closed whatever gesture was open, so a slider being
     * dragged when Stabilize finished became two steps, one either side of it.
     * Here the result goes beneath a gesture still moving, which needs it as a
     * change to a recorded state, [edit], rather than to the screen. [alongside]
     * is what else changes on screen and is not part of the edit: the card that
     * reports the result.
     */
    private fun recordLate(
        label: String,
        edit: (EditSnapshot) -> EditSnapshot,
        alongside: (EditorUiState) -> EditorUiState = { it }
    ) {
        val before = _state.value.editSnapshot
        val after = edit(before)
        _state.update { alongside(if (after == before) it else it.restoring(after)) }
        if (after == before) return
        history.recordBeneathOpen(label, before, System.currentTimeMillis(), edit)
        edited()
        recomputeEstimate()
    }

    /**
     * The finger lifted - off a slider, a handle. The next change to the same
     * thing is a new step, however soon it comes.
     */
    fun endGesture() = history.endGesture()

    private fun publishHistory() = _state.update {
        it.copy(undoLabel = history.undoLabel?.let(::shownLabel), redoLabel = history.redoLabel?.let(::shownLabel))
    }

    /**
     * An undo step as the button says it. Steps used to carry the id of what they
     * changed, to keep one clip's drag apart from another's, and the button
     * printed it: "Undo: Move 16ad0793-d41b-…". Gesture ids do that job now; this
     * still tidies any label that carries an id.
     */
    private fun shownLabel(label: String): String {
        val verb = label.replace(ID_SUFFIX, "")
        return when (verb) {
            "Move" -> "Move clip"
            "Trim" -> "Trim clip"
            "Effect" -> "Effect change"
            "Style" -> "Text style"
            else -> verb
        }
    }

    /**
     * Undoing the auto-caption run itself stops it, since there is no longer a
     * step for its lines to join. Undoing anything else lets it carry on: the
     * lines it has made are written into every state the history holds (see
     * [TextEdits.landAutoCaption]), so they survive the undo and the redo. Every undo used
     * to stop the run, and nudging a slider during a long pass then undoing the
     * nudge threw away the rest of the pass.
     */
    fun undo() {
        text.stopIfUndoingRun()
        val restored = history.undo(_state.value.editSnapshot) ?: return
        _state.update { it.restoring(restored) }
        publishHistory()
        recomputeEstimate()
    }

    fun redo() {
        val restored = history.redo(_state.value.editSnapshot) ?: return
        _state.update { it.restoring(restored) }
        publishHistory()
        recomputeEstimate()
    }

    /**
     * Runs a timeline operation over everything on the strip - picture and sound -
     * and files the result back into whichever list owns each clip.
     *
     * The previous version built its TimelineState from the video clips alone, so
     * split, delete and close-gaps silently did nothing to audio no matter what was
     * selected. One list in, one list out, split by kind: a sound is now trimmed and
     * cut by exactly the same code that trims and cuts a shot.
     */
    private fun mutateTimeline(block: (TimelineState) -> TimelineState) {
        _state.update { current ->
            val timeline = TimelineState(
                clips = current.videoClips + current.audioClips,
                selectedClipId = current.selectedClipId,
                playheadMs = current.playheadMs
            )
            val next = block(timeline)
            val video = next.clips.filter { it.kind == ClipKind.Video }
            // Every change to how long the picture runs comes through here -
            // trims, cuts, deletes, retimes, transitions, layer changes - so this
            // is where effects are kept inside it. See [fittedTo]. Within one
            // gesture they are fitted from where they were when it began, so a
            // tail dragged in and back out in one go brings back the effect it
            // passed over instead of leaving it cut short. Only while nothing else
            // has touched the effects since: a list that is not the one this left
            // is someone else's edit, and is fitted as it stands.
            val continuing = gestureContinues && recordingGesture == fitLabel && current.effects === fitResult
            val base = if (continuing) fitBase ?: current.effects else current.effects
            val fitted = base.fittedTo(video.maxOfOrNull { it.timelineEndMs } ?: 0L)
            fitLabel = recordingGesture
            fitBase = base
            fitResult = fitted
            val edited = current.copy(
                videoClips = video,
                audioClips = next.clips.filter { it.kind == ClipKind.Audio },
                selectedClipId = next.selectedClipId,
                effects = fitted
            )
            // An edit that leaves the edit shorter than where the playhead was -
            // the last shot deleted with the playhead at the end - brings the
            // playhead back to the new end, as a jump the preview follows. Left
            // past it, the strip, which is centred on the playhead, showed empty
            // track, and the first drag of it leapt to the end.
            val end = edited.timelineDurationMs
            if (edited.playheadMs <= end) edited
            else edited.copy(playheadMs = end, scrubNonce = edited.scrubNonce + 1)
        }
        recomputeEstimate()
    }

    /**
     * The gesture of the edit being made, while [record] is making it, and
     * whether that push carries on the undo step the gesture already opened.
     */
    private var recordingGesture: String? = null
    private var gestureContinues = false

    /** The gesture that last fitted the effects, what it began with, and what fitting them last produced. See [mutateTimeline]. */
    private var fitLabel: String? = null
    private var fitBase: List<TimedEffect>? = null
    private var fitResult: List<TimedEffect>? = null

    /** The id on the end of an undo label - " 16ad0793-d41b-…", or a template's " tpl-…". */
    private val ID_SUFFIX = Regex(""" \S*[0-9a-f]{8}-[0-9a-f]{4}-\S*$""")

    private fun recomputeEstimate() {
        _state.update { it.copy(estimatedOutputBytes = it.estimatedExportBytes) }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    fun clearFailure() = _state.update { it.copy(failure = null) }

    fun export(onResult: (String) -> Unit) {
        val current = _state.value
        val sourceUri = current.sourceUri ?: run {
            _state.update { it.copy(failure = SquishError.FileUnreadable()) }
            return
        }

        // Checked before a single frame is encoded. A two-minute export that dies
        // on the last chunk for want of disk space is the worst possible way to
        // learn about it.
        SquishError.preflight(getApplication(), current, current.estimatedOutputBytes)?.let { problem ->
            _state.update { it.copy(failure = problem) }
            return
        }

        _state.update {
            it.copy(isExporting = true, failure = null, exportProgress = ExportProgress(stage = ExportStage.Preparing))
        }

        exportJob = viewModelScope.launch {
            // The edit as it is goes to disk before the encode starts, not a tick
            // later: a long export is exactly when the app is most likely to be
            // sent to the back and killed, and a nudge made a second before
            // Render is the edit the file was made from.
            withContext(Dispatchers.IO) { persist() }

            // Every sound opened and asked about before a frame is encoded: one no
            // decoder takes used to fail minutes in, as a generic sound error.
            SquishError.checkSounds(getApplication(), current)?.let { problem ->
                if (exportJob === coroutineContext[Job]) exportJob = null
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress(), failure = problem) }
                return@launch
            }

            val outputDir = SquishError.exportsDir(getApplication()).apply { mkdirs() }
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.mp4")

            // Built at the size the encoder will write, asked now rather than
            // trusted from the sheet: the answer there may be to a size chosen
            // since. The record below says the same size.
            val asked = current.outputResolution
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked) }
            val rendering = current.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written))

            val result = processor.export(SquishError.exportable(rendering), outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
            }
            // From here the file exists and is being handed over; there is
            // nothing left to stop. See cancelExport.
            if (exportJob === coroutineContext[Job]) exportJob = null

            result.onSuccess { file ->
                // Still exporting until the copy is in the gallery. The sheet used
                // to flip back to "Render and save" the moment the encode ended,
                // while a multi-gigabyte copy ran behind it - and a second tap
                // started a second export over the first one's hand-over.
                _state.update { it.copy(exportProgress = it.exportProgress.copy(stage = ExportStage.Saving)) }
                // All or nothing: into the gallery, into history, and the draft
                // stamped. Cancelled half-way - the screen leaving in the instant
                // after the encode - the file was in the gallery and the draft
                // never knew it had been exported.
                withContext(NonCancellable) {
                    val published = GallerySaver.publish(getApplication(), file)
                    val written = rendering.writtenResolution
                    historyRepository.add(
                        ExportRecord(
                            id = UUID.randomUUID().toString(),
                            title = current.projectName ?: displayNameOf(sourceUri) ?: "Squished video",
                            outputPath = file.absolutePath,
                            originalSizeBytes = current.originalSizeBytes,
                            outputSizeBytes = file.length(),
                            durationMs = current.trimmedDurationMs,
                            // The shape of the file that was written, which after a
                            // rotation is not the shape it was shot at, and after a
                            // crop is the crop's. The library sizes its preview from
                            // these, so a rotated export previewed in the wrong shape.
                            width = if (written.width > 0) written.width else current.framedWidth,
                            height = if (written.height > 0) written.height else current.framedHeight,
                            createdAtMillis = System.currentTimeMillis(),
                            savedToGallery = published != null,
                            galleryUri = published?.toString()
                        )
                    )
                    // The edit stays, marked as exported. It used to be deleted here
                    // on the grounds that the work had reached the gallery - which
                    // made a test render to check a look the one action that could
                    // never be followed by "and now one more change".
                    withContext(Dispatchers.IO) {
                        // Whatever was changed during the render is saved as any
                        // change is; persist knows what to do with an offer.
                        persist()
                        // Stamped only when no saved edit was on offer, then or
                        // now: what was rendered under an offer is the bare clip,
                        // and the stamp would land on the other edit's draft - or,
                        // if the offer was retired by a change made during the
                        // render, on a draft that is not what was rendered.
                        if (current.recovery == null && _state.value.recovery == null) {
                            autosave.markCompleted(current)
                        }
                    }
                }
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                _state.update {
                    it.copy(isExporting = false, exportProgress = ExportProgress(), failure = SquishError.from(throwable))
                }
            }
        }
    }

    /**
     * The running export, so back can stop it rather than abandon it. Cleared
     * the moment the encode returns, before the file is published.
     */
    private var exportJob: Job? = null

    /**
     * Stops an export part-way. The encoder is cancelled through the coroutine
     * and the half-written file is removed by the processor; the edit itself is
     * untouched, so it can simply be rendered again.
     *
     * Nothing once the encode has finished. "Stop exporting?" can still be on
     * screen when it does, and a Stop tapped then used to cancel the hand-over
     * instead: the file was already in the gallery and in history, while the
     * editor stayed put as if the export had been stopped.
     *
     * False when there was nothing to stop, so the question can stay up and
     * say the copy is under way instead of closing as if it had worked.
     */
    fun cancelExport(): Boolean {
        val job = exportJob ?: return false
        exportJob = null
        job.cancel()
        _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
        return true
    }

    // ---- Crash recovery -------------------------------------------------------

    /**
     * Surfaces a saved session, without applying it. The offer stands until it is
     * accepted or dismissed; a snapshot for a clip that is not the one being opened
     * is left on disk untouched so it re-attaches when that clip comes back.
     */
    private fun offerRecovery(snapshot: ProjectSnapshot?, openedUri: Uri) {
        if (snapshot == null) return

        // An untouched clip is not work, and offering to restore it would only
        // teach the user to dismiss this banner without reading it.
        if (snapshot.isTrivial) return

        // A snapshot whose media is gone is worth nothing to restore, so only a
        // readable source ever produces an offer for a different clip. The clip
        // being opened has just been probed, so it is readable by definition.
        val sameClip = snapshot.sourceUri == openedUri
        if (!sameClip && !canRead(snapshot.sourceUri)) return

        // After the app was killed under this very edit, the offer has to be
        // answered before anything else: the person was in the middle of it, and
        // an inline card under a live timeline let them edit the bare clip for
        // as long as they liked with nothing being saved, then lose it all to
        // "Continue".
        val modal = restoredAfterDeath && sameClip
        _state.update { it.copy(recovery = RecoveryOffer(snapshot, modal = modal)) }
    }

    /**
     * "Start a new project": the saved edit is set aside in the bin and the
     * editor carries on with the untouched clip. Nothing is deleted; the drafts
     * screen can bring it back for a month.
     *
     * The move is made before the offer is withdrawn, under the lock the saves
     * take, so no save can land the new session on the old document first. It
     * is a handful of renames.
     */
    fun dismissRecovery() {
        synchronized(slotLock) {
            val snapshot = _state.value.recovery?.snapshot ?: return
            autosave.clear(snapshot.sourceUri)
            // Chosen, not stumbled into: undoing back to the bare clip does not
            // bring this one back the way it does after an accidental edit.
            setAside = null
            _state.update { it.copy(recovery = null, setAsideNotice = false) }
        }
    }

    fun acceptRecovery() {
        val snapshot = synchronized(slotLock) {
            // Gone already if a save answered the offer a moment ago; the notice
            // it put up says where the edit went.
            val offered = _state.value.recovery?.snapshot ?: return
            _state.update { it.copy(recovery = null, setAsideNotice = false, isLoadingSource = true) }
            offered
        }

        viewModelScope.launch {
            // Metadata is re-probed rather than trusted from the file: the same clip
            // can come back through a different provider with a different rotation.
            val meta = ThumbnailExtractor.probe(getApplication(), snapshot.sourceUri)
            loadedUri = snapshot.sourceUri
            _state.update {
                it.applying(snapshot, meta.durationMs, meta.displayWidth, meta.displayHeight, meta.fps)
                    // Drafts saved before effects were fitted can carry some
                    // running far past the end; tidy those on the way in.
                    .let { s -> s.copy(effects = s.effects.fittedTo(s.videoClips.maxOfOrNull { c -> c.timelineEndMs } ?: 0L)) }
            }
            recomputeEstimate()
            val files = (snapshot.clips.mapNotNull { it.uri } + snapshot.sourceUri).distinct()
            ensureProxies(files)
            confirmSourceAudio(snapshot.sourceUri)
            reportMissingLayers(snapshot.clips)
            files.filterNot(StillClips::isStill).forEach(::checkDecodable)
            restoreAudioWaveforms(snapshot.audioClips)
        }
    }

    /**
     * A restored edit whose picture files are not all readable any more - a grant
     * that did not survive, a file deleted from the gallery - says which one, by
     * name, the moment it is restored. The offer only checked the file first
     * opened, so a missing overlay came back as a row with a clip on it, a black
     * layer in the preview and an export that failed when it got there (O10).
     */
    private suspend fun reportMissingLayers(clips: List<Clip>) {
        val missing = withContext(Dispatchers.IO) {
            clips.filter { it.kind == ClipKind.Video }
                .distinctBy { it.uri }
                .firstOrNull { clip -> clip.uri?.let { !canRead(it) } == true }
        } ?: return
        _state.update {
            it.copy(
                failure = if (missing.isOverlay) SquishError.LayerUnreadable(missing.label, missing.layer)
                else SquishError.FileUnreadable(name = missing.label.takeIf { l -> l.isNotBlank() })
            )
        }
    }

    /** Redraws the lanes of a recovered edit without blocking the restore on it. */
    private suspend fun restoreAudioWaveforms(clips: List<Clip>) {
        clips.mapNotNull { it.uri }.distinct().forEach { uri ->
            if (_state.value.audioWaveforms[uri.toString()] != null) return@forEach
            val pcm = PcmDecoder.decodeMono(getApplication(), uri, maxDurationMs = 10 * 60_000L) ?: return@forEach
            val wave = WaveformBuilder.build(pcm)
            _state.update { it.copy(audioWaveforms = it.audioWaveforms + (uri.toString() to wave)) }
        }
    }

    private fun EditorUiState.applying(
        snapshot: ProjectSnapshot,
        durationMs: Long,
        width: Int,
        height: Int,
        fps: Float
    ): EditorUiState = copy(
        sourceUri = snapshot.sourceUri,
        projectName = snapshot.name,
        isLoadingSource = false,
        fitNonce = fitNonce + 1,
        durationMs = durationMs,
        sourceWidth = width,
        sourceHeight = height,
        fps = fps,
        trimStartMs = 0L,
        trimEndMs = durationMs,
        videoClips = snapshot.clips,
        textOverlays = snapshot.textOverlays,
        effects = snapshot.effects,
        reframe = snapshot.reframe,
        markers = snapshot.markers,
        // Saved at the very end, it would reopen on "no clip here"; the start is more useful.
        playheadMs = snapshot.playheadMs.takeIf { it < snapshot.totalDurationMs } ?: 0L,
        outputP = snapshot.outputP,
        fitToSize = snapshot.fitToSize,
        targetSizeMb = snapshot.targetSizeMb,
        audioOnly = snapshot.audioOnly,
        muteOriginal = snapshot.muteOriginal,
        voiceEffect = snapshot.voiceEffect,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        cropRect = snapshot.cropRect,
        snapToMarkers = snapshot.snapToMarkers,
        stabilizeStrength = snapshot.stabilizeStrength,
        beats = snapshot.beats,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation,
        lookId = snapshot.lookId,
        lookIntensity = snapshot.lookIntensity,
        pixelsPerSecond = snapshot.pixelsPerSecond,
        audioClips = snapshot.audioClips,
        selectedClipId = null,
        failure = null
    )

    private fun canRead(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    // ---- Proxy media ----------------------------------------------------------

    /** Files waiting to be looked at for a stand-in, in the order they arrived. Main thread only. */
    private val proxyQueue = ArrayDeque<Uri>()

    /**
     * Heavy footage gets a 540p stand-in for the preview player while the export
     * pipeline keeps reading the original. Everything at or below 1080p skips this
     * entirely - it already scrubs smoothly, and transcoding it would cost more
     * time than it ever saves.
     *
     * Every video file in the edit, main track and overlay rows alike - only the
     * file first opened used to get one (P8). Built one at a time, in the order
     * the files came: each build is an encoder session, and two at once beside
     * a playing preview is two sessions too many. A file is looked at once per
     * editor; a photo kept as a picture is never looked at.
     */
    private fun ensureProxies(uris: Collection<Uri>) {
        val known = _state.value.proxyStatuses
        uris.distinct()
            .filter { it !in known && it !in proxyQueue && !StillClips.isStill(it) }
            .forEach { proxyQueue.addLast(it) }
        if (proxyQueue.isEmpty() || proxyJob?.isActive == true) return
        proxyJob = viewModelScope.launch {
            while (true) {
                val uri = proxyQueue.removeFirstOrNull() ?: break
                if (uri in _state.value.proxyStatuses) continue
                buildProxy(uri)
            }
        }
    }

    private suspend fun buildProxy(uri: Uri) {
        val app = getApplication<Application>()
        val meta = ThumbnailExtractor.probe(app, uri)
        fun status(value: ProxyStatus, proxy: Uri? = null) = _state.update {
            it.copy(
                proxyStatuses = it.proxyStatuses + (uri to value),
                proxyUris = if (proxy != null) it.proxyUris + (uri to proxy) else it.proxyUris - uri
            )
        }
        if (!ProxyEngine.isWorthProxying(meta.displayWidth, meta.displayHeight, meta.durationMs)) {
            status(ProxyStatus.NotNeeded)
            return
        }
        ProxyEngine.cached(app, uri)?.let { file ->
            status(ProxyStatus.Ready, Uri.fromFile(file))
            return
        }
        status(ProxyStatus.Building)
        val file = ProxyEngine.ensure(app, uri)
        // Not worth a dialogue when it fails: the original still plays, just heavier.
        if (file != null) status(ProxyStatus.Ready, Uri.fromFile(file)) else status(ProxyStatus.Failed)
    }

    /**
     * The filmstrip's thumbnails belong to the project that was open, not to the
     * app. Holding twelve megabytes of frames from a video the user has finished
     * with is exactly the kind of quiet growth that turns into a crash on the
     * next big import.
     */
    override fun onCleared() {
        super.onCleared()
        // The last edit, before the ticker that would have saved it is gone.
        // Blocking, on purpose: viewModelScope is already cancelled here, and a
        // write handed to another thread has no guarantee of running before the
        // process that asked for it is killed.
        saveNow()
        loadedUri?.let(OpenEditors::closed)
        // The collector above is cancelled with the scope, so a screen cleared
        // mid-export says so itself.
        ExportsInFlight.set(this, false)
        FilmstripLoader.evictAll()
    }

    private companion object {
        /** Saved-state key: this entry has opened its clip. See [restoredAfterDeath]. */
        const val KEY_OPENED = "opened"
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
    }
}
