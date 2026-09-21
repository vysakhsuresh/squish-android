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
    val pickExtraClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::addClip)
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
        if (!state.hasSource) launchPicker { pickVideo.launch(it) }
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
                        "Choose a video to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextSecondary
                    )
                    SquishOutlinedButton(
                        text = "Choose video",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { launchPicker { request -> pickVideo.launch(request) } }
                    )
                }
                return@Column
            }

            ToolCard {
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
                    onAddClip = { launchPicker { request -> pickExtraClip.launch(request) } }
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
    onAddClip: () -> Unit
) {
    ToolCard {
        Text("Clips in order", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        Text("1. ${state.name ?: "First clip"}", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextSecondary)
        state.extraClips.forEachIndexed { index, clip ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${index + 2}. ${clip.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Timecode.format(clip.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    modifier = Modifier.clickable { viewModel.removeClip(clip.id) }
                )
            }
        }
        SquishOutlinedButton(text = "Add a clip", modifier = Modifier.fillMaxWidth(), onClick = onAddClip)
    }
}
