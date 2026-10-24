package com.squish.app.ui.components

import com.squish.app.editor.Timecode
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.squish.app.media.ExportProgress
import com.squish.app.media.ExportStage
import com.squish.app.ui.theme.SquishColors

/**
 * What the encoder is actually doing, while it does it.
 *
 * The percentage is the Transformer's own, not a timer dressed up as one: an
 * encode is nowhere near linear in wall time, so a bar that fakes it is a lie that
 * gets found out on the first long clip. When the encoder cannot yet report - the
 * first moments, or a pass it does not instrument - the bar says so by sweeping
 * instead of filling, rather than sitting at a made-up number.
 *
 * [cover] is a frame of what is being written, so the card is about this
 * export and not a bar in the abstract. [onCancel] puts Stop on the card - the
 * one way out used to be the back gesture, which nobody looks for while a bar
 * fills. It is not offered while the file is being copied into the gallery:
 * there is nothing left to stop then (see StopExportDialog).
 */
@Composable
fun ExportProgressCard(
    progress: ExportProgress,
    accent: Color = SquishColors.Cyan,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
    onCancel: (() -> Unit)? = null
) {
    // Promised only where it will be kept: from Android 13 the notification
    // needs a permission, and refused, the card used to send people to a
    // shade with nothing in it.
    val context = LocalContext.current
    val notifies = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Saving has no percentage of its own: the render is done, and what is left
    // is a copy into the gallery that Android does not report on. The bar stays
    // full rather than sweeping back as if the work had started again.
    val saving = progress.stage == ExportStage.Saving
    val fraction = if (saving) 1f else progress.fraction
    val eased by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(durationMillis = 220),
        label = "exportProgress"
    )

    SquishCard(modifier = modifier, accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cover != null) {
                val shape = (cover.width.toFloat() / cover.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(52.dp)
                        .width((52 * shape).dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SquishColors.Background)
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    when {
                        saving -> "Saving to gallery…"
                        progress.stage == ExportStage.Preparing -> "Getting ready…"
                        fraction == null -> "Starting the encoder…"
                        else -> "Rendering"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = SquishColors.TextPrimary
                )
                Text(
                    if (saving) "" else fraction?.let { "${(it * 100).toInt()}%" } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
        }

        ProgressTrack(fraction = fraction, eased = eased, accent = accent)

        // Said, not hidden (EDGE_PLAN B6). A hot phone slows the chip down,
        // and an export that suddenly runs at half speed - or a last stretch
        // the encoder does not report on - reads as one that hung, which is
        // the moment people kill the app and lose the render.
        val hot by com.squish.app.media.ExportService.hot.collectAsState()
        var quietSince by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        var quietFor by remember { mutableLongStateOf(0L) }
        LaunchedEffect(progress.fraction, progress.stage) { quietSince = android.os.SystemClock.elapsedRealtime(); quietFor = 0L }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                quietFor = android.os.SystemClock.elapsedRealtime() - quietSince
            }
        }
        val note = when {
            hot -> "Your phone is hot, so Android has slowed it down. The export is still running and will finish - it is just slower."
            !saving && fraction != null && quietFor > QUIET_NOTE_MS ->
                "Still working - the encoder has not reported for ${quietFor / 1000} s. Long effects and the last stretch of a file can do this; it has not stopped."
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow) }

        if (!saving) {
            Text(
                buildString {
                    append(Timecode.clock(progress.elapsedMs))
                    append(" elapsed")
                    progress.remainingMs?.let {
                        append("  ·  about ")
                        append(Timecode.clock(it))
                        append(" left")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        Text(
            if (saving) {
                "The video is finished. Copying it into your gallery - a big file takes a few seconds."
            } else {
                "Nothing is uploaded - the encoding is happening on this phone. It carries on with the " +
                    "screen locked" + if (notifies) "; the notification shows how far it has got." else "."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )

        if (onCancel != null && !saving) {
            SquishOutlinedButton(
                text = "Stop",
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            )
        }
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


/** How long the encoder may be quiet before the card says it is still working. */
private const val QUIET_NOTE_MS = 30_000L
