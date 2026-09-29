package com.squish.app.editor

/**
 * The editor's toolbar as decisions rather than drawing: which tools show for
 * what is selected, what system back does next, which clip "Cut" means, and
 * what a typed project name becomes. Kept free of Compose so it can be executed
 * on the JVM (tools/jvm/ToolRulesChecks.kt); ToolBar.kt draws it.
 */

/** What is selected, as far as the toolbar cares. */
enum class SelectionKind { None, MainVideo, Overlay, Audio, Text, Sticker, Effect }

/**
 * Every tool on either level of the toolbar. [sheet] tools open a sheet over
 * the toolbar; the rest act at once - a tap on Split splits.
 */
enum class Tool(val label: String, val sheet: Boolean) {
    // Level 0: adding things, and the project-wide settings.
    Cut("Cut", false),
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
    Opacity("Opacity", true),
    Layer("Layer", true),
    Mask("Mask", true),
    Cutout("Cutout", true),
    Stabilize("Stabilize", true),
    Track("Track", true),
    Beats("Beats", true),
    Sync("Sync", true),
    Edit("Edit", true),
    Style("Style", true),
    Strength("Strength", true),
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
    Tool.Cut, Tool.Sound, Tool.Text, Tool.Stickers, Tool.Overlay, Tool.Effects, Tool.Looks, Tool.Frame
)

/**
 * The selected clip's tools. [canTransition] is whether a main-track clip has a
 * shot before it to transition from - the first one does not, and a Transition
 * button that could only say so is clutter.
 *
 * Only tools that act on the selected clip are listed. Looks, crop and rotation
 * are still one setting for the whole edit, so they stay on level 0 rather than
 * appear here as if they changed this clip alone.
 */
fun toolsFor(kind: SelectionKind, canTransition: Boolean = false): List<Tool> = when (kind) {
    SelectionKind.None -> LEVEL_ZERO
    SelectionKind.MainVideo -> listOfNotNull(
        Tool.Split, Tool.Speed, Tool.Volume, Tool.Animation, Tool.Placement,
        Tool.Transition.takeIf { canTransition },
        Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Duplicate, Tool.ToOverlay, Tool.Delete
    )
    SelectionKind.Overlay -> listOf(
        Tool.Split, Tool.Speed, Tool.Opacity, Tool.Layer, Tool.Animation, Tool.Placement,
        Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track, Tool.Duplicate, Tool.ToMain, Tool.Delete
    )
    SelectionKind.Audio -> listOf(
        Tool.Split, Tool.Volume, Tool.Speed, Tool.Beats, Tool.Sync, Tool.Duplicate, Tool.Delete
    )
    SelectionKind.Text -> listOf(
        Tool.Edit, Tool.Style, Tool.Animation, Tool.Track, Tool.Split, Tool.Duplicate, Tool.Delete
    )
    SelectionKind.Sticker -> listOf(
        Tool.Placement, Tool.Animation, Tool.Track, Tool.Split, Tool.Duplicate, Tool.Delete
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
 * The shot "Cut" opens the clip tools for: the one under the playhead. On a cut
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

    /**
     * What gets stored: one line, no runs of spaces, at most [MAX_LENGTH]
     * characters and never half an emoji. Blank means "no name", which shows the
     * first clip's name as before.
     */
    fun clean(raw: String): String? {
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return null
        if (oneLine.length <= MAX_LENGTH) return oneLine
        var cut = oneLine.take(MAX_LENGTH)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trimEnd()
    }
}
