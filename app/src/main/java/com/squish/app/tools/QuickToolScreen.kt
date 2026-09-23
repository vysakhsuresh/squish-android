package com.squish.app.tools

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.editor.Quality
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
import com.squish.app.ui.components.SquishPage
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
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    onOpenInEditor: (Uri) -> Unit,
    viewModel: QuickToolViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Bumped when a trim handle moves, which tells the preview to jump to the
    // handle being dragged. Seeing the cut is the entire point of the preview.
    var seekNonce by remember { mutableStateOf(0L) }
    var seekTarget by remember { mutableStateOf(0L) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::load)
    }
    val pickMergeClips = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_MERGE_CLIPS)
    ) { uris ->
        viewModel.addMergeClips(uris)
    }

    val videoOnly = PickVisualMediaRequest.Builder()
        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
        .build()

    fun openPicker() {
        if (tool == QuickTool.Merge) pickMergeClips.launch(videoOnly) else pickVideo.launch(videoOnly)
    }

    // Straight to the picker: the tile tap already said what they want to do.
    LaunchedEffect(Unit) { if (!state.hasSource) openPicker() }

    SquishPage(
        title = tool.title,
        subtitle = tool.blurb,
        onBack = onBack,
        accent = tool.accent
    ) {
        if (!state.hasSource) {
            SquishCard(accent = tool.accent) {
                Text(
                    if (tool == QuickTool.Merge)
                        "Choose the videos you want joined — you can pick several at once."
                    else "Choose a video to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextSecondary
                )
                SquishOutlinedButton(
                    text = if (tool == QuickTool.Merge) "Choose videos" else "Choose video",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openPicker() }
                )
            }
            return@SquishPage
        }

        PreviewCard(
            tool = tool,
            state = state,
            seekTarget = seekTarget,
            seekNonce = seekNonce,
            onChangeSource = { openPicker() }
        )

        when (tool) {
            QuickTool.Compress -> CompressControls(state, viewModel)
            QuickTool.Trim -> RangeControls(
                state = state,
                tool = tool,
                blurb = "Everything between the handles is kept.",
                onRange = { start, end, moved ->
                    viewModel.setTrim(start, end)
                    seekTarget = moved
                    seekNonce += 1
                }
            )
            QuickTool.ExtractAudio -> RangeControls(
                state = state,
                tool = tool,
                blurb = "Only the sound between the handles is saved, as an .m4a in Music/Squish.",
                onRange = { start, end, moved ->
                    viewModel.setTrim(start, end)
                    seekTarget = moved
                    seekNonce += 1
                }
            )
            QuickTool.Merge -> MergeControls(
                state = state,
                viewModel = viewModel,
                onAddClips = { pickMergeClips.launch(videoOnly) }
            )
        }

        errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Pink)
        }

        if (state.isExporting) {
            ExportProgressCard(progress = state.exportProgress, accent = tool.accent)
        } else {
            SquishPrimaryButton(
                text = tool.actionLabel,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    errorMessage = null
                    viewModel.export(tool, onResult = onExported, onError = { errorMessage = it })
                }
            )

            state.sourceUri?.let { uri ->
                Text(
                    "Need more control? Open in the full editor",
                    style = MaterialTheme.typography.labelLarge,
                    color = tool.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenInEditor(uri) }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * The footage, playing, with the file's name and weight underneath.
 *
 * One card rather than a preview plus a details card plus a change-source card:
 * three stacked boxes saying three things about one video is how a tool screen
 * turns into a form.
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
        if (tool == QuickTool.Merge) {
            state.mergeClips.mapNotNull { clip ->
                clip.uri?.let { PreviewSource(it, clip.durationMs, clip.label) }
            }
        } else {
            state.sourceUri?.let { listOf(PreviewSource(it, state.durationMs, state.name.orEmpty())) }
                ?: emptyList()
        }
    }
    if (sources.isEmpty()) return

    SquishCard(accent = tool.accent) {
        ClipPreview(
            sources = sources,
            accent = tool.accent,
            aspect = state.previewAspect,
            rangeStartMs = if (tool.usesRange) state.trimStartMs else 0L,
            rangeEndMs = if (tool.usesRange) state.trimEndMs else 0L,
            audioOnly = tool == QuickTool.ExtractAudio,
            seekToMs = seekTarget,
            seekNonce = seekNonce,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (tool == QuickTool.Merge) "${state.mergeClips.size} clips joined"
                    else state.name ?: "Selected video",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    if (tool == QuickTool.Merge)
                        Timecode.format(state.mergeDurationMs)
                    else
                        "${Timecode.format(state.durationMs)}  ·  ${formatSize(state.originalSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            Text(
                "Change",
                style = MaterialTheme.typography.labelLarge,
                color = tool.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onChangeSource)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun CompressControls(state: QuickToolViewModel.UiState, viewModel: QuickToolViewModel) {
    SquishCard(accent = SquishColors.Blue) {
        SectionHeading(
            title = "Quality",
            subtitle = "Bigger means sharper and heavier",
            icon = Icons.Filled.HighQuality,
            accent = SquishColors.Blue
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Quality.entries.forEach { quality ->
                SelectableChip(
                    label = quality.label,
                    selected = state.quality == quality && !state.fitToSize,
                    accentColor = SquishColors.Blue,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setQuality(quality) }
                )
            }
        }

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

        SavingRow(state)
    }
}

