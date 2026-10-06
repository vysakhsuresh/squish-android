package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.squish.app.media.effects.ColorWheels
import com.squish.app.media.effects.Wheel
import com.squish.app.ui.components.SquishSlider
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.TextAction
import com.squish.app.ui.theme.SquishColors
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.squish.app.ui.components.ColourName
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Lift, gamma and gain: a dot in a disc for the colour and a slider under it
 * for the level, which is how every grading tool has drawn them for forty
 * years.
 *
 * The dot's place and the three numbers are the same thing, both ways
 * ([Wheel.of] and [Wheel.pad]), so nothing is stored twice and a sheet reopened
 * puts the dot back exactly where it was left.
 */

/** Which wheel the pad is showing. */
private enum class WheelPart(val label: String, val hint: String) {
    Lift("Shadows", "Raises the dark part and leaves white alone"),
    Gamma("Midtones", "Bends what is between, without moving either end"),
    Gain("Highlights", "Scales towards white, leaving black alone")
}

@Composable
fun ColorWheelsEditor(
    wheels: ColorWheels,
    onChange: (ColorWheels) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit
) {
    var part by remember { mutableStateOf(WheelPart.Lift) }
    val wheel = when (part) {
        WheelPart.Lift -> wheels.lift
        WheelPart.Gamma -> wheels.gamma
        WheelPart.Gain -> wheels.gain
    }
    val write = { next: Wheel ->
        onChange(
            when (part) {
                WheelPart.Lift -> wheels.copy(lift = next)
                WheelPart.Gamma -> wheels.copy(gamma = next)
                WheelPart.Gain -> wheels.copy(gain = next)
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WheelPart.entries.forEach { entry ->
                val touched = when (entry) {
                    WheelPart.Lift -> !wheels.lift.isIdentity
                    WheelPart.Gamma -> !wheels.gamma.isIdentity
                    WheelPart.Gain -> !wheels.gain.isIdentity
                }
                SelectableChip(
                    label = if (touched) "${entry.label} •" else entry.label,
                    selected = part == entry,
                    accentColor = SquishColors.Blue,
                    modifier = Modifier.weight(1f),
                    onClick = { part = entry }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(part.hint, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            if (!wheels.isIdentity) TextAction("Reset all", color = SquishColors.TextMuted, onClick = onReset)
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WheelDisc(
                wheel = wheel,
                onPad = { x, y -> write(Wheel.of(x, y, wheel.master)) },
                onFinished = onFinished,
                modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Level", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(
                "${(wheel.master * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextPrimary
            )
        }
        SquishSlider(
            value = wheel.master.coerceIn(-1f, 1f),
            onValueChange = { write(wheel.withMaster(it)) },
            onValueChangeFinished = onFinished,
            valueRange = -1f..1f
        )
    }
}

/**
 * The disc itself: hue round it, how far out it is dragged being how much.
 *
 * A tap puts the dot where the finger is, which is what the finger meant - a
 * pad that only moves relatively is a pad you have to drag three times to
 * reach the edge. A tap in the exact middle is neutral, so it is also the way
 * to clear this wheel's colour without touching its level.
 */
@Composable
private fun WheelDisc(
    wheel: Wheel,
    onPad: (Float, Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestPad by rememberUpdatedState(onPad)
    val latestDone by rememberUpdatedState(onFinished)
    // Said out loud, and changeable without a drag.
    //
    // The disc was a Canvas in a Box with two pointer inputs and nothing else:
    // no name, no readout, no actions. A Box whose only child is a Canvas has
    // no semantics to focus, so a screen reader skipped it entirely - the three
    // wheels could be reached and named by their chips, and their actual value
    // could be neither read nor changed by anything but a drag on an unlabelled
    // surface. The sheet shows no number for it either; the chip gains a bullet,
    // which says touched, not which way. Reset all was the only way back.
    //
    // The spokes come from the model rather than from the drawing: Wheel's own
    // pad() of a pure primary is where that primary sits in the disc, so these
    // cannot drift from what a finger would do.
    val nudge: (Wheel, Float, Float) -> Unit = { w, dx, dy ->
        val (x, y) = w.pad()
        latestPad(x + dx, y + dy)
        latestDone()
    }
    val actions = buildList {
        listOf(
            "Towards red" to Wheel(r = 1f),
            "Towards green" to Wheel(g = 1f),
            "Towards blue" to Wheel(b = 1f)
        ).forEach { (label, primary) ->
            val (sx, sy) = primary.pad()
            val length = kotlin.math.hypot(sx.toDouble(), sy.toDouble()).toFloat().coerceAtLeast(1e-4f)
            add(
                CustomAccessibilityAction(label) {
                    nudge(wheel, sx / length * PAD_STEP, sy / length * PAD_STEP)
                    true
                }
            )
        }
        add(
            CustomAccessibilityAction("Back to neutral") {
                latestPad(0f, 0f)
                latestDone()
                true
            }
        )
    }
    Box(
        modifier = modifier.semantics {
            contentDescription = "Colour wheel"
            stateDescription = spokenTint(wheel)
            customActions = actions
        }.pointerInput(Unit) {
            // Both gestures on one pointer input, so a tap and a drag cannot
            // each claim the first touch.
            detectDragGestures(
                onDragEnd = { latestDone() },
                onDragCancel = { latestDone() }
            ) { change, _ ->
                change.consume()
                val half = size.width / 2f
                latestPad((change.position.x - half) / half, (half - change.position.y) / half)
            }
        }.pointerInput(Unit) {
            detectTapGestures { at ->
                val half = size.width / 2f
                latestPad((at.x - half) / half, (half - at.y) / half)
                latestDone()
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(2.dp)) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            // The hues round the rim, where the three axes actually put them:
            // red up, green at seven o'clock, blue at five. A sweep gradient
            // starts at three o'clock and runs *clockwise* while the axes are
            // measured anticlockwise from there, so each stop is placed by
            // hand - evenly spaced colours put red on the left, and a finger
            // dragged at what looked like red warmed nothing.
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color(0xFF7A4AFF),
                    0.0833f to Color(0xFF3F6BFF),
                    0.25f to Color(0xFF00C8FF),
                    0.4167f to Color(0xFF3AE08A),
                    0.5833f to Color(0xFFE8E84A),
                    0.75f to Color(0xFFFF5B4A),
                    0.9167f to Color(0xFFFF4ADA),
                    1f to Color(0xFF7A4AFF),
                    center = centre
                ),
                radius = radius,
                center = centre
            )
            // Washed out towards the middle, because the middle is neutral.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(SquishColors.Background, SquishColors.Background.copy(alpha = 0f)),
                    center = centre,
                    radius = radius
                ),
                radius = radius,
                center = centre
            )
            drawCircle(color = SquishColors.Border, radius = radius, center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))

            val (x, y) = wheel.pad()
            val distance = hypot(x.toDouble(), y.toDouble()).toFloat().coerceAtMost(1f)
            val at = if (distance <= 0f) centre else Offset(
                centre.x + x / maxOf(distance, 1e-6f) * distance * radius,
                centre.y - y / maxOf(distance, 1e-6f) * distance * radius
            )
            drawCircle(color = Color.White, radius = 9f, center = at)
            drawCircle(color = Color.Black, radius = 9f, center = at, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

/** How far one accessibility nudge moves the dot, out of the disc's radius of 1. */
private const val PAD_STEP = 0.2f

/**
 * Where the wheel's dot sits, said out loud: the tint's own name and how far
 * out it is. Named from the tint itself (ColourName), so it says the same thing
 * a swatch of that colour would.
 */
private fun spokenTint(wheel: Wheel): String {
    val out = wheel.radius
    if (out < 0.03f) return "neutral"
    val (x, y) = wheel.pad()
    val tint = Wheel.of(x, y, 0f)
    fun channel(v: Float) = ((0.5f + v) * 255f).roundToInt().coerceIn(0, 255)
    val argb = (0xFF shl 24) or (channel(tint.r) shl 16) or (channel(tint.g) shl 8) or channel(tint.b)
    return "${ColourName.of(argb)}, ${(out * 100f).roundToInt()}% out"
}
