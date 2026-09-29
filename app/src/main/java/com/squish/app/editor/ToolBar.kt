package com.squish.app.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.editor.edits.MIN_EFFECT_MS
import com.squish.app.media.StillClips
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.TimelineState
import com.squish.app.ui.theme.SquishColors

/**
 * One colour and one glyph per concept, everywhere it appears: the toolbar, the
 * track heads beside the strip, a sheet's title, a clip's tint. The editor had
 * amber for both Motion and Words, magenta for Blend, Stickers and Looks, and a
 * different picture of "sound" on the rail, the gutter and the panel heading;
 * this table is the only place any of that is decided now.
 */
enum class Concept(val accent: Color, val icon: ImageVector) {
    Video(SquishColors.Violet, Icons.Filled.Videocam),
    Overlay(SquishColors.Magenta, Icons.Filled.Layers),
    Sound(SquishColors.Cyan, Icons.Filled.MusicNote),
    Text(SquishColors.Amber, Icons.Filled.TextFields),
    Sticker(SquishColors.Amber, Icons.Filled.EmojiEmotions),
    Effects(SquishColors.Blue, Icons.Filled.Bolt),
    Looks(SquishColors.Blue, Icons.Filled.AutoAwesome)
}

/** The concept a selection belongs to, and so the colour its tools wear. */
val SelectionKind.concept: Concept?
    get() = when (this) {
        SelectionKind.None -> null
        SelectionKind.MainVideo -> Concept.Video
        SelectionKind.Overlay, SelectionKind.PhotoOverlay -> Concept.Overlay
        SelectionKind.Audio -> Concept.Sound
        SelectionKind.Text -> Concept.Text
        SelectionKind.Sticker -> Concept.Sticker
        SelectionKind.Effect -> Concept.Effects
    }

/**
 * A tool's glyph. A level-0 tool that adds something wears that thing's glyph -
 * Sound is the sound track's note, Text the text track's letters, Edit (the
 * shot under the playhead) the video track's camera - so the
 * toolbar and the track heads say the same thing.
 */
val Tool.icon: ImageVector
    get() = when (this) {
        Tool.Clip -> Concept.Video.icon
        Tool.Sound -> Concept.Sound.icon
        Tool.Text -> Concept.Text.icon
        Tool.Stickers -> Concept.Sticker.icon
        Tool.Overlay -> Concept.Overlay.icon
        Tool.Effects -> Concept.Effects.icon
        Tool.Looks -> Concept.Looks.icon
        Tool.Frame -> Icons.Filled.Crop
        Tool.Split -> Icons.Filled.ContentCut
        Tool.Speed -> Icons.Filled.Speed
        Tool.Volume -> Icons.AutoMirrored.Filled.VolumeUp
        Tool.Animation -> Icons.Filled.Animation
        Tool.Placement -> Icons.Filled.OpenWith
        Tool.Transition -> TransitionGlyph
        Tool.Opacity -> Icons.Filled.Opacity
        Tool.Layer -> Icons.Filled.Layers
        Tool.Mask -> Icons.Filled.Vignette
        Tool.Cutout -> Icons.Filled.Person
        Tool.Stabilize -> Icons.Filled.Straighten
        Tool.Track -> Icons.Filled.MyLocation
        Tool.Beats -> Icons.Filled.GraphicEq
        Tool.Sync -> Icons.Filled.Sync
        Tool.Fade -> Icons.AutoMirrored.Filled.TrendingUp
        Tool.Voice -> Icons.Filled.RecordVoiceOver
        Tool.ExtractAudio -> Icons.Filled.Audiotrack
        Tool.Edit -> Icons.Filled.Edit
        Tool.Style -> Icons.Filled.Palette
        Tool.Strength -> Icons.Filled.Tune
        Tool.Speak -> Icons.Filled.RecordVoiceOver
        Tool.Flip -> Icons.Filled.Flip
        Tool.Rotate -> Icons.AutoMirrored.Filled.RotateRight
        Tool.Mirror -> Icons.Filled.Flip
        Tool.Freeze -> Icons.Filled.PauseCircleOutline
        Tool.Reverse -> Icons.Filled.FastRewind
        Tool.Replace -> Icons.Filled.SwapHoriz
        Tool.CopyAttributes -> Icons.Filled.ContentPaste
        Tool.PasteAttributes -> Icons.Filled.ContentPasteGo
        Tool.SelectMore -> Icons.Filled.Checklist
        Tool.Duplicate -> Icons.Filled.ContentCopy
        Tool.ToOverlay -> Icons.Filled.FlipToFront
        Tool.ToMain -> Icons.Filled.FlipToBack
        Tool.Delete -> Icons.Filled.DeleteOutline
    }

