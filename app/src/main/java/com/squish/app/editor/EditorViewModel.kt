package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ProjectRules
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectSnapshot
import com.squish.app.data.ProjectStart
import com.squish.app.data.SquishRepositories
import com.squish.app.media.importCopy
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.withTransition
import com.squish.app.media.EncoderCeiling
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportService
import com.squish.app.media.ExportSettings
import com.squish.app.media.ExportStage
import com.squish.app.settings.Preferences
import com.squish.app.settings.withExportDefaults
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
import com.squish.app.media.video.Segmenter
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.ZOOM_MAX
import com.squish.app.timeline.ZOOM_MIN
import com.squish.app.timeline.withSelectionJoined
import com.squish.app.timeline.withSelectionToggled
import com.squish.app.timeline.withOverlayTransitionsFitted
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val processor = VideoProcessor(application)
    private val historyRepository = SquishRepositories.history(application)
    private val autosave = SquishRepositories.autosave(application)

    /** The project this editor is open on, once [open] has been called. */
    private var openedId: String? = null

    /** Opened by "Open with" or a share (ProjectStart.openedFromOutside); see onCleared. */
    private var openedJustToLook = false
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
        override fun selectClip(clipId: String?) = this@EditorViewModel.selectClip(clipId)
        override fun joinSelection(clipId: String) = this@EditorViewModel.joinSelection(clipId)
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
     * Held by every save. The ticker saves on IO and a flush on leaving saves
     * on Main, and two of them interleaved could rename over each other.
     */
    private val slotLock = Any()

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
     * The save itself, behind [saveNow] and the export's own flushes. A
     * project is a draft from the moment it is made, so this is a plain save
     * of whatever the edit is: the untouched-clip rule, the offer of a saved
     * edit and the bin dance around them went with projects keyed by video.
     */
    private fun persist(): Unit = synchronized(slotLock) {
        val current = _state.value
        if (current.projectId.isBlank() || current.isLoadingSource) return
        autosave.save(current)
    }

    /**
     * Names the project, or with a blank name un-names it. Saved at once rather
     * than at the next tick: the rename dialog is often the last thing done
     * before leaving.
     */
    fun renameProject(raw: String) {
        val name = ProjectName.clean(raw)
        if (name == _state.value.projectName) return
        _state.update { it.copy(projectName = name) }
        viewModelScope.launch(Dispatchers.IO) { saveNow() }
    }

    /**
     * Opens a project: its saved edit when it has one, otherwise the files it
     * was staged to start from (ProjectAutosave.stageStart) - a project just
     * made on the dashboard, handed over by "Open with", or sent from a quick
     * tool. Neither on disk means the project is gone, and the editor says so
     * rather than sitting empty.
     */
    fun open(projectId: String) {
        if (openedId == projectId) return
        openedId = projectId
        _state.update { it.copy(projectId = projectId, isLoadingSource = true) }

        viewModelScope.launch {
            // Off the main thread: a draft with a few thousand motion samples
            // in it was a visible hitch on entry.
            // When it was started, which an unnamed project is called by (ProjectRules.displayTitle).
            val startedAt = withContext(Dispatchers.IO) { autosave.startedAt(projectId) }
            _state.update { it.copy(startedAtMillis = startedAt.takeIf { at -> at > 0L } ?: System.currentTimeMillis()) }
            val draft = withContext(Dispatchers.IO) { autosave.peek(projectId) }
            if (draft != null) {
                applyDraft(draft)
                return@launch
            }
            val start = withContext(Dispatchers.IO) { autosave.peekStart(projectId) }
            if (start == null || start.uris.isEmpty()) {
                _state.update { it.copy(isLoadingSource = false, failure = SquishError.FileUnreadable()) }
                return@launch
            }
            openedJustToLook = start.openedFromOutside
            loadFresh(start)
        }
    }

    /**
     * A new project on its files, laid end to end in the order they were
     * picked, photos as stills (StillClips) at the length Settings gives them,
     * with the join between each pair given the default transition and the
     * frame the default ratio - and saved at once, so the project is on the
     * dashboard from the moment it is made, and the files handed over with a
     * grant that ends with this process copied in first (MediaAccess.importCopy).
     */
    private suspend fun loadFresh(start: ProjectStart) {
        val app = getApplication<Application>()
        val defaults = Preferences.editorDefaults(app)
        val stillMs = defaults.stillMs
        val resolver = app.contentResolver
        // The photos are counted first so the loading screen can say how many
        // are still to render, and the count falls as each lands.
        val images = start.uris.count { resolver.getType(it)?.startsWith("image/") == true }
        _state.update { it.copy(preparingStills = images) }
        val sources = try {
            start.uris.mapNotNull { picked ->
                // Named by the file handed over, not by its copy: a copy is kept under
                // a made-up name, and that was the project's title - "5ee44925-efb7...".
                val name = withContext(Dispatchers.IO) { displayNameOf(picked) }
                val uri = if (start.copyIn) withContext(Dispatchers.IO) { app.importCopy(picked) } else picked
                if (resolver.getType(uri)?.startsWith("image/") == true) {
                    val still = StillClips.fromImage(app, uri)
                    _state.update { it.copy(preparingStills = (it.preparingStills - 1).coerceAtLeast(0)) }
                    still?.let { Triple(it, name ?: displayNameOf(uri) ?: "Photo", true) }
                } else {
                    Triple(uri, name ?: displayNameOf(uri) ?: "Clip", false)
                }
            }
        } finally {
            _state.update { it.copy(preparingStills = 0) }
        }
        val probed = sources.map { (uri, label, still) -> Pair(Triple(uri, label, still), ThumbnailExtractor.probe(app, uri)) }
            .filter { (_, meta) -> meta.durationMs > 0L }
        val lead = probed.firstOrNull()
        if (lead == null) {
            // Every real video has a length. None means the file could not be read
            // - gone, or handed over without permission - and an empty editor with
            // nothing said is the worst way to learn that.
            _state.update { it.copy(isLoadingSource = false, failure = SquishError.FileUnreadable()) }
            return
        }
        val (leadSource, leadMeta) = lead
        val originalSize = runCatching {
            resolver.openFileDescriptor(leadSource.first, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
        var cursor = 0L
        val clips = probed.map { (source, meta) ->
            val (uri, label, still) = source
            val placed = if (still) minOf(stillMs, meta.durationMs) else meta.durationMs
            Clip(
                kind = ClipKind.Video,
                uri = uri,
                label = label,
                sourceInMs = 0,
                sourceOutMs = placed,
                timelineStartMs = cursor,
                sourceDurationMs = meta.durationMs
            ).also { cursor += placed }
        }
        _state.update {
            it.copy(
                sourceUri = leadSource.first,
                durationMs = leadMeta.durationMs,
                // The shape the picture is seen in, not the shape it is stored
                // in. A portrait clip is a 1920x1080 stream with a rotation tag;
                // taking the stored numbers made the preview box landscape and
                // letterboxed the export into a landscape frame.
                sourceWidth = leadMeta.displayWidth,
                sourceHeight = leadMeta.displayHeight,
                sourceHasAudio = leadMeta.hasAudio,
                fps = leadMeta.fps,
                trimStartMs = 0L,
                trimEndMs = leadMeta.durationMs,
                sourceName = leadSource.second,
                isLoadingSource = false,
                // Fitted the moment the clip is known. At the default zoom a
                // ten-minute video is twenty-five thousand dp of strip, so it
                // opened somewhere off the right-hand edge and stayed there.
                fitNonce = it.fitNonce + 1,
                originalSizeBytes = originalSize,
                cropAspect = defaults.cropAspect,
                videoClips = clips
                // What the last export was set to, as this project's starting
                // point. A draft brings its own.
            ).let { fresh -> Preferences.exportDefaults(app)?.let { fresh.withExportDefaults(it) } ?: fresh }
        }
        // The default transition on every join, through the model's own fitting
        // so a short still is never asked to overlap more than it has.
        if (defaults.transition != TransitionType.None) {
            clips.drop(1).forEach { clip ->
                mutateTimeline { it.withTransition(clip.id, Transition(defaults.transition, defaults.transitionMs)) }
            }
        }
        // Not an undo step: this is where the project begins.
        history.clear()
        publishHistory()
        recomputeEstimate()
        // Whether this phone writes HEVC is asked now, not when the sheet
        // first opens: a remembered "Smaller file" rendered before the codec
        // list had come back was written in H.264 without a word said.
        probeCodecs()
        withContext(Dispatchers.IO) { persist() }
        if (probed.size < sources.size || sources.size < start.uris.size) {
            _state.update { it.copy(failure = SquishError.FileUnreadable()) }
        }
        markOpened()
        val files = clips.mapNotNull { it.uri }.distinct()
        files.filterNot(StillClips::isStill).forEach(::checkDecodable)
        ensureProxies(files)
        confirmSourceAudio(leadSource.first)
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

    private var showJob: Job? = null

    /**
     * A join's transition, played once with a little either side. Read after
     * the change: the incoming shot starts earlier by the overlap it brings.
     */
    fun showJoin(clipId: String) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        val length = if (clip.transitionIn.isActive) clip.transitionIn.durationMs else 0L
        if (length <= 0L) return
        val from = (clip.timelineStartMs - SHOW_AROUND_MS).coerceAtLeast(0L)
        showMoment(from, clip.timelineStartMs + length + SHOW_AROUND_MS - from)
    }

    /**
     * Plays [forMs] from [fromMs] and stops: what a picked arrival, leaving or
     * loop looks like, shown once as CapCut shows it. Picked with the playhead
     * past the arrival, the picture did not change and the tap seemed to do
     * nothing. Left playing if anything else asked for play or pause meanwhile.
     */
    fun showMoment(fromMs: Long, forMs: Long) {
        _state.update {
            it.copy(
                playheadMs = fromMs.coerceIn(0L, it.timelineDurationMs),
                scrubNonce = it.scrubNonce + 1,
                transportRequest = TransportRequest(play = true, nonce = (it.transportRequest?.nonce ?: 0L) + 1)
            )
        }
        val mine = _state.value.transportRequest?.nonce
        showJob?.cancel()
        val endMs = _state.value.playheadMs + forMs.coerceAtLeast(0L)
        showJob = viewModelScope.launch {
            // Stopped by where the playhead is, not by a timer: the seek and the
            // start take half a second of their own, and timed from the tap a
            // 0.4 s arrival was stopped a quarter of the way through.
            withTimeoutOrNull(forMs + SHOW_START_SLACK_MS) {
                // Just past the end, not anywhere past it: until the seek lands the
                // preview still reports where the playhead was, which may be later.
                _state.first { it.playheadMs in endMs..endMs + 1_500L || it.transportRequest?.nonce != mine }
            }
            _state.update {
                if (it.transportRequest?.nonce != mine) it
                else it.copy(transportRequest = TransportRequest(play = false, nonce = (mine ?: 0L) + 1))
            }
        }
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
        // A fresh limit starts from the plain budget; the tightening was for the last one.
        _state.update { it.copy(targetSizeMb = mb, fitScale = 1f) }
        recomputeEstimate()
    }

    fun setOutputFps(fps: Int) {
        _state.update { it.copy(outputFps = fps) }
        recomputeEstimate()
    }

    fun setQuality(quality: ExportQuality) {
        _state.update { it.copy(quality = quality) }
        recomputeEstimate()
    }

    fun setHevc(enabled: Boolean) {
        _state.update { it.copy(hevc = enabled) }
        recomputeEstimate()
        // The HEVC encoder may stop at a different size from the H.264 one.
        probeEncoder()
    }

    fun setKeepHdr(enabled: Boolean) {
        _state.update { it.copy(keepHdr = enabled) }
        recomputeEstimate()
        probeEncoder()
    }

    fun setAudioOnly(enabled: Boolean) {
        _state.update { it.copy(audioOnly = enabled) }
        recomputeEstimate()
    }

    /** Which size is being asked about, and which answer to keep; see [ProbeGate]. */
    private val probes = ProbeGate<Pair<ExportPresets.Resolution, String>> { (asked, mime) ->
        _state.value.encoderAnswer?.asked == asked && probedMime == mime
    }

    /** The codec the kept encoder answer was asked about, since an HEVC encoder can stop at a different size. */
    private var probedMime: String? = null

    /** The frame shape the ceiling was measured for, so it is measured once per shape and codec. */
    private var ceilingFor: Pair<ExportPresets.Resolution, String>? = null

    /**
     * Asks the phone's encoder what it will write for the size now chosen, so
     * the sheet can say so. Off the main thread: it opens the codec list. An
     * answer for a size no longer chosen is dropped - the size chosen since was
     * asked about when it was chosen, and its own answer is on its way.
     *
     * The same call measures the encoder's ceiling for the edit's shape once,
     * so the sheet can grey the sizes above it, and asks once whether the
     * phone has an HEVC encoder at all, so the sheet knows whether to offer it.
     */
    fun probeEncoder() {
        val current = _state.value
        if (current.hevcAvailable == null) probeCodecs()
        val mime = EncoderCeiling.mimeFor(current.exportCodecHevc)
        val shape = current.resolutionAt(OutputSize.MAX_P)
        if (shape.width > 0 && shape.height > 0 && ceilingFor != shape to mime) {
            ceilingFor = shape to mime
            viewModelScope.launch {
                val ceiling = withContext(Dispatchers.IO) { EncoderCeiling.ceilingShortEdge(shape, mime) }
                if (ceilingFor == shape to mime) _state.update { it.copy(encoderCeilingP = ceiling) }
            }
        }
        val asked = current.outputResolution
        if (!probes.ask(asked to mime)) return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked, mime) }
            val now = _state.value
            if (!probes.keep(asked to mime, now.outputResolution to EncoderCeiling.mimeFor(now.exportCodecHevc))) return@launch
            probedMime = mime
            _state.update { it.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written)) }
            recomputeEstimate()
        }
    }

    /** Whether this phone can write HEVC at all, asked once; the sheet offers the toggle only then. */
    private fun probeCodecs() {
        if (codecsProbed) return
        codecsProbed = true
        viewModelScope.launch {
            val hevc = withContext(Dispatchers.IO) { EncoderCeiling.hasEncoder(EncoderCeiling.mimeFor(hevc = true)) }
            _state.update { it.copy(hevcAvailable = hevc) }
            recomputeEstimate()
        }
    }

    private var codecsProbed = false

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
    fun selectClip(clipId: String?) = _state.update { current ->
        // A plain selection is one thing: whatever was selected alongside goes,
        // and Select more is off until it is asked for again.
        current.copy(selectedClipId = clipId, selectedClipIds = emptySet(), selectingMore = false)
            .replacingOnlyIfSelected()
    }

    /**
     * A tap on the strip while Select more is on: the clip joins the selection
     * or leaves it (TimelineState.withSelectionToggled). Bare track lets go of
     * the whole selection, as it always has. Not an undo step.
     */
    fun toggleSelected(clipId: String?) {
        if (clipId == null) {
            selectClip(null)
            return
        }
        _state.update { current ->
            val toggled = current.toTimeline().withSelectionToggled(clipId)
            current.copy(
                selectedClipId = toggled.selectedClipId,
                selectedClipIds = toggled.selectedIds,
                // Everything let go of one by one turns the mode off with the last.
                selectingMore = toggled.selectedClipId != null
            ).replacingOnlyIfSelected()
        }
    }

    /**
     * A clip taken hold of, or an overlay tapped on the picture, while Select
     * more is on: it joins the selection if it was not in it, and leads it
     * either way (TimelineState.withSelectionJoined). Never leaves it - that
     * is a tap on the strip. A tap on a PiP to see which of three selected it
     * was went through the plain selection and let the other two go.
     */
    fun joinSelection(clipId: String) = _state.update { current ->
        val joined = current.toTimeline().withSelectionJoined(clipId)
        current.copy(selectedClipId = joined.selectedClipId, selectedClipIds = joined.selectedIds).replacingOnlyIfSelected()
    }

    /**
     * Select more on: from here taps add to the selection. Off again - the
     * button reads "Done selecting" while it is on - ends the adding and keeps
     * what was selected: the set is what Delete and a carry then act on. It
     * used to let go of all but the lead, so pressing the orange button to
     * finish threw away the taps that had just been made.
     */
    fun setSelectingMore(on: Boolean) = _state.update { current ->
        current.copy(selectingMore = on && current.selectedClipId != null)
    }

    /**
     * The Replace sheet is a question about one clip; it goes when that clip is
     * no longer the selection. It stayed up over the next shot tapped, and Done
     * then replaced the shot the sheet had been opened on, out of sight.
     */
    private fun EditorUiState.replacingOnlyIfSelected(): EditorUiState =
        if (replacing == null || replacing.clipId == selectedClipId) this else copy(replacing = null)

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
        refreshMissingMedia()
        publishHistory()
        recomputeEstimate()
    }

    fun redo() {
        val restored = history.redo(_state.value.editSnapshot) ?: return
        _state.update { it.restoring(restored) }
        refreshMissingMedia()
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
                selectedIds = current.selectedClipIds,
                playheadMs = current.playheadMs
            )
            // Every change to a clip's played length comes through here too, so
            // this is where its fades are kept inside it (AudioRules.withFittedFades):
            // a fade set on the whole song and left on a six-second sting of it
            // held the sting under forty percent from end to end.
            // And where an overlay's transition is kept only while its join is
            // (withOverlayTransitionsFitted): the tool that turns it off is only
            // offered on a join, so one left past the join could not be reached.
            val next = block(timeline)
                .let { t -> t.copy(clips = t.clips.map(AudioRules::withFittedFades)) }
                .withOverlayTransitionsFitted()
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
            // A clip taken out of the edit leaves the set selected alongside too;
            // the lines and effects in the set live outside this state and stay.
            val kept = next.clips.map { it.id }.toSet() + current.textOverlays.map { it.id } + current.effects.map { it.id }
            val edited = current.copy(
                videoClips = video,
                audioClips = next.clips.filter { it.kind == ClipKind.Audio },
                selectedClipId = next.selectedClipId,
                selectedClipIds = (next.selectedIds intersect kept) - setOfNotNull(next.selectedClipId),
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

    fun export(onResult: (String) -> Unit) = export(onResult, tightened = false)

    /**
     * [tightened] is the second run after a fitted export missed its limit
     * (see [retryFit]): the one run that keeps the pulled-down fit scale.
     * Every other render starts from the plain budget - the scale used to
     * outlive the run it was measured on, so an edit cut down to a quarter of
     * its length was still rendered a fifth under what it was allowed.
     */
    private fun export(onResult: (String) -> Unit, tightened: Boolean) {
        if (!tightened && _state.value.fitScale != 1f) {
            _state.update { it.copy(fitScale = 1f) }
            recomputeEstimate()
        }
        var current = _state.value
        val sourceUri = current.sourceUri ?: run {
            _state.update { it.copy(failure = SquishError.FileUnreadable()) }
            return
        }

        // An edit with every shot deleted has no picture of its own. The render
        // reads an empty track as the quick tools' one file, and would have
        // written the project's first source whole - a file nobody had on the
        // strip. (Such a draft now reopens as itself rather than refusing.)
        if (current.videoClips.isEmpty()) {
            _state.update { it.copy(failure = SquishError.NothingToExport()) }
            return
        }

        // Checked before a single frame is encoded. A two-minute export that dies
        // on the last chunk for want of disk space is the worst possible way to
        // learn about it.
        SquishError.preflight(getApplication(), current, current.estimatedOutputBytes)?.let { problem ->
            _state.update { it.copy(failure = problem) }
            return
        }

        // The preview stops for the render: it played on audibly under the
        // progress card, its players decoding beside the encoder's own.
        audio.requestPause()
        _state.update {
            it.copy(isExporting = true, failure = null, fitOvershoot = null, exportProgress = ExportProgress(stage = ExportStage.Preparing))
        }
        // The render is carried by a foreground service from here until it is
        // handed over: with the screen locked, or the app behind a call, a
        // process with nothing in front is one Android may kill mid-encode,
        // and the notification is the one place the progress can be seen then.
        // The name on the project's card, not the first file's ("1001319240.jpg").
        val title = current.projectName ?: ProjectRules.displayTitle(current.videoClips.firstOrNull()?.label, current.startedAtMillis)
        ExportService.begin(getApplication(), title)

        exportJob = viewModelScope.launch {
            // The edit as it is goes to disk before the encode starts, not a tick
            // later: a long export is exactly when the app is most likely to be
            // sent to the back and killed, and a nudge made a second before
            // Render is the edit the file was made from.
            withContext(Dispatchers.IO) { persist() }

            // The codec is settled before anything is built on it. The probe
            // starts on opening the clip, but Render tapped before it answers
            // - a slow first enumeration of the codec list - used to write a
            // draft's "Smaller file" or "Keep HDR" as H.264 at H.264's rate.
            if (current.hevcAvailable == null) {
                val hevc = withContext(Dispatchers.IO) { EncoderCeiling.hasEncoder(EncoderCeiling.mimeFor(hevc = true)) }
                _state.update { it.copy(hevcAvailable = hevc) }
                current = current.copy(hevcAvailable = hevc)
                recomputeEstimate()
            }

            // Every sound opened and asked about before a frame is encoded: one no
            // decoder takes used to fail minutes in, as a generic sound error.
            SquishError.checkSounds(getApplication(), current)?.let { problem ->
                if (exportJob === coroutineContext[Job]) exportJob = null
                ExportService.end(getApplication())
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress(), failure = problem) }
                return@launch
            }

            val outputDir = SquishError.exportsDir(getApplication()).apply { mkdirs() }
            // A sound-only export is an .m4a, and lands in Music rather than the gallery.
            val extension = if (current.audioOnly) "m4a" else "mp4"
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.$extension")

            // Built at the size the encoder will write, asked now rather than
            // trusted from the sheet: the answer there may be to a size chosen
            // since. The record below says the same size.
            val asked = current.outputResolution
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked, EncoderCeiling.mimeFor(current.exportCodecHevc)) }
            val rendering = current.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written))

            val result = processor.export(SquishError.exportable(rendering), outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
                ExportService.update(progress)
            }
            // From here the file exists and is being handed over; there is
            // nothing left to stop. See cancelExport.
            if (exportJob === coroutineContext[Job]) exportJob = null

            result.onSuccess { file ->
                // Still exporting until the copy is in the gallery. The sheet used
                // to flip back to "Render and save" the moment the encode ended,
                // while a multi-gigabyte copy ran behind it - and a second tap
                // started a second export over the first one's hand-over.
                val saving = _state.value.exportProgress.copy(stage = ExportStage.Saving)
                _state.update { it.copy(exportProgress = saving) }
                ExportService.update(saving)
                // Measured now, before retire below deletes the private copy:
                // read after it, a fitted export that overshot measured as
                // nothing and was handed over as if it had fitted.
                val size = file.length()
                // All or nothing: into the gallery, into history, and the draft
                // stamped. Cancelled half-way - the screen leaving in the instant
                // after the encode - the file was in the gallery and the draft
                // never knew it had been exported.
                withContext(NonCancellable) {
                    val published = if (current.audioOnly) GallerySaver.publishAudio(getApplication(), file)
                    else GallerySaver.publish(getApplication(), file)
                    val written = rendering.writtenResolution
                    historyRepository.add(
                        ExportRecord(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            // A name the person gave is shown as it is in the library.
                            named = current.projectName != null,
                            outputPath = file.absolutePath,
                            originalSizeBytes = current.originalSizeBytes,
                            outputSizeBytes = size,
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
                        // change is, then the draft is stamped with what was rendered.
                        persist()
                        autosave.markCompleted(current)
                        // What was chosen here is the next project's starting point.
                        Preferences.rememberExport(getApplication(), current)
                        // The private copy goes once the gallery's is known whole;
                        // the library reads the gallery copy from here on.
                        GallerySaver.retire(getApplication(), file, published)
                    }
                }
                ExportService.end(getApplication())
                // A fitted export is measured against its limit. The file is
                // kept whatever the answer - it is in the gallery and the library
                // already - but one that missed is not handed over as if it had
                // fitted: the sheet says by how much and offers a tighter run.
                val target = current.targetSizeMb * 1_000_000L
                if (current.fitToSize && !current.audioOnly && ExportSettings.overshoots(size, target)) {
                    _state.update {
                        it.copy(
                            isExporting = false,
                            exportProgress = ExportProgress(),
                            fitOvershoot = FitOvershoot(file.absolutePath, size, target),
                            fitScale = ExportSettings.retryScale(it.fitScale, size, target)
                        )
                    }
                    recomputeEstimate()
                    return@onSuccess
                }
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                ExportService.end(getApplication())
                _state.update {
                    it.copy(isExporting = false, exportProgress = ExportProgress(), failure = SquishError.from(throwable))
                }
            }
        }
    }

    /** The oversize file is the one wanted after all: it is handed over as any export is. */
    fun keepOversize(onResult: (String) -> Unit) {
        val kept = _state.value.fitOvershoot ?: return
        _state.update { it.copy(fitOvershoot = null) }
        onResult(kept.path)
    }

    /** Runs the fitted export again at the tightened budget (see [FitOvershoot]); the oversize file stays in the library. */
    fun retryFit(onResult: (String) -> Unit) {
        _state.update { it.copy(fitOvershoot = null) }
        export(onResult, tightened = true)
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
        ExportService.end(getApplication())
        _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
        return true
    }

    // ---- Opening a saved edit -----------------------------------------------

    /** The project's saved edit, poured back into the editor. */
    private suspend fun applyDraft(snapshot: ProjectSnapshot) {
        // Metadata is re-probed rather than trusted from the file: the same clip
        // can come back through a different provider with a different rotation.
        // A source that cannot be read any more probes as nothing, and every
        // frame and crop is measured against nothing (framedWidth 0): the first
        // main-track shot that still reads stands in for its shape, so the
        // edit is laid out right while the missing file waits for Relink.
        val app = getApplication<Application>()
        val sourceMeta = ThumbnailExtractor.probe(app, snapshot.sourceUri).takeIf { it.durationMs > 0L }
        // What loadFresh reads off the source and a draft does not carry: its
        // name (the export's title when the project has none), its size (what
        // the bitrate is measured from - 0 fell back to the nominal rate, so a
        // reopened project estimated and wrote a different file) and whether
        // it has sound (a silent source read as having some, by default).
        // A file of the app's own - a copy of a shared video - is named by its
        // clip: the copy's own name is made up.
        val sourceLabel = snapshot.clips.firstOrNull { it.uri == snapshot.sourceUri }?.label
        val sourceName = if (snapshot.sourceUri.scheme == "file" && sourceLabel != null) sourceLabel
            else withContext(Dispatchers.IO) { displayNameOf(snapshot.sourceUri) } ?: sourceLabel
        val sourceSize = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openFileDescriptor(snapshot.sourceUri, "r")?.use { it.statSize } ?: 0L }
                .getOrDefault(0L)
        }
        val meta = sourceMeta
            ?: snapshot.clips.filter { it.isMain }.sortedBy { it.timelineStartMs }.mapNotNull { it.uri }.distinct()
                .filter { it != snapshot.sourceUri }
                .firstNotNullOfOrNull { uri -> ThumbnailExtractor.probe(app, uri).takeIf { it.durationMs > 0L } }
            ?: ThumbnailExtractor.probe(app, snapshot.sourceUri)
        _state.update {
            it.applying(snapshot, meta.durationMs, meta.displayWidth, meta.displayHeight, meta.fps)
                .copy(
                    sourceName = sourceName,
                    originalSizeBytes = sourceSize.coerceAtLeast(0L),
                    // A source that no longer reads is not known to have sound.
                    sourceHasAudio = sourceMeta?.hasAudio ?: false
                )
                // Drafts saved before effects were fitted can carry some
                // running far past the end; tidy those on the way in.
                .let { s -> s.copy(effects = s.effects.fittedTo(s.videoClips.maxOfOrNull { c -> c.timelineEndMs } ?: 0L)) }
        }
        recomputeEstimate()
        probeCodecs()
        val files = (snapshot.clips.mapNotNull { it.uri } + snapshot.sourceUri).distinct()
        ensureProxies(files)
        confirmSourceAudio(snapshot.sourceUri)
        reportMissingMedia(snapshot.clips + snapshot.audioClips)
        markOpened()
        files.filterNot(StillClips::isStill).forEach(::checkDecodable)
        restoreAudioWaveforms(snapshot.audioClips)
    }

    /**
     * Whether the project's edit is on screen yet, missing files named. A
     * picker's result can arrive before that: killed behind the photo picker,
     * the app comes back with a new view model still reading the draft when
     * the launcher hands the pick over, and a Replace (no clip selected yet)
     * or a Relink (no missing file named yet) was dropped without a word.
     * Such a pick waits here for the edit instead.
     */
    private var opened = false
    private val whenOpened = ArrayList<() -> Unit>()

    private fun markOpened() {
        opened = true
        val waiting = whenOpened.toList()
        whenOpened.clear()
        waiting.forEach { it() }
    }

    private fun onceOpened(action: () -> Unit) {
        if (opened) action() else whenOpened += action
    }

    /** A file picked to go under [clipId] (Replace), once the edit is open. */
    fun replaceWhenOpened(clipId: String, uri: Uri) = onceOpened { clips.beginReplace(clipId, uri) }

    /** A file picked for the missing one (Relink), once the edit is open and it is named. */
    fun relinkWhenOpened(uri: Uri) = onceOpened { relink(uri) }

    /**
     * An opened edit whose files are not all readable any more - a grant that
     * did not survive, a file deleted from the gallery - says which one, by
     * name, the moment it is opened, and names the file for Relink. Every clip
     * is checked, not only the one first opened: a missing overlay used to
     * come back as a row with a clip on it, a black layer in the preview and
     * an export that failed when it got there (O10). The clips stay on the
     * strip as placeholders, so the edit's shape is kept for the relink.
     */
    private suspend fun reportMissingMedia(clips: List<Clip>) {
        val missing = withContext(Dispatchers.IO) {
            clips.distinctBy { it.uri }.filter { clip -> clip.uri?.let { !canRead(it) } == true }
        }
        missing.mapNotNullTo(unreadable) { it.uri }
        val first = missing.firstOrNull() ?: return
        _state.update { it.copy(missingMedia = first.uri, failure = unreadableFailure(first)) }
    }

    /**
     * The files found unreadable since the project was opened. Undo and redo
     * decide from this whether the Relink card should stand (refreshMissingMedia)
     * without opening every file again; a file relinked and then undone is
     * missing again, and the card must come back with it.
     */
    private val unreadable = HashSet<Uri>()

    private fun unreadableFailure(missing: Clip): SquishError = when {
        missing.kind == ClipKind.Audio -> SquishError.SoundUnreadable(missing.label.ifBlank { "sound" }, relinkable = true)
        missing.isOverlay -> SquishError.LayerUnreadable(missing.label.ifBlank { "Overlay" }, missing.layer, relinkable = true)
        else -> SquishError.FileUnreadable(name = missing.label.takeIf { l -> l.isNotBlank() }, relinkable = true)
    }

    /**
     * The Relink card and its failure follow the clips through undo and redo.
     * Neither is part of the edit snapshot, so an undone Relink used to put the
     * unreadable file back under every clip with the card gone: the only way
     * to it again was to leave and reopen the project.
     */
    private fun refreshMissingMedia() = _state.update { current ->
        val missing = (current.videoClips + current.audioClips).firstOrNull { it.uri in unreadable }
        when {
            missing != null && current.missingMedia != missing.uri ->
                current.copy(missingMedia = missing.uri, failure = unreadableFailure(missing))
            missing == null && current.missingMedia != null ->
                current.copy(missingMedia = null, failure = current.failure?.takeUnless { it.isUnreadable })
            else -> current
        }
    }

    /**
     * Puts [replacement] under every clip that plays the missing file - the
     * source of the edit included - keeping each clip's window and everything
     * on it, as Replace does for one clip. One undo step. A file shorter than
     * a clip's window is cut to fit rather than refused: the point is to get
     * the edit playing again. A photo is rendered into a still first, as Add
     * media does, so a picture swept from files/stills can be put back from
     * its original. Then the next missing file, if there is one, is named in
     * turn.
     */
    fun relink(picked: Uri) {
        val missing = _state.value.missingMedia ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val image = app.contentResolver.getType(picked)?.startsWith("image/") == true
            val replacement = if (image) {
                _state.update { it.copy(preparingStills = it.preparingStills + 1) }
                try {
                    StillClips.fromImage(app, picked)
                } finally {
                    _state.update { it.copy(preparingStills = (it.preparingStills - 1).coerceAtLeast(0)) }
                }
            } else picked
            val meta = replacement?.let { ThumbnailExtractor.probe(app, it) }
            if (replacement == null || meta == null || meta.durationMs <= 0L) {
                _state.update { it.copy(failure = SquishError.FileUnreadable(name = displayNameOf(picked))) }
                return@launch
            }
            val label = displayNameOf(picked) ?: if (image) "Photo" else "Clip"
            fun Clip.relinked(): Clip {
                if (uri != missing) return this
                val out = minOf(sourceOutMs, meta.durationMs)
                val into = minOf(sourceInMs, (out - 1L).coerceAtLeast(0L))
                return copy(uri = replacement, label = label, sourceInMs = into, sourceOutMs = out, sourceDurationMs = meta.durationMs)
            }
            record("Relink") {
                _state.update { current ->
                    val source = current.sourceUri == missing
                    current.copy(
                        videoClips = current.videoClips.map { it.relinked() },
                        audioClips = current.audioClips.map { it.relinked() },
                        sourceUri = if (source) replacement else current.sourceUri,
                        sourceName = if (source) label else current.sourceName,
                        missingMedia = null,
                        failure = null
                    ).let { next ->
                        // The source was probed as nothing when the project
                        // opened, so every frame and crop was measured against
                        // 0x0; the replacement's shape is the edit's shape now.
                        if (!source) next
                        else next.copy(
                            durationMs = meta.durationMs,
                            sourceWidth = meta.displayWidth,
                            sourceHeight = meta.displayHeight,
                            sourceHasAudio = meta.hasAudio,
                            fps = meta.fps,
                            trimStartMs = 0L,
                            trimEndMs = meta.durationMs
                        )
                    }
                }
            }
            recomputeEstimate()
            probeEncoder()
            checkDecodable(replacement)
            ensureProxies(listOf(replacement))
            withContext(Dispatchers.IO) { persist() }
            reportMissingMedia(_state.value.videoClips + _state.value.audioClips)
        }
    }

    /** Redraws the lanes of a recovered edit without blocking the restore on it. */
    private suspend fun restoreAudioWaveforms(clips: List<Clip>) {
        clips.mapNotNull { it.uri }.distinct().forEach { uri ->
            if (_state.value.audioWaveforms[uri.toString()] != null) return@forEach
            val wave = PcmDecoder.decodePeaks(getApplication(), uri) ?: return@forEach
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
        markers = snapshot.markers,
        // Always the start, as CapCut opens a project: reopened where it was left -
        // usually the end, after playing it through - the strip showed every clip
        // off to the left of an empty playhead, and read as the timeline starting
        // part way along.
        playheadMs = 0L,
        outputP = snapshot.outputP,
        fitToSize = snapshot.fitToSize,
        targetSizeMb = snapshot.targetSizeMb,
        audioOnly = snapshot.audioOnly,
        outputFps = snapshot.outputFps,
        quality = snapshot.quality,
        hevc = snapshot.hevc,
        keepHdr = snapshot.keepHdr,
        muteOriginal = snapshot.muteOriginal,

        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        cropRect = snapshot.cropRect,
        snapToMarkers = snapshot.snapToMarkers,
        stabilizeStrength = snapshot.stabilizeStrength,
        beats = snapshot.beats,
        canvasBackground = snapshot.canvasBackground,
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
                proxyUris = if (proxy != null) it.proxyUris + (uri to proxy) else it.proxyUris - uri,
                proxyPercent = null
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
        // The encoder's own percentage, for the notice over the strip: a bare
        // spinner for a minute on heavy footage read as stuck.
        val file = ProxyEngine.ensure(app, uri) { percent -> _state.update { it.copy(proxyPercent = percent) } }
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
        // A take under way is closed and put on the strip first, so the save
        // below keeps it.
        audio.finishRecordingNow()
        // The last edit, before the ticker that would have saved it is gone.
        // Blocking, on purpose: viewModelScope is already cancelled here, and a
        // write handed to another thread has no guarantee of running before the
        // process that asked for it is killed.
        saveNow()
        // Opened from another app or a file and left without one edit: a look,
        // not a project. Kept, every video opened to check it - an export, a
        // clip from the gallery - stayed on the grid as a project nobody made.
        // To the bin, not deleted, so it can still be brought back.
        if (openedJustToLook && !history.canUndo && _state.value.projectName == null) {
            runCatching { autosave.delete(_state.value.projectId) }
            com.squish.app.data.ProjectsChanged.bump()
        }
        // The encode died with the scope; the notification must not outlive it.
        if (_state.value.isExporting) ExportService.end(getApplication())
        // The collector above is cancelled with the scope, so a screen cleared
        // mid-export says so itself.
        ExportsInFlight.set(this, false)
        FilmstripLoader.evictAll()
        // The person masks no draft names any more go too (V19). After the
        // save, so this edit's own are on disk and counted; on a thread of
        // its own, since the scope is gone and the folder is a listing plus
        // a few deletes.
        val app = getApplication<Application>()
        val open = _state.value.videoClips.mapNotNull { it.background?.maskFile }.toSet()
        Thread {
            runCatching { Segmenter.sweep(app, autosave.referencedMaskFiles() + open) }
        }.start()
    }

    private companion object {
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
        /** Allowed on top of a shown moment for the seek and the start. */
        const val SHOW_START_SLACK_MS = 2_500L
        /** Played before and after a shown transition, so the cut it smooths is seen. */
        const val SHOW_AROUND_MS = 400L
    }
}
