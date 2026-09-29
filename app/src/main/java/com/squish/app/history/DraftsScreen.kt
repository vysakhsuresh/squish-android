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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.squish.app.data.TrashedDraft
import com.squish.app.editor.Timecode
import com.squish.app.home.UndoOffer
import com.squish.app.home.agoOf
import androidx.compose.ui.semantics.Role
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.canReadMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.squish.app.tools.QuickTool
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.VideoPreviewSheet
import com.squish.app.ui.theme.SquishColors

/**
 * The quick tools' unfinished sessions, with a picture of each, and under
 * them the bin.
 *
 * The editor's projects live on the dashboard now, as a grid of their own;
 * what is left here is the other kind of half-done work - a merge set up and
 * not run, a cut half-made in Snip - and the bin both kinds go into.
 * Deleting moves a draft there rather than removing it, with an Undo the
 * moment it happens and a Restore for the month after; a project purged from
 * the bin is gone for good, and the app's right to read its files goes with
 * it unless another project names them.
 */
@Composable
fun DraftsScreen(
    drafts: List<DraftSummary>,
    trashed: List<TrashedDraft>,
    /** The set-aside just made, offered for undoing; null once that offer is over. */
    undoOffer: UndoOffer?,
    onBack: () -> Unit,
    onOpenEdit: (DraftSummary) -> Unit,
    onOpenTool: (DraftSummary) -> Unit,
    onDiscard: (DraftSummary) -> Unit,
    /** Puts the draft's earlier version back; see [DraftSummary.earlierSavedAtMillis]. */
    onRevert: (DraftSummary) -> Unit,
    onRestore: (TrashedDraft) -> Unit,
    onPurge: (TrashedDraft) -> Unit,
    onDismissUndoOffer: () -> Unit
) {
    var previewing by remember { mutableStateOf<DraftSummary?>(null) }
    var pendingDiscard by remember { mutableStateOf<DraftSummary?>(null) }
    var pendingRevert by remember { mutableStateOf<DraftSummary?>(null) }
    var pendingPurge by remember { mutableStateOf<TrashedDraft?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun open(draft: DraftSummary) {
        if (draft.toolId != null) onOpenTool(draft) else onOpenEdit(draft)
    }

    // The undo, right where the thumb is. A delete used to be final the moment
    // the dialog closed; now it is a snackbar's length from being nothing.
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = offer.message,
            actionLabel = "Undo",
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) offer.entries.forEach(onRestore)
        onDismissUndoOffer()
    }
    // Leaving mid-snackbar cancels the effect above before it can clear the
    // offer, and the view model holding it outlives this screen - so the Undo
    // came back on a later visit, when the slot might hold a newer draft that
    // restoring would have swapped out.
    DisposableEffect(Unit) { onDispose { onDismissUndoOffer() } }

    Scaffold(
        containerColor = SquishColors.Background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
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
                        if (drafts.isEmpty()) "No tool session waiting — projects are on the dashboard"
                        else "${drafts.size} tool ${if (drafts.size == 1) "session" else "sessions"} saved as you worked",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextMuted
                    )
                }

                if (drafts.isEmpty() && trashed.isEmpty()) {
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
                                "A quick tool you walk away from lands here and picks up where " +
                                    "it stopped; anything deleted waits here for 30 days.",
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
                                onDiscard = { pendingDiscard = draft },
                                onEarlier = { pendingRevert = draft }
                            )
                        }

                        if (trashed.isNotEmpty()) {
                            item(key = "trash-heading") {
                                Column(
                                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        "Recently deleted",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = SquishColors.TextPrimary
                                    )
                                    Text(
                                        "Kept for 30 days, then gone for good",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SquishColors.TextMuted
                                    )
                                }
                            }
                            items(trashed, key = { "trash/${it.trashId}" }) { entry ->
                                TrashedCard(
                                    entry = entry,
                                    onRestore = { onRestore(entry) },
                                    onPurge = { pendingPurge = entry }
                                )
                            }
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

    pendingRevert?.let { draft ->
        val earlier = draft.earlierSavedAtMillis
        ConfirmDialog(
            title = "Go back to the earlier version?",
            body = "The version of \"${draft.title}\" saved " +
                "${earlier?.let(::agoOf) ?: "earlier"} takes the place of the one saved ${agoOf(draft.savedAtMillis)}.",
            caution = "The version you have now moves to Recently deleted, below, for 30 days, " +
                "so this can be undone.",
            confirmLabel = "Go back",
            dismissLabel = "Cancel",
            icon = Icons.Filled.History,
            accent = SquishColors.Cyan,
            onConfirm = {
                onRevert(draft)
                pendingRevert = null
            },
            onDismiss = { pendingRevert = null }
        )
    }

    pendingDiscard?.let { draft ->
        ConfirmDialog(
            title = "Delete \"${draft.title}\"?",
            body = draft.toolId?.let { id ->
                "This sets aside the ${QuickTool.fromId(id).title.lowercase()} you had " +
                    "set up — ${draft.clipCount} " +
                    "${if (draft.clipCount == 1) "file, and the settings on it." else "files, and the settings on them."}"
            } ?: (
                "This sets aside the edit in progress — " +
                    "${draft.clipCount} ${if (draft.clipCount == 1) "clip" else "clips"}, " +
                    "with every cut, look and caption on it."
                ),
            caution = "It moves to Recently deleted, below, for 30 days. " +
                "Your original video is untouched either way.",
            confirmLabel = "Delete",
            onConfirm = {
                onDiscard(draft)
                pendingDiscard = null
            },
            onDismiss = { pendingDiscard = null }
        )
    }

    pendingPurge?.let { entry ->
        ConfirmDialog(
            title = "Delete \"${entry.draft.title}\" for good?",
            body = "This takes it out of Recently deleted before its 30 days are up.",
            caution = "There is no undo and nowhere to fetch it back from. " +
                "Your original video is untouched; the work built on it is not.",
            // "for good" is in the title; on a half-width button it was cut to "Delete for".
            confirmLabel = "Delete",
            onConfirm = {
                onPurge(entry)
                pendingPurge = null
            },
            onDismiss = { pendingPurge = null }
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
    onDiscard: () -> Unit,
    onEarlier: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(draft.sourceUri) { mutableStateOf<Bitmap?>(null) }
    // Null until checked. A tool session whose video can no longer be opened -
    // deleted, or picked before the app kept its access - cannot be resumed,
    // and opening it used to crash the app. (A project's missing file is the
    // editor's to name and relink, so a project is always openable.)
    var readable by remember(draft.sourceUri) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(draft.sourceUri) {
        readable = if (draft.toolId == null) true else withContext(Dispatchers.IO) { context.canReadMedia(draft.sourceUri) }
        if (readable != true) return@LaunchedEffect
        // Remembered across scrolls and restarts: each row used to decode a
        // frame afresh every time it came back into view.
        thumb = ThumbnailCache.frame(context, draft.coverUri ?: draft.sourceUri, draft.coverAtMs)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Cyan.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .clickable(enabled = readable != false, role = Role.Button, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Thumb(thumb = thumb, toolDraft = draft.toolId != null)

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
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (readable == false) {
                Text(
                    "The video can't be opened any more — remove this with ✕",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    maxLines = 2
                )
            } else if (draft.durationMs > 0 || draft.exportedAtMillis != null) {
                // The badge on this line, not beside the name: there it cut the
                // name to "Video · 30...". An exported edit stays in the list - it is
                // the one most likely to be opened for one more change.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.durationMs > 0) Text(
                        Timecode.format(draft.durationMs).removeSuffix(".000"),
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted.copy(alpha = 0.7f)
                    )
                    if (draft.exportedAtMillis != null) ExportedBadge(editedSince = draft.editedSinceExport)
                }
            }
            // The snapshot that falls behind on purpose, offered by name. Kept on
            // disk and never reachable, it protected nothing: a run of bad saves
            // is only recoverable if someone can ask for the version before it.
            draft.earlierSavedAtMillis?.let { earlier ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onEarlier)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = SquishColors.Cyan, modifier = Modifier.size(13.dp))
                    Text(
                        "Earlier version · ${agoOf(earlier)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Cyan,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (readable != false) {
            CardAction(Icons.Filled.PlayArrow, "Preview ${draft.title}", SquishColors.Cyan, onPreview)
        }
        CardAction(Icons.Filled.Close, "Delete ${draft.title}", SquishColors.Pink, onDiscard)
    }
}

