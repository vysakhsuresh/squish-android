package com.squish.app.editor

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipAnimation
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.TransformLimits
import com.squish.app.timeline.hasKeyNear
import com.squish.app.timeline.within
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors
import kotlin.math.roundToInt

/**
 * Animation: how a clip's picture arrives, leaves and behaves in between - a
 * chip each with its length, the way a line of words is animated (TextSheet),
 * so the two sheets read the same - and under them the moves across the whole
 * clip: a preset in one tap, and the keys it lays, which can be moved between,
 * eased or taken off.
 *
 * The arrival, leaving and loop are laid over the keys rather than written
 * into them (ClipAnimation): a push-in drawn across the shot survives a fade
 * in being switched on, changed and taken off again.
 *
 * The clip's own tool now, on its toolbar. It used to be the Motion tab, which
 * acted on "the first video clip" whenever the selection was anything else, and
 * led with Stabilize and Track - analysis, not motion.
 */
@Composable
fun AnimationPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel, accent: Color) {
    val animated = clip.keyframes.isNotEmpty()
    // A trim or a cut keeps the keys over footage the clip no longer shows, so
    // the move comes back with the footage; they are not this clip's to edit.
    val keys = clip.keyframes.within(clip.durationMs)
    val hidden = clip.keyframes.size - keys.size
    val seconds = { ms: Float -> "%.1f s".format(ms / 1000f) }
    val motion = ClipAnimation.MIN_MOTION_MS.toFloat()..ClipAnimation.MAX_MOTION_MS.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = accent) {
            PanelHeading(
                state.clipTitle(clip),
                when {
                    clip.hasAnimation && animated -> "Arrives, leaves and moves while it plays"
                    clip.hasAnimation -> "Arrives and leaves as set below"
                    animated -> "${keys.size} keys · moves while it plays" + if (hidden > 0) " · $hidden more in trimmed footage" else ""
                    else -> "Sitting still — pick an arrival, or a move below"
                },
                icon = Icons.Filled.Animation,
                accent = accent
            )
            AnimationChips("In", ClipArrival.entries, clip.arrival, { it.label }, accent) {
                viewModel.clips.setArrival(clip.id, it)
                if (it != ClipArrival.None) viewModel.showMoment(clip.timelineStartMs, minOf(clip.arrivalMs, clip.durationMs / 2) + SHOW_AFTER_MS)
            }
            if (clip.arrival != ClipArrival.None) {
                LabeledSlider("In takes", clip.arrivalMs.toFloat(), motion, readout = seconds, onFinished = viewModel::endGesture) {
                    viewModel.clips.setArrivalMs(clip.id, it.roundToInt().toLong())
                }
            }
            AnimationChips("Out", ClipLeaving.entries, clip.leaving, { it.label }, accent) {
                viewModel.clips.setLeaving(clip.id, it)
                // Stopped a frame short of the end, on the last of this shot rather than the next one.
                val out = minOf(clip.leavingMs, clip.durationMs / 2)
                val from = (clip.timelineEndMs - out - SHOW_AFTER_MS).coerceAtLeast(clip.timelineStartMs)
                if (it != ClipLeaving.None) viewModel.showMoment(from, (clip.timelineEndMs - 40L - from).coerceAtLeast(0L))
            }
            if (clip.leaving != ClipLeaving.None) {
                LabeledSlider("Out takes", clip.leavingMs.toFloat(), motion, readout = seconds, onFinished = viewModel::endGesture) {
                    viewModel.clips.setLeavingMs(clip.id, it.roundToInt().toLong())
                }
            }
            AnimationChips("Loop", ClipLoop.entries, clip.loop, { it.label }, accent) {
                viewModel.clips.setLoop(clip.id, it)
                if (it != ClipLoop.None) viewModel.showMoment(clip.timelineStartMs, minOf(clip.loopMs * 2, clip.durationMs - 40L))
            }
            if (clip.loop != ClipLoop.None) {
                LabeledSlider(
                    "Every", clip.loopMs.toFloat(),
                    ClipAnimation.MIN_LOOP_MS.toFloat()..ClipAnimation.MAX_LOOP_MS.toFloat(),
                    readout = seconds, onFinished = viewModel::endGesture
                ) {
                    viewModel.clips.setLoopMs(clip.id, it.roundToInt().toLong())
                }
            }
            if (clip.hasAnimation && clip.durationMs < (clip.arrivalMs + clip.leavingMs)) {
                Text(
                    "On a clip this short the arrival and the leaving each take at most half of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            }
        }

        PanelSurface(accent = accent) {
            PanelHeading(
                "Moves",
                "Across the whole clip, over the arrival and leaving",
                icon = Icons.Filled.Transform,
                accent = accent
            )
            // Read back off the keys, since a move is not stored by name: the
            // chip lights while the two keys are the ones it laid, and goes
            // out once one is moved or a third is added. None used to light.
            val active = PolishRules.activeMotionPreset(clip.keyframes, clip.durationMs)
            MotionPreset.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { preset ->
                        SelectableChip(
                            label = preset.label,
                            selected = preset == active,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.clips.applyMotionPreset(clip.id, preset) }
                        )
                    }
                }
            }
            Text(
                PolishRules.motionHint(active),
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            // A slideshow in one tap: every still photo on the track given its own slow move.
            // The note lives outside the button's condition: once every photo
            // moves the button goes, and it used to take its note with it.
            var photosNote by remember { mutableStateOf<String?>(null) }
            if (state.videoClips.count { it.isMain && com.squish.app.timeline.isRenderedPhoto(it.uri?.toString()) && it.keyframes.isEmpty() } > 1) {
                com.squish.app.ui.components.SquishOutlinedButton(
                    text = "Animate every photo",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val n = viewModel.clips.animateAllPhotos()
                        photosNote = "$n photos given a slow move each, in turn. Undo takes it back."
                    }
                )
            }
            photosNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Teal) }
        }

        PanelSurface(accent = accent) {
            PanelHeading(
                "Keyframes",
                "Control points along the clip",
                icon = Icons.Filled.Timer,
                accent = accent,
                trailing = {
                    if (animated) {
                        com.squish.app.ui.components.TextAction("Clear", color = SquishColors.Pink) { viewModel.clips.clearKeyframes(clip.id) }
                    }
                }
            )

            SquishOutlinedButton(
                text = "Add key at playhead",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.clips.addKeyframeAtPlayhead(clip.id) }
            )

            if (!animated) {
                Text(
                    "With one key the clip holds that position. Two or more and it moves between them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            } else {
                keys.forEach { key ->
                    KeyRow(
                        clip = clip,
                        key = key,
                        atPlayhead = isUnderPlayhead(clip, key, state),
                        accent = accent,
                        onGoTo = { viewModel.scrubTo(clip.timelineStartMs + key.atMs) },
                        onEasing = { viewModel.clips.setKeyframeEasing(clip.id, key.atMs, it) },
                        onRemove = { viewModel.clips.removeKeyframe(clip.id, key.atMs) }
                    )
                }
            }
        }
    }
}

