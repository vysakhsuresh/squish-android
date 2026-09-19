package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.components.WaveformCanvas
import com.squish.app.ui.theme.SquishColors

/**
 * Everything about sound in one place: the camera's own audio, and a second track
 * you can trim, place anywhere on the timeline, and align to the picture.
 */
@Composable
fun AudioPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickAudio: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Camera audio", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                    Text(
                        if (state.sourceHasAudio) "The sound recorded with the video"
                        else "This clip has no audio track",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                }
                SquishToggleSwitch(
                    checked = !state.muteOriginal,
                    onCheckedChange = { viewModel.setMuteOriginal(!it) }
                )
            }
            if (!state.muteOriginal && state.sourceHasAudio) {
                LabeledSlider("Level", state.originalVolume, 0f..1f, viewModel::setOriginalVolume)
            }
        }

        if (!state.hasSeparateAudio) {
            PanelCard {
                Text(
                    "Add music, a voiceover, or sound recorded on a separate mic. Squish can line it up with the picture for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
                SquishOutlinedButton(
                    text = "Add audio track",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPickAudio
                )
            }
            return@Column
        }

        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.audioTrackName ?: "Audio track",
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

            WaveformCanvas(
                waveform = state.audioWaveform,
                color = SquishColors.Teal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SquishColors.Background)
                    .padding(vertical = 4.dp)
            )

            LabeledSlider("Level", state.audioVolume, 0f..1f, viewModel::setAudioVolume)
        }

        PanelCard {
            Text("Trim the track", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Text(
                "Which part of the audio file plays.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            AudioPointRow(
                label = "In",
                value = Timecode.format(state.audioTrimStartMs),
                onBack = { viewModel.setAudioTrim(state.audioTrimStartMs - 100, state.audioTrimEndMs) },
                onForward = { viewModel.setAudioTrim(state.audioTrimStartMs + 100, state.audioTrimEndMs) }
            )
            AudioPointRow(
                label = "Out",
                value = Timecode.format(state.audioTrimEndMs),
                onBack = { viewModel.setAudioTrim(state.audioTrimStartMs, state.audioTrimEndMs - 100) },
                onForward = { viewModel.setAudioTrim(state.audioTrimStartMs, state.audioTrimEndMs + 100) }
            )
            Text(
                "${Timecode.format(state.audioSliceDurationMs)} of audio selected",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }

        PanelCard {
            Text("Place on the timeline", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Text(
                "The moment in the video where this audio starts.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Starts at ${Timecode.format(state.audioPlacementMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary
                )
                SquishOutlinedButton(
                    text = "Move to playhead",
                    onClick = { viewModel.placeAudioAtPlayhead() }
                )
            }
            if (state.audioPlacementMs > state.trimStartMs && !state.sourceHasAudio) {
                Text(
                    "This clip has no audio track to pad the gap with, so the cue will start at the beginning of the video instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )
            }
        }

        PanelCard {
            Text("Align to picture", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SquishColors.Background)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    Timecode.formatOffset(state.audioOffsetMs, state.fps),
                    style = MaterialTheme.typography.headlineSmall,
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
                    onClick = { viewModel.resetAudioAlignment() }
                )
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun SyncStatusLine(state: EditorUiState) {
    val (message, color) = when (state.syncStatus) {
        SyncStatus.Idle -> "Drag the audio lane, or tap auto-sync" to SquishColors.TextMuted
        SyncStatus.Analyzing -> "Matching waveforms…" to SquishColors.TextSecondary
        SyncStatus.Matched -> "Matched · ${(state.syncConfidence * 100).toInt()}% confidence" to SquishColors.Teal
        SyncStatus.NoMatch -> "No clear match — align it by hand" to SquishColors.Yellow
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
private fun AudioPointRow(label: String, value: String, onBack: () -> Unit, onForward: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = SquishColors.Teal, modifier = Modifier.width(30.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, modifier = Modifier.weight(1f))
        NudgeButton("◀", Modifier.width(46.dp), onBack)
        NudgeButton("▶", Modifier.width(46.dp), onForward)
    }
}

@Composable
fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
