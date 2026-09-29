package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SlowMotion
import com.squish.app.timeline.SpeedRamp
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors
import kotlin.math.abs
import kotlin.math.hypot
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
 * the moment you wanted it to. And it is dragged, because a point that can only be
 * placed by parking the playhead and pressing a rate is a list of numbers again.
 */
@Composable
fun SpeedPanel(state: EditorUiState, viewModel: EditorViewModel, accent: Color) {
    val clip = viewModel.clips.speedTargetClip(state)

    if (clip == null) {
        PanelSurface(accent = accent) {
            PanelHeading(
                "Nothing to retime",
                "Add a clip to the timeline first",
                icon = Icons.Filled.Speed,
                accent = accent
            )
        }
        return
    }

    val ramp = clip.speedRamp

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        PanelSurface(accent = accent) {
            PanelHeading(
                clip.label,
                lengthLine(clip),
                icon = Icons.Filled.Speed,
                accent = accent
            )

            RampCurve(clip = clip, playheadMs = state.playheadMs, accent = accent, viewModel = viewModel)
            Text(
                if (ramp.ordered.size >= 2) "Drag a point to move it; tap the curve to add one."
                else "Tap the curve to add a point where you want the rate to change.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )

            // What the speed does to the frame rate, said as a number.
            //
            // Slowing footage down does not create frames - the timestamps are
            // stretched and the same pictures are spread further apart - so a
            // thirty-frame second at a quarter speed is seven and a half frames a
            // second, each held for a seventh of a second. That is the stepping,
            // and it is arithmetic rather than a fault. Nobody can be expected to
            // work that out from a slider, so the panel says it.
            SmoothnessLine(sourceFps = state.fps, speed = ramp.slowestSpeed)

            SmoothToggle(
                sourceFps = state.fps,
                speed = ramp.slowestSpeed,
                onHold = { viewModel.clips.keepSmooth(clip.id) }
            )

            FrameBlendRow(clip = clip, sourceFps = state.fps, viewModel = viewModel)

            if (ramp.isRamped) {
                Text(
                    "Ramped · ${"%.2f".format(ramp.speedAt(0L))}x to " +
                        "${"%.2f".format(ramp.speedAt(clip.sourceSpanMs))}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = accent
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Rate", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                    Text(
                        rateLabel(ramp.flatSpeed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextPrimary
                    )
                }
                // Logarithmic, so half speed and double speed sit the same distance
                // either side of the middle. On a linear track everything below 1x
                // is crushed into the first fifth and unusable - and with the
                // ceiling at a hundred times, everything below ten would be.
                Slider(
                    value = speedToSlider(ramp.flatSpeed),
                    onValueChange = { viewModel.clips.setClipSpeed(clip.id, sliderToSpeed(it), dragging = true) },
                    onValueChangeFinished = viewModel::endGesture,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = SquishColors.Border
                    )
                )
                // Two rows of three, as the Curves chips are: six across cut
                // "0.25x" to ".25" on a narrow phone.
                listOf(0.25f, 0.5f, 1f, 2f, 4f, 10f).chunked(3).forEach { presets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        presets.forEach { preset ->
                            SelectableChip(
                                label = rateLabel(preset),
                                selected = abs(ramp.flatSpeed - preset) < 0.01f,
                                accentColor = accent,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.clips.setClipSpeed(clip.id, preset) }
                            )
                        }
                    }
                }
            }

            PitchRow(clip = clip, viewModel = viewModel)
        }

        PanelSurface(accent = accent) {
            PanelHeading(
                "Curves",
                "A rate that moves across the shot",
                icon = Icons.Filled.Timer,
                accent = accent
            )
            // Read back off the curve, since a shape is not stored by name:
            // the chip lights while the points are the ones it laid, and goes
            // out the moment one is dragged. The chips used to light none, and
            // the line under them was always Bullet's.
            val active = PolishRules.activeRampShape(ramp, clip.sourceSpanMs)
            RampShape.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { shape ->
                        SelectableChip(
                            label = shape.label,
                            selected = shape == active,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.clips.applyRampShape(clip.id, shape) }
                        )
                    }
                    repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
            Text(
                PolishRules.rampHint(active),
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        PanelSurface(accent = accent) {
            PanelHeading(
                "Points",
                if (ramp.ordered.isEmpty()) "Set a rate at the playhead to start a curve"
                else "${ramp.ordered.size} on this clip",
                icon = Icons.Filled.Timer,
                accent = accent
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0.25f, 0.5f, 1f, 2f).forEach { speed ->
                    SquishOutlinedButton(
                        text = rateLabel(speed),
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.clips.setSpeedPointAtPlayhead(clip.id, speed) }
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
                        rateLabel(point.speed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove this point",
                        tint = SquishColors.Pink,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { viewModel.clips.removeSpeedPoint(clip.id, point.atMs) }
                    )
                }
            }
        }
    }
}

