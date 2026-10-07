package com.squish.app.tools

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.ProjectRules
import com.squish.app.home.countOf
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolAutosave
import com.squish.app.data.ToolDraft
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.FitOvershoot
import com.squish.app.editor.OutputSize
import com.squish.app.editor.ProbeGate
import com.squish.app.media.EncoderCeiling
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportSettings
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
        /**
         * The bitrate budget's multiplier for the next fitted run, pulled down
         * by however much the last one missed (ExportSettings.retryScale). 1 for
         * a size that has not been missed yet, and back to 1 whenever the target
         * changes - see [setTargetSizeMb].
         */
        val fitScale: Float = 1f,
        /**
         * Set when a fitted run came out over its limit. The file is kept - it is
         * in the gallery and in the library already - but the screen says by how
         * much and offers a tighter run rather than handing it over as if it had
         * fitted. The editor has had this since B14; Squeeze, which is the screen
         * whose whole job is hitting a size, published the oversize file in
         * silence until 7 October.
         */
        val fitOvershoot: FitOvershoot? = null,
        /**
         * The format of the first file in this session whose sound no decoder on
         * this phone takes, or null when there is none - the sentence the screen
         * shows before the render rather than after it.
         *
         * The sound really is lost, not merely at risk: `SquishError.exportable`
         * mutes a source with an `audioProblem` on its way into the renderer, and
         * every tool passes its state through that. The editor has said so on its
         * sheet since the sweep that found it; the tools wrote the silent file
         * and reported "Squeezed · N% smaller". Found by reading on 7 October,
         * the third of the same shape that day.
         */
        val soundLeftOut: String? = null,
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

        /**
         * Whether the size a squeeze is fitted to can be met at all. Below the
         * smallest bitrate anything is written at it cannot, and aiming lower
         * changes nothing - so the overshoot card drops its retry rather than
         * publishing another identical copy to the gallery on every tap. The
         * editor's own [com.squish.app.editor.EditorUiState.fitUnreachable],
         * against the length a squeeze actually writes.
         */
        val fitUnreachable: Boolean
            get() = fitToSize && !ExportPresets.fitReachable(
                targetSizeMb * 1_000_000L, squeezedDurationMs, includeAudio = true
            )

        /** The least this video can be made, for the sentence [fitUnreachable] turns on. */
        val smallestFittedBytes: Long
            get() = ExportPresets.smallestFittedBytes(squeezedDurationMs, includeAudio = true)

        /**
         * What a squeeze writes: Squeeze does not use the range (QuickTool.usesRange
         * is Snip and Extract audio), so it is the whole file - but read from the
         * handles where they are set, so this stays right if that ever changes.
         */
        private val squeezedDurationMs: Long
            get() = (trimEndMs - trimStartMs).takeIf { it > 0L } ?: durationMs
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

    /**
     * Whether the output size on screen is the one a resumed session chose, so
     * loading a file must not re-default it. Apart from [resumed], which also
     * tells `persist` the session came off disk: Change picks a new file for
     * the same session, which wants the size re-defaulted and the session still
     * known to be a resumed one.
     */
    private var keepsResumedSize = false

    /**
     * Whether there is a draft on disk for this slot that this screen owns, so
     * undoing back to nothing - or emptying the list - can take it off.
     *
     * Set when a save lands *and* when a session is resumed, because a resumed
     * one is on disk by definition. It used to be set only by a save, and
     * `ToolAutosave.save` deliberately returns false when the file already says
     * exactly what is on screen - which is every resumed session's first tick.
     * So emptying a resumed Stitch list retracted nothing: the three-clip file
     * stayed on disk and the drafts screen went on offering "3 clips to merge"
     * for a screen the person had cleared.
     */
    @Volatile
    private var wroteDraft = false

    /**
     * Whether this session has written a file. It retires "untouched" (see
     * [persist]), because an export is a deliberate act on the session even
     * when nothing was moved to get there.
     *
     * Found on the phone, 7 October: a Squeeze on a clip whose suggested size
     * was already the one wanted is one tap and no change at all, so the draft
     * never differed from its first pick and nothing was saved - and the run
     * that followed, which took an encode and published a file, left no row on
     * Tool sessions, while the Snip and Stitch beside it (where picking a clip
     * or dragging a handle *is* the work) both had one. The list's own reason
     * for being - go back and fix the one thing that came out wrong rather than
     * choose the files again - is strongest for exactly this case: the thing to
     * change is the size chip, and the session holding it was the one thrown
     * away.
     */
    private var exportedSomething = false

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

        return withContext(Dispatchers.IO) { autosave.peek(slot) }
            ?.also { resumed = true; keepsResumedSize = true; wroteDraft = true }
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
        val untouched = !resumed && !exportedSomething &&
            (baseline == null || autosave.keyOf(draft) == baseline)
        // A session with nothing in it is nothing to keep, and `save` refuses
        // it - so emptying a Stitch list left the three-clip file it had already
        // written sitting on disk, and the drafts screen went on offering "3
        // clips to merge" for a screen the person had cleared. Retracted here
        // instead, by the same rule as undoing back to the start.
        val emptied = draft.uris.isEmpty()
        if (untouched || emptied) {
            if (wroteDraft) {
                // Not `delete`, which is the person's Delete and bins the
                // session for a month: this is the tool screen taking back a
                // file it wrote a tick ago, and through the bin it showed up on
                // the drafts screen as "Recently deleted" for a session nobody
                // had deleted.
                autosave.retract(draft.slot)
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
    private fun restore(draft: ToolDraft, thenAdd: List<Uri> = emptyList()) {
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
                // Picked while the session was coming back: after it, as an add.
                appendMergeClips(thenAdd)
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
     * choosing six files again. [exported] is the session the file was made
     * from.
     *
     * [exportedSomething] goes up *before* the save, because the save is the
     * one that has to see it: a session that never differed from its first
     * pick used to be dropped there, so a run that took an encode and
     * published a file left nothing behind at all.
     */
    private fun markExported(exported: ToolDraft) {
        val slot = slot ?: return
        exportedSomething = true
        persist()
        autosave.markCompleted(slot, exported)
    }

    /**
     * Whether the screen has settled what it opened on - the session in its
     * slot, or none (see [settle]). Until it has, a pick is held rather than
     * shown: after the app was killed behind the picker, the result is handed
     * to the new screen as its launcher registers, before [begin] has read
     * the slot - and the restore that followed replaced the video just picked
     * with the old one, or put the old merge after the new picks.
     */
    private var settled = false
    private var pendingLoad: Uri? = null
    private var pendingAdds: List<Uri> = emptyList()

    /**
     * The screen's answer to [begin]: [draft] is the session in the slot, or
     * null. A video picked before this lands (above) wins over the saved one -
     * it is the newer choice; clips picked for a merge go after the saved ones.
     * Returns whether anything is on its way to the screen, so the screen knows
     * whether to open the picker.
     */
    fun settle(draft: ToolDraft?): Boolean {
        if (settled) return true
        settled = true
        val load = pendingLoad
        val adds = pendingAdds
        pendingLoad = null
        pendingAdds = emptyList()
        when {
            // A new pick in the slot is a new session, not the saved one resumed.
            load != null -> { resumed = false; load(load) }
            draft != null -> restore(draft, thenAdd = adds)
            adds.isNotEmpty() -> addMergeClips(adds)
            else -> return false
        }
        return true
    }

    /** A video chosen on this screen: a new start, whatever was being restored. */
    fun load(uri: Uri) {
        if (!settled) {
            pendingLoad = uri
            return
        }
        restoring = false
        // A different file gets the size its own shape calls for. The size a
        // resumed session chose belongs to the file it chose it for, and this
        // was read off [resumed], which Change never cleared - so Change on a
        // resumed Squeeze carried a 1080p choice onto a 720p file and exported
        // it upscaled, from a chip the sheet itself had greyed out. Not
        // [resumed] itself, which is also what tells `persist` this session
        // came off disk rather than being new.
        keepsResumedSize = false
        loadSource(uri)
    }

    private fun loadSource(uri: Uri) {
        if (_state.value.sourceUri == uri) return
        _state.update { it.copy(sourceUri = uri, isLoading = true) }

        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            // Answered in the background, for the export's preflight to read -
            // and read back onto the screen now as well, since the answer decides
            // whether the file comes out silent.
            launch {
                MediaCompat.check(getApplication(), uri)
                refreshSoundLeftOut()
            }
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
                    outputP = if (keepsResumedSize) it.outputP
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
        if (!settled) {
            pendingAdds = pendingAdds + uris
            return
        }
        viewModelScope.launch { appendMergeClips(uris) }
    }

    /** [addMergeClips], for a caller that needs to know when the clips are in. */
    private suspend fun appendMergeClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // The first pick fills an empty list. One clip is the starting point
        // rather than an edit; several, chosen and ordered, are the work itself -
        // taken as untouched, two clips picked and the app killed kept nothing.
        // Anything added after that is an edit too.
        val firstPick = _state.value.mergeClips.isEmpty()
        val added = uris.map { uri ->
            val meta = ThumbnailExtractor.probe(getApplication(), uri)
            viewModelScope.launch {
                MediaCompat.check(getApplication(), uri)
                refreshSoundLeftOut()
            }
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
        if (firstPick) {
            if (added.size < 2) markBaseline()
            // The empty list is the starting point instead: no baseline at all
            // reads as untouched too.
            else if (!resumed) baseline = EMPTY_BASELINE
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
            fps = lead?.fps ?: 30f,
            // Every file that goes in, so the finished screen can say what the
            // merge actually saved instead of comparing against zero.
            originalSizeBytes = sequenced.sumOf { mergeSizes[it.id] ?: 0L },
            trimStartMs = 0L,
            trimEndMs = first?.durationMs ?: 0L
        )
    }

    // The overshoot card goes with any of the three, because the settings rows
    // stay on screen under it here (the editor's sheet hides them behind the
    // card, so the question never came up there). Touching a size is choosing a
    // different answer to "what should this file be", and leaving the card up
    // would aim "Try again, tighter" at a target the person has just changed.
    // The file it is about is in the gallery either way.
    fun setOutputP(p: Int) {
        _state.update { it.copy(outputP = p, fitToSize = false, fitOvershoot = null) }
        recomputeEstimate()
    }

    fun setFitToSize(enabled: Boolean) {
        _state.update { it.copy(fitToSize = enabled, fitOvershoot = null) }
        recomputeEstimate()
    }

    fun setTargetSizeMb(mb: Int) {
        // The scale goes back to 1 with it: it is how far the *previous* target
        // was missed by, and means nothing against a new one.
        _state.update { it.copy(targetSizeMb = mb, fitScale = 1f, fitOvershoot = null) }
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
    /**
     * The sound this run would leave out, from the same function the editor's
     * sheet asks (`SquishError.soundLeftOut`) against the same state the render
     * is handed. One definition, two screens - which is the lesson of the two
     * faults found before this one on 7 October, both of them a safeguard the
     * editor had and the tool did not.
     *
     * Called when a probe lands rather than computed on the fly, because
     * `MediaCompat.cached` only answers once the background check has finished
     * and nothing else would bring the screen back to ask.
     */
    private fun refreshSoundLeftOut() {
        val tool = tool ?: return
        val lost = SquishError.soundLeftOut(editorStateOf(tool, _state.value))
        if (lost != _state.value.soundLeftOut) _state.update { it.copy(soundLeftOut = lost) }
    }

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
            // Carried, or a second run at a tightened budget would be rendered
            // at the first run's rate and come out the same size again.
            fitScale = current.fitScale,
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

    /**
     * What a picked file is called here. The photo picker names every file by
     * its number ("1001319364.mp4"), and cameras by a stamp; those show as the
     * day the video was taken, "Video · 29 Sep" (ProjectRules.displayTitle).
     */
    private fun displayNameOf(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        val taken = runCatching {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_TAKEN), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull() ?: 0L
        return ProjectRules.displayTitle(name, taken, prefix = "Video").takeIf { it != "Untitled edit" } ?: name
    }

    /**
     * [tightened] is only true for the second run after a missed fit (see
     * [retryFit]). Every other render starts from the plain budget: the scale is
     * how far *that* run missed by, and the editor's copy of this had it outlive
     * its run, so an edit cut down to a quarter of its length was still rendered
     * a fifth under what it was allowed. The same trap here, where the size chip
     * and the Fit switch are a tap away from each other.
     */
    fun export(
        tool: QuickTool,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        tightened: Boolean = false
    ) {
        if (!tightened && _state.value.fitScale != 1f) {
            _state.update { it.copy(fitScale = 1f) }
            recomputeEstimate()
        }
        val current = _state.value
        if (current.sourceUri == null) return

        val audioOnly = tool == QuickTool.Rip
        val editorState = editorStateOf(tool, current)

        if (tool == QuickTool.Stitch && editorState.videoClips.isEmpty()) {
            onError("Nothing to merge. Every clip came back with no length — pick them again.")
            return
        }

        // Every file in the list, not the ones that survived the filter in
        // [editorStateOf]. A clip that no longer reads probes to no length and
        // is kept in the list on purpose - the row says so - but it was filtered
        // out of the state the preflight is handed, so the readability check
        // never saw it: a merge whose middle file had been deleted from the
        // gallery since it was picked rendered the other two and said "Saved to
        // your gallery" for a video a third shorter than the one on screen.
        if (tool == QuickTool.Stitch) {
            val gone = current.mergeClips.filter { it.sourceSpanMs <= 0L }
            if (gone.isNotEmpty()) {
                onError(
                    if (gone.size == 1) "“${gone.first().label}” can't be read any more. Take it out of the list, or pick it again."
                    else "${gone.size} of these clips can't be read any more. Take them out of the list, or pick them again."
                )
                return
            }
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

            // The folder the space check measured and Storage clears, with its
            // fallback: with no external volume, File(null, "exports") was a
            // relative path under "/", and the file could not be written.
            val outputDir = SquishError.exportsDir(getApplication()).apply { mkdirs() }
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
                // Read here, while the private copy is still on disk: it is gone
                // by the end of this block (GallerySaver.retire), and the fitted
                // check below needs the number.
                val size = file.length()
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
                // A fitted squeeze is measured against the limit that was asked
                // for. The file is kept whatever the answer - it is in the
                // gallery and in the library already - but one that missed is
                // not handed over as if it had fitted.
                //
                // This is the editor's rule (B14), and Squeeze is the screen it
                // matters most on: choosing a limit is the whole of what "Fit to
                // a size" is for, and until 7 October a run that came out over it
                // published the file and said "Squeezed · N% smaller" with no
                // word that the number asked for had been missed.
                //
                // `size` is read above, before GallerySaver.retire deletes the
                // private copy - measured after, every overshoot reads as nothing
                // and the card can never show. That is not a hypothetical: it is
                // the fault B15's review round found in the editor's copy of this.
                val target = current.targetSizeMb * 1_000_000L
                if (editorState.fitToSize && !audioOnly && ExportSettings.overshoots(size, target)) {
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
                _state.update { it.copy(isExporting = false, exportProgress = ExportProgress()) }
                // Same typed vocabulary as the editor, so a failure reads the same
                // way whichever door the user came in through.
                val problem = SquishError.from(throwable)
                onError("${problem.title}. ${problem.fix}")
            }
        }
    }

    /** The oversize file is the one wanted after all: handed over as any export is. */
    fun keepOversize(onResult: (String) -> Unit) {
        val kept = _state.value.fitOvershoot ?: return
        _state.update { it.copy(fitOvershoot = null) }
        onResult(kept.path)
    }

    /**
     * Runs the squeeze again at the tightened budget; the oversize file stays in
     * the gallery and in the library.
     *
     * The card is held rather than dropped, for the editor's reason: [export]
     * has refusals that come back without starting a render - the likeliest
     * being the space check, which this run's own gallery copy has just made
     * more likely - and with the card already cleared, the oversize file could
     * never reach the done screen and "Keep this one" could not be reached at
     * all. Every refusal happens before the render starts, so the card goes
     * back up.
     */
    fun retryFit(tool: QuickTool, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val held = _state.value.fitOvershoot
        _state.update { it.copy(fitOvershoot = null) }
        export(tool, onResult, onError, tightened = true)
        val after = _state.value
        if (held != null && !after.isExporting && after.fitOvershoot == null) {
            _state.update { it.copy(fitOvershoot = held) }
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
        /** A baseline no session matches: a Stitch whose first pick was already several clips. */
        const val EMPTY_BASELINE = ""
        /** The editor's interval. A tool session changes far less often than a timeline. */
        val AUTOSAVE_INTERVAL = 1_500.milliseconds
    }
}
