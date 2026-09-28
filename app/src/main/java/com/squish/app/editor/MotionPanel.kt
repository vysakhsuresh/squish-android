package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.within
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Motion: where a clip's picture sits, and how it moves while the clip plays.
 *
 * The sliders always edit *the moment the playhead is on*. On a still clip that is
 * simply its placement; on an animated one it becomes a keyframe there. One
 * control, one meaning, whichever mode the clip is in.
 */
@Composable
fun MotionPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val clip = state.targetVideoClip

    if (clip == null) {
        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Nothing to move",
                "Add a clip to the timeline first",
                icon = Icons.Filled.Animation,
                accent = SquishColors.Amber
            )
        }
        return
    }

    // What the editor set, not what is drawn: the drawn transform includes the
    // stabilizer, and showing it here showed a 108% "scale" nobody chose - which
    // the next nudge then wrote back, doubling the stabilizer's zoom.
    val here = clip.userTransformAt(state.playheadMs)
    val animated = clip.keyframes.isNotEmpty()
    // A trim or a cut keeps the keys over footage the clip no longer shows, so
    // the move comes back with the footage; they are not this clip's to edit.
    val keys = clip.keyframes.within(clip.durationMs)
    val hidden = clip.keyframes.size - keys.size

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        StabilizeCard(state = state, clip = clip, viewModel = viewModel)

        TrackPanel(state = state, clip = clip, viewModel = viewModel)

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                clip.label,
                if (animated) {
                    "${keys.size} keys · moves while it plays" +
                        if (hidden > 0) " · $hidden more in trimmed footage" else ""
                } else "Sitting still — add a move below",
                icon = Icons.Filled.Animation,
                accent = SquishColors.Amber
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MotionPreset.entries.take(3).forEach { preset ->
                    SelectableChip(
                        label = preset.label,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.clips.applyMotionPreset(clip.id, preset) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MotionPreset.entries.drop(3).forEach { preset ->
                    SelectableChip(
                        label = preset.label,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.clips.applyMotionPreset(clip.id, preset) }
                    )
                }
            }
            Text(
                "A preset lays two keys across the whole clip. Adjust them below, or add your own.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                if (animated) "At ${Timecode.format(state.playheadMs)}" else "Placement",
                if (animated) "Changing these sets a key at the playhead" else "Where the picture sits",
                icon = Icons.Filled.Transform,
                accent = SquishColors.Amber
            )
            LabeledSlider("Scale", here.scale, 0.2f..3f, onFinished = viewModel::endGesture) {
                viewModel.clips.setClipTransform(clip.id, scale = it)
            }
            LabeledSlider("Across", here.offsetXFraction, -1f..1f, onFinished = viewModel::endGesture) {
                viewModel.clips.setClipTransform(clip.id, offsetX = it)
            }
            LabeledSlider("Up / down", here.offsetYFraction, -1f..1f, onFinished = viewModel::endGesture) {
                viewModel.clips.setClipTransform(clip.id, offsetY = it)
            }
            LabeledSlider(
                "Rotation", here.rotationDegrees, -45f..45f,
                readout = Readout.degrees,
                onFinished = viewModel::endGesture
            ) {
                viewModel.clips.setClipTransform(clip.id, rotation = it)
            }
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Keyframes",
                "Control points along the clip",
                icon = Icons.Filled.Timer,
                accent = SquishColors.Amber,
                trailing = {
                    if (animated) {
                        Text(
                            "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Pink,
                            modifier = Modifier.clickable { viewModel.clips.clearKeyframes(clip.id) }
                        )
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
                        onGoTo = { viewModel.scrubTo(clip.timelineStartMs + key.atMs) },
                        onEasing = { viewModel.clips.setKeyframeEasing(clip.id, key.atMs, it) },
                        onRemove = { viewModel.clips.removeKeyframe(clip.id, key.atMs) }
                    )
                }
            }
        }
    }
}

private fun isUnderPlayhead(clip: Clip, key: Keyframe, state: EditorUiState): Boolean {
    val at = state.playheadMs - clip.timelineStartMs
    return kotlin.math.abs(at - key.atMs) <= state.frameMs
}

@Composable
private fun KeyRow(
    clip: Clip,
    key: Keyframe,
    atPlayhead: Boolean,
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
                color = if (atPlayhead) SquishColors.Amber else SquishColors.Border,
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
                Text(
                    "◆ ${Timecode.format(key.atMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (atPlayhead) SquishColors.Amber else SquishColors.TextPrimary
                )
                Text(
                    "${(key.transform.scale * 100).toInt()}%" +
                        if (key.transform.rotationDegrees != 0f) " · ${key.transform.rotationDegrees.toInt()}°" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
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
 * why it shows up live in the preview the moment it finishes.
 */
@Composable
private fun StabilizeCard(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    // Only this clip's analysis. The card used to report whichever clip was
    // measured last - "Zoomed in 8%", or its failure - on every clip selected
    // after it, beside a button that said this one was not stabilized.
    val status = state.stabilize.takeIf { it.clipId == clip.id } ?: StabilizeProgress()
    val busyElsewhere = state.stabilize.running && state.stabilize.clipId != clip.id

    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Stabilize",
            if (clip.isStabilized) "Shake removed · ${clip.stabilizer.size} measurements"
            else "Smooth out handheld shake",
            icon = Icons.Filled.Straighten,
            accent = SquishColors.Amber,
            trailing = {
                if (clip.isStabilized) {
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.analysis.clearStabilization(clip.id) }
                    )
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

        LabeledSlider(
            "Strength", state.stabilizeStrength, 0f..1f,
            onFinished = viewModel::endGesture,
            onChange = viewModel.analysis::setStabilizeStrength
        )
        Text(
            "Stronger holds the frame steadier and crops in further to afford it.",
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
                clip.isStabilized -> "Measure again at this strength"
                else -> "Stabilize this clip"
            },
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!status.running && !busyElsewhere) viewModel.analysis.stabilizeClip(clip.id) }
        )
    }
}
