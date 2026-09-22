package com.squish.app.data

import android.content.Context

/**
 * One HistoryRepository for the whole process. Previously every screen constructed
 * its own instance, so each held a private StateFlow over the same file - an export
 * written by the editor never reached the Home screen's already-loaded flow.
 *
 * The autosave store is shared for the same reason: two instances would each keep
 * their own change signature and race each other writing the same file.
 */
object SquishRepositories {

    @Volatile
    private var historyRepository: HistoryRepository? = null

    @Volatile
    private var autosaveStore: ProjectAutosave? = null

    fun history(context: Context): HistoryRepository =
        historyRepository ?: synchronized(this) {
            historyRepository ?: HistoryRepository(context.applicationContext).also { historyRepository = it }
        }

    fun autosave(context: Context): ProjectAutosave =
        autosaveStore ?: synchronized(this) {
            autosaveStore ?: ProjectAutosave(context.applicationContext).also { autosaveStore = it }
        }
}
