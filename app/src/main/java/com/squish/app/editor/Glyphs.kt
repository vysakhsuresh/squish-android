package com.squish.app.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.ZoomIn
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
 * app shows a template, a voice or a quick tool. Emoji render
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
        Template.Travel -> glyph(SquishIcons.Vlog, 0xFF3DD6E0, 0xFF1F8AC0, ordinal)
        Template.Birthday -> glyph(SquishIcons.Party, 0xFFFFD166, 0xFFFF5FA2, ordinal)
        Template.Food -> glyph(SquishIcons.Vlog, 0xFFFF9A3D, 0xFFC0392B, ordinal)
        Template.Fitness -> glyph(SquishIcons.Reel, 0xFFFF5B5B, 0xFF2B2F8F, ordinal)
        Template.Gaming -> glyph(SquishIcons.Retro, 0xFF7CFF6B, 0xFF5B2DB0, ordinal)
        Template.Love -> glyph(SquishIcons.Memories, 0xFFFF8FB1, 0xFFB14DFF, ordinal)
        Template.News -> glyph(SquishIcons.Cinematic, 0xFFE0E4F0, 0xFFC0392B, ordinal)
        Template.Sale -> glyph(SquishIcons.Party, 0xFFFFC53D, 0xFFFF6A3D, ordinal)

        // The twenty-eight added on 4 October. Six marks between forty-two
        // tiles, so the colours are what tell them apart - each pair taken from
        // the look the template applies, so the tile is roughly the colour the
        // picture will come out.
        Template.Trailer -> glyph(SquishIcons.Cinematic, 0xFF9AA7C7, 0xFF11162E, ordinal)
        Template.Thriller -> glyph(SquishIcons.Cinematic, 0xFF5EC8D8, 0xFF16213A, ordinal)
        Template.Western -> glyph(SquishIcons.Cinematic, 0xFFE0A765, 0xFF8A4A22, ordinal)
        Template.Noir -> glyph(SquishIcons.Cinematic, 0xFFD6D9E3, 0xFF2A2C36, ordinal)
        Template.Documentary -> glyph(SquishIcons.Cinematic, 0xFFBFC6D4, 0xFF4C5468, ordinal)
        Template.Wedding -> glyph(SquishIcons.Memories, 0xFFF6D7C4, 0xFFC98D8D, ordinal)
        Template.Baby -> glyph(SquishIcons.Memories, 0xFFFFD9E8, 0xFFA5C8F0, ordinal)
        Template.Pets -> glyph(SquishIcons.Memories, 0xFFFFC46B, 0xFFE2605B, ordinal)
        Template.Sunset -> glyph(SquishIcons.Vlog, 0xFFFFB05C, 0xFFD2456B, ordinal)
        Template.Beach -> glyph(SquishIcons.Vlog, 0xFF6FE3E1, 0xFF2A7FD4, ordinal)
        Template.Nature -> glyph(SquishIcons.Vlog, 0xFF7ED98A, 0xFF1F6B4A, ordinal)
        Template.Desert -> glyph(SquishIcons.Vlog, 0xFFF0C987, 0xFFB06B3C, ordinal)
        Template.Night -> glyph(SquishIcons.Reel, 0xFF6C7BFF, 0xFF11143A, ordinal)
        Template.City -> glyph(SquishIcons.Reel, 0xFF8FA8C8, 0xFF2B3550, ordinal)
        Template.Y2k -> glyph(SquishIcons.Retro, 0xFF9CFFEB, 0xFFFF6BD6, ordinal)
        Template.Vhs -> glyph(SquishIcons.Retro, 0xFFB8A0FF, 0xFF4A2D7A, ordinal)
        Template.Super8 -> glyph(SquishIcons.Retro, 0xFFE8B071, 0xFF8C5A2B, ordinal)
        Template.Polaroid -> glyph(SquishIcons.Retro, 0xFFF2E9D8, 0xFFA8A090, ordinal)
        Template.Crt -> glyph(SquishIcons.Retro, 0xFF77FF9E, 0xFF14321F, ordinal)
        Template.Trippy -> glyph(SquishIcons.Party, 0xFFFF7BE5, 0xFF5BE1FF, ordinal)
        Template.Workout -> glyph(SquishIcons.Reel, 0xFF9EE6FF, 0xFF1E3A5F, ordinal)
        Template.Sport -> glyph(SquishIcons.Reel, 0xFFFFE066, 0xFF1F7A4C, ordinal)
        Template.Recipe -> glyph(SquishIcons.Vlog, 0xFFFFC078, 0xFFB03A2E, ordinal)
        Template.Product -> glyph(SquishIcons.Memories, 0xFFE9EEF7, 0xFF7B869C, ordinal)
        Template.Tutorial -> glyph(SquishIcons.Memories, 0xFFFFE066, 0xFF7A6A1F, ordinal)
        Template.Podcast -> glyph(SquishIcons.Vlog, 0xFFFFD2A1, 0xFF8A5A3C, ordinal)
        Template.Quote -> glyph(SquishIcons.Memories, 0xFFD8D4CC, 0xFF5A564E, ordinal)
        Template.Follow -> glyph(SquishIcons.Party, 0xFFFF6BA8, 0xFFFFC46B, ordinal)
    }

