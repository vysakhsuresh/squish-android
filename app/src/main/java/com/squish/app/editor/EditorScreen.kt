package com.squish.app.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.theme.SquishColors

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

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    LaunchedEffect(state.durationMs) {
        if (state.durationMs > 0 && thumbnails.isEmpty()) {
            val frames = ThumbnailExtractor.extractFrames(context, sourceUri, 10, state.durationMs)
            thumbnails = frames.map { it.asImageBitmap() }
        }
    }

    val addAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.setMusic(it) }
    }
    val addClipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addClipToQueue(it) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("← Back", color = SquishColors.TextPrimary) }
                Text("Edit video", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                Spacer(modifier = Modifier.width(64.dp))
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.isLoadingSource) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SquishColors.Coral)
                    }
                } else {
                    VideoPreviewPlayer(
                        uri = sourceUri,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp))
                    )

                    TimelineTrimmer(
                        durationMs = state.durationMs,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        thumbnails = thumbnails,
                        onTrimChange = viewModel::setTrim
                    )

                    Text(
                        "${formatMs(state.trimStartMs)} – ${formatMs(state.trimEndMs)}  ·  ${formatMs(state.trimmedDurationMs)} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextSecondary
                    )

                    SectionLabel("Compress & convert")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Quality.entries.forEach { q ->
                            SelectableChip(
                                label = q.label,
                                selected = state.quality == q && !state.fitToSize,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.setFitToSize(false)
                                    viewModel.setQuality(q)
                                }
                            )
                        }
                    }

                    FitToSizeCard(state = state, viewModel = viewModel)
                    EstimateCard(state = state)

                    SectionLabel("Crop & speed")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CropAspect.entries.forEach { aspect ->
                            SelectableChip(
                                label = aspect.label,
                                selected = state.cropAspect == aspect,
                                accentColor = SquishColors.Purple,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setCropAspect(aspect) }
                            )
                        }
                    }
                    SpeedSlider(state.speed, viewModel::setSpeed)

                    SectionLabel("Fix & clean up")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OptionToggle("Mute audio", state.muted, Modifier.weight(1f)) { viewModel.setMuted(it) }
                        OptionToggle("Rotate 90°", state.rotationDegrees != 0, Modifier.weight(1f)) { viewModel.toggleRotate() }
                    }

                    SectionLabel("Color")
                    LabeledSlider("Brightness", state.brightness, -1f..1f, viewModel::setBrightness)
                    LabeledSlider("Contrast", state.contrast, -1f..1f, viewModel::setContrast)
                    LabeledSlider("Saturation", state.saturation, -1f..1f, viewModel::setSaturation)

                    SectionLabel("Captions & text")
                    TextOverlaySection(state = state, viewModel = viewModel)

                    SectionLabel("Background music")
                    MusicSection(
                        state = state,
                        onPick = { addAudioLauncher.launch(arrayOf("audio/*")) },
                        onClear = { viewModel.setMusic(null) }
                    )

                    SectionLabel("Merge clips")
                    MergeQueueSection(
                        state = state,
                        onAddClip = {
                            addClipLauncher.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        },
                        onRemoveClip = viewModel::removeClipFromQueue
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                errorMessage?.let {
                    Text(it, color = SquishColors.Pink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
                SquishPrimaryButton(
                    text = if (state.isExporting) "Compressing…" else "Compress video",
                    enabled = !state.isExporting && !state.isLoadingSource,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        errorMessage = null
                        viewModel.export(
                            onResult = onExported,
                            onError = { errorMessage = it }
                        )
                    }
                )
            }
        }
    }
}