/** A labelled row of chips that scrolls sideways: nine arrivals do not fit a phone. */
@Composable
private fun <T> AnimationChips(
    label: String,
    options: List<T>,
    chosen: T,
    text: (T) -> String,
    accent: Color,
    onPick: (T) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary,
            modifier = Modifier.padding(end = 10.dp)
        )
        SideScrollChips(options, { it == chosen }, Modifier.weight(1f)) { option ->
            SelectableChip(
                label = text(option),
                selected = option == chosen,
                accentColor = accent,
                onClick = { onPick(option) }
            )
        }
    }
}

/**
 * The keyframe button every sheet whose number can be keyed carries: a
 * diamond, filled while a key sits under the playhead, that adds one there or
 * takes it off; beside it how many keys the clip has and a way to clear them.
 * The sheet's slider then sets the number at the playhead (ValueTracks) - one
 * control, one meaning, whether the clip is keyed or not.
 */
@Composable
fun KeyframeButton(
    /** A key sits under the playhead. */
    keyed: Boolean,
    /** How many keys the clip has on this number. */
    count: Int,
    /** The playhead is on the clip; off it there is no moment to key. */
    onClip: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (keyed) accent.copy(alpha = 0.14f) else SquishColors.Background)
            .border(1.dp, if (keyed) accent else SquishColors.Border, RoundedCornerShape(10.dp))
            .clickable(enabled = onClip, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            if (keyed) KeyframeGlyph else KeyframeOutlineGlyph,
            contentDescription = null,
            tint = if (onClip) accent else SquishColors.TextMuted,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when {
                    !onClip -> "Move the playhead onto the clip to key it"
                    keyed -> "Key here · tap to remove"
                    count > 0 -> "Add a key here"
                    else -> "Add a key here to animate this"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (onClip) SquishColors.TextPrimary else SquishColors.TextMuted
            )
            if (count > 0) {
                Text(
                    if (count == 1) "1 key · the slider sets the value at the playhead" else "$count keys · the slider sets the value at the playhead",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }
        if (count > 0) {
            // A target the size of a finger, and its own: the row round it
            // toggles a key, so a miss here used to add one instead of clearing them.
            com.squish.app.ui.components.TextAction("Clear", color = SquishColors.Pink, onClick = onClear)
        }
    }
}

