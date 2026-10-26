package com.squish.app.settings

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.media.audio.NeuralVoice
import com.squish.app.media.audio.NeuralVoices
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.TextAction
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voices to download for Read aloud (EDGE_PLAN E5), under the phone's own:
 * each language once, its size said first and fetched through the online
 * gate; then a speaker to pick, Listen to hear it before it is used, and
 * Delete. A chosen voice speaks every Read aloud; with none chosen, or the
 * chosen one deleted, the phone's own engine does.
 */
@Composable
fun DownloadedVoices() {
    val context = LocalContext.current
    val gate = com.squish.app.online.rememberOnlineGate()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(Preferences.neuralVoice(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Downloaded voices", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        Text(
            "Natural voices that speak on your phone once downloaded - nothing you write is sent. " +
                "AI4Bharat's Indic-TTS voices (MIT).",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        NeuralVoices.all.forEach { language ->
            @Suppress("UNUSED_EXPRESSION") refresh
            val installed = language.installed(context)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(language.label, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, modifier = Modifier.weight(1f))
                when {
                    busy == language.code -> Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = SquishColors.Cyan)
                    installed -> TextAction("Delete", color = SquishColors.Pink) {
                        NeuralVoices.remove(context, language)
                        if (chosen?.first == language.code) { chosen = null; Preferences.setNeuralVoice(context, null, null) }
                        refresh++
                    }
                    else -> TextAction(if (language.approxMb > 0) "Download · ${language.approxMb} MB" else "Download", color = SquishColors.Cyan) {
                        if (busy != null) return@TextAction
                        gate.request("A ${language.label} voice") {
                            busy = language.code
                            progress = 0f
                            note = null
                            scope.launch {
                                runCatching {
                                    NeuralVoices.download(context, language) { done, total -> if (total > 0) progress = done.toFloat() / total }
                                }.onFailure { note = "Couldn't download the ${language.label} voice. Check the connection and try again." }
                                busy = null
                                refresh++
                            }
                        }
                    }
                }
            }
            if (busy == language.code) {
                LinearProgressIndicator(progress = { progress }, color = SquishColors.Cyan, trackColor = SquishColors.Border, modifier = Modifier.fillMaxWidth())
            }
            if (installed) {
                val voice = remember(language.code, refresh) { NeuralVoice.of(context, language) }
                val speakers = voice?.speakers?.keys?.sorted().orEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    speakers.forEach { name ->
                        SelectableChip(
                            label = name.replaceFirstChar { it.uppercase() },
                            selected = chosen == language.code to name,
                            accentColor = SquishColors.Cyan,
                            onClick = {
                                val pick = language.code to name
                                chosen = if (chosen == pick) null else pick
                                Preferences.setNeuralVoice(context, chosen?.first, chosen?.second)
                            }
                        )
                    }
                    SquishOutlinedButton(text = if (busy == "listen:${language.code}") "Speaking…" else "Listen") {
                        if (voice == null || busy != null) return@SquishOutlinedButton
                        busy = "listen:${language.code}"
                        scope.launch {
                            val speaker = (chosen?.takeIf { it.first == language.code }?.second ?: speakers.firstOrNull())
                                ?.let { voice.speakers[it] } ?: 0
                            runCatching {
                                val samples = voice.speak(SAMPLE[language.code] ?: "Hello", speaker)
                                withContext(Dispatchers.IO) { play(samples, voice.sampleRate) }
                            }.onFailure { note = "The ${language.label} voice couldn't speak on this phone." }
                            busy = null
                        }
                    }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow) }
        Text(
            if (chosen == null) "Read aloud uses your phone's own voice." else "Read aloud uses the voice picked above.",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
    }
}

private val SAMPLE = mapOf(
    "en" to "Hello! This is how your captions will sound when they are read aloud.",
    "hi" to "नमस्ते! जब आपके कैप्शन पढ़े जाएँगे, तो वे ऐसे सुनाई देंगे।",
    "ml" to "നമസ്കാരം! നിങ്ങളുടെ അടിക്കുറിപ്പുകൾ വായിക്കുമ്പോൾ ഇങ്ങനെയാണ് കേൾക്കുക."
)

/** [samples] through the speaker, once, blocking until they have played. */
private fun play(samples: FloatArray, rate: Int) {
    val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / rate + 150L)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}
