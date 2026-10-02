package com.squish.app.editor

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Translate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.squish.app.online.rememberOnlineGate
import com.squish.app.online.OnlineFonts
import com.squish.app.data.TextStyleJson
import com.squish.app.media.CustomFonts
import com.squish.app.media.typeface
import com.squish.app.ui.components.ConfirmDialog
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The Text tool: adding comes first - one tap puts a line at the playhead with
 * the keyboard up - then titles, auto-captions and every line on the timeline.
 *
 * Captions are honest about which half did what. Finding the speech always
 * works and is the half that eats an afternoon; turning it into words needs an
 * on-device recogniser that not every phone has. Saying so plainly is the
 * difference between a useful result and a user wondering why the cards are empty.
 */
@Composable
fun TextPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onAddText: () -> Unit,
    /** A title from a preset: dropped at the playhead and opened for typing, its sample words selected. */
    onAddTitle: (TitlePreset) -> Unit,
    /** A line from the list, opened on its keyboard. */
    onEditLine: (String) -> Unit
) {
    // Words only - stickers share the caption track but have their own panel.
    val lines = remember(state.textOverlays) { state.textOverlays.filterNot { it.sticker }.sortedBy { it.startMs } }
    val autoLines = lines.count { it.isAutoCaption }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // A subtitle file picked while lines are on the timeline: beside them, or instead?
    var importing by remember { mutableStateOf<android.net.Uri?>(null) }

    // A result, not a state: it said "Saved the subtitle file" for as long as the
    // panel stayed open, through every edit after it.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear all captions?",
            body = "Every line on the timeline goes - ${lines.size} of them, words and timing. " +
                "Stickers stay.",
            caution = "Undo brings them back.",
            confirmLabel = "Clear all",
            onConfirm = {
                confirmClear = false
                viewModel.text.clearCaptions()
            },
            onDismiss = { confirmClear = false }
        )
    }

    importing?.let { uri ->
        ConfirmDialog(
            title = "Replace the lines on the timeline?",
            body = "The subtitle file's lines can take the place of the ${lines.size} here, or go in beside them.",
            caution = "Undo brings the replaced lines back.",
            confirmLabel = "Replace",
            dismissLabel = "Add beside",
            icon = Icons.Filled.Subtitles,
            accent = SquishColors.Amber,
            onConfirm = {
                importing = null
                viewModel.text.importSrt(uri, replace = true)
            },
            onDismiss = {
                importing = null
                viewModel.text.importSrt(uri, replace = false)
            }
        )
    }

    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { if (lines.isEmpty()) viewModel.text.importSrt(it) else importing = it }
    }
    val exportSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        uri?.let {
            viewModel.text.exportSrt(it) { ok ->
                notice = if (ok) "Saved the subtitle file" else "Could not write that file"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        com.squish.app.ui.components.SheetActionButton(
            text = "Add text",
            icon = Icons.Filled.Add,
            accent = SquishColors.Amber,
            modifier = Modifier.fillMaxWidth(),
            onClick = onAddText
        )

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Titles",
                "Tap one to drop it at the playhead and type your words",
                icon = Icons.Filled.Title,
                accent = SquishColors.Amber
            )
            // Two rows scrolling together: with seventeen styles one row showed
            // four and hid the rest off to the side.
            val titleScroll = rememberScrollState()
            val half = (TitlePreset.entries.size + 1) / 2
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(titleScroll)
            ) {
                TitlePreset.entries.chunked(half).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            StyleTile(label = preset.label, sample = preset.sample, style = preset.style, onClick = { onAddTitle(preset) })
                        }
                    }
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

            // Whose speech, and in what language. The source matters once a
            // voiceover is on the strip: its words are as much the edit's as the
            // camera's. Not while a run is on - it has already chosen.
            val hasSounds = state.audioClips.isNotEmpty()
            if (hasSounds) {
                ChipRow("Listen to", CaptionSource.entries, state.captionSource, { it.label }) { viewModel.text.setCaptionSource(it) }
            }
            Text("Language", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                CAPTION_LANGUAGES.forEach { (tag, label) ->
                    SelectableChip(
                        label = label,
                        selected = state.captionLanguage == tag,
                        accentColor = SquishColors.Amber,
                        onClick = { viewModel.text.setCaptionLanguage(tag) }
                    )
                }
            }

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
                    else "Stopped. The lines made so far are on the timeline — " +
                        "auto-caption again to replace them with a full pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextSecondary
                )

                // Before "no speech": a video with no sound has nothing to find
                // speech in, and saying there was none blamed the recording.
                status.noAudio -> Text(
                    "This video has no sound to caption. Add lines by hand below, or import a " +
                        "subtitle file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )

                status.imported > 0 -> Text(
                    "Imported ${status.imported} lines from the subtitle file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Teal
                )

                status.finished && status.total == 0 -> Text(
                    "No speech found. If there is talking in it, the recording may be " +
                        "too quiet or too noisy for the detector to separate from the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.Yellow
                )

                status.finished -> Text(
                    if (!status.recognitionAvailable)
                        "${status.total} lines timed. This device has no on-device speech " +
                            "recognition, so the words are yours to type — the timing is done."
                    else "${status.total} lines timed, ${status.transcribed} transcribed. Animation → Words lands each word as it is said.",
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
            // Says what a second run does: it replaces the first run's lines,
            // corrections included - which undo brings back.
            SquishOutlinedButton(
                text = when {
                    state.captions.running -> "Stop"
                    autoLines > 0 -> "Auto-caption again · replaces $autoLines"
                    else -> "Auto-caption the video"
                },
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (state.captions.running) viewModel.text.stopCaptions() else viewModel.text.generateCaptions()
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

        if (lines.isNotEmpty()) TranslateCard(viewModel)

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Lines",
                "${lines.size} on the timeline",
                icon = Icons.Filled.Subtitles,
                accent = SquishColors.Amber,
                trailing = {
                    if (lines.isNotEmpty()) {
                        com.squish.app.ui.components.TextAction("Clear all", color = SquishColors.Pink) { confirmClear = true }
                    }
                }
            )

            if (lines.isEmpty()) {
                Text(
                    "Nothing yet. Add text at the top, auto-caption, or import a transcript.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            } else {
                LinesList(
                    lines = lines,
                    playheadMs = state.playheadMs,
                    selectedId = state.selectedClipId,
                    onSelect = { line ->
                        viewModel.selectClip(line.id)
                        viewModel.scrubTo(line.startMs)
                    },
                    onEdit = { onEditLine(it.id) },
                    onRemove = { viewModel.text.removeTextOverlay(it.id) }
                )
            }
        }
    }
}

