package com.squish.app.data

import android.content.Context

/**
 * One HistoryRepository for the whole process. Previously every screen constructed
 * its own instance, so each held a private StateFlow over the same file - an export
 * written by the editor never reached the Home screen's already-loaded flow.
 */
object SquishRepositories {

    @Volatile
    private var historyRepository: HistoryRepository? = null

    fun history(context: Context): HistoryRepository =
        historyRepository ?: synchronized(this) {
            historyRepository ?: HistoryRepository(context.applicationContext).also { historyRepository = it }
        }
}