/** A rate as the sheet prints it: "0.25x", "2x", "100x". */
private fun rateLabel(speed: Float): String =
    if (speed < 1f) "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
    else if (abs(speed - speed.toInt()) < 0.005f) "${speed.toInt()}x"
    else "${"%.1f".format(speed).trimEnd('0').trimEnd('.')}x"

/**
 * Whether the sound's pitch follows the speed. Held is the default: a voice at
 * half speed stays a voice. Followed, it drops and rises with the rate like a
 * tape - the sound every slow-motion replay has - and the file does the same
 * (VideoProcessor.buildAudioProcessors). Only where there is sound to pitch.
 */
@Composable
private fun PitchRow(clip: Clip, viewModel: EditorViewModel) {
    if (clip.isStillPicture) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Pitch follows speed", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            Text(
                if (clip.pitchFollowsSpeed) "Slower is deeper, faster is higher, like a tape."
                else "The pitch is held whatever the speed, so a voice stays a voice.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
        SquishToggleSwitch(
            checked = clip.pitchFollowsSpeed,
            onCheckedChange = { viewModel.clips.setPitchFollowsSpeed(clip.id, it) }
        )
    }
}

/**
 * Frame blending for the file, offered where the clip goes below the rate its
 * footage carries (SlowMotion). Export only - the preview shows the steps - and
 * the row says so, since a switch that changes nothing on screen reads as broken.
 */
@Composable
private fun FrameBlendRow(clip: Clip, sourceFps: Float, viewModel: EditorViewModel) {
    if (clip.kind != ClipKind.Video || clip.isStillPicture) return
    val slowest = clip.speedRamp.slowestSpeed
    if (!clip.frameBlend && (sourceFps <= 0f || SlowMotion.isSmooth(sourceFps, slowest))) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Blend frames", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            Text(
                "Mixes neighbouring frames into the gaps the slow motion opens, in the file. " +
                    "The preview shows the steps; the file blurs through them.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
        SquishToggleSwitch(
            checked = clip.frameBlend,
            onCheckedChange = { viewModel.clips.setFrameBlend(clip.id, it) }
        )
    }
}

/**
 * The curve, over the played length of the clip, with its points under the finger.
 *
 * Drawn against played time rather than source time on purpose: that is the axis
 * the strip uses and the axis the playhead moves along, so the dip in the line
 * sits under the moment it actually slows down. Plotted against source time the
 * same ramp would appear to slow down too early, because the slow part occupies
 * more of the screen than it does of the file.
 *
 * A finger put down on a point takes hold of it at once and moves it: sideways
 * along the clip, up and down through the same log scale the slider uses. The
 * point is followed by its place in the list, which a move never changes
 * (SpeedRamp.withPointMoved). Sideways is read through the clocks the drag
 * began on - the curve, and the clip's played length - both fixed for the
 * drag: a point is anchored in source time, and every move of it retimes the
 * clip, so read against the live length the same finger gave a different
 * moment on every event and a point dragged straight up slid sideways. A
 * finger put down anywhere else is left to the sheet: a tap adds a point
 * there, at the rate the curve already has, and a swipe scrolls the sheet -
 * the curve used to take every drag on it and scroll nothing.
 */
