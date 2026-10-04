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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Finds the pulse of the music and turns it into edit points.
 *
 * The whole value of this is that a cut landing a fifth of a second off the beat
 * reads as a mistake to anyone watching, even to someone who could not say why -
 * and placing forty of them by ear is an afternoon. Once the grid exists, two
 * things use it: markers, which every snap in the editor already respects, and a
 * razor that cuts the whole track on the bar in one go.
 *
 * The beats live on the sound (Clip.beats) as dots that travel with it, the
 * way CapCut draws them; "Add beat" drops one at the playhead by ear while
 * listening, which is how most people mark a drop. The density - every beat,
 * every second, every bar - is a toggle the dots, the markers and the cuts all
 * read, so what is drawn is what a cut lands on.
 *
 * [showClear] puts a Clear link in the heading: for the Sound sheet's Sync
 * chip, which has no Reset. Under the Beats tool the sheet's Reset clears the
 * grid, and a second control for the same thing beside it was one too many.
 */
@Composable
fun BeatPanel(state: EditorUiState, viewModel: EditorViewModel, showClear: Boolean = true) {
    val beats = state.beats
    val hasGrid = state.hasBeatGrid
    val onClip = state.beatClip
    // Found on the camera's own sound: there is no clip to draw dots on, so
    // the grid is not seen on the strip until it is marked.
    val onCamera = hasGrid && beats.clipId == null

    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Beats",
            when {
                beats.running -> "Listening to ${beats.listeningTo}"
                hasGrid && onClip != null -> "${state.allBeats.size} beats on ${onClip.label} - they move with it"
                hasGrid -> "${state.allBeats.size} beats on ${beats.clipLabel.ifBlank { "the timeline" }}"
                else -> "Find the pulse, or tap it in, and cut to it"
            },
            icon = Icons.Filled.GraphicEq,
            accent = SquishColors.Cyan,
            trailing = if (!showClear || !hasGrid) null else ({
                com.squish.app.ui.components.TextAction("Clear", color = SquishColors.Pink) { viewModel.audio.clearBeats() }
            })
        )

        when {
            beats.running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = SquishColors.Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "Decoding and analysing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
            }

            beats.failed && !hasGrid -> {
                Text(
                    "No pulse found in ${beats.listeningTo}. Speech and ambient sound " +
                        "often have none — try a track with drums on it, or tap the beats in by ear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SquishOutlinedButton(
                        text = "Try again",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.detectBeats() }
                    )
                    SquishOutlinedButton(
                        text = "Add beat",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.addBeatAtPlayhead() }
                    )
                }
            }

            hasGrid -> {
                // A second listen that found nothing leaves the grid it had; it
                // used to throw that away along with the failed answer.
                if (beats.failed) {
                    Text(
                        "No pulse found in ${beats.listeningTo} — the grid from ${beats.clipLabel} is kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.Yellow
                    )
                }
                TempoReadout(state)

                if (onCamera) {
                    Text(
                        "Found on the camera sound, so there is no clip to draw the dots on. " +
                            "Mark below puts a line across the strip on each one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                }

                DensityToggle(beats.every) { viewModel.audio.setBeatDensity(it) }

                if (beats.bpm > 0f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        SquishOutlinedButton(
                            text = "÷2",
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.audio.scaleBeats(faster = false) }
                        )
                        SquishOutlinedButton(
                            text = "×2",
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.audio.scaleBeats(faster = true) }
                        )
                        SquishOutlinedButton(
                            text = "Shift bar",
                            modifier = Modifier.weight(1.4f),
                            onClick = { viewModel.audio.nudgeDownbeat() }
                        )
                    }
                    Text(
                        "A slow track with busy hi-hats has two defensible tempos, and " +
                            "two people tapping along will disagree. If it counted at the " +
                            "wrong level, move it an octave.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SquishOutlinedButton(
                        text = "Add beat",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.addBeatAtPlayhead() }
                    )
                    SquishOutlinedButton(
                        text = "Find again",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.detectBeats() }
                    )
                }

                BeatAction(
                    icon = Icons.Filled.Flag,
                    title = "Snap to the beat",
                    body = "Drops a marker on every dot. Dragging a clip, setting an " +
                        "in point and moving a line of text all snap to markers already. " +
                        "Markers you placed yourself stay.",
                    accent = SquishColors.Amber,
                    action = "Mark ${densityName(beats.every)}"
                ) { viewModel.audio.markBeats() }

                BeatAction(
                    icon = Icons.Filled.ContentCut,
                    title = "Cut on the beat",
                    body = "Razors the main video track at once, cutting every shot " +
                        "where a dot lands. The music and any overlays stay whole.",
                    accent = SquishColors.Violet,
                    action = "Cut ${densityName(beats.every)}"
                ) { viewModel.audio.cutOnBeats() }

                BeatAction(
                    icon = Icons.Filled.MusicNote,
                    title = "Fit shots to the beat",
                    body = "Shortens each shot so it ends on a dot - an edit that moves with " +
                        "the song, the way AutoCut makes one. Shots only get shorter.",
                    accent = SquishColors.Cyan,
                    action = "Fit ${densityName(beats.every)}"
                ) { viewModel.audio.fitShotsToBeats() }

                // The other half of fitting to the beat, and the one people
                // arrive with: a pile of clips and a song. Offered only with a
                // sound to fit to, since the song's length is the whole input.
                if (state.audioClips.isNotEmpty()) {
                    BeatAction(
                        icon = Icons.Filled.MusicNote,
                        title = "Fit the shots to the song",
                        body = "Gives every shot an equal share of the song and puts each join on a " +
                            "dot, so the edit ends with the music. A shot with less footage than its " +
                            "share keeps what it has.",
                        accent = SquishColors.Teal,
                        action = "Fit to the song"
                    ) { viewModel.audio.spreadShotsOverSong() }
                }
            }

            else -> {
                Text(
                    "Squish listens to the music on the timeline, works out the tempo, " +
                        "and puts a dot on every beat of the song - with no song, it listens " +
                        "to the camera sound, and Mark draws those beats across the strip. " +
                        "Or play it and tap the beats in yourself. Then snap your cuts to " +
                        "them, or have it cut the picture on the bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SquishOutlinedButton(
                        text = "Find the beat",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.detectBeats() }
                    )
                    SquishOutlinedButton(
                        text = "Add beat",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.audio.addBeatAtPlayhead() }
                    )
                }
            }
        }
    }
}

