package com.squish.app.editor

/**
 * Bounded undo and redo over immutable snapshots.
 *
 * Generic and free of the editor, so it can be run and checked on its own. The
 * only interesting decisions are in here rather than in the view model:
 *
 * **Coalescing is by gesture, not by clock.** A slider drag emits a state change
 * every frame. Pushing each one would fill the history with sixty
 * identical-looking steps. So a continuing edit names itself with a gesture id -
 * "Level <clip>", "Move <clip>" - and further pushes under the same id join the
 * step already on top, whose snapshot is the state from *before* the gesture
 * began, which is where undo should land.
 *
 * It used to be label plus a 700 ms window, which also merged things that were
 * never one gesture: two Cut presses in quick succession, or five taps of a
 * frame-nudge button, came back as one step and one undo jumped back all five.
 * A discrete action now carries no gesture id and is never merged with anything.
 *
 * A gesture ends when the control says so ([endGesture], from a slider's
 * release), when anything else is recorded on top of it, or - for controls that
 * cannot say when the finger lifted - after [COALESCE_MS] with no movement. A
 * drag delivers an event every frame, so that silence means the finger stopped.
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

    private data class Entry<T>(
        val label: String,
        val value: T,
        val atMillis: Long,
        val gesture: String? = null,
        val holdMs: Long = COALESCE_MS,
        val open: Boolean = gesture != null
    )

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
     * @param label what the edit is, for the button.
     * @param before the state to return to.
     * @param atMillis when, so a gesture that went quiet can be told apart from
     *   one still moving.
     * @param gesture names a continuing edit - one drag, one slider, one run of
     *   typing - so that its many pushes are one step. Null for a discrete action,
     *   which is always a step of its own.
     * @param holdMs how long the gesture may go quiet and still continue. Typing
     *   pauses between words far longer than a drag pauses between frames.
     */
    fun record(
        label: String,
        before: T,
        atMillis: Long,
        gesture: String? = null,
        holdMs: Long = COALESCE_MS
    ) {
        val top = past.lastOrNull()
        // A gesture still in progress: the stack already holds where it started.
        if (gesture != null && top != null && top.open && top.gesture == gesture &&
            atMillis - top.atMillis <= top.holdMs
        ) {
            past[past.size - 1] = top.copy(atMillis = atMillis, label = label)
            future.clear()
            return
        }

        // Whatever was on top is finished: something else has happened since.
        if (top != null && top.open) past[past.size - 1] = top.copy(open = false)
        past.addLast(Entry(label, before, atMillis, gesture, holdMs))
        while (past.size > maxDepth) past.removeFirst()
        future.clear()
    }

    /**
     * The finger lifted. The next push under the same gesture id - a second drag
     * of the same slider - is a new step, however soon it comes.
     *
     * With [gesture], only that gesture is ended: something finishing in the
     * background must not cut short a drag the person is in the middle of.
     */
    fun endGesture(gesture: String? = null) {
        val top = past.lastOrNull() ?: return
        if (!top.open || (gesture != null && top.gesture != gesture)) return
        past[past.size - 1] = top.copy(open = false)
    }

    /**
     * Steps back one edit.
     *
     * @param current what is on screen now, so redo has somewhere to return to.
     * @return the state to restore, or null when there is nothing to undo.
     */
    fun undo(current: T): T? {
        val entry = past.removeLastOrNull() ?: return null
        future.addLast(entry.copy(value = current, open = false))
        return entry.value
    }

    fun redo(current: T): T? {
        val entry = future.removeLastOrNull() ?: return null
        // Closed, so an edit after redo is never folded into the step redone.
        past.addLast(entry.copy(value = current, open = false))
        return entry.value
    }

    fun clear() {
        past.clear()
        future.clear()
    }

    companion object {
        /** How long a gesture that cannot say when it ended may go quiet and still be one step. */
        const val COALESCE_MS = 700L

        /** Deep enough to cover a working session, shallow enough to bound memory. */
        const val MAX_DEPTH = 40
    }
}
