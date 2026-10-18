package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.squish.app.av.AvFile
import com.squish.app.av.AvInfo
import com.squish.app.av.AvRetriever
import com.squish.app.av.AvRoute
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMeta(
    val durationMs: Long,
    /** As stored in the file, before the container's rotation is applied. */
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float,
    /** Whether the file carries sound at all. Unknown reads as yes. */
    val hasAudio: Boolean = true
) {
    /**
     * The shape the picture is actually seen in.
     *
     * A phone shoots portrait as a 1920x1080 stream with a 90 degree rotation tag
     * on it, and every decoder turns it on the way out. Anything that asks the
     * file how wide the video is and believes the answer ends up with a landscape
     * frame for portrait footage - which is how a portrait export came out
     * letterboxed into a landscape box with black down both sides.
     */
    val displayWidth: Int get() = if (quarterTurned) height else width
    val displayHeight: Int get() = if (quarterTurned) width else height

    private val quarterTurned: Boolean get() = rotationDegrees % 180 != 0
}

object ThumbnailExtractor {

    /**
     * What a file is, asked once. A picked video used to be opened by four
     * readers before the editor showed - FFmpeg for its route, then
     * MediaMetadataRetriever, then MediaExtractor, then Media3's retriever
     * when any field came back empty - and the proxy engine asked again
     * after. The answer is kept for the process; only a real one is kept, so
     * a file that could not be read yet is asked again.
     */
    suspend fun probe(context: Context, uri: Uri): VideoMeta {
        probes[uri.toString()]?.let { return it }
        val t0 = android.os.SystemClock.elapsedRealtime()
        return probeUncached(context, uri).also {
            if (it.durationMs > 0L) probes[uri.toString()] = it
            com.squish.app.av.Perf.took("probe", android.os.SystemClock.elapsedRealtime() - t0, uri.lastPathSegment.orEmpty())
        }
    }

    private val probes = java.util.concurrent.ConcurrentHashMap<String, VideoMeta>()

    /** Forgets [uri]'s answer: a file relinked or replaced at the same address. */
    fun forget(uri: Uri) {
        probes.remove(uri.toString())
    }

    private suspend fun probeUncached(context: Context, uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
        // FFmpeg's description, which deciding the route (AvRoute) has
        // already read: one native open that answers the length, the stored
        // size, the turn, the rate and the sound together, where the
        // platform's path opened the file three times for the same. A file
        // only FFmpeg reads whole has no other answer anyway.
        val route = AvRoute.decide(context, uri)
        val info = route.info ?: if (route.ffmpeg) null else AvFile.probe(context, uri)
        info?.let { i -> metaOf(i)?.takeIf { m -> m.durationMs > 0L && (i.video == null || m.width > 0) } }
            ?.let { return@withContext it }
        val retriever = AvRetriever()
        val base = try {
            retriever.setDataSource(context, uri)
            VideoMeta(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                fps = 30f
            )
        } catch (t: Throwable) {
            // A file that cannot be opened - deleted, or a picker link whose
            // access ran out - is a video with nothing known about it, not a crash.
            // This threw straight through to the main thread and took the whole
            // app down when an old draft was reopened.
            //
            // Or a file the *platform* cannot parse and the player can: the
            // retriever and MediaExtractor share one set of container readers
            // (MP4, Matroska, TS, Ogg…), and an AVI, an FLV, or an MKV whose
            // first track is something odd returns nothing from them. The
            // editor took "no length" for "no file" and dropped the clip without
            // a word, while ExoPlayer and the export would have played it -
            // its readers are Media3's own and cover more. So the player's own
            // reader is asked before giving up.
            return@withContext probeWithMedia3(context, uri)
                ?: AvFile.probe(context, uri)?.let { metaOf(it) }
                ?: VideoMeta(durationMs = 0L, width = 0, height = 0, rotationDegrees = 0, fps = 30f)
        } finally {
            retriever.release()
        }

        // The track format wins where it has an answer. MediaMetadataRetriever is
        // documented loosely enough that whether its width is the coded width or
        // the displayed one has varied by device and by version, and getting that
        // backwards silently rotates every export. The extractor's width, height
        // and rotation-degrees are unambiguous: coded, and the tag separately.
        val track = probeVideoTrack(context, uri)
        val merged = base.copy(
            width = track?.width?.takeIf { it > 0 } ?: base.width,
            height = track?.height?.takeIf { it > 0 } ?: base.height,
            rotationDegrees = track?.rotation ?: base.rotationDegrees,
            fps = track?.fps ?: 30f,
            hasAudio = track?.hasAudio ?: true
        )
        // Opened but with no length or no picture measured: the same gap, met
        // half way - a container the platform reads the header of and no more.
        if (merged.durationMs <= 0L || merged.width <= 0 || merged.height <= 0) {
            probeWithMedia3(context, uri)?.let { m3 ->
                return@withContext merged.copy(
                    durationMs = if (merged.durationMs > 0L) merged.durationMs else m3.durationMs,
                    width = if (merged.width > 0) merged.width else m3.width,
                    height = if (merged.height > 0) merged.height else m3.height,
                    rotationDegrees = if (track?.rotation != null) merged.rotationDegrees else m3.rotationDegrees,
                    fps = track?.fps ?: m3.fps,
                    hasAudio = track?.hasAudio ?: m3.hasAudio
                )
            }
        }
        merged
    }

