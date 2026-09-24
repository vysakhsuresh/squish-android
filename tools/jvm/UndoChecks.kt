import com.squish.app.editor.UndoStack
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- The shape everyone expects. ------------------------------------------
    run {
        val s = UndoStack<String>()
        check(!s.canUndo && !s.canRedo, "a new stack claims history")
        check(s.undo("a") == null, "undoing an empty stack returned something")
        check(s.redo("a") == null, "redoing an empty stack returned something")

        s.record("cut", "a", 0)          // a -> b
        s.record("trim", "b", 1000)      // b -> c   (state is now "c")
        check(s.canUndo, "two edits left nothing to undo")
        check(s.undoLabel == "trim", "the label is ${s.undoLabel}, not the last edit")

        check(s.undo("c") == "b", "undo did not return the state before the trim")
        check(s.undo("b") == "a", "undo did not return the state before the cut")
        check(s.undo("a") == null, "undo went past the beginning")

        check(s.redo("a") == "b", "redo did not replay the cut")
        check(s.redo("b") == "c", "redo did not replay the trim")
        check(s.redo("c") == null, "redo went past the end")
    }

    // --- A new edit after undoing must drop the forward path. -----------------
    run {
        val s = UndoStack<String>()
        s.record("cut", "a", 0)
        s.record("trim", "b", 1000)
        s.undo("c")
        check(s.canRedo, "undo left nothing to redo")
        s.record("move", "b", 2000)
        check(!s.canRedo, "a new edit did not discard the redo path")
    }

    // --- Coalescing: a drag is one step, not sixty. ---------------------------
    run {
        val s = UndoStack<String>()
        var t = 0L
        // Sixty ticks of one slider drag, all within the window of each other.
        repeat(60) { s.record("brightness", if (it == 0) "start" else "mid$it", t); t += 16 }
        check(s.depth == 1, "a 60-frame drag recorded ${s.depth} steps")
        check(s.undo("end") == "start", "undoing a drag did not return to before it began")

        // A pause longer than the window starts a new gesture.
        val u = UndoStack<String>()
        u.record("brightness", "a", 0)
        u.record("brightness", "b", UndoStack.COALESCE_MS + 1)
        check(u.depth == 2, "a pause did not start a new step (${u.depth})")

        // A different edit in between is never coalesced with it.
        val v = UndoStack<String>()
        v.record("brightness", "a", 0)
        v.record("cut", "b", 10)
        v.record("brightness", "c", 20)
        check(v.depth == 3, "different labels were coalesced (${v.depth})")
    }

    // --- Depth is bounded, and it is the oldest that goes. --------------------
    run {
        val s = UndoStack<Int>(maxDepth = 5)
        repeat(20) { s.record("edit$it", it, it * 10_000L) }
        check(s.depth == 5, "the cap did not hold: ${s.depth}")
        // The five most recent survive, so the first undo is edit19's "before".
        check(s.undo(99) == 19, "the newest entry was not kept")
        repeat(4) { s.undo(0) }
        check(!s.canUndo, "more than the cap survived")
    }

    // --- A long session must not grow without bound. --------------------------
    run {
        val s = UndoStack<Int>()
        repeat(10_000) { s.record("edit", it, it * 10_000L) }
        check(s.depth <= UndoStack.MAX_DEPTH, "ten thousand edits left ${s.depth} on the stack")
    }

    // --- Undo/redo round trips exactly, over a random walk. -------------------
    run {
        val s = UndoStack<Int>()
        var state = 0
        var t = 0L
        val expected = mutableListOf(0)
        repeat(30) { i ->
            s.record("edit$i", state, t)
            t += UndoStack.COALESCE_MS * 2
            state = i + 1
            expected.add(state)
        }
        // Walk all the way back, then all the way forward.
        var cursor = expected.size - 1
        while (s.canUndo) {
            val before = s.undo(state)!!
            cursor--
            check(before == expected[cursor], "undo gave $before, expected ${expected[cursor]}")
            state = before
        }
        while (s.canRedo) {
            val after = s.redo(state)!!
            cursor++
            check(after == expected[cursor], "redo gave $after, expected ${expected[cursor]}")
            state = after
        }
        check(state == expected.last(), "the walk did not return to where it started")
    }

    // --- clear() leaves nothing behind. ---------------------------------------
    run {
        val s = UndoStack<String>()
        s.record("a", "1", 0)
        s.record("b", "2", 10_000)
        s.undo("3")
        s.clear()
        check(!s.canUndo && !s.canRedo, "clear left history behind")
        check(s.undoLabel == null && s.redoLabel == null, "clear left labels behind")
    }

    println("undo stack: depth cap ${UndoStack.MAX_DEPTH}, coalesce window ${UndoStack.COALESCE_MS}ms")
    if (problems.isEmpty()) println("PASS - undo and redo step exactly, coalesce drags, and stay bounded")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
