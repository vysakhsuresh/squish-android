package com.squish.app.tools

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.Quality
import com.squish.app.media.ExportPresets
import com.squish.app.media.GallerySaver
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoProcessor
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Drives the one-job tools. They share the editor's export pipeline - a quick
 * compress and a compress inside the editor produce byte-identical output - but
 * expose only the handful of settings that job needs.
 */
class QuickToolViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val sourceUri: Uri? = null,
        val name: String? = null,
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val originalSizeBytes: Long = 0,
        val quality: Quality = Quality.Medium,
        val fitToSize: Boolean = false,
        val targetSizeMb: Int = 16,
        val trimStartMs: Long = 0,
        val trimEndMs: Long = 0,
        val extraClips: List<Clip> = emptyList(),
        val isLoading: Boolean = false,
        val isExporting: Boolean = false,
        val estimatedOutputBytes: Long = 0
    ) {
        val hasSource: Boolean get() = sourceUri != null
        val selectedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val processor = VideoProcessor(application)
    private val historyRepository = SquishRepositories.history(application)

    fun load(uri: Uri) {
        if (_state.value.sourceUri == uri) return
        _state.update { it.copy(sourceUri = uri, isLoading = true) }

        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            val size = runCatching {
                getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)

            _state.update {
                it.copy(
                    name = displayNameOf(uri),
                    durationMs = meta.durationMs,
                    width = meta.width,
                    height = meta.height,
                    originalSizeBytes = size,
                    trimStartMs = 0,
                    trimEndMs = meta.durationMs,
                    isLoading = false
                )
            }
            recomputeEstimate()
        }
    }

    /** Probes each added clip so the merged timeline knows its real length. */
    fun addClip(uri: Uri) {
        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            _state.update { current ->
                // Placed after everything already queued, the first clip included.
                // Every appended clip used to land at zero, so a merge asked the
                // compositor to stack them on top of one another instead of playing
                // them one after another.
                val start = maxOf(
                    current.durationMs,
                    current.extraClips.maxOfOrNull { it.timelineEndMs } ?: 0L
                )
                current.copy(
                    extraClips = current.extraClips + Clip(
                        kind = ClipKind.Video,
                        uri = uri,
                        label = displayNameOf(uri) ?: "Clip ${current.extraClips.size + 2}",
                        sourceInMs = 0,
                        sourceOutMs = meta.durationMs,
                        timelineStartMs = start,
                        sourceDurationMs = meta.durationMs
                    )
                )
            }
        }
    }

    fun removeClip(clipId: String) =
        _state.update { it.copy(extraClips = it.extraClips.filterNot { c -> c.id == clipId }) }

    fun setQuality(quality: Quality) {
        _state.update { it.copy(quality = quality, fitToSize = false) }
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

    fun setTrim(startMs: Long, endMs: Long) {
        _state.update {
            it.copy(
                trimStartMs = startMs.coerceIn(0L, it.durationMs),
                trimEndMs = endMs.coerceIn(0L, it.durationMs)
            )
        }
        recomputeEstimate()
    }

    private fun recomputeEstimate() {
        val current = _state.value
        val bitrate = if (current.fitToSize) {
            ExportPresets.bitrateForTargetSize(
                current.targetSizeMb * 1_000_000L,
                current.selectedDurationMs,
                true
            )
        } else {
            ExportPresets.bitrateFor(current.quality)
        }
        val seconds = current.selectedDurationMs / 1000.0
        val bits = bitrate * seconds + ExportPresets.AUDIO_BITRATE_BPS * seconds
        _state.update { it.copy(estimatedOutputBytes = (bits / 8).toLong()) }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    fun export(tool: QuickTool, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val current = _state.value
        val sourceUri = current.sourceUri ?: return
        _state.update { it.copy(isExporting = true) }

        val audioOnly = tool == QuickTool.ExtractAudio
        val editorState = EditorUiState(
            sourceUri = sourceUri,
            isLoadingSource = false,
            durationMs = current.durationMs,
            sourceWidth = current.width,
            sourceHeight = current.height,
            originalSizeBytes = current.originalSizeBytes,
            trimStartMs = if (tool == QuickTool.Trim) current.trimStartMs else 0,
            trimEndMs = if (tool == QuickTool.Trim) current.trimEndMs else current.durationMs,
            quality = current.quality,
            fitToSize = tool == QuickTool.Compress && current.fitToSize,
            targetSizeMb = current.targetSizeMb,
            audioOnly = audioOnly,
            videoClips = if (tool != QuickTool.Merge) emptyList() else buildList {
                add(
                    Clip(
                        kind = ClipKind.Video,
                        uri = sourceUri,
                        label = current.name ?: "Clip 1",
                        sourceInMs = 0,
                        sourceOutMs = current.durationMs,
                        timelineStartMs = 0,
                        sourceDurationMs = current.durationMs
                    )
                )
                addAll(current.extraClips)
            }
        )

        viewModelScope.launch {
            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val extension = if (audioOnly) "m4a" else "mp4"
            val outputFile = File(outputDir, "squish_${tool.id}_${System.currentTimeMillis()}.$extension")

            val result = processor.export(editorState, outputFile)
            _state.update { it.copy(isExporting = false) }

            result.onSuccess { file ->
                if (audioOnly) {
                    GallerySaver.publishAudio(getApplication(), file)
                } else {
                    GallerySaver.publish(getApplication(), file)
                }
                historyRepository.add(
                    ExportRecord(
                        id = UUID.randomUUID().toString(),
                        title = current.name ?: tool.title,
                        outputPath = file.absolutePath,
                        originalSizeBytes = current.originalSizeBytes,
                        outputSizeBytes = file.length(),
                        durationMs = editorState.trimmedDurationMs,
                        width = current.width,
                        height = current.height,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                // Same typed vocabulary as the editor, so a failure reads the same
                // way whichever door the user came in through.
                val problem = SquishError.from(throwable)
                onError("${problem.title}. ${problem.fix}")
            }
        }
    }
}
