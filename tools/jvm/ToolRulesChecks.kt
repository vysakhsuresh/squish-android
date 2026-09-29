import com.squish.app.editor.BackStep
import com.squish.app.editor.LEVEL_ZERO
import com.squish.app.editor.ProjectName
import com.squish.app.editor.SelectionKind
import com.squish.app.editor.ShotSpan
import com.squish.app.editor.Tool
import com.squish.app.editor.backStep
import com.squish.app.editor.cutTarget
import com.squish.app.editor.sheetSurvives
import com.squish.app.editor.toolsFor
import kotlin.system.exitProcess

// The editor's toolbar decisions, executed: which tools show for what is
// selected, what back does, which shot Cut opens, what a typed name becomes.
// Each block is a claim from docs/ROADMAP.md section 2 or batch B6.

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- Level 0: the order section 2 decided, nothing clip-scoped on it. -------
    run {
        check(
            LEVEL_ZERO == listOf(Tool.Cut, Tool.Sound, Tool.Text, Tool.Stickers, Tool.Overlay, Tool.Effects, Tool.Looks, Tool.Frame),
            "level 0 is $LEVEL_ZERO"
        )
        check(toolsFor(SelectionKind.None) == LEVEL_ZERO, "nothing selected does not show level 0")
        check(LEVEL_ZERO.all { it.levelZero }, "a level-0 tool does not know it")
        check(SelectionKind.entries.filter { it != SelectionKind.None }.none { kind ->
            toolsFor(kind, canTransition = true).any { it.levelZero }
        }, "a level-0 tool leaked onto a clip's toolbar")
    }

    // --- Level 1: every clip can be split and deleted; the tap budgets hold. ----
    run {
        for (kind in SelectionKind.entries.filter { it != SelectionKind.None }) {
            val tools = toolsFor(kind, canTransition = true)
            check(Tool.Delete in tools, "$kind has no Delete")
            check(Tool.Split in tools, "$kind has no Split")
            check(Tool.Duplicate in tools, "$kind has no Duplicate")
            check(tools.distinct() == tools, "$kind lists a tool twice: $tools")
            check(tools.last() == Tool.Delete, "$kind: Delete is not last, where a slip cannot reach it by accident")
        }
        // Speed is three taps: the clip, Speed, a preset. So it is on the first screenful.
        check(toolsFor(SelectionKind.MainVideo).indexOf(Tool.Speed) in 0..3, "Speed is not near the start for a shot")
        check(toolsFor(SelectionKind.MainVideo).first() == Tool.Split, "Split is not first for a shot")
    }

    // --- Transition only where there is a join to put it on. --------------------
    run {
        check(Tool.Transition !in toolsFor(SelectionKind.MainVideo, canTransition = false), "the first shot offers a transition")
        check(Tool.Transition in toolsFor(SelectionKind.MainVideo, canTransition = true), "a later shot offers no transition")
        check(Tool.Transition !in toolsFor(SelectionKind.Overlay, canTransition = true), "an overlay offers a cut transition")
        check(Tool.ToOverlay in toolsFor(SelectionKind.MainVideo) && Tool.ToMain !in toolsFor(SelectionKind.MainVideo),
            "a shot does not offer to float")
        check(Tool.ToMain in toolsFor(SelectionKind.Overlay) && Tool.ToOverlay !in toolsFor(SelectionKind.Overlay),
            "an overlay does not offer to drop to the main track")
        check(Tool.Opacity in toolsFor(SelectionKind.Overlay) && Tool.Layer in toolsFor(SelectionKind.Overlay),
            "an overlay has no opacity or layer")
    }

    // --- Sheets across a change of selection. -------------------------------------
    run {
        check(sheetSurvives(Tool.Stickers, SelectionKind.Sticker, false), "adding a sticker closed the sticker sheet")
        check(sheetSurvives(Tool.Sound, SelectionKind.None, false), "deselecting closed the Sound sheet")
        check(sheetSurvives(Tool.Speed, SelectionKind.Audio, false), "Speed closed moving from a shot to a sound")
        check(!sheetSurvives(Tool.Speed, SelectionKind.Text, false), "Speed stayed open over a caption")
        check(!sheetSurvives(Tool.Speed, SelectionKind.None, false), "Speed stayed open with nothing selected")
        check(!sheetSurvives(Tool.Transition, SelectionKind.MainVideo, canTransition = false),
            "Transition stayed open on the first shot")
    }

    // --- Back: innermost first. ------------------------------------------------------
    run {
        check(backStep(fullscreen = true, sheetOpen = true, hasSelection = true) == BackStep.ExitFullscreen, "back past fullscreen")
        check(backStep(false, sheetOpen = true, hasSelection = true) == BackStep.CloseSheet, "back did not close the sheet first")
        check(backStep(false, false, hasSelection = true) == BackStep.Deselect, "back did not return to level 0")
        check(backStep(false, false, false) == BackStep.Leave, "back at level 0 did not leave")
    }

    // --- Which shot Cut opens. -----------------------------------------------------------
    run {
        val shots = listOf(ShotSpan("a", 0, 3_000), ShotSpan("b", 3_000, 5_000), ShotSpan("c", 5_000, 9_000))
        check(cutTarget(shots, 1_000) == "a", "inside a")
        check(cutTarget(shots, 3_000) == "b", "on the a|b cut: ${cutTarget(shots, 3_000)}, not the incoming b")
        check(cutTarget(shots, 9_000) == "c", "at the very end: ${cutTarget(shots, 9_000)}")
        check(cutTarget(shots, 60_000) == "c", "past the end")
        check(cutTarget(shots.reversed(), 4_000) == "b", "order of the list mattered")
        // A dissolve: b starts before a ends; the playhead in the overlap is on b.
        val dissolve = listOf(ShotSpan("a", 0, 3_000), ShotSpan("b", 2_500, 5_000))
        check(cutTarget(dissolve, 2_700) == "b", "in a dissolve: ${cutTarget(dissolve, 2_700)}")
        // A gap from an old draft: the nearer shot.
        val gap = listOf(ShotSpan("a", 0, 2_000), ShotSpan("b", 6_000, 8_000))
        check(cutTarget(gap, 2_500) == "a", "gap near a: ${cutTarget(gap, 2_500)}")
        check(cutTarget(gap, 5_500) == "b", "gap near b: ${cutTarget(gap, 5_500)}")
        check(cutTarget(emptyList(), 0) == null, "no shots, but a target")
    }

    // --- Project names. ------------------------------------------------------------------
    run {
        check(ProjectName.clean("  Holiday \n in   Goa ") == "Holiday in Goa", "whitespace: '${ProjectName.clean("  Holiday \n in   Goa ")}'")
        check(ProjectName.clean("   ") == null, "a blank name was kept")
        check(ProjectName.clean("") == null, "an empty name was kept")
        val long = "x".repeat(200)
        check(ProjectName.clean(long)?.length == ProjectName.MAX_LENGTH, "a long name was not cut to ${ProjectName.MAX_LENGTH}")
        // An emoji straddling the limit is dropped whole, never halved.
        val straddle = "a".repeat(ProjectName.MAX_LENGTH - 1) + "🎉" + "tail"
        val cut = ProjectName.clean(straddle)!!
        check(!cut.last().isHighSurrogate(), "a name was cut through an emoji")
        check(cut.length == ProjectName.MAX_LENGTH - 1, "the straddling emoji: ${cut.length} chars")
        check(ProjectName.clean("Trip 🎉") == "Trip 🎉", "an emoji inside the limit was touched")
    }

    println("tool rules: toolbar levels, sheets, back, cut target, project names")
    if (problems.isEmpty()) println("PASS - the toolbar shows what section 2 decided and back unwinds innermost first")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
