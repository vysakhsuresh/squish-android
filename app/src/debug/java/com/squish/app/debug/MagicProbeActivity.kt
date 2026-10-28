package com.squish.app.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.squish.app.media.video.Segmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Debug builds only: what MagicTouch (EDGE_PLAN F7) hands back for a tap, mask
 * by mask - on a plain red square on white, and on a file's first frame.
 *
 *   am start -n com.squish.app/.debug.MagicProbeActivity --es uri content://media/external/video/media/<id> --ef x 0.28 --ef y 0.55
 *
 * Logs under SquishMagic: how many masks came back, and for each its size, its
 * range, its mean and its value at the point.
 */
class MagicProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(16, 16, 16, 16) }
        setContentView(text)
        val uri = intent.getStringExtra("uri")?.let(Uri::parse)
        val x = intent.getFloatExtra("x", 0.5f)
        val y = intent.getFloatExtra("y", 0.5f)
        scope.launch {
            val lines = withContext(Dispatchers.Default) {
                val out = ArrayList<String>()
                val touch = Segmenter.createTouch(this@MagicProbeActivity)
                if (touch == null) { out += "the model did not load"; return@withContext out }
                val square = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it); c.drawColor(Color.WHITE)
                    c.drawRect(100f, 50f, 220f, 130f, Paint().apply { color = Color.RED })
                }
                out += probe(touch, square, 0.5f, 0.5f, "red square, at its middle")
                out += probe(touch, square, 0.1f, 0.1f, "red square, on the white")
                val big = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
                    val c = Canvas(it); c.drawColor(Color.WHITE)
                    c.drawRect(156f, 156f, 356f, 356f, Paint().apply { color = Color.RED })
                }
                out += probe(touch, big, 0.5f, 0.5f, "512 red square, at its middle")
                // The category mask as well: which pixels it calls the thing.
                val cat = runCatching {
                    com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.createFromOptions(
                        this@MagicProbeActivity,
                        com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                            .setBaseOptions(com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
                            .setOutputConfidenceMasks(true)
                            .setOutputCategoryMask(false)
                            .build()
                    )
                }.getOrElse { out += "category segmenter: $it"; null }
                if (cat != null) {
                    val roi = com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest.create(
                        com.google.mediapipe.tasks.components.containers.NormalizedKeypoint.create(0.5f, 0.5f))
                    val r = cat.segment(BitmapImageBuilder(big).build(), roi)
                    out += "the point in pixels:"
                    out += probe(cat, big, 256f, 256f, "512 red square, (256, 256) px")
                    out += probe(cat, square, 160f, 90f, "red square, (160, 90) px")
                    out += probe(cat, square, 30f, 20f, "red square, on the white, (30, 20) px")
                    cat.close()
                }
                if (uri != null) {
                    val retriever = com.squish.app.av.AvRetriever()
                    runCatching {
                        retriever.setDataSource(this@MagicProbeActivity, uri)
                        val frame = retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                        if (frame == null) out += "no first frame" else {
                            val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
                            out += "frame ${argb.width}x${argb.height} config ${argb.config}"
                            out += probe(touch, argb, x, y, "the file at ($x, $y)")
                        }
                    }.onFailure { out += "file: $it" }
                    runCatching { retriever.release() }
                }
                touch.close()
                out
            }
            lines.forEach { Log.i("SquishMagic", it) }
            text.text = lines.joinToString("\n")
        }
    }

    private fun probe(
        touch: com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter,
        bitmap: Bitmap, x: Float, y: Float, label: String
    ): List<String> {
        val roi = com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest.create(
            com.google.mediapipe.tasks.components.containers.NormalizedKeypoint.create(x, y)
        )
        val result = touch.segment(BitmapImageBuilder(bitmap).build(), roi)
        val masks = result.confidenceMasks().orElse(null) ?: return listOf("$label: no confidence masks")
        val out = arrayListOf("$label: ${masks.size} mask(s)")
        masks.forEachIndexed { i, image ->
            val buffer = ByteBufferExtractor.extract(image).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val w = image.width; val h = image.height
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var sum = 0.0; var hiAt = 0; var over = 0
            for (k in 0 until w * h) { val v = buffer.get(k); if (v < lo) lo = v; if (v > hi) { hi = v; hiAt = k }; if (v > 0.5f) over++; sum += v }
            val px = if (x > 1f) x / bitmap.width else x; val py = if (y > 1f) y / bitmap.height else y
            val at = buffer.get(((py * h).toInt().coerceIn(0, h - 1)) * w + (px * w).toInt().coerceIn(0, w - 1))
            out += "  mask $i: ${w}x$h range $lo..$hi mean ${"%.3f".format(java.util.Locale.ROOT, sum / (w * h))} at the point $at; peak at (${hiAt % w}, ${hiAt / w}), $over pixels over a half"
        }
        return out
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