/** A draft in the bin: dimmer than a live one, with the way back on the right. */
@Composable
private fun TrashedCard(
    entry: TrashedDraft,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val draft = entry.draft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface.copy(alpha = 0.6f))
            .border(1.dp, SquishColors.Border, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Thumb(thumb = null, toolDraft = draft.toolId != null)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                draft.title,
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${draft.describe()} · deleted ${agoOf(entry.discardedAtMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        CardAction(Icons.Filled.RestoreFromTrash, "Restore ${draft.title}", SquishColors.Cyan, onRestore)
        CardAction(Icons.Filled.Close, "Delete ${draft.title} now", SquishColors.Pink, onPurge)
    }
}

@Composable
private fun ExportedBadge(editedSince: Boolean) {
    Text(
        if (editedSince) "Exported · edited since" else "Exported",
        style = MaterialTheme.typography.labelSmall,
        color = SquishColors.Cyan,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SquishColors.Cyan.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun Thumb(thumb: Bitmap?, toolDraft: Boolean) {
    Box(
        modifier = Modifier
            .width(78.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
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
                if (toolDraft) Icons.Filled.Bolt else Icons.Filled.Edit,
                contentDescription = null,
                tint = SquishColors.Cyan,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * A row's button: drawn at 36 dp, pressed at 48 dp. Custom clickables get none of
 * Material's minimum touch size, and at 34 dp these were the smallest targets
 * in the app; the padding round the drawn square is part of the target, and
 * the role tells a screen reader what it is.
 */
@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}
