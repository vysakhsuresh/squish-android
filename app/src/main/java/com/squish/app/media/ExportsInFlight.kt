package com.squish.app.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any screen is exporting right now, for what must not cover one.
 *
 * An export runs only while its screen is in front: the screen keeps the
 * display awake, shows the progress, asks "Stop exporting?" on back, and takes
 * the result to the done screen - only while it is the top of the stack. An
 * "Open with" that arrived mid-render used to put a new editor over it: the
 * display could sleep under the rest of the render, and when it finished the
 * result was dropped, so the file was in the gallery and nobody was told. The
 * nav host holds such a request until this is clear (SquishNavHost).
 */
object ExportsInFlight {

    private val exporting = HashSet<Any>()
    private val _any = MutableStateFlow(false)

    /** True while any screen is exporting. */
    val any: StateFlow<Boolean> = _any.asStateFlow()

    /** [owner] - a view model - is exporting, or has stopped. */
    fun set(owner: Any, isExporting: Boolean) {
        synchronized(exporting) {
            if (isExporting) exporting.add(owner) else exporting.remove(owner)
            _any.value = exporting.isNotEmpty()
        }
    }
}
