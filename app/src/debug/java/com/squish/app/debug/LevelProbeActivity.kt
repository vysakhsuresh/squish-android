package com.squish.app.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.squish.app.media.audio.PcmDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Debug builds only: a file's sound levels (EDGE_PLAN G2), for telling a
 * noise floor before and after Reduce noise without a desktop decoder.
 *
 *   am start -n com.squish.app/.debug.LevelProbeActivity --es uri content://media/external/video/media/<id>
 *
 * Logs under SquishLevel: the level of the first [QUIET_MS] (noise alone, in
 * the test files) and of the loudest second, in dBFS, and their difference.
 */
class LevelProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(16, 16, 16, 16) }
        setContentView(text)
        val uri = intent.getStringExtra("uri")?.let(Uri::parse) ?: return
        scope.launch {
            val pcm = PcmDecoder.decodeMono(this@LevelProbeActivity, uri, targetSampleRate = 16_000, maxDurationMs = 10L * 60 * 1000)
            val line = if (pcm == null) "no sound" else {
                val rate = pcm.sampleRate
                fun db(from: Int, to: Int): Double {
                    var sum = 0.0
                    for (i in from until to) sum += pcm.samples[i].toDouble() * pcm.samples[i]
                    return 10 * log10((sum / (to - from).coerceAtLeast(1)).coerceAtLeast(1e-12))
                }
                val quiet = db(rate / 4, (QUIET_MS * rate / 1000).toInt().coerceAtMost(pcm.samples.size))
                var loud = -200.0
                var at = 0
                while (at + rate <= pcm.samples.size) { loud = maxOf(loud, db(at, at + rate)); at += rate / 4 }
                // And the level every quarter second, for telling where sounds are.
                val quarters = (0 until pcm.samples.size / (rate / 4)).joinToString(" ") { q -> "%.0f".format(db(q * rate / 4, (q + 1) * rate / 4)) }
                "floor ${"%.1f".format(quiet)} dBFS, loudest second ${"%.1f".format(loud)} dBFS, gap ${"%.1f".format(loud - quiet)} dB; quarters: $quarters"
            }
            Log.i(TAG, "$uri: $line")
            text.text = line
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val TAG = "SquishLevel"
        const val QUIET_MS = 1_800L
    }
}
