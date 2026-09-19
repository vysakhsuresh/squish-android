package com.squish.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.squish.app.data.ExportRecord
import com.squish.app.data.HistoryRepository
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val historyRepository = HistoryRepository(application)
    val recentExports: StateFlow<List<ExportRecord>> = historyRepository.records
}
