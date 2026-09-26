package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A vector mark on its own gradient tile - how Squish shows a template, an
 * effect or a voice, in place of emoji, which render differently on every phone
 * and read as a chat message rather than a tool.
 */
data class Glyph(val icon: ImageVector, val from: Color, val to: Color)

private fun glyph(icon: ImageVector, from: Long, to: Long) = Glyph(icon, Color(from), Color(to))

val Template.glyph: Glyph
    get() = when (this) {
        Template.Reel -> glyph(Icons.Rounded.Smartphone, 0xFFFF5F8F, 0xFF8B5CF6)
        Template.Vlog -> glyph(Icons.Rounded.Videocam, 0xFFFFB547, 0xFFFF6A3D)
        Template.Cinematic -> glyph(Icons.Rounded.Movie, 0xFF4F7BFF, 0xFF1E2A78)
        Template.Retro -> glyph(Icons.Rounded.Voicemail, 0xFFFF9A62, 0xFFB5476E)
        Template.Party -> glyph(Icons.Rounded.Celebration, 0xFF22E3C4, 0xFFB14DFF)
        Template.Memories -> glyph(Icons.Rounded.PhotoAlbum, 0xFFB8BCCB, 0xFF4A4E63)
    }

val EffectKind.glyph: Glyph
    get() = when (this) {
        EffectKind.Shake -> glyph(Icons.Rounded.Vibration, 0xFFFF7A59, 0xFFE83E8C)
        EffectKind.Punch -> glyph(Icons.Rounded.CenterFocusStrong, 0xFFFFC53D, 0xFFFF5B3A)
        EffectKind.ZoomIn -> glyph(Icons.Rounded.ZoomIn, 0xFF4FD1FF, 0xFF4F6BFF)
        EffectKind.Glitch -> glyph(Icons.Rounded.BrokenImage, 0xFF00F0C8, 0xFFFF2E93)
        EffectKind.Flash -> glyph(Icons.Rounded.FlashOn, 0xFFFFE259, 0xFFFFA751)
        EffectKind.Vhs -> glyph(Icons.Rounded.Voicemail, 0xFFFF9A62, 0xFFB5476E)
        EffectKind.Mono -> glyph(Icons.Rounded.Contrast, 0xFFD4D7E2, 0xFF3A3E52)
        EffectKind.Invert -> glyph(Icons.Rounded.InvertColors, 0xFF7CF0FF, 0xFF7B5CFF)
        EffectKind.Blur -> glyph(Icons.Rounded.BlurOn, 0xFF9AB6FF, 0xFF5A6ACF)
        EffectKind.Rainbow -> glyph(Icons.Rounded.Palette, 0xFFFF5FA2, 0xFF3DD6FF)
    }

val VoiceEffect.glyph: Glyph
    get() = when (this) {
        VoiceEffect.None -> glyph(Icons.Rounded.Mic, 0xFF8A90AE, 0xFF4A4F6E)
        VoiceEffect.Chipmunk -> glyph(Icons.Rounded.Pets, 0xFFFFC05A, 0xFFFF7A3D)
        VoiceEffect.Deep -> glyph(Icons.Rounded.GraphicEq, 0xFF6C7BFF, 0xFF2B2F8F)
        VoiceEffect.Robot -> glyph(Icons.Rounded.SmartToy, 0xFF4FF0D2, 0xFF2F80ED)
        VoiceEffect.Echo -> glyph(Icons.Rounded.Terrain, 0xFF7EE08A, 0xFF1F8A70)
        VoiceEffect.Radio -> glyph(Icons.Rounded.Radio, 0xFFFF8FB1, 0xFFB14DFF)
    }

/** The tile itself: gradient corner to corner, a light along the top, white mark. */
@Composable
fun GlyphTile(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 40.dp, iconSize: Dp = size * 0.55f) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(glyph.from, glyph.to)))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(glyph.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}
