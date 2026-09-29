package com.squish.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.TransitionType
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors

/**
 * How a shot arrives from the one before it. Opened from the shot's toolbar, or
 * by tapping the mark on its join in the strip.
 *
 * Only ever for a main-track shot with a shot before it: the toolbar offers it
 * nowhere else, so there is no "select a clip" or "the first shot has nothing to
 * transition from" to explain here. The Blend panel used to stack those messages
 * three deep above the one control that could be used.
 */
@Composable
fun TransitionPanel(clip: Clip, viewModel: EditorViewModel) {
    val current = clip.transitionIn
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Violet) {
            PanelHeading(
                "Transition in",
                "How ${clip.label} arrives",
                icon = Icons.Filled.Transform,
                accent = SquishColors.Violet
            )
            TransitionType.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { type ->
                        SelectableChip(
                            label = type.label,
                            selected = current.type == type,
                            accentColor = SquishColors.Violet,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.layers.setTransition(clip.id, type, current.durationMs) }
                        )
                    }
                    repeat(3 - row.size) { Column(modifier = Modifier.weight(1f)) {} }
                }
            }

            if (current.isActive) {
                // Seconds, as the rest of the editor reads time; a length in
                // milliseconds was the one number here nobody thinks in.
                LabeledSlider(
                    "Length",
                    current.durationMs.toFloat(),
                    MIN_TRANSITION_MS..MAX_TRANSITION_MS,
                    readout = { ms -> "%.1f s".format(ms / 1000f) },
                    onFinished = viewModel::endGesture
                ) {
                    viewModel.layers.setTransition(clip.id, current.type, it.toLong())
                }
                Text(
                    "The shots overlap by this much, so the edit gets that much shorter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            }
        }
    }
}

/** Shorter than this a transition is a flicker; longer, it is a scene of its own. */
private const val MIN_TRANSITION_MS = 150f
private const val MAX_TRANSITION_MS = 2_000f
