package com.squish.app.tools

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolDraft
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.Quality
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.GallerySaver
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoMeta
import com.squish.app.media.VideoProcessor
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val hasAudio: Boolean = true,
        val quality: Quality = Quality.Medium,
        val fitToSize: Boolean = false,
        val targetSizeMb: Int = 16,
        val trimStartMs: Long = 0,
        val trimEndMs: Long = 0,
        /**
         * Every clip in a merge, in the order they will play. One list rather than
         * "the source, plus some extras": with the first clip held apart, it could
         * never be moved out of first place, which is exactly the thing anyone
         * merging videos wants to do.
         */
        val mergeClips: List<Clip> = emptyList(),
        val isLoading: Boolean = false,
        val isExporting: Boolean = false,
        val exportProgress: ExportProgress = ExportProgress(),
        val estimatedOutputBytes: Long = 0
    ) {
        val hasSource: Boolean get() = sourceUri != null
        val mergeDurationMs: Long get() = mergeClips.sumOf { it.durationMs }
        val selectedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)

        /**
         * How long the finished file runs.
         *
         * A merge is the whole list; everything else is the selected range. The
         * estimate used the selected range for both, and since a merge's range is
         * only ever the *first* clip, joining four videos was estimated at the
         * weight of the shortest one - which also made the free-space check
         * nonsense, because it was checking for a fiftieth of what would be written.
         */
        val exportDurationMs: Long
            get() = if (mergeClips.isNotEmpty()) mergeDurationMs else selectedDurationMs

        /**
         * The shape the preview should be.
         *
         * [width] and [height] are already the displayed size - the rotation tag is
         * applied when the file is probed, in one place, rather than by everything
         * that wants to know how wide the picture is.
         */
        val previewAspect: Float
            get() {
                if (width <= 0 || height <= 0) return 16f / 9f
                return (width.toFloat() / height).coerceIn(0.4f, 2.5f)
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val processor = VideoProcessor(application)
    private val historyRepository = SquishRepositories.history(application)

    /**
     * What was measured about each clip added to a merge.
     *
     * Kept here rather than on [UiState] because it is not something the screen
     * draws - it exists so that reordering or removing a clip can recompute the
     * output's shape and weight without probing every file again.
     */
    private val mergeMeta = HashMap<String, VideoMeta>()
    private val mergeSizes = HashMap<String, Long>()

    private val autosave = SquishRepositories.toolAutosave(application)

    /** Which tool this screen is, so a save knows what it is a draft of. */
    private var tool: QuickTool? = null

    /**
     * Starts saving this tool's session, and hands back the one it left behind.
     *
     * Called once, as the screen opens. The ticker is the editor's: a fixed
     * interval, a no-op when nothing changed, and never on the frame loop. What is
     * saved is only the choices - which files, in which order, where the handles
     * are - so the cost of a tick is a short string comparison.
     */
    suspend fun begin(tool: QuickTool): ToolDraft? {
        if (this.tool != null) return null
        this.tool = tool

        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL)
                val current = _state.value
                if (current.isExporting || current.isLoading) continue
                withContext(Dispatchers.IO) { autosave.save(draftOf(tool, current)) }
            }
        }

        return withContext(Dispatchers.IO) { autosave.peek(tool.id) }
    }

    private fun draftOf(tool: QuickTool, state: UiState) = ToolDraft(
        toolId = tool.id,
        title = if (tool == QuickTool.Merge) {
            "${state.mergeClips.size} clips to merge"
        } else {
            state.name ?: tool.title
        },
        uris = if (tool == QuickTool.Merge) {
            state.mergeClips.mapNotNull { it.uri }
        } else {
            listOfNotNull(state.sourceUri)
        },
        durationMs = state.exportDurationMs,
        trimStartMs = state.trimStartMs,
        trimEndMs = state.trimEndMs,
        quality = state.quality.name,
        fitToSize = state.fitToSize,
        targetSizeMb = state.targetSizeMb,
        savedAtMillis = System.currentTimeMillis()
    )

    /**
     * Puts a saved session back.
     *
     * The files are re-probed rather than restored from the draft: their lengths
     * and shapes are facts about the media, and a file that changed - or went
     * away - underneath a draft must not be described by what was true yesterday.
     * The trim is applied after the probe for the same reason, clamped to the
     * length the file actually turned out to have.
     */
    fun restore(draft: ToolDraft) {
        val tool = QuickTool.fromId(draft.toolId)
        _state.update {
            it.copy(
                quality = runCatching { Quality.valueOf(draft.quality) }.getOrDefault(it.quality),
                fitToSize = draft.fitToSize,
                targetSizeMb = draft.targetSizeMb
            )
        }

        if (tool == QuickTool.Merge) {
            addMergeClips(draft.uris)
            return
        }

        val uri = draft.uris.firstOrNull() ?: return
        viewModelScope.launch {
            load(uri)
            // load() marks itself loading before it returns and probes on its own
            // coroutine, resetting the handles to the whole clip when it lands. So
            // the saved range can only go on afterwards - and it waits on the state
            // flow itself rather than on a fixed delay, because how long a probe
            // takes is a property of the file, not something to guess at.
            _state.first { !it.isLoading && it.sourceUri == uri }
            if (draft.trimEndMs > draft.trimStartMs) {
                setTrim(draft.trimStartMs, draft.trimEndMs)
            }
        }
    }

    /** The session is finished - it produced a file, so there is nothing to resume. */
    private fun clearDraft() {
        tool?.let { autosave.clear(it.id) }
    }

    fun load(uri: Uri) {
        if (_state.value.sourceUri == uri) return
        _state.update { it.copy(sourceUri = uri, isLoading = true) }

        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            val size = fileSizeOf(uri)

            _state.update {
                it.copy(
                    name = displayNameOf(uri),
                    durationMs = meta.durationMs,
                    width = meta.displayWidth,
                    height = meta.displayHeight,
                    hasAudio = meta.hasAudio,
                    originalSizeBytes = size,
                    trimStartMs = 0,
                    trimEndMs = meta.durationMs,
                    isLoading = false
                )
            }
            recomputeEstimate()
        }
    }

    /**
     * Adds clips to a merge, keeping the list in playing order.
     *
     * Takes a list because the picker hands back a list: choosing six videos should
     * be one trip through the gallery, not six.
     */
    fun addMergeClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = uris.map { uri ->
                val meta = ThumbnailExtractor.probe(getApplication(), uri)
                val clip = Clip(
                    kind = ClipKind.Video,
                    uri = uri,
                    label = displayNameOf(uri) ?: "Clip",
                    sourceInMs = 0,
                    sourceOutMs = meta.durationMs,
                    timelineStartMs = 0,
                    sourceDurationMs = meta.durationMs
                )
                // The frame size was being probed and thrown away, which is what
                // made every merge export with no output resolution set at all.
                mergeMeta[clip.id] = meta
                mergeSizes[clip.id] = fileSizeOf(uri)
                clip
            }
            _state.update { current -> current.withMerge(current.mergeClips + added) }
            recomputeEstimate()
        }
    }

    fun moveMergeClip(clipId: String, delta: Int) {
        _state.update { current ->
            val clips = current.mergeClips.toMutableList()
            val from = clips.indexOfFirst { it.id == clipId }
            val to = from + delta
            if (from < 0 || to !in clips.indices) return@update current
            clips.add(to, clips.removeAt(from))
            current.withMerge(clips)
        }
        recomputeEstimate()
    }

    fun removeMergeClip(clipId: String) {
        mergeMeta.remove(clipId)
        mergeSizes.remove(clipId)
        _state.update { current -> current.withMerge(current.mergeClips.filterNot { it.id == clipId }) }
        recomputeEstimate()
    }

    fun clearMerge() {
        mergeMeta.clear()
        mergeSizes.clear()
        _state.update { it.withMerge(emptyList()) }
        recomputeEstimate()
    }

    /**
     * Re-lays the list end to end and republishes the first clip as the screen's
     * "source", so the header, the duration and the size all describe whatever now
     * plays first. Start times are derived here and nowhere else, which is what
     * keeps the numbers on screen and the numbers in the export the same.
     */
    private fun UiState.withMerge(clips: List<Clip>): UiState {
        var cursor = 0L
        val sequenced = clips.map { clip ->
            val placed = clip.copy(timelineStartMs = cursor)
            cursor += clip.durationMs
            placed
        }
        val first = sequenced.firstOrNull()
        // The output takes the shape of whatever plays first, and everything else
        // is fitted into it. Without these the exporter had no idea what size to
        // write, so four clips of four different shapes went into one sequence
        // with nothing reconciling them.
        val lead = first?.let { mergeMeta[it.id] }
        return copy(
            mergeClips = sequenced,
            sourceUri = first?.uri,
            name = first?.label,
            durationMs = first?.durationMs ?: 0L,
            width = lead?.displayWidth ?: 0,
            height = lead?.displayHeight ?: 0,
            // Every file that goes in, so the finished screen can say what the
            // merge actually saved instead of comparing against zero.
            originalSizeBytes = sequenced.sumOf { mergeSizes[it.id] ?: 0L },
            trimStartMs = 0L,
            trimEndMs = first?.durationMs ?: 0L
        )
    }

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
                current.exportDurationMs,
                true
            )
        } else {
            ExportPresets.bitrateFor(current.quality)
        }
        val seconds = current.exportDurationMs / 1000.0
        val bits = bitrate * seconds + ExportPresets.AUDIO_BITRATE_BPS * seconds
        _state.update { it.copy(estimatedOutputBytes = (bits / 8).toLong()) }
    }

    private fun fileSizeOf(uri: Uri): Long = runCatching {
        getApplication<Application>().contentResolver
            .openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    fun export(tool: QuickTool, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val current = _state.value
        val sourceUri = current.sourceUri ?: return

        val audioOnly = tool == QuickTool.ExtractAudio
        val editorState = EditorUiState(
            sourceUri = sourceUri,
            isLoadingSource = false,
            durationMs = current.durationMs,
            sourceWidth = current.width,
            sourceHeight = current.height,
            originalSizeBytes = current.originalSizeBytes,
            trimStartMs = if (tool.usesRange) current.trimStartMs else 0,
            trimEndMs = if (tool.usesRange) current.trimEndMs else current.durationMs,
            quality = current.quality,
            fitToSize = tool == QuickTool.Compress && current.fitToSize,
            targetSizeMb = current.targetSizeMb,
            audioOnly = audioOnly,
            // Checked rather than assumed, so extracting audio from a silent clip
            // fails immediately with a sentence about it instead of after a full
            // encode with a code nobody can read.
            sourceHasAudio = current.hasAudio,
            // Already in order and already sequenced, so the export is simply the
            // list as shown - there is no second place for the order to be decided.
            videoClips = if (tool != QuickTool.Merge) emptyList()
            else current.mergeClips.filter { it.sourceSpanMs > 0 }
        )

        if (tool == QuickTool.Merge && editorState.videoClips.isEmpty()) {
            onError("Nothing to merge. Every clip came back with no length — pick them again.")
            return
        }

        // The same checks the editor runs. They were never run here, so a merge
        // whose third file had been deleted since it was picked spent two minutes
        // encoding before finding out.
        SquishError.preflight(getApplication(), editorState, current.estimatedOutputBytes)?.let { problem ->
            onError("${problem.title}. ${problem.fix}")
            return
        }

        _state.update { it.copy(isExporting = true, exportProgress = ExportProgress()) }

        viewModelScope.launch {
            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val extension = if (audioOnly) "m4a" else "mp4"
            val outputFile = File(outputDir, "squish_${tool.id}_${System.currentTimeMillis()}.$extension")

            val result = processor.export(editorState, outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
            }
            _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }

            result.onSuccess { file ->
                if (audioOnly) {
                    GallerySaver.publishAudio(getApplication(), file)
                } else {
                    GallerySaver.publish(getApplication(), file)
                }
                historyRepository.add(
                    ExportRecord(
                        id = UUID.randomUUID().toString(),
                        title = if (tool == QuickTool.Merge) {
                            "${current.mergeClips.size} clips merged"
                        } else {
                            current.name ?: tool.title
                        },
                        outputPath = file.absolutePath,
                        originalSizeBytes = current.originalSizeBytes,
                        outputSizeBytes = file.length(),
                        durationMs = editorState.trimmedDurationMs,
                        width = current.width,
                        height = current.height,
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
                clearDraft()
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                // Same typed vocabulary as the editor, so a failure reads the same
                // way whichever door the user came in through.
                val problem = SquishError.from(throwable)
                onError("${problem.title}. ${problem.fix}")
            }
        }
    }

    private companion object {
        /** The editor's interval. A tool session changes far less often than a timeline. */
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
    }
}