/** A level-0 tool's own colour: the concept it adds, or the picture's for Cut and Frame. */
val Tool.levelZeroAccent: Color
    get() = when (this) {
        Tool.Sound -> Concept.Sound.accent
        Tool.Text -> Concept.Text.accent
        Tool.Stickers -> Concept.Sticker.accent
        Tool.Overlay -> Concept.Overlay.accent
        Tool.Effects -> Concept.Effects.accent
        Tool.Looks -> Concept.Looks.accent
        else -> Concept.Video.accent
    }

/**
 * The join between two shots: two wedges meeting, filled when a transition is on
 * it. Drawn rather than typed - the strip's join mark was a "|" and, once set, an
 * "✕", which read as a button that removed the transition.
 */
val TransitionGlyph: ImageVector by lazy {
    ImageVector.Builder("Transition", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f); lineTo(12f, 12f); lineTo(4f, 19f); close()
            moveTo(20f, 5f); lineTo(12f, 12f); lineTo(20f, 19f); close()
        }
    }.build()
}

/** What is selected, by kind, for the toolbar. */
val EditorUiState.selectionKind: SelectionKind
    get() {
        val id = selectedClipId ?: return SelectionKind.None
        videoClips.firstOrNull { it.id == id }?.let {
            return when {
                !it.isOverlay -> SelectionKind.MainVideo
                StillClips.isStill(it.uri) -> SelectionKind.PhotoOverlay
                else -> SelectionKind.Overlay
            }
        }
        if (audioClips.any { it.id == id }) return SelectionKind.Audio
        textOverlays.firstOrNull { it.id == id }?.let { return if (it.sticker) SelectionKind.Sticker else SelectionKind.Text }
        if (effects.any { it.id == id }) return SelectionKind.Effect
        return SelectionKind.None
    }

/** Whether the selected main-track shot has a shot before it to transition from. */
val EditorUiState.selectedCanTransition: Boolean
    get() {
        val base = videoClips.filter { it.layer == 0 }.sortedBy { it.timelineStartMs }
        return base.indexOfFirst { it.id == selectedClipId } > 0
    }

/**
 * The toolbar: level 0 with nothing selected, the selection's own tools with
 * something selected, and a back chevron between them. One row, labelled icons,
 * scrolling sideways when it runs past the screen.
 *
 * [enabled] greys a tool that would do nothing now - Split on a sliver too short
 * to cut - rather than hiding it, so the row does not jump about.
 */
@Composable
fun ToolBar(
    tools: List<Tool>,
    accentOf: (Tool) -> Color,
    onTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Tool) -> Boolean = { true },
    /** Given, a back chevron leads the row: the way from a clip's tools to level 0. */
    onBack: (() -> Unit)? = null,
    backAccent: Color = SquishColors.TextSecondary
) {
    // A new row starts at its start: the clip's tools arriving scrolled to where
    // level 0 had been left put Split off-screen.
    val scroll = remember(tools) { androidx.compose.foundation.ScrollState(0) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SquishColors.Surface)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            ToolItem(
                icon = Icons.Filled.ChevronLeft,
                label = "Back",
                tint = backAccent,
                description = "Back to the main tools",
                onClick = onBack
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(36.dp)
                    .background(SquishColors.Border)
            )
        }
        tools.forEach { tool ->
            val on = enabled(tool)
            ToolItem(
                icon = tool.icon,
                label = tool.label,
                tint = if (!on) SquishColors.TextMuted.copy(alpha = 0.5f)
                // Its own colour on every toolbar, overlay's magenta included.
                else if (tool == Tool.Delete) SquishColors.Danger
                else accentOf(tool),
                enabled = on,
                description = if (tool == Tool.Clip) "Edit the shot under the playhead" else tool.label,
                onClick = { onTool(tool) }
            )
        }
    }
}

/**
 * One tool: its glyph over its name. At least a thumb wide, and wider when the
 * name needs it - at a large font a fixed width cut the names off.
 */
@Composable
private fun ToolItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = label
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(), label = "toolScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = TOOL_MIN_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) SquishColors.TextSecondary else SquishColors.TextMuted.copy(alpha = 0.5f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible
        )
    }
}

/** A thumb's width: every tool at least this, so the row reads as one set. */
private val TOOL_MIN_WIDTH = 60.dp

/**
 * Whether Split would change anything now - the same three cases the view
 * model's cut takes, in the same order: a selected caption or effect under the
 * playhead is cut itself, anything else is the picture and sound under it.
 */
val EditorUiState.canSplitHere: Boolean
    get() {
        val selected = selectedClipId
        val at = playheadMs
        textOverlays.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }?.let {
            return EditRules.splitAt(it.startMs, it.endMs, at, MIN_CLIP_MS) != null
        }
        effects.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }?.let {
            return EditRules.splitAt(it.startMs, it.endMs, at, MIN_EFFECT_MS) != null
        }
        return TimelineState(clips = videoClips + audioClips, selectedClipId = selected, playheadMs = at).canSplit()
    }
