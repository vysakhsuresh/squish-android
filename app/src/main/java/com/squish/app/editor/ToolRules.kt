package com.squish.app.editor

/**
 * The editor's toolbar as decisions rather than drawing: which tools show for
 * what is selected, what system back does next, which shot Edit opens, and
 * what a typed project name becomes. Kept free of Compose so it can be executed
 * on the JVM (tools/jvm/ToolRulesChecks.kt); ToolBar.kt draws it.
 */

/**
 * What is selected, as far as the toolbar cares. [PhotoOverlay] is a photo kept
 * as a picture on an overlay row: it has no sound or speed, and the tools that
 * work on footage in the player's shaders (mask, cutout, stabilize, track) have
 * nothing to act on there.
 */
enum class SelectionKind { None, MainVideo, Overlay, PhotoOverlay, Audio, Text, Sticker, Effect }

/**
 * Every tool on either level of the toolbar. [sheet] tools open a sheet over
 * the toolbar; the rest act at once - a tap on Split splits.
 */
enum class Tool(val label: String, val sheet: Boolean) {
    // Level 0: adding things, and the project-wide settings. The first opens the
    // tools of the shot under the playhead. It was called Cut and drawn as
    // scissors, beside a Split that is also scissors and does cut; a scissors
    // button that only selected read as a Split that did nothing.
    Clip("Edit", false),
    Sound("Sound", true),
    Text("Text", true),
    Stickers("Stickers", true),
    Overlay("Overlay", false),
    Effects("Effects", true),
    Looks("Looks", true),
    Frame("Frame", true),

    // Level 1: the selected clip's own tools.
    Split("Split", false),
    Speed("Speed", true),
    Volume("Volume", true),
    Animation("Animation", true),
    Placement("Placement", true),
    Transition("Transition", true),
    /** The clip's own look, its own colour sliders, and the window kept of its picture (B12). */
    Filters("Filters", true),
    Adjust("Adjust", true),
    Crop("Crop", true),
    Opacity("Opacity", true),
    Layer("Layer", true),
    Mask("Mask", true),
    Cutout("Cutout", true),
    Stabilize("Stabilize", true),
    Track("Track", true),
    Beats("Beats", true),
    Sync("Sync", true),
    Fade("Fade", true),
    Voice("Voice", true),
    ExtractAudio("Extract audio", false),
    Edit("Edit", true),
    Style("Style", true),
    Strength("Strength", true),
    /** A line read aloud by the phone into a sound clip at its start. Acts at once. */
    Speak("Read aloud", false),
    /**
     * A sticker mirrored to face the other way. Acts at once. Named as the
     * clip's [Mirror] is, under the same glyph: one concept, one name
     * (section 2, item 8) - it was "Flip" beside a "Mirror" that did the same.
     */
    Flip("Mirror", false),

    // B11: what is done to a clip's footage. Each acts at once; Replace opens
    // the picker, and the sheet with the in-point comes up on the pick.
    /** This clip's picture a quarter turn clockwise, each press. */
    Rotate("Rotate", false),
    /** This clip's picture the other way round, left to right. */
    Mirror("Mirror", false),
    /** The frame under the playhead held for a few seconds, the shot cut round it. */
    Freeze("Freeze", false),
    /** The clip's footage played backwards, rendered in the background; again puts it back. */
    Reverse("Reverse", false),
    /** Another file under the clip, keeping its place, length and everything set on it. */
    Replace("Replace", false),
    /** The clip's settings - sound, speed, placement, cut-out - taken for Paste attributes. */
    CopyAttributes("Copy attributes", false),
    PasteAttributes("Paste attributes", false),
    /** Taps on the strip add to the selection from here on: Delete and a carry then act on all of it. */
    SelectMore("Select more", false),
    Duplicate("Duplicate", false),
    ToOverlay("To overlay", false),
    ToMain("To main", false),
    Delete("Delete", false);

    val levelZero: Boolean get() = this in LEVEL_ZERO
}

/**
 * Nothing selected: the tools that add to the edit, most used first, and the
 * two that change the whole picture. Speed, Motion and Blend were here and
 * quietly worked on "the first video clip" when nothing was selected; they
 * belong to a clip, so they are on the clip's own toolbar now.
 */
val LEVEL_ZERO: List<Tool> = listOf(
    Tool.Clip, Tool.Sound, Tool.Text, Tool.Stickers, Tool.Overlay, Tool.Effects, Tool.Looks, Tool.Frame
)

