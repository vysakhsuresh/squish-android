package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.media.SquishError
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A failure the user can read and act on: what happened, why, and the one thing
 * that fixes it. Never a stack trace, never "Export failed", and never a frozen
 * screen with no explanation at all.
 */
@Composable
fun FailureCard(error: SquishError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Magenta, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = SquishColors.Magenta,
                modifier = Modifier.size(18.dp)
            )
            Text(error.title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }
        Text(error.detail, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
        Text(
            error.fix,
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.Cyan
        )
        SquishOutlinedButton(text = "Dismiss", onClick = onDismiss)
    }
}

/**
 * The parachute opening. An edit survived the app being killed, and it is offered
 * rather than applied - quietly overwriting the clip someone just opened would be
 * its own kind of data loss.
 */
@Composable
fun RecoveryBanner(
    offer: RecoveryOffer,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.SurfaceElevated)
            .border(1.dp, SquishColors.Amber, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.HistoryToggleOff,
                contentDescription = null,
                tint = SquishColors.Amber,
                modifier = Modifier.size(18.dp)
            )
            Text("Unsaved edit found", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }

        Text(
            buildString {
                append(offer.clipCount)
                append(if (offer.clipCount == 1) " clip, " else " clips, ")
                append(Timecode.format(offer.durationMs))
                append(" of edit, saved ")
                append(relativeTime(offer.savedAtMillis))
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquishOutlinedButton(text = "Restore it", onClick = onRestore)
            SquishOutlinedButton(text = "Start fresh", onClick = onDiscard)
        }
    }
}

/**
 * Only ever shown for footage heavy enough to need it, and only while it matters.
 * Says plainly that the preview is the low-resolution copy and the export is not.
 */
@Composable
fun ProxyIndicator(status: ProxyStatus, modifier: Modifier = Modifier) {
    if (status == ProxyStatus.NotNeeded) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (status == ProxyStatus.Building) {
            CircularProgressIndicator(
                color = SquishColors.Cyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            when (status) {
                ProxyStatus.Building -> "Building a light preview copy — editing stays responsive while it works"
                ProxyStatus.Ready -> "Previewing at 540p for smooth scrubbing · exports at full resolution"
                ProxyStatus.Failed -> "Playing the original — scrubbing may stutter on footage this large"
                ProxyStatus.NotNeeded -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (status == ProxyStatus.Failed) SquishColors.TextMuted else SquishColors.Cyan
        )
    }
}

private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return "recently"
    val elapsed = System.currentTimeMillis() - millis
    return when {
        elapsed < 60_000 -> "moments ago"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} h ago"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
