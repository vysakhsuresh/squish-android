package com.squish.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SquishColorScheme = darkColorScheme(
    primary = SquishColors.Primary,
    onPrimary = SquishColors.Background,
    secondary = SquishColors.Teal,
    onSecondary = SquishColors.Background,
    tertiary = SquishColors.Yellow,
    background = SquishColors.Background,
    onBackground = SquishColors.TextPrimary,
    surface = SquishColors.Surface,
    onSurface = SquishColors.TextPrimary,
    surfaceVariant = SquishColors.SurfaceElevated,
    outline = SquishColors.Border,
    error = Color(0xFFFF5C5C)
)

@Composable
fun SquishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SquishColorScheme,
        typography = SquishTypography,
        shapes = SquishShapes,
        content = content
    )
}
