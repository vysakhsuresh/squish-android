package com.squish.app.tools

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.editor.GlyphTile
import com.squish.app.editor.glyph
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.ui.components.StopExportDialog
import com.squish.app.media.ExportStage
import com.squish.app.media.keepReadAccess
import com.squish.app.home.countOf
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.OutputSizePicker
import com.squish.app.editor.Timecode
import com.squish.app.home.formatSize
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.ClipPreview
import com.squish.app.ui.components.ExportProgressCard
import com.squish.app.ui.components.OrderBadge
import com.squish.app.ui.components.PreviewSource
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishOutlinedButton

import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * A one-job tool, with the job on screen before it runs.
 *
 * The rule here is that nothing renders blind. Trim used to be two numbers and no
 * picture; extract-audio took the whole soundtrack whether you wanted it or not;
 * merge showed a list of filenames. Every one of them now opens on a player
 * holding exactly what the render will produce, bounded by exactly the same in and
 * out points the export reads.
 */
@Composable
fun QuickToolScreen(
    tool: QuickTool,
    /** The file this session saves into; see [com.squish.app.data.ToolDraft.slot]. */
    slot: String,
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    /** The session's files, in playing order, for a project made from them. */
    onOpenInEditor: (List<Uri>) -> Unit,
    viewModel: QuickToolViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmStopExport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val render = {
        errorMessage = null
        viewModel.export(tool, onResult = onExported, onError = { errorMessage = it })
    }
    // The progress notification wants asking for from Android 13 on, here as
    // in the editor: a Squeeze was the first export for most people, and it
    // never asked, so the card's promise of a notification was never kept.
    // Refused, the export runs the same with none.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    val startRender = {
        val wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (wanted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else render()
    }

    // Leaving flushes the session first. And mid-export, leaving is a question:
    // the back gesture used to pop the screen, clear the view model and cancel
    // the encode with nothing said, a broken file left in exports/.
    val leave = {
        if (state.isExporting) confirmStopExport = true
        else {
            viewModel.saveNow()
            onBack()
        }
    }
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveNow() }

    // An encode only runs while the app is in front, so the screen must not go
    // to sleep under a long one.
    val view = LocalView.current
    DisposableEffect(state.isExporting) {
        view.keepScreenOn = state.isExporting
        onDispose { view.keepScreenOn = false }
    }
    // An export that finishes while "Stop exporting?" is open takes the question
    // with it: there is nothing left to stop, and a Stop tapped then looked as if
    // it had thrown away a file that was already in the gallery.
    LaunchedEffect(state.isExporting) { if (!state.isExporting) confirmStopExport = false }
    // Bumped when a trim handle moves, which tells the preview to jump to the
    // handle being dragged. Seeing the cut is the entire point of the preview.
    var seekNonce by remember { mutableStateOf(0L) }
    var seekTarget by remember { mutableStateOf(0L) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { context.keepReadAccess(it); viewModel.load(it) }
    }
    val pickMergeClips = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_MERGE_CLIPS)
    ) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.addMergeClips(uris)
    }

    val videoOnly = PickVisualMediaRequest.Builder()
        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
        .build()

    fun openPicker() {
        if (tool == QuickTool.Stitch) pickMergeClips.launch(videoOnly) else pickVideo.launch(videoOnly)
    }

    // Straight to the picker: the tile tap already said what they want to do.
    //
    // Unless the slot already holds a session - one reopened from the drafts
    // list, or this one, back after the app was killed under it. Six videos chosen
    // and ordered for a merge is ten minutes of work, and throwing the picker over
    // the top of it would mean starting that again. And only once: after a
    // recreation - a font size change, a fold - the picker came up again unasked
    // over a screen whose first picker had been cancelled.
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tool) {
        // A pick handed over after the app was killed behind the picker has
        // already arrived, and settle() puts it ahead of the saved session.
        val coming = viewModel.settle(viewModel.begin(tool, slot))
        if (!coming && !state.hasSource && !askedOnce) {
            askedOnce = true
            openPicker()
        }
    }

    // Laid out the way the studio is: the picture pinned at the top, the controls
    // scrolling underneath it, and the action pinned at the bottom. As one long
    // scrolling page, a portrait clip took the whole screen and every setting sat
    // below it - change something, scroll up to see it, scroll down to change it
    // again.
    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ToolHeader(
                tool = tool,
                canOpenInEditor = state.sourceUri != null && !state.isExporting,
                onBack = leave,
                onOpenInEditor = {
                    // The whole merge, in order: only the first clip used to go,
                    // so a six-clip Stitch opened as a one-clip project.
                    val uris = if (tool == QuickTool.Stitch) state.mergeClips.mapNotNull { it.uri }
                    else listOfNotNull(state.sourceUri)
                    if (uris.isNotEmpty()) {
                        viewModel.saveNow()
                        onOpenInEditor(uris)
                    }
                }
            )

            if (state.hasSource) {
                PreviewCard(
                    tool = tool,
                    state = state,
                    seekTarget = seekTarget,
                    seekNonce = seekNonce,
                    onChangeSource = { openPicker() }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!state.hasSource) {
                    // What the tool does, before a file is chosen: the picker
                    // cancelled used to leave a header and one button.
                    SquishCard(accent = tool.accent) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GlyphTile(tool.glyph, size = 52.dp)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tool.title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                                Text(
                                    when (tool) {
                                        QuickTool.Squeeze -> "Pick a video, choose a size or a limit, and get a lighter file that looks the same."
                                        QuickTool.Snip -> "Pick a video and drag two handles over its frames. Only what is between them is kept."
                                        QuickTool.Rip -> "Pick a video and keep its sound alone, as an .m4a in Music › Squish."
                                        QuickTool.Stitch -> "Pick several videos at once and put them in order. They play end to end as one file."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SquishColors.TextSecondary
                                )
                            }
                        }
                        SquishOutlinedButton(
                            text = if (tool == QuickTool.Stitch) "Choose videos" else "Choose video",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openPicker() }
                        )
                    }
                } else {
                    when (tool) {
                        QuickTool.Squeeze -> CompressControls(state, viewModel)
                        QuickTool.Snip -> RangeControls(
                            state = state,
                            tool = tool,
                            blurb = "Everything between the handles is kept.",
                            onRange = { start, end, moved ->
                                viewModel.setTrim(start, end)
                                seekTarget = moved
                                seekNonce += 1
                            }
                        )
                        QuickTool.Rip -> RangeControls(
                            state = state,
                            tool = tool,
                            blurb = "Only the sound between the handles is saved, as an .m4a in Music/Squish.",
                            onRange = { start, end, moved ->
                                viewModel.setTrim(start, end)
                                seekTarget = moved
                                seekNonce += 1
                            }
                        )
                        QuickTool.Stitch -> MergeControls(
                            state = state,
                            viewModel = viewModel,
                            onAddClips = { pickMergeClips.launch(videoOnly) }
                        )
                    }
                }

                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Pink)
                }
            }

            if (state.hasSource) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SquishColors.Surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (state.isExporting) {
                        ExportProgressCard(
                            progress = state.exportProgress,
                            accent = tool.accent,
                            onCancel = { confirmStopExport = true }
                        )
                    } else {
                        SquishPrimaryButton(
                            text = tool.actionLabel,
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = startRender
                        )
                    }
                }
            }
        }
    }

    if (confirmStopExport) {
        StopExportDialog(
            saving = state.exportProgress.stage == ExportStage.Saving,
            onStop = {
                // Nothing to stop means the encode finished under the tap; the
                // dialog stays and now says the copy is under way.
                if (viewModel.cancelExport()) confirmStopExport = false
            },
            onKeepGoing = { confirmStopExport = false }
        )
    }
}