    /**
     * The file as the player reads it: length from the timeline, shape, turn
     * and rate from the first video track's format, sound from any audio
     * track. Null when Media3 cannot read it either, which is the real "no
     * file". A width of zero is possible for a stream that declares none;
     * the caller treats that as unknown, as it does the retriever's.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun probeWithMedia3(context: Context, uri: Uri): VideoMeta? = runCatching {
        androidx.media3.inspector.MetadataRetriever.Builder(context, androidx.media3.common.MediaItem.fromUri(uri))
            .setMediaSourceFactory(SoftwareDecoders.mediaSourceFactory(context))
            .build().use { retriever ->
                val durationUs = retriever.retrieveDurationUs().get(PROBE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                val groups = retriever.retrieveTrackGroups().get(PROBE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                var video: androidx.media3.common.Format? = null
                var hasAudio = false
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (t in 0 until group.length) {
                        val format = group.getFormat(t)
                        val mime = format.sampleMimeType ?: continue
                        if (video == null && androidx.media3.common.MimeTypes.isVideo(mime)) video = format
                        if (androidx.media3.common.MimeTypes.isAudio(mime)) hasAudio = true
                    }
                }
                val durationMs = if (durationUs > 0L && durationUs != androidx.media3.common.C.TIME_UNSET) durationUs / 1000L else 0L
                if (durationMs <= 0L && video == null && !hasAudio) return@use null
                VideoMeta(
                    durationMs = durationMs,
                    width = video?.width?.takeIf { it > 0 } ?: 0,
                    height = video?.height?.takeIf { it > 0 } ?: 0,
                    rotationDegrees = video?.rotationDegrees ?: 0,
                    fps = video?.frameRate?.takeIf { it > 1f && it < 1000f } ?: 30f,
                    hasAudio = hasAudio
                )
            }
    }.getOrNull()

    private const val PROBE_TIMEOUT_S = 10L

    /** A file as Squish's FFmpeg describes it, in this file's terms; null when it has no picture or sound at all. */
    private fun metaOf(info: AvInfo): VideoMeta? {
        val video = info.video
        if (video == null && info.audio == null && info.durationMs <= 0L) return null
        return VideoMeta(
            durationMs = info.durationMs,
            // The stored shape with the pixel shape applied - an anamorphic
            // DVD's 720x576 is 1024x576 to look at - and the turn kept apart,
            // as the retriever's keys keep it.
            width = video?.displayWidth ?: 0,
            height = video?.height ?: 0,
            rotationDegrees = video?.rotation ?: 0,
            fps = video?.fps?.takeIf { it > 1f && it < 1000f }?.let { roundedRate(it) } ?: 30f,
            hasAudio = info.audio != null
        )
    }

    /**
     * A rate within a tenth of a whole number as that number - 29.97 as 30,
     * 23.976 as 24 - which is what MediaExtractor's integer KEY_FRAME_RATE
     * said for the same files, and what every frame step in the editor was
     * worked out against. 12.5 stays 12.5.
     */
    private fun roundedRate(fps: Float): Float {
        val whole = Math.round(fps).toFloat()
        return if (kotlin.math.abs(fps - whole) <= 0.1f) whole else fps
    }

    private data class TrackInfo(
        val width: Int,
        val height: Int,
        val rotation: Int?,
        val fps: Float?,
        val hasAudio: Boolean
    )