@Composable
private fun RampCurve(clip: Clip, playheadMs: Long, accent: Color, viewModel: EditorViewModel) {
    val ramp = clip.speedRamp
    val played = clip.durationMs.coerceAtLeast(1L)
    val latestClip by rememberUpdatedState(clip)
    val hitRadius = with(LocalDensity.current) { 24.dp.toPx() }
    // The point under the finger, by its index in the ordered list.
    var held by remember(clip.id) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3.2f)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .pointerInput(clip.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = latestClip
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val index = nearestPoint(c, down.position, w, h, hitRadius)
                    if (index == null) {
                        // Not on a point: the sheet may scroll by this, so
                        // nothing is taken; it is a tap only if it ends here.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        up.consume()
                        val playedAt = (up.position.x / w).coerceIn(0f, 1f) * c.durationMs
                        val atMs = c.speedRamp.sourceOffsetAt(playedAt.toLong(), c.sourceSpanMs)
                        viewModel.clips.setSpeedPoint(c.id, atMs, c.speedRamp.speedAt(atMs))
                        return@awaitEachGesture
                    }
                    down.consume()
                    val startRamp = c.speedRamp
                    val startPlayed = c.durationMs.coerceAtLeast(1L)
                    held = index
                    drag(down.id) { change ->
                        val now = latestClip
                        val current = now.speedRamp.ordered.getOrNull(index) ?: return@drag
                        val playedAt = (change.position.x / w).coerceIn(0f, 1f) * startPlayed
                        val atMs = startRamp.sourceOffsetAt(playedAt.toLong(), now.sourceSpanMs)
                        viewModel.clips.moveSpeedPoint(now.id, current.atMs, atMs, speedFromY(change.position.y, h))
                        change.consume()
                    }
                    held = null
                    viewModel.endGesture()
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // The 1x line, so a ramp reads as above or below normal at a glance.
        val normalY = yForSpeed(1f, h)
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
            val py = yForSpeed(ramp.speedAt(srcMs), h)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = SquishColors.Cyan,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )

        // Each control point, at where it lands in played time; the one under
        // the finger drawn larger.
        ramp.ordered.forEachIndexed { index, point ->
            val outMs = ramp.outputOffsetAt(point.atMs, clip.sourceSpanMs)
            val px = w * (outMs.toFloat() / played).coerceIn(0f, 1f)
            val underFinger = held == index
            drawCircle(color = accent, radius = if (underFinger) 9f else 6f, center = Offset(px, yForSpeed(point.speed, h)))
            if (underFinger) drawCircle(color = SquishColors.TextPrimary, radius = 3f, center = Offset(px, yForSpeed(point.speed, h)))
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

/** The index of the point within [radius] of [at] on a curve [w] by [h], nearest first. */
private fun nearestPoint(clip: Clip, at: Offset, w: Float, h: Float, radius: Float): Int? {
    val ramp = clip.speedRamp
    val played = clip.durationMs.coerceAtLeast(1L)
    var best: Int? = null
    var bestDistance = radius
    ramp.ordered.forEachIndexed { index, point ->
        val outMs = ramp.outputOffsetAt(point.atMs, clip.sourceSpanMs)
        val px = w * (outMs.toFloat() / played).coerceIn(0f, 1f)
        val d = hypot(px - at.x, yForSpeed(point.speed, h) - at.y)
        if (d <= bestDistance) {
            bestDistance = d
            best = index
        }
    }
    return best
}

/** Same log mapping as the slider, so the line and the control agree. */
private fun yForSpeed(speed: Float, h: Float): Float {
    val t = speedToSlider(speed)
    return h - (h * 0.14f + t * h * 0.72f)
}

private fun speedFromY(y: Float, h: Float): Float {
    val t = ((h - y - h * 0.14f) / (h * 0.72f)).coerceIn(0f, 1f)
    return sliderToSpeed(t)
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
 * anyone thinks about rate. On a linear 0.1-to-100 track, 1x sits at a hundredth
 * and everything slower than normal is crushed into the first pixel.
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
    for (snap in floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 10f, 50f, 100f)) {
        if (abs(raw - snap) < snap * 0.06f) return snap
    }
    return if (raw >= 10f) Math.round(raw).toFloat() else (Math.round(raw * 100f) / 100f)
}

/** The frame rate this speed produces, and whether it will show. */
@Composable
private fun SmoothnessLine(sourceFps: Float, speed: Float) {
    if (sourceFps <= 0f || speed >= 1f) return
    val verdict = SlowMotion.verdict(sourceFps, speed)
    Text(
        SlowMotion.advice(sourceFps, speed),
        style = MaterialTheme.typography.bodySmall,
        color = when (verdict) {
            SlowMotion.Verdict.Smooth -> SquishColors.Cyan
            SlowMotion.Verdict.Stepped -> SquishColors.Amber
            SlowMotion.Verdict.Slideshow -> SquishColors.Pink
        }
    )
}

/**
 * Holds the clip at the slowest speed its own footage can carry.
 *
 * Not the toggle other editors have. Theirs invents the frames in between by
 * working out where everything moved, which is a real thing this does not do
 * and will not pretend to; Blend frames (FrameBlendRow) is the honest cousin,
 * blurring through the gaps in the file. This does the other honest thing: stops
 * at the point where the footage runs out of frames, so the result moves instead
 * of stepping. Only the parts slower than that are raised: a ramp keeps its
 * shape, where it used to be flattened to one rate without a word.
 *
 * Shown only when it would change something. At a hundred and twenty frames a
 * second there is nothing to warn about until a fifth speed, and a control that
 * does nothing is worse than no control.
 */
@Composable
private fun SmoothToggle(sourceFps: Float, speed: Float, onHold: () -> Unit) {
    if (sourceFps <= 0f) return
    if (SlowMotion.isSmooth(sourceFps, speed)) return
    val limit = SlowMotion.smoothestSpeed(sourceFps)
    if (limit <= speed + 0.01f) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Cyan.copy(alpha = 0.10f))
            .clickable(onClick = onHold)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = SquishColors.Cyan,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Keep it smooth",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary
            )
            Text(
                "Nothing slower than ${"%.2f".format(limit).trimEnd('0').trimEnd('.')}x — " +
                    "the slowest this footage carries",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
    }
}
