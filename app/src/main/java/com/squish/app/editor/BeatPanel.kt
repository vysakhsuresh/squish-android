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
 */
@Composable
fun BeatPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val beats = state.beats

    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Beat",
            when {
                beats.running -> "Listening to ${beats.clipLabel}"
                beats.hasBeats -> "${beats.beatsMs.size} beats in ${beats.clipLabel}"
                else -> "Find the pulse and cut to it"
            },
            icon = Icons.Filled.GraphicEq,
            accent = SquishColors.Cyan,
            trailing = {
                if (beats.hasBeats) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.clearBeats() }
                    )
                }
            }
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

            beats.failed -> {
                Text(
                    "No pulse found in ${beats.clipLabel}. Speech and ambient sound " +
                        "often have none — try a track with drums on it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )
                SquishOutlinedButton(
                    text = "Try again",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.detectBeats() }
                )
            }

            beats.hasBeats -> {
                TempoReadout(beats)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SquishOutlinedButton(
                        text = "÷2",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.scaleBeats(faster = false) }
                    )
                    SquishOutlinedButton(
                        text = "×2",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.scaleBeats(faster = true) }
                    )
                    SquishOutlinedButton(
                        text = "Shift bar",
                        modifier = Modifier.weight(1.4f),
                        onClick = { viewModel.nudgeDownbeat() }
                    )
                }
                Text(
                    "A slow track with busy hi-hats has two defensible tempos, and " +
                        "two people tapping along will disagree. If it counted at the " +
                        "wrong level, move it an octave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )

                BeatAction(
                    icon = Icons.Filled.Flag,
                    title = "Snap to the beat",
                    body = "Drops a marker on every beat. Dragging a clip, setting an " +
                        "in point and moving a caption all snap to markers already.",
                    accent = SquishColors.Amber
                ) { n -> viewModel.markBeats(n) }

                BeatAction(
                    icon = Icons.Filled.ContentCut,
                    title = "Cut on the beat",
                    body = "Razors the whole video track at once. Every clip on the " +
                        "strip is cut where the beat lands.",
                    accent = SquishColors.Violet
                ) { n -> viewModel.cutOnBeats(n) }
            }

            else -> {
                Text(
                    "Squish listens to the music on the timeline, works out the tempo, " +
                        "and marks every beat. Then you can snap your cuts to it, or " +
                        "have it cut the whole track on the bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
                SquishOutlinedButton(
                    text = "Find the beat",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.detectBeats() }
                )
            }
        }
    }
}

/**
 * The tempo, and how much to believe it.
 *
 * Confidence is shown because it means something specific: how periodic the audio
 * actually was, not how neatly the beats came out. A spoken-word track will
 * always produce a tidy grid and should not be trusted, and this is the only
 * thing on screen that says so before someone cuts forty clips to it.
 */
@Composable
private fun TempoReadout(beats: BeatProgress) {
    val verdict = when {
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
        Column {
            Text(
                "${"%.1f".format(beats.bpm)} BPM",
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
            Text(
                "Bar starts on beat ${beats.downbeatOffset + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
            Text(
                "${beats.every(4).size} bars",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }
    }
}

/** One thing to do with the grid, at one of three resolutions. */
@Composable
private fun BeatAction(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
    onEvery: (Int) -> Unit
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(1 to "Every beat", 2 to "Every 2", 4 to "Every bar").forEach { (n, label) ->
                SelectableChip(
                    label = label,
                    selected = false,
                    accentColor = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onEvery(n) }
                )
            }
        }
    }
}
