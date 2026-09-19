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
import kotlin.coroutines.resume

class VideoProcessor(private val context: Context) {

    /**
     * Resolved in/out points after sync correction.
     *
     * Offset convention (shared with the preview player and AudioSyncAnalyzer):
     * at video time t, the aligned external sample lives at (t + offsetMs). So the
     * external track is read from [audioStartMs], which is the video in-point shifted
     * by the offset. When that would land before the start of the audio file, the
     * video in-point moves forward by the shortfall instead - which keeps the two in
     * sync rather than silently dropping the offset.
     */
    class Window(
        val videoStartMs: Long,
        val videoEndMs: Long,
        val audioStartMs: Long,
        val audioEndMs: Long
    )

    companion object {
        fun resolveWindow(state: EditorUiState): Window {
            val headTrim = state.syncHeadTrimMs
            val videoStart = state.trimStartMs + headTrim
            val videoEnd = state.trimEndMs.coerceAtLeast(videoStart)

            val audioStart = (state.trimStartMs + state.audioOffsetMs + headTrim).coerceAtLeast(0L)
            var audioEnd = audioStart + (videoEnd - videoStart)
            if (state.audioTrackDurationMs > 0) {
                audioEnd = audioEnd.coerceAtMost(state.audioTrackDurationMs)
            }
            return Window(videoStart, videoEnd, audioStart, audioEnd)
        }
    }

    suspend fun export(
        state: EditorUiState,
        outputFile: File
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        val window = resolveWindow(state)

        val videoItem = MediaItem.Builder()
            .setUri(state.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(window.videoStartMs)
                    .setEndPositionMs(window.videoEndMs)
                    .build()
            )
            .build()

        val editedVideo = EditedMediaItem.Builder(videoItem)
            .setRemoveAudio(state.muteOriginal)
            .setEffects(
                Effects(
                    buildAudioProcessors(state.speed, state.originalVolume),
                    buildVideoEffects(state)
                )
            )
            .build()

        val videoItems = mutableListOf(editedVideo)
        state.clipQueue.forEach { uri ->
            videoItems.add(EditedMediaItem.Builder(MediaItem.fromUri(uri)).build())
        }
        val videoSequence = EditedMediaItemSequence(ImmutableList.copyOf(videoItems))

        val sequences = mutableListOf(videoSequence)
        val audioUri = state.audioTrackUri
        if (audioUri != null && window.audioEndMs > window.audioStartMs) {
            val audioItem = MediaItem.Builder()
                .setUri(audioUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(window.audioStartMs)
                        .setEndPositionMs(window.audioEndMs)
                        .build()
                )
                .build()

            val editedAudio = EditedMediaItem.Builder(audioItem)
                .setRemoveVideo(true)
                .setEffects(Effects(buildAudioProcessors(state.speed, state.audioVolume), ImmutableList.of()))
                .build()

            sequences.add(EditedMediaItemSequence(ImmutableList.of(editedAudio)))
        }

        val composition = Composition.Builder(ImmutableList.copyOf(sequences)).build()

        val bitrate = if (state.fitToSize) {
            ExportPresets.bitrateForTargetSize(
                state.targetSizeMb * 1_000_000L,
                state.trimmedDurationMs,
                !state.muteOriginal || state.hasSeparateAudio
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
                    if (continuation.isActive) continuation.resume(Result.success(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) continuation.resume(Result.failure(exportException))
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)
        continuation.invokeOnCancellation { transformer.cancel() }
    }

    private fun buildVideoEffects(state: EditorUiState): ImmutableList<Effect> {
        val effects = mutableListOf<Effect>()

        if (state.rotationDegrees != 0) {
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(state.rotationDegrees.toFloat())
                    .build()
            )
        }

        state.cropAspect.ratio?.let { ratio ->
            effects.add(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }

        if (state.quality != Quality.Original && !state.fitToSize) {
            val resolution = ExportPresets.resolutionFor(state.quality, state.sourceWidth, state.sourceHeight)
            if (resolution.width > 0 && resolution.height > 0) {
                effects.add(
                    Presentation.createForWidthAndHeight(
                        resolution.width,
                        resolution.height,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }
        }

        if (state.speed != 1f) effects.add(SpeedChangeEffect(state.speed))

        if (state.brightness != 0f) {
            val scale = (1f + state.brightness).coerceIn(0f, 2f)
            effects.add(RgbAdjustment.Builder().setRedScale(scale).setGreenScale(scale).setBlueScale(scale).build())
        }

        if (state.contrast != 0f) effects.add(Contrast(state.contrast))

        if (state.saturation != 0f) {
            effects.add(HslAdjustment.Builder().adjustSaturation(state.saturation * 100f).build())
        }

        if (state.textOverlays.isNotEmpty()) {
            val overlays = state.textOverlays.map { SquishTextOverlay(it) }
            effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
        }

        return ImmutableList.copyOf(effects)
    }

    private fun buildAudioProcessors(speed: Float, volume: Float): ImmutableList<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (speed != 1f) {
            // Tempo only, pitch preserved. Driving pitch from the same value as well
            // (as this used to) stacked a chipmunk shift on top of the speed change.
            processors.add(SonicAudioProcessor().apply { setSpeed(speed) })
        }
        AudioMixing.gain(volume)?.let { processors.add(it) }
        return ImmutableList.copyOf(processors)
    }
}
