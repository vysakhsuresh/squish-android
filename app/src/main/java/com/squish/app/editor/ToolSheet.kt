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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.editor.edits.TextEdits
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
    /** Starts the eyedropper over the picture; the colour picked comes back to the caller given. */
    onEyedropper: ((Int) -> Unit) -> Unit = {},
    /** Gives the eyedropper up: the tab that asked for it has gone. */
    onEyedropperCancel: () -> Unit = {},
    /** Which tab of a tabbed sheet is showing, for the layout around it. */
    onChipChanged: (Int) -> Unit = {},
    /** A line picked from Text's list: selected, with its keyboard up in place of the sheet. */
    onEditLine: (String) -> Unit = {},
    /** A picture wanted for the canvas's background: the picker, whose pick lands through ClipEdits.setCanvasImage. */
    onPickBackgroundImage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val kind = state.selectionKind
    val accent = if (tool.levelZero) tool.levelZeroAccent else kind.concept?.accent ?: SquishColors.Violet
    val clip = (state.videoClips + state.audioClips).firstOrNull { it.id == state.selectedClipId }
    val item = state.textOverlays.firstOrNull { it.id == state.selectedClipId }
    val effect = state.effects.firstOrNull { it.id == state.selectedClipId }
    // Level 0's Looks works on the shot under the playhead (or the picture
    // selected); a clip's own Filters and Adjust on the selection. The shot
    // is chosen when the sheet opens and again on a deliberate move - a
    // scrub, a frame step, a change of selection - not as playback runs on:
    // the subject used to change under an open sheet mid-play, and a Strength
    // drag across a cut wrote half its travel to each of two shots.
    val pinnedId = remember(tool, state.selectedClipId, state.scrubNonce) { viewModel.clips.gradeTarget(state)?.id }
    val graded = if (tool == Tool.Looks) {
        state.videoClips.firstOrNull { it.id == pinnedId } ?: viewModel.clips.gradeTarget(state)
    } else clip?.takeIf { it.kind == ClipKind.Video }
    // A look put on shot 2 while the preview showed shot 1 could not be seen:
    // opening a picture tool on a clip the playhead is off brings the playhead
    // a little way into it, past where a transition would cover it.
    // The same for a shot's speed, animation and crop, each of which is seen, not read.
    val shown = when (tool) {
        Tool.Looks, Tool.Filters, Tool.Adjust -> graded
        Tool.Speed, Tool.Animation, Tool.Crop, Tool.Mask, Tool.Cutout, Tool.Stabilize, Tool.Track,
        Tool.Placement, Tool.Opacity, Tool.Transition -> clip?.takeIf { it.kind == ClipKind.Video }
        else -> null
    }
    LaunchedEffect(tool, shown?.id) {
        val shot = shown ?: return@LaunchedEffect
        if (state.playheadMs !in shot.timelineStartMs until shot.timelineEndMs) {
            // Stopped first: playing on, the clock carried the playhead on
            // through the shot before and the jump never showed.
            viewModel.audio.requestPause()
            // The pause lands first: answered after the jump, it put the playhead
            // back where the clock had got to.
            repeat(2) { withFrameNanos { } }
            viewModel.seekTo(shot.timelineStartMs + minOf(LOOK_LEAD_MS, shot.durationMs / 3))
        }
    }
    // A line's Edit opens on its keyboard, whichever tab the last line was left on.
    var chip by rememberSaveable(tool, if (tool == Tool.Edit) state.selectedClipId else null) { mutableIntStateOf(0) }
    // The new line's sample words are selected the first time its field opens,
    // and only then: the field is rebuilt on every return to the Keyboard tab,
    // and selecting the whole line again there meant the next letter typed
    // replaced everything already said.
    var sampleSelected by rememberSaveable(newLineId) { mutableStateOf(false) }

    val chips = when (tool) {
        // Named by what the chip holds - the mic, and the camera's sound. It
        // was "Voice & FX" from when the voice changer lived there; that is
        // each clip's own Voice now, and the old name sent people to a mic
        // button to look for Robot.
        Tool.Sound -> listOf("Music", "Mic & camera", "Sync")
        Tool.Looks -> listOf("Filters", "Adjust", "Templates")
        // "Rotate all": the whole edit turns here, and the clip toolbar's Rotate
        // turns one clip - the two read as the same tool in two places.
        Tool.Frame -> listOf("Ratio", "Background", "Rotate all")
        Tool.Cutout -> listOf("Background", "Chroma key")
        // As CapCut lays a line out: the keyboard first, then how it looks,
        // what is behind it and how it moves, all in the one sheet over the
        // keyboard so the words can be watched while any of it is changed.
        Tool.Edit -> listOf("Keyboard", "Style", "Bubble", "Animation")
        else -> emptyList()
    }
    // The keyboard goes when a tab that is not for typing is picked, so the tab
    // has the room; the field is a tap away on the Keyboard tab.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(tool, chip) {
        onChipChanged(chip)
        if (tool == Tool.Edit && chip != 0) keyboard?.hide()
        // A pick asked for by the Style tab must not land on the Bubble tab's colour.
        onEyedropperCancel()
    }
    val reset: (() -> Unit)? = when (tool) {
        Tool.Looks -> graded?.let { g ->
            when (chip) {
                0 -> { { viewModel.clips.setLook(g.id, null) } }
                1 -> { { viewModel.clips.resetAdjust(g.id) } }
                else -> null
            }
        }
        Tool.Filters -> graded?.let { g -> { viewModel.clips.setLook(g.id, null) } }
        Tool.Adjust -> graded?.let { g -> { viewModel.clips.resetAdjust(g.id) } }
        Tool.Crop -> clip?.let { c -> { viewModel.clips.resetClipCrop(c.id) } }
        Tool.Frame -> when (chip) {
            0 -> viewModel.clips::resetCrop
            1 -> viewModel.clips::resetCanvasBackground
            else -> viewModel.clips::resetRotation
        }
        Tool.Speed -> clip?.let { c -> { viewModel.clips.clearSpeed(c.id) } }
        // The clip's own level, a sound's or a picture's; the camera's sound for
        // the whole edit is on Sound.
        Tool.Volume -> clip?.let { c ->
            if (c.kind == ClipKind.Audio) { { viewModel.audio.resetAudioClipVolume(c.id) } }
            else { { viewModel.clips.resetClipVolume(c.id) } }
        }
        Tool.Animation -> when {
            item != null -> { { viewModel.text.restyleCaption(item.id, { it.withoutMotion() }) } }
            clip != null -> { { viewModel.clips.clearAnimation(clip.id) } }
            else -> null
        }
        Tool.Placement -> when {
            item != null -> {
                {
                    viewModel.text.restyleCaption(item.id, {
                        it.copy(xFraction = 0.5f, yFraction = 0.5f, sizeSp = TextEdits.STICKER_SIZE_SP, rotationDegrees = 0f, flipped = false)
                    })
                }
            }
            clip != null -> { { viewModel.clips.resetPlacement(clip.id) } }
            else -> null
        }
        Tool.Transition -> clip?.let { c ->
            { viewModel.layers.setTransition(c.id, TransitionType.None, c.transitionIn.durationMs) }
        }
        Tool.Opacity -> when {
            item != null -> { { viewModel.text.setOpacity(item.id, 1f) } }
            clip != null -> { { viewModel.layers.resetOpacity(clip.id) } }
            else -> null
        }
        Tool.Edit -> item?.let { i ->
            when (chip) {
                1 -> { { viewModel.text.restyleCaption(i.id, { it.withDefaultStyle() }) } }
                2 -> { { viewModel.text.restyleCaption(i.id, { it.copy(background = TextBackground.NONE) }) } }
                3 -> { { viewModel.text.restyleCaption(i.id, { it.withoutMotion() }) } }
                else -> null
            }
        }
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
        Tool.Strength -> effect?.let { e -> { viewModel.clips.changeEffect(e.id, discrete = true) { it.copy(intensity = DEFAULT_STRENGTH) } } }
        // Where the sheet opened - the old clip's own in-point - as every sheet's
        // Reset is the value it opened with. It went to the start of the file.
        Tool.Replace -> state.replacing?.let { r -> { viewModel.clips.setReplaceInPoint(r.defaultInMs) } }
        else -> null
    }
    // Replace's Done is the replacement itself: the sheet is the question
    // "start where?", and Done is the answer. Every other sheet's Done just closes.
    val done: () -> Unit = if (tool == Tool.Replace) ({ viewModel.clips.commitReplace(); onDone() }) else onDone

    ToolSheet(
        title = tool.label,
        icon = tool.icon,
        accent = accent,
        onDone = done,
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
            Tool.Text -> TextPanel(state, viewModel, onAddText, onAddTitle, onEditLine)
            Tool.Stickers -> StickersPanel(viewModel)
            Tool.Effects -> EffectsPanel(state, viewModel)
            Tool.Looks -> when (chip) {
                0 -> if (graded != null) FiltersPanel(state, graded, viewModel) else NoPicturePanel()
                1 -> if (graded != null) AdjustPanel(state, graded, viewModel) else NoPicturePanel()
                else -> TemplatesPanel(viewModel)
            }
            Tool.Frame -> when (chip) {
                0 -> RatioPanel(state, viewModel)
                1 -> CanvasPanel(state, viewModel, onPickBackgroundImage, onEyedropper)
                else -> RotatePanel(state, viewModel)
            }
            Tool.Filters -> graded?.let { FiltersPanel(state, it, viewModel) }
            Tool.Adjust -> graded?.let { AdjustPanel(state, it, viewModel) }
            Tool.Crop -> clip?.let { CropPanel(it, rememberClipAspect(state, it), viewModel, accent) }

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
            Tool.Transition -> clip?.let { TransitionPanel(state, it, viewModel) }
            Tool.Opacity -> when {
                item != null -> TextOpacityPanel(item, viewModel)
                clip != null -> OpacityPanel(state, clip, viewModel)
            }
            Tool.Layer -> clip?.let { LayerPanel(state, it, viewModel) }
            Tool.Mask -> clip?.let { MaskPanel(it, viewModel) }
            Tool.Cutout -> clip?.let {
                if (chip == 0) BackgroundPanel(state, viewModel)
                else ChromaKeyPanel(state = state, clip = it, viewModel = viewModel, onEyedropper = onEyedropper)
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
            Tool.Edit -> item?.let {
                when (chip) {
                    0 -> TextEditPanel(it, viewModel, selectAll = it.id == newLineId && !sampleSelected, onOpened = { sampleSelected = true })
                    1 -> TextStylePanel(it, viewModel, onEyedropper)
                    2 -> TextBubblePanel(it, viewModel, onEyedropper)
                    else -> TextAnimationPanel(it, viewModel)
                }
            }
            Tool.Style -> item?.let { TextStylePanel(it, viewModel, onEyedropper) }
            Tool.Strength -> effect?.let { EffectStrengthPanel(it, viewModel) }
            Tool.Replace -> state.replacing?.let { ReplacePanel(it, viewModel) }
            else -> Unit
        }
    }
}