    /** Container duration for any media file, including audio-only tracks. */
    suspend fun probeDurationMs(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = AvRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * MediaMetadataRetriever has no reliable frame-rate key, so read it off the
     * video track format directly. Frame rate drives every precision control in
     * the editor, so guessing 30 everywhere would put nudges off on 24/60fps clips.
     */
    private fun probeVideoTrack(context: Context, uri: Uri): TrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            // One pass for both questions: the video track's shape, and whether
            // there is any sound in the file at all. Asking separately would open
            // the extractor twice for an answer it already had.
            val hasAudio = (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }

            var result: TrackInfo? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue

                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull() }
                } else {
                    null
                }
                result = TrackInfo(
                    width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0),
                    height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0),
                    // Not every container carries it, and a missing tag is not a
                    // zero - the retriever's answer is better than inventing one.
                    rotation = if (format.containsKey(KEY_ROTATION)) {
                        runCatching { format.getInteger(KEY_ROTATION) }.getOrNull()
                    } else {
                        null
                    },
                    fps = fps?.takeIf { it > 1f && it < 1000f },
                    hasAudio = hasAudio
                )
                break
            }
            // An audio-only file has no video track, and still has to report that
            // it has sound - otherwise extracting audio from an .m4a says it has
            // none, which is the opposite of true.
            result ?: TrackInfo(0, 0, null, null, hasAudio)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** MediaFormat.KEY_ROTATION, spelled out because it is API 23 and this is minSdk-safe. */
    private const val KEY_ROTATION = "rotation-degrees"

    /**
     * One frame, scaled down, for sampling a color out of.
     *
     * Scaled because a 4K frame is ~35 MB and this is held in Compose state while a
     * panel is open; a few hundred pixels across is far more than enough to pick a
     * screen color from, and averaging over the downscale actually helps - it
     * smooths the sensor noise that would otherwise make two adjacent taps on the
     * same green give two different answers.
     */
    suspend fun frameAt(
        context: Context,
        uri: Uri,
        timeMs: Long,
        /**
         * **The frame at that moment, not the nearest keyframe.** The default is
         * the nearest keyframe, which is what a chip, a tile or a blurred
         * backdrop wants: it is a seek with no decoding after it, and a picture
         * a second out does not matter there.
         *
         * It matters very much where what is shown is then *acted on* at the
         * playhead. The Track picker draws the box you are about to track on
         * this frame and the tracker then starts from the playhead's own frame;
         * on a long-GOP file those were seconds and a whole shot apart, so you
         * aimed at one thing and tracked another. Seen on the phone on 8 October
         * (`docs/DEVICE_FINDINGS.md` §46): at 0:00 the picker matched the
         * preview, at 0:12.023 it showed a different shot entirely.
         */
        exact: Boolean = false
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = AvRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(
                    timeMs * 1000L,
                    if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    SAMPLE_WIDTH_PX,
                    SAMPLE_HEIGHT_PX
                )
            } catch (t: Throwable) {
                null
            } finally {
                retriever.release()
            }
        }

    /**
     * A frame for a list to show - a project's cover, a library row - from a
     * video, a rendered still, or a picture: the retriever reads none of the
     * PNGs a photo overlay is kept as, so those go through StillClips.
     */
    suspend fun cover(context: Context, uri: Uri, timeMs: Long): Bitmap? =
        if (StillClips.isStill(uri)) withContext(Dispatchers.IO) { StillClips.previewBitmap(context, uri, SAMPLE_WIDTH_PX) }
        else frameAt(context, uri, timeMs) ?: withContext(Dispatchers.IO) {
            // A photo picked as a file - a document URI to a JPEG - has no frames
            // either, and is read by the same helper the branch above uses.
            //
            // It used to solve its own sample size, and against whichever side
            // was *shorter*: the two tests were joined with &&, so the doubling
            // stopped the moment either side would fall under 480. A 12000x1200
            // panorama therefore came back 6000x600 - fourteen megabytes of
            // ARGB_8888 for a 56 dp row, and written to cache/thumbs at that
            // size - where solving on the long side, as every other loop in the
            // app does, gives 750x75.
            StillClips.previewBitmap(context, uri, SAMPLE_WIDTH_PX)
        }

    suspend fun extractFrames(context: Context, uri: Uri, count: Int, durationMs: Long): List<Bitmap?> =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0 || count <= 0) return@withContext emptyList()
            val retriever = AvRetriever()
            try {
                retriever.setDataSource(context, uri)
                val step = durationMs / count
                // One per slot, null where a frame would not decode: dropped, every
                // later frame slid a tile left and the strip stopped matching its time.
                (0 until count).map { i ->
                    val timeUs = (i * step + step / 2) * 1000
                    runCatching {
                        // Scaled decode: full-resolution frames for a 4K clip would be
                        // ~35 MB each and OOM the filmstrip on mid-range devices.
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMB_WIDTH_PX,
                            THUMB_HEIGHT_PX
                        )
                    }.getOrNull()
                }
            } catch (t: Throwable) {
                // A file gone or no longer readable: no tiles, as frameAt gives no
                // frame. Thrown, it crashed the trim screen's filmstrip.
                if (t is kotlinx.coroutines.CancellationException) throw t
                emptyList()
            } finally {
                retriever.release()
            }
        }

    private const val SAMPLE_WIDTH_PX = 480
    private const val SAMPLE_HEIGHT_PX = 480

    private const val THUMB_WIDTH_PX = 160
    private const val THUMB_HEIGHT_PX = 160
}
