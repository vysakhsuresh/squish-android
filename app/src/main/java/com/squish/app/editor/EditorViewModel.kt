package com.squish.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.HistoryRepository
import com.squish.app.media.ExportPresets
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val processor = VideoProcessor(application)
    private val historyRepository = HistoryRepository(application)
    private var loadedUri: Uri? = null

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
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    isLoadingSource = false,
                    originalSizeBytes = originalSize
                )
            }
            recomputeEstimate()
        }
    }

    fun setTrim(startMs: Long, endMs: Long) {
        _state.update { it.copy(trimStartMs = startMs, trimEndMs = endMs) }
        recomputeEstimate()
    }

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

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        recomputeEstimate()
    }

    fun toggleRotate() = _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }

    fun setCropAspect(aspect: CropAspect) = _state.update { it.copy(cropAspect = aspect) }

    fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }

    fun setBrightness(v: Float) = _state.update { it.copy(brightness = v) }
    fun setContrast(v: Float) = _state.update { it.copy(contrast = v) }
    fun setSaturation(v: Float) = _state.update { it.copy(saturation = v) }

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

    fun setMusic(uri: Uri?) = _state.update { it.copy(musicUri = uri) }

    fun addClipToQueue(uri: Uri) = _state.update { it.copy(clipQueue = it.clipQueue + uri) }
    fun removeClipFromQueue(uri: Uri) = _state.update { it.copy(clipQueue = it.clipQueue.filterNot { u -> u == uri }) }

    private fun recomputeEstimate() {
        val s = _state.value
        val bitrate = if (s.fitToSize) {
            ExportPresets.bitrateForTargetSize(s.targetSizeMb * 1_000_000L, s.trimmedDurationMs, !s.muted)
        } else {
            ExportPresets.bitrateFor(s.quality)
        }
        val durationSec = s.trimmedDurationMs / 1000.0
        val audioBits = if (s.muted) 0.0 else ExportPresets.AUDIO_BITRATE_BPS * durationSec
        val videoBits = bitrate * durationSec
        _state.update { it.copy(estimatedOutputBytes = ((videoBits + audioBits) / 8).toLong()) }
    }

    fun export(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val s = _state.value
        val uri = s.sourceUri ?: return
        _state.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports").apply { mkdirs() }
            val outputFile = File(outputDir, "squish_${System.currentTimeMillis()}.mp4")
            val result = processor.export(s, s.sourceWidth, s.sourceHeight, outputFile)
            _state.update { it.copy(isExporting = false) }
            result.onSuccess { file ->
                historyRepository.add(
                    ExportRecord(
                        id = UUID.randomUUID().toString(),
                        title = uri.lastPathSegment ?: "Squished video",
                        outputPath = file.absolutePath,
                        originalSizeBytes = s.originalSizeBytes,
                        outputSizeBytes = file.length(),
                        durationMs = s.trimmedDurationMs,
                        width = s.sourceWidth,
                        height = s.sourceHeight,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                onError(throwable.message ?: "Export failed")
            }
        }
    }
}
