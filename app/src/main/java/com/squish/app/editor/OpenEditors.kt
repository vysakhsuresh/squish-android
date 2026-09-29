package com.squish.app.editor

import android.net.Uri

/**
 * The videos with an editor alive on the back stack, kept by the editors
 * themselves (EditorViewModel), for "Open with".
 *
 * Every editor of a video saves into the same draft slot, on its own ticker,
 * and each thinks the slot is its own. Two of them at once - "Open with" on a
 * video that was already open used to stack a second editor over the first -
 * took turns writing their own state over the slot every second and a half,
 * and whichever wrote last after the other was left won: the work done in
 * either could be gone from disk with nothing in the bin. So a request for a
 * video that is open goes back to that editor instead (SquishNavHost), which
 * needs to know it is there when it may be several screens down, where no
 * screen of it is composed. Counted, not flagged, so a copy closing does not
 * hide one still open.
 */
object OpenEditors {

    private val open = HashMap<String, Int>()

    fun opened(uri: Uri) = synchronized(open) { open[uri.toString()] = (open[uri.toString()] ?: 0) + 1 }

    fun closed(uri: Uri) = synchronized(open) {
        val key = uri.toString()
        val left = (open[key] ?: 0) - 1
        if (left <= 0) open.remove(key) else open[key] = left
    }

    fun isOpen(uri: Uri): Boolean = synchronized(open) { (open[uri.toString()] ?: 0) > 0 }
}
