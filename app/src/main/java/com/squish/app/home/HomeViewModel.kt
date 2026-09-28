package com.squish.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.DraftHousekeeping
import com.squish.app.data.DraftSummary
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.data.ToolDraft
import com.squish.app.data.TrashedDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Something just set aside that can be put straight back: a discard, or the
 * version an "earlier version" replaced. [message] is what the snackbar says.
 */
data class UndoOffer(val message: String, val entry: TrashedDraft)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val historyRepository = SquishRepositories.history(application)
    private val autosave = SquishRepositories.autosave(application)
    private val toolAutosave = SquishRepositories.toolAutosave(application)

    val recentExports: StateFlow<List<ExportRecord>> = historyRepository.records

    private val _drafts = MutableStateFlow<List<DraftSummary>>(emptyList())
    val drafts: StateFlow<List<DraftSummary>> = _drafts.asStateFlow()

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
        viewModelScope.launch {
            val (live, binned) = withContext(Dispatchers.IO) {
                val edits = autosave.drafts()
                val tools = toolAutosave.drafts().mapNotNull { it.summary(withEarlier = true) }
                val bin = autosave.trashed() + toolAutosave.trashed().mapNotNull { (trashId, draft) ->
                    val at = DraftHousekeeping.parseTrashName(trashId)?.second ?: return@mapNotNull null
                    TrashedDraft(trashId, draft.summary(withEarlier = false) ?: return@mapNotNull null, at)
                }
                (edits + tools).sortedByDescending { it.savedAtMillis } to bin.sortedByDescending { it.discardedAtMillis }
            }
            _drafts.value = live
            _trashed.value = binned
        }
    }

    /** On IO: reads the session's snapshots when [withEarlier] asks. */
    private fun ToolDraft.summary(withEarlier: Boolean): DraftSummary? {
        val first = uris.firstOrNull() ?: return null
        return DraftSummary(
            id = slot,
            title = title,
            sourceUri = first,
            durationMs = durationMs,
            clipCount = uris.size,
            savedAtMillis = savedAtMillis,
            toolId = toolId,
            exportedAtMillis = exportedAtMillis,
            editFingerprint = toolAutosave.fingerprintOf(this),
            exportedFingerprint = exportedFingerprint,
            earlierSavedAtMillis = if (withEarlier) toolAutosave.earlierSavedAt(this) else null
        )
    }

    /** Moves a draft into the bin. Never deletes: the bin keeps it for a month. */
    fun discardDraft(draft: DraftSummary) {
        viewModelScope.launch {
            val trashId = withContext(Dispatchers.IO) {
                // The id means different things in the two stores - a slot derived
                // from the source video, or the tool session's own slot - so the
                // discard has to go to the store the draft came from.
                if (draft.toolId != null) toolAutosave.delete(draft.id) else autosave.delete(draft.id)
            }
            if (trashId != null) {
                _undoOffer.value = UndoOffer(
                    message = "Discarded \"${draft.title}\"",
                    entry = TrashedDraft(trashId, draft, System.currentTimeMillis())
                )
            }
            refreshDrafts()
        }
    }

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
            if (_undoOffer.value?.entry?.trashId == entry.trashId) _undoOffer.value = null
            refreshDrafts()
        }
    }

    /** Removes a bin entry for good. Only from a confirmed tap. */
    fun purgeDraft(entry: TrashedDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entry.draft.toolId != null) toolAutosave.purge(entry.trashId) else autosave.purge(entry.trashId)
            }
            if (_undoOffer.value?.entry?.trashId == entry.trashId) _undoOffer.value = null
            refreshDrafts()
        }
    }

    fun dismissUndoOffer() {
        _undoOffer.value = null
    }
}
