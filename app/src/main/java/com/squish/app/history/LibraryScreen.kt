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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.editor.Timecode
import com.squish.app.export.ShareUtils
import com.squish.app.home.formatSize
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.canReadMedia
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.VideoPreviewSheet
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything ever exported, with a picture of it.
 *
 * This was a list of filenames on the dashboard, which made the dashboard longer
 * every time anyone used the app and still did not let them find anything. On its
 * own screen it can do the two things a library is for: show you what a file is
 * without opening it, and let you search when there are eighty of them.
 *
 * A row whose file has been deleted from outside the app is shown greyed rather
 * than hidden - silently dropping it would look like the app lost the work.
 * Each export is one file, the gallery's (ExportRecord.mediaUri): the private
 * copy went once the gallery copy was verified.
 */
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SquishRepositories.history(context) }
    val records by repository.records.collectAsState()
    var query by remember { mutableStateOf("") }

    // Nothing is deleted from a tap. The tap only names what a second, deliberate
    // press would destroy, and the dialogue below says plainly that it is final.
    var pendingDelete by remember { mutableStateOf<ExportRecord?>(null) }

    // Watching one does not leave the list. The row that opened it is still under
    // the sheet, and the list is still scrolled exactly where it was.
    var previewing by remember { mutableStateOf<ExportRecord?>(null) }

    // A row whose file was deleted in the gallery opens nothing: it is
    // forgotten rather than listed, greyed, under the count, for good. Only
    // when the gallery row itself is gone - a refused read (a reinstall's
    // files) proves nothing, and those stay.
    LaunchedEffect(records.size) {
        val gone = withContext(Dispatchers.IO) {
            records.filter { record ->
                val gallery = record.galleryUri
                if (gallery == null) !java.io.File(record.outputPath).exists()
                else runCatching {
                    context.contentResolver.query(android.net.Uri.parse(gallery), arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                        ?.use { it.count == 0 } ?: false
                }.getOrDefault(false)
            }.map { it.id }
        }
        repository.forget(gone)
    }

    val shown = remember(records, query) {
        if (query.isBlank()) records
        else records.filter { it.shownTitle.contains(query.trim(), ignoreCase = true) || it.title.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayLarge,
                            color = SquishColors.TextPrimary
                        )
                        Text(
                            if (records.isEmpty()) "Everything you export lands here"
                            else "${records.size} ${if (records.size == 1) "export" else "exports"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SquishColors.TextMuted
                        )
                    }
                    if (records.size > 3) {
                        SearchField(query = query, onQuery = { query = it })
                    }
                }

                when {
                    records.isEmpty() -> EmptyState(
                        "Nothing exported yet",
                        "Whatever you make lands here, with a thumbnail and how much smaller it came out."
                    )
                    shown.isEmpty() -> EmptyState(
                        "No match",
                        "Nothing here is called \"$query\"."
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 108.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(shown, key = { it.id }) { record ->
                            LibraryRow(
                                record = record,
                                onOpen = { onOpen(record.id) },
                                onPreview = { previewing = record },
                                onShare = { ShareUtils.share(context, record.mediaUri, record.mimeType) },
                                onRemove = { pendingDelete = record }
                            )
                        }
                    }
                }
            }

            BackOrb(
                accent = SquishColors.Violet,
                onClick = onBack,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)
            )
        }
    }

    previewing?.let { record ->
        VideoPreviewSheet(
            title = record.shownTitle,
            subtitle = "${Timecode.format(record.durationMs).removeSuffix(".000")}  ·  " +
                formatSize(record.outputSizeBytes),
            uri = record.mediaUri,
            durationMs = record.durationMs,
            accent = SquishColors.Violet,
            // Measured off the exported file itself. The record holds the source's
            // shape, which is not the export's once it was cropped or reframed -
            // a 16:9 reframe of a portrait clip played stretched into a tall box.
            audioOnly = record.isAudio,
            actionLabel = "Open",
            onAction = {
                previewing = null
                onOpen(record.id)
            },
            onDismiss = { previewing = null }
        )
    }

    pendingDelete?.let { record ->
        DeleteExportDialog(
            record = record,
            onConfirm = {
                repository.delete(record.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * The one question before an export goes, shared with the detail screen. The
 * gallery copy is the export now, so deleting reaches into the gallery, and
 * the words say so - the older wording promised the gallery copy would stay.
 */
@Composable
internal fun DeleteExportDialog(record: ExportRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var stillHere by remember(record.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(record.id) { stillHere = withContext(Dispatchers.IO) { context.canReadMedia(record.mediaUri) } }
    ConfirmDialog(
        title = "Delete “${record.shownTitle}”?",
        body = when {
            stillHere == false ->
                "The file behind this one is already gone from this phone. " +
                    "Deleting clears the entry that is left."
            record.onPrivateCopy ->
                "This removes it from your library and deletes the video from Squish's own storage."
            else ->
                "This removes it from your library and deletes the video from your " +
                    "${if (record.isAudio) "Music" else "gallery"} - it is the only copy Squish keeps."
        },
        caution = if (stillHere == false) "" else "There is no undo and no bin to fetch it back from. " +
            "A copy you saved to Files yourself, or sent somewhere, stays where it is.",
        confirmLabel = "Delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = SquishColors.TextMuted,
            modifier = Modifier.size(18.dp)
        )
        Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (query.isEmpty()) {
                Text(
                    "Search your exports",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextMuted
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.merge(
                    TextStyle(color = SquishColors.TextPrimary)
                ),
                cursorBrush = SolidColor(SquishColors.Violet),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQuery("") }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear the search",
                    tint = SquishColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LibraryRow(
    record: ExportRecord,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    // Whether the file is still there is a resolver call, off the main thread;
    // it used to be a stat at composition, for every row, on every scroll.
    var exists by remember(record.id) { mutableStateOf<Boolean?>(null) }
    var thumb by remember(record.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(record.id) {
        val here = withContext(Dispatchers.IO) { context.canReadMedia(record.mediaUri) }
        exists = here
        if (!here || record.isAudio) return@LaunchedEffect
        thumb = ThumbnailCache.frame(context, record.mediaUri, (record.durationMs / 3).coerceAtLeast(0L))
    }
    val gone = exists == false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(16.dp))
            .clickable(enabled = !gone, role = Role.Button, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(78.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SquishColors.Background)
                // The picture with a play mark on it plays: a separate Play button
                // beside it cost the name its room ("30 Sep, 12:09...").
                .clickable(enabled = !gone, role = Role.Button, onClickLabel = "Preview", onClick = onPreview),
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
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SquishColors.Background.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = SquishColors.TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                // A sound export has no picture: a note, not a film that never loads.
                Icon(
                    if (record.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = if (record.isAudio) SquishColors.Cyan else SquishColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                record.shownTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (gone) SquishColors.TextMuted else SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Timecode.format(record.durationMs)}  ·  ${formatSize(record.outputSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
            if (gone) {
                Text(
                    "File no longer on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Yellow
                )
            }
        }

        if (!gone) {
            RowAction(Icons.Filled.Share, "Share ${record.shownTitle}", SquishColors.Cyan, onShare)
        }
        RowAction(Icons.Filled.DeleteOutline, "Remove ${record.shownTitle}", SquishColors.Pink, onRemove)
    }
}

/** A row's button at the size a thumb needs; see DraftsScreen's CardAction for why. */
@Composable
private fun RowAction(
    icon: ImageVector,
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
