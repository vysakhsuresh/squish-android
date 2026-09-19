package com.squish.app.media

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.Quality
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

/**
 * Wraps Media3 Transformer to turn one [EditorUiState] into an exported MP4.
 *
 * Confidence notes for whoever opens this in Android Studio first (this sandbox
 * could not reach Google's Maven repo to compile-check it - see README):
 *  - HIGH confidence: MediaItem clipping, EditedMediaItem.setRemoveAudio,
 *    Presentation (resolution + aspect crop), ScaleAndRotateTransformation,
 *    SpeedChangeEffect + SonicAudioProcessor, RgbAdjustment, Contrast,
 *    DefaultEncoderFactory bitrate override, EditedMediaItemSequence concatenation.
 *  - VERIFY: HslAdjustment.adjustSaturation's exact signature, OverlayEffect/
 *    TextOverlay anchor math in SquishTextOverlay, and Composition's multi-sequence
 *    audio-mixing behavior for the background-music track - all real, documented
 *    Media3 1.4.x APIs, but their exact shapes are worth a first-build sanity check.
 */
class VideoProcessor(private val context: Context) {

    suspend fun export(
        state: EditorUiState,
        sourceWidth: Int,
        sourceHeight: Int,
        outputFile: File
    ): Result<File> = suspendCancellableCoroutine { cont ->
        val videoEffects = buildVideoEffects(state, sourceWidth, sourceHeight)
        val audioProcessors = buildAudioProcessors(state)

        val clippedItem = MediaItem.Builder()
            .setUri(state.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.trimStartMs)
                    .setEndPositionMs(state.trimEndMs)
                    .build()
            )
            .build()

        val editedItem = EditedMediaItem.Builder(clippedItem)
            .setRemoveAudio(state.muted)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        val queueItems = state.clipQueue.map { uri -> EditedMediaItem.Builder(MediaItem.fromUri(uri)).build() }
        val sequenceBuilder = EditedMediaItemSequence.Builder(editedItem)
        queueItems.forEach { sequenceBuilder.addItem(it) }
        val videoSequence = sequenceBuilder.build()

        val composition = if (state.musicUri != null && !state.muted) {
            val musicItem = EditedMediaItem.Builder(MediaItem.fromUri(state.musicUri)).build()
            val musicSequence = EditedMediaItemSequence.Builder(musicItem)
                .setIsLooping(true)
                .build()
            Composition.Builder(ImmutableList.of(videoSequence, musicSequence)).build()
        } else {
            Composition.Builder(ImmutableList.of(videoSequence)).build()
        }

        val bitrate = if (state.fitToSize) {
            ExportPresets.bitrateForTargetSize(
                state.targetSizeMb * 1_000_000L,
                state.trimmedDurationMs,
                !state.muted
            )
        } else {
            ExportPresets.bitrateFor(state.quality)
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Result.success(outputFile))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(Result.failure(exportException))
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)
        cont.invokeOnCancellation { transformer.cancel() }
    }

    private fun buildVideoEffects(state: EditorUiState, sourceWidth: Int, sourceHeight: Int): List<Effect> {
        val effects = mutableListOf<Effect>()

        if (state.rotationDegrees != 0) {
            effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(state.rotationDegrees.toFloat()).build())
        }

        state.cropAspect.ratio?.let { ratio ->
            effects.add(Presentation.createForAspectRatio(ratio.toDouble(), Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }

        if (state.quality != Quality.Original && !state.fitToSize) {
            val res = ExportPresets.resolutionFor(state.quality, sourceWidth, sourceHeight)
            if (res.width > 0 && res.height > 0) {
                effects.add(Presentation.createForWidthAndHeight(res.width, res.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }

        if (state.speed != 1f) {
            effects.add(SpeedChangeEffect(state.speed))
        }

        if (state.brightness != 0f) {
            val scale = (1f + state.brightness).coerceIn(0f, 2f)
            effects.add(RgbAdjustment.Builder().setRedScale(scale).setGreenScale(scale).setBlueScale(scale).build())
        }

        if (state.contrast != 0f) {
            effects.add(Contrast(state.contrast))
        }

        if (state.saturation != 0f) {
            effects.add(HslAdjustment.Builder().adjustSaturation(state.saturation * 100).build())
        }

        if (state.textOverlays.isNotEmpty()) {
            val overlays = state.textOverlays.map { SquishTextOverlay(it) }
            effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
        }

        return effects
    }

    private fun buildAudioProcessors(state: EditorUiState): List<AudioProcessor> {
        if (state.speed == 1f) return emptyList()
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(state.speed)
        sonic.setPitch(state.speed)
        return listOf(sonic)
    }
}
