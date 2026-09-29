package com.squish.app.tools

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.home.countOf
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolAutosave
import com.squish.app.data.ToolDraft
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.OutputSize
import com.squish.app.editor.ProbeGate
import com.squish.app.media.EncoderCeiling
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.ExportService
import com.squish.app.media.ExportStage
import com.squish.app.media.ExportsInFlight
import com.squish.app.media.MediaCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        /** The export's short edge. 720p: small enough to send, sharp enough to watch. */
        val outputP: Int = 720,
        val fps: Float = 30f,
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
        val estimatedOutputBytes: Long = 0,
        /** The frame the size chosen gives, and what the phone's encoder will write for it - see EncoderCeiling. */
        val outputFrame: ExportPresets.Resolution = ExportPresets.Resolution(0, 0),
        val writtenFrame: ExportPresets.Resolution = ExportPresets.Resolution(0, 0),
        val encoderAnswer: ExportPresets.EncoderAnswer? = null
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

    init {
        // Told from the state, as the editor is, so nothing can be left counted
        // as exporting; the nav host holds an "Open with" while any is.
        viewModelScope.launch {
            _state.map { it.isExporting }.distinctUntilChanged().collect { ExportsInFlight.set(this@QuickToolViewModel, it) }
        }
    }

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
     * Which file this session saves into. Handed in with the route: a fresh one
     * from the dashboard, the draft's own from the drafts list. One slot per
     * tool used to mean a second Stitch quietly wrote over the first.
     */
    private var slot: String? = null

    /**
     * The session as it stood the moment its video finished loading.
     *
     * Choosing a video is not work. Saving from that moment on is what put a
     * "pick up where you left off" row on the dashboard for every video that was
     * merely opened and backed out of. A session is only a draft once it differs
     * from this - a handle moved, a size picked, a clip reordered.
     */
    @Volatile
    private var baseline: String? = null

    /** A draft that was reopened is already work, so it keeps saving as it is. */
    @Volatile
    private var resumed = false

    /** Whether this session has put a draft on disk, so undoing back to nothing can take it off. */
    @Volatile
    private var wroteDraft = false

    /**
     * True from the moment a saved session starts coming back until all of it is
     * on screen; nothing is saved in between. Stays true if the file turned out
     * unreadable, so the empty result of that never replaces the saved session -
     * until another video is chosen, which is a new session in the same slot.
     */
    @Volatile
    private var restoring = false

    /**
     * Starts saving this tool's session and hands back the one already in its
     * slot, if there is one.
     *
     * The drafts list opens a session's own slot and so always finds it. A
     * dashboard tap opens a slot nobody has used, which is empty - unless the
     * app was killed under this very session and the screen has come back with
     * its route. Then the slot holds what was built before the kill, and it is
     * picked up rather than an empty screen offered over it: the first thing
     * saved from that empty screen went into the same slot and wrote over the
     * six-clip merge that was there.
     *
     * Called once, as the screen opens. The ticker is the editor's: a fixed
     * interval, a no-op when nothing changed, and never on the frame loop. What is
     * saved is only the choices - which files, in which order, where the handles
     * are - so the cost of a tick is a short string comparison.
     */
    suspend fun begin(tool: QuickTool, slot: String): ToolDraft? {
        if (this.tool != null) return null
        this.tool = tool
        this.slot = slot

        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL)
                withContext(Dispatchers.IO) { saveNow() }
            }
        }

        return withContext(Dispatchers.IO) { autosave.peek(slot) }?.also { resumed = true }
    }

    /**
     * Writes the session to disk now, on the calling thread, if it has changed.
     *
     * The ticker's job, and also called on the way out of the screen: an edit
     * made in the last second and a half before back was pressed used to be the
     * one edit that never reached disk.
     *
     * Also while an export runs, which now lasts through the gallery copy: a
     * change made under a long render, with the app sent behind something during
     * the copy, was on disk nowhere if the process went. ToolAutosave serialises
     * the writes and keeps the export stamp, so this and the export's own save
     * cannot undo each other.
     */
    fun saveNow() {
        persist()
    }

    /** The save itself, behind [saveNow] and the export's own flushes. */
    private fun persist() {
        val tool = tool ?: return
        val current = _state.value
        // A reopened session is only itself once all of it is back. Saved in
        // between - after the probe but before the trim went on - it wrote the
        // whole clip over the cut, and a back press in that gap cleared the view
        // model before the trim could land at all.
        if (current.isLoading || restoring) return
        val draft = draftOf(tool, current)
        val untouched = !resumed && (baseline == null || autosave.keyOf(draft) == baseline)
        if (untouched) {
            if (wroteDraft) {
                autosave.delete(draft.slot)
                wroteDraft = false
            }
        } else if (autosave.save(draft)) {
            wroteDraft = true
        }
    }

    /** Marks the current state as the untouched starting point. */
    private fun markBaseline() {
        val tool = tool ?: return
        if (resumed) return
        baseline = autosave.keyOf(draftOf(tool, _state.value))
    }

    private fun draftOf(tool: QuickTool, state: UiState) = ToolDraft(
        slot = slot ?: ToolAutosave.freshSlot(tool.id),
        toolId = tool.id,
        title = if (tool == QuickTool.Stitch) {
            "${countOf(state.mergeClips.size, "clip")} to merge"
        } else {
            state.name ?: tool.title
        },
        uris = if (tool == QuickTool.Stitch) {
            state.mergeClips.mapNotNull { it.uri }
        } else {
            listOfNotNull(state.sourceUri)
        },
        durationMs = state.exportDurationMs,
        trimStartMs = state.trimStartMs,
        trimEndMs = state.trimEndMs,
        outputP = state.outputP,
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
        restoring = true
        _state.update {
            it.copy(
                outputP = draft.outputP,
                fitToSize = draft.fitToSize,
                targetSizeMb = draft.targetSizeMb
            )
        }

        if (tool == QuickTool.Stitch) {
            viewModelScope.launch {
                // Every file stays in the list even if it no longer reads, so
                // what is saved after this is never less than what was.
                appendMergeClips(draft.uris)
                restoring = false
            }
            return
        }

        val uri = draft.uris.firstOrNull() ?: run {
            restoring = false
            return
        }
        viewModelScope.launch {
            loadSource(uri)
            // loadSource() marks itself loading before it returns and probes on its
            // own coroutine, resetting the handles to the whole clip when it lands.
            // So the saved range can only go on afterwards - and it waits on the
            // state flow itself rather than on a fixed delay, because how long a
            // probe takes is a property of the file, not something to guess at.
            _state.first { !it.isLoading && it.sourceUri == uri }
            if (draft.trimEndMs > draft.trimStartMs) {
                setTrim(draft.trimStartMs, draft.trimEndMs)
            }
            // A file that no longer reads comes back with no length, and a save
            // of that would put a zero-length cut over the one on disk.
            restoring = _state.value.durationMs <= 0L
        }
    }

    /**
     * The session produced a file. It stays, stamped as exported, so "back to
     * the tool" from the done screen finds it as it was - a merge that came out
     * with one clip in the wrong place is fixed by moving that clip, not by
     * choosing six files again. Nothing is stamped for a session that never
     * differed from its first pick, since no draft was written for it.
     * [exported] is the session the file was made from.
     */
    private fun markExported(exported: ToolDraft) {
        val slot = slot ?: return
        persist()
        autosave.markCompleted(slot, exported)
    }

    /** A video chosen on this screen: a new start, whatever was being restored. */
    fun load(uri: Uri) {
        restoring = false
        loadSource(uri)
    }

    private fun loadSource(uri: Uri) {
        if (_state.value.sourceUri == uri) return
        _state.update { it.copy(sourceUri = uri, isLoading = true) }

        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            // Answered in the background, for the export's preflight to read.
            launch { MediaCompat.check(getApplication(), uri) }
            val size = fileSizeOf(uri)

            _state.update {
                it.copy(
                    name = displayNameOf(uri),
                    durationMs = meta.durationMs,
                    width = meta.displayWidth,
                    height = meta.displayHeight,
                    hasAudio = meta.hasAudio,
                    fps = meta.fps,
                    // A resumed session keeps the size it chose; a new video gets
                    // one below its own, so the default never makes it bigger.
                    outputP = if (resumed) it.outputP
                    else OutputSize.squeezeDefault(minOf(meta.displayWidth, meta.displayHeight)),
                    originalSizeBytes = size,
                    trimStartMs = 0,
                    trimEndMs = meta.durationMs,
                    isLoading = false
                )
            }
            recomputeEstimate()
            markBaseline()
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
        viewModelScope.launch { appendMergeClips(uris) }
    }

    /** [addMergeClips], for a caller that needs to know when the clips are in. */
    private suspend fun appendMergeClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // The first pick fills an empty list, and is the starting point rather
        // than an edit. Anything added after that is.
        val firstPick = _state.value.mergeClips.isEmpty()
        val added = uris.map { uri ->
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            viewModelScope.launch { MediaCompat.check(getApplication(), uri) }
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
        if (firstPick) markBaseline()
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
            fps = lead?.fps ?: 30f,
            // Every file that goes in, so the finished screen can say what the
            // merge actually saved instead of comparing against zero.
            originalSizeBytes = sequenced.sumOf { mergeSizes[it.id] ?: 0L },
            trimStartMs = 0L,
            trimEndMs = first?.durationMs ?: 0L
        )
    }

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

    fun setTrim(startMs: Long, endMs: Long) {
        _state.update {
            it.copy(
                trimStartMs = startMs.coerceIn(0L, it.durationMs),
                trimEndMs = endMs.coerceIn(0L, it.durationMs)
            )
        }
        recomputeEstimate()
    }

    /**
     * The estimate is read off the very state the export will be handed, so the
     * number on screen and the file that comes out are worked out by one piece
     * of code rather than two that have to be kept agreeing.
     */
    private fun recomputeEstimate() {
        val tool = tool ?: QuickTool.Squeeze
        val editor = editorStateOf(tool, _state.value)
        _state.update {
            it.copy(
                estimatedOutputBytes = editor.estimatedExportBytes,
                outputFrame = editor.outputResolution,
                writtenFrame = editor.writtenResolution
            )
        }
        probeEncoder(editor.outputResolution)
    }

    /** Which size is being asked about, and which answer to keep; see [ProbeGate]. */
    private val probes = ProbeGate<ExportPresets.Resolution> { _state.value.encoderAnswer?.asked == it }

    /**
     * Asks the phone's encoder what it will write for [asked], once per size,
     * off the main thread; the estimate is worked out again when it answers.
     * An answer for a size no longer chosen is dropped; the estimate that
     * follows the current size's own answer is the one that stands.
     */
    private fun probeEncoder(asked: ExportPresets.Resolution) {
        if (asked.width <= 0 || asked.height <= 0 || !probes.ask(asked)) return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked) }
            if (!probes.keep(asked, _state.value.outputFrame)) return@launch
            _state.update { it.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written)) }
            recomputeEstimate()
        }
    }

    /**
     * This session, as the export pipeline's state.
     *
     * Only Squeeze changes the size. Cutting or joining a video is not a request to
     * shrink it, so the other tools write at the source's own size and bitrate -
     * they used to re-encode everything to 720p on the way through.
     */
    private fun editorStateOf(tool: QuickTool, current: UiState): EditorUiState {
        val merging = tool == QuickTool.Stitch
        return EditorUiState(
            sourceUri = current.sourceUri,
            isLoadingSource = false,
            durationMs = current.durationMs,
            sourceWidth = current.width,
            sourceHeight = current.height,
            fps = current.fps,
            originalSizeBytes = current.originalSizeBytes,
            // A merge's weight is every file's, over every file's length.
            sourceVideoBps = if (merging) {
                ExportPresets.sourceVideoBitrate(current.originalSizeBytes, current.mergeDurationMs, current.hasAudio)
            } else {
                0L
            },
            trimStartMs = if (tool.usesRange) current.trimStartMs else 0,
            trimEndMs = if (tool.usesRange) current.trimEndMs else current.durationMs,
            outputP = if (tool == QuickTool.Squeeze) current.outputP else OutputSize.ORIGINAL,
            fitToSize = tool == QuickTool.Squeeze && current.fitToSize,
            targetSizeMb = current.targetSizeMb,
            encoderAnswer = current.encoderAnswer,
            audioOnly = tool == QuickTool.Rip,
            // Checked rather than assumed, so extracting audio from a silent clip
            // fails immediately with a sentence about it instead of after a full
            // encode with a code nobody can read.
            sourceHasAudio = current.hasAudio,
            // Already in order and already sequenced, so the export is simply the
            // list as shown - there is no second place for the order to be decided.
            videoClips = if (!merging) emptyList()
            else current.mergeClips.filter { it.sourceSpanMs > 0 }
        )
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
        if (current.sourceUri == null) return

        val audioOnly = tool == QuickTool.Rip
        val editorState = editorStateOf(tool, current)

        if (tool == QuickTool.Stitch && editorState.videoClips.isEmpty()) {
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
        // Carried by a foreground service until it is handed over, so a
        // locked screen does not get the process killed mid-encode.
        ExportService.begin(getApplication(), current.name ?: tool.title)
        val rendered = draftOf(tool, current)

        exportJob = viewModelScope.launch {
            // The session as it is goes to disk before the encode starts, not a
            // tick later: a long export is exactly when the app is most likely
            // to be sent to the back and killed.
            withContext(Dispatchers.IO) { persist() }

            val outputDir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
                .apply { mkdirs() }
            val extension = if (audioOnly) "m4a" else "mp4"
            val outputFile = File(outputDir, "squish_${tool.id}_${System.currentTimeMillis()}.$extension")

            // Built at the size the encoder will write, asked now: the answer
            // on screen may be to a size chosen since.
            val asked = editorState.outputResolution
            val written = withContext(Dispatchers.IO) { EncoderCeiling.written(asked) }
            val rendering = editorState.copy(encoderAnswer = ExportPresets.EncoderAnswer(asked, written))

            val result = processor.export(SquishError.exportable(rendering), outputFile) { progress ->
                _state.update { it.copy(exportProgress = progress) }
                ExportService.update(progress)
            }
            // From here the file exists and is being handed over; there is
            // nothing left to stop. See cancelExport.
            if (exportJob === coroutineContext[Job]) exportJob = null

            result.onSuccess { file ->
                // Still exporting until the copy is in the gallery, so the button
                // cannot start a second export over this one's hand-over.
                val saving = _state.value.exportProgress.copy(stage = ExportStage.Saving)
                _state.update { it.copy(exportProgress = saving) }
                ExportService.update(saving)
                // All or nothing: the file into the gallery, into history and the
                // session stamped. Cancelled half-way - the screen leaving in the
                // instant after the encode - it was in the gallery and never
                // stamped.
                withContext(NonCancellable) {
                    val published = if (audioOnly) {
                        GallerySaver.publishAudio(getApplication(), file)
                    } else {
                        GallerySaver.publish(getApplication(), file)
                    }
                    historyRepository.add(
                        ExportRecord(
                            id = UUID.randomUUID().toString(),
                            title = if (tool == QuickTool.Stitch) {
                                "${countOf(current.mergeClips.size, "clip")} merged"
                            } else {
                                current.name ?: tool.title
                            },
                            outputPath = file.absolutePath,
                            originalSizeBytes = current.originalSizeBytes,
                            outputSizeBytes = file.length(),
                            durationMs = editorState.trimmedDurationMs,
                            // The size written, which for a squeeze is not the source's.
                            width = rendering.writtenResolution.width.takeIf { it > 0 } ?: current.width,
                            height = rendering.writtenResolution.height.takeIf { it > 0 } ?: current.height,
                            createdAtMillis = System.currentTimeMillis(),
                            savedToGallery = published != null,
                            galleryUri = published?.toString()
                        )
                    )
                    withContext(Dispatchers.IO) { markExported(rendered) }
                    // The private copy goes once the gallery's is known whole;
                    // the library reads the gallery copy from here on.
                    GallerySaver.retire(getApplication(), file, published)
                }
                ExportService.end(getApplication())
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
                onResult(file.absolutePath)
            }.onFailure { throwable ->
                ExportService.end(getApplication())
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
                // Same typed vocabulary as the editor, so a failure reads the same
                // way whichever door the user came in through.
                val problem = SquishError.from(throwable)
                onError("${problem.title}. ${problem.fix}")
            }
        }
    }

    /**
     * The running export, so back can stop it rather than abandon it. Cleared
     * the moment the encode returns, before the file is published.
     */
    private var exportJob: Job? = null

    /**
     * Stops an export part-way; the processor removes the half-written file.
     *
     * Nothing once the encode has finished. "Stop exporting?" can still be on
     * screen when it does, and a Stop tapped then used to cancel the hand-over
     * instead: the file was already in the gallery and in history, while the
     * screen stayed put as if the export had been stopped.
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

    /**
     * The last change, before the ticker that would have saved it is gone.
     * Blocking on purpose: viewModelScope is already cancelled here.
     */
    override fun onCleared() {
        super.onCleared()
        saveNow()
        // The encode died with the scope; the notification must not outlive it.
        if (_state.value.isExporting) ExportService.end(getApplication())
        ExportsInFlight.set(this, false)
    }

    private companion object {
        /** The editor's interval. A tool session changes far less often than a timeline. */
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
    }
}
