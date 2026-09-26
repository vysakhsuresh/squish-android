package com.squish.app.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.squish.app.ui.components.SelectableChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Subtitles
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
    // Words only - stickers share the caption track but have their own panel.
    val lines = state.textOverlays.filterNot { it.sticker }
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

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Titles",
                "Tap one to drop it at the playhead",
                icon = Icons.Filled.Title,
                accent = SquishColors.Amber
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                TitlePreset.entries.forEach { preset ->
                    TitleTile(preset = preset, onClick = { viewModel.addTitle(preset) })
                }
            }
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Auto-captions",
                "Finds every line of speech and times it",
                icon = Icons.Filled.ClosedCaption,
                accent = SquishColors.Amber
            )

            val status = state.captions
            when {
                status.running -> {
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
                            if (status.total > 0) "${status.stage} — line ${status.done} of ${status.total}"
                            else status.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.TextSecondary
                        )
                    }
                    if (status.total > 0) {
                        LinearProgressIndicator(
                            progress = { status.done.toFloat() / status.total },
                            color = SquishColors.Cyan,
                            trackColor = SquishColors.Border,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                status.stopped -> Text(
                    if (status.done == 0) "Stopped before any lines were made."
                    else "Stopped. The ${status.done} lines made so far are on the timeline — " +
                        "auto-caption again to redo the whole clip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )

                status.finished && status.total == 0 -> Text(
                    "No speech found in this clip. If there is talking in it, the recording may be " +
                        "too quiet or too noisy for the detector to separate from the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )

                status.finished -> Text(
                    if (!status.recognitionAvailable)
                        "${status.total} lines timed. This device has no on-device speech " +
                            "recognition, so the words are yours to type — the timing is done."
                    else "${status.total} lines timed, ${status.transcribed} transcribed.",
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

            // The same place starts and stops it. While it runs, the button is the
            // way out - there was none, and a stuck run could only be left by
            // leaving the editor.
            SquishOutlinedButton(
                text = if (state.captions.running) "Stop" else "Auto-caption this clip",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (state.captions.running) viewModel.stopCaptions() else viewModel.generateCaptions()
                }
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

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Lines",
                "${lines.size} on the timeline",
                icon = Icons.Filled.Subtitles,
                accent = SquishColors.Amber,
                trailing = {
                    if (lines.isNotEmpty()) {
                        Text(
                            "Clear all",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Pink,
                            modifier = Modifier.clickable { viewModel.clearCaptions() }
                        )
                    }
                }
            )

            if (lines.isEmpty()) {
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

            lines.sortedBy { it.startMs }.forEach { caption ->
                CaptionRow(
                    caption = caption,
                    onJump = { viewModel.scrubTo(caption.startMs) },
                    onEdit = { viewModel.updateCaptionText(caption.id, it) },
                    onRemove = { viewModel.removeTextOverlay(caption.id) },
                    onRestyle = { change -> viewModel.restyleCaption(caption.id, change) }
                )
            }
        }
    }
}

/** A title preset, showing its own face, colour and edge rather than just its name. */
@Composable
private fun TitleTile(preset: TitlePreset, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .then(
                    if (preset.look == TextLook.Box) Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 6.dp)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                preset.sample.take(10),
                color = Color(preset.colorArgb),
                fontFamily = FontFamily(preset.font.typeface()),
                fontSize = 15.sp,
                maxLines = 1,
                style = when (preset.look) {
                    TextLook.Outline -> TextStyle(
                        shadow = Shadow(Color.Black, blurRadius = 3f)
                    )
                    TextLook.Shadow -> TextStyle(
                        shadow = Shadow(Color.Black, offset = Offset(2f, 3f), blurRadius = 6f)
                    )
                    TextLook.Neon -> TextStyle(
                        shadow = Shadow(Color(preset.colorArgb), blurRadius = 18f)
                    )
                    else -> TextStyle()
                }
            )
        }
        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
    }
}

/**
 * Face, look, motion, colour, size and place for one caption, all one tap each.
 * Every choice is a named chip rather than a slider hidden behind a menu, because
 * the choices are few and seeing them all is faster than finding them.
 */
@Composable
private fun StyleEditor(caption: TextOverlayItem, onRestyle: ((TextOverlayItem) -> TextOverlayItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipRow("Look", TextLook.entries, caption.look, { it.label }) { v -> onRestyle { it.copy(look = v) } }
        ChipRow("Font", TextFont.entries, caption.font, { it.label }) { v -> onRestyle { it.copy(font = v) } }
        ChipRow("Motion", TextMotion.entries, caption.motion, { it.label }) { v -> onRestyle { it.copy(motion = v) } }

        Text("Colour", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CAPTION_COLOURS.forEach { argb ->
                val selected = caption.colorArgb == argb
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(argb))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) SquishColors.Primary else SquishColors.Border,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onRestyle { it.copy(colorArgb = argb) } }
                )
            }
        }

        ChipRow("Place", CaptionPlace.entries, CaptionPlace.nearest(caption.yFraction), { it.label }) { v ->
            onRestyle { it.copy(yFraction = v.yFraction) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Size ${caption.sizeSp}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = caption.sizeSp.toFloat(),
                onValueChange = { v -> onRestyle { it.copy(sizeSp = v.toInt()) } },
                valueRange = 14f..72f,
                colors = SliderDefaults.colors(
                    thumbColor = SquishColors.Amber,
                    activeTrackColor = SquishColors.Amber,
                    inactiveTrackColor = SquishColors.Border
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit
) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            SelectableChip(
                label = label(option),
                selected = option == selected,
                accentColor = SquishColors.Amber,
                onClick = { onPick(option) }
            )
        }
    }
}

/** Where a caption sits, in the three places people actually put one. */
private enum class CaptionPlace(val label: String, val yFraction: Float) {
    Top("Top", 0.14f),
    Middle("Middle", 0.5f),
    Bottom("Bottom", 0.84f);

    companion object {
        fun nearest(y: Float): CaptionPlace = entries.minBy { kotlin.math.abs(it.yFraction - y) }
    }
}

private val CAPTION_COLOURS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF111111.toInt(),
    0xFFFFD166.toInt(),
    0xFFFF4FD8.toInt(),
    0xFF5CE1E6.toInt(),
    0xFF7CFC8A.toInt(),
    0xFFFF6B6B.toInt()
)

@Composable
private fun CaptionRow(
    caption: TextOverlayItem,
    onJump: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
    onRestyle: ((TextOverlayItem) -> TextOverlayItem) -> Unit
) {
    var styling by remember(caption.id) { mutableStateOf(false) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (styling) "Done" else "Style",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Amber,
                    modifier = Modifier.clickable { styling = !styling }
                )
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    modifier = Modifier.clickable(onClick = onRemove)
                )
            }
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

        if (styling) StyleEditor(caption, onRestyle)
    }
}
