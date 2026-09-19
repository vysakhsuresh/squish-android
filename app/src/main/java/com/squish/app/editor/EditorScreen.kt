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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.theme.SquishColors

private const val FILMSTRIP_FRAMES = 12

enum class EditorTab(val label: String) {
    Trim("Trim"),
    Crop("Crop"),
    Speed("Speed"),
    Audio("Audio"),
    Text("Text"),
    Colour("Colour"),
    Export("Export")
}

/**
 * Preview and timeline stay put; only one tool panel is on screen at a time.
 * Previously every control lived in one endless scroll, which made even trim and
 * crop hard to find.
 */
@Composable
fun EditorScreen(
    sourceUri: Uri,
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var thumbnails by remember { mutableStateOf(listOf<ImageBitmap>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(EditorTab.Trim) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    LaunchedEffect(state.durationMs) {
        if (state.durationMs > 0 && thumbnails.isEmpty()) {
            thumbnails = ThumbnailExtractor
                .extractFrames(context, sourceUri, FILMSTRIP_FRAMES, state.durationMs)
                .map { it.asImageBitmap() }
        }
    }

    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setAudioTrack(it)
        }
    }

    val pickExtraClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addClipToQueue(it) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Text("Edit", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                Text(
                    if (state.isExporting) "Exporting…" else "Export",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.isExporting) SquishColors.TextMuted else SquishColors.Coral,
                    modifier = Modifier.clickable(enabled = !state.isExporting && !state.isLoadingSource) {
                        errorMessage = null
                        viewModel.export(onResult = onExported, onError = { errorMessage = it })
                    }
                )
            }

            if (state.isLoadingSource) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SquishColors.Coral)
                }
                return@Column
            }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                PrecisionTimeline(
                    state = state,
                    thumbnails = thumbnails,
                    onTrimChange = viewModel::setTrim,
                    onAudioOffsetChange = { delta -> viewModel.nudgeAudioOffset(delta) }
                )
            }

            errorMessage?.let {
                Text(
                    it,
                    color = SquishColors.Pink,
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
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EditorTab.entries.forEach { entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) SquishColors.Coral else SquishColors.Background)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) SquishColors.Background else SquishColors.TextSecondary
                )
            }
        }
    }
}
