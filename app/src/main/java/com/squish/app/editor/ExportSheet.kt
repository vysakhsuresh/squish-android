package com.squish.app.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.squish.app.home.countOf
import com.squish.app.ui.components.OutputSizePicker
import com.squish.app.ui.components.ExportProgressCard
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * What gets written, asked before anything is written.
 *
 * Export used to fire straight off the header button at whatever the settings
 * happened to be, which meant the one irreversible, minutes-long action in the app
 * was also the only one with no confirmation. Now it opens here: resolution, size,
 * what it will weigh, and then a button that says render.
 *
 * Original is the default. Someone who opened the editor came to edit, not to
 * shrink - silently re-encoding their footage smaller than they shot it is a
 * decision the app does not get to make on its own.
 */
@Composable
fun ExportSheet(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
    onRender: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().zIndex(10f)) {
        // The scrim swallows taps so nothing behind the sheet can be nudged while
        // it is up, and dismisses - except mid-render, when leaving would strand
        // an encode with nothing on screen reporting it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SquishColors.Background.copy(alpha = 0.82f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !state.isExporting,
                    onClick = onDismiss
                )
        )

        val entry = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) { entry.targetState = true }

        AnimatedVisibility(
            visibleState = entry,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(SquishColors.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SquishColors.Border)
                )

                if (state.isExporting) {
                    ExportProgressCard(progress = state.exportProgress, accent = SquishColors.Blue)
                    return@Column
                }

                SectionHeading(
                    title = "Export",
                    subtitle = "${Timecode.format(state.trimmedDurationMs)} · " +
                        "${countOf(state.videoClips.size, "clip")} · ${countOf(state.audioClips.size, "sound")}",
                    icon = Icons.Filled.FileUpload,
                    accent = SquishColors.Blue,
                    trailing = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = SquishColors.TextMuted,
                            modifier = Modifier.size(20.dp).clickable(onClick = onDismiss)
                        )
                    }
                )

                OutputSizePicker(
                    outputP = state.outputP,
                    fitToSize = state.fitToSize,
                    sourceWidth = state.sourceWidth,
                    sourceHeight = state.sourceHeight,
                    estimatedBytes = state.estimatedOutputBytes,
                    originalBytes = state.originalSizeBytes,
                    accent = SquishColors.Blue,
                    onPick = viewModel::setOutputP
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Fit to a size",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SquishColors.TextPrimary
                        )
                        Text(
                            "For a strict upload limit",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.TextMuted
                        )
                    }
                    SquishToggleSwitch(
                        checked = state.fitToSize,
                        onCheckedChange = viewModel::setFitToSize
                    )
                }

                if (state.fitToSize) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(16, 25, 50, 100).forEach { mb ->
                            SelectableChip(
                                label = "$mb MB",
                                selected = state.targetSizeMb == mb,
                                accentColor = SquishColors.Cyan,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setTargetSizeMb(mb) }
                            )
                        }
                    }
                }

                SquishPrimaryButton(
                    text = "Render and save",
                    enabled = !state.isLoadingSource,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRender
                )
            }
        }
    }
}
