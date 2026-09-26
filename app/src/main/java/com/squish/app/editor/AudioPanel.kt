package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.AccentBadge
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.components.WaveformCanvas
import com.squish.app.ui.theme.SquishColors

/**
 * Everything about sound in one place: the camera's own audio, and as many added
 * tracks as the edit needs. Each track is an ordinary timeline clip, so the panel
 * works on whichever one is selected and the strip does the rest - dragging,
 * trimming at the edges and cutting at the playhead all happen there.
 */
@Composable
fun AudioPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickAudio: () -> Unit
) {
    val target = state.targetAudioClip

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        BeatPanel(state, viewModel)

        PanelCard {
            PanelHeading(
                "Camera audio",
                if (state.sourceHasAudio) "The sound recorded with the video"
                else "This clip has no audio track",
                icon = Icons.Filled.Mic,
                accent = SquishColors.Cyan,
                trailing = {
                    SquishToggleSwitch(
                        checked = !state.muteOriginal,
                        onCheckedChange = { viewModel.setMuteOriginal(!it) }
                    )
                }
            )
            if (!state.muteOriginal && state.sourceHasAudio) {
                LabeledSlider("Level", state.originalVolume, 0f..1f, viewModel::setOriginalVolume)
            }
        }

        PanelCard {
            PanelHeading(
                "Added tracks",
                "Music, voiceover, or a separate mic",
                icon = Icons.Filled.MusicNote,
                accent = SquishColors.Cyan,
                trailing = {
                    Text(
                        "${state.audioClips.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = SquishColors.Cyan
                    )
                }
            )

            if (state.audioClips.isEmpty()) {
                Text(
                    "Add as many as you like — they can overlap, and Squish can line any of them up with the picture for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
            } else {
                state.audioClips.forEach { clip ->
                    TrackRow(
                        clip = clip,
                        selected = clip.id == target?.id,
                        onSelect = { viewModel.selectClip(clip.id) },
                        onRemove = { viewModel.removeAudioClip(clip.id) }
                    )
                }
            }

            SquishOutlinedButton(
                text = if (state.audioClips.isEmpty()) "Add audio track" else "Add another track",
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickAudio
            )
        }

        if (target == null) return@Column

        PanelCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AccentBadge(icon = Icons.Filled.GraphicEq, accent = SquishColors.Cyan)
                Column(modifier = Modifier.weight(1f)) {
                    Text(target.label, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary, maxLines = 1)
                    SyncStatusLine(state)
                }
            }

            WaveformCanvas(
                waveform = state.waveformFor(target),
                color = SquishColors.Teal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SquishColors.Background)
                    .padding(vertical = 4.dp)
            )

            LabeledSlider("Level", target.volume, 0f..1f) { viewModel.setAudioClipVolume(target.id, it) }
        }

        PanelCard {
            PanelHeading(
                "Trim the track",
                "Which part of the audio file plays — the same thing the clip's edges do",
                icon = Icons.Filled.ContentCut,
                accent = SquishColors.Cyan
            )
            AudioPointRow(
                label = "In",
                value = Timecode.format(target.sourceInMs),
                onBack = { viewModel.setAudioTrim(target.id, target.sourceInMs - 100, target.sourceOutMs) },
                onForward = { viewModel.setAudioTrim(target.id, target.sourceInMs + 100, target.sourceOutMs) }
            )
            AudioPointRow(
                label = "Out",
                value = Timecode.format(target.sourceOutMs),
                onBack = { viewModel.setAudioTrim(target.id, target.sourceInMs, target.sourceOutMs - 100) },
                onForward = { viewModel.setAudioTrim(target.id, target.sourceInMs, target.sourceOutMs + 100) }
            )
            Text(
                "${Timecode.format(target.durationMs)} of audio selected",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }

        PanelCard {
            PanelHeading(
                "Place on the timeline",
                "Where this track starts",
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                accent = SquishColors.Cyan
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Starts at ${Timecode.format(target.timelineStartMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary
                )
                SquishOutlinedButton(
                    text = "Move to playhead",
                    onClick = { viewModel.placeAudioAtPlayhead(target.id) }
                )
            }
            if (target.timelineStartMs > 0 && !state.sourceHasAudio) {
                Text(
                    "This clip has no audio track to pad the gap with, so on export the cue will start at the beginning of the video instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )
            }
        }

        PanelCard {
            PanelHeading(
                "Align to picture",
                "Nudge it into sync, or let Squish find the match",
                icon = Icons.Filled.Sync,
                accent = SquishColors.Cyan
            )
            val offsetMs = target.sourceInMs - target.timelineStartMs
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SquishColors.Background)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    Timecode.formatOffset(offsetMs, state.fps),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (offsetMs == 0L) SquishColors.TextMuted else SquishColors.Teal,
                    textAlign = TextAlign.Center
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                NudgeButton("-1f", Modifier.weight(1f)) { viewModel.nudgeAudioOffsetFrames(target.id, -1) }
                NudgeButton("-10ms", Modifier.weight(1f)) { viewModel.nudgeAudioOffset(target.id, -10) }
                NudgeButton("+10ms", Modifier.weight(1f)) { viewModel.nudgeAudioOffset(target.id, 10) }
                NudgeButton("+1f", Modifier.weight(1f)) { viewModel.nudgeAudioOffsetFrames(target.id, 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(
                    text = if (state.syncStatus == SyncStatus.Analyzing) "Listening…" else "Auto-sync",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.runAutoSync(target.id) }
                )
                SquishOutlinedButton(
                    text = "Reset",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.resetAudioAlignment(target.id) }
                )
            }
        }
    }
}

/** One added sound. Selecting it points the whole panel - and the strip - at it. */
@Composable
private fun TrackRow(clip: Clip, selected: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SquishColors.SurfaceElevated else SquishColors.Background)
            .border(
                width = 1.dp,
                color = if (selected) SquishColors.Teal else SquishColors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                clip.label,
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                maxLines = 1
            )
            Text(
                "${Timecode.format(clip.durationMs)} at ${Timecode.format(clip.timelineStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
        Text(
            "Remove",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.Pink,
            modifier = Modifier.clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) =
    PanelSurface(accent = SquishColors.Cyan, content = content)

@Composable
private fun SyncStatusLine(state: EditorUiState) {
    val (message, color) = when (state.syncStatus) {
        SyncStatus.Idle -> "Drag it on the strip, or tap auto-sync" to SquishColors.TextMuted
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
