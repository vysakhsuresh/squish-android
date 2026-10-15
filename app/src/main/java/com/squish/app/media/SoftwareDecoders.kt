@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Build
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.SquishFfmpegCodecs

/**
 * The FFmpeg software decoders the app carries, for files no decoder on the
 * phone will take.
 *
 * A professional editor was handed a phone's own footage and some of it
 * "gave errors": a file opens, its length and tracks read, and then no
 * decoder on the device takes its frames. On this project's own test phone
 * that is every HEVC 10-bit file ever made - it has no Main 10 decoder at
 * all - and on any phone it is DTS and TrueHD sound off a film, MPEG-2 and
 * DivX off a disc, AV1 on a phone from before it. The platform decodes
 * what the platform decodes; for the rest the app brings its own, through
 * nextlib's build of FFmpeg for Media3 1.11.1
 * (io.github.anilbeesetti:nextlib-media3ext, GPL-3.0 - see
 * THIRD_PARTY_NOTICES.md).
 *
 * Three doors, and the platform's decoders stay first at each:
 *
 * - **Every player** ([renderers]): nextlib's renderers behind Media3's own,
 *   so a format the phone decodes still goes to the phone, and only a
 *   format it refuses falls to FFmpeg. That is the preview, the clip
 *   previews and the music audition playing anything at once.
 * - **Transformer** ([assetLoaderFactory]): the proxy, the stills, the
 *   export and the import conversion read through a decoder factory that
 *   hands MediaCodec everything it takes and the FFmpeg decoders the rest
 *   (SquishFfmpegCodecs, which says what that path cannot do).
 * - **The gate** (MediaCompat): a file is decodable when either takes it,
 *   and one only FFmpeg takes is marked so, which is what has it converted
 *   on import (ConversionRules says why converted rather than decoded in
 *   place).
 */
object SoftwareDecoders {

    /** Whether the native libraries loaded on this phone; false on an ABI they were not built for. */
    val available: Boolean
        get() = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

    /**
     * Whether FFmpeg decodes [format] *for this app*: by its MIME type, never
     * anything encrypted, and for video only from API 33 - the one API that
     * can put a software-decoded frame on Transformer's surface at the
     * picture's size is `ImageWriter.Builder.setWidthAndHeight`
     * (YuvImagePainter says what happened without it). Below 33 the players
     * still fall back to the FFmpeg renderers on their own; this is the
     * answer for the gate and the export, which must agree.
     */
    fun decodes(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (format.drmInitData != null) return false
        if (MimeTypes.isVideo(mime) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return available && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
    }

    /**
     * A player's renderers: Media3's own first, FFmpeg's after them
     * (EXTENSION_RENDERER_MODE_ON), and a MediaCodec that fails to start
     * falling to the next decoder of its kind before giving up.
     */
    fun renderers(context: Context): RenderersFactory =
        NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

    /** Transformer's decoders: the platform's where they take the format, FFmpeg's where only it does. */
    fun decoderFactory(context: Context): Codec.DecoderFactory = FallbackDecoderFactory(context)

    /**
     * What every Transformer in the app is built with (`setAssetLoaderFactory`):
     * Media3's default loader - images and all - over [decoderFactory].
     */
    fun assetLoaderFactory(context: Context): AssetLoader.Factory =
        DefaultAssetLoaderFactory(context, decoderFactory(context), Clock.DEFAULT, /* logSessionId = */ null)

    private class FallbackDecoderFactory(private val context: Context) : Codec.DecoderFactory {
        private val platform = DefaultDecoderFactory(context)

        private fun software(format: Format): Boolean =
            !MediaCompat.platformDecodes(context, format) && decodes(format)

        override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec =
            if (software(format)) SquishFfmpegCodecs.audio(format)
            else platform.createForAudioDecoding(format, logSessionId)

        override fun createForVideoDecoding(
            format: Format,
            outputSurface: Surface,
            requestSdrToneMapping: Boolean,
            logSessionId: LogSessionId?
        ): Codec =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && software(format)) {
                SquishFfmpegCodecs.video(format, outputSurface)
            } else {
                platform.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
            }
    }
}
