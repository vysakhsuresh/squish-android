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

    suspend fun export(state: EditorUiState, outputFile: File): Result<File> =
        suspendCancellableCoroutine { continuation ->
            // The video track as the timeline holds it. Every clip carries its own
            // source window, so a split is just two clips over the same file and
            // nothing is re-encoded twice.
            val track = state.videoClips
            val videoSequences: List<EditedMediaItemSequence> = when {
                track.isEmpty() -> CompositionFactory.buildCutsOnly(
                    listOf(editedVideo(state, state.sourceUri, state.trimStartMs, state.trimEndMs))
                )
                // Only an edit that actually uses transitions or layers pays the
                // cost - and the risk - of the compositing path.
                CompositionFactory.needsCompositing(state) ->
                    CompositionFactory.buildComposited(state) { clip -> editedClip(state, clip) }
                else -> CompositionFactory.buildCutsOnly(track.map { editedClip(state, it) })
            }

            val headSourceIn = track.firstOrNull()?.sourceInMs ?: state.trimStartMs
            val timelineDuration = state.trimmedDurationMs

            val sequences = videoSequences.toMutableList()
            buildAudioTrackSequence(state, headSourceIn, timelineDuration)?.let { sequences.add(it) }

            val composition = Composition.Builder(ImmutableList.copyOf(sequences)).build()

            val bitrate = if (state.fitToSize) {
                ExportPresets.bitrateForTargetSize(
                    state.targetSizeMb * 1_000_000L,
                    state.trimmedDurationMs,
                    state.hasAnyAudio
                )
            } else {
                ExportPresets.bitrateFor(state.quality)
            }

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()

            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .apply { if (!state.audioOnly) setVideoMimeType(MimeTypes.VIDEO_H264) }
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

    /**
     * The separate audio track, positioned on the output timeline.
     *
     * Media3 sequences always begin at zero, so to start a music cue partway in we
     * need leading silence. Rather than encoding a silent file, the pad is a slice
     * of the source video's own audio played at zero gain: exactly the right length,
     * guaranteed decodable, and no extra encoder in the path. If the source has no
     * audio track to borrow, the cue starts with the clip instead.
     */
    /** One timeline clip, with the look applied and overlay geometry if it floats. */
    private fun editedClip(state: EditorUiState, clip: com.squish.app.timeline.Clip): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(clip.uri ?: state.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceInMs)
                    .setEndPositionMs(clip.sourceOutMs.coerceAtLeast(clip.sourceInMs))
                    .build()
            )
            .build()

        val effects = if (clip.isOverlay) {
            CompositionFactory.overlayEffects(clip, state.sourceWidth, state.sourceHeight)
        } else {
            buildVideoEffects(state)
        }

        return EditedMediaItem.Builder(item)
            .setRemoveAudio(clip.isOverlay || (state.muteOriginal && !state.audioOnly))
            .setRemoveVideo(state.audioOnly)
            .setEffects(
                Effects(
                    if (clip.isOverlay) ImmutableList.of() else buildAudioProcessors(state.speed, state.originalVolume),
                    ImmutableList.copyOf(effects)
                )
            )
            .build()
    }

    private fun editedVideo(
        state: EditorUiState,
        uri: android.net.Uri?,
        startMs: Long,
        endMs: Long
    ): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs.coerceAtLeast(startMs))
                    .build()
            )
            .build()

        return EditedMediaItem.Builder(item)
            .setRemoveAudio(state.muteOriginal && !state.audioOnly)
            .setRemoveVideo(state.audioOnly)
            .setEffects(
                Effects(
                    buildAudioProcessors(state.speed, state.originalVolume),
                    if (state.audioOnly) ImmutableList.of() else buildVideoEffects(state)
                )
            )
            .build()
    }

    /**
     * @param headSourceIn where in the source file the first video clip starts -
     *   the silent pad is borrowed from there.
     * @param timelineDuration total length of the assembled video track.
     */
    private fun buildAudioTrackSequence(
        state: EditorUiState,
        headSourceIn: Long,
        timelineDuration: Long
    ): EditedMediaItemSequence? {
        val audioUri = state.audioTrackUri ?: return null
        if (state.audioOnly) return null

        val requestedPad = state.audioPlacementMs.coerceAtLeast(0L)
        val padMs = if (state.sourceHasAudio) requestedPad else 0L

        val roomAfterPad = (timelineDuration - padMs).coerceAtLeast(0L)
        var sliceMs = state.audioSliceDurationMs.coerceAtMost(roomAfterPad)
        if (state.audioTrackDurationMs > 0) {
            sliceMs = sliceMs.coerceAtMost(state.audioTrackDurationMs - state.audioTrimStartMs)
        }
        if (sliceMs <= 0) return null

        val items = mutableListOf<EditedMediaItem>()

        if (padMs > 0) {
            val padItem = MediaItem.Builder()
                .setUri(state.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(headSourceIn)
                        .setEndPositionMs(headSourceIn + padMs)
                        .build()
                )
                .build()
            items.add(
                EditedMediaItem.Builder(padItem)
                    .setRemoveVideo(true)
                    .setEffects(Effects(silentProcessors(), ImmutableList.of()))
                    .build()
            )
        }

        val trackItem = MediaItem.Builder()
            .setUri(audioUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.audioTrimStartMs)
                    .setEndPositionMs(state.audioTrimStartMs + sliceMs)
                    .build()
            )
            .build()
        items.add(
            EditedMediaItem.Builder(trackItem)
                .setRemoveVideo(true)
                .setEffects(Effects(buildAudioProcessors(1f, state.audioVolume), ImmutableList.of()))
                .build()
        )

        return EditedMediaItemSequence(ImmutableList.copyOf(items))
    }

    private fun silentProcessors(): ImmutableList<AudioProcessor> {
        val silence = AudioMixing.gain(0f)
        return if (silence == null) ImmutableList.of() else ImmutableList.of(silence)
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
            effects.add(OverlayEffect(ImmutableList.copyOf(state.textOverlays.map { SquishTextOverlay(it) })))
        }

        return ImmutableList.copyOf(effects)
    }

    private fun buildAudioProcessors(speed: Float, volume: Float): ImmutableList<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (speed != 1f) {
            // Tempo only, pitch preserved.
            processors.add(SonicAudioProcessor().apply { setSpeed(speed) })
        }
        AudioMixing.gain(volume)?.let { processors.add(it) }
        return ImmutableList.copyOf(processors)
    }
}
