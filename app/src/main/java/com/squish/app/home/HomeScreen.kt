package com.squish.app.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.data.DraftSummary
import com.squish.app.data.ProjectRules
import com.squish.app.editor.GlyphTile
import com.squish.app.editor.ProjectName
import com.squish.app.editor.Timecode
import com.squish.app.editor.glyph
import com.squish.app.media.IMPORTS_DIR
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.keepReadAccess
import com.squish.app.settings.Preferences
import com.squish.app.tools.QuickTool
import com.squish.app.ui.components.CoachMark
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors
import java.io.File

fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1000.0)
}

/** "1 clip", "3 clips" - a count read aloud, not a count with an s stapled on. */
fun countOf(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * The dashboard: the projects, as a grid, and the ways to start one.
 *
 * It used to be a hero that opened the picker for one video and a door to a
 * list of "unfinished" edits - which made a project the same thing as its
 * first video, and the list a place to go rather than the thing the screen is.
 * CapCut's home is the project grid itself, and so is this: a cover, a name,
 * a length and when it was last touched, with New project above it taking
 * photos and videos together, and the one-job tools below it for people who
 * just need a smaller file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onOpenTool: (QuickTool) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val recent by viewModel.recentExports.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val toolDrafts by viewModel.toolDrafts.collectAsState()
    val trashed by viewModel.trashed.collectAsState()
    val undoOffer by viewModel.undoOffer.collectAsState()
    // Re-read on every return to the dashboard, so an edit left five minutes ago
    // is here rather than whatever the list happened to hold at launch.
    LaunchedEffect(Unit) { viewModel.refreshDrafts() }
    val context = LocalContext.current

    // What is picked lands as one project, the picker's order kept.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.startProject(uris, onOpenProject)
    }
    // The file manager, for what the picker never shows: an MKV in Downloads,
    // a folder synced from a camera.
    val browseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.startProject(uris, onOpenProject)
    }
    // The camera, straight into a project: the take is written under the
    // app's own storage (files/imports), so it is the project's from the start.
    var recording by remember { mutableStateOf<File?>(null) }
    val record = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { taken ->
        val file = recording
        recording = null
        if (taken && file != null && file.length() > 0L) viewModel.startProject(listOf(Uri.fromFile(file)), onOpenProject)
        else file?.delete()
    }

    var renaming by remember { mutableStateOf<DraftSummary?>(null) }
    var deleting by remember { mutableStateOf<List<DraftSummary>>(emptyList()) }
    // Long-press a card to select; the header then counts and the bin takes
    // the set. Back leaves the mode before it leaves the app.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message = offer.message, actionLabel = "Undo", withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) viewModel.restoreDraft(offer.entry)
        viewModel.dismissUndoOffer()
    }
    DisposableEffect(Unit) { onDispose { viewModel.dismissUndoOffer() } }

    Scaffold(containerColor = SquishColors.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onDelete = { deleting = projects.filter { it.id in selected } },
                    onCancel = { selected = emptySet() }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SquishLogoMark(modifier = Modifier.size(34.dp))
                        Text("Squish", style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SquishColors.Surface)
                                .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = SquishColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            NewProjectCard(
                onPick = {
                    pickMedia.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            .build()
                    )
                },
                onBrowse = { browseFiles.launch(arrayOf("video/*", "image/*")) },
                onRecord = {
                    val dir = File(context.filesDir, IMPORTS_DIR).apply { mkdirs() }
                    val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
                    val target = runCatching {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }.getOrNull()
                    if (target != null) {
                        recording = file
                        runCatching { record.launch(target) }.onFailure { recording = null }
                    }
                }
            )

            // Once, on the first visit: what the one button does with several files.
            var coach by remember { mutableStateOf(!Preferences.coachSeen(context, Preferences.COACH_HOME)) }
            if (coach) {
                CoachMark(
                    text = "Pick several photos and videos at once - they land end to end, ready to cut.",
                    onDismiss = {
                        Preferences.markCoachSeen(context, Preferences.COACH_HOME)
                        coach = false
                    }
                )
            }

            if (projects.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header("Projects", if (projects.size == 1) "1 project" else "${projects.size} projects")
                    projects.chunked(2).forEach { pair ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)
                        ) {
                            pair.forEach { project ->
                                ProjectCard(
                                    project = project,
                                    selected = project.id in selected,
                                    selecting = selecting,
                                    onOpen = {
                                        if (selecting) selected = selected.toggled(project.id)
                                        else onOpenProject(project.id)
                                    },
                                    onSelect = { selected = selected.toggled(project.id) },
                                    onRename = { renaming = project },
                                    onDuplicate = { viewModel.duplicateProject(project) },
                                    onDelete = { deleting = listOf(project) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header("Fast lane", "One job, one tap")
                QuickTool.entries.chunked(2).forEach { pair ->
                    // As tall as the taller of the two, never fixed: at a large
                    // font the blurb used to be cut off at a hard-coded height.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)
                    ) {
                        pair.forEach { tool ->
                            ToolTile(
                                tool = tool,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) { onOpenTool(tool) }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // The quick tools' unfinished sessions and the bin have a screen of
            // their own; the door shows only when there is something behind it.
            if (toolDrafts.isNotEmpty() || trashed.isNotEmpty()) {
                DraftsDoor(tools = toolDrafts.size, binned = trashed.size, onClick = onOpenDrafts)
            }

            LibraryDoor(count = recent.size, onClick = onOpenLibrary)

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    renaming?.let { project ->
        RenameProjectDialog(
            current = project.title,
            onSave = { name ->
                viewModel.renameProject(project, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    if (deleting.isNotEmpty()) {
        val many = deleting.size > 1
        ConfirmDialog(
            title = if (many) "Discard ${deleting.size} projects?" else "Discard \"${deleting.first().title}\"?",
            body = if (many) "This sets aside every project selected, with every cut, look and caption on each."
            else "This sets aside the project - ${countOf(deleting.first().clipCount, "clip")}, with every cut, look and caption on it.",
            caution = "They move to Recently discarded for 30 days, where they can be brought back. " +
                "Your original videos are untouched either way.",
            confirmLabel = "Discard",
            onConfirm = {
                viewModel.discardDrafts(deleting)
                selected = emptySet()
                deleting = emptyList()
            },
            onDismiss = { deleting = emptyList() }
        )
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun Header(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

/**
 * The way to start, as the thing the screen is obviously for.
 *
 * It carries the brand's whole sweep where the tiles below carry one hue each,
 * so the hierarchy is visible before a word is read: this is the main event,
 * those are the shortcuts. The big target is the picker - photos and videos,
 * several at once - with the file manager and the camera as the two quieter
 * ways in beside it, as CapCut's picker has its Camera tab.
 */
@Composable
private fun NewProjectCard(onPick: () -> Unit, onBrowse: () -> Unit, onRecord: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(SquishColors.Cyan, SquishColors.Blue, SquishColors.Violet, SquishColors.Magenta)
                )
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onPick)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SquishColors.Background.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = SquishColors.Background, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "New project",
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = SquishColors.Background
                )
                Text(
                    "Photos and videos, as many as you like",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.Background.copy(alpha = 0.8f)
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = SquishColors.Background,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietWayIn(Icons.Filled.FolderOpen, "Browse files", Modifier.weight(1f), onBrowse)
            QuietWayIn(Icons.Filled.Videocam, "Record", Modifier.weight(1f), onRecord)
        }
    }
}

@Composable
private fun QuietWayIn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SquishColors.Background.copy(alpha = 0.18f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SquishColors.Background, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = SquishColors.Background, maxLines = 1)
    }
}

/** "4 minutes ago" — the only thing anyone wants to know about a draft's age. */
internal fun agoOf(millis: Long): String {
    val elapsed = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1_440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1_440} d ago"
    }
}

/**
 * One project: its cover, its name, its length and when it was last touched,
 * and a menu with the three things done to a project from a list. A long
 * press starts selecting, as it does in every gallery app; in that mode a
 * tap adds to the set rather than opening.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: DraftSummary,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cover by remember(project.coverUri, project.coverAtMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project.coverUri, project.coverAtMs) {
        val uri = project.coverUri ?: return@LaunchedEffect
        cover = ThumbnailCache.frame(context, uri, project.coverAtMs)
    }
    var menu by remember { mutableStateOf(false) }
    val edge = if (selected) SquishColors.Primary else SquishColors.Cyan.copy(alpha = 0.22f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(if (selected) 2.dp else 1.dp, edge, RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onSelect)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(SquishColors.Background),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = SquishColors.TextMuted, modifier = Modifier.size(24.dp))
            }
            if (project.durationMs > 0L) {
                Text(
                    Timecode.format(project.durationMs).substringBefore('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SquishColors.Background.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (selecting) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) SquishColors.Primary else SquishColors.TextPrimary.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // An exported project stays: it is the one most likely to be
                    // opened again for one more change. The badge says so.
                    if (project.exportedAtMillis != null) {
                        Text(
                            if (project.editedSinceExport) "Exported · edited" else "Exported",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Cyan,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SquishColors.Cyan.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    buildString {
                        append(countOf(project.clipCount, "clip"))
                        append(" · ")
                        append(agoOf(project.savedAtMillis))
                        if (project.sizeBytes > 0L) {
                            append(" · ")
                            append(ProjectRules.sizeLabel(project.sizeBytes))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for ${project.title}", tint = SquishColors.TextSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() })
                    DropdownMenuItem(text = { Text("Delete", color = SquishColors.Pink) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** The header while projects are being selected: how many, and the two things to do with them. */
@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (count == 1) "1 selected" else "$count selected",
            style = MaterialTheme.typography.titleMedium,
            color = SquishColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "Delete",
            style = MaterialTheme.typography.labelLarge,
            color = SquishColors.Pink,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onDelete)
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
        IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stop selecting", tint = SquishColors.TextSecondary)
        }
    }
}

