import com.squish.app.editor.BackStep
import com.squish.app.editor.DELETE_POSITION
import com.squish.app.editor.FOOTAGE_TOOLS
import com.squish.app.editor.LEVEL_ZERO
import com.squish.app.editor.NEEDS_MOTION
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
            LEVEL_ZERO == listOf(Tool.Clip, Tool.Sound, Tool.Text, Tool.Stickers, Tool.Overlay, Tool.Effects, Tool.Looks, Tool.Frame),
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
            // Fifth on every row (section 2, CapCut), or last of a shorter one:
            // on the first screenful, in the same place whatever is selected.
            // It was last, which on a shot's row of 23 was three screens away.
            val expectedAt = minOf(DELETE_POSITION, tools.size) - 1
            check(tools.indexOf(Tool.Delete) == expectedAt, "$kind: Delete is ${tools.indexOf(Tool.Delete) + 1}th, not ${expectedAt + 1}th: $tools")
            check(toolsFor(kind, canTransition = false).indexOf(Tool.Delete) == expectedAt, "$kind without a transition: Delete moved")
        }
        // Speed is three taps: the clip, Speed, a preset. So it is on the first screenful.
        check(toolsFor(SelectionKind.MainVideo).indexOf(Tool.Speed) in 0..3, "Speed is not near the start for a shot")
        check(toolsFor(SelectionKind.MainVideo).first() == Tool.Split, "Split is not first for a shot")
    }

    // --- Transition only where there is a join to put it on. --------------------
    run {
        check(Tool.Transition !in toolsFor(SelectionKind.MainVideo, canTransition = false), "the first shot offers a transition")
        check(Tool.Transition in toolsFor(SelectionKind.MainVideo, canTransition = true), "a later shot offers no transition")
        // B13: an overlay butted after another on its row transitions in over it.
        check(Tool.Transition in toolsFor(SelectionKind.Overlay, canTransition = true), "an overlay after another offers no transition")
        check(Tool.Transition !in toolsFor(SelectionKind.Overlay, canTransition = false), "a lone overlay offers a transition")
        check(Tool.Transition in toolsFor(SelectionKind.PhotoOverlay, canTransition = true), "a photo after another offers no transition")
        check(Tool.ToOverlay in toolsFor(SelectionKind.MainVideo) && Tool.ToMain !in toolsFor(SelectionKind.MainVideo),
            "a shot does not offer to float")
        check(Tool.ToMain in toolsFor(SelectionKind.Overlay) && Tool.ToOverlay !in toolsFor(SelectionKind.Overlay),
            "an overlay does not offer to drop to the main track")
        check(Tool.Opacity in toolsFor(SelectionKind.Overlay) && Tool.Layer in toolsFor(SelectionKind.Overlay),
            "an overlay has no opacity or layer")
    }

    // --- The sound tools (B9): fades and a voice on a sound, a voice and extraction on footage. ---
    run {
        val sound = toolsFor(SelectionKind.Audio)
        check(Tool.Fade in sound && Tool.Voice in sound, "a sound has no Fade or Voice")
        check(sound.indexOf(Tool.Fade) == sound.indexOf(Tool.Volume) + 1, "Fade is not beside Volume on a sound")
        check(Tool.ExtractAudio !in sound, "a sound offers to extract its own sound")
        for (kind in listOf(SelectionKind.MainVideo, SelectionKind.Overlay)) {
            check(Tool.Voice in toolsFor(kind, true) && Tool.ExtractAudio in toolsFor(kind, true), "$kind has no Voice or Extract audio")
            check(Tool.Fade !in toolsFor(kind, true), "$kind offers Fade, which the sheet only has for sounds")
        }
        // A photo has no sound: nothing to voice, nothing to extract.
        check(Tool.Voice !in toolsFor(SelectionKind.PhotoOverlay) && Tool.ExtractAudio !in toolsFor(SelectionKind.PhotoOverlay),
            "a photo offers sound tools")
        for (kind in listOf(SelectionKind.Text, SelectionKind.Sticker, SelectionKind.Effect)) {
            check(toolsFor(kind).none { it == Tool.Voice || it == Tool.Fade || it == Tool.ExtractAudio }, "$kind offers sound tools")
        }
        check(!Tool.ExtractAudio.sheet, "Extract audio opens a sheet instead of acting")
        check(Tool.Fade.sheet && Tool.Voice.sheet, "Fade or Voice acts at once instead of opening a sheet")
        // Voice from a shot to a sound stays open; Fade from a sound to a shot does not.
        check(sheetSurvives(Tool.Voice, SelectionKind.Audio, false), "Voice closed moving from a shot to a sound")
        check(!sheetSurvives(Tool.Fade, SelectionKind.MainVideo, false), "Fade stayed open over a shot")
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

        // A family is five code points joined by ZWJs: cut through, it left a
        // "man" and a dangling joiner. It goes whole or not at all.
        val family = "👨‍👩‍👧"
        val famName = ProjectName.clean("b".repeat(ProjectName.MAX_LENGTH - 2) + family)!!
        check(famName == "b".repeat(ProjectName.MAX_LENGTH - 2), "a family emoji was cut into: ${famName.takeLast(6).map { it.code }}")
        check(!famName.endsWith("‍"), "a name ends in a joiner")
        val thumbs = "👍🏽" // thumbs up, medium skin tone: four chars
        val thumbName = ProjectName.clean("c".repeat(ProjectName.MAX_LENGTH - 2) + thumbs)!!
        check(thumbName == "c".repeat(ProjectName.MAX_LENGTH - 2), "a skin-toned emoji was split: ${thumbName.length} chars")
        val fits = "d".repeat(ProjectName.MAX_LENGTH - 4) + thumbs
        check(ProjectName.clean(fits) == fits, "an emoji that fits exactly was dropped")

        // The field keeps a long paste, cut back rather than refused.
        val paste = "word ".repeat(80)
        val held = ProjectName.cut(paste, ProjectName.FIELD_LENGTH)
        check(held.length == ProjectName.FIELD_LENGTH && paste.startsWith(held), "a long paste became '${held.length}' chars")
        check(ProjectName.cut("short", ProjectName.FIELD_LENGTH) == "short", "a short value was changed")
    }

    // --- A tool on a clip's toolbar changes that clip, not the edit. --------------------
    run {
        // Volume is each clip's own level now (B8): a shot's, an overlay's, a
        // sound's. The camera sound for every shot at once stays on Sound.
        check(Tool.Volume in toolsFor(SelectionKind.MainVideo, canTransition = true), "a shot has no Volume")
        check(Tool.Volume in toolsFor(SelectionKind.Overlay), "an overlay has no Volume")
        check(Tool.Volume in toolsFor(SelectionKind.Audio), "a sound has no Volume")
        check(toolsFor(SelectionKind.MainVideo).indexOf(Tool.Volume) in 0..3, "Volume is not near the start for a shot")
        check(Tool.Clip.label != Tool.Split.label, "level 0's Edit and Split share a name")
    }

    // --- A photo on an overlay row: only the tools that do something to a picture -
    //     its look and sliders among them, graded on the CPU, since the plan puts a
    //     grade on every clip and Apply to all overlays would otherwise skip it. ------
    run {
        val photo = toolsFor(SelectionKind.PhotoOverlay)
        for (footageOnly in listOf(Tool.Volume, Tool.Speed, Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track)) {
            check(footageOnly !in photo, "a photo overlay offers $footageOnly, which has nothing to act on")
        }
        for (wanted in listOf(Tool.Opacity, Tool.Layer, Tool.Placement, Tool.Animation, Tool.ToMain, Tool.Crop, Tool.Filters, Tool.Adjust)) {
            check(wanted in photo, "a photo overlay has no $wanted")
        }
        check(photo.all { it in toolsFor(SelectionKind.Overlay) }, "a photo overlay offers a tool footage does not")
        check(sheetSurvives(Tool.Opacity, SelectionKind.PhotoOverlay, false), "Opacity closed moving to a photo overlay")
        check(!sheetSurvives(Tool.Volume, SelectionKind.PhotoOverlay, false), "Volume stayed open over a photo")
    }

    // --- The clip operations (B11): on footage, and on what has settings to carry. ------
    run {
        val footage = listOf(Tool.Rotate, Tool.Mirror, Tool.Freeze, Tool.Reverse, Tool.Replace)
        for (kind in listOf(SelectionKind.MainVideo, SelectionKind.Overlay)) {
            val tools = toolsFor(kind, canTransition = true)
            check(footage.all { it in tools }, "$kind is missing one of $footage")
            check(Tool.CopyAttributes in tools && Tool.PasteAttributes in tools, "$kind cannot copy or paste attributes")
            check(tools.indexOf(Tool.CopyAttributes) + 1 == tools.indexOf(Tool.PasteAttributes), "$kind: Paste is not beside Copy")
        }
        // A photo turns and mirrors, and carries settings; it has no footage to freeze, reverse or swap.
        val photo = toolsFor(SelectionKind.PhotoOverlay)
        check(Tool.Rotate in photo && Tool.Mirror in photo && Tool.CopyAttributes in photo, "a photo cannot turn, mirror or copy")
        check(FOOTAGE_TOOLS.none { it in photo }, "a photo offers a footage tool")
        // A sound carries its level, fades, voice and speed; nothing of the picture.
        val sound = toolsFor(SelectionKind.Audio)
        check(Tool.CopyAttributes in sound && Tool.PasteAttributes in sound, "a sound cannot copy or paste attributes")
        check(sound.none { it in FOOTAGE_TOOLS || it == Tool.Rotate || it == Tool.Mirror }, "a sound offers a picture tool")
        for (kind in listOf(SelectionKind.Text, SelectionKind.Sticker, SelectionKind.Effect)) {
            check(toolsFor(kind).none { it in FOOTAGE_TOOLS || it == Tool.CopyAttributes || it == Tool.Rotate }, "$kind offers a clip's footage tool")
        }
        // Everything on the strip can be selected alongside something else; the
        // set then offers what acts on all of it and nothing that acts on one.
        for (kind in SelectionKind.entries.filter { it != SelectionKind.None }) {
            check(Tool.SelectMore in toolsFor(kind, canTransition = true), "$kind has no Select more")
            val several = toolsFor(kind, canTransition = true, multi = true)
            check(several == listOf(Tool.SelectMore, Tool.Delete), "$kind with several selected shows $several")
        }
        check(toolsFor(SelectionKind.None, multi = true) == LEVEL_ZERO, "nothing selected but several: not level 0")
        check(footage.none { it.sheet } && !Tool.CopyAttributes.sheet && !Tool.SelectMore.sheet, "a clip operation opens a sheet instead of acting")
        // Replace's sheet - where in the new file to start - opens on the pick, and
        // stays while a footage clip is selected; a shot's tool closes over a set.
        check(sheetSurvives(Tool.Replace, SelectionKind.MainVideo, false), "the Replace sheet closed over a shot")
        check(!sheetSurvives(Tool.Replace, SelectionKind.Audio, false), "the Replace sheet stayed over a sound")
        check(!sheetSurvives(Tool.Speed, SelectionKind.MainVideo, false, multi = true), "Speed stayed open over several clips")
        check(sheetSurvives(Tool.Sound, SelectionKind.MainVideo, false, multi = true), "the Sound sheet closed over several clips")
        // A photo, blank or freeze rendered into a file on a video track: no
        // frame to freeze, nothing to run backwards, no shake or motion to
        // measure. Everything else a shot has, in the same order.
        for (kind in listOf(SelectionKind.MainVideo, SelectionKind.Overlay)) {
            val stillRow = toolsFor(kind, canTransition = true, footage = false)
            check(NEEDS_MOTION.none { it in stillRow }, "$kind as a still offers a tool that needs motion: $stillRow")
            check(FOOTAGE_TOOLS.all { it in NEEDS_MOTION } && Tool.Stabilize in NEEDS_MOTION && Tool.Track in NEEDS_MOTION, "a still is offered Stabilize or Track")
            check(stillRow == toolsFor(kind, canTransition = true).filterNot { it in NEEDS_MOTION }, "$kind as a still reorders the row")
            check(Tool.Delete in stillRow && Tool.Rotate in stillRow && Tool.Duplicate in stillRow, "$kind as a still lost a tool it has")
        }
        // A caption still follows a subject: Track is about the footage under it.
        check(Tool.Track in toolsFor(SelectionKind.Text) && Tool.Track in toolsFor(SelectionKind.Sticker), "words lost Track")
        check(!sheetSurvives(Tool.Stabilize, SelectionKind.MainVideo, true, footage = false), "Stabilize stayed open over a freeze")
        check(sheetSurvives(Tool.Speed, SelectionKind.MainVideo, true, footage = false), "Speed closed over a freeze")
        // One name per concept: a sticker's mirror and a clip's read the same.
        check(Tool.Flip.label == Tool.Mirror.label, "a sticker mirrors as '${Tool.Flip.label}' and a clip as '${Tool.Mirror.label}'")
    }

    // --- Colour and crop are each clip's own (B12): on every piece of footage, not on sounds or words. ---
    run {
        for (kind in listOf(SelectionKind.MainVideo, SelectionKind.Overlay)) {
            val tools = toolsFor(kind, canTransition = true)
            check(Tool.Filters in tools && Tool.Adjust in tools && Tool.Crop in tools, "$kind has no Filters, Adjust or Crop")
            check(tools.indexOf(Tool.Adjust) == tools.indexOf(Tool.Filters) + 1, "$kind: Adjust is not beside Filters")
            check(tools.indexOf(Tool.Crop) == tools.indexOf(Tool.Adjust) + 1, "$kind: Crop is not beside Adjust")
        }
        for (kind in listOf(SelectionKind.Audio, SelectionKind.Text, SelectionKind.Sticker, SelectionKind.Effect)) {
            check(toolsFor(kind).none { it == Tool.Filters || it == Tool.Adjust || it == Tool.Crop }, "$kind offers a picture's colour or crop")
        }
        check(Tool.Filters.sheet && Tool.Adjust.sheet && Tool.Crop.sheet, "a colour or crop tool acts at once instead of opening a sheet")
        // Filters from a shot to an overlay stays open; to a sound it does not.
        check(sheetSurvives(Tool.Filters, SelectionKind.Overlay, false), "Filters closed moving from a shot to an overlay")
        check(!sheetSurvives(Tool.Adjust, SelectionKind.Audio, false), "Adjust stayed open over a sound")
        // Level 0's Looks and Frame stay: they work on the edit, or the shot under the playhead.
        check(sheetSurvives(Tool.Looks, SelectionKind.MainVideo, false), "Looks closed when a shot was selected")
        check(sheetSurvives(Tool.Frame, SelectionKind.None, false), "Frame closed on deselect")
    }

    println("tool rules: toolbar levels, sheets, back, cut target, project names")
    if (problems.isEmpty()) println("PASS - the toolbar shows what section 2 decided and back unwinds innermost first")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
