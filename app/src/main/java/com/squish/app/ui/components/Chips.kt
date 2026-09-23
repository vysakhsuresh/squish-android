package com.squish.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = SquishColors.Primary,
    onClick: () -> Unit
) {
    // A flat fill reads as a button that happens to be on; a sweep reads as the
    // chosen one. Since every panel in the app builds its options out of this, the
    // difference is the difference between the whole editor looking inert or alive.
    val scale by animateFloatAsState(if (selected) 1.03f else 1f, spring(), label = "chipScale")
    val border by animateColorAsState(
        if (selected) Color.Transparent else SquishColors.Border, label = "chipBorder"
    )
    val content by animateColorAsState(
        if (selected) SquishColors.Background else SquishColors.TextSecondary, label = "chipContent"
    )

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(13.dp))
            .then(
                if (selected) Modifier.background(accentSweep(accentColor))
                else Modifier.background(SquishColors.Background)
            )
            .border(1.5.dp, border, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun SquishToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(if (checked) SquishColors.Primary else SquishColors.Border, label = "switchTrack")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(SquishColors.TextPrimary)
        )
    }
}