/**
 * Back, the tool's name, and the way out to the full editor, on one line.
 *
 * The back control sits here rather than floating bottom-left as on other pages:
 * the bottom of this screen is the action button, and a floating orb over it
 * would cover the one thing the screen is for.
 */
@Composable
private fun ToolHeader(
    tool: QuickTool,
    canOpenInEditor: Boolean,
    onBack: () -> Unit,
    onOpenInEditor: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BackOrb(accent = tool.accent, onClick = onBack, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(tool.title, style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary, maxLines = 1)
            Text(
                tool.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canOpenInEditor) {
            Text(
                "Open in editor",
                style = MaterialTheme.typography.labelLarge,
                color = tool.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tool.accent.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onOpenInEditor)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * The footage, playing, with the file's name and weight underneath.
 *
 * Pinned above the controls rather than scrolling with them, and capped in
 * height, so whatever is being changed below is always in view.
 */
@Composable
private fun PreviewCard(
    tool: QuickTool,
    state: QuickToolViewModel.UiState,
    seekTarget: Long,
    seekNonce: Long,
    onChangeSource: () -> Unit
) {
    val sources = remember(state.mergeClips, state.sourceUri, state.durationMs) {
        if (tool == QuickTool.Stitch) {
            state.mergeClips.mapNotNull { clip ->
                clip.uri?.let { PreviewSource(it, clip.durationMs, clip.label) }
            }
        } else {
            state.sourceUri?.let { listOf(PreviewSource(it, state.durationMs, state.name.orEmpty())) }
                ?: emptyList()
        }
    }
    if (sources.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ClipPreview(
            sources = sources,
            accent = tool.accent,
            aspect = state.previewAspect,
            rangeStartMs = if (tool.usesRange) state.trimStartMs else 0L,
            rangeEndMs = if (tool.usesRange) state.trimEndMs else 0L,
            audioOnly = tool == QuickTool.Rip,
            seekToMs = seekTarget,
            seekNonce = seekNonce,
            maxHeightDp = PREVIEW_MAX_HEIGHT_DP,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (tool == QuickTool.Stitch) "${countOf(state.mergeClips.size, "clip")} joined"
                    else state.name ?: "Selected video",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (tool == QuickTool.Stitch)
                        Timecode.format(state.mergeDurationMs)
                    else
                        "${Timecode.format(state.durationMs)}  ·  ${formatSize(state.originalSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            // Named for what it does: a Stitch's picker appends, so "Change" on
            // it added clips rather than replacing them.
            Text(
                if (tool == QuickTool.Stitch) "Add clips" else "Change",
                style = MaterialTheme.typography.labelLarge,
                color = tool.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onChangeSource)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun CompressControls(state: QuickToolViewModel.UiState, viewModel: QuickToolViewModel) {
    SquishCard(accent = SquishColors.Blue) {
        SectionHeading(
            title = "Size",
            subtitle = "Bigger means sharper and heavier",
            icon = Icons.Filled.HighQuality,
            accent = SquishColors.Blue
        )
        OutputSizePicker(
            outputP = state.outputP,
            fitToSize = state.fitToSize,
            sourceWidth = state.width,
            sourceHeight = state.height,
            asked = state.outputFrame,
            written = state.writtenFrame,
            estimatedBytes = state.estimatedOutputBytes,
            originalBytes = state.originalSizeBytes,
            accent = SquishColors.Blue,
            onPick = viewModel::setOutputP
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Fit to a size", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
                Text("For a strict upload limit", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
            }
            SquishToggleSwitch(checked = state.fitToSize, onCheckedChange = viewModel::setFitToSize)
        }

        if (state.fitToSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(16, 25, 50, 100).forEach { mb ->
                    SelectableChip(
                        label = "$mb MB",
                        selected = state.targetSizeMb == mb,
                        accentColor = SquishColors.Cyan,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setTargetSizeMb(mb) }
                    )
                }
            }
        }
    }
}

/**
 * The in and out points, for the two tools that take a slice: handles over
 * the footage's own frames (TrimStrip), landing on frame boundaries, with a
 * frame either way for the last bit of precision. It was a bare slider over
 * the whole clip, three seconds to the pixel on a long file.
 *
 * [onRange] is handed the handle that moved as well as the new range, so the
 * preview can jump to it: dragging the out point should show you the last frame
 * you are keeping, not leave you staring at the first.
 */
@Composable
private fun RangeControls(
    state: QuickToolViewModel.UiState,
    tool: QuickTool,
    blurb: String,
    onRange: (start: Long, end: Long, moved: Long) -> Unit
) {
    SquishCard(accent = tool.accent) {
        SectionHeading(
            title = "Keep this part",
            subtitle = blurb,
            icon = if (tool == QuickTool.Rip) Icons.Filled.MusicNote else Icons.Filled.ContentCut,
            accent = tool.accent
        )

        val uri = state.sourceUri
        if (state.durationMs > 0 && uri != null) {
            TrimStrip(
                uri = uri,
                durationMs = state.durationMs,
                fps = state.fps,
                startMs = state.trimStartMs,
                endMs = state.trimEndMs,
                accent = tool.accent,
                onRange = onRange
            )
        }
    }
}

@Composable
private fun MergeControls(
    state: QuickToolViewModel.UiState,
    viewModel: QuickToolViewModel,
    onAddClips: () -> Unit
) {
    SquishCard(accent = SquishColors.Magenta) {
        SectionHeading(
            title = "Playing order",
            subtitle = if (state.mergeClips.isEmpty()) "Nothing added yet"
            else "Tap a row's arrows to move it",
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            accent = SquishColors.Magenta,
            trailing = {
                if (state.mergeClips.size > 1) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelLarge,
                        color = SquishColors.Pink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { viewModel.clearMerge() }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }
            }
        )

        state.mergeClips.forEachIndexed { index, clip ->
            MergeRow(
                position = index + 1,
                clip = clip,
                isFirst = index == 0,
                isLast = index == state.mergeClips.lastIndex,
                onUp = { viewModel.moveMergeClip(clip.id, -1) },
                onDown = { viewModel.moveMergeClip(clip.id, +1) },
                onRemove = { viewModel.removeMergeClip(clip.id) }
            )
        }

        SquishOutlinedButton(
            text = "Add more clips",
            modifier = Modifier.fillMaxWidth(),
            onClick = onAddClips
        )
    }
}

/**
 * One clip in the merge.
 *
 * The number and the start time answer different questions: the number says which
 * comes next, the start time says where it lands in the finished video. Without
 * either, a list of filenames tells you nothing about what you are about to render.
 */
@Composable
private fun MergeRow(
    position: Int,
    clip: Clip,
    isFirst: Boolean,
    isLast: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OrderBadge(number = position, accent = SquishColors.Magenta)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                clip.label,
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Timecode.format(clip.durationMs)}  ·  starts at ${Timecode.format(clip.timelineStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }

        // Side by side rather than stacked: two 26 dp buttons one above the other
        // were the smallest targets in the app, and a thumb took the wrong one.
        MoveButton(Icons.Filled.KeyboardArrowUp, "Move ${clip.label} up", enabled = !isFirst, onClick = onUp)
        MoveButton(Icons.Filled.KeyboardArrowDown, "Move ${clip.label} down", enabled = !isLast, onClick = onDown)

        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${clip.label}",
                tint = SquishColors.Pink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MoveButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) SquishColors.Surface else SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) SquishColors.TextSecondary else SquishColors.TextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

private const val MAX_MERGE_CLIPS = 20

/** Room for the picture, and still room under it for the controls it is showing. */
private const val PREVIEW_MAX_HEIGHT_DP = 320f
