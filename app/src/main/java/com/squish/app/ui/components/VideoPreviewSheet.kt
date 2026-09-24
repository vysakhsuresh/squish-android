package com.squish.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.theme.SquishColors

/**
 * Watch a file without leaving the list you found it in.
 *
 * A library that only shows a still and a filename makes you open a thing to
 * find out whether it is the thing - and opening it means leaving the list,
 * losing your place in it, and coming back to the top. This puts the picture over
 * the list instead: play it, decide, dismiss, and the list is exactly where it
 * was, still scrolled where it was.
 *
 * Reuses [ClipPreview], which already owns a player, releases it on the way out
 * and carries its own transport - so a preview here and a preview inside a tool
 * behave identically, because they are the same thing.
 */
@Composable
fun VideoPreviewSheet(
    title: String,
    subtitle: String,
    uri: Uri,
    durationMs: Long,
    accent: Color,
    onDismiss: () -> Unit,
    /**
     * The picture's shape. Left at zero it is measured off the file, which is what
     * a draft needs - nothing has recorded a draft's dimensions yet, and guessing
     * 16:9 stretches every portrait clip sideways.
     */
    aspect: Float = 0f,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var measured by remember(uri) { mutableStateOf(aspect) }

    LaunchedEffect(uri, aspect) {
        if (aspect > 0f) return@LaunchedEffect
        val meta = ThumbnailExtractor.probe(context, uri)
        if (meta.displayWidth > 0 && meta.displayHeight > 0) {
            measured = meta.displayWidth.toFloat() / meta.displayHeight
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // The default width is meant for a paragraph of text, and a video in it is
        // a postage stamp. The picture is the whole point here, so it gets the room.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, SquishColors.Border, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SquishColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SquishColors.Background)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close preview",
                        tint = SquishColors.TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            ClipPreview(
                sources = listOf(PreviewSource(uri = uri, durationMs = durationMs, label = title)),
                accent = accent,
                // Held at 16:9 only until the file has been measured. Committing to
                // a shape before then would show one wrong frame and then jump.
                aspect = if (measured > 0f) measured else 16f / 9f,
                modifier = Modifier.fillMaxWidth()
            )

            if (actionLabel != null && onAction != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SquishOutlinedButton(
                        text = "Close",
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss
                    )
                    SquishPrimaryButton(
                        text = actionLabel,
                        modifier = Modifier.weight(1f),
                        onClick = onAction
                    )
                }
            }
        }
    }
}
