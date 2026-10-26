package com.squish.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.squish.app.media.audio.NeuralVoice
import com.squish.app.media.audio.NeuralVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EDGE_PLAN E5 on the phone, debug builds only: a downloaded voice made to
 * speak a text into files/voice_probe.wav, timed, so the WAV can be read back
 * by Whisper (tools/bench/edge.sh voice) and scored against what it was given.
 *
 *   am start -n com.squish.app/.debug.VoiceProbeActivity --es lang ml --es speaker female --es textFile <path>
 */
class VoiceProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(view) })
        val code = intent.getStringExtra("lang") ?: "en"
        val speakerName = intent.getStringExtra("speaker")
        val text = intent.getStringExtra("text")
            ?: intent.getStringExtra("textFile")?.let { runCatching { File(it).readText() }.getOrNull() }
            ?: "Hello, this is a test."
        scope.launch { probe(code, speakerName, text) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        view.append(line + "\n")
    }

    private suspend fun probe(code: String, speakerName: String?, text: String) {
        val language = NeuralVoices.byCode(code) ?: return say("failed: no voice $code")
        val t0 = System.currentTimeMillis()
        val voice = withContext(Dispatchers.IO) { NeuralVoice.of(this@VoiceProbeActivity, language) }
            ?: return say("failed: voice $code not installed or would not open")
        val opened = System.currentTimeMillis() - t0
        val speaker = speakerName?.let { voice.speakers[it] } ?: voice.speakers.values.firstOrNull() ?: 0
        say("voice $code opened in $opened ms; speakers ${voice.speakers}; speaking as $speaker, ${text.length} characters")
        voice.describe().lines().forEach { say(it) }
        val t1 = System.currentTimeMillis()
        val samples = runCatching { voice.speak(text, speaker) }.getOrElse { e ->
            Log.w(TAG, "speak", e)
            return say("failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        val took = System.currentTimeMillis() - t1
        say("durations ${voice.lastDurations.toList()} = ${voice.lastDurations.sum()} frames")
        val lengthMs = samples.size * 1000L / voice.sampleRate
        val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val out = File(filesDir, "voice_probe.wav")
        withContext(Dispatchers.IO) { NeuralVoice.writeWav(samples, voice.sampleRate, out) }
        say("wrote $lengthMs ms of speech in $took ms (x${"%.2f".format(java.util.Locale.ROOT, lengthMs / took.coerceAtLeast(1).toFloat())} real time), peak ${"%.2f".format(java.util.Locale.ROOT, peak)}, to ${out.path}")
    }

    private companion object {
        const val TAG = "SquishVoice"
    }
}
