package com.squish.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bumped when a project is added to or taken off the disk from somewhere the
 * dashboard cannot see - an editor binning a project opened just to look at
 * as it closes, after the dashboard has already re-read its list on coming
 * back. The dashboard reads the list again on each bump.
 */
object ProjectsChanged {
    val count = MutableStateFlow(0)
    fun bump() { count.value = count.value + 1 }
}
