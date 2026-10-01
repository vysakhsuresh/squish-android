package com.squish.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

@Composable
fun SquishPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SquishColors.Primary,
            contentColor = SquishColors.Background,
            disabledContainerColor = SquishColors.Primary.copy(alpha = 0.4f),
            disabledContentColor = SquishColors.Background.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun SquishOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    /** A mark before the words, where one says it better than a glyph in the text would. */
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, SquishColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SquishColors.TextSecondary),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * The button that actually destroys something.
 *
 * Its own colour, because a delete that looks like every other button gets
 * pressed like every other button. Paired with [SquishOutlinedButton] carrying
 * the way out, never on its own.
 */
@Composable
fun SquishDangerButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SquishColors.Pink,
            contentColor = SquishColors.Background
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * The main action inside a tool sheet - Add text, Add from your files.
 *
 * Not [SquishPrimaryButton]: the sheet's Done is the one solid orange there,
 * and a full-width orange bar under it read as two primaries of two sizes
 * fighting (seen on the Text and Sound sheets). This is the tool's own
 * colour, tinted, with a mark: plainly the thing to press in the sheet, and
 * plainly not the way out of it.
 */
@Composable
fun SheetActionButton(
    text: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.16f),
            contentColor = accent,
            disabledContainerColor = accent.copy(alpha = 0.06f),
            disabledContentColor = accent.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}
