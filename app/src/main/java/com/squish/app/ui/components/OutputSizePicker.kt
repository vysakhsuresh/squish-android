package com.squish.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.squish.app.editor.OutputSize
import com.squish.app.home.formatSize
import com.squish.app.media.ExportPresets
import com.squish.app.ui.theme.SquishColors

/**
 * Pick the export's size, and see what it comes to in the same glance.
 *
 * The result sits *above* the options. It used to sit below them, under the
 * fit-to-size switch, so on a phone every tap on a size meant scrolling down to
 * find out what it had done and back up to try another.
 *
 * Everything that can appear or grow - the notes, the custom size field - sits
 * above the chips too. The sheet hangs from the bottom of the screen, so what
 * is below a thing that grows stays where it is and what is above it moves:
 * the "bigger than the source" note used to appear under the chips and shift
 * every one of them a hundred pixels up, and a second tap aimed at 480p landed
 * on 4K.
 *
 * Eight options in two rows of four: Original, six sizes by name, and Custom for
 * anything else. A size bigger than the source is allowed and says so - it makes
 * a bigger file, but it cannot add detail the camera never recorded.
 *
 * [sourceWidth] and [sourceHeight] are the frame the size is applied to, which
 * for an edit is the picture after its rotation and crop (EditorUiState
 * .croppedFrame). [asked] is the frame that gives, and [written] what the
 * phone's encoder will write for it - the same numbers the encoder is given, so
 * the summary is the file's size and not the camera's, nor a size the encoder
 * will quietly halve.
 */
@Composable
fun OutputSizePicker(
    outputP: Int,
    fitToSize: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    asked: ExportPresets.Resolution,
    written: ExportPresets.Resolution,
    estimatedBytes: Long,
    originalBytes: Long,
    accent: Color,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceP = if (sourceWidth > 0 && sourceHeight > 0) minOf(sourceWidth, sourceHeight) else 0
    val isCustom = !fitToSize && outputP != OutputSize.ORIGINAL && outputP !in OutputSize.PRESETS
    var editingCustom by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutputSummary(
            fitToSize = fitToSize,
            written = written,
            estimatedBytes = estimatedBytes,
            originalBytes = originalBytes
        )

        if (written != asked && asked.width > 0 && asked.height > 0) {
            Text(
                "This phone's encoder can't write ${asked.width} × ${asked.height}, so the file will be " +
                    "${written.width} × ${written.height}.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Amber
            )
        }
        if (!fitToSize && sourceP > 0 && outputP != OutputSize.ORIGINAL && outputP > sourceP) {
            Text(
                "Bigger than the source (${sourceP}p). The file grows, but upscaling can't add " +
                    "detail the camera didn't record.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Amber
            )
        }

        if (editingCustom) {
            CustomSizeField(
                initial = if (isCustom) outputP else sourceP.takeIf { it > 0 } ?: 720,
                accent = accent,
                onSet = { p ->
                    editingCustom = false
                    onPick(p)
                }
            )
        }

        val options: List<Int?> = listOf(OutputSize.ORIGINAL) + OutputSize.PRESETS + listOf(null)
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    val label = when {
                        p == null -> if (isCustom) OutputSize.label(outputP) else "Custom"
                        else -> OutputSize.label(p)
                    }
                    val selected = !fitToSize && when (p) {
                        null -> isCustom || editingCustom
                        else -> outputP == p && !editingCustom
                    }
                    SelectableChip(
                        label = label,
                        selected = selected,
                        accentColor = accent,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (p == null) {
                                editingCustom = true
                            } else {
                                editingCustom = false
                                onPick(p)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * The answer to "what will I get": frame size, weight, and the change against
 * the original, on one strip.
 */
@Composable
private fun OutputSummary(
    fitToSize: Boolean,
    written: ExportPresets.Resolution,
    estimatedBytes: Long,
    originalBytes: Long
) {
    val frame = when {
        fitToSize -> "Sized to fit"
        written.width > 0 && written.height > 0 -> "${written.width} × ${written.height}"
        else -> "Original size"
    }
    val change = if (originalBytes > 0 && estimatedBytes > 0) {
        val percent = ((estimatedBytes - originalBytes) * 100.0 / originalBytes).toInt()
        when {
            percent <= -1 -> "${-percent}% smaller than the original"
            percent >= 1 -> "$percent% bigger than the original"
            else -> "About the same as the original"
        }
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(frame, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
            Text(
                change ?: "Original ${formatSize(originalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("≈ ${formatSize(estimatedBytes)}", style = MaterialTheme.typography.titleSmall, color = SquishColors.Cyan)
            if (originalBytes > 0) {
                Text(
                    "was ${formatSize(originalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }
    }
}

/** A size by hand, for when none of the named ones is the one needed. */
@Composable
private fun CustomSizeField(initial: Int, accent: Color, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val focus = LocalFocusManager.current
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in OutputSize.MIN_P..OutputSize.MAX_P

    fun commit() {
        val p = parsed ?: return
        if (!valid) return
        focus.clearFocus()
        // Encoders want even frame sizes, so the short edge is kept even too.
        onSet((p / 2) * 2)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, if (valid) accent.copy(alpha = 0.5f) else SquishColors.Pink, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Short edge", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
        Spacer(modifier = Modifier.width(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(4) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = SquishColors.TextPrimary),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f)
        )
        Text("p", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextMuted)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            if (valid) "Set" else "${OutputSize.MIN_P}–${OutputSize.MAX_P}",
            style = MaterialTheme.typography.labelLarge,
            color = if (valid) accent else SquishColors.Pink,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = valid) { commit() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
