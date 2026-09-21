package com.squish.app.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.media.ExportPresets
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
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withClipMoved
import com.squish.app.timeline.withClipRemoved
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.zoomedBy
import kotlinx.coroutines.Job
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

    private var loadedUri: Uri? = null
    private var syncJob: Job? = null

    fun load(uri: Uri) {
        if (loadedUri == uri) return
        loadedUri = uri
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

    fun setTrim(startMs: Long, endMs: Long) {
        val current = _state.value
        val start = if (current.snapToMarkers) snapToNearbyMarker(startMs, current) else startMs
        val end = if (current.snapToMarkers) snapToNearbyMarker(endMs, current) else endMs
        _state.update {
            it.copy(
                trimStartMs = start.coerceIn(0L, it.durationMs),
                trimEndMs = end.coerceIn(0L, it.durationMs)
            )
        }
        recomputeEstimate()
    }

    fun nudgeTrim(isStart: Boolean, frames: Int) {
        val current = _state.value
        val delta = frames * current.frameMs
        val minGap = current.frameMs * 2
        if (isStart) {
            val next = (current.trimStartMs + delta).coerceIn(0L, current.trimEndMs - minGap)
            _state.update { it.copy(trimStartMs = Timecode.quantize(next, it.fps)) }
        } else {
            val next = (current.trimEndMs + delta).coerceIn(current.trimStartMs + minGap, current.durationMs)
            _state.update { it.copy(trimEndMs = Timecode.quantize(next, it.fps)) }
        }
        recomputeEstimate()
    }

    fun setTrimPointToPlayhead(isStart: Boolean) {
        val current = _state.value
        val position = Timecode.quantize(current.playheadMs, current.fps)
        val minGap = current.frameMs * 2
        if (isStart) {
            _state.update { it.copy(trimStartMs = position.coerceIn(0L, it.trimEndMs - minGap)) }
        } else {
            _state.update { it.copy(trimEndMs = position.coerceIn(it.trimStartMs + minGap, it.durationMs)) }
        }
        recomputeEstimate()
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
                        it.copy(start, endMs = (it.endMs + endDeltaMs).coerceAtLeast(start + 200L))
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

    fun export(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val current = _state.value
        val sourceUri = current.sourceUri ?: return
        _state.update { it.copy(isExporting = true) }

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
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                onError(throwable.message ?: "Export failed")
            }
        }
    }

    private companion object {
        const val MIN_SYNC_CONFIDENCE = 0.28f
    }
}
