package com.squish.app.home

import com.squish.app.data.ProjectAutosave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.data.DraftSummary
import com.squish.app.data.ProjectRules
import com.squish.app.editor.GlyphTile
import com.squish.app.editor.Timecode
import com.squish.app.editor.glyph
import com.squish.app.media.IMPORTS_DIR
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.keepReadAccess
import com.squish.app.settings.Preferences
import com.squish.app.tools.QuickTool
import com.squish.app.ui.components.CoachMark
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.RenameDialog
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.components.VideoPreviewSheet
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors
import java.io.File

/**
 * A file's size, in the library and on the done screen.
 *
 * The project cards already had this in `ProjectRules.sizeLabel`, with a suite
 * under it; this one was a second copy with no bytes tier and no gigabytes, so
 * a four-gigabyte export read "4000.0 MB" and a four-hundred-byte file read
 * "0 KB", while the same file on a project card read correctly. One ladder.
 */
fun formatSize(bytes: Long): String = ProjectRules.sizeLabel(bytes)

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
 *
 * A lazy column, not a scrolling column: every card on it decodes a cover,
 * one at a time behind ThumbnailCache's lock, so forty cards composed at once
 * queued forty decodes and the bottom rows waited for the top ones.
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
    // And when an editor closing changed the list after that (ProjectsChanged).
    val projectsChanged by com.squish.app.data.ProjectsChanged.count.collectAsState()
    LaunchedEffect(projectsChanged) { viewModel.refreshDrafts() }
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
    // The path is saved state, not remembered: the camera is the one app most
    // likely to have this process killed behind it, and the result then came
    // back to a recreated screen that had forgotten which file it asked for -
    // no project, no message, and the take orphaned under files/imports.
    var recording by rememberSaveable { mutableStateOf<String?>(null) }
    val record = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { taken ->
        val file = recording?.let(::File)
        recording = null
        if (taken && file != null && file.length() > 0L) viewModel.startProject(listOf(Uri.fromFile(file)), onOpenProject)
        else file?.delete()
    }

    var renaming by remember { mutableStateOf<DraftSummary?>(null) }
    var previewing by remember { mutableStateOf<DraftSummary?>(null) }
    var reverting by remember { mutableStateOf<DraftSummary?>(null) }
    var deleting by remember { mutableStateOf<List<DraftSummary>>(emptyList()) }
    // Long-press a card to select; a bar above the grid then counts and takes
    // the set. Back leaves the mode before it leaves the app.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Once, on the first visit: what the one button does with several files.
    var coach by remember { mutableStateOf(!Preferences.coachSeen(context, Preferences.COACH_HOME)) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message = offer.message, actionLabel = "Undo", withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) viewModel.restoreAll(offer.entries)
        viewModel.dismissUndoOffer()
    }
    DisposableEffect(Unit) { onDispose { viewModel.dismissUndoOffer() } }

    Scaffold(containerColor = SquishColors.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above the scrolling grid, not at the top of it: a selection
            // started on row four used to put the count and Delete wherever
            // the header had been scrolled to, which was off screen.
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onDelete = { deleting = projects.filter { it.id in selected } },
                    onCancel = { selected = emptySet() },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!selecting) {
                    item(key = "header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
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
                }

                item(key = "new-project") {
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
                                recording = file.absolutePath
                                runCatching { record.launch(target) }.onFailure { recording = null }
                            }
                        }
                    )
                }

                if (coach) {
                    item(key = "coach") {
                        CoachMark(
                            text = "Pick several photos and videos at once - they land end to end, ready to cut.",
                            onDismiss = {
                                Preferences.markCoachSeen(context, Preferences.COACH_HOME)
                                coach = false
                            }
                        )
                    }
                }

                if (projects.isNotEmpty()) {
                    item(key = "projects-heading") {
                        Header(
                            "Projects",
                            if (projects.size == 1) "1 project" else "${projects.size} projects",
                            modifier = Modifier.padding(top = SECTION_GAP)
                        )
                    }
                    items(projects.chunked(2), key = { pair -> pair.joinToString("|") { it.id } }) { pair ->
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
                                    onPreview = { previewing = project },
                                    onEarlier = { reverting = project },
                                    onDelete = { deleting = listOf(project) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                item(key = "tools") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = SECTION_GAP)) {
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
                }

                // The quick tools' unfinished sessions and what was deleted have
                // a screen of their own; the door shows only when there is
                // something behind it.
                if (toolDrafts.isNotEmpty() || trashed.isNotEmpty()) {
                    item(key = "drafts-door") {
                        DraftsDoor(
                            tools = toolDrafts.size,
                            binned = trashed.size,
                            onClick = onOpenDrafts,
                            modifier = Modifier.padding(top = SECTION_GAP)
                        )
                    }
                }

                item(key = "library-door") {
                    LibraryDoor(
                        count = recent.size,
                        onClick = onOpenLibrary,
                        modifier = Modifier.padding(top = SECTION_GAP)
                    )
                }
            }
        }
    }

    renaming?.let { project ->
        RenameDialog(
            current = project.name.orEmpty(),
            placeholder = if (project.name == null) project.title else "Untitled edit",
            onSave = { name ->
                viewModel.renameProject(project, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    previewing?.let { project ->
        VideoPreviewSheet(
            title = project.title,
            subtitle = "${countOf(project.clipCount, "clip")} · ${agoOf(project.savedAtMillis)}",
            uri = project.coverUri ?: project.sourceUri,
            durationMs = project.durationMs,
            accent = SquishColors.Cyan,
            actionLabel = "Open",
            onAction = {
                previewing = null
                onOpenProject(project.id)
            },
            onDismiss = { previewing = null }
        )
    }

    // The ten-minute snapshot, offered by name from the card's menu as the
    // drafts list offered it before projects moved to the grid: a run of bad
    // edits is only recoverable if someone can ask for the version before it.
    reverting?.let { project ->
        val earlier = project.earlierSavedAtMillis
        ConfirmDialog(
            title = "Go back to the earlier version?",
            body = "The version of “${project.title}” saved " +
                "${earlier?.let(::agoOf) ?: "earlier"} takes the place of the one saved ${agoOf(project.savedAtMillis)}.",
            caution = "The version you have now moves to Recently deleted for 30 days, so this can be undone.",
            confirmLabel = "Go back",
            dismissLabel = "Cancel",
            icon = Icons.Filled.History,
            accent = SquishColors.Cyan,
            onConfirm = {
                viewModel.revertDraft(project)
                reverting = null
            },
            onDismiss = { reverting = null }
        )
    }

    if (deleting.isNotEmpty()) {
        val many = deleting.size > 1
        // A staged project has no edit to keep, so it is not offered a way back.
        val onlyStaged = deleting.all { it.staged }
        ConfirmDialog(
            title = if (many) "Delete ${deleting.size} projects?" else "Delete “${deleting.first().title}”?",
            body = when {
                onlyStaged -> "Nothing has been edited yet, so there is nothing to keep."
                many -> "This sets aside every project selected, with every cut, look and caption on each."
                else -> "This sets aside the project - ${countOf(deleting.first().clipCount, "clip")}, with every cut, look and caption on it."
            },
            caution = if (onlyStaged) "Your original photos and videos are untouched."
            else "They move to Recently deleted for 30 days, where they can be brought back. " +
                "Your original videos are untouched either way.",
            confirmLabel = "Delete",
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
private fun Header(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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
 * and a menu with the things done to a project from a list - including its
 * earlier version, when the ten-minute snapshot differs from what is saved.
 * A long press starts selecting, as it does in every gallery app; in that
 * mode a tap adds to the set rather than opening.
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
    onPreview: () -> Unit,
    onEarlier: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cover by remember(project.coverUri, project.coverAtMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project.coverUri, project.coverAtMs) {
        val uri = project.coverUri ?: return@LaunchedEffect
        cover = ThumbnailCache.frame(context, uri, project.coverAtMs)
            ?: withContext(Dispatchers.IO) { ProjectAutosave(context).readableCover(project.id) }
                ?.takeIf { it.first != uri }
                ?.let { (other, at) -> ThumbnailCache.frame(context, other, at) }
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
            // On the cover, not beside the name: beside it, "Exported · edited"
            // took the whole line and the name came out as "...".
            if (project.exportedAtMillis != null) {
                Text(
                    if (project.editedSinceExport) "Exported · edited" else "Exported",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Background,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SquishColors.Cyan)
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
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Shrunk a little before it is cut: "Edit · 2 Oct copy 1" read
                // "Edit · 2 Oct co…", losing the one word that told the copy apart.
                com.squish.app.ui.components.FitText(
                    project.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    minScale = 0.75f,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(countOf(project.clipCount, if (project.staged) "file" else "clip"))
                        append(" · ")
                        append(if (project.staged) "not opened yet" else agoOf(project.savedAtMillis))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for ${project.title}", tint = SquishColors.TextSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // A staged project has no draft to rename or copy yet.
                    if (!project.staged) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    }
                    DropdownMenuItem(text = { Text("Preview") }, onClick = { menu = false; onPreview() })
                    project.earlierSavedAtMillis?.let { earlier ->
                        DropdownMenuItem(
                            text = { Text("Earlier version · ${agoOf(earlier)}", color = SquishColors.Cyan) },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, tint = SquishColors.Cyan) },
                            onClick = { menu = false; onEarlier() }
                        )
                    }
                    DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() })
                    DropdownMenuItem(text = { Text("Delete", color = SquishColors.Pink) }, onClick = { menu = false; onDelete() })
                    // In the menu, not on the card: on a half-width card the size
                    // pushed the line to "4 clips · 5 min ago · ...".
                    if (project.sizeBytes > 0L) {
                        DropdownMenuItem(
                            text = { Text("Uses ${ProjectRules.sizeLabel(project.sizeBytes)} on this phone", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted) },
                            onClick = {},
                            enabled = false
                        )
                    }
                }
            }
        }
    }
}

/** The bar over the grid while projects are being selected: how many, and the two things to do with them. */
@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp)
        )
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stop selecting", tint = SquishColors.TextSecondary)
        }
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
private fun LibraryDoor(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Door(
        icon = Icons.Filled.VideoLibrary,
        accent = SquishColors.Violet,
        title = "Library",
        subtitle = when (count) {
            0 -> "Everything you export lands here"
            1 -> "1 export · search and share"
            else -> "$count exports · search and share"
        },
        onClick = onClick,
        modifier = modifier
    )
}

/** The way in to the quick tools' half-done sessions and what was deleted. */
@Composable
private fun DraftsDoor(tools: Int, binned: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Door(
        icon = Icons.Filled.Edit,
        accent = SquishColors.Cyan,
        title = "Tool sessions and recently deleted",
        subtitle = buildList {
            if (tools > 0) add(if (tools == 1) "1 tool session" else "$tools tool sessions")
            if (binned > 0) add(if (binned == 1) "1 recently deleted" else "$binned recently deleted")
        }.joinToString(" · "),
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun Door(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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

/** The room between one section of the dashboard and the next, over the list's own 12 dp. */
private val SECTION_GAP = 10.dp

/** As many as the editor's own picker takes. */
private const val MAX_PICK = 30
