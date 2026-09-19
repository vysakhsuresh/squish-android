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

            _state.update {
                it.copy(
                    durationMs = meta.durationMs,
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    fps = meta.fps,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    isLoadingSource = false,
                    originalSizeBytes = originalSize
                )
            }
            recomputeEstimate()
            loadVideoWaveform(uri)
        }
    }

    private fun loadVideoWaveform(uri: Uri) {
        viewModelScope.launch {
            val pcm = PcmDecoder.decodeMono(getApplication(), uri) ?: return@launch
            _state.update { it.copy(videoWaveform = WaveformBuilder.build(pcm)) }
        }
    }

    // ---- Precision trim -------------------------------------------------------

    fun setTrim(startMs: Long, endMs: Long) {
        val current = _state.value
        val snapped = if (current.snapToMarkers) {
            snapToNearbyMarker(startMs, current) to snapToNearbyMarker(endMs, current)
        } else {
            startMs to endMs
        }
        _state.update {
            it.copy(
                trimStartMs = snapped.first.coerceIn(0L, it.durationMs),
                trimEndMs = snapped.second.coerceIn(0L, it.durationMs)
            )
        }
        recomputeEstimate()
    }

    /** Frame-exact nudge of an in/out point - the control that trim sliders cannot give you. */
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

    // ---- Separate audio track + sync ------------------------------------------

    fun setAudioTrack(uri: Uri?) {
        syncJob?.cancel()
        if (uri == null) {
            _state.update {
                it.copy(
                    audioTrackUri = null,
                    audioTrackName = null,
                    audioTrackDurationMs = 0,
                    audioOffsetMs = 0,
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
                audioOffsetMs = 0,
                audioWaveform = null,
                syncStatus = SyncStatus.Idle,
                syncConfidence = 0f
            )
        }

        viewModelScope.launch {
            // Real container duration, not the decoded window - the decoder caps how
            // much it reads, and a capped value here would silently truncate the
            // export's audio clip on long tracks.
            val trackDuration = ThumbnailExtractor.probeDurationMs(getApplication(), uri)
            _state.update { it.copy(audioTrackDurationMs = trackDuration) }

            val pcm = PcmDecoder.decodeMono(getApplication(), uri, maxDurationMs = 10 * 60_000L)
            if (pcm != null) {
                _state.update { it.copy(audioWaveform = WaveformBuilder.build(pcm)) }
            }
            recomputeEstimate()
            runAutoSync()
        }
    }

    /**
     * Cross-correlates the camera's scratch audio against the external track and
     * jumps straight to the aligned offset.
     */
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
                    it.copy(
                        audioOffsetMs = result.offsetMs,
                        syncStatus = SyncStatus.Matched,
                        syncConfidence = result.confidence
                    )
                }
            }
        }
    }

    fun nudgeAudioOffset(deltaMs: Long) {
        _state.update { it.copy(audioOffsetMs = it.audioOffsetMs + deltaMs) }
    }

    fun nudgeAudioOffsetFrames(frames: Int) {
        _state.update { it.copy(audioOffsetMs = it.audioOffsetMs + frames * it.frameMs) }
    }

    fun setAudioOffset(ms: Long) = _state.update { it.copy(audioOffsetMs = ms) }

    fun resetAudioOffset() = _state.update { it.copy(audioOffsetMs = 0, syncStatus = SyncStatus.Idle) }

    fun setAudioVolume(volume: Float) = _state.update { it.copy(audioVolume = volume.coerceIn(0f, 1f)) }

    fun setOriginalVolume(volume: Float) = _state.update { it.copy(originalVolume = volume.coerceIn(0f, 1f)) }

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

    fun addClipToQueue(uri: Uri) = _state.update { it.copy(clipQueue = it.clipQueue + uri) }
    fun removeClipFromQueue(uri: Uri) =
        _state.update { it.copy(clipQueue = it.clipQueue.filterNot { queued -> queued == uri }) }

    private fun recomputeEstimate() {
        val current = _state.value
        val bitrate = if (current.fitToSize) {
            ExportPresets.bitrateForTargetSize(
                current.targetSizeMb * 1_000_000L,
                current.trimmedDurationMs,
                !current.muteOriginal || current.hasSeparateAudio
            )
        } else {
            ExportPresets.bitrateFor(current.quality)
        }
        val durationSeconds = current.trimmedDurationMs / 1000.0
        val hasAudio = !current.muteOriginal || current.hasSeparateAudio
        val audioBits = if (hasAudio) ExportPresets.AUDIO_BITRATE_BPS * durationSeconds else 0.0
        val videoBits = bitrate * durationSeconds
        _state.update { it.copy(estimatedOutputBytes = ((videoBits + audioBits) / 8).toLong()) }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    // ---- Export ---------------------------------------------------------------

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
