package com.squish.app.editor

/**
 * Bounded undo and redo over immutable snapshots.
 *
 * Generic and free of the editor, so it can be run and checked on its own. The
 * only interesting decisions are in here rather than in the view model:
 *
 * **Coalescing.** A slider drag emits a state change every frame. Pushing each
 * one would fill the history with sixty identical-looking steps and make undo
 * useless - press it and nothing appears to happen. Two pushes that carry the
 * same label within [COALESCE_MS] are treated as one continuing gesture: the
 * second replaces nothing, because the snapshot already on the stack is the
 * state from *before* the gesture began, which is where undo should land.
 *
 * **Depth.** Snapshots hold references to immutable lists, so one costs a few
 * dozen pointers rather than a copy of the edit. Even so the history is capped:
 * an editor that has been open for an hour should not be the reason the app runs
 * out of memory, and nobody has ever wanted the fortieth undo.
 *
 * **Redo dies on a new edit.** Standard, and the only sane reading: once the
 * timeline has diverged, the old forward path describes an edit that no longer
 * exists.
 */
class UndoStack<T>(private val maxDepth: Int = MAX_DEPTH) {

    private data class Entry<T>(val label: String, val value: T, val atMillis: Long)

    private val past = ArrayDeque<Entry<T>>()
    private val future = ArrayDeque<Entry<T>>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** What pressing undo would reverse, for a button that says so. */
    val undoLabel: String? get() = past.lastOrNull()?.label

    val redoLabel: String? get() = future.lastOrNull()?.label

    val depth: Int get() = past.size

    /**
     * Records the state as it was *before* an edit.
     *
     * @param label what the edit is, both for the button and for coalescing.
     * @param before the state to return to.
     * @param atMillis when, so a continuing gesture can be recognised.
     */
    fun record(label: String, before: T, atMillis: Long) {
        val top = past.lastOrNull()
        // A gesture still in progress: the stack already holds where it started.
        if (top != null && top.label == label && atMillis - top.atMillis <= COALESCE_MS) {
            past[past.size - 1] = top.copy(atMillis = atMillis)
            future.clear()
            return
        }

        past.addLast(Entry(label, before, atMillis))
        while (past.size > maxDepth) past.removeFirst()
        future.clear()
    }

    /**
     * Steps back one edit.
     *
     * @param current what is on screen now, so redo has somewhere to return to.
     * @return the state to restore, or null when there is nothing to undo.
     */
    fun undo(current: T): T? {
        val entry = past.removeLastOrNull() ?: return null
        future.addLast(entry.copy(value = current))
        return entry.value
    }

    fun redo(current: T): T? {
        val entry = future.removeLastOrNull() ?: return null
        past.addLast(entry.copy(value = current))
        return entry.value
    }

    fun clear() {
        past.clear()
        future.clear()
    }

    companion object {
        /** Two edits closer together than this, under one name, are one gesture. */
        const val COALESCE_MS = 700L

        /** Deep enough to cover a working session, shallow enough to bound memory. */
        const val MAX_DEPTH = 40
    }
}
