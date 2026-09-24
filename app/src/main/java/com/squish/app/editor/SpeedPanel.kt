package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors
import kotlin.math.abs
import kotlin.math.ln

/**
 * Speed, per clip, with ramps.
 *
 * Speed used to be one number for the whole project, which meant it was a setting
 * rather than an edit - there was no way to slow the landing and leave the run-up
 * alone. It belongs to a clip now, and a clip's rate can move across it.
 *
 * The curve is drawn because a ramp you cannot see is not a curve, it is a list of
 * numbers: the shape of the line is what tells you whether the slow part lands on
 * the moment you wanted it to.
 */
@Composable
fun SpeedPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val clip = viewModel.speedTargetClip(state)

    if (clip == null) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Nothing to retime",
                "Add a clip to the timeline first",
                icon = Icons.Filled.Speed,
                accent = SquishColors.Blue
            )
        }
        return
    }

    val ramp = clip.speedRamp

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                clip.label,
                lengthLine(clip),
                icon = Icons.Filled.Speed,
                accent = SquishColors.Blue,
                trailing = {
                    if (!ramp.isIdentity) {
                        Text(
                            "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Pink,
                            modifier = Modifier.clickable { viewModel.clearSpeed(clip.id) }
                        )
                    }
                }
            )

            RampCurve(clip = clip, playheadMs = state.playheadMs)

            if (ramp.isRamped) {
                Text(
                    "Ramped · ${"%.2f".format(ramp.speedAt(0L))}x to " +
                        "${"%.2f".format(ramp.speedAt(clip.sourceSpanMs))}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Cyan
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Rate", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                    Text(
                        "${"%.2f".format(ramp.flatSpeed)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextPrimary
                    )
                }
                // Logarithmic, so half speed and double speed sit the same distance
                // either side of the middle. On a linear track everything below 1x
                // is crushed into the first fifth and unusable.
                Slider(
                    value = speedToSlider(ramp.flatSpeed),
                    onValueChange = { viewModel.setClipSpeed(clip.id, sliderToSpeed(it)) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = SquishColors.Blue,
                        activeTrackColor = SquishColors.Blue,
                        inactiveTrackColor = SquishColors.Border
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { preset ->
                        SelectableChip(
                            label = if (preset < 1f) "${"%.2f".format(preset).trimEnd('0').trimEnd('.')}x"
                            else "${preset.toInt()}x",
                            selected = abs(ramp.flatSpeed - preset) < 0.01f,
                            accentColor = SquishColors.Blue,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.setClipSpeed(clip.id, preset) }
                        )
                    }
                }
            }
        }

        PanelSurface(accent = SquishColors.Cyan) {
            PanelHeading(
                "Ramps",
                "A rate that moves across the shot",
                icon = Icons.Filled.Timer,
                accent = SquishColors.Cyan
            )
            RampShape.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { shape ->
                        SelectableChip(
                            label = shape.label,
                            selected = false,
                            accentColor = SquishColors.Cyan,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.applyRampShape(clip.id, shape) }
                        )
                    }
                    repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
            Text(
                RampShape.entries.first { it.label == "Bullet" }.hint,
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        PanelSurface(accent = SquishColors.Cyan) {
            PanelHeading(
                "Points",
                if (ramp.ordered.isEmpty()) "Set a rate at the playhead to start a curve"
                else "${ramp.ordered.size} on this clip",
                icon = Icons.Filled.Timer,
                accent = SquishColors.Cyan
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0.25f, 0.5f, 1f, 2f).forEach { speed ->
                    SquishOutlinedButton(
                        text = if (speed < 1f) "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
                        else "${speed.toInt()}x",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setSpeedPointAtPlayhead(clip.id, speed) }
                    )
                }
            }
            Text(
                "Each button drops a point at the playhead. Two or more and the rate " +
                    "moves between them.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )

            ramp.ordered.forEach { point ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SquishColors.Background)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        Timecode.format(clip.timelineAtSource(clip.sourceInMs + point.atMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${"%.2f".format(point.speed)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.Cyan
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove this point",
                        tint = SquishColors.Pink,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { viewModel.removeSpeedPoint(clip.id, point.atMs) }
                    )
                }
            }
        }
    }
}