/**
 * Every line, in order, the one under the playhead marked and scrolled to as
 * the edit plays: forty auto-captions are read against the picture, not
 * hunted for. A lazy list, since forty rows with a field each were composed at
 * once before and the sheet opened a beat late.
 */
@Composable
private fun LinesList(
    lines: List<TextOverlayItem>,
    playheadMs: Long,
    selectedId: String?,
    onSelect: (TextOverlayItem) -> Unit,
    onEdit: (TextOverlayItem) -> Unit,
    onRemove: (TextOverlayItem) -> Unit
) {
    val listState = rememberLazyListState()
    val current = lines.indexOfFirst { playheadMs >= it.startMs && playheadMs < it.endMs }
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem(current)
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        // Bounded, since the sheet's content scrolls too; past this the list scrolls inside it.
        modifier = Modifier.fillMaxWidth().heightIn(max = LINES_MAX_HEIGHT)
    ) {
        items(lines, key = { it.id }) { line ->
            val under = playheadMs >= line.startMs && playheadMs < line.endMs
            val selected = line.id == selectedId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (under) SquishColors.Amber.copy(alpha = 0.12f) else SquishColors.Background)
                    .border(1.dp, if (selected) SquishColors.Amber else SquishColors.Border, RoundedCornerShape(10.dp))
                    .clickable { onSelect(line) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Timecode.format(line.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Cyan
                    )
                    Text(
                        line.text.takeIf { it.isNotBlank() } ?: "Empty text",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.text.isBlank()) SquishColors.TextMuted else SquishColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                com.squish.app.ui.components.TextAction("Edit", color = SquishColors.Amber) { onEdit(line) }
                com.squish.app.ui.components.TextAction("Remove", color = SquishColors.Pink) { onRemove(line) }
            }
        }
    }
}

/** A style shown as itself - its face, colour, edge and box - rather than by its name alone. */
@Composable
private fun StyleTile(label: String, sample: String, style: TextStyleSpec, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, if (selected) SquishColors.Amber else SquishColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        val background = style.background
        Box(
            modifier = Modifier
                .height(34.dp)
                .then(
                    if (background.isOn) Modifier
                        .clip(RoundedCornerShape(if (background.bubble == TextBubble.Pill) 17.dp else 6.dp))
                        .background(Color(background.colorArgb).copy(alpha = background.opacity))
                        .padding(horizontal = 8.dp)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            // The whole sample, shrunk to fit: cut at ten letters "Day 1 in Goa"
            // read "Day 1 in G", and wider letters were clipped by the box.
            com.squish.app.ui.components.FitText(
                sample,
                color = Color(style.colorArgb),
                minScale = 0.4f,
                style = when {
                    style.glow -> TextStyle(shadow = Shadow(Color(style.colorArgb), blurRadius = 18f))
                    style.shadow.isOn -> TextStyle(shadow = Shadow(Color.Black, offset = Offset(2f, 3f), blurRadius = 6f))
                    style.stroke.isOn -> TextStyle(shadow = Shadow(Color(style.stroke.colorArgb), blurRadius = 3f))
                    else -> TextStyle()
                }.copy(
                    fontFamily = FontFamily(style.font.typeface()),
                    fontWeight = if (style.bold || style.font.bold) FontWeight.Bold else null,
                    fontStyle = if (style.italic) FontStyle.Italic else null,
                    fontSize = 15.sp
                )
            )
        }
        com.squish.app.ui.components.FitText(label, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
    }
}

@Composable
private fun <T> ChipRow(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onPick: (T) -> Unit
) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
    SideScrollChips(options, { it == selected }, Modifier.fillMaxWidth()) { option ->
        SelectableChip(
            label = label(option),
            selected = option == selected,
            accentColor = SquishColors.Amber,
            onClick = { onPick(option) }
        )
    }
}

