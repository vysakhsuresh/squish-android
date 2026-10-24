package com.squish.app.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.squish.app.av.AvFrames
import com.squish.app.media.SyncAnalysis
import com.squish.app.media.audio.PcmDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug builds only: reads A/V sync off a file (EDGE_PLAN B3).
 *
 *   am start -n com.squish.app/.debug.SyncProbeActivity --es uri content://media/external/video/media/<id>
 *   am start -n com.squish.app/.debug.SyncProbeActivity --es path /data/user/0/com.squish.app/files/x.mp4
 *
 * Every frame's mean luma off Squish's own FFmpeg (AvFrames), the sound off
 * PcmDecoder at 4 kHz, the two matched by SyncAnalysis; the report goes to
 * logcat under SquishSync and to files/sync_report.txt. Meant for the sync
 * clips of tools/native/make_bench_media.sh, which flash and beep together
 * on every second, before and after going through an export.
 */
class SyncProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(text) })
        val uri = intent.getStringExtra("uri")?.let(Uri::parse)
            ?: intent.getStringExtra("path")?.let { Uri.fromFile(File(it)) }
        if (uri == null) { say("no uri or path given"); return }
        scope.launch { probe(uri) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        text.append(line + "\n")
    }

    private suspend fun probe(uri: Uri) {
        say("probing $uri")
        val t0 = System.currentTimeMillis()
        val times = ArrayList<Long>()
        val luma = ArrayList<Float>()
        val read = withContext(Dispatchers.Default) {
            AvFrames.forEach(
                this@SyncProbeActivity, uri, 0L, Long.MAX_VALUE / 2,
                wanted = { _, _ -> true },
                convert = { view -> view.grid { _, _ -> 32 to 18 }.pixels.average().toFloat() },
                onFrame = { at, mean -> times += at; luma += mean }
            )
        }
        say("video: ${if (read) "${times.size} frames" else "could not be read"} in ${System.currentTimeMillis() - t0} ms")
        val pcm = PcmDecoder.decodeMono(this, uri, targetSampleRate = 4_000, maxDurationMs = 4L * 60 * 60 * 1000)
        say("sound: ${pcm?.let { "${it.samples.size} samples at ${it.sampleRate} Hz" } ?: "could not be read"}")
        if (pcm == null || times.isEmpty()) return
        val flashes = SyncAnalysis.flashes(times.toLongArray(), luma.toFloatArray())
        // The platform's decoder hands over the encoder's priming as sound and
        // the MP4's audio edit list says to skip it; a player skips it, so the
        // probe must too. Unskipped, every beep read 21 ms late on a camera
        // file and 33 ms late on an export (Media3 writes 1600 samples there),
        // which read as the export losing sync when it had not.
        val delayMs = encoderDelayMs(uri)
        val beeps = SyncAnalysis.beeps(pcm.samples, pcm.sampleRate).map { (it - delayMs).coerceAtLeast(0L) }
        if (delayMs > 0) say("audio priming skipped: $delayMs ms (the audio edit list)")
        val report = SyncAnalysis.report(SyncAnalysis.pair(flashes, beeps))
        val line = "flashes ${flashes.size}, beeps ${beeps.size}; ${report ?: "nothing paired"}"
        say(line)
        runCatching { File(filesDir, "sync_report.txt").appendText("$uri\n$line\n") }
    }

    /** The audio track's encoder delay as the platform reads it off the file's edit list, in ms; 0 when it says none. */
    private fun encoderDelayMs(uri: Uri): Long = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { f ->
                    val rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    val delay = if (f.containsKey("encoder-delay")) f.getInteger("encoder-delay") else 0
                    if (rate > 0) delay * 1000L / rate else 0L
                } ?: 0L
        } finally {
            extractor.release()
        }
    }.getOrDefault(0L)

    private companion object { const val TAG = "SquishSync" }
}
