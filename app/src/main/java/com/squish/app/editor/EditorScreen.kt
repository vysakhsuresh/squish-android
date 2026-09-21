package com.squish.app.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.timeline.TimelineActionBar
import com.squish.app.timeline.TimelineEditor
import com.squish.app.ui.theme.SquishColors

enum class EditorTab(val label: String, val icon: ImageVector) {
    Trim("Trim", Icons.Filled.ContentCut),
    Crop("Crop", Icons.Filled.Crop),
    Speed("Speed", Icons.Filled.Speed),
    Audio("Audio", Icons.Filled.GraphicEq),
    Text("Text", Icons.Filled.TextFields),
    Colour("Colour", Icons.Filled.Tune),
    Export("Export", Icons.Filled.FileUpload)
}

@Composable
fun EditorScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(EditorTab.Trim) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setAudioTrack(it)
        }
    }
    val pickExtraClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addVideoClip(it) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = SquishColors.TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onBack)
                )
                Text("Edit", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (state.isExporting) SquishColors.Surface else SquishColors.Orange)
                        .clickable(enabled = !state.isExporting && !state.isLoadingSource) {
                            errorMessage = null
                            viewModel.export(onResult = onExported, onError = { errorMessage = it })
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (state.isExporting) "Exporting…" else "Export",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.isExporting) SquishColors.TextMuted else SquishColors.Background
                    )
                }
            }

            if (state.isLoadingSource) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SquishColors.Orange)
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(186.dp)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SquishColors.Surface)
            ) {
                VideoPreviewPlayer(
                    videoUri = sourceUri,
                    audioUri = state.audioTrackUri,
                    audioTrimStartMs = state.audioTrimStartMs,
                    audioPlacementMs = state.audioPlacementMs,
                    audioSliceDurationMs = state.audioSliceDurationMs,
                    muteOriginal = state.muteOriginal,
                    originalVolume = state.originalVolume,
                    audioVolume = state.audioVolume,
                    onPlayheadChange = viewModel::setPlayhead,
                    modifier = Modifier.fillMaxSize()
                )
                // Live framing while cropping, so the ratio is never chosen blind.
                if (tab == EditorTab.Crop || state.cropAspect != CropAspect.Original) {
                    CropOverlay(aspect = state.cropAspect, modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val timeline = state.toTimeline()
            TimelineEditor(
                state = timeline,
                onSelect = viewModel::selectClip,
                onMove = viewModel::moveClip,
                onTrim = viewModel::trimClip,
                onScrub = viewModel::setPlayhead
            )

            TimelineActionBar(
                state = timeline,
                onSplit = viewModel::splitAtPlayhead,
                onDelete = viewModel::deleteSelectedClip,
                onZoomIn = viewModel::zoomIn,
                onZoomOut = viewModel::zoomOut,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            errorMessage?.let {
                Text(
                    it,
                    color = SquishColors.Magenta,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (tab) {
                    EditorTab.Trim -> PrecisionTrimPanel(state, viewModel)
                    EditorTab.Crop -> CropPanel(state, viewModel)
                    EditorTab.Speed -> SpeedPanel(state, viewModel)
                    EditorTab.Audio -> AudioPanel(
                        state = state,
                        viewModel = viewModel,
                        onPickAudio = { pickAudioTrack.launch(arrayOf("audio/*", "video/*")) }
                    )
                    EditorTab.Text -> TextOverlaySection(state, viewModel)
                    EditorTab.Colour -> ColourPanel(state, viewModel)
                    EditorTab.Export -> ExportPanel(
                        state = state,
                        viewModel = viewModel,
                        onAddClip = {
                            pickExtraClip.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            ToolRail(selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun ToolRail(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SquishColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        EditorTab.entries.forEach { entry ->
            val isSelected = entry == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) SquishColors.Orange else SquishColors.Background)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    entry.icon,
                    contentDescription = entry.label,
                    tint = if (isSelected) SquishColors.Background else SquishColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) SquishColors.Background else SquishColors.TextSecondary
                )
            }
        }
    }
}
