package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectSnapshot
import com.squish.app.data.SrtCue
import com.squish.app.data.SrtFile
import com.squish.app.data.SquishRepositories
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.MediaCompat
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.StillClips
import com.squish.app.media.GallerySaver
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import com.squish.app.media.audio.AudioSyncAnalyzer
import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.BeatMap
import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.SpeechSegment
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.media.audio.Transcriber
import com.squish.app.media.video.FilmstripLoader
import com.squish.app.media.video.Reframer
import com.squish.app.media.video.Segmenter
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.Stabilizer
import com.squish.app.media.video.TrackRunner
import com.squish.app.media.audio.WaveformBuilder
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SlowMotion
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.ZOOM_MAX
import com.squish.app.timeline.ZOOM_MIN
import com.squish.app.timeline.rippleVideo
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withClipMoved
import com.squish.app.timeline.withClipRemoved
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.withSplitAllTracks
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.zoomedBy
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

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
    private var syncJob: Job? = null
    private var proxyJob: Job? = null
    private var captionJob: Job? = null
    private var stabilizeJob: Job? = null
    private var trackJob: Job? = null

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
    }

    /**
     * Writes the edit to disk now, on the calling thread, if it has changed.
     *
     * The ticker calls this every second and a half; leaving the screen, the app
     * going to the background and the view model being cleared each call it once
     * more, so the last edit before a back press is on disk and not in the
     * one-and-a-half-second gap it used to fall into. A no-op while an export is
     * running, which flushes for itself before it starts.
     */
    fun saveNow() {
        if (_state.value.isExporting) return
        persist()
    }

    /**
     * [saveNow] without the export check, for the export's own flush.
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
     * Opens a video. With [resume] - a draft chosen from the drafts list - its
     * saved edit is applied at once instead of being offered: picking a draft is
     * already the answer to "restore it?".
     */
    fun load(uri: Uri, resume: Boolean = false) {
        if (loadedUri == uri) return
        loadedUri = uri
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
            startProxy(uri, meta.displayWidth, meta.displayHeight, meta.durationMs)
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

    // ---- Precision trim -------------------------------------------------------

    /**
     * Which clip the precision controls act on: whatever is selected, falling back
     * to the opening shot so the panel is never inert.
     */
    fun trimTargetClip(current: EditorUiState = _state.value): Clip? =
        current.videoClips.firstOrNull { it.id == current.selectedClipId }
            ?: current.videoClips.firstOrNull()

    fun nudgeTrim(isStart: Boolean, frames: Int) {
        val current = _state.value
        val clip = trimTargetClip(current) ?: return
        val delta = frames * current.frameMs
        if (isStart) trimOnce(clip.id, delta, 0L) else trimOnce(clip.id, 0L, delta)
    }

    fun setTrimPointToPlayhead(isStart: Boolean) {
        val current = _state.value
        val clip = trimTargetClip(current) ?: return
        val snapped = if (current.snapToMarkers) snapToNearbyMarker(current.playheadMs, current)
        else current.playheadMs
        // The playhead is played time; trimClip moves source points. On a ramped
        // clip those diverge, so setting the out point to the playhead without
        // this would cut somewhere else entirely - further in on a slowed shot,
        // further out on a sped one.
        val played = Timecode.quantize(snapped - clip.timelineStartMs, current.fps)
        val intoSource = clip.speedRamp.sourceOffsetAt(played, clip.sourceSpanMs)
        if (isStart) {
            trimOnce(clip.id, intoSource, 0L)
        } else {
            trimOnce(clip.id, 0L, intoSource - clip.sourceSpanMs)
        }
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
     * Rewind or forward by a fixed step, from wherever the playhead is.
     *
     * Not snapped, unlike a scrub: a skip of five seconds that landed on a nearby
     * cut instead would be a skip of some other amount, and pressing it twice
     * would not go twice as far.
     */
    fun jumpBy(deltaMs: Long) = _state.update {
        val target = (it.playheadMs + deltaMs).coerceIn(0L, it.timelineDurationMs)
        it.copy(playheadMs = target, scrubNonce = it.scrubNonce + 1)
    }

    /** Jump straight to either end, which is otherwise a long drag on a long edit. */
    fun scrubToStart() = scrubTo(0L)

    fun scrubToEnd() = _state.update {
        it.copy(playheadMs = it.timelineDurationMs, scrubNonce = it.scrubNonce + 1)
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

    fun setPlaying(playing: Boolean) = _state.update { it.copy(isPlaying = playing) }

    // ---- Markers --------------------------------------------------------------

    fun addMarkerAtPlayhead() = record("Add marker") {
        val position = _state.value.playheadMs
        _state.update { current ->
            if (current.markers.any { abs(it - position) < current.frameMs }) current
            else current.copy(markers = (current.markers + position).sorted())
        }
    }

    fun clearMarkers() = record("Clear markers") {
        _state.update { it.copy(markers = emptyList()) }
    }

    fun setSnapToMarkers(enabled: Boolean) = _state.update { it.copy(snapToMarkers = enabled) }

    private fun snapToNearbyMarker(ms: Long, current: EditorUiState): Long {
        val threshold = snapThreshold(current)
        val nearest = current.markers.minByOrNull { abs(it - ms) } ?: return ms
        return if (abs(nearest - ms) <= threshold) nearest else ms
    }

    /** A third of a fingertip, in dp. */
    private val SNAP_DP = 8f

    // ---- Sound ----------------------------------------------------------------

    /**
     * Adds a sound to the timeline at the playhead. There is no limit: music, a
     * voiceover and a second mic can all sit on the strip at once, overlapping
     * freely, because each one is an ordinary clip rather than a special case.
     */
    fun addAudioTrack(uri: Uri, label: String? = null) {
        viewModelScope.launch {
            val trackDuration = ThumbnailExtractor.probeDurationMs(getApplication(), uri)
            // A file with no readable length is refused here, with a reason. It
            // used to become a clip zero milliseconds long: recorded as "Add",
            // selected, invisible on the strip, silently dropped by the export,
            // and one the trim buttons could crash on.
            if (trackDuration <= 0L) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            val name = label ?: displayNameOf(uri) ?: "Audio"

            // Recorded like every other edit, so a track added by mistake is one
            // undo away rather than a select-and-delete.
            record("Add $name") { _state.update { current ->
                // Ends with the video. A song is usually longer than the clip it
                // goes under, and left whole it stretched the edit to the song's
                // length - a minute of black after an eight-second video. The rest
                // of the song is still there: drag the end out to use it.
                val videoEnd = current.videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L
                val room = videoEnd - current.playheadMs
                val out = if (room >= MIN_EFFECT_MS && room < trackDuration) room else trackDuration
                val clip = Clip(
                    kind = ClipKind.Audio,
                    uri = uri,
                    label = name,
                    sourceInMs = 0,
                    sourceOutMs = out,
                    timelineStartMs = current.playheadMs,
                    sourceDurationMs = trackDuration
                )
                current.copy(audioClips = current.audioClips + clip, selectedClipId = clip.id)
            } }
            recomputeEstimate()
            // A readable file can still carry a codec this phone cannot decode; say
            // so now rather than when the export fails on it.
            checkDecodable(uri)

            // Cached against the file, not the clip, so splitting a track in two
            // costs nothing and both halves draw immediately.
            if (_state.value.audioWaveforms[uri.toString()] == null) {
                val pcm = PcmDecoder.decodeMono(getApplication(), uri, maxDurationMs = 10 * 60_000L)
                if (pcm != null) {
                    val wave = WaveformBuilder.build(pcm)
                    _state.update { it.copy(audioWaveforms = it.audioWaveforms + (uri.toString() to wave)) }
                }
            }
        }
    }

    fun removeAudioClip(clipId: String) = record("Remove sound") {
        _state.update { current ->
            current.copy(
                audioClips = current.audioClips.filterNot { it.id == clipId },
                selectedClipId = if (current.selectedClipId == clipId) null else current.selectedClipId,
                syncStatus = SyncStatus.Idle,
                syncConfidence = 0f
            )
        }
        recomputeEstimate()
    }

    /** Changes one added sound. Not recorded itself: every caller is its own undo step. */
    private fun updateAudioClip(clipId: String, block: (Clip) -> Clip) {
        _state.update { current ->
            current.copy(audioClips = current.audioClips.map { if (it.id == clipId) block(it) else it })
        }
        recomputeEstimate()
    }

    /**
     * Which slice of the audio file plays, in source time.
     *
     * Bounded so it cannot throw: with a file shorter than the minimum clip, or
     * one whose length was never read, the old bounds crossed and `coerceIn`
     * threw on a tap of the trim buttons.
     */
    fun setAudioTrim(clipId: String, startMs: Long, endMs: Long) = record("Trim sound") {
        updateAudioClip(clipId) { clip ->
            val limit = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else maxOf(endMs, clip.sourceOutMs)
            if (limit < MIN_CLIP_MS) return@updateAudioClip clip
            val start = startMs.coerceIn(0L, limit - MIN_CLIP_MS)
            clip.copy(sourceInMs = start, sourceOutMs = endMs.coerceIn(start + MIN_CLIP_MS, limit))
        }
    }

    fun placeAudioAtPlayhead(clipId: String) = record("Move sound") {
        val playhead = _state.value.playheadMs
        updateAudioClip(clipId) { it.copy(timelineStartMs = playhead) }
    }

    fun setAudioClipVolume(clipId: String, volume: Float) = record("Level", gesture = "Level $clipId") {
        updateAudioClip(clipId) { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    /**
     * Slides a sound against the picture. Sliding left past the start of the edit
     * is impossible, so the remainder is spent entering the file later instead -
     * which is the same thing to the ear and keeps the nudge from stalling at zero.
     *
     * Each tap is its own step: five nudges and one undo steps back one nudge.
     */
    fun nudgeAudioOffset(clipId: String, deltaMs: Long) = record("Nudge sound") {
        updateAudioClip(clipId) { clip ->
            val proposed = clip.timelineStartMs + deltaMs
            if (proposed >= 0) {
                clip.copy(timelineStartMs = proposed)
            } else {
                clip.copy(
                    timelineStartMs = 0,
                    sourceInMs = (clip.sourceInMs - proposed)
                        .coerceAtMost((clip.sourceOutMs - MIN_CLIP_MS).coerceAtLeast(clip.sourceInMs))
                )
            }
        }
    }

    fun nudgeAudioOffsetFrames(clipId: String, frames: Int) =
        nudgeAudioOffset(clipId, frames * _state.value.frameMs)

    /**
     * The sound back to the top of its file at the top of the timeline - all of
     * it, from its first second.
     *
     * Not "no offset against the head shot's file": with the first shot trimmed
     * five seconds in, that entered the sound five seconds in too, and a music
     * bed lost its opening to a button called Reset. Lining the sound up with
     * the picture is what Auto-sync and the nudges are for; the readout above
     * says truthfully where this leaves it.
     */
    fun resetAudioAlignment(clipId: String) {
        record("Reset alignment") {
            updateAudioClip(clipId) { clip ->
                val placed = EditRules.syncPlacement(0L, 0L, clip.sourceOutMs, clip.sourceDurationMs, MIN_CLIP_MS)
                clip.copy(
                    sourceInMs = placed.sourceInMs,
                    timelineStartMs = placed.timelineStartMs,
                    sourceOutMs = placed.sourceOutMs
                )
            }
        }
        _state.update { it.copy(syncStatus = SyncStatus.Idle, syncConfidence = 0f) }
    }

    fun setOriginalVolume(volume: Float) = record("Camera level", gesture = "Camera level") {
        _state.update { it.copy(originalVolume = volume.coerceIn(0f, 1f)) }
    }

    /**
     * Lines a sound up with the picture by listening to both.
     *
     * Against the head shot of the main track - its file, and where that file
     * sits on the timeline. It used to listen to whichever file was opened first
     * and place the sound as if that file started at timeline zero untrimmed, so
     * after a trim and "close gaps", or with the shot dragged along, the "matched"
     * sound played early or late by exactly that much.
     */
    fun runAutoSync(clipId: String) {
        val current = _state.value
        val head = current.headVideoClip
        val videoUri = head?.uri ?: current.sourceUri ?: return
        val clip = current.audioClips.firstOrNull { it.id == clipId } ?: return
        val audioUri = clip.uri ?: return

        syncJob?.cancel()
        _state.update { it.copy(syncStatus = SyncStatus.Analyzing) }

        syncJob = viewModelScope.launch {
            val result = AudioSyncAnalyzer.detectOffset(getApplication(), videoUri, audioUri)
            if (result == null || result.confidence < MIN_SYNC_CONFIDENCE) {
                _state.update {
                    it.copy(syncStatus = SyncStatus.NoMatch, syncConfidence = result?.confidence ?: 0f)
                }
            } else {
                // Measured when the answer lands, not when the question was asked:
                // the head shot may have been trimmed while this was listening.
                val headDelta = _state.value.headPictureDeltaMs
                recordLate(
                    "Auto-sync",
                    edit = { snapshot ->
                        snapshot.copy(audioClips = snapshot.audioClips.map { existing ->
                            if (existing.id != clipId) existing else {
                                val placed = EditRules.syncPlacement(
                                    result.offsetMs, headDelta, existing.sourceOutMs, existing.sourceDurationMs, MIN_CLIP_MS
                                )
                                existing.copy(
                                    sourceInMs = placed.sourceInMs,
                                    timelineStartMs = placed.timelineStartMs,
                                    sourceOutMs = placed.sourceOutMs
                                )
                            }
                        })
                    },
                    alongside = { it.copy(syncStatus = SyncStatus.Matched, syncConfidence = result.confidence) }
                )
            }
        }
    }

    // ---- Look & compression ---------------------------------------------------

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

    fun setVoiceEffect(effect: VoiceEffect) = record("Voice") {
        _state.update { it.copy(voiceEffect = effect) }
    }

    fun setMuteOriginal(muted: Boolean) = record("Camera audio") {
        _state.update { it.copy(muteOriginal = muted) }
        recomputeEstimate()
    }

    fun toggleRotate() = record("Rotate") {
        _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    fun setCropAspect(aspect: CropAspect) = record("Crop") {
        _state.update { current ->
            // Switching to Custom starts from whatever was already framed, not
            // from the whole picture. Having chosen a square and then wanting it
            // moved off centre, being handed the full frame back means doing the
            // work twice.
            val rect = if (aspect == CropAspect.Custom) {
                current.effectiveCrop.takeIf { !it.isFull }
                    ?: CropRect.centred(1f, current.sourceFrameAspect)
            } else {
                current.cropRect
            }
            current.copy(cropAspect = aspect, cropRect = rect)
        }
    }

    /**
     * Moves the hand-drawn crop.
     *
     * Not recorded per drag event - the drag is one gesture, so it is one undo
     * step rather than one per frame of movement.
     */
    fun setCropRect(rect: CropRect) = record("Crop", gesture = "Crop rect") {
        _state.update { it.copy(cropAspect = CropAspect.Custom, cropRect = rect) }
    }

    /** A file to run a frame analysis over, and the frame size it will produce. */
    private data class AnalysisSource(val uri: Uri, val width: Int, val height: Int)

    /**
     * Which copy of a clip the motion analyses should read.
     *
     * The proxy, whenever one exists. Both analyses downsample every frame they
     * decode to a few hundred pixels across before looking at it, so decoding a
     * 33-megabyte 4K frame to measure a 320-pixel one is thirty times the memory
     * and thirty times the wait for an identical answer. The proxy has the same
     * frame count and frame rate, so indices and timings carry over unchanged.
     *
     * Falls back to the original when no proxy has been built yet, which is safe
     * now that the batch size is budgeted against the frame size.
     */
    private fun analysisSourceFor(uri: Uri, current: EditorUiState): AnalysisSource {
        val proxy = current.proxyUri
        if (proxy != null && uri == current.sourceUri) {
            return AnalysisSource(proxy, ProxyEngine.PROXY_WIDTH_HINT, ProxyEngine.PROXY_HEIGHT)
        }
        return AnalysisSource(uri, current.sourceWidth, current.sourceHeight)
    }

    // ---- Beat detection ---------------------------------------------------------

    private var beatJob: Job? = null

    /**
     * Finds the pulse of whichever sound the edit is built around.
     *
     * Prefers an added track - if someone has dropped music onto the timeline,
     * that is the thing they are cutting to - and falls back to the camera audio,
     * which is right for a performance shot with the music in the room.
     */
    fun detectBeats() {
        val current = _state.value
        if (current.beats.running) return

        val target = current.audioClips.firstOrNull { it.id == current.selectedClipId }
            ?: current.audioClips.firstOrNull()
        val uri = target?.uri ?: current.sourceUri ?: return
        val label = target?.label ?: "the camera audio"

        beatJob?.cancel()
        // The grid already found stays until there is a new one. Wiping it at the
        // start meant a failed second listen lost the first answer, with nothing
        // for undo to bring back.
        _state.update { it.copy(beats = it.beats.copy(running = true, failed = false, listeningTo = label)) }

        beatJob = viewModelScope.launch {
            val pcm = PcmDecoder.decodeMono(
                context = getApplication(),
                uri = uri,
                targetSampleRate = BEAT_ANALYSIS_RATE,
                maxDurationMs = BEAT_MAX_ANALYSIS_MS
            )
            if (pcm == null) {
                _state.update { it.copy(beats = it.beats.copy(running = false, failed = true, listeningTo = label)) }
                return@launch
            }

            val map = withContext(Dispatchers.Default) {
                BeatDetector.detect(pcm.samples, pcm.sampleRate)
            }
            if (map.isEmpty) {
                _state.update { it.copy(beats = it.beats.copy(running = false, failed = true, listeningTo = label)) }
                return@launch
            }

            // A step of its own: the grid is what "Snap to the beat" and "Cut on
            // the beat" act on, and undo puts the previous one back.
            val found = map.toProgress(target, label)
            recordLate(
                "Find the beat",
                edit = { it.copy(beats = found) },
                alongside = { it.copy(beats = found) }
            )
        }
    }

    /**
     * Beat times moved out of the analysed clip's source clock and onto the
     * timeline.
     *
     * The decoder always starts at the top of the file, so a beat's time is a
     * source time; a music cue dragged to start ten seconds in has its beats ten
     * seconds later than the analysis says. Mapping through the clip also puts
     * them through its speed curve, which is the only way a ramped music bed's
     * beats land where they are heard.
     */
    private fun BeatMap.toProgress(clip: Clip?, label: String): BeatProgress {
        val onTimeline = if (clip == null) beatsMs else beatsMs.mapNotNull { at ->
            val source = clip.sourceInMs + at
            if (source < clip.sourceInMs || source > clip.sourceOutMs) null
            else clip.timelineAtSource(source)
        }
        return BeatProgress(
            finished = true,
            bpm = bpm,
            confidence = confidence,
            beatsMs = onTimeline,
            downbeatOffset = downbeatOffset,
            clipLabel = label
        )
    }

    /** The same pulse counted twice as fast, or half as fast. */
    fun scaleBeats(faster: Boolean) = record("Beat tempo") {
        _state.update { current ->
            val beats = current.beats
            if (!beats.hasBeats) return@update current
            val map = BeatMap(beats.beatsMs, beats.bpm, beats.confidence, beats.downbeatOffset)
            val scaled = if (faster) map.doubled() else map.halved()
            current.copy(
                beats = beats.copy(
                    beatsMs = scaled.beatsMs,
                    bpm = scaled.bpm,
                    downbeatOffset = scaled.downbeatOffset
                )
            )
        }
    }

    /** Moves which beat counts as the one on the bar. */
    fun nudgeDownbeat() = record("Shift bar") {
        _state.update { current ->
            current.copy(beats = current.beats.copy(downbeatOffset = (current.beats.downbeatOffset + 1).mod(4)))
        }
    }

    fun clearBeats() = record("Clear beat") {
        beatJob?.cancel()
        _state.update { it.copy(beats = BeatProgress()) }
    }

    /**
     * Drops a marker on every nth beat, so every edit that already snaps now snaps
     * to the music: dragging a clip, setting an in point, moving a caption.
     *
     * Merged with the markers already there. It used to replace them, so a marker
     * put by hand on the one frame that mattered went the moment the grid was
     * snapped to. Markers sitting on the grid are the ones an earlier snap made,
     * and those are replaced - so "every bar" after "every beat" thins them out.
     */
    fun markBeats(everyN: Int) = record("Mark beats") {
        _state.update { current ->
            val chosen = current.beats.every(everyN)
            if (chosen.isEmpty()) current
            else current.copy(
                markers = EditRules.mergedBeatMarkers(current.markers, current.beats.beatsMs, chosen, current.frameMs),
                snapToMarkers = true
            )
        }
    }

    /**
     * Cuts the main video track on every nth beat.
     *
     * The picture only. It used to razor every track under each beat - the song
     * included, into one piece per bar, after which finding the beat again heard
     * only the first piece. Overlays and sounds stay whole, which is what cutting
     * a montage to a song means.
     *
     * Applied back to front. Each cut renumbers the clips after it, so working
     * forwards would have every subsequent position measured against a timeline
     * that had already changed underneath it.
     */
    fun cutOnBeats(everyN: Int) {
        val current = _state.value
        val cuts = current.beats.every(everyN)
            .filter { it > 0 }
            .sortedDescending()
        if (cuts.isEmpty()) return

        // One step for the whole run, not one per cut. Cutting a track on forty
        // beats and then pressing undo forty times is not undo.
        record("Cut on the beat") {
            // The main track only. The song being cut to is on the strip too, and
            // razoring it on every beat left a hundred pieces for the next nudge to
            // break; overlays stay whole for the same reason. The all-tracks split
            // is the one that re-lays the magnetic track and carries keyframes over.
            cuts.forEach { at ->
                mutateTimeline { timeline ->
                    timeline.copy(playheadMs = at).withSplitAllTracks { it.isMain }
                }
            }
            _state.update { it.copy(selectedClipId = null) }
        }
    }

    // ---- Speed ----------------------------------------------------------------

    /**
     * Which clip the speed controls act on.
     *
     * Whatever is selected, of either kind. A sound can be ramped too - pitching a
     * music bed up into a drop is the same operation as ramping a shot, and making
     * it a special case would mean writing it twice.
     */
    fun speedTargetClip(current: EditorUiState = _state.value): Clip? =
        (current.videoClips + current.audioClips).firstOrNull { it.id == current.selectedClipId }
            ?: current.videoClips.firstOrNull()

    /**
     * Retimes a clip, and closes up behind it.
     *
     * A clip's length on the strip is derived from its ramp, so changing the ramp
     * moves where everything after it starts. Leaving those in place would open a
     * gap on a speed-up and overlap on a slow-down - and an overlap on the base
     * track is read as a transition, so slowing one shot would quietly dissolve it
     * into the next.
     */
    private fun retime(clipId: String, ramp: SpeedRamp) {
        mutateTimeline { timeline ->
            val target = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val updated = timeline.clips.map { if (it.id == clipId) it.copy(speedRamp = ramp) else it }
            timeline.copy(clips = resequenceAfterRetime(updated, target))
        }
    }

    /**
     * Re-lays the clips that shared a lane with the retimed one.
     *
     * Only the ones after it, only on its own layer and kind, and only when they
     * were butted up against what came before - a clip the editor deliberately
     * placed in a gap stays where it was put. Anything else would make a speed
     * change silently rearrange an edit someone had already timed by hand.
     */
    private fun resequenceAfterRetime(clips: List<Clip>, target: Clip): List<Clip> {
        val lane = clips
            .filter { it.kind == target.kind && it.layer == target.layer }
            .sortedBy { it.timelineStartMs }
        val startIndex = lane.indexOfFirst { it.id == target.id }
        if (startIndex < 0) return clips

        val moved = HashMap<String, Long>()
        // Where the retimed clip now ends, and where it used to. The first is what
        // the followers are moved to; the second is what decides which of them
        // were following in the first place.
        var cursor = lane[startIndex].timelineEndMs
        var previousEnd = target.timelineEndMs

        for (i in startIndex + 1 until lane.size) {
            val next = lane[i]
            // A gap the editor put there is part of the edit. Only a butt cut,
            // within a frame or so, is treated as "follows on from".
            if (next.timelineStartMs - previousEnd > TOUCHING_MS) break
            moved[next.id] = cursor
            previousEnd = next.timelineEndMs
            cursor += next.durationMs
        }

        return clips.map { clip -> moved[clip.id]?.let { clip.copy(timelineStartMs = it) } ?: clip }
    }

    /**
     * One rate across the whole clip, which is what the slider and presets set.
     * [dragging] is the slider: its frames are one step, where two taps on the
     * preset chips are two.
     */
    fun setClipSpeed(clipId: String, speed: Float, dragging: Boolean = false) =
        record("Speed", gesture = if (dragging) "Speed $clipId" else null) {
            retime(clipId, SpeedRamp.flat(speed))
        }

    /**
     * "Keep it smooth": nothing in the clip slower than its footage can carry.
     *
     * Raises only the slow parts. It used to set one flat rate for the whole clip,
     * so a bullet-time ramp - fast, slow, fast - silently became a constant crawl.
     */
    fun keepSmooth(clipId: String) {
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        val limit = SlowMotion.smoothestSpeed(current.fps)
        record("Keep it smooth") { retime(clipId, EditRules.heldAtLeast(clip.speedRamp, limit)) }
    }

    /** Lays a ready-made ramp across the clip. */
    fun applyRampShape(clipId: String, shape: RampShape) {
        val clip = _state.value.let { current ->
            (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }
        } ?: return
        record("Speed ramp") { retime(clipId, SpeedRamp.preset(shape, clip.sourceSpanMs)) }
    }

    /** Adds or moves a control point, at the source frame under the playhead. */
    fun setSpeedPointAtPlayhead(clipId: String, speed: Float) {
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        // The playhead is in played time; a point is anchored in source time, so
        // that editing the curve elsewhere does not drag this point along with it.
        val at = (clip.sourceAt(current.playheadMs) - clip.sourceInMs).coerceIn(0L, clip.sourceSpanMs)
        val base = if (clip.speedRamp.ordered.isEmpty()) {
            SpeedRamp.flat(1f)
        } else {
            clip.speedRamp
        }
        record("Speed point") { retime(clipId, base.withPoint(at, speed, clip.sourceSpanMs)) }
    }

    fun removeSpeedPoint(clipId: String, atMs: Long) {
        val clip = _state.value.let { current ->
            (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }
        } ?: return
        record("Remove speed point") { retime(clipId, clip.speedRamp.withoutPoint(atMs)) }
    }

    fun clearSpeed(clipId: String) = record("Reset speed") {
        retime(clipId, SpeedRamp())
    }

    /**
     * Picks a look. Choosing the same one again clears it, so the chip you just
     * tapped is also the way back to the untouched picture.
     */
    fun setLook(lookId: String?) = record("Look") {
        _state.update { current ->
            val next = if (lookId == null || lookId == current.lookId) null else lookId
            current.copy(
                lookId = next,
                lookIntensity = if (next == null) 1f else current.lookIntensity
            )
        }
    }

    fun setLookIntensity(value: Float) = record("Look strength", gesture = "Look strength") {
        _state.update { it.copy(lookIntensity = value.coerceIn(0f, 1f)) }
    }

    fun setBrightness(value: Float) = record("Brightness", gesture = "Brightness") {
        _state.update { it.copy(brightness = value) }
    }
    fun setContrast(value: Float) = record("Contrast", gesture = "Contrast") {
        _state.update { it.copy(contrast = value) }
    }
    fun setSaturation(value: Float) = record("Saturation", gesture = "Saturation") {
        _state.update { it.copy(saturation = value) }
    }

    /**
     * Where a new title, line or sticker of [lengthMs] goes: from the playhead, and
     * never past the last frame - see [EditRules.placedAt].
     */
    private fun placeNewText(current: EditorUiState, lengthMs: Long): Span =
        EditRules.placedAt(current.playheadMs, lengthMs, current.trimmedDurationMs, MIN_CLIP_MS)

    /**
     * A blank line starting at the playhead. Spanning the whole clip - which is what
     * this used to do - is never what anyone wants from a caption.
     */
    fun addCaptionAtPlayhead() = record("Add line") {
        val span = placeNewText(_state.value, DEFAULT_CAPTION_MS)
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = "",
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = android.graphics.Color.WHITE
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item) }
    }

    /**
     * A title at the playhead, styled by [preset]: its text, face, look, colour,
     * place on the frame and motion, all at once. It is an ordinary caption from
     * then on - every part of it can be changed afterwards.
     */
    fun addTitle(preset: TitlePreset) = record("Add title") {
        val span = placeNewText(_state.value, DEFAULT_TITLE_MS)
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = preset.sample,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = preset.colorArgb,
            yFraction = preset.yFraction,
            sizeSp = preset.sizeSp,
            font = preset.font,
            look = preset.look,
            motion = preset.motion
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item) }
    }

    /**
     * A sticker at the playhead, in the middle of the picture, popping in. It
     * stays for [DEFAULT_TITLE_MS] and can be moved, sized and retimed from there.
     */
    fun addSticker(emoji: String) = record("Add sticker") {
        val span = placeNewText(_state.value, DEFAULT_TITLE_MS)
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = emoji,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = android.graphics.Color.WHITE,
            xFraction = 0.5f,
            yFraction = 0.5f,
            sizeSp = 64,
            look = TextLook.Plain,
            motion = TextMotion.Pop,
            sticker = true
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id) }
    }

    // ---- Background removal ---------------------------------------------------------

    private var backgroundJob: Job? = null

    /** The clip background removal acts on: the selected video clip, else the first. */
    fun backgroundTarget(state: EditorUiState = _state.value): Clip? =
        state.videoClips.firstOrNull { it.id == state.selectedClipId }
            ?: state.videoClips.firstOrNull { it.layer == 0 }
            ?: state.videoClips.firstOrNull()

    /** Finds the person through the clip, then blurs what is behind them. */
    fun removeBackground() {
        val current = _state.value
        if (current.backgroundProgress.running) return
        val clip = backgroundTarget(current) ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        backgroundJob?.cancel()
        _state.update { it.copy(backgroundProgress = ReframeProgress(running = true)) }
        backgroundJob = viewModelScope.launch {
            val file = Segmenter.analyze(
                getApplication(), uri, clip.sourceInMs, clip.sourceOutMs
            ) { done, total ->
                _state.update { it.copy(backgroundProgress = it.backgroundProgress.copy(done = done, total = total)) }
            }
            if (file == null) {
                _state.update { it.copy(backgroundProgress = ReframeProgress(failed = true)) }
                return@launch
            }
            _state.update { it.copy(backgroundProgress = ReframeProgress()) }
            val fill = clip.background?.fill ?: BackgroundFill.Blur
            val colour = clip.background?.colorArgb ?: BackgroundRemoval(file).colorArgb
            setBackground(clip.id, BackgroundRemoval(file, fill, colour))
        }
    }

    fun cancelBackground() {
        backgroundJob?.cancel()
        backgroundJob = null
        _state.update { it.copy(backgroundProgress = ReframeProgress()) }
    }

    fun setBackgroundFill(clipId: String, fill: BackgroundFill, colorArgb: Int? = null) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        val current = clip.background ?: return
        setBackground(clipId, current.copy(fill = fill, colorArgb = colorArgb ?: current.colorArgb))
    }

    fun setBackground(clipId: String, background: BackgroundRemoval?) = record("Background") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(background = background) else it })
        }
    }

    // ---- Auto-reframe -------------------------------------------------------------

    private var reframeJob: Job? = null

    /**
     * Finds the subject through the head clip and makes the frame-shape crop
     * follow it. Needs a fixed shape; if none is chosen yet, 9:16 is - the shape
     * this is nearly always wanted for.
     */
    fun autoReframe() {
        val current = _state.value
        if (current.reframeProgress.running) return
        val clip = current.videoClips.firstOrNull() ?: return
        val uri = clip.uri ?: current.sourceUri ?: return
        if (current.cropAspect.ratio == null) setCropAspect(CropAspect.Portrait)

        reframeJob?.cancel()
        _state.update { it.copy(reframeProgress = ReframeProgress(running = true)) }
        reframeJob = viewModelScope.launch {
            val track = Reframer.analyze(
                getApplication(), uri, clip.sourceInMs, clip.sourceOutMs
            ) { done, total ->
                _state.update { it.copy(reframeProgress = it.reframeProgress.copy(done = done, total = total)) }
            }
            if (track == null) {
                _state.update { it.copy(reframeProgress = ReframeProgress(failed = true)) }
                return@launch
            }
            record("Auto-reframe") {
                _state.update { it.copy(reframe = track, reframeProgress = ReframeProgress()) }
            }
        }
    }

    fun cancelReframe() {
        reframeJob?.cancel()
        reframeJob = null
        _state.update { it.copy(reframeProgress = ReframeProgress()) }
    }

    /** Back to a centred crop. */
    fun clearReframe() = record("Centre crop") {
        _state.update { it.copy(reframe = null) }
    }

    // ---- Templates ----------------------------------------------------------------

    /**
     * Applies [template] as one undoable step. Replaces the look and the frame
     * shape, and the effects and title an earlier template added; keeps every
     * caption, sticker and effect added by hand.
     */
    fun applyTemplate(template: Template) = record("Template ${template.label}") {
        _state.update { current ->
            val total = current.timelineDurationMs.coerceAtLeast(1L)
            val placed = template.effects.map { (kind, at) ->
                val start = (total * at).toLong().coerceIn(0L, (total - MIN_EFFECT_MS).coerceAtLeast(0L))
                // A slow push fills the whole edit; the others are a moment each.
                val length = if (kind == EffectKind.ZoomIn) total else DEFAULT_EFFECT_MS
                TimedEffect(
                    id = TEMPLATE_PREFIX + UUID.randomUUID(),
                    kind = kind,
                    startMs = start,
                    endMs = (start + length).coerceAtMost(total)
                )
            }
            val title = template.title?.let { preset ->
                TextOverlayItem(
                    id = TEMPLATE_PREFIX + UUID.randomUUID(),
                    text = template.titleText ?: preset.sample,
                    startMs = 0L,
                    endMs = DEFAULT_TITLE_MS.coerceAtMost(total),
                    colorArgb = preset.colorArgb,
                    yFraction = preset.yFraction,
                    sizeSp = preset.sizeSp,
                    font = preset.font,
                    look = preset.look,
                    motion = preset.motion
                )
            }
            current.copy(
                cropAspect = template.crop ?: CropAspect.Original,
                lookId = template.lookId,
                lookIntensity = 1f,
                effects = current.effects.filterNot { it.id.startsWith(TEMPLATE_PREFIX) } + placed,
                textOverlays = current.textOverlays.filterNot { it.id.startsWith(TEMPLATE_PREFIX) } +
                    listOfNotNull(title)
            )
        }
    }

    // ---- Effects library --------------------------------------------------------

    /** An effect from the playhead for [DEFAULT_EFFECT_MS], or to the end if that is sooner. */
    fun addEffect(kind: EffectKind) = record("Add ${kind.label}") {
        val current = _state.value
        val total = current.timelineDurationMs
        val start = current.playheadMs.coerceIn(0L, (total - MIN_EFFECT_MS).coerceAtLeast(0L))
        val end = (start + DEFAULT_EFFECT_MS).coerceAtMost(total.takeIf { it > start } ?: (start + DEFAULT_EFFECT_MS))
        val effect = TimedEffect(id = UUID.randomUUID().toString(), kind = kind, startMs = start, endMs = end)
        _state.update { it.copy(effects = it.effects + effect) }
    }

    fun changeEffect(id: String, change: (TimedEffect) -> TimedEffect) = record("Effect change", gesture = "Effect $id") {
        _state.update { current ->
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) e else change(e).let { c ->
                    // Never shorter than a tenth of a second, never inside out.
                    val start = c.startMs.coerceAtLeast(0L)
                    c.copy(startMs = start, endMs = c.endMs.coerceAtLeast(start + MIN_EFFECT_MS))
                }
            })
        }
    }

    /** Slides an effect along the timeline, keeping its length and staying inside the edit. */
    fun moveEffect(id: String, deltaMs: Long) = record("Move effect", gesture = "Move $id") {
        _state.update { current ->
            val total = current.timelineDurationMs
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) return@map e
                val delta = deltaMs.coerceIn(-e.startMs, (total - e.endMs).coerceAtLeast(0L))
                e.copy(startMs = e.startMs + delta, endMs = e.endMs + delta)
            })
        }
    }

    /** Pulls an effect's start and end by the given amounts, never past each other or the edit's ends. */
    fun trimEffect(id: String, startDeltaMs: Long, endDeltaMs: Long) = record("Trim effect", gesture = "Trim $id") {
        _state.update { current ->
            val total = current.timelineDurationMs
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) return@map e
                val start = (e.startMs + startDeltaMs).coerceIn(0L, (e.endMs - MIN_EFFECT_MS).coerceAtLeast(0L))
                val end = (e.endMs + endDeltaMs).coerceIn(start + MIN_EFFECT_MS, maxOf(total, start + MIN_EFFECT_MS))
                e.copy(startMs = start, endMs = end)
            })
        }
    }

    fun removeEffect(id: String) = record("Remove effect") {
        _state.update { it.copy(effects = it.effects.filterNot { e -> e.id == id }) }
    }

    /**
     * Changes how one caption looks or moves. The text and timing are left alone.
     * [dragging] is a slider - size, position - whose frames are one step; a tap
     * on a chip is a step of its own.
     */
    fun restyleCaption(id: String, change: (TextOverlayItem) -> TextOverlayItem, dragging: Boolean = false) =
        record("Text style", gesture = if (dragging) "Style $id" else null) {
            _state.update { current ->
                current.copy(textOverlays = current.textOverlays.map { if (it.id == id) change(it) else it })
            }
        }

    /** Takes one caption or sticker off, as an undo step - from the panels and the strip alike. */
    fun removeTextOverlay(id: String) = record("Remove text") { dropTextOverlay(id) }

    private fun dropTextOverlay(id: String) {
        _state.update {
            it.copy(
                textOverlays = it.textOverlays.filterNot { item -> item.id == id },
                selectedClipId = if (it.selectedClipId == id) null else it.selectedClipId
            )
        }
    }

    // ---- Timeline -------------------------------------------------------------

    fun addVideoClip(uri: Uri) = addVideoClips(listOf(uri))

    /**
     * Adds videos to the end of the main track, in the order they were picked.
     *
     * Probed one after another and added in a single step, so the order is the
     * picking order - not whichever file happened to finish probing first - and
     * one undo takes back the whole batch. The strip is refitted afterwards so
     * what was just added is on screen rather than past its right-hand edge.
     */
    fun addVideoClips(uris: List<Uri>) = addVideoSources(uris, atPlayhead = false)

    /**
     * Adds videos and photos to the main track at the playhead - on the nearer cut
     * of the shot under it, never inside one - and moves everything after along
     * to make room. The way a picked clip lands in CapCut; the append above is
     * what the strip's "+" at the end of the track means.
     */
    fun insertSourcesAtPlayhead(uris: List<Uri>) = addVideoSources(uris, atPlayhead = true)

    private fun addVideoSources(uris: List<Uri>, atPlayhead: Boolean) {
        if (uris.isEmpty()) return
        // Where the playhead was when the files were picked, not where it has got
        // to by the time the photos have been rendered into clips.
        val at = if (atPlayhead) _state.value.playheadMs else null
        viewModelScope.launch {
            // Photos come in the same pick as videos. Each is made into a short
            // clip first (see StillClips); the order picked is kept either way.
            val resolver = getApplication<Application>().contentResolver
            val isPhoto = uris.associateWith { resolver.getType(it)?.startsWith("image/") == true }
            val photos = isPhoto.count { it.value }
            if (photos > 0) _state.update { it.copy(preparingStills = it.preparingStills + photos) }
            val sources = uris.map { uri ->
                if (isPhoto[uri] == true) {
                    StillClips.fromImage(getApplication(), uri)?.let { StillSource(it, displayNameOf(uri) ?: "Photo") }
                        .also { _state.update { s -> s.copy(preparingStills = (s.preparingStills - 1).coerceAtLeast(0)) } }
                } else {
                    StillSource(uri, null)
                }
            }
            addSources(sources.filterNotNull(), failedAny = sources.any { it == null }, at = at)
        }
    }

    /** A blank - plain black, the edit's own shape - at the end of the video track. */
    fun addBlankClip() {
        viewModelScope.launch {
            _state.update { it.copy(preparingStills = it.preparingStills + 1) }
            val current = _state.value
            val made = StillClips.blank(getApplication(), current.framedWidth, current.framedHeight)
            _state.update { it.copy(preparingStills = (it.preparingStills - 1).coerceAtLeast(0)) }
            if (made == null) {
                _state.update { it.copy(failure = SquishError.Unknown(null)) }
                return@launch
            }
            addSources(listOf(StillSource(made, "Blank")), failedAny = false)
        }
    }

    /**
     * A file to put on the video track, and the name to show for it. A still is
     * rendered longer than it is placed, so its end can be dragged out; [label]
     * is set for those, and marks them.
     */
    private data class StillSource(val uri: Uri, val label: String?)

    /**
     * Puts probed files on the main track: at the end, or with [at] set, into the
     * track at the cut nearest that moment, with every later main-track clip
     * moved along by what was added.
     */
    private suspend fun addSources(sources: List<StillSource>, failedAny: Boolean, at: Long? = null) {
        if (sources.isEmpty()) {
            _state.update { it.copy(failure = SquishError.FileUnreadable()) }
            return
        }
        if (failedAny) _state.update { it.copy(failure = SquishError.FileUnreadable()) }
        run {
            val probed = sources.map { src ->
                val meta = ThumbnailExtractor.probe(getApplication(), src.uri)
                Triple(src.uri, meta, src.label)
            }.filter { (_, meta, _) -> meta.durationMs > 0L }
            if (probed.isEmpty()) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return
            }
            val what = when {
                probed.size > 1 -> "${probed.size} clips"
                probed[0].third == "Blank" -> "blank"
                probed[0].third != null -> "photo"
                else -> "clip"
            }
            record("Add $what") {
                _state.update { current ->
                    val base = current.videoClips.filter { it.layer == 0 }.sortedBy { it.timelineStartMs }
                    // A still lands short and can be dragged out to its full render.
                    val lengths = probed.map { (_, meta, label) ->
                        if (label != null) minOf(StillClips.DEFAULT_MS, meta.durationMs) else meta.durationMs
                    }
                    val insertion = if (at == null) null
                    else EditRules.insertion(base.map { Span(it.timelineStartMs, it.timelineEndMs) }, at, lengths)
                    val insertAt = insertion?.atMs ?: base.maxOfOrNull { c -> c.timelineEndMs } ?: 0L
                    var start = insertAt
                    val added = probed.mapIndexed { i, (uri, meta, label) ->
                        val placed = lengths[i]
                        Clip(
                            kind = ClipKind.Video,
                            uri = uri,
                            label = label ?: displayNameOf(uri) ?: "Clip ${current.videoClips.size + i + 1}",
                            sourceInMs = 0,
                            sourceOutMs = placed,
                            timelineStartMs = start,
                            sourceDurationMs = meta.durationMs
                        ).also { start += placed }
                    }
                    // Only when inserting: an append has nothing after it to move.
                    // Which clips follow is by track order, not by start time - a
                    // clip transitioning in starts before the cut it follows.
                    val followers = insertion?.let { ins -> base.drop(ins.index).map { it.id }.toSet() }.orEmpty()
                    val existing = if (insertion == null) current.videoClips else current.videoClips.map { c ->
                        if (c.id in followers) c.copy(timelineStartMs = c.timelineStartMs + insertion.followersShiftMs)
                        else c
                    }
                    current.copy(
                        videoClips = existing + added,
                        selectedClipId = if (at == null) current.selectedClipId else added.first().id,
                        fitNonce = current.fitNonce + 1
                    )
                }
            }
            recomputeEstimate()
            probed.forEach { (uri, _, label) -> if (label == null) checkDecodable(uri) }
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

    /**
     * Moves a main-track clip to [index] in the track's order and lays the track
     * end to end again - long-press, drag, drop. Transitions and everything else
     * on the clip travel with it; the clip that ends up first has nothing to
     * transition in from, so its transition does nothing until it moves again.
     */
    fun reorderClip(clipId: String, index: Int) {
        val base = _state.value.videoClips.filter { it.layer == 0 }.sortedBy { it.timelineStartMs }
        if (base.none { it.id == clipId }) return
        val order = EditRules.reordered(base.map { it.id }, clipId, index)
        if (order == base.map { it.id }) return
        record("Reorder") {
            mutateTimeline { timeline ->
                // Placeholder starts in the new order; rippleVideo lays them out for real.
                val rank = order.withIndex().associate { (i, id) -> id to i.toLong() }
                timeline.copy(
                    clips = timeline.clips.map { c -> rank[c.id]?.let { c.copy(timelineStartMs = it) } ?: c }
                ).rippleVideo()
            }
        }
    }

    /** Adds a clip on an overlay layer, starting at the playhead. */
    fun addOverlayClip(uri: Uri) {
        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            // Refused with a reason rather than added as an invisible layer with
            // no length, the way an unreadable sound used to be.
            if (meta.durationMs <= 0L) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            record("Add overlay") { _state.update { current ->
                val clip = Clip(
                    kind = ClipKind.Video,
                    uri = uri,
                    label = displayNameOf(uri) ?: "Overlay",
                    sourceInMs = 0,
                    sourceOutMs = meta.durationMs,
                    timelineStartMs = current.playheadMs,
                    sourceDurationMs = meta.durationMs,
                    layer = 1,
                    scale = 0.4f,
                    offsetXFraction = 0.45f,
                    offsetYFraction = -0.45f
                )
                // Onto the first row free at this moment, never on top of another
                // overlay: the preview shows one clip per row, the export all of them.
                val placed = TimelineState(clips = current.videoClips).withClipAdded(clip)
                if (placed.clips.size == current.videoClips.size) {
                    current.copy(failure = SquishError.OverlayRowsFull(MAX_LAYER))
                } else {
                    current.copy(videoClips = placed.clips, selectedClipId = clip.id)
                }
            } }
            recomputeEstimate()
            checkDecodable(uri)
        }
    }

    /**
     * Sets the transition into a clip. Picking a kind is a step; dragging its
     * length is one gesture, so the slider's hundred frames are one undo.
     *
     * This and a layer change both re-lay the main track, which can shorten the
     * edit and so cut back the effects at its end - an undo step each, or there
     * is no way back.
     */
    fun setTransition(clipId: String, type: TransitionType, durationMs: Long) {
        val existing = _state.value.videoClips.firstOrNull { it.id == clipId }?.transitionIn
        val lengthOnly = existing != null && existing.type == type && existing.durationMs != durationMs
        record("Transition", gesture = if (lengthOnly) "Transition length $clipId" else null) {
            mutateTimeline { it.withTransition(clipId, Transition(type, durationMs)) }
        }
    }

    fun changeLayer(clipId: String, delta: Int) = record(if (delta > 0) "Raise layer" else "Lower layer") {
        mutateTimeline { it.withLayerChanged(clipId, delta) }
    }

    /** One slider on the overlay sheet. Each slider, on each clip, is a gesture of its own. */
    fun setOverlayGeometry(
        clipId: String,
        opacity: Float? = null,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null
    ) = record(
        if (opacity != null && scale == null && offsetX == null && offsetY == null) "Opacity" else "Placement",
        gesture = "Layer ${fieldsNamed("opacity" to opacity, "scale" to scale, "x" to offsetX, "y" to offsetY)} $clipId"
    ) {
        mutateTimeline { it.withOverlayGeometry(clipId, opacity, scale, offsetX, offsetY) }
    }

    /** Which of a family of optional arguments were passed, for telling one slider's gesture from another's. */
    private fun fieldsNamed(vararg fields: Pair<String, Any?>): String =
        fields.filter { it.second != null }.joinToString("+") { it.first }

    // ---- Green screen -------------------------------------------------------------

    /** Turns keying on for a clip, or off when passed null. */
    fun setChromaKey(clipId: String, key: ChromaKey?) = record(if (key == null) "Remove key" else "Green screen") {
        mutateTimeline { timeline ->
            timeline.copy(
                clips = timeline.clips.map { if (it.id == clipId) it.copy(chromaKey = key) else it }
            )
        }
    }

    /**
     * Tunes the key. A colour picked - a chip, a tap on the frame - is a step;
     * the sliders are gestures, one per slider per clip.
     */
    fun updateChromaKey(
        clipId: String,
        keyColorArgb: Int? = null,
        similarity: Float? = null,
        smoothness: Float? = null,
        spill: Float? = null
    ) = record(
        if (keyColorArgb != null) "Key colour" else "Green screen",
        gesture = if (similarity == null && smoothness == null && spill == null) null
        else "Chroma ${fieldsNamed("similarity" to similarity, "smoothness" to smoothness, "spill" to spill)} $clipId"
    ) {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val current = clip.chromaKey ?: ChromaKey()
            val next = current.copy(
                keyColorArgb = keyColorArgb ?: current.keyColorArgb,
                similarity = (similarity ?: current.similarity).coerceIn(0.02f, 0.6f),
                smoothness = (smoothness ?: current.smoothness).coerceIn(0.005f, 0.4f),
                spill = (spill ?: current.spill).coerceIn(0.005f, 0.4f)
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(chromaKey = next) else it })
        }
    }

    /**
     * The frame on screen at [atMs] of [clip], for sampling the screen colour or
     * placing a tracking box.
     *
     * Through the clip's speed curve: on a retimed clip the frame the preview
     * shows is not "in-point plus time since the clip started", and the swatch
     * was sampled from somewhere else. From the proxy when there is one - the
     * answer is scaled down to a few hundred pixels anyway, and decoding a 4K
     * frame to get there was most of the wait.
     */
    suspend fun sampleFrame(clip: Clip, atMs: Long): android.graphics.Bitmap? {
        val current = _state.value
        val uri = clip.uri ?: current.sourceUri ?: return null
        val inClip = clip.sourceAt(atMs).coerceIn(clip.sourceInMs, clip.sourceOutMs)
        return ThumbnailExtractor.frameAt(getApplication(), analysisSourceFor(uri, current).uri, inClip)
    }

    /**
     * The main-track picture at [timelineMs]: which file, and where in it. Null
     * over a gap or past the end. What the look thumbnails are graded on, so they
     * show the shot under the playhead rather than the first file at a time that
     * may not even be in it.
     */
    fun pictureAt(current: EditorUiState, timelineMs: Long): Pair<Uri, Long>? {
        val clip = current.baseClipAt(timelineMs) ?: return null
        val uri = clip.uri ?: current.sourceUri ?: return null
        return analysisSourceFor(uri, current).uri to clip.sourceAt(timelineMs).coerceIn(clip.sourceInMs, clip.sourceOutMs)
    }

    // ---- Captions ---------------------------------------------------------------

    /**
     * Finds every stretch of speech and makes a caption for each, transcribing where
     * the device can.
     *
     * The two halves are deliberately independent. Segmentation runs on the PCM the
     * app already decodes and always works; recognition needs an on-device model
     * that not every phone has. When recognition is unavailable you still get every
     * caption card sitting on exactly the right frames, which is the half that takes
     * the time - typing the words is quick once the timing is done for you.
     */
    fun generateCaptions() {
        val current = _state.value
        if (current.captions.running) return
        // Every shot of the main track, from its own file, over the part of that
        // file it plays. It used to listen to the first file opened, from its top,
        // and write the file's times straight onto the timeline - so a trimmed,
        // moved or retimed shot got its captions early or late, a second file got
        // none, and speech trimmed away still got a card.
        val shots = current.videoClips.filter { it.layer == 0 && it.uri != null }.sortedBy { it.timelineStartMs }
        if (shots.isEmpty()) return

        captionJob?.cancel()
        val run = CaptionRun("Auto-caption ${UUID.randomUUID()}")
        captionRun = run
        _state.update {
            it.copy(captions = CaptionProgress(running = true, stage = "Listening to the audio"))
        }
        // The run's undo step opens now, where the run began, and every line
        // that lands is folded into it - see [landAutoCaption] - so one undo takes
        // the whole run back, whatever was edited while it ran. Nothing is
        // removed yet: the previous run's lines go when the first new line
        // arrives. Taking them here, before a single byte was decoded, meant a
        // pass that found no sound or was stopped straight away left forty
        // hand-corrected lines gone and nothing in their place.
        history.record("Auto-caption", _state.value.editSnapshot, System.currentTimeMillis(), tag = run.tag)
        publishHistory()

        captionJob = viewModelScope.launch {
            try {
                captionPass(run, shots)
            } finally {
                settleCaptionRun(run)
            }
        }
    }

    /** The listening and transcribing of one auto-caption run, lines landing as they are done. */
    private suspend fun captionPass(run: CaptionRun, shots: List<Clip>) {
        // One decode and one pass of the segmenter per file, however many
        // shots are cut from it.
        val decoded = HashMap<Uri, MonoPcm?>()
        val found = HashMap<Uri, List<SpeechSegment>>()
        val planned = ArrayList<PlannedCaption>()
        var anySound = false
        for (shot in shots) {
            val uri = shot.uri ?: continue
            // 16 kHz mono is what speech recognisers expect, and it is plenty
            // for finding utterance boundaries.
            val pcm = if (decoded.containsKey(uri)) decoded[uri] else PcmDecoder.decodeMono(
                getApplication(), uri,
                targetSampleRate = 16_000,
                maxDurationMs = 30 * 60_000L
            ).also { decoded[uri] = it }
            if (pcm == null) continue
            anySound = true
            val segments = found[uri] ?: withContext(Dispatchers.Default) { SpeechSegmenter.segment(pcm) }
                .also { found[uri] = it }
            EditRules.speechInWindow(
                segments.map { Span(it.startMs, it.endMs) },
                shot.sourceInMs, shot.sourceOutMs, MIN_CAPTION_MS
            ).forEach { planned += PlannedCaption(shot.id, pcm, SpeechSegment(it.startMs, it.endMs)) }
        }

        if (!anySound) {
            // Said in the panel, as what it is. The failure card this used to
            // raise talked about audio-only exports.
            _state.update { it.copy(captions = CaptionProgress(finished = true, noAudio = true)) }
            return
        }
        if (planned.isEmpty()) {
            _state.update { it.copy(captions = CaptionProgress(finished = true, total = 0)) }
            return
        }

        val canTranscribe = Transcriber.isAvailable(getApplication())
        _state.update {
            it.copy(
                captions = CaptionProgress(
                    running = true,
                    stage = if (canTranscribe) "Transcribing" else "Timing the captions",
                    total = planned.size,
                    recognitionAvailable = canTranscribe
                )
            )
        }

        val language = Locale.getDefault().toLanguageTag()
        var transcribed = 0
        var made = 0

        // Each line lands on the timeline as soon as it is done, rather than all
        // of them at the end. So stopping part-way keeps what was already made,
        // and a long clip shows its captions arriving instead of a spinner.
        planned.forEachIndexed { index, plan ->
            val words = if (canTranscribe) {
                Transcriber.transcribe(getApplication(), plan.pcm, plan.segment, language)
            } else null
            // A recogniser that ignores cancellation can still hand back words
            // after Stop; they must not land.
            currentCoroutineContext().ensureActive()
            if (!words.isNullOrBlank()) transcribed++
            if (landAutoCaption(run, plan, words?.takeIf { it.isNotBlank() } ?: "")) made++
            _state.update { it.copy(captions = it.captions.copy(transcribed = transcribed, done = index + 1)) }
        }

        _state.update {
            it.copy(
                captions = CaptionProgress(
                    finished = true,
                    total = made,
                    transcribed = transcribed,
                    done = planned.size,
                    recognitionAvailable = canTranscribe
                )
            )
        }
    }

    /**
     * One auto-caption run: the tag its undo step carries, and whether any line
     * has landed yet - the first one is what retires the previous run's lines,
     * and a run that lands none leaves no step behind.
     */
    private class CaptionRun(val tag: String) {
        var landed = false
    }

    /** The run in progress, or null once it has finished or been stopped. */
    private var captionRun: CaptionRun? = null

    /**
     * After a run, whichever way it ended. A run that made nothing - no sound, no
     * speech, stopped before its first line - changed nothing, and an "Undo:
     * Auto-caption" that does nothing is not left on the button.
     */
    private fun settleCaptionRun(run: CaptionRun) {
        if (!run.landed) {
            history.drop(run.tag)
            publishHistory()
        }
        if (captionRun === run) captionRun = null
    }

    /** One line of speech to caption: which shot carries it, and where it is in that shot's file. */
    private class PlannedCaption(val clipId: String, val pcm: MonoPcm, val segment: SpeechSegment)

    /**
     * Puts one auto-caption on the timeline, where its words are heard.
     *
     * Mapped through the shot as it is *now*: a shot trimmed or moved while the
     * run was listening carries its speech with it, and speech it has since
     * trimmed away is dropped.
     *
     * The line is part of the run's undo step, however much has been edited since
     * the run began - [UndoStack.amend] writes it into every state recorded after
     * that step. It used to be pushed on top like an edit, which closed whatever
     * the person was doing each time a line landed: typing a correction while
     * lines arrived became a dozen alternating steps, and a sixty-line run
     * became sixty steps that pushed the one holding the previous run's lines
     * out of the history.
     *
     * The first line to land takes the previous run's lines away, so a second
     * run replaces the first instead of stacking a copy of every line on it -
     * and only once there is something to replace them with.
     */
    private fun landAutoCaption(run: CaptionRun, plan: PlannedCaption, text: String): Boolean {
        if (captionRun !== run) return false
        val shot = _state.value.videoClips.firstOrNull { it.id == plan.clipId } ?: return false
        val start = maxOf(plan.segment.startMs, shot.sourceInMs)
        val end = minOf(plan.segment.endMs, shot.sourceOutMs)
        if (end - start < MIN_CAPTION_MS) return false
        val line = TextOverlayItem(
            id = AUTO_CAPTION_PREFIX + UUID.randomUUID(),
            text = text,
            startMs = shot.timelineAtSource(start),
            endMs = shot.timelineAtSource(end),
            colorArgb = android.graphics.Color.WHITE
        )
        if (line.endMs <= line.startMs) return false
        val replacing = !run.landed
        val land = { lines: List<TextOverlayItem> ->
            (if (replacing) lines.filterNot { it.isAutoCaption } else lines) + line
        }
        if (!history.amend(run.tag) { it.copy(textOverlays = land(it.textOverlays)) }) return false
        run.landed = true
        _state.update { current ->
            val kept = land(current.textOverlays)
            // A selected line of the previous run has gone; nothing is selected in its place.
            val selectionGone = current.textOverlays.any { it.id == current.selectedClipId } &&
                kept.none { it.id == current.selectedClipId }
            current.copy(
                textOverlays = kept,
                selectedClipId = if (selectionGone) null else current.selectedClipId
            )
        }
        edited()
        return true
    }

    /**
     * Stops auto-captioning where it has got to.
     *
     * There was no way to do this, and on a long clip - or a phone whose recogniser
     * went quiet - the panel said "Listening" for as long as the editor stayed
     * open. The lines already made stay on the timeline; only the rest are dropped.
     */
    fun stopCaptions() {
        if (!_state.value.captions.running) return
        captionJob?.cancel()
        captionJob = null
        // Nothing more lands from here, even from a recogniser that does not
        // notice it was cancelled.
        captionRun = null
        _state.update {
            val progress = it.captions
            it.copy(
                captions = CaptionProgress(
                    finished = true,
                    stopped = true,
                    total = progress.done,
                    transcribed = progress.transcribed,
                    done = progress.done,
                    recognitionAvailable = progress.recognitionAvailable
                )
            )
        }
    }

    /**
     * The words of one caption, typed. A run of typing in one line is one undo
     * step - pauses between words included - and leaving the field ends it.
     */
    fun updateCaptionText(id: String, text: String) =
        record("Caption text", gesture = typingGesture(id), holdMs = TYPING_HOLD_MS) {
            _state.update { current ->
                current.copy(
                    textOverlays = current.textOverlays.map {
                        if (it.id == id) it.copy(text = text) else it
                    }
                )
            }
        }

    /**
     * Every caption, gone - asked first by the panel, and one undo away after.
     * It used to be neither: one tap on "Clear all" and forty hand-corrected lines
     * were gone, with the next autosave writing the empty list over the draft.
     */
    fun clearCaptions() {
        if (_state.value.captions.running) stopCaptions()
        record("Clear captions") {
            // Stickers are not captions, and clearing the words should not take them.
            _state.update { it.copy(textOverlays = it.textOverlays.filter { o -> o.sticker }, captions = CaptionProgress()) }
        }
    }

    /** Brings in a transcript made anywhere else. */
    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (raw == null) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            val cues = SrtFile.parse(raw)
            if (cues.isEmpty()) {
                _state.update { it.copy(failure = SquishError.CaptionsUnreadable()) }
                return@launch
            }
            record("Import subtitles") {
                _state.update { current ->
                    current.copy(
                        textOverlays = current.textOverlays + cues.map { cue ->
                            TextOverlayItem(
                                id = UUID.randomUUID().toString(),
                                text = cue.text,
                                startMs = cue.startMs,
                                endMs = cue.endMs,
                                colorArgb = android.graphics.Color.WHITE
                            )
                        },
                        // An auto-caption run in progress keeps its own progress line.
                        captions = if (current.captions.running) current.captions
                        else CaptionProgress(finished = true, imported = cues.size)
                    )
                }
            }
        }
    }

    /** Writes the captions out so they can be used anywhere else. */
    fun exportSrt(target: Uri, onDone: (Boolean) -> Unit) {
        val cues = _state.value.textOverlays
            .filterNot { it.sticker }
            .sortedBy { it.startMs }
            .map { SrtCue(it.startMs, it.endMs, it.text) }
            .filter { it.text.isNotBlank() }

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                        it.write(SrtFile.format(cues).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    // ---- Motion tracking ------------------------------------------------------------

    fun setTrackPoint(x: Float, y: Float) = _state.update {
        it.copy(tracking = it.tracking.copy(pointX = x.coerceIn(0f, 1f), pointY = y.coerceIn(0f, 1f)))
    }

    fun setTrackBox(fraction: Float) = _state.update {
        it.copy(tracking = it.tracking.copy(boxFraction = fraction.coerceIn(0.06f, 0.35f)))
    }

    fun clearTrack() {
        trackJob?.cancel()
        _state.update { it.copy(tracking = TrackProgress(pointX = it.tracking.pointX, pointY = it.tracking.pointY)) }
    }

    /**
     * Follows whatever is under the chosen point through the clip. The result is
     * held rather than applied: a track is a measurement, and what it drives - a
     * caption, a layer - is a separate decision.
     */
    fun startTracking(clipId: String) {
        val current = _state.value
        if (current.tracking.running) return
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        trackJob?.cancel()
        _state.update {
            it.copy(tracking = it.tracking.copy(running = true, finished = false, failed = false, track = null))
        }

        trackJob = viewModelScope.launch {
            val analysis = analysisSourceFor(uri, current)
            val result = TrackRunner.track(
                context = getApplication(),
                uri = analysis.uri,
                sourceWidth = analysis.width,
                sourceHeight = analysis.height,
                fps = current.fps,
                fromMs = clip.sourceInMs,
                toMs = clip.sourceOutMs,
                startXFraction = current.tracking.pointX,
                startYFraction = current.tracking.pointY,
                boxFraction = current.tracking.boxFraction,
                onProgress = { done, total ->
                    _state.update { it.copy(tracking = it.tracking.copy(done = done, total = total)) }
                }
            )
            _state.update {
                it.copy(
                    tracking = it.tracking.copy(
                        running = false,
                        finished = true,
                        failed = result == null,
                        track = result,
                        clipId = clipId
                    )
                )
            }
        }
    }

    /** Source time of the tracked clip into timeline time. */
    private fun trackInTimelineTime(track: MotionTrack, clip: Clip): MotionTrack {
        val delta = clip.timelineStartMs - clip.sourceInMs
        return MotionTrack(track.samples.map { it.copy(atMs = it.atMs + delta) })
    }

    /**
     * Pins a mask to the track - the point of which is hiding a face or a plate.
     *
     * The mask lives on the clip being tracked, so the track goes in as it was
     * measured: in source time, which is also how the mask evaluates it. No
     * conversion, and nothing to get backwards.
     */
    fun pinMaskToTrack(clipId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        if (current.tracking.clipId != clipId) return
        record("Pin mask") {
            mutateTimeline { timeline ->
                val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
                val existing = clip.mask ?: Mask(
                    shape = MaskShape.Ellipse,
                    widthFraction = current.tracking.boxFraction * 1.4f,
                    heightFraction = current.tracking.boxFraction * 1.4f,
                    mode = MaskMode.Pixelate
                )
                timeline.copy(
                    clips = timeline.clips.map {
                        if (it.id == clipId) it.copy(mask = existing.copy(track = track)) else it
                    }
                )
            }
        }
    }

    fun unpinMask(clipId: String) = record("Unpin mask") {
        mutateTimeline { timeline ->
            timeline.copy(
                clips = timeline.clips.map {
                    if (it.id == clipId) it.copy(mask = it.mask?.copy(track = null)) else it
                }
            )
        }
    }

    fun pinCaptionToTrack(captionId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        val clip = current.videoClips.firstOrNull { it.id == current.tracking.clipId } ?: return
        val timed = trackInTimelineTime(track, clip)
        record("Pin text") {
            _state.update { state ->
                state.copy(
                    textOverlays = state.textOverlays.map {
                        if (it.id == captionId) it.copy(track = timed) else it
                    }
                )
            }
        }
    }

    fun unpinCaption(captionId: String) = record("Unpin text") {
        _state.update { state ->
            state.copy(textOverlays = state.textOverlays.map {
                if (it.id == captionId) it.copy(track = null) else it
            })
        }
    }

    /**
     * Pins a layer to the track, as keyframes on that layer.
     *
     * Written into the ordinary keyframe track rather than a private one: unlike
     * stabilization, this *is* an edit, and you should be able to nudge it
     * afterward without the app arguing.
     */
    fun pinLayerToTrack(layerClipId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        val source = current.videoClips.firstOrNull { it.id == current.tracking.clipId } ?: return
        val layer = current.videoClips.firstOrNull { it.id == layerClipId } ?: return
        val timed = trackInTimelineTime(track, source)

        val keys = timed.samples.map { sample ->
            Keyframe(
                atMs = (sample.atMs - layer.timelineStartMs).coerceAtLeast(0L),
                transform = Transform(
                    scale = layer.scale * sample.scale,
                    // Track fractions run 0 to 1 across the frame; transform
                    // offsets run -1 to 1 from the center.
                    offsetXFraction = (sample.xFraction - 0.5f) * 2f,
                    offsetYFraction = (sample.yFraction - 0.5f) * 2f,
                    rotationDegrees = layer.rotation
                ),
                easing = KeyframeEasing.Linear
            )
        }.sortedBy { it.atMs }

        record("Pin layer") {
            _state.update { state ->
                state.copy(
                    videoClips = state.videoClips.map {
                        if (it.id == layerClipId) it.copy(keyframes = keys) else it
                    }
                )
            }
        }
    }

    // ---- Stabilization ------------------------------------------------------------

    fun setStabilizeStrength(value: Float) = record("Stabilize strength", gesture = "Stabilize strength") {
        _state.update { it.copy(stabilizeStrength = value.coerceIn(0f, 1f)) }
    }

    /**
     * Measures the shake in a clip and writes the correction.
     *
     * Analysis only - it produces a keyframe track, which the existing transform
     * effect then applies in the preview and the export alike. Nothing about
     * rendering changes.
     *
     * The result is an undo step of its own, recorded when it lands rather than
     * when the button was pressed: anything edited during the half-minute of
     * measuring stays its own step, before this one.
     */
    fun stabilizeClip(clipId: String) {
        val current = _state.value
        if (current.stabilize.running) return
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        stabilizeJob?.cancel()
        _state.update { it.copy(stabilize = StabilizeProgress(running = true, clipId = clipId)) }

        stabilizeJob = viewModelScope.launch {
            val analysis = analysisSourceFor(uri, current)
            val result = Stabilizer.analyze(
                context = getApplication(),
                uri = analysis.uri,
                sourceWidth = analysis.width,
                sourceHeight = analysis.height,
                fps = current.fps,
                fromMs = clip.sourceInMs,
                toMs = clip.sourceOutMs,
                strength = current.stabilizeStrength,
                onProgress = { done, total ->
                    _state.update { it.copy(stabilize = it.stabilize.copy(done = done, total = total)) }
                }
            )

            if (result == null) {
                _state.update {
                    it.copy(stabilize = StabilizeProgress(finished = true, failed = true, clipId = clipId))
                }
                return@launch
            }

            recordLate(
                "Stabilize",
                edit = { snapshot ->
                    snapshot.copy(videoClips = snapshot.videoClips.map {
                        if (it.id == clipId) it.copy(stabilizer = result.keyframes) else it
                    })
                },
                alongside = {
                    it.copy(
                        stabilize = StabilizeProgress(
                            finished = true,
                            crop = result.crop,
                            framesAnalysed = result.framesAnalysed,
                            clipId = clipId
                        )
                    )
                }
            )
        }
    }

    /**
     * Takes the correction off one clip. A measurement running on some other
     * clip carries on; it used to be cancelled by removing this one's.
     */
    fun clearStabilization(clipId: String) {
        val running = _state.value.stabilize
        if (running.clipId == clipId) stabilizeJob?.cancel()
        record("Remove stabilization") {
            _state.update { state ->
                state.copy(
                    videoClips = state.videoClips.map {
                        if (it.id == clipId) it.copy(stabilizer = emptyList()) else it
                    },
                    stabilize = if (state.stabilize.clipId == clipId) StabilizeProgress() else state.stabilize
                )
            }
        }
    }

    // ---- Masking --------------------------------------------------------------

    fun setMask(clipId: String, mask: Mask?) = record(if (mask == null) "Remove mask" else "Mask") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = mask) else it })
        }
    }

    /**
     * Changes the mask. A choice - shape, mode, invert - is a step; a slider is a
     * gesture of its own, per slider and per clip, so dragging Width and then
     * Height is two steps however quickly one follows the other.
     */
    fun updateMask(
        clipId: String,
        shape: MaskShape? = null,
        centerX: Float? = null,
        centerY: Float? = null,
        width: Float? = null,
        height: Float? = null,
        rotation: Float? = null,
        feather: Float? = null,
        cornerRadius: Float? = null,
        inverted: Boolean? = null,
        mode: MaskMode? = null,
        strength: Float? = null
    ) {
        val sliders = fieldsNamed(
            "x" to centerX, "y" to centerY, "width" to width, "height" to height,
            "rotation" to rotation, "feather" to feather, "corner" to cornerRadius, "strength" to strength
        )
        record("Mask", gesture = if (sliders.isEmpty()) null else "Mask $sliders $clipId") {
            mutateTimeline { timeline ->
                val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
                val current = clip.mask ?: Mask()
                val next = current.copy(
                    shape = shape ?: current.shape,
                    centerXFraction = (centerX ?: current.centerXFraction).coerceIn(-1.5f, 1.5f),
                    centerYFraction = (centerY ?: current.centerYFraction).coerceIn(-1.5f, 1.5f),
                    widthFraction = (width ?: current.widthFraction).coerceIn(0.02f, 2f),
                    heightFraction = (height ?: current.heightFraction).coerceIn(0.02f, 2f),
                    rotationDegrees = (rotation ?: current.rotationDegrees).coerceIn(-180f, 180f),
                    feather = (feather ?: current.feather).coerceIn(0.001f, 0.5f),
                    cornerRadius = (cornerRadius ?: current.cornerRadius).coerceIn(0f, 1f),
                    inverted = inverted ?: current.inverted,
                    mode = mode ?: current.mode,
                    strength = (strength ?: current.strength).coerceIn(0f, 1f)
                )
                timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = next) else it })
            }
        }
    }

    // ---- Motion and keyframes ---------------------------------------------------

    /**
     * Edits the placement of a clip at the playhead.
     *
     * If the clip is not animated this simply moves it. If it *is* animated, the
     * edit lands as a keyframe at the playhead - creating one if there is not
     * already a key there. That is auto-keying, and it is how every editor behaves:
     * once you have said "this shot moves", changing the picture at a moment in time
     * can only sensibly mean "and here is where it should be at that moment".
     *
     * Starts from the user's own transform, never the drawn one: see
     * [userTransformAt]. Each slider on each clip is its own gesture.
     */
    fun setClipTransform(
        clipId: String,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null,
        rotation: Float? = null
    ) = record(
        "Motion",
        gesture = "Motion ${fieldsNamed("scale" to scale, "x" to offsetX, "y" to offsetY, "rotation" to rotation)} $clipId"
    ) {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val playhead = timeline.playheadMs
            val current = clip.userTransformAt(playhead)
            val next = Transform(
                scale = (scale ?: current.scale).coerceIn(0.1f, 4f),
                offsetXFraction = (offsetX ?: current.offsetXFraction).coerceIn(-1.5f, 1.5f),
                offsetYFraction = (offsetY ?: current.offsetYFraction).coerceIn(-1.5f, 1.5f),
                rotationDegrees = (rotation ?: current.rotationDegrees).coerceIn(-180f, 180f)
            )

            val updated = if (clip.keyframes.isEmpty()) {
                clip.copy(
                    scale = next.scale,
                    offsetXFraction = next.offsetXFraction,
                    offsetYFraction = next.offsetYFraction,
                    rotation = next.rotationDegrees
                )
            } else {
                val at = (playhead - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
                clip.copy(keyframes = clip.keyframes.upsert(Keyframe(at, next, easingNear(clip, at))))
            }
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /**
     * Pins the clip's placement at the playhead as a control point - the
     * placement the editor set, not the stabilizer's correction for this one frame.
     */
    fun addKeyframeAtPlayhead(clipId: String) = record("Add key") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val at = (timeline.playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
            val here = clip.userTransformAt(timeline.playheadMs)
            val updated = clip.copy(keyframes = clip.keyframes.upsert(Keyframe(at, here, easingNear(clip, at))))
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    fun removeKeyframe(clipId: String, atMs: Long) = record("Remove key") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val updated = clip.copy(keyframes = clip.keyframes.filterNot { it.atMs == atMs })
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    fun setKeyframeEasing(clipId: String, atMs: Long, easing: KeyframeEasing) = record("Key easing") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val updated = clip.copy(
                keyframes = clip.keyframes.map { if (it.atMs == atMs) it.copy(easing = easing) else it }
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /** Drops the animation, leaving the clip wherever it was on its first frame. */
    fun clearKeyframes(clipId: String) = record("Clear keys") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            // The pose on the clip's first frame, which is the first key's only
            // while no trim has hidden a key before it.
            val settled = clip.placementAt(clip.timelineStartMs)
            val updated = clip.copy(
                keyframes = emptyList(),
                scale = settled.scale,
                offsetXFraction = settled.offsetXFraction,
                offsetYFraction = settled.offsetYFraction,
                rotation = settled.rotationDegrees
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /** The one-tap moves people actually want, as a pair of keys across the clip. */
    fun applyMotionPreset(clipId: String, preset: MotionPreset) = record(preset.label) {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val end = clip.durationMs.coerceAtLeast(MIN_CLIP_MS)
            val (from, to) = preset.endpoints()
            val updated = clip.copy(
                keyframes = listOf(
                    Keyframe(0L, from, KeyframeEasing.Smooth),
                    Keyframe(end, to, KeyframeEasing.Smooth)
                )
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /**
     * A new key inherits the easing of the segment it lands in, so inserting a
     * control point in the middle of a smooth move does not put a linear kink in it.
     */
    private fun easingNear(clip: Clip, atMs: Long): KeyframeEasing =
        clip.keyframes.lastOrNull { it.atMs <= atMs }?.easing
            ?: clip.keyframes.firstOrNull()?.easing
            ?: KeyframeEasing.Smooth

    /**
     * Inserts a key, or replaces the one already at that moment. The tolerance is a
     * frame: two keys a millisecond apart are a fight, not an animation.
     */
    private fun List<Keyframe>.upsert(key: Keyframe): List<Keyframe> {
        val tolerance = _state.value.frameMs
        val without = filterNot { abs(it.atMs - key.atMs) <= tolerance }
        return (without + key).sortedBy { it.atMs }
    }

    /** Sets the zoom directly, which is how the strip answers a fit request. */
    // One pair of limits for the zoom, shared with the strip. They were written
    // out again here, so the pinch and the buttons disagreed about how far in you
    // could go - and the strip's own ceiling had moved.
    fun setPixelsPerSecond(value: Float) =
        _state.update { it.copy(pixelsPerSecond = value.coerceIn(ZOOM_MIN, ZOOM_MAX)) }

    /** Asks the strip to fit the whole edit across its width. */
    fun fitTimeline() = _state.update { it.copy(fitNonce = it.fitNonce + 1) }

    fun zoomIn() = _state.update { it.copy(pixelsPerSecond = it.toTimeline().zoomedBy(1.35f).pixelsPerSecond) }

    fun zoomOut() = _state.update { it.copy(pixelsPerSecond = it.toTimeline().zoomedBy(1f / 1.35f).pixelsPerSecond) }

    /** Timeline drag. Each lane writes back to whichever model owns it. */
    /**
     * A strip drag: the clip to start at [startMs], as near as its track allows.
     * The step is taken from the clip as it is now, not as the strip last drew it
     * - a second touch event can arrive before the first one's move is on screen,
     * and a step worked out there would be applied twice.
     */
    fun moveClipTo(clipId: String, startMs: Long) {
        val current = _state.value
        val from = current.textOverlays.firstOrNull { it.id == clipId }?.startMs
            ?: (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }?.timelineStartMs
            ?: return
        if (startMs != from) moveClip(clipId, startMs - from)
    }

    // One gesture per clip, so dragging one, then another, is two steps - but
    // the hundred frames of a single drag are one. A drag on the main track that
    // has not yet crossed a neighbour's middle changes nothing, and [record]
    // files nothing for it.
    fun moveClip(clipId: String, deltaMs: Long) = record("Move clip", gesture = "Move $clipId") {
        if (_state.value.textOverlays.any { it.id == clipId }) shiftOverlay(clipId, deltaMs)
        else mutateTimeline { it.withClipMoved(clipId, deltaMs) }
    }

    /** Timeline edge drag - the handles on a selected clip. One drag is one step. */
    fun trimClip(clipId: String, startDeltaMs: Long, endDeltaMs: Long) =
        record("Trim clip", gesture = "Trim $clipId") { applyTrim(clipId, startDeltaMs, endDeltaMs) }

    /**
     * A trim by a button - a frame nudge, "set to playhead". Each press is its own
     * step: five nudges and one undo used to step back all five, because they
     * shared a label and arrived inside the old coalescing window.
     */
    private fun trimOnce(clipId: String, startDeltaMs: Long, endDeltaMs: Long) =
        record("Trim clip") { applyTrim(clipId, startDeltaMs, endDeltaMs) }

    private fun applyTrim(clipId: String, startDeltaMs: Long, endDeltaMs: Long) {
        if (_state.value.textOverlays.any { it.id == clipId }) resizeOverlay(clipId, startDeltaMs, endDeltaMs)
        else mutateTimeline { it.withClipTrimmed(clipId, startDeltaMs, endDeltaMs) }
    }

    /**
     * Cut at the playhead.
     *
     * With a caption or sticker selected and the playhead on it, that item is cut
     * in two, same words and style on both sides; with an effect, the effect - a
     * sliver too short to stand is refused, as a clip's is. Otherwise a razor
     * through picture and sound alike - this used to be handed only the video
     * clips, which is why a music bed could never be cut on the strip.
     *
     * A selected caption used to be ignored: the button lit, the caption stayed
     * whole, and the video and music underneath were cut instead. A cut that
     * changes nothing - no clip under the playhead, or a sliver too short to
     * split - no longer leaves an "Undo: Cut" that undoes nothing.
     */
    fun splitAtPlayhead() {
        val current = _state.value
        val selected = current.selectedClipId
        val at = current.playheadMs
        val text = current.textOverlays.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }
        if (text != null) {
            val halves = EditRules.splitAt(text.startMs, text.endMs, at, MIN_CLIP_MS) ?: return
            record("Cut") {
                val second = text.copy(
                    id = if (text.isAutoCaption) AUTO_CAPTION_PREFIX + UUID.randomUUID() else UUID.randomUUID().toString(),
                    startMs = halves.second.startMs,
                    endMs = halves.second.endMs
                )
                _state.update { s ->
                    s.copy(
                        textOverlays = s.textOverlays.flatMap {
                            if (it.id == text.id) listOf(it.copy(endMs = halves.first.endMs), second) else listOf(it)
                        },
                        selectedClipId = second.id
                    )
                }
            }
            return
        }
        val effect = current.effects.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }
        if (effect != null) {
            val halves = EditRules.splitAt(effect.startMs, effect.endMs, at, MIN_EFFECT_MS) ?: return
            record("Cut") {
                val second = effect.copy(
                    id = UUID.randomUUID().toString(),
                    startMs = halves.second.startMs,
                    endMs = halves.second.endMs
                )
                _state.update { s ->
                    s.copy(
                        effects = s.effects.flatMap {
                            if (it.id == effect.id) listOf(it.copy(endMs = halves.first.endMs), second) else listOf(it)
                        },
                        selectedClipId = second.id
                    )
                }
            }
            return
        }
        record("Cut") { mutateTimeline { it.withSplitAtPlayhead() } }
    }

    /** Pull the base track back end to end. Deliberate, never automatic. */
    fun closeGaps() = record("Close gaps") { mutateTimeline { it.rippleVideo() } }

    fun deleteSelectedClip() {
        val selected = _state.value.selectedClipId ?: return
        record("Delete") {
            if (_state.value.textOverlays.any { it.id == selected }) dropTextOverlay(selected)
            else if (_state.value.effects.any { it.id == selected }) {
                _state.update { it.copy(effects = it.effects.filterNot { e -> e.id == selected }) }
            } else mutateTimeline { it.withClipRemoved(selected) }
            _state.update { it.copy(selectedClipId = null) }
        }
    }

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
        history.record(label, before, now, gesture, holdMs)
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

    /**
     * The caption's text field lost focus. Only that caption's typing ends: the
     * panel may be showing a dozen rows, and a row that never had the cursor has
     * no business closing a drag or a run of typing somewhere else.
     */
    fun endCaptionTyping(id: String) = history.endGesture(typingGesture(id))

    private fun typingGesture(id: String) = "Text $id"

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
     * [landAutoCaption]), so they survive the undo and the redo. Every undo used
     * to stop the run, and nudging a slider during a long pass then undoing the
     * nudge threw away the rest of the pass.
     */
    fun undo() {
        if (captionRun?.let { history.undoTag == it.tag } == true) stopCaptions()
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
            current.copy(
                videoClips = video,
                audioClips = next.clips.filter { it.kind == ClipKind.Audio },
                selectedClipId = next.selectedClipId,
                effects = fitted
            )
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

    /** Close enough to a butt cut that a retime should carry the next clip along. */
    private val TOUCHING_MS = 40L

    /** The id on the end of an undo label - " 16ad0793-d41b-…", or a template's " tpl-…". */
    private val ID_SUFFIX = Regex(""" \S*[0-9a-f]{8}-[0-9a-f]{4}-\S*$""")

    /**
     * The rate the beat analysis runs at, and how much of a track it will listen to.
     *
     * 8kHz is plenty: everything that marks a beat - kick, snare, hat - has ample
     * energy below 4kHz, and halving the rate halves both the decode and the
     * hundreds of thousands of butterflies the FFT does over a long track.
     *
     * Six minutes covers any song. Beyond that the tempo has almost certainly
     * moved anyway, and the array would be twenty megabytes of floats.
     */
    private val BEAT_ANALYSIS_RATE = 8_000
    private val BEAT_MAX_ANALYSIS_MS = 6 * 60 * 1000L

    /**
     * A caption or sticker dragged along the strip: whole, and inside the
     * picture. See [EditRules.clampedShift].
     */
    private fun shiftOverlay(id: String, deltaMs: Long) {
        _state.update { current ->
            val picture = current.trimmedDurationMs
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else {
                        val delta = EditRules.clampedShift(it.startMs, it.endMs, deltaMs, picture)
                        // A pinned track stays put: it says where the thing being
                        // followed is at each moment, and moving the caption in
                        // time does not move that.
                        it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta)
                    }
                }
            )
        }
    }

    /** A caption or sticker's ends dragged: see [EditRules.resized]. */
    private fun resizeOverlay(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _state.update { current ->
            val picture = current.trimmedDurationMs
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else {
                        val span = EditRules.resized(it.startMs, it.endMs, startDeltaMs, endDeltaMs, picture, MIN_CLIP_MS)
                        it.copy(startMs = span.startMs, endMs = span.endMs)
                    }
                }
            )
        }
    }

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

        _state.update { it.copy(isExporting = true, failure = null, exportProgress = ExportProgress()) }

        exportJob = viewModelScope.launch {
            // The edit as it is goes to disk before the encode starts. Nothing is
            // saved while an export runs, and a long export is exactly when the
            // app is most likely to be sent to the back and killed - so a nudge
            // made a second before Render was on disk nowhere for its length.
            withContext(Dispatchers.IO) { persist() }

            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.mp4")

            val result = processor.export(SquishError.exportable(current), outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
            }
            // From here the file exists and is being handed over; there is
            // nothing left to stop. See cancelExport.
            if (exportJob === coroutineContext[Job]) exportJob = null
            _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }

            result.onSuccess { file ->
                // All or nothing: into the gallery, into history, and the draft
                // stamped. Cancelled half-way - the screen leaving in the instant
                // after the encode - the file was in the gallery and the draft
                // never knew it had been exported.
                withContext(NonCancellable) {
                    GallerySaver.publish(getApplication(), file)
                    historyRepository.add(
                        ExportRecord(
                            id = UUID.randomUUID().toString(),
                            title = displayNameOf(sourceUri) ?: "Squished video",
                            outputPath = file.absolutePath,
                            originalSizeBytes = current.originalSizeBytes,
                            outputSizeBytes = file.length(),
                            durationMs = current.trimmedDurationMs,
                            // The shape of the file that was written, which after a
                            // rotation is not the shape it was shot at. The library
                            // sizes its preview from these, so a rotated export
                            // previewed in the wrong shape.
                            width = current.framedWidth,
                            height = current.framedHeight,
                            createdAtMillis = System.currentTimeMillis()
                        )
                    )
                    // The edit stays, marked as exported. It used to be deleted here
                    // on the grounds that the work had reached the gallery - which
                    // made a test render to check a look the one action that could
                    // never be followed by "and now one more change".
                    withContext(Dispatchers.IO) {
                        // Unless a saved edit is still on offer: what was rendered
                        // then is the bare clip, and the stamp would land on the
                        // other edit's draft.
                        if (_state.value.recovery == null) {
                            persist()
                            autosave.markCompleted(current)
                        }
                    }
                }
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                _state.update { it.copy(failure = SquishError.from(throwable)) }
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
     */
    fun cancelExport() {
        val job = exportJob ?: return
        exportJob = null
        job.cancel()
        _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
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
            startProxy(snapshot.sourceUri, meta.displayWidth, meta.displayHeight, meta.durationMs)
            confirmSourceAudio(snapshot.sourceUri)
            (snapshot.clips.mapNotNull { it.uri } + snapshot.sourceUri).distinct().forEach(::checkDecodable)
            restoreAudioWaveforms(snapshot.audioClips)
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

    /**
     * Heavy footage gets a 540p stand-in for the preview player while the export
     * pipeline keeps reading the original. Everything at or below 1080p skips this
     * entirely - it already scrubs smoothly, and transcoding it would cost more
     * time than it ever saves.
     */
    private fun startProxy(uri: Uri, width: Int, height: Int, durationMs: Long) {
        proxyJob?.cancel()

        if (!ProxyEngine.isWorthProxying(width, height, durationMs)) {
            _state.update { it.copy(proxyUri = null, proxyStatus = ProxyStatus.NotNeeded) }
            return
        }

        ProxyEngine.cached(getApplication(), uri)?.let { file ->
            _state.update { it.copy(proxyUri = Uri.fromFile(file), proxyStatus = ProxyStatus.Ready) }
            return
        }

        _state.update { it.copy(proxyUri = null, proxyStatus = ProxyStatus.Building) }
        proxyJob = viewModelScope.launch {
            val file = ProxyEngine.ensure(getApplication(), uri)
            _state.update {
                if (file != null) {
                    it.copy(proxyUri = Uri.fromFile(file), proxyStatus = ProxyStatus.Ready)
                } else {
                    // Not worth a dialogue: the original still plays, just heavier.
                    it.copy(proxyUri = null, proxyStatus = ProxyStatus.Failed)
                }
            }
        }
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
        FilmstripLoader.evictAll()
    }

    private companion object {
        /** Saved-state key: this entry has opened its clip. See [restoredAfterDeath]. */
        const val KEY_OPENED = "opened"
        const val MIN_SYNC_CONFIDENCE = 0.28f
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
        const val DEFAULT_CAPTION_MS = 2_000L

        /** Shorter than this, a stretch of speech is a fragment of a word, not a line. */
        const val MIN_CAPTION_MS = 150L

        /**
         * How long typing in one caption may pause and still be the same undo step.
         * Long enough for a pause between words, short enough that coming back to
         * the line later is a new edit.
         */
        const val TYPING_HOLD_MS = 5_000L

        /** Long enough for a title to arrive, be read and leave. */
        const val DEFAULT_TITLE_MS = 3_000L

        /** An effect lasts two seconds unless stretched - long enough to see, short enough to be a moment. */
        const val DEFAULT_EFFECT_MS = 2_000L
        const val MIN_EFFECT_MS = 100L

        /** Marks what a template added, so the next template replaces it rather than piling on. */
        const val TEMPLATE_PREFIX = "tpl-"
    }
}
