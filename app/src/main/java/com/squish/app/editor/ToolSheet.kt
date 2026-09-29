package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TransitionType
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors

/**
 * A tool, open: a sheet over the toolbar's place, never over the strip.
 *
 * [name] [Reset] [Done] along the top, chips under that where a tool has more
 * than one side, and the tool itself below, scrolling within the sheet. Every
 * panel used to open by folding the strip away - undo, split and the timecode
 * with it - or, beside the strip, with a sliver of room; the sheet's height is
 * the editor's to give now (see EditorScreen), and the strip stays above it.
 */
@Composable
fun ToolSheet(
    title: String,
    icon: ImageVector,
    accent: Color,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null where there is nothing to put back; otherwise one undo step. */
    onReset: (() -> Unit)? = null,
    chips: List<String> = emptyList(),
    chip: Int = 0,
    onChip: (Int) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(SquishColors.Surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onReset != null) {
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClickLabel = "Reset $title", onClick = onReset)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Text(
                "Done",
                style = MaterialTheme.typography.labelLarge,
                color = SquishColors.Background,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(SquishColors.Primary)
                    .clickable(onClickLabel = "Close $title", onClick = onDone)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        if (chips.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                chips.forEachIndexed { i, label ->
                    SelectableChip(label = label, selected = i == chip, accentColor = accent, onClick = { onChip(i) })
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * What each tool's sheet holds, and what its Reset puts back. Level-0 tools work
 * on the edit; the rest on the selection, which the toolbar has made sure fits
 * the tool - and which, if it has just gone (deleted, undone), shows nothing
 * for the frame before the editor closes the sheet.
 */
@Composable
fun EditorToolSheet(
    tool: Tool,
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDone: () -> Unit,
    onPickAudio: () -> Unit,
    onAddText: () -> Unit,
    /** A title from a preset, opened for typing at once. */
    onAddTitle: (TitlePreset) -> Unit,
    /** The line Add text or a title just made, opened with its words selected; see TextEditPanel. */
    newLineId: String?,
    /** A sound picked from Sound's list: selected, with its own tools in place of the sheet. */
    onSelectSound: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = state.selectionKind
    val accent = if (tool.levelZero) tool.levelZeroAccent else kind.concept?.accent ?: SquishColors.Violet
    val clip = (state.videoClips + state.audioClips).firstOrNull { it.id == state.selectedClipId }
    val item = state.textOverlays.firstOrNull { it.id == state.selectedClipId }
    val effect = state.effects.firstOrNull { it.id == state.selectedClipId }
    var chip by rememberSaveable(tool) { mutableIntStateOf(0) }

    val chips = when (tool) {
        // Named by what the chip holds - the mic, and the camera's sound. It
        // was "Voice & FX" from when the voice changer lived there; that is
        // each clip's own Voice now, and the old name sent people to a mic
        // button to look for Robot.
        Tool.Sound -> listOf("Music", "Mic & camera", "Sync")
        Tool.Looks -> listOf("Filters", "Adjust", "Templates")
        Tool.Frame -> listOf("Ratio", "Rotate")
        Tool.Cutout -> listOf("Background", "Chroma key")
        else -> emptyList()
    }
    val reset: (() -> Unit)? = when (tool) {
        Tool.Looks -> when (chip) {
            0 -> { { viewModel.clips.setLook(null) } }
            1 -> viewModel.clips::resetAdjust
            else -> null
        }
        Tool.Frame -> if (chip == 0) viewModel.clips::resetCrop else viewModel.clips::resetRotation
        Tool.Speed -> clip?.let { c -> { viewModel.clips.clearSpeed(c.id) } }
        // The clip's own level, a sound's or a picture's; the camera's sound for
        // the whole edit is on Sound.
        Tool.Volume -> clip?.let { c ->
            if (c.kind == ClipKind.Audio) { { viewModel.audio.setAudioClipVolume(c.id, 1f) } }
            else { { viewModel.clips.resetClipVolume(c.id) } }
        }
        Tool.Animation -> when {
            item != null -> { { viewModel.text.restyleCaption(item.id, { it.copy(motion = TextMotion.None) }) } }
            clip != null -> { { viewModel.clips.clearKeyframes(clip.id) } }
            else -> null
        }
        Tool.Placement -> when {
            item != null -> {
                { viewModel.text.restyleCaption(item.id, { it.copy(xFraction = 0.5f, yFraction = 0.5f, sizeSp = STICKER_SIZE_SP) }) }
            }
            clip != null -> { { viewModel.clips.resetPlacement(clip.id) } }
            else -> null
        }
        Tool.Transition -> clip?.let { c ->
            { viewModel.layers.setTransition(c.id, TransitionType.None, c.transitionIn.durationMs) }
        }
        Tool.Opacity -> clip?.let { c -> { viewModel.layers.setOpacity(c.id, 1f) } }
        Tool.Mask -> clip?.let { c -> { viewModel.layers.setMask(c.id, null) } }
        Tool.Cutout -> clip?.let { c ->
            if (chip == 0) { { viewModel.analysis.setBackground(c.id, null) } }
            else { { viewModel.layers.setChromaKey(c.id, null) } }
        }
        Tool.Stabilize -> clip?.let { c -> { viewModel.analysis.clearStabilization(c.id) } }
        // What Track put on this selection comes off, as an undo step. The
        // measurement itself is not an edit and is kept: Reset here used to
        // throw away whichever clip's track had been measured last - tens of
        // seconds of analysis - with nothing to undo.
        Tool.Track -> when {
            item != null -> item.takeIf { it.track != null }?.let { i -> { viewModel.analysis.unpinCaption(i.id) } }
            clip != null -> clip.takeIf { it.mask?.track != null }?.let { c -> { viewModel.analysis.unpinMask(c.id) } }
            else -> null
        }
        Tool.Beats -> viewModel.audio::clearBeats
        Tool.Sync -> clip?.let { c -> { viewModel.audio.resetAudioAlignment(c.id) } }
        Tool.Fade -> clip?.let { c -> { viewModel.audio.clearFades(c.id) } }
        Tool.Voice -> clip?.let { c -> { viewModel.audio.setClipVoice(c.id, com.squish.app.timeline.VoiceEffect.None) } }
        Tool.Style -> item?.let { i -> { viewModel.text.restyleCaption(i.id, { it.withDefaultStyle() }) } }
        Tool.Strength -> effect?.let { e -> { viewModel.clips.changeEffect(e.id) { it.copy(intensity = DEFAULT_STRENGTH) } } }
        else -> null
    }

    ToolSheet(
        title = tool.label,
        icon = tool.icon,
        accent = accent,
        onDone = onDone,
        onReset = reset,
        chips = chips,
        chip = chip,
        onChip = { chip = it },
        modifier = modifier
    ) {
        when (tool) {
            Tool.Sound -> when (chip) {
                0 -> SoundMusicPanel(state, viewModel, onPickAudio, onSelectSound)
                1 -> SoundVoicePanel(state, viewModel)
                else -> SoundSyncPanel(state, viewModel)
            }
            Tool.Text -> TextPanel(state, viewModel, onAddText, onAddTitle)
            Tool.Stickers -> StickersPanel(viewModel)
            Tool.Effects -> EffectsPanel(state, viewModel)
            Tool.Looks -> when (chip) {
                0 -> FiltersPanel(state, viewModel)
                1 -> AdjustPanel(state, viewModel)
                else -> TemplatesPanel(viewModel)
            }
            Tool.Frame -> if (chip == 0) RatioPanel(state, viewModel) else RotatePanel(state, viewModel)

            Tool.Speed -> if (clip != null) SpeedPanel(state, viewModel, accent)
            Tool.Volume -> clip?.let {
                if (it.kind == ClipKind.Audio) SoundVolumePanel(state, it, viewModel) else ClipVolumePanel(state, it, viewModel)
            }
            Tool.Animation -> when {
                item != null -> TextAnimationPanel(item, viewModel)
                clip != null -> AnimationPanel(state, clip, viewModel, accent)
            }
            Tool.Placement -> when {
                item != null -> StickerPlacementPanel(item, viewModel)
                clip != null -> PlacementPanel(state, clip, viewModel, accent)
            }
            Tool.Transition -> clip?.let { TransitionPanel(it, viewModel) }
            Tool.Opacity -> clip?.let { OpacityPanel(it, viewModel) }
            Tool.Layer -> clip?.let { LayerPanel(state, it, viewModel) }
            Tool.Mask -> clip?.let { MaskPanel(it, viewModel) }
            Tool.Cutout -> clip?.let {
                if (chip == 0) BackgroundPanel(state, viewModel)
                else ChromaKeyPanel(clip = it, playheadMs = state.playheadMs, viewModel = viewModel)
            }
            Tool.Stabilize -> clip?.let { StabilizePanel(state, it, viewModel, accent) }
            Tool.Track -> {
                // A caption rides a track measured on the picture under it.
                val tracked = clip ?: state.baseClipAt(state.playheadMs) ?: state.headVideoClip
                tracked?.let { TrackPanel(state, it, viewModel, accent) }
            }
            Tool.Beats -> BeatPanel(state, viewModel, showClear = false)
            Tool.Sync -> clip?.let { AlignPanel(state, it, viewModel) }
            Tool.Fade -> clip?.let { FadePanel(it, viewModel) }
            Tool.Voice -> clip?.let { VoicePanel(it, viewModel) }
            Tool.Edit -> item?.let { TextEditPanel(it, viewModel, selectAll = it.id == newLineId) }
            Tool.Style -> item?.let { TextStylePanel(it, viewModel) }
            Tool.Strength -> effect?.let { EffectStrengthPanel(it, viewModel) }
            else -> Unit
        }
    }
}

/** The size a sticker lands at, which Placement's Reset goes back to. */
private const val STICKER_SIZE_SP = 64

/** An effect's strength when it is added; Strength's Reset goes back to it. */
private const val DEFAULT_STRENGTH = 0.7f
