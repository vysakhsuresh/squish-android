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
 *
 * **Work that lands in the background** is not pushed on top like an edit. A
 * job that runs for minutes - auto-captioning - has its step opened when it
 * starts ([record] with a tag) and every piece that lands later is folded into
 * that step with [amend], which writes it into every state recorded since. So
 * the person can keep editing while it runs: their edits stay their own steps,
 * undoing one of them keeps the lines that landed after it, and one undo of
 * the run takes all of it. A one-off result - a measurement finishing - goes
 * *under* a gesture in progress with [recordBeneathOpen], so a drag the person
 * is in the middle of stays one step.
 */
class UndoStack<T>(private val maxDepth: Int = MAX_DEPTH) {

    private data class Entry<T>(
        val label: String,
        val value: T,
        val atMillis: Long,
        val gesture: String? = null,
        val holdMs: Long = COALESCE_MS,
        val open: Boolean = gesture != null,
        val tag: String? = null
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
     * @param tag names the step so that background work can later [amend] it.
     */
    fun record(
        label: String,
        before: T,
        atMillis: Long,
        gesture: String? = null,
        holdMs: Long = COALESCE_MS,
        tag: String? = null
    ) {
        val top = past.lastOrNull()
        // A gesture still in progress: the stack already holds where it started.
        if (top != null && gesture != null && continues(gesture, atMillis)) {
            past[past.size - 1] = top.copy(atMillis = atMillis, label = label)
            future.clear()
            return
        }

        // Whatever was on top is finished: something else has happened since.
        if (top != null && top.open) past[past.size - 1] = top.copy(open = false)
        past.addLast(Entry(label, before, atMillis, gesture, holdMs, tag = tag))
        while (past.size > maxDepth) past.removeFirst()
        future.clear()
    }

    /**
     * Whether a push under [gesture] at [atMillis] would carry on the step on top
     * rather than start a new one - the same test [record] makes, asked before
     * the edit so the edit can work from where the gesture began.
     */
    fun continues(gesture: String, atMillis: Long): Boolean {
        val top = past.lastOrNull() ?: return false
        return top.open && top.gesture == gesture && atMillis - top.atMillis <= top.holdMs
    }

    /**
     * A result that finished in the background, recorded as a step of its own
     * without cutting short a gesture the person is in the middle of.
     *
     * With a gesture still moving on top, the result goes beneath it: the new
     * step returns to where the gesture began, and the gesture's own "before"
     * gains the result through [apply]. Undo then takes the rest of the drag
     * first and the result second - the same states, in the same order, as if
     * the result had landed just before the finger went down. With nothing in
     * progress it is an ordinary step.
     *
     * @param before the state now, used when nothing is in progress.
     * @param apply the result, applied to a recorded state.
     */
    fun recordBeneathOpen(label: String, before: T, atMillis: Long, apply: (T) -> T) {
        val top = past.lastOrNull()
        if (top == null || !top.open || atMillis - top.atMillis > top.holdMs) {
            record(label, before, atMillis)
            return
        }
        past.removeLast()
        past.addLast(Entry(label, top.value, atMillis))
        past.addLast(top.copy(value = apply(top.value)))
        while (past.size > maxDepth) past.removeFirst()
        future.clear()
    }

    /**
     * Folds more of a tagged step's work into it, after other steps may have
     * been recorded on top: every state recorded since the step - each a "before"
     * that undo can go back to, and each redo target - gets [change] as well, so
     * no undo of a later edit takes it away. The step's own "before" is left as
     * it is, so undoing the step takes all of it.
     *
     * @return false when the step has been undone, so the work has nowhere to
     *   go and must not be applied. A step that has aged out of the history is
     *   part of every state still in it.
     */
    fun amend(tag: String, change: (T) -> T): Boolean {
        if (future.any { it.tag == tag }) return false
        val at = past.indexOfFirst { it.tag == tag }
        for (i in (at + 1) until past.size) past[i] = past[i].copy(value = change(past[i].value))
        for (i in future.indices) future[i] = future[i].copy(value = change(future[i].value))
        return true
    }

    /**
     * Writes [change] into every state the history holds, past and future.
     *
     * For work that is not an edit at all. A photo on the main track is a short
     * video rendered from the picture, and dragging its tail out past that
     * rendering renders a longer file and swaps it under the clip - which file
     * plays a clip is a fact about the rendering, not about the edit, so it
     * belongs in every state undo and redo can reach, including the ones
     * recorded before the render finished.
     *
     * Left out of them, an undo or a redo of any edit made while the render ran
     * put the long clip back on top of the short file - and nothing ever asks
     * for the longer render again, since only a trim handle's lift does - so
     * the preview played the ten-second file to its end and held its last frame
     * while the clock ran on to forty, for the rest of the session and in the
     * saved draft.
     *
     * Unlike [amend] there is no tag and no refusal: the change is written to
     * the states it matches and is a no-op in the rest, which is what makes it
     * safe to apply blind.
     */
    fun amendAll(change: (T) -> T) {
        for (i in past.indices) past[i] = past[i].copy(value = change(past[i].value))
        for (i in future.indices) future[i] = future[i].copy(value = change(future[i].value))
    }

    /** The tag of the step undo would reverse, if it has one. */
    val undoTag: String? get() = past.lastOrNull()?.tag

    /**
     * Takes a tagged step out of the history. Only for a step that turned out to
     * change nothing - a run that found nothing to add - where it would be an
     * undo that does nothing; the states either side of it are then the same,
     * so removing it leaves every other step where it was.
     */
    fun drop(tag: String) {
        past.removeAll { it.tag == tag }
        future.removeAll { it.tag == tag }
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
