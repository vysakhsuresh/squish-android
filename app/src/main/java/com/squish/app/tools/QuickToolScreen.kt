package com.squish.app.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.editor.Quality
import com.squish.app.editor.Timecode
import com.squish.app.home.formatSize
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.ui.graphics.vector.ImageVector
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.OrderBadge
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SquishCard
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import com.squish.app.ui.theme.SquishColors

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

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::load)
    }
    // Merging is the one tool where picking several at once is the normal case, so
    // it gets the multiple picker rather than the same trip repeated.
    val pickMergeClips = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_MERGE_CLIPS)
    ) { uris ->
        viewModel.addMergeClips(uris)
    }

    fun launchPicker(launcher: (PickVisualMediaRequest) -> Unit) {
        launcher(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                .build()
        )
    }

    // Straight to the picker: the tile tap already said what they want to do.
    LaunchedEffect(Unit) {
        if (state.hasSource) return@LaunchedEffect
        if (tool == QuickTool.Merge) {
            pickMergeClips.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    .build()
            )
        } else {
            launchPicker { pickVideo.launch(it) }
        }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Text(tool.title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                Spacer(modifier = Modifier.height(1.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tool.title, style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                Text(tool.blurb, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextMuted)
            }

            if (!state.hasSource) {
                ToolCard {
                    Text(
                        if (tool == QuickTool.Merge) "Choose the videos you want joined — you can pick several at once."
                        else "Choose a video to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextSecondary
                    )
                    SquishOutlinedButton(
                        text = if (tool == QuickTool.Merge) "Choose videos" else "Choose video",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (tool == QuickTool.Merge) {
                                pickMergeClips.launch(
                                    PickVisualMediaRequest.Builder()
                                        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        .build()
                                )
                            } else {
                                launchPicker { request -> pickVideo.launch(request) }
                            }
                        }
                    )
                }
                return@Column
            }

            if (tool != QuickTool.Merge) ToolCard {
                Text(
                    state.name ?: "Selected video",
                    style = MaterialTheme.typography.titleSmall,
                    color = SquishColors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    "${Timecode.format(state.durationMs)}  ·  ${formatSize(state.originalSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
                SquishOutlinedButton(
                    text = "Choose a different video",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { launchPicker { request -> pickVideo.launch(request) } }
                )
            }

            when (tool) {
                QuickTool.Compress -> CompressControls(state, viewModel)
                QuickTool.Trim -> TrimControls(state, viewModel)
                QuickTool.ExtractAudio -> ToolCard {
                    Text(
                        "The soundtrack will be saved to Music/Squish as an .m4a file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextSecondary
                    )
                }
                QuickTool.Merge -> MergeControls(
                    state = state,
                    viewModel = viewModel,
                    onAddClips = {
                        pickMergeClips.launch(
                            PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                .build()
                        )
                    }
                )
            }

            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Pink)
            }

            SquishPrimaryButton(
                text = if (state.isExporting) "Working…" else tool.actionLabel,
                enabled = !state.isExporting && !state.isLoading,
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
                    color = SquishColors.Coral,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenInEditor(uri) }
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun CompressControls(state: QuickToolViewModel.UiState, viewModel: QuickToolViewModel) {
    ToolCard {
        Text("Quality", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Quality.entries.forEach { quality ->
                SelectableChip(
                    label = quality.label,
                    selected = state.quality == quality && !state.fitToSize,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setQuality(quality) }
                )
            }
        }
    }

    ToolCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Fit to a size", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                Text("For a strict upload limit", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
            }
            SquishToggleSwitch(checked = state.fitToSize, onCheckedChange = viewModel::setFitToSize)
        }
        if (state.fitToSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(16, 25, 50).forEach { mb ->
                    SelectableChip(
                        label = "$mb MB",
                        selected = state.targetSizeMb == mb,
                        accentColor = SquishColors.Teal,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setTargetSizeMb(mb) }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Estimated output", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(
                formatSize(state.estimatedOutputBytes),
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.Teal
            )
        }
    }
}

@Composable
private fun TrimControls(state: QuickToolViewModel.UiState, viewModel: QuickToolViewModel) {
    ToolCard {
        Text("Keep this part", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        if (state.durationMs > 0) {
            RangeSlider(
                value = state.trimStartMs.toFloat()..state.trimEndMs.toFloat(),
                onValueChange = { range ->
                    viewModel.setTrim(range.start.toLong(), range.endInclusive.toLong())
                },
                valueRange = 0f..state.durationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = SquishColors.Coral,
                    activeTrackColor = SquishColors.Coral,
                    inactiveTrackColor = SquishColors.Border
                )
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(Timecode.format(state.trimStartMs), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
            Text(
                "${Timecode.format(state.selectedDurationMs)} kept",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Teal
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
    SquishCard(accent = SquishColors.Violet) {
        SectionHeading(
            title = "Playing order",
            subtitle = if (state.mergeClips.isEmpty()) "Nothing added yet"
            else "${state.mergeClips.size} clips · ${Timecode.format(state.mergeDurationMs)} total",
            icon = Icons.Filled.PlaylistPlay,
            accent = SquishColors.Violet,
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
 * The number and the start time are both shown because they answer different
 * questions: the number says which comes next, the start time says where it lands
 * in the finished video. Without either, a list of filenames tells you nothing
 * about what you are about to render.
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
        OrderBadge(number = position, accent = SquishColors.Violet)

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
