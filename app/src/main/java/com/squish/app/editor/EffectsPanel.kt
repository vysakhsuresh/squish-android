package com.squish.app.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * The effects library: tap one and it covers the next two seconds from the
 * playhead. Each placed effect can be turned up or down, given its own knob,
 * or removed here; its ends are the handles on the strip.
 *
 * Each tile is the effect running on a small picture - the same FxParams the
 * shader is handed, drawn with what Compose has - so what it does is seen
 * before it is added rather than read off a name.
 */
@Composable
fun EffectsPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val placed = state.effects.sortedBy { it.startMs }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Effects",
                "Tap one to add it at the playhead",
                icon = Icons.Filled.Bolt,
                accent = SquishColors.Blue
            )
            EffectKind.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { kind ->
                        EffectTile(kind = kind, modifier = Modifier.weight(1f)) { viewModel.clips.addEffect(kind) }
                    }
                    repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }

        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "On the video",
                if (placed.isEmpty()) "None yet" else "${placed.size} placed",
                icon = Icons.Filled.Layers,
                accent = SquishColors.Blue
            )
            placed.forEach { effect ->
                PlacedEffect(
                    effect = effect,
                    onJump = { viewModel.scrubTo(effect.startMs) },
                    onChange = { change -> viewModel.clips.changeEffect(effect.id, change = change) },
                    onRemove = { viewModel.clips.removeEffect(effect.id) },
                    onGestureEnd = viewModel::endGesture
                )
            }
        }
    }
}

/** One effect in the library: its picture running, and its name. */
@Composable
private fun EffectTile(kind: EffectKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val loop = rememberInfiniteTransition(label = "effect")
    val t by loop.animateFloat(
        initialValue = 0f,
        targetValue = 2_000f,
        animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart),
        label = "time"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
        ) {
            // The effect over a two-second stretch, at the strength it is added at.
            val sample = TimedEffect(id = "sample", kind = kind, startMs = 0L, endMs = 2_000L)
            drawSample(FxParams.at(listOf(sample), t.toLong()))
        }
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A small scene - a sky, a hill, a sun - drawn as the shader would treat it:
 * moved and zoomed, split into colours, flashed, drained, inverted, lined,
 * grained, softened, turned round the hue.
 */