/**
 * Effects wear the same plain line marks as the editor's tool rail, not
 * stickers: they sit in a grid of ten beside that rail and in a slim timeline
 * lane, where a quieter mark reads better than a crowd of little pictures.
 */
val EffectKind.icon: ImageVector
    get() = when (this) {
        EffectKind.Shake -> Icons.Filled.Vibration
        EffectKind.Punch -> Icons.Filled.CenterFocusStrong
        EffectKind.ZoomIn -> Icons.Filled.ZoomIn
        EffectKind.Glitch -> Icons.Filled.BrokenImage
        EffectKind.Flash -> Icons.Filled.FlashOn
        EffectKind.Vhs -> Icons.Filled.Voicemail
        EffectKind.Mono -> Icons.Filled.Contrast
        EffectKind.Invert -> Icons.Filled.InvertColors
        EffectKind.Blur -> Icons.Filled.BlurOn
        EffectKind.Rainbow -> Icons.Filled.Gradient
        EffectKind.RgbSplit -> Icons.Filled.Layers
        EffectKind.Strobe -> Icons.Filled.FlashAuto
        EffectKind.Earthquake -> Icons.Filled.Waves
        EffectKind.Heartbeat -> Icons.Filled.Favorite
        EffectKind.Static -> Icons.Filled.Tv
        EffectKind.OldFilm -> Icons.Filled.Movie
        EffectKind.Dream -> Icons.Filled.Cloud
        EffectKind.NegativePulse -> Icons.Filled.Exposure
        EffectKind.Trippy -> Icons.Filled.AutoAwesome
        EffectKind.Sway -> Icons.Filled.SwapHoriz
        EffectKind.ZoomOut -> Icons.Filled.ZoomOut
    }

/** Each effect's colour on the timeline, so neighbours on the one lane can be told apart. */
val EffectKind.color: Color
    get() = Color(
        when (this) {
            EffectKind.Shake -> 0xFFFF7A59
            EffectKind.Punch -> 0xFFFF5B7A
            EffectKind.ZoomIn -> 0xFF4FD1FF
            EffectKind.Glitch -> 0xFF3DE0C0
            EffectKind.Flash -> 0xFFFFC53D
            EffectKind.Vhs -> 0xFFFF9A62
            EffectKind.Mono -> 0xFFB8BCCB
            EffectKind.Invert -> 0xFFFF7AC6
            EffectKind.Blur -> 0xFF9AB6FF
            EffectKind.Rainbow -> 0xFFB14DFF
            EffectKind.RgbSplit -> 0xFFFF4F9A
            EffectKind.Strobe -> 0xFFFFF27A
            EffectKind.Earthquake -> 0xFFC9864A
            EffectKind.Heartbeat -> 0xFFFF4F6A
            EffectKind.Static -> 0xFF9AA3B8
            EffectKind.OldFilm -> 0xFFD9B37A
            EffectKind.Dream -> 0xFFC9B8FF
            EffectKind.NegativePulse -> 0xFF7AF0FF
            EffectKind.Trippy -> 0xFF7CFF6B
            EffectKind.Sway -> 0xFF6BC4FF
            EffectKind.ZoomOut -> 0xFF4FA8FF
        }
    )

val VoiceEffect.glyph: Glyph
    get() = when (this) {
        VoiceEffect.None -> glyph(SquishIcons.Mic, 0xFF8A90AE, 0xFF4A4F6E, ordinal)
        VoiceEffect.Enhance -> glyph(SquishIcons.Mic, 0xFF3DE0C0, 0xFF2F80ED, ordinal)
        VoiceEffect.Chipmunk -> glyph(SquishIcons.Chipmunk, 0xFF3DE0C0, 0xFF1F8A70, ordinal)
        VoiceEffect.Deep -> glyph(SquishIcons.Deep, 0xFF6C7BFF, 0xFF2B2F8F, ordinal)
        VoiceEffect.Robot -> glyph(SquishIcons.Robot, 0xFF4FA8FF, 0xFF2F4FC0, ordinal)
        VoiceEffect.Echo -> glyph(SquishIcons.Echo, 0xFFB14DFF, 0xFF5B2DB0, ordinal)
        VoiceEffect.Radio -> glyph(SquishIcons.Radio, 0xFFFF8FB1, 0xFFB14DFF, ordinal)
        VoiceEffect.Helium -> glyph(SquishIcons.Chipmunk, 0xFFFFD166, 0xFFFF8F3D, ordinal)
        VoiceEffect.Giant -> glyph(SquishIcons.Deep, 0xFF3DE0A0, 0xFF1F6A50, ordinal)
        VoiceEffect.Telephone -> glyph(SquishIcons.Radio, 0xFFB8BCCB, 0xFF4A4E63, ordinal)
        VoiceEffect.Megaphone -> glyph(SquishIcons.Radio, 0xFFFF7A59, 0xFFC0392B, ordinal)
        VoiceEffect.Cave -> glyph(SquishIcons.Echo, 0xFF6C7BFF, 0xFF1B1F5F, ordinal)
        VoiceEffect.Wobble -> glyph(SquishIcons.Echo, 0xFF5CE1E6, 0xFF2F80ED, ordinal)
        VoiceEffect.Alien -> glyph(SquishIcons.Robot, 0xFF7CFF6B, 0xFF2F8F3D, ordinal)
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
