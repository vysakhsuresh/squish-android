package com.squish.app.media

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
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
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.ColorGrade
import com.squish.app.media.effects.MaskEffect
import com.squish.app.media.effects.Looks
import com.squish.app.timeline.Clip
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
            // One sequence per added sound. A Composition mixes its sequences
            // together, so overlapping music, a voiceover and a second mic all
            // land in the same output without any of them being a special case.
            sequences.addAll(buildAudioSequences(state, headSourceIn, timelineDuration))

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

            continuation.invokeOnCancellation { runCatching { transformer.cancel() } }

            // start() validates the composition on the calling thread and throws
            // synchronously for a malformed one, which the listener never sees.
            // Without this the coroutine would hang forever on an invalid edit.
            runCatching { transformer.start(composition, outputFile.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(Result.failure(it)) }
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
            // Overlays carry their placement inside overlayEffects already.
            CompositionFactory.overlayEffects(clip, state.sourceWidth, state.sourceHeight)
        } else {
            // A base shot can be animated too - a push-in, a drift, a slow turn -
            // so its transform goes on first, in source space, ahead of rotation,
            // crop and the output resolution.
            val moved = clip.keyframes.isNotEmpty() || !clip.staticTransform.isIdentity
            val leading = buildList<Effect> {
                clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
                clip.mask?.let { add(MaskEffect(it)) }
                if (moved) add(ClipTransformEffect(clip.keyframes, clip.staticTransform))
            }
            if (leading.isEmpty()) buildVideoEffects(state) else leading + buildVideoEffects(state)
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
     * Every added sound, each as its own sequence.
     *
     * @param headSourceIn where in the source file the first video clip starts -
     *   the silent pad is borrowed from there.
     * @param timelineDuration total length of the assembled video track.
     */
    private fun buildAudioSequences(
        state: EditorUiState,
        headSourceIn: Long,
        timelineDuration: Long
    ): List<EditedMediaItemSequence> {
        if (state.audioOnly) return emptyList()
        return state.audioClips.mapNotNull { clip ->
            buildAudioSequence(state, clip, headSourceIn, timelineDuration)
        }
    }

    private fun buildAudioSequence(
        state: EditorUiState,
        clip: Clip,
        headSourceIn: Long,
        timelineDuration: Long
    ): EditedMediaItemSequence? {
        val audioUri = clip.uri ?: return null

        val requestedPad = clip.timelineStartMs.coerceAtLeast(0L)
        val padMs = if (state.sourceHasAudio) requestedPad else 0L

        val roomAfterPad = (timelineDuration - padMs).coerceAtLeast(0L)
        var sliceMs = clip.durationMs.coerceAtMost(roomAfterPad)
        if (clip.sourceDurationMs > 0) {
            sliceMs = sliceMs.coerceAtMost(clip.sourceDurationMs - clip.sourceInMs)
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
                    .setStartPositionMs(clip.sourceInMs)
                    .setEndPositionMs(clip.sourceInMs + sliceMs)
                    .build()
            )
            .build()
        items.add(
            EditedMediaItem.Builder(trackItem)
                .setRemoveVideo(true)
                .setEffects(Effects(buildAudioProcessors(1f, clip.volume), ImmutableList.of()))
                .build()
        )

        val sequence = EditedMediaItemSequence.Builder()
        items.forEach { sequence.addItem(it) }
        return sequence.build()
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

        // The chosen look and the manual sliders, folded into one set of moves and
        // built by the same code the preview uses - so the graded frame on screen
        // is the graded frame that gets written.
        effects.addAll(
            ColorGrade.effects(
                Looks.grade(
                    lookId = state.lookId,
                    intensity = state.lookIntensity,
                    brightness = state.brightness,
                    contrast = state.contrast,
                    saturation = state.saturation
                )
            )
        )

        if (state.textOverlays.isNotEmpty()) {
            // Widened at the declaration: OverlayEffect takes List<TextureOverlay>,
            // and Java generics are invariant, so a list of the subtype will not do.
            val overlays: List<TextureOverlay> = state.textOverlays.map { SquishTextOverlay(it) }
            effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
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
