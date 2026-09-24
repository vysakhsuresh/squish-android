package com.squish.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SquishOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, SquishColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SquishColors.TextSecondary),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
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
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
