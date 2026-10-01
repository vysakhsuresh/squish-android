package com.squish.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    // Blue, not the orange of the primary action: a chip left on its default
    // was a second orange in a sheet whose Done is the orange one.
    accentColor: Color = SquishColors.Blue,
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
            // Finger-sized: the chips are most of the editor's controls, and at
            // 37dp they were the most-missed.
            .heightIn(min = 44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.background(accentSweep(accentColor))
                else Modifier.background(SquishColors.Background)
            )
            .border(1.5.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            // 8 at the sides: at 12, four to a row on a 360dp phone cut "Camera"
            // and "Bottom" short.
            .padding(vertical = 8.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ellipsis rather than the default clip: a label a few pixels too
        // wide for a narrow phone lost its last letter mid-glyph.
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SquishToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The track when on: a switch inside a tool takes that tool's colour. */
    accent: Color = SquishColors.Primary
) {
    val trackColor by animateColorAsState(if (checked) accent else SquishColors.Border, label = "switchTrack")
    Box(
        modifier = modifier
            // Drawn 44x26, pressed anywhere in a 48dp square round it.
            .minimumInteractiveComponentSize()
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
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
