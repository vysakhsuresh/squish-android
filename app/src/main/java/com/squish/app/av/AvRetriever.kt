package com.squish.app.av

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.Closeable

/**
 * MediaMetadataRetriever, with Squish's FFmpeg behind it.
 *
 * The filmstrip, the covers, Freeze, the tracker, the stabilizer, the
 * reframer, the person masks, the GIF maker and the blurred backdrops all
 * read frames through the platform's retriever, which shares the platform's
 * container readers and decoders: a WMV, a ProRes MOV, a 10-bit HEVC on a
 * phone with no Main 10 decoder give it nothing. This answers the same calls
 * the same way - frames turned upright, scaled to fit the box asked for,
 * the same metadata keys - and asks the platform first, so a file it reads
 * is read exactly as before. FFmpeg answers when the file is one only FFmpeg
 * reads whole (AvRoute), when the platform cannot open it, and when the
 * platform opens it but gives back no frame.
 *
 * Not thread-safe, like the class it stands in for.
 */
class AvRetriever : Closeable {

    private var platform: MediaMetadataRetriever? = null
    private var av: AvFrameSource? = null
    private var appContext: Context? = null
    private var uri: Uri? = null
    /** The platform gave no frame once; FFmpeg answers frames from then on. */
    private var platformFramesFailed = false
    private var avTried = false

    /** Opens [uri]. Throws IllegalArgumentException, as the platform's does, when nothing reads it. */
    fun setDataSource(context: Context, uri: Uri) {
        release()
        this.appContext = context.applicationContext
        this.uri = uri
        val route = AvRoute.decide(context, uri)
        if (route.ffmpeg) {
            av = AvFrameSource.open(context, uri, threads = 1)
            avTried = true
            if (av == null) throw IllegalArgumentException("nothing reads $uri")
            return
        }
        val p = MediaMetadataRetriever()
        try {
            p.setDataSource(context, uri)
            platform = p
        } catch (e: Exception) {
            p.release()
            av = AvFrameSource.open(context, uri, threads = 1)
            avTried = true
            if (av == null) throw if (e is IllegalArgumentException) e else IllegalArgumentException(e)
        }
        // A file whose picture only FFmpeg decodes (the import probe found
        // so): the platform reads its header and then gives no frames.
        val report = com.squish.app.media.MediaCompat.cached(uri)
        if (report != null && (report.videoSoftware || report.videoProblem != null)) platformFramesFailed = true
    }

    private fun ffmpeg(): AvFrameSource? {
        av?.let { return it }
        if (avTried) return null
        avTried = true
        val c = appContext ?: return null
        val u = uri ?: return null
        av = AvFrameSource.open(c, u, threads = 1)
        return av
    }

    fun extractMetadata(key: Int): String? {
        platform?.let { p ->
            runCatching { p.extractMetadata(key) }.getOrNull()?.let { return it }
            // The platform answering nothing for the picture's own keys on a
            // file it has opened is the platform not reading the picture.
            if (key !in PICTURE_KEYS) return null
        }
        return ffmpeg()?.metadata(key)
    }

    fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? = frame(timeUs, option, 0, 0)

    fun getFrameAtTime(timeUs: Long): Bitmap? = frame(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 0, 0)

    fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? =
        frame(timeUs, option, dstWidth, dstHeight)

    private fun frame(timeUs: Long, option: Int, w: Int, h: Int): Bitmap? {
        val p = platform
        if (p != null && !platformFramesFailed) {
            val got = runCatching {
                if (w > 0 && h > 0) p.getScaledFrameAtTime(timeUs, option, w, h) else p.getFrameAtTime(timeUs, option)
            }.getOrNull()
            if (got != null) return got
            platformFramesFailed = ffmpeg() != null
            if (!platformFramesFailed) return null
        }
        val exact = option == MediaMetadataRetriever.OPTION_CLOSEST
        return ffmpeg()?.frameAt(timeUs, exact, w, h)
    }

