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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors

/**
 * The editor's tools. Each carries a colour, and it is the same colour its track
 * wears on the timeline - so the rail is a legend for the strip above it rather
 * than nine identically grey words.
 */
enum class EditorTab(val label: String, val icon: ImageVector, val accent: Color) {
    Trim("Trim", Icons.Filled.ContentCut, SquishColors.Violet),
    Crop("Crop", Icons.Filled.Crop, SquishColors.Violet),
    Speed("Speed", Icons.Filled.Speed, SquishColors.Blue),
    Mix("Mix", Icons.Filled.Layers, SquishColors.Magenta),
    Motion("Motion", Icons.Filled.Animation, SquishColors.Amber),
    Audio("Audio", Icons.Filled.GraphicEq, SquishColors.Cyan),
    Captions("Captions", Icons.Filled.ClosedCaption, SquishColors.Amber),
    Effects("Effects", Icons.Filled.AutoAwesome, SquishColors.Magenta),
    Export("Export", Icons.Filled.FileUpload, SquishColors.Blue)
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
    var tab by remember { mutableStateOf(EditorTab.Trim) }
    var exportSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    val pickAudioTrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.addAudioTrack(it)
        }
    }
    val pickExtraClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addVideoClip(it) }
    }
    val pickOverlayClip = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addOverlayClip(it) }
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackOrb(accent = tab.accent, onClick = onBack, size = 40.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.videoClips.firstOrNull()?.label ?: "Your edit",
                        style = MaterialTheme.typography.titleMedium,
                        color = SquishColors.TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        "${state.videoClips.size} clips · ${Timecode.format(state.trimmedDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (state.isExporting) SquishColors.Surface else SquishColors.Primary)
                        // Opens the sheet rather than firing: this is the one
                        // irreversible, minutes-long action in the app, and it used
                        // to be the only one with no confirmation.
                        .clickable(enabled = !state.isExporting && !state.isLoadingSource) {
                            exportSheetOpen = true
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
                    CircularProgressIndicator(color = SquishColors.Primary)
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SquishColors.Surface)
            ) {
                TimelinePreview(
                    videoClips = state.videoClips,
                    audioClips = state.audioClips,
                    captions = state.textOverlays,
                    fallbackUri = sourceUri,
                    proxyUri = state.proxyUri,
                    muteOriginal = state.muteOriginal,
                    originalVolume = state.originalVolume,
                    grade = state.grade,
                    speed = state.speed,
                    sourceAspect = state.previewAspect,
                    playheadMs = state.playheadMs,
                    scrubNonce = state.scrubNonce,
                    onPositionChange = viewModel::setPlayhead,
                    onPlayingChange = viewModel::setPlaying,
                    modifier = Modifier.fillMaxSize()
                )
                // Live framing while cropping, so the ratio is never chosen blind.
                if (tab == EditorTab.Crop || state.cropAspect != CropAspect.Original) {
                    CropOverlay(aspect = state.cropAspect, modifier = Modifier.fillMaxSize())
                }
            }

            ProxyIndicator(status = state.proxyStatus, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

            Spacer(modifier = Modifier.height(2.dp))

            val timeline = state.toTimeline()
            TimelineEditor(
                state = timeline,
                onSelect = viewModel::selectClip,
                onMove = viewModel::moveClip,
                onTrim = viewModel::trimClip,
                onScrub = viewModel::scrubTo,
                onTransitionTap = { clipId ->
                    viewModel.selectClip(clipId)
                    tab = EditorTab.Mix
                }
            )

            TimelineActionBar(
                state = timeline,
                onSplit = viewModel::splitAtPlayhead,
                onDelete = viewModel::deleteSelectedClip,
                onCloseGaps = viewModel::closeGaps,
                onZoomIn = viewModel::zoomIn,
                onZoomOut = viewModel::zoomOut,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            state.recovery?.let { offer ->
                RecoveryBanner(
                    offer = offer,
                    onRestore = viewModel::acceptRecovery,
                    onDiscard = viewModel::dismissRecovery,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            state.failure?.let { failure ->
                FailureCard(
                    error = failure,
                    onDismiss = viewModel::clearFailure,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
                    EditorTab.Mix -> TransitionPanel(
                        state = state,
                        viewModel = viewModel,
                        onAddOverlay = {
                            pickOverlayClip.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        }
                    )
                    EditorTab.Motion -> MotionPanel(state, viewModel)
                    EditorTab.Audio -> AudioPanel(
                        state = state,
                        viewModel = viewModel,
                        onPickAudio = { pickAudioTrack.launch(arrayOf("audio/*", "video/*")) }
                    )
                    EditorTab.Captions -> CaptionsPanel(state, viewModel)
                    EditorTab.Effects -> EffectsPanel(state, viewModel)
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

        if (exportSheetOpen) {
            ExportSheet(
                state = state,
                viewModel = viewModel,
                onDismiss = { exportSheetOpen = false },
                onRender = {
                    viewModel.export(onResult = onExported)
                }
            )
        }
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
            // A sprung nudge rather than a colour swap: at a glance down a row of
            // nine, movement is what tells you which one you just pressed.
            val scale by animateFloatAsState(if (isSelected) 1.06f else 1f, spring(), label = "tabScale")
            val tint by animateColorAsState(
                if (isSelected) SquishColors.Background else SquishColors.TextSecondary,
                label = "tabTint"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isSelected) Modifier.background(accentSweep(entry.accent))
                        else Modifier.background(SquishColors.Background)
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    entry.icon,
                    contentDescription = entry.label,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Text(entry.label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}
