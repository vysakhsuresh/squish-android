package com.squish.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squish.app.data.DraftSummary
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
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

    /**
     * Reads the draft sidecars, off the main thread.
     *
     * Called on every return to the dashboard rather than held in memory: an edit
     * made since the last look should be here, and the whole read is a handful of
     * small files.
     */
    fun refreshDrafts() {
        viewModelScope.launch {
            _drafts.value = withContext(Dispatchers.IO) {
                val edits = autosave.drafts()
                val tools = toolAutosave.drafts().mapNotNull { draft ->
                    val first = draft.uris.firstOrNull() ?: return@mapNotNull null
                    DraftSummary(
                        id = draft.toolId,
                        title = draft.title,
                        sourceUri = first,
                        durationMs = draft.durationMs,
                        clipCount = draft.uris.size,
                        savedAtMillis = draft.savedAtMillis,
                        toolId = draft.toolId
                    )
                }
                (edits + tools).sortedByDescending { it.savedAtMillis }
            }
        }
    }

    fun discardDraft(draft: DraftSummary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // The id means different things in the two stores - a slot derived
                // from the source video, or the tool's own name - so the discard
                // has to go to the store the draft came from.
                if (draft.toolId != null) toolAutosave.clear(draft.toolId) else autosave.delete(draft.id)
            }
            refreshDrafts()
        }
    }
}
