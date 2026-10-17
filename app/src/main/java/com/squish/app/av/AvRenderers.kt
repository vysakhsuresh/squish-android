@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.av

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Every player's renderers: Media3's MediaCodec renderers, with Squish's
 * FFmpeg standing behind the phone's decoders in each (AvCodecAdapter says
 * why behind MediaCodec rather than beside it).
 *
 * The order is the phone's first. A format some decoder on the phone takes
 * goes to that decoder, with FFmpeg after it only as the fallback should it
 * fail to start; a format none takes goes straight to FFmpeg; and Squish's
 * own tracks (AvExtractor) only ever to FFmpeg. So every file that played
 * before plays the same way, through the same decoder.
 */
open class AvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = AvCodecAdapter.Factory(super.getCodecAdapterFactory())

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val at = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
        // The first renderer added is Media3's MediaCodecVideoRenderer; ours
        // takes its place with the same settings DefaultRenderersFactory gives it.
        if (at < out.size && out[at] is MediaCodecVideoRenderer) {
            out[at] = AvVideoRenderer(
                context,
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            )
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val at = out.size
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        if (at < out.size && out[at] is MediaCodecAudioRenderer) {
            out[at] = AvAudioRenderer(
                context, getCodecAdapterFactory(), mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, audioSink
            )
        }
    }
}

/** What both renderers do: the phone's decoders, then FFmpeg. */
private object AvSupport {

    fun capabilities(own: Int, format: Format): Int {
        if (RendererCapabilities.getFormatSupport(own) == C.FORMAT_HANDLED) return own
        if (!AvCodecs.decodes(format)) return own
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )
    }

    fun decoders(context: Context, platform: List<MediaCodecInfo>, format: Format): List<MediaCodecInfo> {
        if (AvCodecs.isOwn(format)) return listOf(AvCodecs.codecInfo(format))
        if (!AvCodecs.decodes(format)) return platform
        val takes = platform.any { runCatching { it.isFormatSupported(context, format) }.getOrDefault(false) }
        // A phone decoder that takes the format stays first, FFmpeg behind it
        // in case it will not start; one that does not take it is passed
        // over, since it would accept the stream and then fail on its frames.
        return if (takes) platform + AvCodecs.codecInfo(format) else listOf(AvCodecs.codecInfo(format))
    }
}

private class AvVideoRenderer(private val appContext: Context, builder: MediaCodecVideoRenderer.Builder) :
    MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val own = if (AvCodecs.isOwn(format)) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        else super.supportsFormat(mediaCodecSelector, format)
        return AvSupport.capabilities(own, format)
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val platform = if (AvCodecs.isOwn(format)) emptyList() else super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return AvSupport.decoders(appContext, platform, format)
    }
}

private class AvAudioRenderer(
    private val appContext: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : MediaCodecAudioRenderer(
    appContext, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink
) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val own = if (AvCodecs.isOwn(format)) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        else super.supportsFormat(mediaCodecSelector, format)
        return AvSupport.capabilities(own, format)
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val platform = if (AvCodecs.isOwn(format)) emptyList() else super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return AvSupport.decoders(appContext, platform, format)
    }
}
