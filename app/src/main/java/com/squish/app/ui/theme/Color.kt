package com.squish.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sampled from the Squish logo, so the icon, the splash and the app are one piece.
 *
 * The mark runs cyan → blue → violet → magenta → orange on a deep navy ground.
 * Those hues carry meaning in the editor rather than being decoration, one per
 * concept and the same everywhere it appears - toolbar, track head, sheet title,
 * clip tint: video is violet, an overlay magenta, sound cyan, text and stickers
 * amber, effects and looks blue. Orange is the primary action and nothing else,
 * so it never has to compete with a track's colour. (Primary was blue while
 * looks and speed were blue too, and magenta was both effects and stickers; the
 * editor's own legend contradicted itself.)
 */
object SquishColors {
    val Background = Color(0xFF0A0E2D)
    val Surface = Color(0xFF141A3A)
    val SurfaceElevated = Color(0xFF1C2350)
    val Border = Color(0xFF2A3260)

    val TextPrimary = Color(0xFFF2F4FF)
    val TextSecondary = Color(0xFF9AA3CC)
    val TextMuted = Color(0xFF6B76A8)

    // Logo hues
    val Cyan = Color(0xFF3DE0C0)
    val Blue = Color(0xFF4A7BFF)
    val Violet = Color(0xFF8B5CF6)
    val Magenta = Color(0xFFF0477F)
    val Orange = Color(0xFFFF7A45)
    val Amber = Color(0xFFFFC53D)

    /** The primary action: Export, a sheet's Done, the play button. */
    val Primary = Orange

    /**
     * Destructive and failed: Delete, Remove, an error. Not a logo hue - it was
     * magenta, which became the overlay's colour, so on an overlay's toolbar
     * Delete wore the same colour as every other tool.
     */
    val Danger = Color(0xFFFF4F4F)

    // Semantic aliases used across the app
    val Teal = Cyan               // audio, savings, success
    val Purple = Violet           // video track, crop
    val Pink = Danger             // destructive, failure
    val Yellow = Amber            // markers, warnings, text track
}
