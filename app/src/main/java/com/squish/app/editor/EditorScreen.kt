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

private const val FILMSTRIP_FRAMES = 12

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
            thumbnails = ThumbnailExtractor
                .extractFrames(context, sourceUri, FILMSTRIP_FRAMES, state.durationMs)
                .map { it.asImageBitmap() }
        }
    }

    // Accepts audio files and video files alike: the second angle of a two-camera
    // shoot is a perfectly normal source for the good audio.
    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
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
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SquishColors.Coral)
                    }
                } else {
                    VideoPreviewPlayer(
                        videoUri = sourceUri,
                        audioUri = state.audioTrackUri,
                        audioOffsetMs = state.audioOffsetMs,
                        muteOriginal = state.muteOriginal,
                        originalVolume = state.originalVolume,
                        audioVolume = state.audioVolume,
                        onPlayheadChange = viewModel::setPlayhead,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp))
                    )

                    PrecisionTimeline(
                        state = state,
                        thumbnails = thumbnails,
                        onTrimChange = viewModel::setTrim,
                        onAudioOffsetChange = { delta -> viewModel.nudgeAudioOffset(delta) }
                    )

                    SectionLabel("Precision")
                    PrecisionTrimPanel(state = state, viewModel = viewModel)

                    SectionLabel("Separate audio & sync")
                    SyncPanel(
                        state = state,
                        viewModel = viewModel,
                        onPickAudio = { pickAudioTrack.launch(arrayOf("audio/*", "video/*")) }
                    )

                    SectionLabel("Compress & convert")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Quality.entries.forEach { quality ->
                            SelectableChip(
                                label = quality.label,
                                selected = state.quality == quality && !state.fitToSize,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    viewModel.setFitToSize(false)
                                    viewModel.setQuality(quality)
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
                        OptionToggle("Mute camera audio", state.muteOriginal, Modifier.weight(1f)) {
                            viewModel.setMuteOriginal(it)
                        }
                        OptionToggle("Rotate 90°", state.rotationDegrees != 0, Modifier.weight(1f)) {
                            viewModel.toggleRotate()
                        }
                    }

                    SectionLabel("Color")
                    LabeledSlider("Brightness", state.brightness, -1f..1f, viewModel::setBrightness)
                    LabeledSlider("Contrast", state.contrast, -1f..1f, viewModel::setContrast)
                    LabeledSlider("Saturation", state.saturation, -1f..1f, viewModel::setSaturation)

                    SectionLabel("Captions & text")
                    TextOverlaySection(state = state, viewModel = viewModel)

                    SectionLabel("Merge clips")
                    MergeQueueSection(
                        state = state,
                        onAddClip = {
                            pickExtraClip.launch(
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
                    Text(
                        it,
                        color = SquishColors.Pink,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                SquishPrimaryButton(
                    text = if (state.isExporting) "Exporting…" else "Export video",
                    enabled = !state.isExporting && !state.isLoadingSource,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        errorMessage = null
                        viewModel.export(onResult = onExported, onError = { errorMessage = it })
                    }
                )
            }
        }
    }
}
