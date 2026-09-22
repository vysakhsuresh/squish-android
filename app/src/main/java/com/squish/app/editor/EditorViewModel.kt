package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectSnapshot
import com.squish.app.data.SquishRepositories
import com.squish.app.media.ExportPresets
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.GallerySaver
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import com.squish.app.media.audio.AudioSyncAnalyzer
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.WaveformBuilder
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.rippleVideo
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withClipMoved
import com.squish.app.timeline.withClipRemoved
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.zoomedBy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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

    init {
        // Aggressive by design. The write is atomic and skipped entirely when
        // nothing changed, so the cost of a tick is one string comparison, and the
        // worst case after a kill is a second and a half of lost work.
        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                val current = _state.value
                if (!current.isExporting) autosave.save(current)
            }
        }
    }

    fun load(uri: Uri) {
        if (loadedUri == uri) return
        loadedUri = uri

        // Read before anything else writes. The autosave timer is already running,
        // and once this session starts saving it will overwrite the very document
        // we might need to recover.
        val recoverable = autosave.peek()

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
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    fps = meta.fps,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    isLoadingSource = false,
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
            offerRecovery(recoverable, uri)
            startProxy(uri, meta.width, meta.height)

            val pcm = PcmDecoder.decodeMono(getApplication(), uri)
            _state.update {
                it.copy(
                    sourceHasAudio = pcm != null,
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
        val offsetInClip = Timecode.quantize(snapped - clip.timelineStartMs, current.fps)
        if (isStart) {
            trimClip(clip.id, offsetInClip, 0L)
        } else {
            trimClip(clip.id, 0L, offsetInClip - clip.durationMs)
        }
    }

    fun setPlayhead(ms: Long) = _state.update { it.copy(playheadMs = ms.coerceIn(0L, it.durationMs)) }

    // ---- Markers --------------------------------------------------------------

    fun addMarkerAtPlayhead() {
        val position = _state.value.playheadMs
        _state.update { current ->
            if (current.markers.any { abs(it - position) < current.frameMs }) current
            else current.copy(markers = (current.markers + position).sorted())
        }
    }

    fun clearMarkers() = _state.update { it.copy(markers = emptyList()) }

    fun setSnapToMarkers(enabled: Boolean) = _state.update { it.copy(snapToMarkers = enabled) }

    private fun snapToNearbyMarker(ms: Long, current: EditorUiState): Long {
        val threshold = (current.durationMs / 100).coerceIn(40L, 400L)
        val nearest = current.markers.minByOrNull { abs(it - ms) } ?: return ms
        return if (abs(nearest - ms) <= threshold) nearest else ms
    }

    // ---- Separate audio track -------------------------------------------------

    fun setAudioTrack(uri: Uri?) {
        syncJob?.cancel()
        if (uri == null) {
            _state.update {
                it.copy(
                    audioTrackUri = null,
                    audioTrackName = null,
                    audioTrackDurationMs = 0,
                    audioTrimStartMs = 0,
                    audioTrimEndMs = 0,
                    audioPlacementMs = 0,
                    audioWaveform = null,
                    syncStatus = SyncStatus.Idle,
                    syncConfidence = 0f
                )
            }
            recomputeEstimate()
            return
        }

        _state.update {
            it.copy(
                audioTrackUri = uri,
                audioTrackName = displayNameOf(uri),
                audioTrimStartMs = 0,
                audioTrimEndMs = 0,
                audioPlacementMs = it.trimStartMs,
                audioWaveform = null,
                syncStatus = SyncStatus.Idle,
                syncConfidence = 0f
            )
        }

        viewModelScope.launch {
            val trackDuration = ThumbnailExtractor.probeDurationMs(getApplication(), uri)
            _state.update {
                it.copy(
                    audioTrackDurationMs = trackDuration,
                    audioTrimEndMs = trackDuration.coerceAtMost(it.trimmedDurationMs.takeIf { d -> d > 0 } ?: trackDuration)
                )
            }

            val pcm = PcmDecoder.decodeMono(getApplication(), uri, maxDurationMs = 10 * 60_000L)
            if (pcm != null) {
                _state.update { it.copy(audioWaveform = WaveformBuilder.build(pcm)) }
            }
            recomputeEstimate()
        }
    }

    /** Which slice of the audio file plays. */
    fun setAudioTrim(startMs: Long, endMs: Long) {
        _state.update {
            val limit = if (it.audioTrackDurationMs > 0) it.audioTrackDurationMs else endMs
            val start = startMs.coerceIn(0L, limit)
            val end = endMs.coerceIn(start, limit)
            it.copy(audioTrimStartMs = start, audioTrimEndMs = end)
        }
    }

    /** Where on the video timeline the slice begins. */
    fun setAudioPlacement(ms: Long) {
        _state.update { it.copy(audioPlacementMs = ms.coerceIn(0L, it.durationMs)) }
    }

    fun placeAudioAtPlayhead() = setAudioPlacement(_state.value.playheadMs)

    /**
     * Slides the track against the picture. Keeps both the in-point and the
     * placement non-negative by spending the move on whichever end has room, so a
     * nudge can never put the cue into invalid territory.
     */
    fun nudgeAudioOffset(deltaMs: Long) {
        _state.update { current ->
            val proposedTrimStart = current.audioTrimStartMs + deltaMs
            if (proposedTrimStart < 0) {
                current.copy(
                    audioTrimStartMs = 0,
                    audioPlacementMs = (current.audioPlacementMs - proposedTrimStart).coerceAtMost(current.durationMs)
                )
            } else {
                current.copy(audioTrimStartMs = proposedTrimStart)
            }
        }
    }

    fun nudgeAudioOffsetFrames(frames: Int) = nudgeAudioOffset(frames * _state.value.frameMs)

    fun resetAudioAlignment() {
        _state.update {
            it.copy(
                audioTrimStartMs = 0,
                audioPlacementMs = it.trimStartMs,
                syncStatus = SyncStatus.Idle
            )
        }
    }

    fun setAudioVolume(volume: Float) = _state.update { it.copy(audioVolume = volume.coerceIn(0f, 1f)) }

    fun setOriginalVolume(volume: Float) = _state.update { it.copy(originalVolume = volume.coerceIn(0f, 1f)) }

    fun runAutoSync() {
        val current = _state.value
        val videoUri = current.sourceUri ?: return
        val audioUri = current.audioTrackUri ?: return

        syncJob?.cancel()
        _state.update { it.copy(syncStatus = SyncStatus.Analyzing) }

        syncJob = viewModelScope.launch {
            val result = AudioSyncAnalyzer.detectOffset(getApplication(), videoUri, audioUri)
            if (result == null || result.confidence < MIN_SYNC_CONFIDENCE) {
                _state.update {
                    it.copy(syncStatus = SyncStatus.NoMatch, syncConfidence = result?.confidence ?: 0f)
                }
            } else {
                _state.update {
                    // A positive offset means the track runs ahead of the picture, so
                    // we enter the file later; a negative one delays the cue instead.
                    val offset = result.offsetMs
                    it.copy(
                        audioTrimStartMs = offset.coerceAtLeast(0L),
                        audioPlacementMs = (-offset).coerceAtLeast(0L),
                        syncStatus = SyncStatus.Matched,
                        syncConfidence = result.confidence
                    )
                }
            }
        }
    }

    // ---- Look & compression ---------------------------------------------------

    fun setQuality(quality: Quality) {
        _state.update { it.copy(quality = quality) }
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

    fun setMuteOriginal(muted: Boolean) {
        _state.update { it.copy(muteOriginal = muted) }
        recomputeEstimate()
    }

    fun toggleRotate() = _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }

    fun setCropAspect(aspect: CropAspect) = _state.update { it.copy(cropAspect = aspect) }

    fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }

    fun setBrightness(value: Float) = _state.update { it.copy(brightness = value) }
    fun setContrast(value: Float) = _state.update { it.copy(contrast = value) }
    fun setSaturation(value: Float) = _state.update { it.copy(saturation = value) }

    fun addTextOverlay(text: String) {
        val current = _state.value
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = text,
            startMs = current.trimStartMs,
            endMs = current.trimEndMs,
            colorArgb = android.graphics.Color.WHITE
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item) }
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
                    timelineStartMs = current.videoClips.sumOf { it.durationMs },
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
        mutateVideoTrack { it.withTransition(clipId, Transition(type, durationMs)) }

    fun changeLayer(clipId: String, delta: Int) = mutateVideoTrack { it.withLayerChanged(clipId, delta) }

    fun setOverlayGeometry(
        clipId: String,
        opacity: Float? = null,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null
    ) = mutateVideoTrack { it.withOverlayGeometry(clipId, opacity, scale, offsetX, offsetY) }

    fun zoomIn() = _state.update { it.copy(pixelsPerSecond = it.toTimeline().zoomedBy(1.35f).pixelsPerSecond) }

    fun zoomOut() = _state.update { it.copy(pixelsPerSecond = it.toTimeline().zoomedBy(1f / 1.35f).pixelsPerSecond) }

    /** Timeline drag. Each lane writes back to whichever model owns it. */
    fun moveClip(clipId: String, deltaMs: Long) {
        val current = _state.value
        when {
            clipId == AUDIO_CLIP_ID -> setAudioPlacement(current.audioPlacementMs + deltaMs)
            current.textOverlays.any { it.id == clipId } -> shiftOverlay(clipId, deltaMs)
            else -> mutateVideoTrack { it.withClipMoved(clipId, deltaMs) }
        }
    }

    /** Timeline edge drag. */
    fun trimClip(clipId: String, startDeltaMs: Long, endDeltaMs: Long) {
        val current = _state.value
        when {
            clipId == AUDIO_CLIP_ID -> setAudioTrim(
                current.audioTrimStartMs + startDeltaMs,
                current.audioTrimEndMs + endDeltaMs
            )
            current.textOverlays.any { it.id == clipId } -> resizeOverlay(clipId, startDeltaMs, endDeltaMs)
            else -> mutateVideoTrack { it.withClipTrimmed(clipId, startDeltaMs, endDeltaMs) }
        }
    }

    fun splitAtPlayhead() = mutateVideoTrack { it.withSplitAtPlayhead() }

    /** Pull the base track back end to end. Deliberate, never automatic. */
    fun closeGaps() = mutateVideoTrack { it.rippleVideo() }

    fun deleteSelectedClip() {
        val selected = _state.value.selectedClipId ?: return
        when {
            selected == AUDIO_CLIP_ID -> setAudioTrack(null)
            _state.value.textOverlays.any { it.id == selected } -> removeTextOverlay(selected)
            else -> mutateVideoTrack { it.withClipRemoved(selected) }
        }
        _state.update { it.copy(selectedClipId = null) }
    }

    private fun mutateVideoTrack(block: (TimelineState) -> TimelineState) {
        _state.update { current ->
            val track = TimelineState(
                clips = current.videoClips,
                selectedClipId = current.selectedClipId,
                playheadMs = current.playheadMs
            )
            val next = block(track)
            current.copy(videoClips = next.videoClips, selectedClipId = next.selectedClipId)
        }
        recomputeEstimate()
    }

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
        val current = _state.value
        val bitrate = if (current.fitToSize) {
            ExportPresets.bitrateForTargetSize(
                current.targetSizeMb * 1_000_000L,
                current.trimmedDurationMs,
                current.hasAnyAudio
            )
        } else {
            ExportPresets.bitrateFor(current.quality)
        }
        val durationSeconds = current.trimmedDurationMs / 1000.0
        val audioBits = if (current.hasAnyAudio) ExportPresets.AUDIO_BITRATE_BPS * durationSeconds else 0.0
        val videoBits = bitrate * durationSeconds
        _state.update { it.copy(estimatedOutputBytes = ((videoBits + audioBits) / 8).toLong()) }
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

        _state.update { it.copy(isExporting = true, failure = null) }

        viewModelScope.launch {
            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.mp4")

            val result = processor.export(current, outputFile)
            _state.update { it.copy(isExporting = false) }

            result.onSuccess { file ->
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
                autosave.markCompleted()
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

        val sameClip = snapshot.sourceUri == openedUri

        // A snapshot whose media is gone is worth nothing to restore, so only a
        // readable source ever produces an offer for a different clip.
        val readable = sameClip || canRead(snapshot.sourceUri)
        if (!sameClip && !readable) return

        // A session already finished by an export was cleared; anything still here
        // ended some other way, which is exactly the case worth recovering.
        _state.update { it.copy(recovery = RecoveryOffer(snapshot, readable)) }
    }

    fun dismissRecovery() {
        autosave.clear()
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
            _state.update { it.applying(snapshot, meta.durationMs, meta.width, meta.height, meta.fps) }
            recomputeEstimate()
            startProxy(snapshot.sourceUri, meta.width, meta.height)

            val pcm = PcmDecoder.decodeMono(getApplication(), snapshot.sourceUri)
            _state.update {
                it.copy(
                    sourceHasAudio = pcm != null,
                    videoWaveform = pcm?.let { decoded -> WaveformBuilder.build(decoded) }
                )
            }
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
        durationMs = durationMs,
        sourceWidth = width,
        sourceHeight = height,
        fps = fps,
        trimStartMs = 0L,
        trimEndMs = durationMs,
        videoClips = snapshot.clips,
        textOverlays = snapshot.textOverlays,
        markers = snapshot.markers,
        playheadMs = snapshot.playheadMs,
        quality = snapshot.quality,
        fitToSize = snapshot.fitToSize,
        targetSizeMb = snapshot.targetSizeMb,
        audioOnly = snapshot.audioOnly,
        muteOriginal = snapshot.muteOriginal,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        speed = snapshot.speed,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation,
        pixelsPerSecond = snapshot.pixelsPerSecond,
        audioTrackUri = snapshot.audioTrackUri,
        audioTrackName = snapshot.audioTrackName,
        audioTrackDurationMs = snapshot.audioTrackDurationMs,
        audioTrimStartMs = snapshot.audioTrimStartMs,
        audioTrimEndMs = snapshot.audioTrimEndMs,
        audioPlacementMs = snapshot.audioPlacementMs,
        audioVolume = snapshot.audioVolume,
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
    private fun startProxy(uri: Uri, width: Int, height: Int) {
        proxyJob?.cancel()

        if (!ProxyEngine.isWorthProxying(width, height)) {
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

    private companion object {
        const val MIN_SYNC_CONFIDENCE = 0.28f
        const val AUTOSAVE_INTERVAL_MS = 1_500L
    }
}