/**
 * The selected clip's tools. [canTransition] is whether a main-track clip has a
 * shot before it to transition from - the first one does not, and a Transition
 * button that could only say so is clutter.
 *
 * Only tools that act on the selected clip are listed. The frame's ratio and
 * the rotation are still one setting for the whole edit, so they stay on level
 * 0 rather than appear here as if they changed this clip alone; a look, the
 * colour sliders and a crop are each clip's own now (B12), and level 0's Looks
 * works on the shot under the playhead with Apply to all. Volume is the clip's
 * own level; the camera sound for the whole edit stays on Sound (Mic & camera).
 *
 * Section 2's lists are longer; the rest arrive with the batch that builds what
 * they act on (docs/ROADMAP.md, B6, "Deferred"). A button for a tool that
 * does not exist yet would be one more thing that does nothing. B9 brought
 * Fade and Voice to sounds, Voice to every clip with sound, and Extract audio
 * to footage; B10 brought Opacity, Speak and Flip to text; B11 Rotate, Mirror,
 * Freeze, Reverse and Replace to footage, Copy and Paste attributes to every
 * clip with settings to carry, and Select more to everything on the strip;
 * B12 Filters, Adjust and Crop to every piece of footage.
 *
 * A photo kept as a picture is drawn by the preview itself, not a player: its
 * Crop is done to the view and its Filters and Adjust are graded on the CPU
 * (TimelinePreview's StillPictures), the file grading it through its image
 * item. It has no sound, speed or footage for the player's shaders to work
 * on, so the rest stay off it.
 *
 * Text: Edit first, since the keyboard is what a line is usually selected for;
 * a line has no Placement sheet - it is moved on the picture, and Style has
 * the numbers. A sticker keeps Placement, and Flip instead of the keyboard.
 *
 * With several selected ([multi]) the row is what acts on all of them - Select
 * more, to add to or leave the set, and Delete - whatever kinds they are: a
 * shot's Speed on a set that holds a song would do something to one of them
 * and nothing to the rest, and a button that does that is worse than none.
 *
 * Delete is the fifth tool on every row (or the last of a shorter one), where
 * section 2 and CapCut put it: on the first screenful, in the same place
 * whatever is selected. It was last, out of a slip's reach - and, once seven
 * tools had joined a shot's row, three screens of scrolling from Split.
 *
 * A shot with no footage in it - a photo, a blank or a freeze rendered into
 * a file ([footage] false) - is not offered the tools that need frames to
 * differ ([FOOTAGE_TOOLS]): a freeze of a freeze, a still rendered backwards
 * for a minute, a stabilizer measuring no shake.
 */
fun toolsFor(kind: SelectionKind, canTransition: Boolean = false, multi: Boolean = false, footage: Boolean = true): List<Tool> {
    val row: List<Tool> = when {
        kind == SelectionKind.None -> LEVEL_ZERO
        multi -> listOf(Tool.SelectMore, Tool.Delete)
        kind == SelectionKind.MainVideo -> listOfNotNull(
            Tool.Split, Tool.Speed, Tool.Volume, Tool.Animation, Tool.Delete, Tool.Placement,
            Tool.Transition.takeIf { canTransition },
            Tool.Filters, Tool.Adjust, Tool.Crop, Tool.Rotate, Tool.Mirror,
            Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Voice, Tool.ExtractAudio,
            Tool.Freeze, Tool.Reverse, Tool.Replace, Tool.CopyAttributes, Tool.PasteAttributes,
            Tool.Duplicate, Tool.SelectMore, Tool.ToOverlay
        )
        kind == SelectionKind.Overlay -> listOf(
            Tool.Split, Tool.Speed, Tool.Volume, Tool.Opacity, Tool.Delete, Tool.Layer, Tool.Animation, Tool.Placement,
            Tool.Filters, Tool.Adjust, Tool.Crop, Tool.Rotate, Tool.Mirror,
            Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Voice, Tool.ExtractAudio,
            Tool.Freeze, Tool.Reverse, Tool.Replace, Tool.CopyAttributes, Tool.PasteAttributes,
            Tool.Duplicate, Tool.SelectMore, Tool.ToMain
        )
        // A photo has no footage to freeze, reverse or swap for other footage.
        kind == SelectionKind.PhotoOverlay -> listOf(
            Tool.Split, Tool.Opacity, Tool.Layer, Tool.Animation, Tool.Delete, Tool.Placement,
            Tool.Filters, Tool.Adjust, Tool.Crop, Tool.Rotate, Tool.Mirror,
            Tool.CopyAttributes, Tool.PasteAttributes, Tool.Duplicate, Tool.SelectMore, Tool.ToMain
        )
        // CapCut's order: the level, then the fades, then everything about time.
        kind == SelectionKind.Audio -> listOf(
            Tool.Split, Tool.Volume, Tool.Fade, Tool.Speed, Tool.Delete, Tool.Beats, Tool.Sync, Tool.Voice,
            Tool.CopyAttributes, Tool.PasteAttributes, Tool.Duplicate, Tool.SelectMore
        )
        kind == SelectionKind.Text -> listOf(
            Tool.Edit, Tool.Style, Tool.Animation, Tool.Opacity, Tool.Delete, Tool.Speak, Tool.Track, Tool.Split,
            Tool.Duplicate, Tool.SelectMore
        )
        kind == SelectionKind.Sticker -> listOf(
            Tool.Placement, Tool.Animation, Tool.Opacity, Tool.Flip, Tool.Delete, Tool.Track, Tool.Split,
            Tool.Duplicate, Tool.SelectMore
        )
        else -> listOf(Tool.Strength, Tool.Split, Tool.Duplicate, Tool.SelectMore, Tool.Delete)
    }
    return if (footage) row else row.filterNot { it in NEEDS_MOTION }
}

