package com.squish.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sampled from the Squish logo, so the icon, the splash and the app are one piece.
 *
 * The mark runs cyan → blue → violet → magenta → orange on a deep navy ground.
 * Those hues carry meaning in the editor rather than being decoration: video is
 * violet, audio is cyan, text is amber, effects are magenta, and the warm orange
 * is reserved for the primary action so it never competes with track colour.
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

    // Semantic aliases used across the app
    val Coral = Orange            // primary action
    val Teal = Cyan               // audio, savings, success
    val Purple = Violet           // video track, crop
    val Pink = Magenta            // destructive, effects
    val Yellow = Amber            // markers, warnings, text track
}
