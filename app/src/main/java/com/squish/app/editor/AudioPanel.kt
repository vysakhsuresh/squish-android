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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.AccentBadge
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.components.WaveformCanvas
import com.squish.app.ui.theme.SquishColors

/**
 * The Music chip of Sound, with adding first: a file from the phone, or a song
 * from the library, lands at the playhead. Sound, then Add, is two taps; the
 * file picker used to be the fifth card down a panel of nine.
 *
 * Each track is an ordinary timeline clip, so everything else about one -
 * dragging, trimming at the edges, cutting, its level - is done on the strip
 * and on its own toolbar once it is selected.
 */
@Composable
fun SoundMusicPanel(state: EditorUiState, viewModel: EditorViewModel, onPickAudio: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SquishPrimaryButton(
            text = "Add from your files",
            modifier = Modifier.fillMaxWidth(),
            onClick = onPickAudio
        )

        MusicPanel(viewModel)

        if (state.audioClips.isNotEmpty()) {
            PanelCard {
                PanelHeading(
                    "On the timeline",
                    "Tap one to work on it",
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
                state.audioClips.sortedBy { it.timelineStartMs }.forEach { clip ->
                    TrackRow(
                        clip = clip,
                        selected = clip.id == state.selectedClipId,
                        onSelect = { viewModel.selectClip(clip.id) },
                        onRemove = { viewModel.audio.removeAudioClip(clip.id) }
                    )
                }
            }
        }
    }
}

/** The Voice & FX chip of Sound: the recorded voice, and the camera's own sound. */
@Composable
fun SoundVoicePanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.sourceHasAudio) {
            PanelCard {
                PanelHeading(
                    "Voice",
                    "Change how the recorded voice sounds",
                    icon = Icons.Filled.Mic,
                    accent = SquishColors.Cyan
                )
                VoiceEffect.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { effect ->
                            val on = state.voiceEffect == effect
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (on) SquishColors.Cyan.copy(alpha = 0.14f) else SquishColors.Background)
                                    .border(
                                        if (on) 1.5.dp else 1.dp,
                                        if (on) SquishColors.Cyan else SquishColors.Border,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.audio.setVoiceEffect(effect) }
                                    .padding(vertical = 10.dp)
                            ) {
                                GlyphTile(effect.glyph, size = 36.dp)
                                Text(
                                    effect.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (on) SquishColors.TextPrimary else SquishColors.TextSecondary
                                )
                            }
                        }
                        repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
        CameraSoundPanel(state, viewModel)
    }
}

/**
 * The sound recorded with the video: on or off, and how loud. One setting for
 * every shot on the main track - which the heading says, since it also opens
 * from a single shot's Volume.
 */
@Composable
fun CameraSoundPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelCard {
        PanelHeading(
            "Camera sound",
            if (state.sourceHasAudio) "The sound recorded with the video, on every shot"
            else "This clip has no audio track",
            icon = Icons.Filled.Mic,
            accent = SquishColors.Cyan,
            trailing = {
                SquishToggleSwitch(
                    checked = !state.muteOriginal,
                    onCheckedChange = { viewModel.audio.setMuteOriginal(!it) }
                )
            }
        )
        if (!state.muteOriginal && state.sourceHasAudio) {
            LabeledSlider(
                "Level", state.originalVolume, 0f..1f,
                onFinished = viewModel::endGesture,
                onChange = viewModel.audio::setOriginalVolume
            )
        }
    }
}

/**
 * The Sync chip of Sound: the beat grid, the markers it fills in, and lining the
 * selected sound - or the first one - up with the picture.
 */
@Composable
fun SoundSyncPanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BeatPanel(state, viewModel)
        state.targetAudioClip?.let { AlignPanel(state, it, viewModel) }
        MarkersPanel(state, viewModel)
    }
}

/** One added sound's level, over its waveform: the sound's Volume. */
@Composable
fun SoundVolumePanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AccentBadge(icon = Icons.Filled.GraphicEq, accent = SquishColors.Cyan)
            Text(
                clip.label,
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        WaveformCanvas(
            waveform = state.waveformFor(clip),
            color = SquishColors.Teal,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SquishColors.Background)
                .padding(vertical = 4.dp)
        )

        LabeledSlider("Level", clip.volume, 0f..1f, onFinished = viewModel::endGesture) {
            viewModel.audio.setAudioClipVolume(clip.id, it)
        }
    }
}

/** Lining one sound up with the picture, by ear or by Squish listening to both: the sound's Sync. */
@Composable
fun AlignPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    PanelCard {
        PanelHeading(
            "Align ${clip.label}",
            "Nudge it into sync, or let Squish find the match",
            icon = Icons.Filled.Sync,
            accent = SquishColors.Cyan
        )
        SyncStatusLine(state)
        // Against the picture, not against the timeline: the head shot's own
        // trim and position are taken off. Without that a plain trim of the
        // shot read as a sync offset, and a synced sound read as off.
        val offsetMs = clip.sourceInMs - clip.timelineStartMs - state.headPictureDeltaMs
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
        // A sound playing at its own speed cannot follow a shot that does not:
        // it can meet it at one moment and drifts from there. Said, rather
        // than letting a zero above read as "in sync all the way through".
        if (state.headVideoClip?.speedRamp?.isIdentity == false) {
            Text(
                "The first shot is retimed, so a sound at normal speed can only match it at one " +
                    "moment. Squish lines them up at the shot's first frame, and reads the offset there.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            NudgeButton("-1f", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffsetFrames(clip.id, -1) }
            NudgeButton("-10ms", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffset(clip.id, -10) }
            NudgeButton("+10ms", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffset(clip.id, 10) }
            NudgeButton("+1f", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffsetFrames(clip.id, 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = if (state.syncStatus == SyncStatus.Analyzing) "Listening…" else "Auto-sync",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.audio.runAutoSync(clip.id) }
            )
            SquishOutlinedButton(
                text = "Start at 0:00",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.audio.resetAudioAlignment(clip.id) }
            )
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
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
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
