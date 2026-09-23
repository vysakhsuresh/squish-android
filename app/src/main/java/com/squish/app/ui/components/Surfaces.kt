package com.squish.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * A two-stop sweep from any accent toward the next hue round the brand's wheel.
 *
 * Derived rather than hand-picked per component, so a card, a chip and a badge
 * tinted with the same accent are visibly the same family - and adding a colour
 * later does not mean hunting down every gradient that should have included it.
 */
fun accentSweep(accent: Color): Brush = Brush.linearGradient(
    listOf(accent, blendToward(accent, SquishColors.Violet, 0.45f))
)

private fun blendToward(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f
)

/**
 * A panel. The hairline is the whole trick: a card that is only a lighter
 * rectangle disappears into a dark background, where one crisp edge makes it a
 * surface. An accent adds a tinted edge so a section is identifiable at a glance
 * rather than by reading its title.
 */
@Composable
fun SquishCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SquishColors.Surface)
            .border(
                width = 1.dp,
                color = accent?.copy(alpha = 0.35f) ?: SquishColors.Border,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

/** A tinted square holding an icon: a section's colour, at a size you can see. */
@Composable
fun AccentBadge(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.5f))
    }
}

/** A numbered badge, for anything whose order is the point. */
@Composable
fun OrderBadge(number: Int, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(accentSweep(accent)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = SquishColors.Background
        )
    }
}

/** A heading with its section's colour beside it. */
@Composable
fun SectionHeading(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    accent: Color = SquishColors.Primary,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) AccentBadge(icon = icon, accent = accent)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        }
        trailing?.invoke()
    }
}
