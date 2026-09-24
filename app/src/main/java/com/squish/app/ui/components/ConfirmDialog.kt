package com.squish.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squish.app.ui.theme.SquishColors

/**
 * The one thing standing between a thumb and work that cannot be got back.
 *
 * Deliberately awkward in the small ways that matter. The way out is on the left,
 * where a thumb reaching for a list lands first, and the destructive button is on
 * the right in its own colour. Tapping outside or pressing back means keep, never
 * delete - a dialogue whose accidental dismissal destroys something is worse than
 * no dialogue at all, because it teaches people that dismissing is safe.
 *
 * [caution] is the sentence that says what cannot be undone. It is a separate
 * argument rather than another line of [body] so that it cannot be left out by
 * accident, and so it can be drawn in a colour that stops the eye.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    caution: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Keep",
    icon: ImageVector = Icons.Filled.DeleteForever,
    accent: Color = SquishColors.Pink
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SquishColors.TextPrimary
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    caution,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquishOutlinedButton(
                    text = dismissLabel,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                SquishDangerButton(
                    text = confirmLabel,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    }
}