/** Where Delete sits on every clip's row (see [toolsFor]), counted from one. */
const val DELETE_POSITION = 5

/** The tools that act on a clip's footage alone - never on a photo, a sound or words. */
val FOOTAGE_TOOLS: Set<Tool> = setOf(Tool.Freeze, Tool.Reverse, Tool.Replace)

/**
 * The tools that need one frame to differ from the next, so a still rendered
 * into a video file is not offered them: [FOOTAGE_TOOLS], and the two that
 * measure motion. Track is on this list and still on a caption's row - a line
 * of words follows a subject in the footage under it, not in itself.
 */
val NEEDS_MOTION: Set<Tool> = FOOTAGE_TOOLS + Tool.Stabilize + Tool.Track

/**
 * Whether an open sheet stays open when the selection changes under it.
 *
 * A level-0 sheet - Sound, Text - is about the edit, not the selection, so it
 * stays: adding a sticker selects it, and the sticker sheet closing under the
 * finger that is picking the next one would be absurd. A clip's own tool stays
 * while the new selection has that tool too (Speed from one shot to the next),
 * and closes when it does not - Speed has nothing to say about a caption.
 */
fun sheetSurvives(tool: Tool, kind: SelectionKind, canTransition: Boolean, multi: Boolean = false, footage: Boolean = true): Boolean =
    tool.levelZero || tool in toolsFor(kind, canTransition, multi, footage)

/** What system back does next. */
enum class BackStep { ExitFullscreen, CloseSheet, Deselect, Leave }

/**
 * Innermost first: the full-screen player, then the open sheet, then the
 * clip's toolbar, and only then the editor itself. Back used to leave the
 * editor from inside any panel.
 */
fun backStep(fullscreen: Boolean, sheetOpen: Boolean, hasSelection: Boolean): BackStep = when {
    fullscreen -> BackStep.ExitFullscreen
    sheetOpen -> BackStep.CloseSheet
    hasSelection -> BackStep.Deselect
    else -> BackStep.Leave
}

/** One main-track shot, for [cutTarget]: its id and where it sits. */
data class ShotSpan(val id: String, val startMs: Long, val endMs: Long)

/**
 * The shot level 0's Edit opens the clip tools for: the one under the playhead. On a cut
 * that is the incoming shot - the one that starts there - and past the end the
 * last one. Null only when there is no shot at all.
 */
fun cutTarget(shots: List<ShotSpan>, playheadMs: Long): String? {
    if (shots.isEmpty()) return null
    val ordered = shots.sortedBy { it.startMs }
    ordered.lastOrNull { playheadMs >= it.startMs && playheadMs < it.endMs }?.let { return it.id }
    // Over a gap or off either end: the nearest shot, by distance to its nearer edge.
    return ordered.minByOrNull { shot ->
        when {
            playheadMs < shot.startMs -> shot.startMs - playheadMs
            else -> playheadMs - shot.endMs + 1
        }
    }?.id
}

/** A project's name, as typed. */
object ProjectName {
    /** Long enough for any real title; short enough to fit a header and a list row. */
    const val MAX_LENGTH = 60

    /** What the rename field holds at most: room to edit a long paste down, not a paragraph. */
    const val FIELD_LENGTH = MAX_LENGTH * 2

    /**
     * What gets stored: one line, no runs of spaces, at most [MAX_LENGTH]
     * characters and never half an emoji. Blank means "no name", which shows the
     * first clip's name as before.
     */
    fun clean(raw: String): String? {
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return null
        return cut(oneLine, MAX_LENGTH).trimEnd()
    }

    /**
     * [text] cut to at most [max] characters, at the last whole character a
     * person would see. Guarding only a lone high surrogate still split a family
     * or a skin-toned hand - one emoji built of several joined ones - and left a
     * "man" and a dangling joiner at the end of the name.
     */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val graphemes = java.text.BreakIterator.getCharacterInstance()
        graphemes.setText(text)
        val end = graphemes.preceding(max + 1).coerceAtLeast(0)
        return text.substring(0, end)
    }
}
