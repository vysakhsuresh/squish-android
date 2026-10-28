package com.squish.app.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.squish.app.media.video.TrackRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug builds only: the tracker (EDGE_PLAN F8) run over a file from a point
 * and a box, every sample logged - so a track can be read against where the
 * thing really is without driving the Track sheet by hand.
 *
 *   am start -n com.squish.app/.debug.TrackProbeActivity --es uri content://media/external/video/media/<id> \
 *       --ef x 0.18 --ef y 0.4 --ef box 0.12 --el from 0 --el to 9000
 *
 * Logs under SquishTrackProbe: "t=<ms> x=<fraction> y=<fraction> scale=<s> conf=<c>",
 * then "done <n> samples".
 */
class TrackProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(16, 16, 16, 16); this.text = "tracking…" }
        setContentView(text)
        val uri = intent.getStringExtra("uri")?.let(Uri::parse) ?: run { Log.w(TAG, "no uri"); finish(); return }
        val x = intent.getFloatExtra("x", 0.5f)
        val y = intent.getFloatExtra("y", 0.5f)
        val box = intent.getFloatExtra("box", 0.15f)
        val from = intent.getLongExtra("from", 0L)
        val to = intent.getLongExtra("to", 10_000L)
        val w = intent.getIntExtra("w", 1280)
        val h = intent.getIntExtra("h", 720)
        scope.launch {
            val track = runCatching {
                TrackRunner.track(this@TrackProbeActivity, uri, w, h, 30f, from, to, x, y, box) { _, _ -> }
            }.onFailure { Log.w(TAG, "failed", it) }.getOrNull()
            val samples = track?.samples.orEmpty()
            for (s in samples) {
                Log.i(TAG, String.format(java.util.Locale.ROOT, "t=%d x=%.4f y=%.4f scale=%.4f conf=%.3f", s.atMs, s.xFraction, s.yFraction, s.scale, s.confidence))
            }
            Log.i(TAG, "done ${samples.size} samples")
            text.text = "done ${samples.size} samples"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object { const val TAG = "SquishTrackProbe" }
}
