package com.squish.app.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.SpeechSegment
import com.squish.app.media.audio.WhisperModels
import com.squish.app.media.audio.WhisperNative
import com.squish.app.media.audio.WhisperTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: Whisper run on a file (EDGE_PLAN E1), for a phone whose
 * own recognizer means auto-captions never reach it.
 *
 *   am start -n com.squish.app/.debug.WhisperProbeActivity --es path /data/user/0/com.squish.app/files/x.wav [--es lang en]
 *
 * The model must already be under files/models (the Text sheet's card, or
 * pushed with run-as). The words and their times go to logcat under
 * SquishWhisper and to files/whisper_report.txt.
 */
class WhisperProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(text) })
        val uri = intent.getStringExtra("uri")?.let(Uri::parse)
            ?: intent.getStringExtra("path")?.let { Uri.fromFile(File(it)) }
        if (uri == null) { say("no uri or path given"); return }
        scope.launch { probe(uri, intent.getStringExtra("lang")) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        text.append(line + "\n")
    }

    private suspend fun probe(uri: Uri, lang: String?) {
        say("library ${if (WhisperNative.loaded) "loaded" else "MISSING"}, model ${WhisperModels.installed(this)?.id ?: "none"}")
        val pcm = PcmDecoder.decodeMono(this, uri, targetSampleRate = 16_000, maxDurationMs = 10L * 60 * 1000)
        if (pcm == null) { say("sound could not be read"); return }
        val lengthMs = pcm.samples.size * 1000L / pcm.sampleRate
        say("sound: $lengthMs ms at ${pcm.sampleRate} Hz")
        val t0 = System.currentTimeMillis()
        val result = WhisperTranscriber.transcribe(this, pcm, SpeechSegment(0, lengthMs), lang)
        val took = System.currentTimeMillis() - t0
        WhisperTranscriber.release()
        if (result == null) { say("whisper gave nothing in $took ms"); return }
        say("whisper: ${result.words.size} words in $took ms (x${"%.2f".format(java.util.Locale.ROOT, lengthMs / took.coerceAtLeast(1).toFloat())} real time), language ${result.language}")
        say("text: ${result.text}")
        result.words.forEach { say("  ${it.startMs}-${it.endMs} ${it.text}") }
        runCatching { File(filesDir, "whisper_report.txt").appendText("$uri\n${result.text}\n") }
    }

    private companion object {
        const val TAG = "SquishWhisper"
    }
}