/**
 * Naming a project from its card. Blank means no name, and the first clip's
 * name shows again.
 */
@Composable
private fun RenameProjectDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, SquishColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Name this project", style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= ProjectName.FIELD_LENGTH) text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquishOutlinedButton(text = "Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
                SquishPrimaryButton(text = "Save", modifier = Modifier.weight(1f), onClick = { onSave(text) })
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/** One shortcut. Its own colour, its own glyph, recognisable before it is read. */
@Composable
private fun ToolTile(tool: QuickTool, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .heightIn(min = TILE_MIN_HEIGHT)
            .clip(RoundedCornerShape(18.dp))
            .background(tool.accent.copy(alpha = 0.1f))
            .border(1.dp, tool.accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlyphTile(tool.glyph, size = 44.dp)
        Text(tool.title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
        Text(
            tool.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

/**
 * The way into the library, as one row rather than a list that grows forever.
 *
 * It says how many are in there, which is the only thing the dashboard needs to
 * tell you about work you have already finished.
 */
@Composable
private fun LibraryDoor(count: Int, onClick: () -> Unit) {
    Door(
        icon = Icons.Filled.VideoLibrary,
        accent = SquishColors.Violet,
        title = "Library",
        subtitle = when (count) {
            0 -> "Everything you export lands here"
            1 -> "1 export · search and share"
            else -> "$count exports · search and share"
        },
        onClick = onClick
    )
}

/** The way in to the quick tools' half-done sessions and the bin. */
@Composable
private fun DraftsDoor(tools: Int, binned: Int, onClick: () -> Unit) {
    Door(
        icon = Icons.Filled.Edit,
        accent = SquishColors.Cyan,
        title = "Unfinished tools and the bin",
        subtitle = buildList {
            if (tools > 0) add(if (tools == 1) "1 tool session" else "$tools tool sessions")
            if (binned > 0) add(if (binned == 1) "1 in the bin" else "$binned in the bin")
        }.joinToString(" · "),
        onClick = onClick
    )
}

@Composable
private fun Door(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.1f))
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentSweep(accent)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SquishColors.Background, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** A tile's floor; it grows past this with the words in it. */
private val TILE_MIN_HEIGHT = 156.dp

/** As many as the editor's own picker takes. */
private const val MAX_PICK = 30