/** Before and after, side by side, because that is the whole question being asked. */
@Composable
private fun SavingRow(state: QuickToolViewModel.UiState) {
    val saved = if (state.originalSizeBytes > 0 && state.estimatedOutputBytes > 0) {
        (100 - (state.estimatedOutputBytes * 100 / state.originalSizeBytes)).coerceIn(0, 99)
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Estimated output", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(
                formatSize(state.estimatedOutputBytes),
                style = MaterialTheme.typography.titleMedium,
                color = SquishColors.Cyan
            )
        }
        saved?.let {
            Text(
                "$it% smaller",
                style = MaterialTheme.typography.labelLarge,
                color = SquishColors.Cyan
            )
        }
    }
}

/**
 * The in and out points, for the two tools that take a slice.
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
            icon = if (tool == QuickTool.ExtractAudio) Icons.Filled.MusicNote else Icons.Filled.ContentCut,
            accent = tool.accent
        )

        if (state.durationMs > 0) {
            RangeSlider(
                value = state.trimStartMs.toFloat()..state.trimEndMs.toFloat(),
                onValueChange = { range ->
                    val start = range.start.toLong()
                    val end = range.endInclusive.toLong()
                    val moved = if (start != state.trimStartMs) start else end
                    onRange(start, end, moved)
                },
                valueRange = 0f..state.durationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = tool.accent,
                    activeTrackColor = tool.accent,
                    inactiveTrackColor = SquishColors.Border
                )
            )
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(Timecode.format(state.trimStartMs), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
            Text(
                "${Timecode.format(state.selectedDurationMs)} kept",
                style = MaterialTheme.typography.bodySmall,
                color = tool.accent
            )
            Text(Timecode.format(state.trimEndMs), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
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
            icon = Icons.Filled.PlaylistPlay,
            accent = SquishColors.Magenta,
            trailing = {
                if (state.mergeClips.size > 1) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.clearMerge() }
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
                maxLines = 1
            )
            Text(
                "${Timecode.format(clip.durationMs)}  ·  starts at ${Timecode.format(clip.timelineStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MoveButton(Icons.Filled.KeyboardArrowUp, enabled = !isFirst, onClick = onUp)
            MoveButton(Icons.Filled.KeyboardArrowDown, enabled = !isLast, onClick = onDown)
        }

        Icon(
            Icons.Filled.Close,
            contentDescription = "Remove ${clip.label}",
            tint = SquishColors.Pink,
            modifier = Modifier.size(18.dp).clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun MoveButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) SquishColors.Surface else SquishColors.Background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) SquishColors.TextSecondary else SquishColors.TextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp)
        )
    }
}

private const val MAX_MERGE_CLIPS = 20