/** A slider in the text panels' colour, its label and value above it, one drag one step. */
@Composable
private fun TextSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: (Float) -> String,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit
) {
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(readout(value), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = SquishColors.Amber,
                activeTrackColor = SquishColors.Amber,
                inactiveTrackColor = SquishColors.Border
            )
        )
    }
}

/** How long "Saved the subtitle file" stays up. */
private const val NOTICE_MS = 4_000L

/** How long a typed value is recognised as the field's own when the state echoes it back. */
private const val ECHO_MS = 1_500L

/** The lines list's height before it scrolls inside the sheet. */
private val LINES_MAX_HEIGHT = 320.dp

/** The languages the recogniser is asked for. Null is the phone's own. */
private val CAPTION_LANGUAGES: List<Pair<String?, String>> = listOf(
    null to "Phone's",
    "en-US" to "English",
    "es-ES" to "Spanish",
    "fr-FR" to "French",
    "de-DE" to "German",
    "pt-BR" to "Portuguese",
    "it-IT" to "Italian",
    "hi-IN" to "Hindi",
    "ja-JP" to "Japanese",
    "ko-KR" to "Korean",
    "zh-CN" to "Chinese",
    "ar" to "Arabic",
    "ru-RU" to "Russian"
)

private val CAPTION_COLOURS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF111111.toInt(),
    0xFFFFD166.toInt(),
    0xFFFF4FD8.toInt(),
    0xFF5CE1E6.toInt(),
    0xFF7CFC8A.toInt(),
    0xFFFF6B6B.toInt(),
    0xFF4A7BFF.toInt(),
    0xFFFF7A45.toInt()
)

/**
 * One line's words: the line's Edit, with the cursor in the field and the
 * keyboard up at once - Text then Add text is two taps to typing, where it
 * used to be finding the new blank line in a list and tapping its field.
 *
 * [selectAll] opens with the whole line selected: a new line comes with sample
 * words, a title with its own, and typing replaces them the way it does in any
 * other editor. A tapped title used to sit on the picture saying BIG NEWS with
 * no keyboard and no obvious way to make it say anything else. [onOpened] is
 * told once the field is up, so the caller can stop asking for that: the panel
 * is rebuilt on every return from the Style tab, and the cursor then goes to
 * the end, as CapCut's does.
 */
@Composable
fun TextEditPanel(caption: TextOverlayItem, viewModel: EditorViewModel, selectAll: Boolean = false, onOpened: () -> Unit = {}) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Same rule as the rows in the list: the report that the field is attached
    // unfocused is not the cursor leaving, and must not end a step.
    var hadFocus by remember(caption.id) { mutableStateOf(false) }
    // The words as a field value, which is what carries a selection.
    var field by remember(caption.id) {
        mutableStateOf(
            TextFieldValue(caption.text, if (selectAll) TextRange(0, caption.text.length) else TextRange(caption.text.length))
        )
    }
    // What this field last handed the view model. The line's text comes back a
    // recomposition behind the keystrokes, so during quick typing it is an older
    // value than the field's - and following that wrote the field back over the
    // letters just typed. On the phone "Handmade with love" came out "Handde
    // wthlov". The field follows the line only when the line changed from
    // elsewhere (undo, a restyle), which is when it differs from what was sent.
    // Every value typed in the last moment, not just the last one: the state
    // comes back a frame late, so during a quick run of keys it is one of the
    // earlier letters' values - and following that is what dropped them. An
    // echo of a recent keystroke is ignored; anything else (an undo, a restyle)
    // is followed.
    val sent = remember(caption.id) { LinkedHashMap<String, Long>() }
    LaunchedEffect(caption.text) {
        val now = android.os.SystemClock.uptimeMillis()
        sent.entries.removeAll { now - it.value > ECHO_MS }
        if (caption.text != field.text && caption.text !in sent) {
            field = field.copy(text = caption.text, selection = TextRange(caption.text.length))
        }
    }
    // Edit is for typing, so it opens with the keyboard up - after Add text and
    // from a line's own toolbar alike. Only Add text used to, and editing an
    // existing line took a third tap on the field.
    LaunchedEffect(caption.id) {
        // A frame for the field to be laid out; asking before that is ignored.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
        onOpened()
    }
    // Leaving the sheet ends the run of typing, even with the field still focused.
    DisposableEffect(caption.id) { onDispose { viewModel.text.endCaptionTyping(caption.id) } }

    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Text",
            "${Timecode.format(caption.startMs)} → ${Timecode.format(caption.endMs)} · drag it on the picture, its ends on the strip",
            icon = Icons.Filled.Title,
            accent = SquishColors.Amber
        )
        OutlinedTextField(
            value = field,
            onValueChange = { typed ->
                field = typed
                if (typed.text != caption.text) {
                    sent[typed.text] = android.os.SystemClock.uptimeMillis()
                    viewModel.text.updateCaptionText(caption.id, typed.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) viewModel.text.endCaptionTyping(caption.id)
                    hadFocus = it.isFocused
                },
            placeholder = { Text("Type your text", color = SquishColors.TextMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SquishColors.Amber,
                unfocusedBorderColor = SquishColors.Border,
                focusedTextColor = SquishColors.TextPrimary,
                unfocusedTextColor = SquishColors.TextPrimary
            )
        )
    }
}