/**
 * Where a clip's picture sits. The sliders always edit *the moment the playhead
 * is on*: on a still clip that is simply its placement, on an animated one it
 * becomes a key there. One control, one meaning, whichever mode the clip is in.
 *
 * One sheet for shots and overlays alike. An overlay's size and position were
 * also on the Blend panel, with other ranges and another label, and the two
 * fought over the same fields; they are here only now.
 */
@Composable
fun PlacementPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel, accent: Color) {
    // What the editor set, not what is drawn: the drawn transform includes the
    // stabilizer, and showing it here showed a 108% "scale" nobody chose - which
    // the next nudge then wrote back, doubling the stabilizer's zoom. With the
    // playhead off the clip it reads the pose at the nearer end, which is what
    // an edit then moves (see withOverlayGeometry) - read and written by the
    // same rule, for every slider.
    val here = clip.placementAt(state.playheadMs)
    val animated = clip.keyframes.isNotEmpty()
    val offsets = -TransformLimits.OFFSET_MAX..TransformLimits.OFFSET_MAX
    // A shot is straightened a few degrees at a time; an overlay can be turned right round.
    val turn = if (clip.isOverlay) -TransformLimits.ROTATION_MAX..TransformLimits.ROTATION_MAX else -45f..45f

    val onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs
    val local = state.playheadMs - clip.timelineStartMs
    val keyedHere = clip.keyframes.hasKeyNear(local, state.frameMs)

    PanelSurface(accent = accent) {
        PanelHeading(
            if (animated && onClip) "At ${Timecode.format(state.playheadMs)}" else "Placement",
            when {
                !animated -> "Where the picture sits"
                onClip -> "Changing these sets a key at the playhead"
                else -> "The playhead is off this clip, so these move the whole animation"
            },
            icon = Icons.Filled.Transform,
            accent = accent
        )
        KeyframeButton(
            keyed = keyedHere,
            count = clip.keyframes.size,
            onClip = onClip,
            accent = accent,
            onToggle = {
                if (keyedHere) viewModel.clips.removeKeyframe(clip.id, nearestKeyMs(clip, local, state.frameMs))
                else viewModel.clips.addKeyframeAtPlayhead(clip.id)
            },
            onClear = { viewModel.clips.clearKeyframes(clip.id) }
        )
        LabeledSlider(
            "Scale", here.scale, TransformLimits.SCALE_MIN..TransformLimits.SCALE_MAX,
            readout = Readout.times, onFinished = viewModel::endGesture
        ) {
            viewModel.clips.setClipTransform(clip.id, scale = it)
        }
        LabeledSlider("Across", here.offsetXFraction, offsets, onFinished = viewModel::endGesture) {
            viewModel.clips.setClipTransform(clip.id, offsetX = it)
        }
        LabeledSlider("Up / down", here.offsetYFraction, offsets, onFinished = viewModel::endGesture) {
            viewModel.clips.setClipTransform(clip.id, offsetY = it)
        }
        LabeledSlider(
            "Rotation", here.rotationDegrees.coerceIn(turn.start, turn.endInclusive), turn,
            readout = Readout.degrees,
            onFinished = viewModel::endGesture
        ) {
            viewModel.clips.setClipTransform(clip.id, rotation = it)
        }
        // A 2x2 collage: this picture whole in one quarter of the frame. An
        // overlay is laid out in the frame; a shot on the canvas the frame is
        // cut from, fitted at its own shape (see GridTile.placement).
        val fileAspect = rememberClipAspect(state, clip)
        val seenAspect = fileAspect.let { a ->
            val turned = if (clip.quarterTurns % 2 != 0) 1f / a else a
            val cut = clip.crop?.rect?.let { r -> if (r.width > 0f && r.height > 0f) turned * r.width / r.height else turned } ?: turned
            if (state.quarterTurned) 1f / cut else cut
        }
        val window = state.effectiveCrop
        Text("Grid - put this picture in a quarter", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            com.squish.app.timeline.GridTile.entries.forEach { tile ->
                val at = if (clip.isOverlay) tile.placement()
                else tile.placement(state.canvasAspect, seenAspect, window.left, window.top, window.width, window.height)
                SelectableChip(
                    label = tile.label,
                    // Lit only when a tap would change nothing: a turn or a mask added
                    // since, which the tap clears, leaves it dark.
                    selected = !animated && here.rotationDegrees == 0f && clip.mask == null &&
                        at.matches(here.scale, here.offsetXFraction, here.offsetYFraction),
                    accentColor = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.layers.gridTile(clip.id, at) }
                )
            }
        }
        // Split screen in one tap: this overlay one half, the shot under it the other.
        if (clip.isOverlay) {
            Text("Split screen - this clip fills", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Laid out in the frame the file keeps, as every overlay is, and
                // unturned and uncropped, so it is the file's own shape.
                val frame = state.croppedFrame
                val frameAspect = if (frame.width > 0 && frame.height > 0) frame.width.toFloat() / frame.height else fileAspect
                com.squish.app.timeline.SplitSide.entries.forEach { side ->
                    val at = side.layout(frameAspect, fileAspect)
                    SelectableChip(
                        label = side.label,
                        selected = at.matches(clip),
                        accentColor = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.layers.splitScreen(clip.id, at) }
                    )
                }
            }
        }
    }
}