private fun DrawScope.drawSample(p: FxParams) {
    val w = size.width
    val h = size.height
    val zoom = p.zoom.coerceAtLeast(0.1f)
    // The shader samples at (uv - 0.5) / zoom + 0.5 + offset: a zoom in and a
    // shift the other way.
    val sw = w * zoom
    val sh = h * zoom
    val left = (w - sw) / 2f - p.offsetX * w
    val top = (h - sh) / 2f - p.offsetY * h

    fun treat(c: Color): Color {
        var r = c.red; var g = c.green; var b = c.blue
        if (p.hue > 0.001f) {
            val (hr, hg, hb) = hueRotate(r, g, b, p.hue)
            r = hr; g = hg; b = hb
        }
        if (p.mono > 0.001f) {
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r += (l - r) * p.mono; g += (l - g) * p.mono; b += (l - b) * p.mono
        }
        if (p.invert > 0.001f) {
            r += (1f - 2f * r) * p.invert; g += (1f - 2f * g) * p.invert; b += (1f - 2f * b) * p.invert
        }
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    fun scene(dx: Float, alpha: Float, only: Color?) {
        fun paint(c: Color) = only?.let { mask -> Color(c.red * mask.red, c.green * mask.green, c.blue * mask.blue) } ?: c
        drawRect(paint(treat(SKY)), Offset(left + dx, top), Size(sw, sh), alpha = alpha)
        drawCircle(paint(treat(SUN)), radius = sh * 0.14f, center = Offset(left + dx + sw * 0.7f, top + sh * 0.32f), alpha = alpha)
        drawRect(paint(treat(HILL)), Offset(left + dx, top + sh * 0.62f), Size(sw, sh * 0.38f), alpha = alpha)
    }

    if (p.split > 0.0001f) {
        // Red pulled one way and blue the other, green in place: the colour split.
        val d = p.split * w
        scene(d, 1f, Color(1f, 0f, 0f))
        scene(0f, 1f, Color(0f, 1f, 0f))
        scene(-d, 1f, Color(0f, 0f, 1f))
    } else {
        scene(0f, 1f, null)
    }
    if (p.blur > 0.0001f) {
        // Softened: ghosts either side, as a blur spreads the picture.
        val d = p.blur * w * 8f
        scene(d, 0.35f, null)
        scene(-d, 0.35f, null)
    }
    if (p.scan > 0.001f) {
        var y = 0f
        while (y < h) {
            drawRect(Color.Black.copy(alpha = 0.22f * p.scan), Offset(0f, y), Size(w, 1.5f))
            y += 4f
        }
    }
    if (p.noise > 0.001f) {
        // A sprinkle of grain that moves with the time, so it reads as grain.
        val seed = (p.timeSec * 60f).toInt()
        for (i in 0 until 60) {
            val x = ((i * 97 + seed * 31) % 101) / 100f * w
            val y = ((i * 57 + seed * 17) % 103) / 102f * h
            drawRect(Color.White.copy(alpha = 0.5f * p.noise), Offset(x, y), Size(2f, 2f))
        }
    }
    if (p.flash > 0.001f) drawRect(Color.White, alpha = p.flash.coerceIn(0f, 1f))
}

private fun hueRotate(r: Float, g: Float, b: Float, a: Float): Triple<Float, Float, Float> {
    val s = sin(a); val k = cos(a)
    return Triple(
        r * (0.299f + 0.701f * k + 0.168f * s) + g * (0.587f - 0.587f * k + 0.330f * s) + b * (0.114f - 0.114f * k - 0.497f * s),
        r * (0.299f - 0.299f * k - 0.328f * s) + g * (0.587f + 0.413f * k + 0.035f * s) + b * (0.114f - 0.114f * k + 0.292f * s),
        r * (0.299f - 0.300f * k + 1.250f * s) + g * (0.587f - 0.588f * k - 1.050f * s) + b * (0.114f + 0.886f * k - 0.203f * s)
    )
}

private val SKY = Color(0xFF3A6EA5)
private val SUN = Color(0xFFF2C14E)
private val HILL = Color(0xFF3B7A57)

@Composable
private fun PlacedEffect(
    effect: TimedEffect,
    onJump: () -> Unit,
    onChange: ((TimedEffect) -> TimedEffect) -> Unit,
    onRemove: () -> Unit,
    onGestureEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(effect.kind.icon, contentDescription = null, tint = effect.kind.color, modifier = Modifier.size(18.dp))
            Text("  ${effect.kind.label}", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            Text(
                "  ${Timecode.format(effect.startMs)} → ${Timecode.format(effect.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Cyan,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onJump)
            )
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }
        EffectSliders(effect, onChange, onGestureEnd)
        // Its ends are retimed on the strip, where the effect is a clip with
        // handles like any other. "Start here" and "End here" buttons did the
        // same job a second way, with a second set of limits. The handles only
        // show on the selected effect, and selecting one closes this level-0
        // sheet, so the line says the whole path rather than pointing at
        // handles the open sheet keeps off the strip.
        Text(
            "To retime it, close this sheet and tap it on the strip: its ends are handles",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
    }
}

/** Strength, and the effect's own knob where it has one (EffectKind.parameter). */
@Composable
private fun EffectSliders(effect: TimedEffect, onChange: ((TimedEffect) -> TimedEffect) -> Unit, onGestureEnd: () -> Unit) {
    LabeledSlider("Strength", effect.intensity, 0.1f..1f, onFinished = onGestureEnd) { v -> onChange { it.copy(intensity = v) } }
    effect.kind.parameter?.let { name ->
        LabeledSlider(name, effect.amount, 0f..1f, onFinished = onGestureEnd) { v -> onChange { it.copy(amount = v) } }
    }
}

/** How strong one placed effect is, and its own knob: the effect's own toolbar, Strength. */
@Composable
fun EffectStrengthPanel(effect: TimedEffect, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            effect.kind.label,
            "${Timecode.format(effect.startMs)} → ${Timecode.format(effect.endMs)} · drag its ends on the strip to retime it",
            icon = effect.kind.icon,
            accent = SquishColors.Blue
        )
        EffectSliders(
            effect,
            onChange = { change -> viewModel.clips.changeEffect(effect.id, change = change) },
            onGestureEnd = viewModel::endGesture
        )
    }
}
