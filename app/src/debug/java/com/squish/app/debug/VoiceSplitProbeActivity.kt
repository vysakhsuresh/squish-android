package com.squish.app.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.VoiceSplit
import com.squish.app.media.audio.VoiceSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Debug builds only: Voice only or Remove voice run on a file through the real
 * DTLN models, and measured (EDGE_PLAN G3) - no screen needed, so it runs on a
 * locked phone.
 *
 *   am start -n com.squish.app/.debug.VoiceSplitProbeActivity --es uri <uri> \
 *     --es mode VoiceOnly|RemoveVoice --el from 0 --el to 10000 --el quietTo 2000
 *
 * Logs under SquishVoiceSplit: how long it took, and the level of the first
 * [quietTo] ms (the music alone, in the test file) and of the rest, before and
 * after, in dBFS. The rendered WAV is left under files/voicesplit for pulling.
 */
class VoiceSplitProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(16, 16, 16, 16) }
        setContentView(text)
        val uri = intent.getStringExtra("uri")?.let(Uri::parse) ?: return
        val mode = runCatching { VoiceSplit.Mode.valueOf(intent.getStringExtra("mode") ?: "VoiceOnly") }.getOrDefault(VoiceSplit.Mode.VoiceOnly)
        val from = intent.getLongExtra("from", 0L)
        val to = intent.getLongExtra("to", 10_000L)
        val quietTo = intent.getLongExtra("quietTo", 2_000L)
        val level = intent.getFloatExtra("level", 1f)
        scope.launch {
            val started = System.nanoTime()
            val result = VoiceSplitter.render(this@VoiceSplitProbeActivity, uri, from, to, mode, level)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            val line = when (result) {
                is VoiceSplitter.Result.Done -> {
                    val before = PcmDecoder.decodeMono(this@VoiceSplitProbeActivity, uri, 48_000, to - from, fromMs = from)
                    val after = PcmDecoder.decodeMono(this@VoiceSplitProbeActivity, Uri.fromFile(result.file), 48_000, to - from)
                    fun db(p: com.squish.app.media.audio.MonoPcm?, a: Long, b: Long): String {
                        if (p == null) return "?"
                        val i = (a * p.sampleRate / 1000).toInt().coerceIn(0, p.samples.size)
                        val j = (b * p.sampleRate / 1000).toInt().coerceIn(i, p.samples.size)
                        if (j <= i) return "?"
                        var s = 0.0
                        for (k in i until j) s += p.samples[k].toDouble() * p.samples[k]
                        return "%.1f".format(10 * log10((s / (j - i)).coerceAtLeast(1e-12)))
                    }
                    val span = to - from
                    "${mode.label} in $tookMs ms for $span ms; first $quietTo ms ${db(before, 0, quietTo)} -> ${db(after, 0, quietTo)} dBFS; " +
                        "rest ${db(before, quietTo, span)} -> ${db(after, quietTo, span)} dBFS; file ${result.file.name}"
                }
                else -> "${mode.label}: $result after $tookMs ms"
            }
            Log.i(TAG, "$uri: $line")
            text.text = line
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val TAG = "SquishVoiceSplit"
    }
}