/** The key within a frame of [localMs], for the button that removes it. */
private fun nearestKeyMs(clip: Clip, localMs: Long, frameMs: Long): Long =
    clip.keyframes.filter { kotlin.math.abs(it.atMs - localMs) <= frameMs }.minByOrNull { kotlin.math.abs(it.atMs - localMs) }?.atMs ?: localMs

private fun isUnderPlayhead(clip: Clip, key: Keyframe, state: EditorUiState): Boolean {
    val at = state.playheadMs - clip.timelineStartMs
    return kotlin.math.abs(at - key.atMs) <= state.frameMs
}

@Composable
private fun KeyRow(
    clip: Clip,
    key: Keyframe,
    atPlayhead: Boolean,
    accent: Color,
    onGoTo: () -> Unit,
    onEasing: (KeyframeEasing) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (atPlayhead) SquishColors.SurfaceElevated else SquishColors.Background)
            .border(
                width = 1.dp,
                color = if (atPlayhead) accent else SquishColors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).clickable(onClick = onGoTo)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        KeyframeGlyph,
                        contentDescription = null,
                        tint = if (atPlayhead) accent else SquishColors.TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        Timecode.format(key.atMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (atPlayhead) accent else SquishColors.TextPrimary
                    )
                }
                // The size as the Placement slider reads it, so the two agree.
                Text(
                    Readout.times(key.transform.scale) +
                        if (key.transform.rotationDegrees != 0f) " · ${key.transform.rotationDegrees.toInt()}°" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            com.squish.app.ui.components.TextAction("Remove", color = SquishColors.Pink, onClick = onRemove)
        }

        // Easing on the last key would describe a segment that does not exist.
        if (key.atMs != clip.keyframes.last().atMs) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                KeyframeEasing.entries.forEach { easing ->
                    SelectableChip(
                        label = easing.label,
                        selected = key.easing == easing,
                        modifier = Modifier.weight(1f),
                        onClick = { onEasing(easing) }
                    )
                }
            }
        }
    }
}

/**
 * Stabilization. Analysis rather than an effect: it measures the shake and writes a
 * correction into the same transform track everything else already uses, which is
 * why it shows up live in the preview the moment it finishes. The measurement is
 * kept on the clip, so Strength solves the correction again from it in a moment
 * (StabilizerSolve) rather than reading the footage again.
 */