/**
 * How one line looks: presets and saved styles first, a look and a face, the
 * letters' colour with a full picker and the eyedropper, then the fine controls -
 * size, spacing, edge, shadow - and the buttons that carry a style to other lines.
 */
@Composable
fun TextStylePanel(item: TextOverlayItem, viewModel: EditorViewModel, onEyedropper: ((Int) -> Unit) -> Unit) {
    val context = LocalContext.current
    val restyle = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change) }
    val drag = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change, dragging = true) }
    val done = viewModel::endGesture
    val style = item.style
    var savedStyles by remember { mutableStateOf(UserStyles.list(context)) }
    // What "Save this style" just did, for a moment: the tap gave no sign, so
    // it was tapped twice and two chips could not be told apart.
    var savedNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedNotice) {
        if (savedNotice != null) {
            delay(NOTICE_MS)
            savedNotice = null
        }
    }
    var fonts by remember { mutableStateOf(CustomFonts.names()) }
    val onlineGate = rememberOnlineGate()
    val fontScope = rememberCoroutineScope()
    var showOnlineFonts by remember { mutableStateOf(false) }
    var fetchingFont by remember { mutableStateOf<String?>(null) }
    var fontError by remember { mutableStateOf<String?>(null) }
    var pickingColour by remember(item.id) { mutableStateOf(false) }
    var pickingStroke by remember(item.id) { mutableStateOf(false) }
    var pickingShadow by remember(item.id) { mutableStateOf(false) }

    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.text.importFont(it, onto = item.id) { fonts = CustomFonts.names() } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Style",
                item.text.takeIf { it.isNotBlank() } ?: "Untitled line",
                icon = Icons.Filled.Palette,
                accent = SquishColors.Amber
            )
            Text("Presets", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                CaptionStylePreset.entries.forEach { preset ->
                    StyleTile(
                        label = preset.label,
                        sample = "Abc",
                        style = preset.style,
                        selected = preset.style == style,
                        onClick = { viewModel.text.applyStyle(item.id, preset.style, "Style ${preset.label}") }
                    )
                }
            }
            // The person's own: this line's style kept by name, for the next
            // edit. A chip applies it; its × forgets it.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    savedNotice ?: "Mine",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (savedNotice != null) SquishColors.Teal else SquishColors.TextMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Save this style",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Amber,
                    modifier = Modifier.clickable {
                        // Under a name nobody has, never over a kept style.
                        val name = TextStyleNames.next(savedStyles.map { it.first })
                        UserStyles.save(context, name, style)
                        savedStyles = UserStyles.list(context)
                        savedNotice = "Saved as $name"
                    }
                )
            }
            if (savedStyles.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    savedStyles.forEach { (name, saved) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectableChip(
                                label = name,
                                selected = saved == style,
                                accentColor = SquishColors.Amber,
                                onClick = { viewModel.text.applyStyle(item.id, saved, "Style $name") }
                            )
                            Text(
                                "×",
                                style = MaterialTheme.typography.labelSmall,
                                color = SquishColors.TextMuted,
                                modifier = Modifier
                                    .clickable {
                                        UserStyles.remove(context, name)
                                        savedStyles = UserStyles.list(context)
                                    }
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }

            ChipRow("Look", TextLook.entries, TextLook.of(style), { it.label }) { look ->
                restyle { it.withStyle(look.applied(it.style)) }
            }

            Text("Font", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                TextFont.entries.forEach { font ->
                    SelectableChip(
                        label = font.label,
                        selected = item.fontFile == null && item.font == font,
                        accentColor = SquishColors.Amber,
                        onClick = { restyle { it.copy(font = font, fontFile = null) } }
                    )
                }
                fonts.forEach { file ->
                    SelectableChip(
                        label = CustomFonts.label(file),
                        selected = item.fontFile == file,
                        accentColor = SquishColors.Amber,
                        onClick = { restyle { it.copy(fontFile = file) } }
                    )
                }
                SelectableChip(
                    label = "Import font…",
                    selected = false,
                    accentColor = SquishColors.Amber,
                    // Font files carry no dependable type across providers; the importer checks.
                    onClick = { importFont.launch(arrayOf("*/*")) }
                )
            }
            // Free fonts from Google Fonts, fetched once and kept like an imported
            // one. Online features are asked for first; only the font's name is sent.
            SelectableChip(
                label = if (fetchingFont != null) "Getting $fetchingFont…" else if (showOnlineFonts) "Hide free fonts" else "Free fonts online…",
                selected = showOnlineFonts,
                accentColor = SquishColors.Amber,
                onClick = { onlineGate.request("Free fonts") { showOnlineFonts = !showOnlineFonts } }
            )
            if (showOnlineFonts) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    OnlineFonts.families.forEach { family ->
                        SelectableChip(
                            label = family,
                            selected = false,
                            accentColor = SquishColors.Amber,
                            onClick = {
                                if (fetchingFont != null) return@SelectableChip
                                onlineGate.request("Free fonts") {
                                    // Already downloaded: put on at once, not fetched and added again.
                                    val have = CustomFonts.names().firstOrNull { CustomFonts.label(it).equals(family, ignoreCase = true) }
                                    if (have != null) {
                                        restyle { it.copy(fontFile = have) }
                                        return@request
                                    }
                                    fetchingFont = family
                                    fontScope.launch {
                                        val uri = runCatching { OnlineFonts.fetch(context, family) }.getOrNull()
                                        fetchingFont = null
                                        if (uri == null) fontError = "Couldn't download $family. Check the connection."
                                        else viewModel.text.importFont(uri, onto = item.id) { fonts = CustomFonts.names() }
                                    }
                                }
                            }
                        )
                    }
                }
                fontError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Yellow) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                SelectableChip(label = "B", selected = item.bold, accentColor = SquishColors.Amber) { restyle { it.copy(bold = !it.bold) } }
                SelectableChip(label = "I", selected = item.italic, accentColor = SquishColors.Amber) { restyle { it.copy(italic = !it.italic) } }
                SelectableChip(label = "U", selected = item.underline, accentColor = SquishColors.Amber) { restyle { it.copy(underline = !it.underline) } }
                Spacer(modifier = Modifier.width(8.dp))
                TextAlign.entries.forEach { align ->
                    SelectableChip(label = align.label, selected = item.align == align, accentColor = SquishColors.Amber) {
                        restyle { it.copy(align = align) }
                    }
                }
            }

            ColourRow(
                title = "Colour",
                argb = item.colorArgb,
                open = pickingColour,
                onOpen = { pickingColour = it },
                onPick = { restyle { line -> line.copy(colorArgb = it) } },
                onDrag = { drag { line -> line.copy(colorArgb = it) } },
                onFinished = done,
                onEyedropper = onEyedropper
            )

            TextSlider("Size", item.sizeSp.toFloat(), 12f..120f, { "${it.roundToInt()}" }, done) { v ->
                drag { it.copy(sizeSp = v.roundToInt()) }
            }
            TextSlider("Letter spacing", item.letterSpacing, 0f..TextStyleSpec.MAX_LETTER_SPACING, { "%.2f".format(it) }, done) { v ->
                drag { it.copy(letterSpacing = v) }
            }
            TextSlider("Line spacing", item.lineSpacing, TextStyleSpec.MIN_LINE_SPACING..TextStyleSpec.MAX_LINE_SPACING, { "%.1f×".format(it) }, done) { v ->
                drag { it.copy(lineSpacing = v) }
            }
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading("Edge", "A line round every letter", accent = SquishColors.Amber)
            TextSlider("Width", item.stroke.width, 0f..TextStyleSpec.MAX_STROKE, { if (it == 0f) "Off" else "%.2f".format(it) }, done) { v ->
                drag { it.copy(stroke = it.stroke.copy(width = v)) }
            }
            ColourRow(
                title = "Edge colour",
                argb = item.stroke.colorArgb,
                open = pickingStroke,
                onOpen = { pickingStroke = it },
                onPick = { c -> restyle { it.copy(stroke = it.stroke.copy(colorArgb = c, width = it.stroke.width.takeIf { w -> w > 0f } ?: TextLook.OUTLINE_STROKE.width)) } },
                onDrag = { c -> drag { it.copy(stroke = it.stroke.copy(colorArgb = c)) } },
                onFinished = done,
                onEyedropper = onEyedropper
            )
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading("Shadow", "Cast behind the letters", accent = SquishColors.Amber)
            TextSlider("Opacity", item.shadow.opacity, 0f..1f, { if (it == 0f) "Off" else "${(it * 100).roundToInt()}%" }, done) { v ->
                drag { it.copy(shadow = it.shadow.copy(opacity = v)) }
            }
            TextSlider("Blur", item.shadow.blur, 0f..0.6f, { "%.2f".format(it) }, done) { v ->
                drag { it.copy(shadow = it.shadow.copy(blur = v)) }
            }
            TextSlider("Distance", item.shadow.offset, 0f..0.5f, { "%.2f".format(it) }, done) { v ->
                drag { it.copy(shadow = it.shadow.copy(offset = v)) }
            }
            TextSlider("Angle", item.shadow.angleDegrees, 0f..360f, { "${it.roundToInt()}°" }, done) { v ->
                drag { it.copy(shadow = it.shadow.copy(angleDegrees = v)) }
            }
            ColourRow(
                title = "Shadow colour",
                argb = item.shadow.colorArgb,
                open = pickingShadow,
                onOpen = { pickingShadow = it },
                onPick = { c -> restyle { it.copy(shadow = it.shadow.copy(colorArgb = c, opacity = it.shadow.opacity.takeIf { o -> o > 0f } ?: TextLook.SHADOW.opacity)) } },
                onDrag = { c -> drag { it.copy(shadow = it.shadow.copy(colorArgb = c)) } },
                onFinished = done,
                onEyedropper = onEyedropper
            )
        }

        if (!item.sticker) {
            PanelSurface(accent = SquishColors.Amber) {
                PanelHeading("Every line", "This style on the others", accent = SquishColors.Amber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SquishOutlinedButton(text = "Apply to all", modifier = Modifier.weight(1f)) { viewModel.text.applyStyleToAll(item.id) }
                    SquishOutlinedButton(text = "Copy style", modifier = Modifier.weight(1f)) { viewModel.text.copyStyle(item.id) }
                }
                val clipboard = viewModel.state.collectAsState().value.styleClipboard
                if (clipboard != null && clipboard != style) {
                    SquishOutlinedButton(text = "Paste style", modifier = Modifier.fillMaxWidth()) { viewModel.text.pasteStyle(item.id) }
                }
            }
        }
    }
}

