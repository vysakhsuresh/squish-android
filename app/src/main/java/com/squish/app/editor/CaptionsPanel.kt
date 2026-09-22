package com.squish.app.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Captions: found, transcribed where the device can, and editable.
 *
 * The panel is honest about which half did what. Finding the speech always works
 * and is the half that eats an afternoon; turning it into words needs an on-device
 * recogniser that not every phone has. Saying so plainly is the difference between
 * a useful result and a user wondering why the cards are empty.
 */
@Composable
fun CaptionsPanel(state: EditorUiState, viewModel: EditorViewModel) {
    var notice by remember { mutableStateOf<String?>(null) }

    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importSrt(it) }
    }
    val exportSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        uri?.let {
            viewModel.exportSrt(it) { ok ->
                notice = if (ok) "Saved the subtitle file" else "Could not write that file"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        PanelSurface {
            PanelHeading("Auto-captions", "Finds every line of speech and times it")

            val progress = state.captions
            when {
                progress.running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = SquishColors.Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            if (progress.total > 0) "${progress.stage} — ${progress.transcribed} of ${progress.total}"
                            else progress.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.TextSecondary
                        )
                    }
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.transcribed.toFloat() / progress.total },
                            color = SquishColors.Cyan,
                            trackColor = SquishColors.Border,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                progress.finished && progress.total == 0 -> Text(
                    "No speech found in this clip. If there is talking in it, the recording may be " +
                        "too quiet or too noisy for the detector to separate from the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )

                progress.finished -> Text(
                    if (!progress.recognitionAvailable)
                        "${progress.total} lines timed. This device has no on-device speech " +
                            "recognition, so the words are yours to type — the timing is done."
                    else "${progress.total} lines timed, ${progress.transcribed} transcribed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Teal
                )

                else -> Text(
                    "Squish listens for where the speech is and lays a caption on each line. " +
                        "Where the device has on-device speech recognition it fills in the words too — " +
                        "nothing is ever uploaded, so if your phone cannot do it here, it does not happen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )
            }

            SquishOutlinedButton(
                text = if (state.captions.running) "Working…" else "Auto-caption this clip",
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!state.captions.running) viewModel.generateCaptions() }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(text = "Import .srt", modifier = Modifier.weight(1f)) {
                    // Subtitle files have no dependable MIME type across providers,
                    // so anything is offered and the parser decides.
                    importSrt.launch(arrayOf("*/*"))
                }
                SquishOutlinedButton(text = "Export .srt", modifier = Modifier.weight(1f)) {
                    exportSrt.launch("captions.srt")
                }
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
            }
        }

        PanelSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelHeading("Lines", "${state.textOverlays.size} on the timeline")
                if (state.textOverlays.isNotEmpty()) {
                    Text(
                        "Clear all",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.clearCaptions() }
                    )
                }
            }

            if (state.textOverlays.isEmpty()) {
                Text(
                    "Nothing yet. Auto-caption above, import a transcript, or add a line by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            }

            SquishOutlinedButton(
                text = "Add a line at the playhead",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.addCaptionAtPlayhead() }
            )

            state.textOverlays.sortedBy { it.startMs }.forEach { caption ->
                CaptionRow(
                    caption = caption,
                    onJump = { viewModel.scrubTo(caption.startMs) },
                    onEdit = { viewModel.updateCaptionText(caption.id, it) },
                    onRemove = { viewModel.removeTextOverlay(caption.id) }
                )
            }
        }
    }
}

@Composable
private fun CaptionRow(
    caption: TextOverlayItem,
    onJump: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${Timecode.format(caption.startMs)} → ${Timecode.format(caption.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Cyan,
                modifier = Modifier.clickable(onClick = onJump)
            )
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }

        OutlinedTextField(
            value = caption.text,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type what is said here…", color = SquishColors.TextMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SquishColors.Primary,
                unfocusedBorderColor = SquishColors.Border,
                focusedTextColor = SquishColors.TextPrimary,
                unfocusedTextColor = SquishColors.TextPrimary
            )
        )
    }
}
