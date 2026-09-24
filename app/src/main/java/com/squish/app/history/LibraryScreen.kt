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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.editor.Timecode
import com.squish.app.export.ShareUtils
import com.squish.app.home.formatSize
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.theme.SquishColors
import java.io.File

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

    val shown = remember(records, query) {
        if (query.isBlank()) records
        else records.filter { it.title.contains(query.trim(), ignoreCase = true) }
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
                                onOpen = { onOpen(record.outputPath) },
                                onShare = { ShareUtils.share(context, record.outputPath, null) },
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

    pendingDelete?.let { record ->
        val fileStillHere = remember(record.id) { File(record.outputPath).exists() }
        ConfirmDialog(
            title = "Delete \"${record.title}\"?",
            body = if (fileStillHere) {
                "This removes it from your library and deletes the video from " +
                    "Squish's own storage."
            } else {
                "The file behind this one is already gone from this phone. " +
                    "Deleting clears the entry that is left."
            },
            caution = "There is no undo and no bin to fetch it back from. " +
                "A copy you already saved to your phone's gallery stays where it is.",
            confirmLabel = "Delete",
            onConfirm = {
                repository.delete(record.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = SquishColors.TextMuted,
            modifier = Modifier.size(18.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
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
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear",
                tint = SquishColors.TextSecondary,
                modifier = Modifier.size(18.dp).clickable { onQuery("") }
            )
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
    onShare: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val exists = remember(record.outputPath) { File(record.outputPath).exists() }
    var thumb by remember(record.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(record.id, exists) {
        if (!exists) return@LaunchedEffect
        thumb = ThumbnailExtractor.frameAt(
            context,
            android.net.Uri.fromFile(File(record.outputPath)),
            (record.durationMs / 3).coerceAtLeast(0L)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(16.dp))
            .clickable(enabled = exists, onClick = onOpen)
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
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    tint = SquishColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                record.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (exists) SquishColors.TextPrimary else SquishColors.TextMuted,
                maxLines = 1
            )
            Text(
                "${Timecode.format(record.durationMs)}  ·  ${formatSize(record.outputSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
            if (!exists) {
                Text(
                    "File no longer on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Yellow
                )
            }
        }

        if (exists) {
            RowAction(Icons.Filled.Share, "Share ${record.title}", SquishColors.Cyan, onShare)
        }
        RowAction(Icons.Filled.DeleteOutline, "Remove ${record.title}", SquishColors.Pink, onRemove)
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
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