@Composable
fun StabilizePanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel, accent: Color) {
    // Only this clip's analysis. The card used to report whichever clip was
    // measured last - "Zoomed in 8%", or its failure - on every clip selected
    // after it, beside a button that said this one was not stabilized.
    val status = state.stabilize.takeIf { it.clipId == clip.id } ?: StabilizeProgress()
    val busyElsewhere = state.stabilize.running && state.stabilize.clipId != clip.id
    val measured = clip.stabilizerMeasurement != null

    PanelSurface(accent = accent) {
        PanelHeading(
            "Stabilize",
            if (clip.isStabilized) "Shake removed · ${clip.stabilizer.size} measurements"
            else "Smooth out handheld shake",
            icon = Icons.Filled.Straighten,
            accent = accent,
            trailing = {
                if (clip.isStabilized) {
                    com.squish.app.ui.components.TextAction("Remove", color = SquishColors.Pink) { viewModel.analysis.clearStabilization(clip.id) }
                }
            }
        )

        when {
            status.running -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = SquishColors.Cyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        if (status.total > 0) "Measuring — frame ${status.done} of ${status.total}"
                        else "Reading the footage",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextSecondary
                    )
                }
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.done.toFloat() / status.total },
                        color = SquishColors.Cyan,
                        trackColor = SquishColors.Border,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            status.finished && status.failed -> Text(
                "Could not read enough frames to measure the shake. Very short clips and some " +
                    "formats do not expose individual frames for analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )

            // Only while the clip still carries the correction: undo takes the
            // keyframes away and leaves this report, which then described a zoom
            // the clip no longer has.
            status.finished && clip.isStabilized -> Text(
                "Measured ${status.framesAnalysed} frames. Zoomed in " +
                    "${(status.crop * 100).toInt()}% to hide the edges the correction exposes.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Teal
            )

            else -> Text(
                "Squish measures how the camera actually moved, smooths that path, and pushes each " +
                    "frame back onto it. A deliberate pan survives — only the jitter is taken out.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        // This clip's own strength; the edit's default until it has one.
        LabeledSlider(
            "Strength", viewModel.analysis.strengthFor(clip, state), 0f..1f,
            onFinished = viewModel::endGesture,
            onChange = { viewModel.analysis.setStabilizeStrength(clip.id, it) }
        )
        Text(
            when {
                measured && clip.isStabilized -> "Stronger holds the frame steadier and crops in further to afford it. " +
                    "This clip is solved again from its measurement as the slider moves; other clips keep theirs."
                clip.isStabilized -> "Stronger holds the frame steadier and crops in further to afford it. " +
                    "This clip was measured before measurements were kept: measure again for the slider to act on it."
                else -> "Stronger holds the frame steadier and crops in further to afford it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )

        SquishOutlinedButton(
            text = when {
                // The count on the button, where the eye already is: the line at the
                // top of the card has scrolled away by the time this is pressed, and
                // a bare "Measuring…" for forty seconds reads as frozen.
                status.running && status.total > 0 ->
                    "Measuring… ${(status.done * 100 / status.total).coerceIn(0, 99)}%"
                status.running -> "Measuring…"
                // One measurement at a time; this one waits for the other clip's.
                busyElsewhere -> "Measuring another clip…"
                clip.isStabilized -> "Measure again"
                else -> "Stabilize this clip"
            },
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!status.running && !busyElsewhere) viewModel.analysis.stabilizeClip(clip.id) }
        )
    }
}

/**
 * A key: the diamond the strip draws on a clip, as an icon for the sheets.
 * It was the text glyph "◆", which the phone's font drew a different size
 * and weight from the rest of the row - the reason the timeline's own marks
 * are vectors (see MiniAction in TimelineEditor).
 */
val KeyframeGlyph: ImageVector by lazy {
    ImageVector.Builder("Keyframe", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f); lineTo(22f, 12f); lineTo(12f, 22f); lineTo(2f, 12f); close()
        }
    }.build()
}

/** The same diamond hollow: no key here yet. */
val KeyframeOutlineGlyph: ImageVector by lazy {
    ImageVector.Builder("KeyframeOutline", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f); lineTo(22f, 12f); lineTo(12f, 22f); lineTo(2f, 12f); close()
            moveTo(12f, 5f); lineTo(19f, 12f); lineTo(12f, 19f); lineTo(5f, 12f); close()
        }
    }.build()
}

/** How long a shown arrival plays on past its end, so it is seen to land. */
private const val SHOW_AFTER_MS = 400L
