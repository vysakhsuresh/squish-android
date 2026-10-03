package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * The edit, read as words, with the footage under them to hand.
 *
 * Auto-captions have been landing a time for every word since B10 and nothing
 * ever showed them. Tapping two words chooses everything between, and Delete
 * takes that stretch out of the picture, the sound and the captions at once -
 * which is the fastest way there is to cut a talking head, and the thing CapCut
 * and Filmora both sell as text-based editing.
 *
 * A word whose moment is not known - a line typed by hand - is drawn faintly and
 * cannot be chosen on its own: the smallest thing we know the position of there
 * is the whole line, and quietly taking the sentence out round it would be a
 * lie about what the transcript knows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptPanel(state: EditorUiState, viewModel: EditorViewModel, onClose: () -> Unit) {
    // Words only. A sticker lives in the same list and its "text" is an emoji,
    // which is not something anybody wants to read a transcript of.
    val words = remember(state.textOverlays) {
        Transcript.words(
            state.textOverlays
                .filterNot { it.sticker }
                .map { Transcript.Line(it.id, it.text, it.startMs, it.endMs, it.wordStartsMs) }
        )
    }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var head by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val chosen: IntRange? = run {
        val a = anchor
        val b = head
        if (a == null || b == null) null else minOf(a, b)..maxOf(a, b)
    }
    val span = chosen?.let { range -> Transcript.span(words.slice(range)) }

    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            "Transcript",
            "Tap a word, then another, to choose everything between",
            icon = Icons.Filled.Subtitles,
            accent = SquishColors.Blue
        )

        if (words.isEmpty()) {
            Text(
                "No words yet. Auto-captions put them here, and time each one.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                words.forEachIndexed { i, word ->
                    val inRange = chosen?.contains(i) == true
                    Text(
                        text = word.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            inRange -> SquishColors.Background
                            word.timed -> SquishColors.TextPrimary
                            else -> SquishColors.TextMuted
                        },
                        textDecoration = if (word.timed) null else TextDecoration.Underline,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (inRange) SquishColors.Blue else SquishColors.Surface)
                            .border(
                                1.dp,
                                if (inRange) SquishColors.Blue else SquishColors.Border,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                // The first tap sets both ends, the second moves
                                // one of them: a run is two taps, never a drag.
                                val a = anchor
                                if (a == null || chosen?.contains(i) == true) {
                                    anchor = i; head = i
                                } else {
                                    head = i
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val count = chosen?.let { it.last - it.first + 1 } ?: 0
                SquishOutlinedButton(
                    text = if (count > 0) "Delete ${countOfWords(count)}" else "Choose a word first",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        span?.let {
                            viewModel.text.removeTranscriptSpan(it.first, it.last)
                            anchor = null
                            head = null
                        }
                    }
                )
            }

            SquishOutlinedButton(
                text = "Take out every “um” and “uh”",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.text.removeFillerWords { taken ->
                        notice = if (taken == 0) "No filler words with a time of their own."
                        else "Took out $taken."
                    }
                    anchor = null
                    head = null
                }
            )
            notice?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Teal)
            }
            if (words.any { !it.timed }) {
                Text(
                    "Underlined words were typed, not heard, so their moment is not known - " +
                        "choosing one chooses its whole line.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }

        SquishOutlinedButton(text = "Back to text", modifier = Modifier.fillMaxWidth(), onClick = onClose)
    }
}

private fun countOfWords(n: Int) = if (n == 1) "1 word" else "$n words"
