package com.squish.app.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.DraftHousekeeping
import com.squish.app.data.DraftSummary
import com.squish.app.data.ExportRecord
import com.squish.app.data.ProjectRules
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolDraft
import com.squish.app.data.TrashedDraft
import com.squish.app.editor.ProjectName
import com.squish.app.media.releaseReadAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Something just set aside that can be put straight back: a delete, or the
 * version an "earlier version" replaced. [message] is what the snackbar says.
 * [entries] is everything the one action binned - a multi-select delete is
 * several - so its Undo is the whole action, not the last part of it.
 */
data class UndoOffer(val message: String, val entries: List<TrashedDraft>) {
    constructor(message: String, entry: TrashedDraft) : this(message, listOf(entry))
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val historyRepository = SquishRepositories.history(application)
    private val autosave = SquishRepositories.autosave(application)
    private val toolAutosave = SquishRepositories.toolAutosave(application)

    val recentExports: StateFlow<List<ExportRecord>> = historyRepository.records

    /** The projects, newest first: what the dashboard's grid is. */
    private val _projects = MutableStateFlow<List<DraftSummary>>(emptyList())
    val projects: StateFlow<List<DraftSummary>> = _projects.asStateFlow()

    /** The quick tools' unfinished sessions, listed on their own screen with the bin. */
    private val _toolDrafts = MutableStateFlow<List<DraftSummary>>(emptyList())
    val toolDrafts: StateFlow<List<DraftSummary>> = _toolDrafts.asStateFlow()

    /** Everything in the bin, from both stores, newest discard first. */
    private val _trashed = MutableStateFlow<List<TrashedDraft>>(emptyList())
    val trashed: StateFlow<List<TrashedDraft>> = _trashed.asStateFlow()

    /**
     * The set-aside just made, for the drafts screen's Undo. Cleared when the
     * offer has been shown and gone, and when the screen showing it goes away:
     * this view model outlives that screen, and an Undo that came back on a
     * later visit could put an old draft over one made since.
     */
    private val _undoOffer = MutableStateFlow<UndoOffer?>(null)
    val undoOffer: StateFlow<UndoOffer?> = _undoOffer.asStateFlow()

    /**
     * Reads the draft sidecars, off the main thread.
     *
     * Called on every return to the dashboard rather than held in memory: an edit
     * made since the last look should be here, and the whole read is a handful of
     * small files.
     */
    fun refreshDrafts() {
        // The Library door's count leaves out exports deleted from the gallery.
        viewModelScope.launch { historyRepository.forgetDeleted() }
        viewModelScope.launch {
            val (edits, tools, binned) = withContext(Dispatchers.IO) {
                // Anything past its month in the bin goes here, and whatever it
                // was the last thing to name is let go of - the same release a
                // Delete forever does. Done inside the listing, as it used to
                // be, the grants were simply held: a project binned and left to
                // age out kept its picker grant until the app was uninstalled,
                // and the phone caps how many of those an app may keep.
                // Both bins: a quick tool's sessions have their own, and its
                // expiry was still happening inside its listing, which can
                // release nothing - so a binned Trim or Squeeze left to age out
                // held its grant where a binned project no longer did.
                releaseUnnamed(autosave.expireOldTrash() + toolAutosave.expireOldTrash())
                val edits = autosave.drafts()
                val tools = toolAutosave.drafts().mapNotNull { it.summary(withEarlier = true) }
                val bin = autosave.trashed() + toolAutosave.trashed().mapNotNull { (trashId, draft) ->
                    val at = DraftHousekeeping.parseTrashName(trashId)?.second ?: return@mapNotNull null
                    TrashedDraft(trashId, draft.summary(withEarlier = false) ?: return@mapNotNull null, at)
                }
                Triple(edits.sortedByDescending { it.savedAtMillis }, tools.sortedByDescending { it.savedAtMillis }, bin.sortedByDescending { it.discardedAtMillis })
            }
            _projects.value = edits
            _toolDrafts.value = tools
            _trashed.value = binned
        }
    }

    /** On IO: reads the session's snapshots when [withEarlier] asks. */
    private fun ToolDraft.summary(withEarlier: Boolean): DraftSummary? {
        val first = uris.firstOrNull() ?: return null
        return DraftSummary(
            id = slot,
            // A made-up file name ("1001317917.mp4") shows as the day, as a project's does.
            title = ProjectRules.displayTitle(title, savedAtMillis, prefix = "Video"),
            sourceUri = first,
            durationMs = durationMs,
            clipCount = uris.size,
            savedAtMillis = savedAtMillis,
            toolId = toolId,
            exportedAtMillis = exportedAtMillis,
            editFingerprint = toolAutosave.fingerprintOf(this),
            exportedFingerprint = exportedFingerprint,
            earlierSavedAtMillis = if (withEarlier) toolAutosave.earlierSavedAt(this) else null,
            coverUri = first
        )
    }

