@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbFilter
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.squish.app.av.AvImages
import com.squish.app.av.AvNative
import com.squish.app.av.AvRetriever
import com.squish.app.av.AvRoute
import com.squish.app.media.MediaCompat
import com.squish.app.media.ReverseRenderer
import com.squish.app.media.SoftwareDecoders
import com.squish.app.media.StillClips
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.video.LumaDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Debug builds only: every file in a folder through every reader the app has,
 * one line of results each - for checking on a phone that Squish's own FFmpeg
 * reads what it should, without opening eighty projects by hand.
 *
 *   adb push <files> /sdcard/Android/data/com.squish.app/files/selftest/
 *   adb shell am start -n com.squish.app/.debug.AvSelfTestActivity [--es only <name part>]
 *   adb logcat -s SquishSelfTest
 *
 * The results are also written to files/selftest_results.txt there. Each line
 * says which reader opened the file, its probe, a frame's variety (a test
 * card is far from flat), the sound's level, what a player with an effect on
 * rendered and with which decoders, and whether a two-second export, a
 * reverse and the stabilizer's frame walk worked - or, for a picture, its
 * decode and its render into a still clip.
 */
class AvSelfTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 11f; setPadding(16, 16, 16, 16) }
        setContentView(ScrollView(this).apply { addView(text) })
        val only = intent.getStringExtra("only")
        // Files adb pushes into Android/data are not listed to the app on
        // Android 15, so a run-as copy under files/imports/selftest is the
        // usual place (imports, so AvRoute decides on them as on any share).
        val dir = intent.getStringExtra("dir")?.let { File(it) } ?: File(filesDir, "imports/selftest")
        scope.launch { runAll(dir, only) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        text.append(line + "\n")
    }

    private suspend fun runAll(dir: File, only: String?) {
        say("libsquishav loaded=${AvNative.loaded} " + if (AvNative.loaded) AvNative.version() + " decoders/demuxers=" + AvNative.counts().joinToString("/") else "")
        val files = dir.listFiles()?.filter { it.isFile && (only == null || it.name.contains(only)) }?.sortedBy { it.name }.orEmpty()
        val out = File(getExternalFilesDir(null), "selftest_results.txt")
        out.writeText("")
        var passed = 0
        for (f in files) {
            val result = runCatching { withTimeoutOrNull(120_000) { testOne(Uri.fromFile(f), f.name) } ?: JSONObject().put("file", f.name).put("ok", false).put("error", "timed out") }
                .getOrElse { JSONObject().put("file", f.name).put("ok", false).put("error", it.toString()) }
            if (result.optBoolean("ok")) passed++
            val line = result.toString()
            say(line)
            out.appendText(line + "\n")
        }
        say("DONE $passed/${files.size} passed")
        out.appendText("DONE $passed/${files.size} passed\n")
    }

    private suspend fun testOne(uri: Uri, name: String): JSONObject {
        val r = JSONObject().put("file", name)
        val picture = AvImages.isPicture(this, uri)
        r.put("kind", if (picture) "picture" else "media")
        return if (picture) testPicture(uri, r) else testMedia(uri, r)
    }

    private suspend fun testPicture(uri: Uri, r: JSONObject): JSONObject {
        val platform = withContext(Dispatchers.IO) { AvImages.platformReads(this@AvSelfTestActivity, uri) }
        r.put("platformReads", platform)
        val t0 = System.currentTimeMillis()
        val bitmap = withContext(Dispatchers.IO) {
            StillClips.previewBitmap(this@AvSelfTestActivity, uri, 1080)
        }
        r.put("decodeMs", System.currentTimeMillis() - t0)
        if (bitmap != null) {
            r.put("size", "${bitmap.width}x${bitmap.height}").put("variety", variety(bitmap))
            bitmap.recycle()
        }
        val t1 = System.currentTimeMillis()
        val clip = StillClips.fromImage(this, uri)
        r.put("stillMs", System.currentTimeMillis() - t1)
        val clipBytes = clip?.path?.let { File(it).length() } ?: 0L
        r.put("still", clipBytes)
        val overlay = withContext(Dispatchers.IO) { StillClips.overlayFromImage(this@AvSelfTestActivity, uri) }
        r.put("overlay", overlay != null)
        r.put("ok", bitmap != null && clipBytes > 0 && overlay != null && r.optDouble("variety") > 4.0)
        return r
    }

    private suspend fun testMedia(uri: Uri, r: JSONObject): JSONObject {
        val context = this
        val route = withContext(Dispatchers.IO) { AvRoute.decide(context, uri) }
        r.put("reader", if (route.ffmpeg) "ffmpeg" else "media3")
        route.info?.let { info -> r.put("ffprobe", info.format + ": " + info.streams.joinToString { "${it.type}:${it.codec}" }) }
        val meta = ThumbnailExtractor.probe(context, uri)
        r.put("probe", "${meta.durationMs}ms ${meta.width}x${meta.height} r${meta.rotationDegrees} ${meta.fps}fps audio=${meta.hasAudio}")
        val report = MediaCompat.check(context, uri)
        r.put("compat", report?.let { "v=${it.videoProblem ?: "ok"}${if (it.videoSoftware) "(ffmpeg)" else ""} a=${it.audioProblem ?: "ok"}${if (it.audioSoftware) "(ffmpeg)" else ""}" } ?: "unread")
        val hasVideo = meta.width > 0 && meta.height > 0

        if (hasVideo) {
            val frame = withContext(Dispatchers.IO) {
                AvRetriever().use { ret ->
                    ret.setDataSource(context, uri)
                    ret.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                }
            }
            r.put("frame", frame?.let { "${it.width}x${it.height} variety ${"%.1f".format(variety(it))}" } ?: "none")
            r.put("frameVariety", frame?.let { variety(it) } ?: 0.0)
            frame?.recycle()
            var lumaFrames = 0
            withContext(Dispatchers.IO) { LumaDecoder.decode(context, uri, 0L, 1_000L, 1) { _, _ -> lumaFrames++ } }
            r.put("lumaFrames", lumaFrames)
        }
        val pcm = withContext(Dispatchers.IO) { PcmDecoder.decodeMono(context, uri, maxDurationMs = 4_000L) }
        r.put("pcm", pcm?.let { "${it.samples.size}@${it.sampleRate} rms ${"%.3f".format(rms(it.samples))}" } ?: "none")
        val play = play(uri, hasVideo)
        r.put("play", play)
        val export = export(uri, hasVideo, meta.hasAudio)
        r.put("export", export)
        if (hasVideo) {
            val reversed = File(cacheDir, "selftest_rev.mp4")
            val rev = ReverseRenderer.render(context, uri, 0L, minOf(2_000L, meta.durationMs.coerceAtLeast(500L)), reversed)
            r.put("reverse", rev.fold({ "ok ${it.durationMs}ms ${reversed.length()}B" }, { "FAIL ${it.message}" }))
            reversed.delete()
        }
        val ok = (!hasVideo || r.optDouble("frameVariety") > 4.0) &&
            (!meta.hasAudio || pcm != null) &&
            play.optBoolean("ok") && export.optBoolean("ok") &&
            (!hasVideo || r.optString("reverse").startsWith("ok"))
        r.put("ok", ok)
        return r
    }

    /** Plays three seconds into an off-screen surface with an effect on, muted, and counts what came out. */
    private suspend fun play(uri: Uri, video: Boolean): JSONObject = withContext(Dispatchers.Main) {
        val out = JSONObject()
        // GPU output usage: the effects pipeline draws into this, and a reader
        // without it was handed nothing, from any decoder.
        val reader = ImageReader.newInstance(
            640, 360, PixelFormat.RGBA_8888, 4,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or android.hardware.HardwareBuffer.USAGE_CPU_READ_RARELY
        )
        // Counted as the player hands each frame to the surface: an RGBA reader
        // is never given a decoder's YUV buffers, so counting its images read 0.
        val surfaceCount = java.util.concurrent.atomic.AtomicInteger()
        reader.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val player = ExoPlayer.Builder(this@AvSelfTestActivity, SoftwareDecoders.renderers(this@AvSelfTestActivity))
            .setMediaSourceFactory(SoftwareDecoders.mediaSourceFactory(this@AvSelfTestActivity))
            .build()
        val decoders = mutableListOf<String>()
        var error: PlaybackException? = null
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoders += "v:$decoderName" }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoders += "a:$decoderName" }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, e: PlaybackException) { error = e }
        })
        try {
            if (video) {
                if (intent.getBooleanExtra("effects", true)) player.setVideoEffects(listOf(RgbFilter.createGrayscaleFilter()))
                player.setVideoFrameMetadataListener { _, _, _, _ -> surfaceCount.incrementAndGet() }
                player.setVideoSurface(reader.surface)
            }
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 3_500 && error == null && player.playbackState != Player.STATE_ENDED) {
                kotlinx.coroutines.delay(100)
            }
            val v = player.videoDecoderCounters
            val a = player.audioDecoderCounters
            out.put("decoders", decoders.joinToString())
            out.put("videoRendered", v?.renderedOutputBufferCount ?: 0)
            out.put("videoDropped", v?.droppedBufferCount ?: 0)
            out.put("surfaceFrames", surfaceCount.get())
            out.put("audioOut", a?.renderedOutputBufferCount ?: 0)
            out.put("position", player.currentPosition)
            error?.let { out.put("error", it.errorCodeName + ": " + (it.cause?.toString() ?: it.message)) }
            val videoOk = !video || ((v?.renderedOutputBufferCount ?: 0) > 10 && surfaceCount.get() > 5)
            // The clock moving is what says the player played: a stalled one sits at 0.
            out.put("ok", error == null && videoOk && player.currentPosition > 1_000L)
        } finally {
            player.release()
            reader.close()
        }
        out
    }

    /** Two seconds out through the app's Transformer setup, then read back with the platform's extractor. */
    /** As VideoProcessor does: a copy the MP4 writer refuses is made again, encoded. */
    private suspend fun export(uri: Uri, video: Boolean, audio: Boolean): JSONObject {
        val first = export(uri, video, audio, encode = false)
        if (first.optBoolean("ok") || !video) return first
        return export(uri, video, audio, encode = true).also { it.put("result", "re-encoded after copy failed: " + it.optString("result")) }
    }

    private suspend fun export(uri: Uri, video: Boolean, audio: Boolean, encode: Boolean): JSONObject = withContext(Dispatchers.Main) {
        val out = JSONObject()
        val file = File(cacheDir, "selftest_export.mp4").apply { delete() }
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(2_000).build()
            ).build()
        ).apply { if (encode) setEffects(Effects(ImmutableList.of(), ImmutableList.of<androidx.media3.common.Effect>(RgbFilter.createGrayscaleFilter()))) }.build()
        val done = suspendCancellableCoroutine<String> { cont ->
            val transformer = Transformer.Builder(this@AvSelfTestActivity)
                .setAssetLoaderFactory(SoftwareDecoders.assetLoaderFactory(this@AvSelfTestActivity))
                .apply { if (video) setVideoMimeType(MimeTypes.VIDEO_H264) }
                .apply { if (audio) setAudioMimeType(MimeTypes.AUDIO_AAC) }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume("ok frames=${exportResult.videoFrameCount} v=${exportResult.videoEncoderName} a=${exportResult.audioEncoderName}")
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume("FAIL ${exportException.errorCodeName}: ${exportException.cause ?: exportException.message}")
                    }
                })
                .build()
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            runCatching { transformer.start(item, file.absolutePath) }.onFailure { if (cont.isActive) cont.resume("FAIL start: $it") }
        }
        out.put("result", done)
        val tracks = withContext(Dispatchers.IO) {
            runCatching {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.absolutePath)
                    (0 until ex.trackCount).joinToString { i ->
                        val f = ex.getTrackFormat(i)
                        f.getString(MediaFormat.KEY_MIME) + if (f.containsKey(MediaFormat.KEY_DURATION)) "/${f.getLong(MediaFormat.KEY_DURATION) / 1000}ms" else ""
                    }
                } finally {
                    ex.release()
                }
            }.getOrElse { "unreadable" }
        }
        out.put("tracks", tracks)
        out.put("ok", done.startsWith("ok") && (!video || tracks.contains("video/")) && (!audio || tracks.contains("audio/")))
        file.delete()
        out
    }

    /** The spread of brightness across a frame: near 0 for a flat or black one, tens for a test card. */
    private fun variety(b: Bitmap): Double {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        var sq = 0.0
        for (p in px) {
            val y = 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
            sum += y
            sq += y * y
        }
        val n = px.size.coerceAtLeast(1)
        val mean = sum / n
        return sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
    }

    private fun rms(s: FloatArray): Double {
        var sq = 0.0
        for (v in s) sq += v * v
        return sqrt(sq / s.size.coerceAtLeast(1))
    }

    private companion object {
        const val TAG = "SquishSelfTest"
    }
}