/**
 * Swatches for the quick pick, and a full picker under them - hue, then
 * shade and brightness - with the eyedropper, which takes a colour off the
 * picture itself: a title in the colour of the shirt in the shot.
 */
@Composable
private fun ColourRow(
    title: String,
    argb: Int,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    /** A colour chosen in one tap: its own undo step. */
    onPick: (Int) -> Unit,
    /** A colour being dragged towards: one step for the drag. */
    onDrag: (Int) -> Unit,
    onFinished: () -> Unit,
    onEyedropper: ((Int) -> Unit) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted, modifier = Modifier.weight(1f))
        Text(
            if (open) "Less" else "More…",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.Amber,
            modifier = Modifier.clickable { onOpen(!open) }
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        CAPTION_COLOURS.forEach { colour ->
            val selected = (argb or ALPHA_MASK) == (colour or ALPHA_MASK)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(colour))
                    .border(
                        if (selected) 3.dp else 1.dp,
                        if (selected) SquishColors.Primary else SquishColors.Border,
                        CircleShape
                    )
                    .clickable { onPick(colour) }
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(SquishColors.SurfaceElevated)
                .border(1.dp, SquishColors.Border, CircleShape)
                .clickable { onEyedropper(onPick) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Colorize, contentDescription = "Pick a colour from the picture", tint = SquishColors.TextPrimary, modifier = Modifier.size(16.dp))
        }
    }
    if (open) ColourPicker(argb = argb, onChange = onDrag, onFinished = onFinished)
}