    /**
     * A new project on [uris], staged on disk for the editor to open
     * (ProjectAutosave.stageStart) and handed to [onReady] by id. Staged
     * before the editor is reached so a kill on the way still finds the files.
     */
    fun startProject(uris: List<Uri>, onReady: (String) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val id = ProjectRules.newId()
            withContext(Dispatchers.IO) { autosave.stageStart(id, uris) }
            onReady(id)
        }
    }

    /** Names a project from its card; blank takes the name off again. */
    fun renameProject(project: DraftSummary, raw: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { autosave.rename(project.id, ProjectName.clean(raw)) }
            refreshDrafts()
        }
    }

    /** A copy of the project, named after it, beside it in the grid. */
    fun duplicateProject(project: DraftSummary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { autosave.duplicate(project.id, _projects.value.map { it.title }) }
            refreshDrafts()
        }
    }

    /** Moves a draft into the bin. Never deletes: the bin keeps it for a month. */
    fun discardDraft(draft: DraftSummary) {
        viewModelScope.launch {
            val trashId = withContext(Dispatchers.IO) { binOne(draft) }
            if (trashId != null) {
                _undoOffer.value = UndoOffer(
                    message = "Deleted “${draft.title}”",
                    entry = TrashedDraft(trashId, draft, System.currentTimeMillis())
                )
            }
            refreshDrafts()
        }
    }

    /**
     * Several at once, from the grid's selection, as one snackbar whose Undo
     * puts every one of them back. It used to restore the last one binned
     * and leave the rest in the bin, under a label that promised otherwise.
     */
    fun discardDrafts(drafts: List<DraftSummary>) {
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val binned = withContext(Dispatchers.IO) {
                drafts.mapNotNull { draft -> binOne(draft)?.let { TrashedDraft(it, draft, now) } }
            }
            if (binned.isNotEmpty()) {
                _undoOffer.value = UndoOffer(
                    message = if (binned.size == 1) "Deleted “${binned.first().draft.title}”" else "Deleted ${binned.size} projects",
                    entries = binned
                )
            }
            refreshDrafts()
        }
    }

    /** The snackbar's Undo: everything the offer binned comes back, then one refresh. */
    fun restoreAll(entries: List<TrashedDraft>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                entries.forEach { entry ->
                    if (entry.draft.toolId != null) toolAutosave.restore(entry.trashId) else autosave.restore(entry.trashId)
                }
            }
            val ids = entries.map { it.trashId }.toSet()
            if (_undoOffer.value?.entries?.any { it.trashId in ids } == true) _undoOffer.value = null
            refreshDrafts()
        }
    }

    // The id means different things in the two stores - a project's own slot,
    // or the tool session's - so the discard goes to the store the draft came from.
    private fun binOne(draft: DraftSummary): String? =
        if (draft.toolId != null) toolAutosave.delete(draft.id) else autosave.delete(draft.id)

    /**
     * Puts a draft's earlier version back; the version it replaces goes into
     * the bin, with an Undo straight away.
     */
    fun revertDraft(draft: DraftSummary) {
        viewModelScope.launch {
            val trashId = withContext(Dispatchers.IO) {
                if (draft.toolId != null) toolAutosave.revertToEarlier(draft.id) else autosave.revertToEarlier(draft.id)
            }
            if (trashId != null) {
                _undoOffer.value = UndoOffer(
                    message = "Went back to the earlier version",
                    entry = TrashedDraft(trashId, draft, System.currentTimeMillis())
                )
            }
            refreshDrafts()
        }
    }

    fun restoreDraft(entry: TrashedDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entry.draft.toolId != null) toolAutosave.restore(entry.trashId) else autosave.restore(entry.trashId)
            }
            if (_undoOffer.value?.entries?.any { it.trashId == entry.trashId } == true) _undoOffer.value = null
            refreshDrafts()
        }
    }

    /**
     * Removes a bin entry for good. Only from a confirmed tap. The app's right
     * to read the files it named goes with it, unless another draft - live or
     * binned, in either store - still names them (ProjectRules.releasable):
     * the phone caps how many grants an app may hold.
     */
    fun purgeDraft(entry: TrashedDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // A quick-tool session's files were not named at all here - the
                // set was simply empty for one - so purging a binned Trim or
                // Squeeze held its picker grant until the app was uninstalled,
                // which is the same leak the bin's own expiry had.
                val named = if (entry.draft.toolId != null) {
                    toolAutosave.trashed().firstOrNull { (id, _) -> id == entry.trashId }
                        ?.second?.uris?.map { it.toString() }?.toSet().orEmpty()
                } else {
                    autosave.urisInTrash(entry.trashId)
                }
                if (entry.draft.toolId != null) toolAutosave.purge(entry.trashId) else autosave.purge(entry.trashId)
                releaseUnnamed(named)
            }
            if (_undoOffer.value?.entries?.any { it.trashId == entry.trashId } == true) _undoOffer.value = null
            refreshDrafts()
        }
    }

    fun dismissUndoOffer() {
        _undoOffer.value = null
    }

    /**
     * Lets go of the app's right to read every one of [named] that nothing else
     * still names. The phone caps how many of those an app may hold, and past
     * the cap the next file picked cannot have one at all.
     *
     * "Nothing else" is every draft and every binned entry, **in both stores** -
     * and it was written out twice, once at each release site, with the two
     * copies already apart: the purge's left out the binned *projects*, so
     * emptying one bin entry could release a file another binned project was
     * the last to name. Written once now, so they cannot drift again.
     */
    private fun releaseUnnamed(named: Set<String>) {
        if (named.isEmpty()) return
        val still = autosave.referencedUris() +
            autosave.trashed().flatMap { autosave.urisInTrash(it.trashId) } +
            toolAutosave.drafts().flatMap { d -> d.uris.map { it.toString() } } +
            toolAutosave.trashed().flatMap { (_, d) -> d.uris.map { it.toString() } }
        ProjectRules.releasable(named, still)
            .filter { it.startsWith("content://") }
            .forEach { getApplication<Application>().releaseReadAccess(Uri.parse(it)) }
    }
}