/**
 * The curve, over the played length of the clip.
 *
 * Drawn against played time rather than source time on purpose: that is the axis
 * the strip uses and the axis the playhead moves along, so the dip in the line
 * sits under the moment it actually slows down. Plotted against source time the
 * same ramp would appear to slow down too early, because the slow part occupies
 * more of the screen than it does of the file.
 */
@Composable
private fun RampCurve(clip: Clip, playheadMs: Long) {
    val ramp = clip.speedRamp
    val played = clip.durationMs.coerceAtLeast(1L)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4.2f)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        fun y(speed: Float): Float {
            // Same log mapping as the slider, so the line and the control agree.
            val t = speedToSlider(speed)
            return h - (h * 0.14f + t * h * 0.72f)
        }

        // The 1x line, so a ramp reads as above or below normal at a glance.
        val normalY = y(1f)
        drawLine(
            color = SquishColors.Border,
            start = Offset(0f, normalY),
            end = Offset(w, normalY),
            strokeWidth = 1.5f
        )

        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val outMs = played * i / steps
            val srcMs = ramp.sourceOffsetAt(outMs, clip.sourceSpanMs)
            val px = w * i / steps
            val py = y(ramp.speedAt(srcMs))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = SquishColors.Cyan,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )

        // Each control point, at where it lands in played time.
        for (point in ramp.ordered) {
            val outMs = ramp.outputOffsetAt(point.atMs, clip.sourceSpanMs)
            val px = w * (outMs.toFloat() / played).coerceIn(0f, 1f)
            drawCircle(color = SquishColors.Blue, radius = 5f, center = Offset(px, y(point.speed)))
        }

        // The playhead, but only while it is over this clip.
        val local = playheadMs - clip.timelineStartMs
        if (local in 0..played) {
            val px = w * (local.toFloat() / played)
            drawLine(
                color = SquishColors.TextPrimary.copy(alpha = 0.55f),
                start = Offset(px, 0f),
                end = Offset(px, h),
                strokeWidth = 1.5f
            )
        }
    }
}

private fun lengthLine(clip: Clip): String {
    val source = Timecode.format(clip.sourceSpanMs)
    val played = Timecode.format(clip.durationMs)
    return if (clip.speedRamp.isIdentity) "$played on the timeline"
    else "$source of footage · $played on the timeline"
}

/**
 * Speed to slider position, logarithmically.
 *
 * Half and double sit the same distance either side of the middle, which is how
 * anyone thinks about rate. On a linear 0.1-to-10 track, 1x sits at a tenth and
 * everything slower than normal is crushed into the first ninth of the travel.
 */
private fun speedToSlider(speed: Float): Float {
    val clamped = speed.coerceIn(SpeedRamp.MIN_SPEED, SpeedRamp.MAX_SPEED)
    val lo = ln(SpeedRamp.MIN_SPEED.toDouble())
    val hi = ln(SpeedRamp.MAX_SPEED.toDouble())
    return (((ln(clamped.toDouble()) - lo) / (hi - lo)).toFloat()).coerceIn(0f, 1f)
}

private fun sliderToSpeed(t: Float): Float {
    val lo = ln(SpeedRamp.MIN_SPEED.toDouble())
    val hi = ln(SpeedRamp.MAX_SPEED.toDouble())
    val raw = Math.exp(lo + (hi - lo) * t.coerceIn(0f, 1f).toDouble()).toFloat()
    // Snapped near the common rates, so the slider can actually land on 1x.
    for (snap in floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)) {
        if (abs(raw - snap) < snap * 0.06f) return snap
    }
    return (Math.round(raw * 100f) / 100f)
}