/**
 * A hue strip and a shade-and-brightness square. Its own hue is kept while the
 * square is dragged, since a grey has no hue to read back and the strip's thumb
 * would jump to red on every drag to white.
 */
@Composable
private fun ColourPicker(argb: Int, onChange: (Int) -> Unit, onFinished: () -> Unit) {
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(0f) }
    var bright by remember { mutableStateOf(1f) }
    // Followed from outside - a swatch tapped, undo - unless it is the colour
    // the picker itself just made, whose hue it knows better than the number does.
    LaunchedEffect(argb) {
        if (hsvToArgb(hue, sat, bright) != (argb or ALPHA_MASK)) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(argb, hsv)
            hue = hsv[0]; sat = hsv[1]; bright = hsv[2]
        }
    }
    val emit = { onChange(hsvToArgb(hue, sat, bright)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onFinished() }) { change, _ ->
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        bright = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        sat = (at.x / size.width).coerceIn(0f, 1f)
                        bright = (1f - at.y / size.height).coerceIn(0f, 1f)
                        emit()
                        onFinished()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f)))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val x = sat * size.width
                val y = (1f - bright) * size.height
                drawCircle(Color.Black, radius = 9f, center = Offset(x, y))
                drawCircle(Color.White, radius = 7f, center = Offset(x, y))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onFinished() }) { change, _ ->
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        hue = (at.x / size.width).coerceIn(0f, 1f) * 360f
                        emit()
                        onFinished()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) }))
                val x = hue / 360f * size.width
                drawCircle(Color.Black, radius = 9f, center = Offset(x, size.height / 2f))
                drawCircle(Color.White, radius = 7f, center = Offset(x, size.height / 2f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(argb)).border(1.dp, SquishColors.Border, CircleShape))
            Text("#%06X".format(argb and 0xFFFFFF), style = MaterialTheme.typography.labelSmall, color = SquishColors.TextSecondary)
        }
    }
}

