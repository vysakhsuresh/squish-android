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
        // Sixty ticks of one slider drag, all under one gesture id.
        repeat(60) { s.record("brightness", if (it == 0) "start" else "mid$it", t, gesture = "brightness"); t += 16 }
        check(s.depth == 1, "a 60-frame drag recorded ${s.depth} steps")
        check(s.undo("end") == "start", "undoing a drag did not return to before it began")

        // A pause longer than the hold starts a new gesture.
        val u = UndoStack<String>()
        u.record("brightness", "a", 0, gesture = "brightness")
        u.record("brightness", "b", UndoStack.COALESCE_MS + 1, gesture = "brightness")
        check(u.depth == 2, "a pause did not start a new step (${u.depth})")

        // A different edit in between is never coalesced with it.
        val v = UndoStack<String>()
        v.record("brightness", "a", 0, gesture = "brightness")
        v.record("cut", "b", 10)
        v.record("brightness", "c", 20, gesture = "brightness")
        check(v.depth == 3, "different labels were coalesced (${v.depth})")
    }

    // --- Discrete actions are never merged, however quick. --------------------
    run {
        // Two Cut presses 100 ms apart, five frame-nudge taps: each its own step.
        val s = UndoStack<String>()
        s.record("Cut", "a", 0)
        s.record("Cut", "b", 100)
        check(s.depth == 2, "two quick cuts became ${s.depth} step(s)")
        val n = UndoStack<String>()
        repeat(5) { n.record("Trim clip1", "t$it", it * 50L) }
        check(n.depth == 5, "five nudges became ${n.depth} step(s)")
        check(n.undo("t5") == "t4", "one undo after five nudges did not step back one")
    }

    // --- The finger lifting ends the gesture. ---------------------------------
    run {
        val s = UndoStack<String>()
        s.record("Level", "a", 0, gesture = "Level x")
        s.record("Level", "b", 16, gesture = "Level x")
        s.endGesture()
        // A second drag of the same slider, straight after: a second step.
        s.record("Level", "c", 40, gesture = "Level x")
        check(s.depth == 2, "a second drag after release joined the first (${s.depth})")
        check(s.undo("d") == "c" && s.undo("c") == "a", "the two drags did not undo separately")
    }

    // --- Ending a named gesture leaves any other gesture alone. ---------------
    run {
        val s = UndoStack<String>()
        s.record("Level", "a", 0, gesture = "Level x")
        s.endGesture("Auto-caption run")      // a background job finishing
        s.record("Level", "b", 16, gesture = "Level x")
        check(s.depth == 1, "a background gesture ending split the drag in progress (${s.depth})")
        s.endGesture("Level x")
        s.record("Level", "c", 32, gesture = "Level x")
        check(s.depth == 2, "ending the named gesture did not end it (${s.depth})")
    }

    // --- Two sliders, or two clips, are two gestures. -------------------------
    run {
        val s = UndoStack<String>()
        s.record("Level", "a", 0, gesture = "Level clip1")
        s.record("Level", "b", 10, gesture = "Level clip2")
        check(s.depth == 2, "one clip's level coalesced into another's (${s.depth})")
    }

    // --- Typing holds longer than a drag. -------------------------------------
    run {
        val s = UndoStack<String>()
        s.record("Caption text", "", 0, gesture = "Text c1", holdMs = 5_000)
        s.record("Caption text", "H", 2_000, gesture = "Text c1", holdMs = 5_000)
        s.record("Caption text", "He", 6_500, gesture = "Text c1", holdMs = 5_000)
        check(s.depth == 1, "a pause between words split the typing (${s.depth})")
        s.record("Caption text", "Hello", 20_000, gesture = "Text c1", holdMs = 5_000)
        check(s.depth == 2, "coming back to the line much later did not start a step (${s.depth})")
    }

    // --- Undo and redo close the gesture they pass. ---------------------------
    run {
        val s = UndoStack<String>()
        s.record("Level", "a", 0, gesture = "Level x")
        s.undo("b")
        s.redo("a")
        // Straight after redo, the same slider again: not folded into the redone step.
        s.record("Level", "b", 10, gesture = "Level x")
        check(s.depth == 2, "an edit after redo was merged into the redone step (${s.depth})")
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
    if (problems.isEmpty()) println("PASS - undo and redo step exactly, coalesce gestures and only gestures, and stay bounded")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