    /** Frames [start] to [start] + [count] - 1 in decoding order, full size and upright. */
    fun getFramesAtIndex(start: Int, count: Int): List<Bitmap> {
        val p = platform
        if (p != null && !platformFramesFailed) {
            val got = runCatching { p.getFramesAtIndex(start, count) }.getOrNull()
            if (!got.isNullOrEmpty()) return got
            platformFramesFailed = ffmpeg() != null
            if (!platformFramesFailed) return emptyList()
        }
        return ffmpeg()?.framesAtIndex(start, count).orEmpty()
    }

    fun release() {
        platform?.let { runCatching { it.release() } }
        platform = null
        av?.close()
        av = null
        avTried = false
        platformFramesFailed = false
    }

    override fun close() = release()

    private companion object {
        val PICTURE_KEYS = setOf(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
        )
    }
}

/**
 * Frames of one file's picture through Squish's FFmpeg: at a time (the
 * nearest key frame, or exactly), or in order by index. Upright and scaled
 * the way MediaMetadataRetriever gives them.
 */
class AvFrameSource private constructor(
    private val file: AvFile,
    private val stream: AvStream?,
    private val decoder: AvDecoder?
) : Closeable {

    private val frame = AvDecoder.Frame()
    /** Index of the last frame decoded since the last seek to the start, or -1. */
    private var index = -1
    private var sequential = false

    val info: AvInfo get() = file.info

    fun metadata(key: Int): String? {
        val info = file.info
        val s = stream
        return when (key) {
            MediaMetadataRetriever.METADATA_KEY_DURATION -> info.durationMs.takeIf { it > 0 }?.toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH -> s?.displayWidth?.takeIf { it > 0 }?.toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT -> s?.height?.takeIf { it > 0 }?.toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION -> s?.rotation?.toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT -> s?.let { frameCount(it, info) }?.takeIf { it > 0 }?.toString()
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO -> if (s != null) "yes" else null
            MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO -> if (info.audio != null) "yes" else null
            MediaMetadataRetriever.METADATA_KEY_BITRATE -> info.bitRate.takeIf { it > 0 }?.toString()
            MediaMetadataRetriever.METADATA_KEY_TITLE -> info.title.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun frameCount(s: AvStream, info: AvInfo): Long =
        if (s.frames > 0) s.frames
        else if (s.fps > 0f && info.durationUs > 0) Math.round(info.durationUs / 1_000_000.0 * s.fps)
        else 0L

    /**
     * The frame at [timeUs] from the start of the file: the key frame at or
     * before it, or with [exact] the frame nearest it. Fitted inside
     * [maxWidth]x[maxHeight] when both are given (never scaled up).
     */
    fun frameAt(timeUs: Long, exact: Boolean, maxWidth: Int, maxHeight: Int): Bitmap? {
        val s = stream ?: return null
        val d = decoder ?: return null
        val target = timeUs.coerceAtLeast(0L) + file.info.startUs
        AvNative.fileSeekDecoder(file.nativeHandle, d.nativeHandle, target)
        sequential = false
        index = -1
        if (next(s, d) != AvNative.OK) return null
        if (!exact || frame.ptsUs == Long.MIN_VALUE || frame.ptsUs >= target) return draw(s, d, 0L, maxWidth, maxHeight)
        // On towards the moment, keeping the frame before in case it is the nearer.
        var previous = d.takeFrame()
        var previousPts = frame.ptsUs
        val previousMeta = LongArray(frame.meta.size).also { System.arraycopy(frame.meta, 0, it, 0, it.size) }
        try {
            var steps = 0
            while (steps++ < MAX_EXACT_STEPS) {
                if (next(s, d) != AvNative.OK) break
                if (frame.ptsUs == Long.MIN_VALUE || frame.ptsUs >= target) {
                    return if (target - previousPts < frame.ptsUs - target) {
                        System.arraycopy(previousMeta, 0, frame.meta, 0, previousMeta.size)
                        draw(s, d, previous, maxWidth, maxHeight)
                    } else {
                        draw(s, d, 0L, maxWidth, maxHeight)
                    }
                }
                AvDecoder.freeFrame(previous)
                previous = d.takeFrame()
                previousPts = frame.ptsUs
                System.arraycopy(frame.meta, 0, previousMeta, 0, previousMeta.size)
            }
            // The moment is past the last frame: the last frame it is.
            System.arraycopy(previousMeta, 0, frame.meta, 0, previousMeta.size)
            return draw(s, d, previous, maxWidth, maxHeight)
        } finally {
            AvDecoder.freeFrame(previous)
        }
    }

    /** Frames by index from the start, in order, full size; a call asking from behind starts over. */
    fun framesAtIndex(start: Int, count: Int): List<Bitmap> {
        val s = stream ?: return emptyList()
        val d = decoder ?: return emptyList()
        if (!sequential || index >= start) {
            AvNative.fileSeekDecoder(file.nativeHandle, d.nativeHandle, file.info.startUs)
            index = -1
            sequential = true
        }
        val out = ArrayList<Bitmap>(count)
        while (index < start + count - 1) {
            if (next(s, d) != AvNative.OK) break
            index++
            if (index >= start) draw(s, d, 0L, 0, 0)?.let { out += it }
        }
        return out
    }

    private fun next(s: AvStream, d: AvDecoder): Int =
        AvNative.fileDecodeNext(file.nativeHandle, d.nativeHandle, s.index, frame.meta)

    /** The current (or [held]) frame as an upright bitmap, fitted in the box when one is given. */
    private fun draw(s: AvStream, d: AvDecoder, held: Long, maxWidth: Int, maxHeight: Int): Bitmap? {
        val fw = frame.width
        val fh = frame.height
        if (fw <= 0 || fh <= 0) return null
        // The picture's shape as seen: the pixel shape applied, then the turn.
        val shownW = if (s.sarNum > 0 && s.sarDen > 0) fw * s.sarNum.toDouble() / s.sarDen else fw.toDouble()
        val shownH = fh.toDouble()
        val turned = s.rotation % 180 != 0
        val boxW = if (turned) maxHeight else maxWidth
        val boxH = if (turned) maxWidth else maxHeight
        val scale = if (boxW > 0 && boxH > 0) minOf(1.0, boxW / shownW, boxH / shownH) else 1.0
        val w = Math.round(shownW * scale).toInt().coerceAtLeast(1)
        val h = Math.round(shownH * scale).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        if (!d.toBitmap(bitmap, held)) {
            bitmap.recycle()
            return null
        }
        if (s.rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(s.rotation.toFloat()) }
        val upright = Bitmap.createBitmap(bitmap, 0, 0, w, h, m, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    override fun close() {
        decoder?.close()
        file.close()
    }

    companion object {
        /** Frames decoded past the key frame looking for the moment; four seconds at 60 fps... and then some. */
        private const val MAX_EXACT_STEPS = 600

        /**
         * [threads] for the decoder: one for a source asked for frames here
         * and there (a filmstrip, a cover), since FFmpeg's frame threads each
         * hold a frame back and a seek then decodes that many to show one;
         * more for one walked in order.
         */
        fun open(context: Context, uri: Uri, threads: Int = AvDecoder.defaultThreads()): AvFrameSource? {
            val file = AvFile.open(context, uri) ?: return null
            val stream = file.info.video
            val decoder = stream?.let { runCatching { AvDecoder.forStream(file, it.index, threads) }.getOrNull() }
            if (stream != null && decoder == null) {
                // No picture FFmpeg can show; the file still answers its length.
                return AvFrameSource(file, null, null)
            }
            return AvFrameSource(file, stream, decoder)
        }
    }
}