private fun densityName(every: Int): String = when (every) {
    1 -> "every beat"
    2 -> "every 2nd beat"
    else -> "every bar"
}

/**
 * The density, as the toggle it is: the chip that is on says what the dots
 * are drawn at and what the two actions below act on. The chips used to be
 * one-shot buttons dressed as a segmented control, firing on the tap.
 */
@Composable
private fun DensityToggle(every: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(1 to "Every beat", 2 to "Every 2", 4 to "Every bar").forEach { (n, label) ->
            SelectableChip(
                label = label,
                selected = every == n,
                accentColor = SquishColors.Cyan,
                modifier = Modifier.weight(1f),
                onClick = { onChange(n) }
            )
        }
    }
}

/**
 * The tempo, and how much to believe it.
 *
 * Confidence is shown because it means something specific: how periodic the audio
 * actually was, not how neatly the beats came out. A spoken-word track will
 * always produce a tidy grid and should not be trusted, and this is the only
 * thing on screen that says so before someone cuts forty clips to it. A grid
 * tapped in by hand has no tempo to show, and says so.
 */
@Composable
private fun TempoReadout(state: EditorUiState) {
    val beats = state.beats
    val verdict = when {
        beats.bpm <= 0f -> "Tapped by ear" to SquishColors.TextSecondary
        beats.confidence >= 0.65f -> "Strong pulse" to SquishColors.Cyan
        beats.confidence >= 0.35f -> "Some pulse" to SquishColors.Amber
        else -> "Weak — check it by ear" to SquishColors.Pink
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (beats.bpm > 0f) "${"%.1f".format(beats.bpm)} BPM" else "${state.allBeats.size} beats",
                style = MaterialTheme.typography.headlineSmall,
                color = SquishColors.TextPrimary
            )
            Text(
                verdict.first,
                style = MaterialTheme.typography.labelSmall,
                color = verdict.second
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (beats.bpm > 0f) {
                Text(
                    "Bar starts on beat ${beats.downbeatOffset + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            Text(
                "${state.barGrid.size} bars",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }
    }
}

/** One thing to do with the grid, at the density chosen above. */
@Composable
private fun BeatAction(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
    action: String,
    onAct: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        SquishOutlinedButton(text = action, modifier = Modifier.fillMaxWidth(), onClick = onAct)
    }
}