private fun hsvToArgb(hue: Float, sat: Float, bright: Float): Int =
    AndroidColor.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), bright.coerceIn(0f, 1f))) or ALPHA_MASK

private const val ALPHA_MASK = 0xFF000000.toInt()

/** What sits behind the line: the shape, its colour, how solid, how round. */
@Composable
fun TextBubblePanel(item: TextOverlayItem, viewModel: EditorViewModel, onEyedropper: ((Int) -> Unit) -> Unit) {
    val restyle = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change) }
    val drag = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change, dragging = true) }
    val done = viewModel::endGesture
    var picking by remember(item.id) { mutableStateOf(false) }
    val background = item.background
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading("Bubble", "A shape behind the words", icon = Icons.Filled.ChatBubbleOutline, accent = SquishColors.Amber)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextBubble.entries.forEach { bubble ->
                SelectableChip(label = bubble.label, selected = background.bubble == bubble, accentColor = SquishColors.Amber) {
                    restyle { it.copy(background = it.background.copy(bubble = bubble)) }
                }
            }
        }
        if (background.bubble != TextBubble.None) {
            ColourRow(
                title = "Colour",
                argb = background.colorArgb,
                open = picking,
                onOpen = { picking = it },
                onPick = { c -> restyle { it.copy(background = it.background.copy(colorArgb = c)) } },
                onDrag = { c -> drag { it.copy(background = it.background.copy(colorArgb = c)) } },
                onFinished = done,
                onEyedropper = onEyedropper
            )
            TextSlider("Opacity", background.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, done) { v ->
                drag { it.copy(background = it.background.copy(opacity = v)) }
            }
            if (background.bubble == TextBubble.Box || background.bubble == TextBubble.Speech || background.bubble == TextBubble.Stamp) {
                TextSlider("Corners", background.radius, 0f..1f, { "%.2f".format(it) }, done) { v ->
                    drag { it.copy(background = it.background.copy(radius = v)) }
                }
            }
        }
    }
}

/**
 * How a line or sticker arrives, leaves and behaves in between, each with how
 * long it takes. A typewriter has no letters to type on a sticker, and Words
 * are for lines with words.
 */
@Composable
fun TextAnimationPanel(item: TextOverlayItem, viewModel: EditorViewModel) {
    val restyle = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change) }
    val drag = { change: (TextOverlayItem) -> TextOverlayItem -> viewModel.text.restyleCaption(item.id, change, dragging = true) }
    val done = viewModel::endGesture
    val motions = if (item.sticker) TextMotion.entries.filter { !it.reveals } else TextMotion.entries
    val seconds = { ms: Float -> "%.1f s".format(ms / 1000f) }
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading("Animation", "In, out, and while it is there", icon = Icons.Filled.Animation, accent = SquishColors.Amber)
        ChipRow("In", motions, item.motion, { it.label }) { m ->
            restyle { it.copy(motion = m) }
            // Shown once: a paused line is drawn at rest, so a picked arrival was never seen.
            if (m != TextMotion.None) viewModel.showMoment(item.startMs, minOf(item.motionInMs, (item.endMs - item.startMs) / 2) + 400L)
        }
        if (item.motion != TextMotion.None) {
            TextSlider(
                "In takes", item.motionInMs.toFloat(),
                TextAnimation.MIN_MOTION_MS.toFloat()..TextAnimation.MAX_MOTION_MS.toFloat(), seconds, done
            ) { v -> drag { it.copy(motionInMs = v.roundToInt().toLong()) } }
        }
        ChipRow("Out", TextExit.entries, item.motionOut, { it.label }) { m ->
            restyle { it.copy(motionOut = m) }
            val from = (item.endMs - minOf(item.motionOutMs, (item.endMs - item.startMs) / 2) - 400L).coerceAtLeast(item.startMs)
            if (m != TextExit.None) viewModel.showMoment(from, (item.endMs - 40L - from).coerceAtLeast(0L))
        }
        if (item.motionOut != TextExit.None) {
            TextSlider(
                "Out takes", item.motionOutMs.toFloat(),
                TextAnimation.MIN_MOTION_MS.toFloat()..TextAnimation.MAX_MOTION_MS.toFloat(), seconds, done
            ) { v -> drag { it.copy(motionOutMs = v.roundToInt().toLong()) } }
        }
        ChipRow("Loop", TextLoop.entries, item.loop, { it.label }) { m ->
            restyle { it.copy(loop = m) }
            if (m != TextLoop.None) viewModel.showMoment(item.startMs, minOf(item.loopMs * 2, item.endMs - item.startMs - 40L))
        }
        if (item.loop != TextLoop.None) {
            TextSlider(
                "Every", item.loopMs.toFloat(),
                TextAnimation.MIN_LOOP_MS.toFloat()..TextAnimation.MAX_LOOP_MS.toFloat(), seconds, done
            ) { v -> drag { it.copy(loopMs = v.roundToInt().toLong()) } }
        }
        if (item.motion == TextMotion.Words && !item.sticker) {
            Text(
                if (item.wordStartsMs.size > 1) "Each word lands where the speech says it - this line's words were timed by auto-captions."
                else "The words land evenly over the arrival; auto-captions time them to the speech.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
        // The same motion on every other line of this kind, in one step: forty
        // auto-captions landing word by word are one tap, not forty tabs.
        SquishOutlinedButton(text = "Apply to all", modifier = Modifier.fillMaxWidth()) { viewModel.text.applyMotionToAll(item.id) }
    }
}

