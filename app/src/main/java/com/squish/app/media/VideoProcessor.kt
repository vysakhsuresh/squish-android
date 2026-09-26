@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
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
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.squish.app.editor.CropAspect
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.OutputSize
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.ColorGrade
import com.squish.app.media.effects.FxEffect
import com.squish.app.media.effects.MaskEffect
import com.squish.app.media.effects.Looks
import com.squish.app.timeline.Clip
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** How far along an export is, as the encoder itself reports it. */
data class ExportProgress(
    /** 0f..1f, or null while the encoder cannot yet say. */
    val fraction: Float? = null,
    val elapsedMs: Long = 0,
    /**
     * Projected from the rate so far, once there is enough of it to project from.
     * Null early on rather than a wild number that halves every second.
     */
    val remainingMs: Long? = null
)

class VideoProcessor(private val context: Context) {

    /**
     * Runs an export, reporting progress until it finishes.
     *
     * Progress comes from the Transformer rather than from a timer: an encode is
     * not linear in wall time, so a fake bar is a lie that gets found out on every
     * long clip. [onProgress] is called on the caller's thread.
     *
     * Media3 requires getProgress on the thread that built the Transformer, which
     * is this one, so the poll runs in the same coroutine rather than off it.
     */
    suspend fun export(
        state: EditorUiState,
        outputFile: File,
        onProgress: (ExportProgress) -> Unit = {}
    ): Result<File> = coroutineScope {
        val started = SystemClock.elapsedRealtime()
        val active = java.util.concurrent.atomic.AtomicReference<Transformer?>(null)

        val poll = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val transformer = active.get()
                if (transformer != null) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val reported = runCatching { transformer.getProgress(holder) }
                        .getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                    val fraction = if (reported == Transformer.PROGRESS_STATE_AVAILABLE) {
                        (holder.progress / 100f).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    // Only project once a twentieth of the work is done. Before
                    // that the rate is mostly startup cost and the estimate swings
                    // by minutes between ticks.
                    val remaining = fraction
                        ?.takeIf { it >= 0.05f }
                        ?.let { ((elapsed / it) - elapsed).toLong().coerceAtLeast(0L) }
                    onProgress(ExportProgress(fraction, elapsed, remaining))
                }
                delay(PROGRESS_POLL)
            }
        }

        try {
            runExport(state, outputFile) { active.set(it) }
        } finally {
            poll.cancel()
        }
    }

    private suspend fun runExport(
        state: EditorUiState,
        outputFile: File,
        onTransformer: (Transformer) -> Unit
    ): Result<File> =
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

            val composition = Composition.Builder(ImmutableList.copyOf(sequences))
                .setEffects(compositionEffects(state))
                // Silence is generated for whatever stretch has no sound in it.
                // Without this, a run of clips where only some carry an audio
                // track is an inconsistent composition, and Media3 gives up on it
                // with a code that carries no explanation - which is exactly what
                // "Export stopped unexpectedly" was.
                .experimentalSetForceAudioTrack(needsForcedAudio(state))
                .build()

            val bitrate = state.exportVideoBitrate

            // A cut and nothing else keeps the original frames. Only the stretch
            // from the in point to the next keyframe is re-encoded; the rest is
            // copied as it was recorded. Re-encoding the whole thing lost quality
            // for nothing and, on a quiet screen recording, came out heavier than
            // the file it was cut from. Media3 falls back to a full encode by
            // itself when a file cannot be cut this way.
            val trimOnly = isPlainTrim(state)

            // Left at its defaults for a cut. Media3 reads any requested encoder
            // setting - a bitrate included - as "this must be re-encoded", and
            // quietly re-encodes the whole file instead of copying it.
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .apply {
                    if (!trimOnly) {
                        setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    }
                }
                .build()

            val transformer = Transformer.Builder(context)
                .apply {
                    if (trimOnly) {
                        // No codec is asked for, audio or video: a copied stream
                        // stays in its own, and naming one - even the one it is
                        // already in - counts as a transcode and cancels the copy.
                        experimentalSetTrimOptimizationEnabled(true)
                    } else {
                        setAudioMimeType(MimeTypes.AUDIO_AAC)
                        if (!state.audioOnly) setVideoMimeType(MimeTypes.VIDEO_H264)
                    }
                }
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        android.util.Log.i(
                            "SquishExport",
                            "done trimOnly=$trimOnly optimization=${exportResult.optimizationResult} " +
                                "video=${exportResult.videoEncoderName} bitrate=${exportResult.averageVideoBitrate}"
                        )
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
            onTransformer(transformer)

            // start() validates the composition on the calling thread and throws
            // synchronously for a malformed one, which the listener never sees.
            // Without this the coroutine would hang forever on an invalid edit.
            runCatching { transformer.start(composition, outputFile.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(Result.failure(it)) }
        }

    /**
     * Effects that apply to the whole composition rather than to one clip.
     *
     * This is where a merge gets its frame size. Several files joined end to end
     * are almost never the same shape - a gallery holds portrait and landscape
     * side by side - and nothing in a cuts-only sequence reconciles them unless
     * something says what the output is. Per-clip Presentation was not enough:
     * it was skipped entirely at Original quality, and skipped again whenever the
     * source dimensions were unknown, which for a merge they always were.
     *
     * A single export needs none of this. Its own frame size is the answer, and
     * an extra pass over every frame to restate it is a waste.
     */
    private fun compositionEffects(state: EditorUiState): Effects {
        if (!isMultiSource(state) || state.audioOnly) return Effects.EMPTY
        val size = outputSize(state) ?: return Effects.EMPTY

        // Widened at the declaration, for the same reason the caption overlays are:
        // Effects takes List<Effect>, Java generics are invariant, and a list
        // inferred as ImmutableList<Presentation> will not do.
        val framing: List<Effect> = listOf(
            // Fit rather than crop: a landscape clip in a portrait merge is
            // letterboxed, not cut in half. Losing half of someone's footage to an
            // automatic decision would be the worst surprise of the two.
            Presentation.createForWidthAndHeight(
                size.width,
                size.height,
                Presentation.LAYOUT_SCALE_TO_FIT
            )
        )
        return Effects(ImmutableList.of(), ImmutableList.copyOf(framing))
    }

    /**
     * The frame the whole composition is drawn into: the leading clip's shape, at
     * the chosen quality. Null when nothing measured it, in which case forcing a
     * guessed size would be worse than letting Media3 decide.
     */
    private fun outputSize(state: EditorUiState): ExportPresets.Resolution? {
        val quarterTurned = state.rotationDegrees % 180 != 0
        val width = if (quarterTurned) state.sourceHeight else state.sourceWidth
        val height = if (quarterTurned) state.sourceWidth else state.sourceHeight
        if (width <= 0 || height <= 0) return null

        val resolution = ExportPresets.resolutionFor(state.outputP, width, height)
        if (resolution.width <= 0 || resolution.height <= 0) return null
        // Encoders want even dimensions, and a scaled odd number is how you get a
        // configuration failure on one device and not another.
        return ExportPresets.Resolution(
            width = (resolution.width / 2) * 2,
            height = (resolution.height / 2) * 2
        )
    }

    /** More than one file in the video track: the case that has to be reconciled. */
    private fun isMultiSource(state: EditorUiState): Boolean =
        state.videoClips.mapNotNull { it.uri }.distinct().size > 1

    /**
     * Whether the output must carry an audio track whatever the inputs do.
     *
     * Any time the sources might disagree about having sound: several files, or a
     * separate cue mixed over the top. Forcing it on a single silent clip would
     * add a pointless track, so that case is left alone.
     */
    /**
     * One file, cut, with nothing changed about its picture or sound - which is
     * Snip. The editor's clips are left out even when there is only one: a clip
     * carries its own speed, motion and masks, none of which a copied stream keeps.
     */
    private fun isPlainTrim(state: EditorUiState): Boolean =
        state.videoClips.isEmpty() &&
            !state.audioOnly &&
            !state.muteOriginal &&
            state.audioClips.isEmpty() &&
            !state.fitToSize &&
            state.outputP == OutputSize.ORIGINAL &&
            gainOnly(state.originalVolume).isEmpty() &&
            buildVideoEffects(state).isEmpty()

    private fun needsForcedAudio(state: EditorUiState): Boolean =
        !state.audioOnly && (isMultiSource(state) || state.audioClips.isNotEmpty())

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
    private fun editedClip(state: EditorUiState, clip: Clip): EditedMediaItem {
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
            val moved = clip.keyframes.isNotEmpty() || clip.stabilizer.isNotEmpty() ||
                !clip.staticTransform.isIdentity
            val leading = buildList<Effect> {
                clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
                clip.mask?.let { add(MaskEffect(it, clip.sourceInMs)) }
                if (moved) add(ClipTransformEffect(clip.keyframes, clip.staticTransform, clip.stabilizer, clip.sourceInMs))
            }
            val timed = leading + speedEffects(clip)
            if (timed.isEmpty()) buildVideoEffects(state) else timed + buildVideoEffects(state)
        }

        return EditedMediaItem.Builder(item)
            .setRemoveAudio(clip.isOverlay || (state.muteOriginal && !state.audioOnly))
            .setRemoveVideo(state.audioOnly)
            .setEffects(
                Effects(
                    if (clip.isOverlay) ImmutableList.of()
                    else buildAudioProcessors(clip, state.originalVolume),
                    ImmutableList.copyOf(effects)
                )
            )
            .build()
    }

    /**
     * The clip's retime, for the picture.
     *
     * An unramped clip at 1x gets nothing at all rather than a no-op effect: every
     * entry here is a pass over every frame, and SpeedChangeEffect can only skip
     * itself when its provider reports one rate and no upcoming change.
     */
    private fun speedEffects(clip: Clip): List<Effect> {
        if (clip.speedRamp.isIdentity) return emptyList()
        val segments = clip.speedRamp.segments(clip.sourceSpanMs)
        if (segments.isEmpty()) return emptyList()
        return listOf(SpeedChangeEffect(RampSpeedProvider(segments)))
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
                    // No clip, so no ramp: this path is the whole file, untimed.
                    gainOnly(state.originalVolume),
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
                // A sound's own ramp, which is almost always none. Speed is a
                // property of a clip now, so a slowed shot no longer drags the
                // music with it - which was never what anyone wanted.
                .setEffects(Effects(buildAudioProcessors(clip, clip.volume), ImmutableList.of()))
                .build()
        )

        val sequence = EditedMediaItemSequence.Builder()
        items.forEach { sequence.addItem(it) }
        return sequence.build()
    }

    /**
     * The lead-in pad: silence, one second of it per second of timeline.
     *
     * The pad's length is read off the timeline, and the timeline is in played
     * time now, so the slice is already the right length and nothing retimes it.
     */
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

        // A hand-drawn crop is a rectangle, so it is cut rather than fitted: a
        // Presentation can only take the middle of the frame at a given shape,
        // which is exactly the limitation the custom crop exists to remove.
        val crop = state.effectiveCrop
        if (state.cropAspect == CropAspect.Custom && !crop.isFull) {
            val ndc = crop.toNdc()
            effects.add(Crop(ndc[0], ndc[1], ndc[2], ndc[3]))
        } else {
            state.cropAspect.ratio?.let { ratio ->
                effects.add(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }
        }

        if (state.outputP != OutputSize.ORIGINAL && !state.fitToSize) {
            val resolution = state.outputResolution
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

        // The effects library, after the grade and before the captions, so a shake
        // or a glitch moves the picture and leaves the words readable on top.
        if (state.effects.isNotEmpty()) {
            val timed = state.effects
            effects.add(FxEffect { timed })
        }

        if (state.textOverlays.isNotEmpty()) {
            // Widened at the declaration: OverlayEffect takes List<TextureOverlay>,
            // and Java generics are invariant, so a list of the subtype will not do.
            val overlays: List<TextureOverlay> = state.textOverlays.map { SquishTextOverlay(it) }
            effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
        }

        return ImmutableList.copyOf(effects)
    }

    private companion object {
        /** Often enough to feel live, rare enough not to compete with the encoder. */
        val PROGRESS_POLL = 200.milliseconds
    }

    /**
     * A clip's sound: retimed by its own ramp, then set to its level.
     *
     * SpeedChangingAudioProcessor takes the same provider the picture does, from
     * the same segments, so the two cannot end up different lengths. It preserves
     * pitch, which is the only reason a ramp is usable on anything with a voice in
     * it - a rate change without it is a slide whistle.
     */
    private fun buildAudioProcessors(clip: Clip, volume: Float): ImmutableList<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (!clip.speedRamp.isIdentity) {
            val segments = clip.speedRamp.segments(clip.sourceSpanMs)
            if (segments.isNotEmpty()) {
                processors.add(SpeedChangingAudioProcessor(RampSpeedProvider(segments)))
            }
        }
        AudioMixing.gain(volume)?.let { processors.add(it) }
        return ImmutableList.copyOf(processors)
    }

    /** Level only, for the one path that has no clip to read a ramp from. */
    private fun gainOnly(volume: Float): ImmutableList<AudioProcessor> {
        val gain = AudioMixing.gain(volume)
        return if (gain == null) ImmutableList.of() else ImmutableList.of(gain)
    }
}
