@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.av

import android.media.Image
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.squish.app.media.YuvDataSpace
import com.squish.app.media.YuvImagePainter
import java.util.concurrent.ConcurrentHashMap

/**
 * Which formats Squish's FFmpeg decodes, and the parameters it opens each
 * with. The players (AvRenderers) and the export (AvTransformerCodecs) ask
 * here, and the phone's own decoders stay first at both: FFmpeg takes a
 * format only when no decoder on the phone does.
 */
object AvCodecs {

    /** The names our decoders go by in Media3; the codec adapter factory recognises them by it. */
    const val NAME_VIDEO = "squish.ffmpeg.video"
    const val NAME_AUDIO = "squish.ffmpeg.audio"

    private val decoderExists = ConcurrentHashMap<String, Boolean>()

    private fun hasDecoder(codec: String): Boolean =
        decoderExists.getOrPut(codec) { runCatching { AvNative.hasDecoder(codec) }.getOrDefault(false) }

    /**
     * The parameter blob for [format], or null when FFmpeg has no decoder for
     * it. Squish's own tracks (AvExtractor) carry FFmpeg's blob already; a
     * track Media3's readers found is translated (AvCodecMap).
     */
    fun paramsFor(format: Format): ByteArray? {
        if (!AvNative.loaded || format.drmInitData != null) return null
        val mime = format.sampleMimeType ?: return null
        if (mime == AvCodecMap.MIME_VIDEO || mime == AvCodecMap.MIME_AUDIO) return format.initializationData.firstOrNull()
        val codec = AvCodecMap.ffmpegName(mime, format.codecs) ?: return null
        if (!hasDecoder(codec)) return null
        val video = MimeTypes.isVideo(mime)
        return AvCodecMap.blob(
            video = video,
            codec = codec,
            extradata = AvCodecMap.extradata(codec, format.initializationData),
            width = format.width.coerceAtLeast(0),
            height = format.height.coerceAtLeast(0),
            sampleRate = format.sampleRate.coerceAtLeast(0),
            channels = format.channelCount.coerceAtLeast(0),
            bitRate = format.averageBitrate.coerceAtLeast(0).toLong()
        )
    }

    /**
     * Whether FFmpeg decodes [format] for this app. Pictures only from API 33:
     * a software-decoded frame reaches a player's or Transformer's surface at
     * its own size only through `ImageWriter.Builder.setWidthAndHeight`
     * (YuvImagePainter says what happened without it).
     */
    fun decodes(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (MimeTypes.isVideo(mime) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!MimeTypes.isVideo(mime) && !MimeTypes.isAudio(mime)) return false
        return paramsFor(format) != null
    }

    /** Whether [format] is one of Squish's own tracks, which only FFmpeg reads. */
    fun isOwn(format: Format): Boolean =
        format.sampleMimeType == AvCodecMap.MIME_VIDEO || format.sampleMimeType == AvCodecMap.MIME_AUDIO

    /** Our decoder as Media3 lists decoders, for [format]'s MIME type. */
    fun codecInfo(format: Format): MediaCodecInfo {
        val mime = format.sampleMimeType!!
        val video = MimeTypes.isVideo(mime)
        return MediaCodecInfo.newInstance(
            if (video) NAME_VIDEO else NAME_AUDIO, mime, mime,
            /* capabilities = */ null,
            /* hardwareAccelerated = */ false,
            /* softwareOnly = */ true,
            /* vendor = */ false,
            /* forceDisableAdaptive = */ true,
            /* forceSecure = */ false
        )
    }

    fun isOurs(info: MediaCodecInfo): Boolean = info.name == NAME_VIDEO || info.name == NAME_AUDIO

