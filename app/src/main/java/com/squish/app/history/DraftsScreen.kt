package com.squish.app.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.data.DraftSummary
import com.squish.app.editor.Timecode
import com.squish.app.home.agoOf
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.tools.QuickTool
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.VideoPreviewSheet
import com.squish.app.ui.theme.SquishColors

/**
 * Everything left unfinished, with a picture of it.
 *
 * This was a list stacked on the dashboard, which is the same mistake the export
 * history made before it moved out: the home screen got longer every time anyone
 * left something half-done, and the list still could not show what any of it
 * was. On its own screen it can do what the library does - a frame from the
 * footage, a preview that plays without leaving the list, and one way back in.
 *
 * Edits and tool sessions sit in one list on purpose. Someone who left a merge
 * half-set-up and someone who left a cut half-made came back for the same reason,
 * and sorting their work by which screen made it would help nobody.
 */
@Composable
fun DraftsScreen(
    drafts: List<DraftSummary>,
    onBack: () -> Unit,
    onOpenEdit: (DraftSummary) -> Unit,
    onOpenTool: (QuickTool) -> Unit,
    onDiscard: (DraftSummary) -> Unit
) {
    var previewing by remember { mutableStateOf<DraftSummary?>(null) }
    var pendingDiscard by remember { mutableStateOf<DraftSummary?>(null) }

    fun open(draft: DraftSummary) {
        val tool = draft.toolId?.let { QuickTool.fromId(it) }
        if (tool != null) onOpenTool(tool) else onOpenEdit(draft)
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Unfinished",
                        style = MaterialTheme.typography.displayLarge,
                        color = SquishColors.TextPrimary
                    )
                    Text(
                        if (drafts.isEmpty()) "Nothing waiting — everything is finished"
                        else "${drafts.size} saved automatically as you worked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextMuted
                    )
                }

                if (drafts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier.padding(horizontal = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Nothing left half-done",
                                style = MaterialTheme.typography.titleSmall,
                                color = SquishColors.TextPrimary
                            )
                            Text(
                                "Anything you walk away from lands here, whichever screen " +
                                    "you left it on, and picks up where it stopped.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SquishColors.TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 108.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(drafts, key = { "${it.toolId}/${it.id}" }) { draft ->
                            DraftCard(
                                draft = draft,
                                onOpen = { open(draft) },
                                onPreview = { previewing = draft },
                                onDiscard = { pendingDiscard = draft }
                            )
                        }
                    }
                }
            }

            BackOrb(
                accent = SquishColors.Cyan,
                onClick = onBack,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)
            )
        }
    }

    previewing?.let { draft ->
        VideoPreviewSheet(
            title = draft.title,
            subtitle = draft.describe(),
            uri = draft.sourceUri,
            durationMs = draft.durationMs,
            accent = SquishColors.Cyan,
            actionLabel = "Continue",
            onAction = {
                previewing = null
                open(draft)
            },
            onDismiss = { previewing = null }
        )
    }

    pendingDiscard?.let { draft ->
        ConfirmDialog(
            title = "Discard \"${draft.title}\"?",
            body = draft.toolId?.let { id ->
                "This throws away the ${QuickTool.fromId(id).title.lowercase()} you had " +
                    "set up — ${draft.clipCount} " +
                    "${if (draft.clipCount == 1) "file" else "files"}, and the settings on them."
            } ?: (
                "This throws away the edit in progress — " +
                    "${draft.clipCount} ${if (draft.clipCount == 1) "clip" else "clips"}, " +
                    "with every cut, look and caption on it."
                ),
            caution = "There is no undo and no bin to fetch it back from. " +
                "Your original video is untouched; the work built on it is not.",
            confirmLabel = "Discard",
            onConfirm = {
                onDiscard(draft)
                pendingDiscard = null
            },
            onDismiss = { pendingDiscard = null }
        )
    }
}

/** "Merge · 4 files · 2 hours ago" — what it is, how big, and how stale. */
private fun DraftSummary.describe(): String = buildString {
    toolId?.let {
        append(QuickTool.fromId(it).title)
        append(" · ")
    }
    append(clipCount)
    append(if (clipCount == 1) " clip · " else " clips · ")
    append(agoOf(savedAtMillis))
}

@Composable
private fun DraftCard(
    draft: DraftSummary,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onDiscard: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(draft.sourceUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(draft.sourceUri) {
        thumb = ThumbnailExtractor.frameAt(
            context,
            draft.sourceUri,
            (draft.durationMs / 3).coerceAtLeast(0L)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Cyan.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(78.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SquishColors.Background),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // The badge sits over the frame either way: it says which kind of
            // unfinished thing this is, which the picture alone cannot.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(SquishColors.Background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (draft.toolId != null) Icons.Filled.Bolt else Icons.Filled.Edit,
                    contentDescription = null,
                    tint = SquishColors.Cyan,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                draft.title,
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                draft.describe(),
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 1
            )
            if (draft.durationMs > 0) {
                Text(
                    Timecode.format(draft.durationMs).removeSuffix(".000"),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted.copy(alpha = 0.7f)
                )
            }
        }

        CardAction(Icons.Filled.PlayArrow, "Preview ${draft.title}", SquishColors.Cyan, onPreview)
        CardAction(Icons.Filled.Close, "Discard ${draft.title}", SquishColors.Pink, onDiscard)
    }
}

@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SquishColors.Background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}
