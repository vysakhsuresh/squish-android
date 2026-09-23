package com.squish.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.squish.app.media.ExportProgress
import com.squish.app.ui.theme.SquishColors

/**
 * What the encoder is actually doing, while it does it.
 *
 * The percentage is the Transformer's own, not a timer dressed up as one: an
 * encode is nowhere near linear in wall time, so a bar that fakes it is a lie that
 * gets found out on the first long clip. When the encoder cannot yet report - the
 * first moments, or a pass it does not instrument - the bar says so by sweeping
 * instead of filling, rather than sitting at a made-up number.
 */
@Composable
fun ExportProgressCard(
    progress: ExportProgress,
    accent: Color = SquishColors.Cyan,
    modifier: Modifier = Modifier
) {
    val fraction = progress.fraction
    val eased by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 220),
        label = "exportProgress"
    )

    SquishCard(modifier = modifier, accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                if (fraction == null) "Starting the encoder…" else "Rendering",
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.TextPrimary
            )
            Text(
                fraction?.let { "${(it * 100).toInt()}%" } ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
        }

        ProgressTrack(fraction = fraction, eased = eased, accent = accent)

        Text(
            buildString {
                append(clockOf(progress.elapsedMs))
                append(" elapsed")
                progress.remainingMs?.let {
                    append("  ·  about ")
                    append(clockOf(it))
                    append(" left")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )

        Text(
            "Keep Squish open while this runs. Nothing is uploaded — the encoding " +
                "is happening on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )
    }
}

/**
 * The bar. Drawn by hand rather than with the stock indicator because it has two
 * states the stock one will not do together: a real fraction, and an honest "I do
 * not know yet" that still looks alive.
 */
@Composable
private fun ProgressTrack(fraction: Float?, eased: Float, accent: Color) {
    val shape = RoundedCornerShape(999.dp)
    // Indeterminate rests as a short bar at the left: honest about having no
    // number, without pretending to be a tenth done.
    val width = if (fraction == null) 0.18f else eased.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(SquishColors.Background)
    ) {
        if (width > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(width)
                    .height(10.dp)
                    .clip(shape)
                    .background(accentSweep(accent))
            )
        }
    }
}

/** "4:07" — minutes and seconds, which is the resolution anyone waiting cares about. */
private fun clockOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
