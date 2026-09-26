package com.squish.app.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squish.app.tools.QuickTool
import com.squish.app.ui.icons.SquishIcons

/**
 * One of Squish's own marks ([SquishIcons]) on its gradient sticker - how the
 * app shows a template, an effect, a voice or a quick tool. Emoji render
 * differently on every phone and read as chat; the stock icon set read as a
 * settings screen. These are meant to look like something you'd slap on a clip.
 *
 * [tilt] leans alternate stickers opposite ways so a row looks stuck on by hand.
 */
data class Glyph(val icon: ImageVector, val from: Color, val to: Color, val tilt: Float = 0f)

private val Tilts = floatArrayOf(-4f, 3f, -2f, 4f, -3f, 2f)

private fun glyph(icon: ImageVector, from: Long, to: Long, index: Int) =
    Glyph(icon, Color(from), Color(to), Tilts[index % Tilts.size])

val Template.glyph: Glyph
    get() = when (this) {
        Template.Reel -> glyph(SquishIcons.Reel, 0xFFFF5F8F, 0xFF8B5CF6, ordinal)
        Template.Vlog -> glyph(SquishIcons.Vlog, 0xFFFFB547, 0xFFFF6A3D, ordinal)
        Template.Cinematic -> glyph(SquishIcons.Cinematic, 0xFF4F7BFF, 0xFF1E2A78, ordinal)
        Template.Retro -> glyph(SquishIcons.Retro, 0xFFFF9A62, 0xFFB5476E, ordinal)
        Template.Party -> glyph(SquishIcons.Party, 0xFF22E3C4, 0xFFB14DFF, ordinal)
        Template.Memories -> glyph(SquishIcons.Memories, 0xFFB8BCCB, 0xFF4A4E63, ordinal)
    }

val EffectKind.glyph: Glyph
    get() = when (this) {
        EffectKind.Shake -> glyph(SquishIcons.Shake, 0xFFFF7A59, 0xFFE83E8C, ordinal)
        EffectKind.Punch -> glyph(SquishIcons.Punch, 0xFFFF5B3A, 0xFFB5236B, ordinal)
        EffectKind.ZoomIn -> glyph(SquishIcons.ZoomIn, 0xFF4FD1FF, 0xFF4F6BFF, ordinal)
        EffectKind.Glitch -> glyph(SquishIcons.Glitch, 0xFF3A2D7A, 0xFF12103A, ordinal)
        EffectKind.Flash -> glyph(SquishIcons.Flash, 0xFF7B5CFF, 0xFF3A2DB0, ordinal)
        EffectKind.Vhs -> glyph(SquishIcons.Vhs, 0xFFFF9A62, 0xFFB5476E, ordinal)
        EffectKind.Mono -> glyph(SquishIcons.Mono, 0xFF9AA0B8, 0xFF3A3E52, ordinal)
        EffectKind.Invert -> glyph(SquishIcons.Invert, 0xFFFF7AC6, 0xFF7B5CFF, ordinal)
        EffectKind.Blur -> glyph(SquishIcons.Blur, 0xFF9AB6FF, 0xFF5A6ACF, ordinal)
        EffectKind.Rainbow -> glyph(SquishIcons.Rainbow, 0xFF4FD1FF, 0xFF3A6BFF, ordinal)
    }

val VoiceEffect.glyph: Glyph
    get() = when (this) {
        VoiceEffect.None -> glyph(SquishIcons.Mic, 0xFF8A90AE, 0xFF4A4F6E, ordinal)
        VoiceEffect.Chipmunk -> glyph(SquishIcons.Chipmunk, 0xFF3DE0C0, 0xFF1F8A70, ordinal)
        VoiceEffect.Deep -> glyph(SquishIcons.Deep, 0xFF6C7BFF, 0xFF2B2F8F, ordinal)
        VoiceEffect.Robot -> glyph(SquishIcons.Robot, 0xFF4FA8FF, 0xFF2F4FC0, ordinal)
        VoiceEffect.Echo -> glyph(SquishIcons.Echo, 0xFFB14DFF, 0xFF5B2DB0, ordinal)
        VoiceEffect.Radio -> glyph(SquishIcons.Radio, 0xFFFF8FB1, 0xFFB14DFF, ordinal)
    }

val QuickTool.glyph: Glyph
    get() = when (this) {
        QuickTool.Squeeze -> glyph(SquishIcons.Squeeze, 0xFF5B8CFF, 0xFF8B5CF6, ordinal)
        QuickTool.Snip -> glyph(SquishIcons.Snip, 0xFF2EE6D6, 0xFF2F80ED, ordinal)
        QuickTool.Rip -> glyph(SquishIcons.Rip, 0xFFFF9F3D, 0xFFFF4F6A, ordinal)
        QuickTool.Stitch -> glyph(SquishIcons.Stitch, 0xFFFF5FA2, 0xFFB14DFF, ordinal)
    }

private val StickerInk = Color(0xFF140C26)

/**
 * The sticker: a gradient squircle with an ink rim, a gloss along the top and
 * a hard shadow knocked down and right, leaning at the glyph's tilt.
 */
@Composable
fun GlyphTile(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = size * 0.72f,
    tilt: Float = glyph.tilt
) {
    val shape = RoundedCornerShape(size * 0.34f)
    val drop = (size.value * 0.06f).coerceAtLeast(1.5f).dp
    Box(modifier = modifier.size(size).rotate(tilt), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .offset(drop, drop)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.55f))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Brush.linearGradient(listOf(glyph.from, glyph.to)))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)))
                .border(1.5.dp, StickerInk, shape),
            contentAlignment = Alignment.Center
        ) {
            Image(glyph.icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}
