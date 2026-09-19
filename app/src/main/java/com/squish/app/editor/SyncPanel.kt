package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * Dual-system sound workflow: attach the separately recorded track, let the app
 * find the alignment, then fine-tune by frame if the ear disagrees.
 */
@Composable
fun SyncPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickAudio: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!state.hasSeparateAudio) {
            Text(
                "Recorded sound on a separate mic? Add the track and Squish will line it up with the picture automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
            SquishOutlinedButton(
                text = "Add separate audio track",
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickAudio
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.audioTrackName ?: "External track",
                    style = MaterialTheme.typography.titleSmall,
                    color = SquishColors.TextPrimary,
                    maxLines = 1
                )
                SyncStatusLine(state)
            }
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable { viewModel.setAudioTrack(null) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SquishColors.Background)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                Timecode.formatOffset(state.audioOffsetMs, state.fps),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                color = if (state.audioOffsetMs == 0L) SquishColors.TextMuted else SquishColors.Teal,
                textAlign = TextAlign.Center
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            NudgeButton("-1f", Modifier.weight(1f)) { viewModel.nudgeAudioOffsetFrames(-1) }
            NudgeButton("-10ms", Modifier.weight(1f)) { viewModel.nudgeAudioOffset(-10) }
            NudgeButton("+10ms", Modifier.weight(1f)) { viewModel.nudgeAudioOffset(10) }
            NudgeButton("+1f", Modifier.weight(1f)) { viewModel.nudgeAudioOffsetFrames(1) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = if (state.syncStatus == SyncStatus.Analyzing) "Listening…" else "Auto-sync",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.runAutoSync() }
            )
            SquishOutlinedButton(
                text = "Reset",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.resetAudioOffset() }
            )
        }

        if (state.syncHeadTrimMs > 0) {
            Text(
                "Holding this offset trims ${state.syncHeadTrimMs} ms off the head of the clip — the track has no audio before that point.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )
        }

        LabeledSlider("Camera audio", state.originalVolume, 0f..1f, viewModel::setOriginalVolume)
        LabeledSlider("External track", state.audioVolume, 0f..1f, viewModel::setAudioVolume)
    }
}

@Composable
private fun SyncStatusLine(state: EditorUiState) {
    val (message, color) = when (state.syncStatus) {
        SyncStatus.Idle -> "Drag the audio lane, or tap auto-sync" to SquishColors.TextMuted
        SyncStatus.Analyzing -> "Matching waveforms…" to SquishColors.TextSecondary
        SyncStatus.Matched ->
            "Matched · ${(state.syncConfidence * 100).toInt()}% confidence" to SquishColors.Teal
        SyncStatus.NoMatch ->
            "No clear match — align it by hand below" to SquishColors.Yellow
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.syncStatus == SyncStatus.Analyzing) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = SquishColors.Teal,
                strokeWidth = 2.dp
            )
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SquishColors.Background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = SquishColors.TextPrimary)
    }
}

@Composable
fun PrecisionTrimPanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrimPointRow(
            label = "In",
            value = Timecode.formatWithFrame(state.trimStartMs, state.fps),
            accent = SquishColors.Coral,
            onNudgeBack = { viewModel.nudgeTrim(isStart = true, frames = -1) },
            onNudgeForward = { viewModel.nudgeTrim(isStart = true, frames = 1) },
            onSetToPlayhead = { viewModel.setTrimPointToPlayhead(isStart = true) }
        )
        TrimPointRow(
            label = "Out",
            value = Timecode.formatWithFrame(state.trimEndMs, state.fps),
            accent = SquishColors.Coral,
            onNudgeBack = { viewModel.nudgeTrim(isStart = false, frames = -1) },
            onNudgeForward = { viewModel.nudgeTrim(isStart = false, frames = 1) },
            onSetToPlayhead = { viewModel.setTrimPointToPlayhead(isStart = false) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${Timecode.format(state.trimmedDurationMs)} selected · ${state.fps.toInt()} fps",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
            Text(
                "Playhead ${Timecode.format(state.playheadMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = "Drop marker",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.addMarkerAtPlayhead() }
            )
            SquishOutlinedButton(
                text = if (state.markers.isEmpty()) "No markers" else "Clear ${state.markers.size}",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.clearMarkers() }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Snap to markers", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                Text(
                    "Trim handles jump to nearby markers",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
            }
            SquishToggleSwitch(checked = state.snapToMarkers, onCheckedChange = viewModel::setSnapToMarkers)
        }
    }
}

@Composable
private fun TrimPointRow(
    label: String,
    value: String,
    accent: Color,
    onNudgeBack: () -> Unit,
    onNudgeForward: () -> Unit,
    onSetToPlayhead: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = accent, modifier = Modifier.width(28.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = SquishColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        NudgeButton("◀", Modifier.width(44.dp), onNudgeBack)
        NudgeButton("▶", Modifier.width(44.dp), onNudgeForward)
        NudgeButton("Set", Modifier.width(52.dp), onSetToPlayhead)
    }
}
