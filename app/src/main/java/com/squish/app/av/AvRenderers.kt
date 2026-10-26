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
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
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

    /**
     * [video] is the renderer's kind. Asked without it, FFmpeg "decodes" a
     * sound track for the video renderer too - which then claimed every sound
     * it was offered, was handed a WMA as a picture and failed reading a width
     * from its output format, and took the track away from the audio renderer.
     */
    fun capabilities(own: Int, format: Format, video: Boolean): Int {
        if (RendererCapabilities.getFormatSupport(own) == C.FORMAT_HANDLED) return own
        if (androidx.media3.common.MimeTypes.isVideo(format.sampleMimeType) != video) return own
        if (!AvCodecs.decodes(format)) return own
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )
    }

    fun decoders(context: Context, platform: List<MediaCodecInfo>, format: Format, video: Boolean): List<MediaCodecInfo> {
        if (androidx.media3.common.MimeTypes.isVideo(format.sampleMimeType) != video) return platform
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
        return AvSupport.capabilities(own, format, video = true)
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val platform = if (AvCodecs.isOwn(format)) emptyList() else super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return AvSupport.decoders(appContext, platform, format, video = true)
    }

    /**
     * Media3 leaves out, before a seek's target, samples it is told nothing
     * depends on - safe for the phone's decoders. FFmpeg's frame-threaded HEVC
     * was seen on the moto (24 October, a 10-bit x265 MKV) to lose references
     * after a jump ("Could not find ref with POC") and to stall once in six
     * jumps with this left on, none in six with it off; so FFmpeg is given
     * every sample and pays for decoding a few more frames on a seek.
     */
    override fun shouldDiscardDecoderInputBuffer(buffer: androidx.media3.decoder.DecoderInputBuffer): Boolean {
        val info = codecInfo
        if (info != null && AvCodecs.isOurs(info)) return false
        return super.shouldDiscardDecoderInputBuffer(buffer)
    }

    /**
     * The graph is built only for a player given video effects - the editor's
     * preview - and its output there is a TextureView, which composites as
     * SDR. Left alone, Media3 hands a PQ (HDR10) picture through as PQ on API
     * 33+, so the preview showed it washed out (EDGE_PLAN B4) while the
     * export, which tone-maps, was right; and the effect shaders assume SDR
     * values. Asking for OpenGL tone mapping makes the preview the file's
     * picture for HLG and PQ alike; an SDR input is unchanged by it.
     *
     * The class is restricted to Media3's library group, but this protected
     * method is the renderer's own hook for building it and there is no other
     * way in (no ExoPlayer setting reaches setRequestOpenGlToneMapping in
     * 1.11.1). Media3 is pinned; ControlChecks holds the override, so an
     * upgrade that changes the hook breaks the build, not the picture.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper =
        super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).apply {
            setRequestOpenGlToneMapping(true)
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
        return AvSupport.capabilities(own, format, video = false)
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val platform = if (AvCodecs.isOwn(format)) emptyList() else super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return AvSupport.decoders(appContext, platform, format, video = false)
    }
}
