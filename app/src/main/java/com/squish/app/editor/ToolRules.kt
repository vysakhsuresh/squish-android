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
    /** A sticker mirrored to face the other way. Acts at once. */
    Flip("Flip", false),
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
 * they act on (docs/ROADMAP.md, B6, "Deferred"): Rotate, Mirror, Freeze,
 * Reverse and Replace (B11). A button for a tool that does not exist yet would
 * be one more thing that does nothing. B9 brought Fade and Voice to sounds,
 * Voice to every clip with sound, and Extract audio to footage; B10 brought
 * Opacity, Speak and Flip to text.
 *
 * A photo kept as a picture is drawn by the preview itself, not a player, so
 * it has no shader to grade it: Crop, which is done to the view, but no
 * Filters or Adjust.
 *
 * Text: Edit first, since the keyboard is what a line is usually selected for;
 * a line has no Placement sheet - it is moved on the picture, and Style has
 * the numbers. A sticker keeps Placement, and Flip instead of the keyboard.
 */
fun toolsFor(kind: SelectionKind, canTransition: Boolean = false): List<Tool> = when (kind) {
    SelectionKind.None -> LEVEL_ZERO
    SelectionKind.MainVideo -> listOfNotNull(
        Tool.Split, Tool.Speed, Tool.Volume, Tool.Animation, Tool.Placement,
        Tool.Transition.takeIf { canTransition },
        Tool.Filters, Tool.Adjust, Tool.Crop,
        Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Voice, Tool.ExtractAudio,
        Tool.Duplicate, Tool.ToOverlay, Tool.Delete
    )
    SelectionKind.Overlay -> listOf(
        Tool.Split, Tool.Speed, Tool.Volume, Tool.Opacity, Tool.Layer, Tool.Animation, Tool.Placement,
        Tool.Filters, Tool.Adjust, Tool.Crop,
        Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Voice, Tool.ExtractAudio,
        Tool.Duplicate, Tool.ToMain, Tool.Delete
    )
    SelectionKind.PhotoOverlay -> listOf(
        Tool.Split, Tool.Opacity, Tool.Layer, Tool.Animation, Tool.Placement, Tool.Crop, Tool.Duplicate, Tool.ToMain, Tool.Delete
    )
    // CapCut's order: the level, then the fades, then everything about time.
    SelectionKind.Audio -> listOf(
        Tool.Split, Tool.Volume, Tool.Fade, Tool.Speed, Tool.Beats, Tool.Sync, Tool.Voice, Tool.Duplicate, Tool.Delete
    )
    SelectionKind.Text -> listOf(
        Tool.Edit, Tool.Style, Tool.Animation, Tool.Opacity, Tool.Speak, Tool.Track, Tool.Split, Tool.Duplicate, Tool.Delete
    )
    SelectionKind.Sticker -> listOf(
        Tool.Placement, Tool.Animation, Tool.Opacity, Tool.Flip, Tool.Track, Tool.Split, Tool.Duplicate, Tool.Delete
    )
    SelectionKind.Effect -> listOf(Tool.Strength, Tool.Split, Tool.Duplicate, Tool.Delete)
}

/**
 * Whether an open sheet stays open when the selection changes under it.
 *
 * A level-0 sheet - Sound, Text - is about the edit, not the selection, so it
 * stays: adding a sticker selects it, and the sticker sheet closing under the
 * finger that is picking the next one would be absurd. A clip's own tool stays
 * while the new selection has that tool too (Speed from one shot to the next),
 * and closes when it does not - Speed has nothing to say about a caption.
 */
fun sheetSurvives(tool: Tool, kind: SelectionKind, canTransition: Boolean): Boolean =
    tool.levelZero || tool in toolsFor(kind, canTransition)

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
