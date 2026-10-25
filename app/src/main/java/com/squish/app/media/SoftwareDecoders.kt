@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.metrics.LogSessionId
import android.os.Build
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import com.google.common.util.concurrent.MoreExecutors
import com.squish.app.av.AvCodecs
import com.squish.app.av.AvExtractorsFactory
import com.squish.app.av.AvNative
import com.squish.app.av.AvRenderersFactory
import com.squish.app.av.AvTransformerCodecs
import java.util.concurrent.Executors

/**
 * Squish's own FFmpeg (com.squish.app.av, libsquishav.so - every demuxer and
 * every decoder FFmpeg has, with dav1d, libjxl and LibRaw), wired into every
 * place the app reads media. GPL-3.0; see THIRD_PARTY_NOTICES.md.
 *
 * A professional editor was handed a phone's own footage and some of it
 * "gave errors"; the answer then was nextlib's cut-down FFmpeg and a
 * conversion of each such file on import. The answer now is that the app
 * reads them itself, where they are, with no copy:
 *
 * - **Every player** ([renderers], [mediaSourceFactory]): Media3's own
 *   readers and the phone's own decoders first; FFmpeg's demuxer for a file
 *   Media3 does not read whole (AvRoute) and FFmpeg's decoders for a format
 *   no decoder on the phone takes, both behind MediaCodec's renderers so
 *   every effect and every audio processor reaches them (AvCodecAdapter).
 * - **Every Transformer** ([assetLoaderFactory]): the proxy, the stills, the
 *   export, the same readers and a decoder factory that hands MediaCodec
 *   everything it takes and FFmpeg the rest (AvTransformerCodecs).
 * - **The gate** (MediaCompat): a file is decodable when either takes it.
 * - **The frame readers** (AvRetriever and its kin): the filmstrip, the
 *   waveform, Freeze, Reverse, Stabilize and the rest fall back to FFmpeg
 *   where the phone's MediaMetadataRetriever and MediaCodec give up.
 */
object SoftwareDecoders {

    /** Whether the native library loaded on this phone; false on an ABI it was not built for. */
    val available: Boolean
        get() = AvNative.loaded

    /** Whether Squish's FFmpeg decodes [format] for this app (AvCodecs.decodes says what that means). */
    fun decodes(format: Format): Boolean = AvCodecs.decodes(format)

    /** A player's renderers: the phone's decoders first, FFmpeg's behind them (AvRenderersFactory). */
    fun renderers(context: Context): RenderersFactory = AvRenderersFactory(context)

    /** GlUtil.MAX_BITMAP_DECODING_SIZE in Media3 1.11.1, read off the jar. */
    private const val MAX_BITMAP_SIDE = 4096

    /** A player's media sources: Media3's readers, or FFmpeg's for a file they do not read whole. */
    fun mediaSourceFactory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(context, AvExtractorsFactory(context, seekableExtractors()))

    /**
     * Media3's readers with constant-bitrate seeking on the two formats that
     * have no index: an ADTS .aac (a voice recorder's) and an .amr (a call
     * recorder's). Without it they read as unseekable, every seek went to the
     * start, and the preview's sound sync - seeing seconds of drift - put a
     * sound played from its middle back to its start every second and a half.
     * The export always had the flags.
     */
    private fun seekableExtractors() = DefaultExtractorsFactory()
        .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

    /** Transformer's decoders: the platform's where they take the format, FFmpeg's where only it does. */
    fun decoderFactory(context: Context): Codec.DecoderFactory = FallbackDecoderFactory(context)

    /**
     * What every Transformer in the app is built with (`setAssetLoaderFactory`):
     * Media3's default loader - images and all - over the readers above and
     * [decoderFactory]. The extractor flags and the clipping in the media
     * period are the ones Transformer sets for its own default
     * (ExoPlayerAssetLoader.createMediaSourceFactory in 1.11.1), and the
     * bitmap loader the one DefaultAssetLoaderFactory makes for itself.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.ExperimentalApi::class])
    fun assetLoaderFactory(context: Context): AssetLoader.Factory {
        val extractors = seekableExtractors()
        val sources = DefaultMediaSourceFactory(context, AvExtractorsFactory(context, extractors))
            .setEnableClippingInMediaPeriod(true)
        val options = BitmapFactory.Options().apply { inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
        val bitmaps = DataSourceBitmapLoader.Builder(context)
            .setExecutorService(MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()))
            .setBitmapFactoryOptions(options)
            // GlUtil.MAX_BITMAP_DECODING_SIZE's value: the constant is library-private.
            .setMaximumOutputDimension(MAX_BITMAP_SIDE)
            .build()
        return DefaultAssetLoaderFactory(context, decoderFactory(context), Clock.DEFAULT, sources, bitmaps)
    }

    private class FallbackDecoderFactory(private val context: Context) : Codec.DecoderFactory {
        // With fallback on, a decoder that will not start is followed by the
        // next one that takes the format, the phone's software decoder among
        // them. Off - Media3's default - the first refusal failed the export:
        // seen on the moto on 25 October, two 1080p H.264 shots with a join,
        // the hardware decoder's second instance refusing its format ("failed
        // to set format on port:IN") while the software one was never asked.
        private val platform = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()

        private fun software(format: Format): Boolean =
            AvCodecs.isOwn(format) || (!MediaCompat.platformDecodes(context, format) && decodes(format))

        override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec =
            if (software(format)) AvTransformerCodecs.audio(format)
            else platform.createForAudioDecoding(format, logSessionId)

        override fun createForVideoDecoding(
            format: Format,
            outputSurface: Surface,
            requestSdrToneMapping: Boolean,
            logSessionId: LogSessionId?
        ): Codec =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && software(format)) {
                AvTransformerCodecs.video(format, outputSurface)
            } else {
                try {
                    platform.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
                } catch (e: androidx.media3.transformer.ExportException) {
                    // Every platform decoder refused to start: FFmpeg's, where
                    // it can paint the surface (Android 13 on) and reads the
                    // format, rather than the export failing. Not for a tone
                    // map, which only the platform's decoders do.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !requestSdrToneMapping && decodes(format)) {
                        android.util.Log.w("SquishExport", "no platform decoder would start for ${format.sampleMimeType}; FFmpeg's instead", e)
                        AvTransformerCodecs.video(format, outputSurface)
                    } else throw e
                }
            }
    }
}