    /**
     * Input buffer room for [format]: what the reader promised, and never
     * under a floor that holds any packet the stream can plausibly send.
     */
    fun inputCapacity(format: Format): Int {
        val video = MimeTypes.isVideo(format.sampleMimeType)
        val promised = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else 0
        // Every byte of this is on the Java heap (a direct buffer on Android is
        // allocated there), times the inputs each decoder keeps, times the
        // players a preview runs at once - so a reader's own figure is
        // trusted, and a floor is only for the formats that give none (a
        // Matroska track): half a frame's pixels for a picture, 4 MB at 4K. A
        // sample larger still is skipped by MediaCodecRenderer, not fatal.
        if (promised > 0) return maxOf(promised, if (video) 256 * 1024 else 64 * 1024)
        return if (video) maxOf(2 shl 20, format.width.coerceAtLeast(0) * format.height.coerceAtLeast(0) / 2)
        else 256 * 1024
    }

    /**
     * Set as a Format's customData by the player's renderer (AvVideoRenderer)
     * for HDR10 that only FFmpeg decodes, whose colour it has relabelled SDR:
     * the adapter tone-maps it in the decoder (HdrRules says why).
     */
    object ToneMappedInDecoder

    /** The colour space a frame goes onto a surface in, from what the frame and its track say. */
    fun choiceFor(frame: AvDecoder.Frame, format: Format): YuvDataSpace.Choice {
        val transfer = when (frame.colorTrc.takeIf { it != 2 } ?: trcOf(format)) {
            18 -> YuvDataSpace.Transfer.HLG
            16 -> YuvDataSpace.Transfer.PQ
            else -> YuvDataSpace.Transfer.SDR
        }
        // FFmpeg's matrices to YuvDataSpace's words: 1 BT.709, 5 and 6 BT.601, 9 and 10 BT.2020.
        val space = when (frame.colorSpace) {
            1 -> 2
            5, 6 -> 1
            9, 10 -> 3
            else -> 0
        }
        val full = frame.colorRange == 2 ||
            (frame.colorRange == 0 && format.colorInfo?.colorRange == C.COLOR_RANGE_FULL)
        return YuvDataSpace.choose(space, frame.height, transfer, full)
    }

    private fun trcOf(format: Format): Int = when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_HLG -> 18
        C.COLOR_TRANSFER_ST2084 -> 16
        else -> 2
    }

    /**
     * Writes a decoded frame into [painter]'s surface, stamped [timestampNs].
     * [held] is a taken frame (AvDecoder.takeFrame) or 0 for the one last
     * received. The frame is converted to 8-bit 4:2:0 on the way if it is not
     * that already - 10-bit, 4:2:2, 4:4:4, RGB, palette - and its range is
     * kept so the DataSpace named for it stays true (squishav.c's to_i420).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun paint(
        painter: YuvImagePainter,
        decoder: AvDecoder,
        held: Long,
        frame: AvDecoder.Frame,
        format: Format,
        timestampNs: Long,
        /** The frame was tone-mapped in the decoder (decSetToneMap): limited-range BT.709 SDR, whatever the file was. */
        sdr: Boolean = false
    ) {
        val width = frame.width
        val height = frame.height
        val rgb = isRgbFrame(frame)
        // An RGB picture is written out limited range (squishav.c), so the
        // DataSpace says limited whatever the file did.
        val choice = if (sdr) YuvDataSpace.choose(2, height, YuvDataSpace.Transfer.SDR, false)
        else choiceFor(frame, format).let { if (rgb) it.copy(range = YuvDataSpace.Range.LIMITED) else it }
        painter.paintWith(width, height, choice, timestampNs) { image -> fill(image, decoder, held, width, height) }
    }

    private fun isRgbFrame(frame: AvDecoder.Frame): Boolean = frame.rgb

    private fun fill(image: Image, decoder: AvDecoder, held: Long, width: Int, height: Int) {
        val planes = image.planes
        val r = when (planes.size) {
            3 -> AvNative.decFrameToYuv(
                decoder.nativeHandle, held,
                planes[0].buffer, planes[0].rowStride,
                planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                planes[2].buffer, planes[2].rowStride, planes[2].pixelStride
            )
            1 -> AvNative.decFrameToRgbaBuffer(decoder.nativeHandle, held, planes[0].buffer, planes[0].rowStride, width, height)
            else -> throw IllegalStateException("the image writer gave ${planes.size} plane(s)")
        }
        if (r < 0) throw AvException("writing a frame", r)
    }

}
