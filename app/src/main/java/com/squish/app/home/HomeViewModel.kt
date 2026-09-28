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
     * The discard just made, for the drafts screen's Undo. Cleared when the
     * offer to undo it has been shown and gone.
     */
    private val _lastDiscarded = MutableStateFlow<TrashedDraft?>(null)
    val lastDiscarded: StateFlow<TrashedDraft?> = _lastDiscarded.asStateFlow()

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
                val tools = toolAutosave.drafts().mapNotNull { it.summary() }
                val bin = autosave.trashed() + toolAutosave.trashed().mapNotNull { (trashId, draft) ->
                    val at = DraftHousekeeping.parseTrashName(trashId)?.second ?: return@mapNotNull null
                    TrashedDraft(trashId, draft.summary() ?: return@mapNotNull null, at)
                }
                (edits + tools).sortedByDescending { it.savedAtMillis } to bin.sortedByDescending { it.discardedAtMillis }
            }
            _drafts.value = live
            _trashed.value = binned
        }
    }

    private fun ToolDraft.summary(): DraftSummary? {
        val first = uris.firstOrNull() ?: return null
        return DraftSummary(
            id = slot,
            title = title,
            sourceUri = first,
            durationMs = durationMs,
            clipCount = uris.size,
            savedAtMillis = savedAtMillis,
            toolId = toolId,
            exportedAtMillis = exportedAtMillis
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
            if (trashId != null) _lastDiscarded.value = TrashedDraft(trashId, draft, System.currentTimeMillis())
            refreshDrafts()
        }
    }

    fun restoreDraft(entry: TrashedDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entry.draft.toolId != null) toolAutosave.restore(entry.trashId) else autosave.restore(entry.trashId)
            }
            if (_lastDiscarded.value?.trashId == entry.trashId) _lastDiscarded.value = null
            refreshDrafts()
        }
    }

    /** Removes a bin entry for good. Only from a confirmed tap. */
    fun purgeDraft(entry: TrashedDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entry.draft.toolId != null) toolAutosave.purge(entry.trashId) else autosave.purge(entry.trashId)
            }
            if (_lastDiscarded.value?.trashId == entry.trashId) _lastDiscarded.value = null
            refreshDrafts()
        }
    }

    fun dismissLastDiscarded() {
        _lastDiscarded.value = null
    }
}