/** How much of the picture shows through a line or sticker. */
@Composable
fun TextOpacityPanel(item: TextOverlayItem, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading("Opacity", "How much of the picture shows through", icon = Icons.Filled.Opacity, accent = SquishColors.Amber)
        TextSlider("Opacity", item.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, viewModel::endGesture) { v ->
            viewModel.text.setOpacity(item.id, v)
        }
    }
}

/** The style a new line starts with - outlined, see [TextStyleSpec.NEW_LINE] - for the Style tool's Reset. */
fun TextOverlayItem.withDefaultStyle(): TextOverlayItem =
    withStyle(if (sticker) TextStyleSpec(stroke = TextStroke.NONE, sizeSp = sizeSp) else TextStyleSpec.NEW_LINE)

/** Still: no arrival, no leaving, no loop, for Animation's Reset. */
fun TextOverlayItem.withoutMotion(): TextOverlayItem =
    copy(motion = TextMotion.None, motionOut = TextExit.None, loop = TextLoop.None)

/**
 * Styles the person kept, by name, across every edit: a JSON array in the
 * app's preferences, each entry the same fields a draft writes for a line.
 */
object UserStyles {
    fun list(context: Context): List<Pair<String, TextStyleSpec>> = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val style = entry.optJSONObject("style") ?: return@mapNotNull null
            name to TextStyleJson.read(style)
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, name: String, style: TextStyleSpec) {
        val kept = list(context).filterNot { it.first == name } + (name to style)
        write(context, kept)
    }

    fun remove(context: Context, name: String) = write(context, list(context).filterNot { it.first == name })

    private fun write(context: Context, styles: List<Pair<String, TextStyleSpec>>) {
        val array = JSONArray()
        styles.forEach { (name, style) ->
            array.put(JSONObject().apply {
                put("name", name)
                put("style", TextStyleJson.encode(style))
            })
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("text_styles", Context.MODE_PRIVATE)
    private const val KEY = "styles"
}

/**
 * Every caption in another language, online. The language the lines are in,
 * then the one wanted; one undo takes it back.
 */
@Composable
private fun TranslateCard(viewModel: EditorViewModel) {
    val gate = com.squish.app.online.rememberOnlineGate()
    val languages = com.squish.app.online.OnlineTranslate.languages
    var from by rememberSaveable { mutableStateOf("en") }
    var to by rememberSaveable { mutableStateOf("es") }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Translate",
            "Every caption into another language · online",
            icon = Icons.Filled.Translate,
            accent = SquishColors.Amber
        )
        Text("They're in", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            languages.forEach { (code, name) ->
                SelectableChip(label = name, selected = from == code, accentColor = SquishColors.Amber, onClick = { from = code })
            }
        }
        Text("Translate into", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            languages.forEach { (code, name) ->
                SelectableChip(label = name, selected = to == code, accentColor = SquishColors.Amber, onClick = { to = code })
            }
        }
        SquishOutlinedButton(
            text = if (working) "Translating…" else "Translate captions",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (working || from == to) return@SquishOutlinedButton
                gate.request("Translation") {
                    working = true
                    note = null
                    viewModel.text.translateCaptions(from, to) { n, of ->
                        working = false
                        note = when {
                            of == 0 -> "No lines of words to translate."
                            n == 0 -> "Couldn't reach the translation service. Check the connection and try again."
                            n < of -> "Translated $n of $of lines - the service refused the rest, which keep their words. Undo takes it back."
                            else -> "Translated $n ${if (n == 1) "line" else "lines"}. Undo takes it back."
                        }
                    }
                }
            }
        )
        Text(
            note ?: "Only the captions' words are sent, to MyMemory's free translator - never the video.",
            style = MaterialTheme.typography.labelSmall,
            color = if (note != null) SquishColors.Teal else SquishColors.TextMuted
        )
    }
}
