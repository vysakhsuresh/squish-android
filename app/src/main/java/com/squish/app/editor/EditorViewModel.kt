package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectSnapshot
import com.squish.app.data.SrtCue
import com.squish.app.data.SrtFile
import com.squish.app.data.SquishRepositories
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.GallerySaver
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import com.squish.app.media.audio.AudioSyncAnalyzer
import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.BeatMap
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.media.audio.Transcriber
import com.squish.app.media.video.FilmstripLoader
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
import com.squish.app.timeline.zoomedBy
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

class EditorViewModel(application: Application) : AndroidViewModel(application) {

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

    init {
        // Aggressive by design. Each save is atomic, and skipped entirely when
        // nothing changed, so the cost of a tick is one string comparison, and the
        // worst case after a kill is a second and a half of lost work.
        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL)
                val current = _state.value
                if (current.isExporting) continue
                val uri = current.sourceUri ?: continue
                // Off the main thread. viewModelScope is Main, so encoding the
                // timeline to JSON and fsyncing it were both happening on the
                // frame loop, every second and a half, for the whole session -
                // which is exactly the kind of thing that makes a scrub stutter
                // for no visible reason.
                withContext(Dispatchers.IO) {
                    val start = baseline
                    val untouched = start == null || autosave.editKey(current) == start
                    if (untouched) {
                        if (wroteDraft) {
                            autosave.clear(uri)
                            wroteDraft = false
                        }
                    } else if (autosave.save(current)) {
                        wroteDraft = true
                    }
                }
            }
        }
    }

    fun load(uri: Uri) {
        if (loadedUri == uri) return
        loadedUri = uri

        // Read before anything else writes. The autosave timer is already running,
        // and once this session starts saving it will overwrite the very document
        // we might need to recover.
        val recoverable = autosave.peek(uri)

        _state.update { it.copy(sourceUri = uri, isLoadingSource = true) }

        viewModelScope.launch {
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
            offerRecovery(recoverable, uri)
            startProxy(uri, meta.displayWidth, meta.displayHeight, meta.durationMs)

            val pcm = PcmDecoder.decodeMono(getApplication(), uri)
            _state.update {
                it.copy(
                    // A failed decode is not proof of silence - decodeMono gives up
                    // for plenty of reasons that are not "there is no audio here" -
                    // so it can confirm a track but never deny one. The container
                    // already answered that question when the file was probed.
                    sourceHasAudio = it.sourceHasAudio || pcm != null,
                    videoWaveform = pcm?.let { decoded -> WaveformBuilder.build(decoded) }
                )
            }
        }
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
        if (isStart) trimClip(clip.id, delta, 0L) else trimClip(clip.id, 0L, delta)
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
            trimClip(clip.id, intoSource, 0L)
        } else {
            trimClip(clip.id, 0L, intoSource - clip.sourceSpanMs)
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
    fun addAudioTrack(uri: Uri) {
        viewModelScope.launch {
            val trackDuration = ThumbnailExtractor.probeDurationMs(getApplication(), uri)
            val name = displayNameOf(uri) ?: "Audio"

            _state.update { current ->
                val clip = Clip(
                    kind = ClipKind.Audio,
                    uri = uri,
                    label = name,
                    sourceInMs = 0,
                    sourceOutMs = trackDuration,
                    timelineStartMs = current.playheadMs,
                    sourceDurationMs = trackDuration
                )
                current.copy(audioClips = current.audioClips + clip, selectedClipId = clip.id)
            }
            recomputeEstimate()

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

    private fun updateAudioClip(clipId: String, block: (Clip) -> Clip) {
        _state.update { current ->
            current.copy(audioClips = current.audioClips.map { if (it.id == clipId) block(it) else it })
        }
        recomputeEstimate()
    }

    /** Which slice of the audio file plays, in source time. */
    fun setAudioTrim(clipId: String, startMs: Long, endMs: Long) = updateAudioClip(clipId) { clip ->
        val limit = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else endMs
        val start = startMs.coerceIn(0L, (limit - MIN_CLIP_MS).coerceAtLeast(0L))
        clip.copy(sourceInMs = start, sourceOutMs = endMs.coerceIn(start + MIN_CLIP_MS, limit))
    }

    fun placeAudioAtPlayhead(clipId: String) {
        val playhead = _state.value.playheadMs
        updateAudioClip(clipId) { it.copy(timelineStartMs = playhead) }
    }

    fun setAudioClipVolume(clipId: String, volume: Float) =
        updateAudioClip(clipId) { it.copy(volume = volume.coerceIn(0f, 1f)) }

    /**
     * Slides a sound against the picture. Sliding left past the start of the edit
     * is impossible, so the remainder is spent entering the file later instead -
     * which is the same thing to the ear and keeps the nudge from stalling at zero.
     */
    fun nudgeAudioOffset(clipId: String, deltaMs: Long) = updateAudioClip(clipId) { clip ->
        val proposed = clip.timelineStartMs + deltaMs
        if (proposed >= 0) {
            clip.copy(timelineStartMs = proposed)
        } else {
            clip.copy(
                timelineStartMs = 0,
                sourceInMs = (clip.sourceInMs - proposed).coerceAtMost(clip.sourceOutMs - MIN_CLIP_MS)
            )
        }
    }

    fun nudgeAudioOffsetFrames(clipId: String, frames: Int) =
        nudgeAudioOffset(clipId, frames * _state.value.frameMs)

    fun resetAudioAlignment(clipId: String) {
        updateAudioClip(clipId) { it.copy(timelineStartMs = 0, sourceInMs = 0) }
        _state.update { it.copy(syncStatus = SyncStatus.Idle, syncConfidence = 0f) }
    }

    fun setOriginalVolume(volume: Float) = record("Camera level") {
        _state.update { it.copy(originalVolume = volume.coerceIn(0f, 1f)) }
    }

    fun runAutoSync(clipId: String) {
        val current = _state.value
        val videoUri = current.sourceUri ?: return
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
                // A positive offset means the track runs ahead of the picture, so we
                // enter the file later; a negative one delays the cue instead.
                val offset = result.offsetMs
                updateAudioClip(clipId) { existing ->
                    val newIn = offset.coerceAtLeast(0L)
                    existing.copy(
                        sourceInMs = newIn.coerceAtMost((existing.sourceDurationMs - MIN_CLIP_MS).coerceAtLeast(0L)),
                        timelineStartMs = (-offset).coerceAtLeast(0L),
                        sourceOutMs = existing.sourceOutMs.coerceAtLeast(newIn + MIN_CLIP_MS)
                    )
                }
                _state.update {
                    it.copy(syncStatus = SyncStatus.Matched, syncConfidence = result.confidence)
                }
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
     * Not recorded per drag event - `record` coalesces inside its window, so one
     * gesture is one undo step rather than one per frame of movement.
     */
    fun setCropRect(rect: CropRect) = record("Crop") {
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
        _state.update { it.copy(beats = BeatProgress(running = true, clipLabel = label)) }

        beatJob = viewModelScope.launch {
            val pcm = PcmDecoder.decodeMono(
                context = getApplication(),
                uri = uri,
                targetSampleRate = BEAT_ANALYSIS_RATE,
                maxDurationMs = BEAT_MAX_ANALYSIS_MS
            )
            if (pcm == null) {
                _state.update { it.copy(beats = BeatProgress(finished = true, failed = true, clipLabel = label)) }
                return@launch
            }

            val map = withContext(Dispatchers.Default) {
                BeatDetector.detect(pcm.samples, pcm.sampleRate)
            }
            if (map.isEmpty) {
                _state.update { it.copy(beats = BeatProgress(finished = true, failed = true, clipLabel = label)) }
                return@launch
            }

            _state.update { it.copy(beats = map.toProgress(target, label)) }
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
    fun scaleBeats(faster: Boolean) = _state.update { current ->
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

    /** Moves which beat counts as the one on the bar. */
    fun nudgeDownbeat() = _state.update { current ->
        current.copy(beats = current.beats.copy(downbeatOffset = (current.beats.downbeatOffset + 1).mod(4)))
    }

    fun clearBeats() = _state.update { it.copy(beats = BeatProgress()) }

    /**
     * Drops a marker on every nth beat, so every edit that already snaps now snaps
     * to the music: dragging a clip, setting an in point, moving a caption.
     */
    fun markBeats(everyN: Int) = record("Mark beats") {
        _state.update { current ->
            val beats = current.beats.every(everyN)
            if (beats.isEmpty()) current
            else current.copy(markers = beats.sorted().distinct(), snapToMarkers = true)
        }
    }

    /**
     * Cuts the video track on every nth beat.
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
            cuts.forEach { at ->
                mutateTimeline { timeline -> timeline.copy(playheadMs = at).withSplitAtPlayhead() }
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

    /** One rate across the whole clip, which is what the slider and presets set. */
    fun setClipSpeed(clipId: String, speed: Float) = record("Speed") {
        retime(clipId, SpeedRamp.flat(speed))
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
        retime(clipId, base.withPoint(at, speed, clip.sourceSpanMs))
    }

    fun removeSpeedPoint(clipId: String, atMs: Long) {
        val clip = _state.value.let { current ->
            (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }
        } ?: return
        retime(clipId, clip.speedRamp.withoutPoint(atMs))
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

    fun setLookIntensity(value: Float) = record("Look strength") {
        _state.update { it.copy(lookIntensity = value.coerceIn(0f, 1f)) }
    }

    fun setBrightness(value: Float) = record("Brightness") {
        _state.update { it.copy(brightness = value) }
    }
    fun setContrast(value: Float) = record("Contrast") {
        _state.update { it.copy(contrast = value) }
    }
    fun setSaturation(value: Float) = record("Saturation") {
        _state.update { it.copy(saturation = value) }
    }

    /**
     * A blank line starting at the playhead. Spanning the whole clip - which is what
     * this used to do - is never what anyone wants from a caption.
     */
    fun addCaptionAtPlayhead() {
        val current = _state.value
        val start = current.playheadMs
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = "",
            startMs = start,
            endMs = (start + DEFAULT_CAPTION_MS).coerceAtMost(
                current.timelineDurationMs.takeIf { it > start } ?: (start + DEFAULT_CAPTION_MS)
            ),
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
        val current = _state.value
        val start = current.playheadMs
        val end = (start + DEFAULT_TITLE_MS).coerceAtMost(
            current.timelineDurationMs.takeIf { it > start } ?: (start + DEFAULT_TITLE_MS)
        )
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = preset.sample,
            startMs = start,
            endMs = end,
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
        val current = _state.value
        val start = current.playheadMs
        val end = (start + DEFAULT_TITLE_MS).coerceAtMost(
            current.timelineDurationMs.takeIf { it > start } ?: (start + DEFAULT_TITLE_MS)
        )
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = emoji,
            startMs = start,
            endMs = end,
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

    fun changeEffect(id: String, change: (TimedEffect) -> TimedEffect) = record("Effect $id") {
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

    fun removeEffect(id: String) = record("Remove effect") {
        _state.update { it.copy(effects = it.effects.filterNot { e -> e.id == id }) }
    }

    /** Changes how one caption looks or moves. The text and timing are left alone. */
    fun restyleCaption(id: String, change: (TextOverlayItem) -> TextOverlayItem) = record("Style $id") {
        _state.update { current ->
            current.copy(textOverlays = current.textOverlays.map { if (it.id == id) change(it) else it })
        }
    }

    fun removeTextOverlay(id: String) {
        _state.update { it.copy(textOverlays = it.textOverlays.filterNot { item -> item.id == id }) }
    }

    // ---- Timeline -------------------------------------------------------------

    fun addVideoClip(uri: Uri) {
        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            _state.update { current ->
                val clip = Clip(
                    kind = ClipKind.Video,
                    uri = uri,
                    label = displayNameOf(uri) ?: "Clip ${current.videoClips.size + 1}",
                    sourceInMs = 0,
                    sourceOutMs = meta.durationMs,
                    timelineStartMs = current.videoClips.maxOfOrNull { c -> c.timelineEndMs } ?: 0L,
                    sourceDurationMs = meta.durationMs
                )
                current.copy(videoClips = current.videoClips + clip)
            }
            recomputeEstimate()
        }
    }

    fun selectClip(clipId: String?) = _state.update { it.copy(selectedClipId = clipId) }

    /** Adds a clip on an overlay layer, starting at the playhead. */
    fun addOverlayClip(uri: Uri) {
        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            _state.update { current ->
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
                current.copy(videoClips = current.videoClips + clip, selectedClipId = clip.id)
            }
            recomputeEstimate()
        }
    }

    fun setTransition(clipId: String, type: TransitionType, durationMs: Long) =
        mutateTimeline { it.withTransition(clipId, Transition(type, durationMs)) }

    fun changeLayer(clipId: String, delta: Int) = mutateTimeline { it.withLayerChanged(clipId, delta) }

    fun setOverlayGeometry(
        clipId: String,
        opacity: Float? = null,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null
    ) = mutateTimeline { it.withOverlayGeometry(clipId, opacity, scale, offsetX, offsetY) }

    // ---- Green screen -------------------------------------------------------------

    /** Turns keying on for a clip, or off when passed null. */
    fun setChromaKey(clipId: String, key: ChromaKey?) = mutateTimeline { timeline ->
        timeline.copy(
            clips = timeline.clips.map { if (it.id == clipId) it.copy(chromaKey = key) else it }
        )
    }

    fun updateChromaKey(
        clipId: String,
        keyColorArgb: Int? = null,
        similarity: Float? = null,
        smoothness: Float? = null,
        spill: Float? = null
    ) = mutateTimeline { timeline ->
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

    /** The frame under the playhead, for sampling the screen color out of. */
    suspend fun sampleFrame(clip: Clip, atMs: Long): android.graphics.Bitmap? {
        val uri = clip.uri ?: _state.value.sourceUri ?: return null
        val inClip = (clip.sourceInMs + (atMs - clip.timelineStartMs)).coerceIn(clip.sourceInMs, clip.sourceOutMs)
        return ThumbnailExtractor.frameAt(getApplication(), uri, inClip)
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
        val uri = current.sourceUri ?: return
        if (current.captions.running) return

        captionJob?.cancel()
        _state.update {
            it.copy(captions = CaptionProgress(running = true, stage = "Listening to the audio"))
        }

        captionJob = viewModelScope.launch {
            // 16 kHz mono is what speech recognisers expect, and it is plenty for
            // finding utterance boundaries.
            val pcm = PcmDecoder.decodeMono(
                getApplication(), uri,
                targetSampleRate = 16_000,
                maxDurationMs = 30 * 60_000L
            )
            if (pcm == null) {
                _state.update {
                    it.copy(
                        captions = CaptionProgress(finished = true),
                        failure = SquishError.NoAudioTrack()
                    )
                }
                return@launch
            }

            val segments = SpeechSegmenter.segment(pcm)
            if (segments.isEmpty()) {
                _state.update { it.copy(captions = CaptionProgress(finished = true, total = 0)) }
                return@launch
            }

            val canTranscribe = Transcriber.isAvailable(getApplication())
            _state.update {
                it.copy(
                    captions = CaptionProgress(
                        running = true,
                        stage = if (canTranscribe) "Transcribing" else "Timing the captions",
                        total = segments.size,
                        recognitionAvailable = canTranscribe
                    )
                )
            }

            val language = Locale.getDefault().toLanguageTag()
            var transcribed = 0

            // Each line lands on the timeline as soon as it is done, rather than all
            // of them at the end. So stopping part-way keeps what was already made,
            // and a long clip shows its captions arriving instead of a spinner.
            segments.forEachIndexed { index, segment ->
                val words = if (canTranscribe) {
                    Transcriber.transcribe(getApplication(), pcm, segment, language)
                } else null
                if (!words.isNullOrBlank()) transcribed++

                val line = TextOverlayItem(
                    id = UUID.randomUUID().toString(),
                    text = words?.takeIf { it.isNotBlank() } ?: "",
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    colorArgb = android.graphics.Color.WHITE
                )
                _state.update {
                    it.copy(
                        textOverlays = it.textOverlays + line,
                        captions = it.captions.copy(transcribed = transcribed, done = index + 1)
                    )
                }
            }

            _state.update {
                it.copy(
                    captions = CaptionProgress(
                        finished = true,
                        total = segments.size,
                        transcribed = transcribed,
                        done = segments.size,
                        recognitionAvailable = canTranscribe
                    )
                )
            }
        }
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

    fun updateCaptionText(id: String, text: String) {
        _state.update { current ->
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id == id) it.copy(text = text) else it
                }
            )
        }
    }

    fun clearCaptions() {
        captionJob?.cancel()
        // Stickers are not captions, and clearing the words should not take them.
        _state.update { it.copy(textOverlays = it.textOverlays.filter { o -> o.sticker }, captions = CaptionProgress()) }
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
                    captions = CaptionProgress(finished = true, total = cues.size, transcribed = cues.size)
                )
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

    fun unpinMask(clipId: String) = mutateTimeline { timeline ->
        timeline.copy(
            clips = timeline.clips.map {
                if (it.id == clipId) it.copy(mask = it.mask?.copy(track = null)) else it
            }
        )
    }

    fun pinCaptionToTrack(captionId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        val clip = current.videoClips.firstOrNull { it.id == current.tracking.clipId } ?: return
        val timed = trackInTimelineTime(track, clip)
        _state.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map {
                    if (it.id == captionId) it.copy(track = timed) else it
                }
            )
        }
    }

    fun unpinCaption(captionId: String) = _state.update { state ->
        state.copy(textOverlays = state.textOverlays.map {
            if (it.id == captionId) it.copy(track = null) else it
        })
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

        _state.update { state ->
            state.copy(
                videoClips = state.videoClips.map {
                    if (it.id == layerClipId) it.copy(keyframes = keys) else it
                }
            )
        }
    }

    // ---- Stabilization ------------------------------------------------------------

    fun setStabilizeStrength(value: Float) =
        _state.update { it.copy(stabilizeStrength = value.coerceIn(0f, 1f)) }

    /**
     * Measures the shake in a clip and writes the correction.
     *
     * Analysis only - it produces a keyframe track, which the existing transform
     * effect then applies in the preview and the export alike. Nothing about
     * rendering changes.
     */
    fun stabilizeClip(clipId: String) {
        val current = _state.value
        if (current.stabilize.running) return
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        stabilizeJob?.cancel()
        _state.update { it.copy(stabilize = StabilizeProgress(running = true)) }

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
                    it.copy(stabilize = StabilizeProgress(finished = true, failed = true))
                }
                return@launch
            }

            _state.update { state ->
                state.copy(
                    videoClips = state.videoClips.map {
                        if (it.id == clipId) it.copy(stabilizer = result.keyframes) else it
                    },
                    stabilize = StabilizeProgress(
                        finished = true,
                        crop = result.crop,
                        framesAnalysed = result.framesAnalysed
                    )
                )
            }
        }
    }

    fun clearStabilization(clipId: String) {
        stabilizeJob?.cancel()
        _state.update { state ->
            state.copy(
                videoClips = state.videoClips.map {
                    if (it.id == clipId) it.copy(stabilizer = emptyList()) else it
                },
                stabilize = StabilizeProgress()
            )
        }
    }

    // ---- Masking --------------------------------------------------------------

    fun setMask(clipId: String, mask: Mask?) = mutateTimeline { timeline ->
        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = mask) else it })
    }

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
    ) = mutateTimeline { timeline ->
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

    // ---- Motion and keyframes ---------------------------------------------------

    /**
     * Edits the placement of a clip at the playhead.
     *
     * If the clip is not animated this simply moves it. If it *is* animated, the
     * edit lands as a keyframe at the playhead - creating one if there is not
     * already a key there. That is auto-keying, and it is how every editor behaves:
     * once you have said "this shot moves", changing the picture at a moment in time
     * can only sensibly mean "and here is where it should be at that moment".
     */
    fun setClipTransform(
        clipId: String,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null,
        rotation: Float? = null
    ) = mutateTimeline { timeline ->
        val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
        val playhead = timeline.playheadMs
        val current = clip.transformAt(playhead)
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

    /** Pins the clip's current appearance at the playhead as a control point. */
    fun addKeyframeAtPlayhead(clipId: String) = mutateTimeline { timeline ->
        val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
        val at = (timeline.playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
        val here = clip.transformAt(timeline.playheadMs)
        val updated = clip.copy(keyframes = clip.keyframes.upsert(Keyframe(at, here, easingNear(clip, at))))
        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
    }

    fun removeKeyframe(clipId: String, atMs: Long) = mutateTimeline { timeline ->
        val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
        val updated = clip.copy(keyframes = clip.keyframes.filterNot { it.atMs == atMs })
        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
    }

    fun setKeyframeEasing(clipId: String, atMs: Long, easing: KeyframeEasing) = mutateTimeline { timeline ->
        val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
        val updated = clip.copy(
            keyframes = clip.keyframes.map { if (it.atMs == atMs) it.copy(easing = easing) else it }
        )
        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
    }

    /** Drops the animation, leaving the clip wherever it was at the first key. */
    fun clearKeyframes(clipId: String) = mutateTimeline { timeline ->
        val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
        val settled = clip.keyframes.firstOrNull()?.transform ?: clip.staticTransform
        val updated = clip.copy(
            keyframes = emptyList(),
            scale = settled.scale,
            offsetXFraction = settled.offsetXFraction,
            offsetYFraction = settled.offsetYFraction,
            rotation = settled.rotationDegrees
        )
        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
    }

    /** The one-tap moves people actually want, as a pair of keys across the clip. */
    fun applyMotionPreset(clipId: String, preset: MotionPreset) = mutateTimeline { timeline ->
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
    // Labelled per clip so dragging one, then another, is two steps — but the
    // hundred frames of a single drag are one.
    fun moveClip(clipId: String, deltaMs: Long) = record("Move $clipId") {
        if (_state.value.textOverlays.any { it.id == clipId }) shiftOverlay(clipId, deltaMs)
        else mutateTimeline { it.withClipMoved(clipId, deltaMs) }
    }

    /** Timeline edge drag - the handles on a selected clip. */
    fun trimClip(clipId: String, startDeltaMs: Long, endDeltaMs: Long) = record("Trim $clipId") {
        if (_state.value.textOverlays.any { it.id == clipId }) resizeOverlay(clipId, startDeltaMs, endDeltaMs)
        else mutateTimeline { it.withClipTrimmed(clipId, startDeltaMs, endDeltaMs) }
    }

    /**
     * Razor cut at the playhead, on picture and sound alike. This used to be handed
     * only the video clips, which is why a music bed could never be cut on the strip.
     */
    fun splitAtPlayhead() = record("Cut") { mutateTimeline { it.withSplitAtPlayhead() } }

    /** Pull the base track back end to end. Deliberate, never automatic. */
    fun closeGaps() = record("Close gaps") { mutateTimeline { it.rippleVideo() } }

    fun deleteSelectedClip() {
        val selected = _state.value.selectedClipId ?: return
        record("Delete") {
            if (_state.value.textOverlays.any { it.id == selected }) removeTextOverlay(selected)
            else mutateTimeline { it.withClipRemoved(selected) }
            _state.update { it.copy(selectedClipId = null) }
        }
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
    // ---- Undo -------------------------------------------------------------------

    private val history = UndoStack<EditSnapshot>()

    /**
     * Records where the edit was, then makes the change.
     *
     * Every destructive gesture goes through here. [label] names the edit for the
     * button and, just as importantly, groups a continuing gesture: sixty ticks
     * of one slider drag arrive under the same name inside the coalescing window
     * and become one step, so undo lands before the drag rather than one frame
     * into it.
     */
    private fun record(label: String, change: () -> Unit) {
        history.record(label, _state.value.editSnapshot, System.currentTimeMillis())
        change()
        publishHistory()
    }

    private fun publishHistory() = _state.update {
        it.copy(undoLabel = history.undoLabel, redoLabel = history.redoLabel)
    }

    fun undo() {
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

    private fun mutateTimeline(block: (TimelineState) -> TimelineState) {
        _state.update { current ->
            val timeline = TimelineState(
                clips = current.videoClips + current.audioClips,
                selectedClipId = current.selectedClipId,
                playheadMs = current.playheadMs
            )
            val next = block(timeline)
            current.copy(
                videoClips = next.clips.filter { it.kind == ClipKind.Video },
                audioClips = next.clips.filter { it.kind == ClipKind.Audio },
                selectedClipId = next.selectedClipId
            )
        }
        recomputeEstimate()
    }

    /** Close enough to a butt cut that a retime should carry the next clip along. */
    private val TOUCHING_MS = 40L

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

    private fun shiftOverlay(id: String, deltaMs: Long) {
        _state.update { current ->
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else it.copy(
                        startMs = (it.startMs + deltaMs).coerceAtLeast(0L),
                        endMs = (it.endMs + deltaMs).coerceAtLeast(0L)
                    )
                }
            )
        }
    }

    private fun resizeOverlay(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _state.update { current ->
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else {
                        val start = (it.startMs + startDeltaMs).coerceAtLeast(0L)
                        it.copy(startMs = start, endMs = (it.endMs + endDeltaMs).coerceAtLeast(start + 200L))
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

        viewModelScope.launch {
            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.mp4")

            val result = processor.export(current, outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
            }
            _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }

            result.onSuccess { file ->
                // What was just rendered is now the untouched starting point, so the
                // autosave, which keeps ticking, has nothing to write back. Without
                // this, the finished edit reappeared under Unfinished moments later.
                baseline = autosave.editKey(_state.value)
                GallerySaver.publish(getApplication(), file)
                historyRepository.add(
                    ExportRecord(
                        id = UUID.randomUUID().toString(),
                        title = displayNameOf(sourceUri) ?: "Squished video",
                        outputPath = file.absolutePath,
                        originalSizeBytes = current.originalSizeBytes,
                        outputSizeBytes = file.length(),
                        durationMs = current.trimmedDurationMs,
                        width = current.sourceWidth,
                        height = current.sourceHeight,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
                // The work is in the gallery now, so there is nothing left to
                // recover and no reason to offer it on the next launch.
                autosave.markCompleted(sourceUri)
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                _state.update { it.copy(failure = SquishError.from(throwable)) }
            }
        }
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

        // A session already finished by an export was cleared; anything still here
        // ended some other way, which is exactly the case worth recovering.
        _state.update { it.copy(recovery = RecoveryOffer(snapshot)) }
    }

    fun dismissRecovery() {
        _state.value.recovery?.snapshot?.sourceUri?.let { autosave.clear(it) }
        _state.update { it.copy(recovery = null) }
    }

    fun acceptRecovery() {
        val snapshot = _state.value.recovery?.snapshot ?: return
        _state.update { it.copy(recovery = null, isLoadingSource = true) }

        viewModelScope.launch {
            // Metadata is re-probed rather than trusted from the file: the same clip
            // can come back through a different provider with a different rotation.
            val meta = ThumbnailExtractor.probe(getApplication(), snapshot.sourceUri)
            loadedUri = snapshot.sourceUri
            _state.update {
                it.applying(snapshot, meta.durationMs, meta.displayWidth, meta.displayHeight, meta.fps)
            }
            recomputeEstimate()
            startProxy(snapshot.sourceUri, meta.displayWidth, meta.displayHeight, meta.durationMs)

            val pcm = PcmDecoder.decodeMono(getApplication(), snapshot.sourceUri)
            _state.update {
                it.copy(
                    // A failed decode is not proof of silence - decodeMono gives up
                    // for plenty of reasons that are not "there is no audio here" -
                    // so it can confirm a track but never deny one. The container
                    // already answered that question when the file was probed.
                    sourceHasAudio = it.sourceHasAudio || pcm != null,
                    videoWaveform = pcm?.let { decoded -> WaveformBuilder.build(decoded) }
                )
            }
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
        markers = snapshot.markers,
        playheadMs = snapshot.playheadMs,
        outputP = snapshot.outputP,
        fitToSize = snapshot.fitToSize,
        targetSizeMb = snapshot.targetSizeMb,
        audioOnly = snapshot.audioOnly,
        muteOriginal = snapshot.muteOriginal,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
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
        FilmstripLoader.evictAll()
    }

    private companion object {
        const val MIN_SYNC_CONFIDENCE = 0.28f
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
        const val DEFAULT_CAPTION_MS = 2_000L

        /** Long enough for a title to arrive, be read and leave. */
        const val DEFAULT_TITLE_MS = 3_000L

        /** An effect lasts two seconds unless stretched - long enough to see, short enough to be a moment. */
        const val DEFAULT_EFFECT_MS = 2_000L
        const val MIN_EFFECT_MS = 100L
    }
}