/** An effect's strength when it is added; Strength's Reset goes back to it. */
private const val DEFAULT_STRENGTH = 0.7f

/** How far into a shot the playhead is brought when a look is opened on it. */
private const val LOOK_LEAD_MS = 500L

/**
 * A clip's own picture, width over height, unrotated: what its crop window
 * is drawn on. Read off the clip's file once, whichever track it is on - a
 * main-track shot cut in from a differently shaped file used to be given the
 * edit's shape, and a 1:1 chip on it drew a window that was not square (V11).
 * Until the file has answered, the edit's source shape stands in.
 */
@Composable
internal fun rememberClipAspect(state: EditorUiState, clip: com.squish.app.timeline.Clip): Float {
    val fallback = if (state.sourceWidth > 0 && state.sourceHeight > 0) state.sourceWidth.toFloat() / state.sourceHeight else 16f / 9f
    val context = androidx.compose.ui.platform.LocalContext.current
    val uri = clip.uri ?: state.sourceUri
    var aspect by androidx.compose.runtime.remember(uri) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        aspect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (clip.isStillPicture) com.squish.app.media.StillClips.aspectOf(context, uri)
            else com.squish.app.media.ThumbnailExtractor.probe(context, uri).let { m ->
                // The shape the picture is seen in, rotation tag applied, which is
                // what the player draws and the crop window sits on.
                if (m.displayWidth > 0 && m.displayHeight > 0) m.displayWidth.toFloat() / m.displayHeight else null
            }
        }
    }
    return aspect ?: fallback
}
