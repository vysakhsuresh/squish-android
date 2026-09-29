@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
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
import com.squish.app.editor.OverlayRules
import com.squish.app.timeline.VoiceEffect
import com.squish.app.media.audio.FadeProcessor
import com.squish.app.media.audio.GainCurveProcessor
import com.squish.app.media.audio.VoiceProcessor
import com.squish.app.media.video.FrameBlendEffect
import com.squish.app.media.video.FrameBlendPlan
import com.squish.app.media.effects.BackgroundEffect
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.ColorGrade
import com.squish.app.media.effects.FxEffect
import com.squish.app.media.effects.MaskEffect
import com.squish.app.media.effects.ReframeEffect
import com.squish.app.media.effects.TransitionEffect
import com.squish.app.timeline.Clip
import com.squish.app.timeline.SpeedRamp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** What an export is doing, as far as anyone waiting on it needs to know. */
enum class ExportStage {
    /** Checking the edit and getting the encoder ready; nothing is being written yet. */
    Preparing,
    Rendering,
    /** The file is written and being copied into the gallery. */
    Saving
}

/** How far along an export is, as the encoder itself reports it. */
data class ExportProgress(
    /** 0f..1f, or null while the encoder cannot yet say. */
    val fraction: Float? = null,
    val elapsedMs: Long = 0,
    /**
     * Projected from the rate so far, once there is enough of it to project from.
     * Null early on rather than a wild number that halves every second.
     */
    val remainingMs: Long? = null,
    val stage: ExportStage = ExportStage.Rendering
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

        var result: Result<File>? = null
        try {
            // The transparent still that fills a layer's empty stretches, written
            // once and kept. Only a composited export needs it.
            val needsClear = !state.audioOnly && CompositionFactory.needsCompositing(state)
            val clear = if (needsClear) withContext(Dispatchers.IO) { StillClips.clearFrame(context) } else null
            val outcome = if (needsClear && clear == null) {
                Result.failure(IOException("Could not write the blank frame the layers are padded with"))
            } else {
                runExport(state, outputFile, clear) { active.set(it) }
            }
            result = outcome
            outcome
        } finally {
            poll.cancel()
            // A file that did not finish is not an export. Left behind, a
            // cancelled or failed render sat in exports/ as a broken MP4 nobody
            // could see or remove - and each one cost what a finished export
            // costs. A cancellation reaches here with no result at all.
            if (result?.isSuccess != true) runCatching { outputFile.delete() }
        }
    }

    private suspend fun runExport(
        state: EditorUiState,
        outputFile: File,
        clear: File?,
        onTransformer: (Transformer) -> Unit
    ): Result<File> =
        suspendCancellableCoroutine { continuation ->
            val built = runCatching { buildComposition(state, clear) }
            val composition = built.getOrElse {
                continuation.resume(Result.failure(it))
                return@suspendCancellableCoroutine
            }

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
                        android.util.Log.w("SquishExport", "failed: ${composition.sequences.size} sequences", exportException)
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
     * The edit as Media3 objects: the picture's sequences, one sequence per added
     * sound, and what is drawn over the whole frame.
     */
    private fun buildComposition(state: EditorUiState, clear: File?): Composition {
        val track = state.videoClips
        val videoOut = !state.audioOnly
        // A sound-only export keeps the camera's sound even when the picture's
        // mute is on - sound is all it was asked for.
        val baseAudio = state.audioOnly || !state.muteOriginal
        // The size the encoder will write, not the one asked for: fitted here,
        // the layers are drawn at it and the bitrate is spent on it, rather than
        // drawn at twice the size and shrunk by the encoder's own fallback.
        val canvas = state.writtenResolution.takeIf { it.width > 0 && it.height > 0 }

        var settings: androidx.media3.common.VideoCompositorSettings? = null
        val videoSequences: List<EditedMediaItemSequence> = when {
            // One file and no clips: the quick tools. The sequence takes whatever
            // tracks the file has, which is what lets a plain cut be copied
            // rather than re-encoded.
            track.isEmpty() -> {
                @Suppress("DEPRECATION")
                val single = EditedMediaItemSequence.Builder(
                    listOf(editedVideo(state, state.sourceUri, state.trimStartMs, state.trimEndMs, isPlainTrim(state)))
                ).build()
                listOf(single)
            }
            // Only an edit that actually uses transitions, layers or gaps pays the
            // cost - and the risk - of the compositing path.
            CompositionFactory.needsCompositing(state) -> {
                // To the edit's end, sounds included: a song dragged out past
                // the last shot runs on over black, as the preview plays it.
                val layers = ExportPlan.layers(track, state.trimmedDurationMs)
                val rate = frameRateOf(state)
                val composited = CompositionFactory.buildComposited(
                    layers = layers,
                    canvas = canvas,
                    videoOut = videoOut,
                    baseAudio = baseAudio,
                    filler = { ms -> CompositionFactory.filler(checkNotNull(clear), ms, rate) },
                    clockLeadMs = ExportPlan.clockLeadMs(rate),
                    mixerSampleRateHz = ExportPlan.mixerSampleRate(soundSampleRates(state, baseAudio)),
                    overlaySound = ::overlayHeard,
                    editedFor = { clip, layer ->
                        if (layer.role == ExportPlan.Role.Overlay) editedOverlay(state, clip, canvas, rate)
                        else editedClip(state, clip, canvas, layers.baseRolls)
                    }
                )
                settings = composited.settings
                composited.sequences
            }
            // In time order, which is what "one after another" means; a clip
            // trimmed to nothing is left out rather than handed to Media3 as an
            // empty window.
            else -> CompositionFactory.buildCutsOnly(
                track.filter { it.durationMs > 0 }.sortedBy { it.timelineStartMs }
                    .map { editedClip(state, it, canvas, rolls = null) },
                trackTypesFor(videoOut, baseAudio)
            )
        }

        val sequences = videoSequences.toMutableList()
        // One sequence per added sound. A Composition mixes its sequences
        // together, so overlapping music, a voiceover and a second mic all
        // land in the same output without any of them being a special case.
        sequences.addAll(buildAudioSequences(state))
        if (sequences.isEmpty()) throw IllegalStateException("Nothing to export")

        return Composition.Builder(ImmutableList.copyOf(sequences))
            .setEffects(compositionEffects(state))
            .apply { settings?.let { setVideoCompositorSettings(it) } }
            .build()
    }

    /**
     * The tracks the one-sequence export carries. The sound track is declared
     * rather than inferred, so a run of clips that opens on a photo or a silent
     * clip still has sound from its first moment - Media3 will not start a
     * sequence without it and pick it up later.
     */
    private fun trackTypesFor(videoOut: Boolean, withAudio: Boolean): Set<Int> = when {
        !videoOut -> setOf(C.TRACK_TYPE_AUDIO)
        withAudio -> setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        else -> setOf(C.TRACK_TYPE_VIDEO)
    }

    /**
     * Drawn once over the finished frame, above every layer: the captions and the
     * effects library.
     *
     * They rode on each base clip before, which drew a caption twice across a
     * dissolve (both rolls carried it), left it off a picture-in-picture, and
     * dropped it over a gap - a title on a black cold open was simply not in the
     * file. Here the frames carry the edit's own time, which is the clock both
     * run on.
     */
    private fun compositionEffects(state: EditorUiState): Effects {
        if (state.audioOnly) return Effects.EMPTY
        // Widened at the declaration: Effects takes List<Effect>, Java generics are
        // invariant, and a list inferred as a subtype will not do.
        val effects = mutableListOf<Effect>()
        // The effects library before the captions, so a shake or a glitch moves
        // the picture and leaves the words readable on top.
        if (state.effects.isNotEmpty()) {
            val timed = state.effects
            effects.add(FxEffect { timed })
        }
        if (state.textOverlays.isNotEmpty()) {
            // Widened at the declaration: OverlayEffect takes List<TextureOverlay>.
            val overlays: List<TextureOverlay> = state.textOverlays.map { SquishTextOverlay(it) }
            effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
        }
        if (effects.isEmpty()) return Effects.EMPTY
        return Effects(ImmutableList.of(), ImmutableList.copyOf(effects))
    }

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
            state.originalVolume >= 0.999f &&
            singleFileEffects(state).isEmpty() &&
            state.effects.isEmpty() &&
            state.textOverlays.isEmpty()

    /**
     * The sample rates of every sound that reaches the file, as far as the
     * background check has read them (MediaCompat); a file not yet checked
     * contributes nothing. Read here so the mixer can run at the highest of
     * them rather than at the clock's 44.1 kHz - see ExportPlan.mixerSampleRate.
     */
    private fun soundSampleRates(state: EditorUiState, baseAudio: Boolean): List<Int> {
        val sources = buildList {
            if (baseAudio) state.videoClips.filter { !it.isOverlay }.forEach { clip -> (clip.uri ?: state.sourceUri)?.let { add(it) } }
            state.videoClips.filter { it.isOverlay && overlayHeard(it) }.forEach { clip -> clip.uri?.let { add(it) } }
            state.audioClips.forEach { clip -> clip.uri?.let { add(it) } }
        }
        return sources.distinct().mapNotNull { MediaCompat.cached(it)?.sampleRateHz }
    }

    /** The rate the layers are drawn at: the source's, as a whole number, within what encoders take. */
    private fun frameRateOf(state: EditorUiState): Int {
        val fps = state.fps
        return if (fps.isFinite() && fps >= 1f) Math.round(fps).coerceIn(MIN_FPS, MAX_FPS) else DEFAULT_FPS
    }

    // ---- Picture -------------------------------------------------------------------

    /**
     * One base clip: its own look and placement, the edit's rotation, grade and
     * crop, fitted to the canvas, retimed, and - where it shares the screen with
     * another shot - its part in the transition.
     *
     * The order is the preview's. The stabilizer's correction is measured on the
     * frame the camera recorded, so it goes on before the rotation; what the
     * editor asked for is applied to the picture as it is seen, after it. The
     * grade comes before the crop, so a vignette falls off towards the corners of
     * the whole picture as it does on screen, not towards the corners of a 9:16
     * slice of it. Everything that reads a clock and wants source time - the
     * stabilizer, a tracked mask, the reframe - sits before the speed change;
     * the transition wants played time and sits after it.
     *
     * @param canvas the frame every layer is fitted to; null when nothing measured it.
     * @param rolls the base rolls, top first, when the clip is on one.
     */
    private fun editedClip(
        state: EditorUiState,
        clip: Clip,
        canvas: ExportPresets.Resolution?,
        rolls: List<List<Clip>>?
    ): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(clip.uri ?: state.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceInMs)
                    .setEndPositionMs(clip.sourceOutMs.coerceAtLeast(clip.sourceInMs))
                    .build()
            )
            .build()

        val effects: List<Effect> = if (state.audioOnly) emptyList() else buildList {
            clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
            clip.background?.let { add(BackgroundEffect(it, clip.sourceInMs)) }
            clip.mask?.let { add(MaskEffect(it, clip.sourceInMs)) }
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.Stabilizer)?.let { add(it) }
            rotation(state)?.let { add(it) }
            addAll(ColorGrade.effects(state.grade))
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.User)?.let { add(it) }
            crop(state, clip)?.let { add(it) }
            if (canvas != null) {
                // Fit rather than crop: a landscape clip in a portrait edit is
                // letterboxed, not cut in half. Losing half of someone's footage
                // to an automatic decision would be the worst surprise of the two.
                add(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            addAll(speedEffects(clip, state, frameRateOf(state)))
            // Its share of a transition, and its own fade, in one pass; on the
            // one-sequence path there is no compositor to read an alpha, so the
            // fade is drawn towards black there (TransitionEffect).
            val blends = rolls != null && ExportPlan.takesPartInBlend(rolls, clip)
            if (blends || ExportPlan.drawsOwn(clip)) {
                add(TransitionEffect(clip, if (blends) ExportPlan.neighbourhood(rolls!!, clip) else null, opaque = rolls == null))
            }
        }

        // The shot's own level under the camera sound for the whole edit - the
        // number the preview plays it at (OverlayRules.effectiveVolume). A
        // sound-only export keeps the camera's sound even with the picture's
        // mute on, as it always has: sound is all it was asked for. With a
        // level keyed over the clip, the keys carry the shot's own part and
        // the camera level is what is mixed at.
        val keyed = clip.volumeKeys.isNotEmpty()
        val own = if (clip.muted) 0f else if (keyed) 1f else clip.volume
        val level = if (state.audioOnly) (own * state.originalVolume).coerceIn(0f, 1f)
        else OverlayRules.effectiveVolume(clip.copy(volume = own), state.muteOriginal, state.originalVolume)
        return EditedMediaItem.Builder(item)
            .setRemoveAudio(state.muteOriginal && !state.audioOnly)
            .setRemoveVideo(state.audioOnly)
            .setEffects(
                Effects(
                    buildAudioProcessors(clip.speedRamp, clip.sourceSpanMs, level, clip.voice, fadeOf(clip), clip.pitchFollowsSpeed, curveOf(clip)),
                    ImmutableList.copyOf(effects)
                )
            )
            .build()
    }

    /**
     * A clip's level keyed over it (Clip.volumeAt), as the processor takes it,
     * or null when the level is one number. Each value is the shot's own level
     * at that moment, clamped as the slider is.
     */
    private fun curveOf(clip: Clip, startMs: Long = 0L): GainCurveProcessor? =
        if (clip.volumeKeys.isEmpty()) null
        else GainCurveProcessor({ playedMs -> clip.volumeAt(playedMs).coerceIn(0f, AudioMixing.MAX_GAIN) }, startMs)

    /** A clip's fades as the processor takes them, or null when it has none. */
    private fun fadeOf(clip: Clip, startMs: Long = 0L): FadeProcessor? =
        if (clip.fadeInMs <= 0L && clip.fadeOutMs <= 0L) null
        else FadeProcessor(clip.fadeInMs, clip.fadeOutMs, clip.durationMs, startMs)

    /** Whether an overlay is heard: footage, not muted, at a level above nothing somewhere in it. A photo has no sound. */
    private fun overlayHeard(clip: Clip): Boolean = !StillClips.isStill(clip.uri) && clip.isHeard

    /**
     * A floating clip: placed on the canvas and retimed (see
     * CompositionFactory.overlayEffects), with its own sound at its own level -
     * which used to be thrown away, so a reaction clip was silent in the file
     * and the preview alike - and its own voice and fades, as the preview
     * plays them.
     *
     * A photo kept as a picture goes in as the image it is.
     */
    private fun editedOverlay(state: EditorUiState, clip: Clip, canvas: ExportPresets.Resolution?, frameRate: Int): EditedMediaItem {
        val uri = clip.uri ?: state.sourceUri
        if (StillClips.isStill(uri) && uri != null) {
            val still = OverlayRules.asStill(clip)
            return CompositionFactory.stillItem(uri, still.durationMs, frameRate, CompositionFactory.overlayEffects(still, canvas, emptyList()))
        }
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceInMs)
                    .setEndPositionMs(clip.sourceOutMs.coerceAtLeast(clip.sourceInMs))
                    .build()
            )
            .build()
        val heard = overlayHeard(clip)
        // The same retime the preview plays it at. Overlays used to get none, so
        // a slowed picture-in-picture ran at full speed and ended early.
        val effects = if (state.audioOnly) emptyList() else CompositionFactory.overlayEffects(clip, canvas, speedEffects(clip, state, frameRate))
        val keyed = clip.volumeKeys.isNotEmpty()
        return EditedMediaItem.Builder(item)
            .setRemoveAudio(!heard)
            .setRemoveVideo(state.audioOnly)
            .setEffects(
                Effects(
                    if (heard) buildAudioProcessors(
                        clip.speedRamp, clip.sourceSpanMs, if (keyed) 1f else clip.volume.coerceIn(0f, 1f), clip.voice, fadeOf(clip),
                        clip.pitchFollowsSpeed, curveOf(clip)
                    )
                    else ImmutableList.of(),
                    ImmutableList.copyOf(effects)
                )
            )
            .build()
    }

    /** The whole file as one item, for the quick tools. */
    private fun editedVideo(
        state: EditorUiState,
        uri: android.net.Uri?,
        startMs: Long,
        endMs: Long,
        plainTrim: Boolean
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
                    // A copied stream takes no processing at all: any processor,
                    // even one that would change nothing, makes Media3 decode the
                    // sound and cancels the copy.
                    if (plainTrim) ImmutableList.of()
                    else buildAudioProcessors(SpeedRamp(), 0L, state.originalVolume),
                    if (state.audioOnly) ImmutableList.of() else ImmutableList.copyOf(singleFileEffects(state))
                )
            )
            .build()
    }

    /**
     * The one-file path's picture: the edit's rotation, grade and crop, and a
     * resize only when one was asked for - a file exported at its own size is
     * not given a pass over every frame that changes nothing.
     */
    private fun singleFileEffects(state: EditorUiState): List<Effect> = buildList {
        rotation(state)?.let { add(it) }
        addAll(ColorGrade.effects(state.grade))
        crop(state, clip = null)?.let { add(it) }
        if (state.outputP != OutputSize.ORIGINAL && !state.fitToSize) {
            val canvas = state.writtenResolution
            if (canvas.width > 0 && canvas.height > 0) {
                add(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }
    }

    private fun rotation(state: EditorUiState): Effect? =
        if (state.rotationDegrees == 0) null
        else ScaleAndRotateTransformation.Builder().setRotationDegrees(state.rotationDegrees.toFloat()).build()

    /**
     * The kept part of the frame, for [clip] (null: the one-file path).
     *
     * A hand-drawn crop is a rectangle, so it is cut rather than fitted: a
     * Presentation can only take the middle of the frame at a given shape, which
     * is exactly the limitation the custom crop exists to remove.
     *
     * Auto-reframe follows a track measured on one file - the clip it was run on
     * - so only clips of that file follow it; any other clip is cropped to the
     * same shape about its centre. Handed a track from another file, a clip's
     * window chased where a subject had been in different footage.
     */
    private fun crop(state: EditorUiState, clip: Clip?): Effect? {
        val rect = state.effectiveCrop
        if (state.cropAspect == CropAspect.Custom) {
            if (rect.isFull) return null
            val ndc = rect.toNdc()
            return Crop(ndc[0], ndc[1], ndc[2], ndc[3])
        }
        val ratio = state.cropAspect.ratio ?: return null
        val follow = state.reframe
        if (follow != null && !follow.isEmpty) {
            val analysed = state.videoClips.firstOrNull()
            when {
                clip == null -> return ReframeEffect(ratio, follow, state.trimStartMs)
                analysed != null && (clip.uri ?: state.sourceUri) == (analysed.uri ?: state.sourceUri) ->
                    return ReframeEffect(ratio, follow, clip.sourceInMs)
            }
        }
        return Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
    }

    /**
     * The clip's retime, for the picture, and the frames blended into the gaps
     * it opens when asked (FrameBlendEffect, at the file's own rate).
     *
     * An unramped clip at 1x gets nothing at all rather than a no-op effect: every
     * entry here is a pass over every frame, and SpeedChangeEffect can only skip
     * itself when its provider reports one rate and no upcoming change. The
     * blend is only worth a pass where the clip actually goes below the rate
     * its footage carries (SlowMotion): above it there are no gaps to fill.
     */
    private fun speedEffects(clip: Clip, state: EditorUiState, frameRate: Int): List<Effect> {
        if (clip.speedRamp.isIdentity) return emptyList()
        val segments = clip.speedRamp.segments(clip.sourceSpanMs)
        if (segments.isEmpty()) return emptyList()
        return buildList {
            add(SpeedChangeEffect(RampSpeedProvider(segments)))
            if (clip.frameBlend && clip.speedRamp.slowestSpeed < 1f) {
                // Only as many textures as the slowest stretch needs: each is a
                // whole frame of the file, and eight of them at 4K is a third of
                // a gigabyte of GPU memory a 0.5x clip never touches.
                val frames = FrameBlendPlan.framesPerInput(state.fps, clip.speedRamp.slowestSpeed, frameRate)
                add(FrameBlendEffect(FrameBlendPlan.intervalUs(frameRate), frames))
            }
        }
    }

    // ---- Sound ---------------------------------------------------------------------

    /**
     * Every added sound, each as its own sequence: silence up to where it was
     * placed, then the part of the file that plays.
     *
     * The silence is Media3's own gap, which an audio sequence takes. It used to
     * be a slice of the source video's sound played at zero volume, which came up
     * short whenever the timeline ran longer than that file - a photo or a slowed
     * clip after it, and the song started early by the difference - and was
     * skipped outright on a silent source, where the song started at zero.
     */
    private fun buildAudioSequences(state: EditorUiState): List<EditedMediaItemSequence> =
        state.audioClips.mapNotNull { clip -> buildAudioSequence(state, clip) }

    private fun buildAudioSequence(state: EditorUiState, clip: Clip): EditedMediaItemSequence? {
        val audioUri = clip.uri ?: return null
        val slice = ExportPlan.audioSlice(clip, state.trimmedDurationMs) ?: return null

        val trackItem = MediaItem.Builder()
            .setUri(audioUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(slice.sourceInMs)
                    .setEndPositionMs(slice.sourceOutMs)
                    .build()
            )
            .build()

        // A clip heard from part-way through its window has its curve cut to
        // match, or the ramp would run from its start on footage that is past it.
        val span = clip.sourceSpanMs - slice.skippedSourceMs
        val ramp = if (slice.skippedSourceMs > 0L) clip.speedRamp.sliced(slice.skippedSourceMs, clip.sourceSpanMs)
        else clip.speedRamp

        // The fade runs on the clip's played clock, which the stream joins
        // part-way when the clip is heard from inside itself.
        val skippedPlayedMs = if (slice.skippedSourceMs > 0L) clip.speedRamp.outputOffsetAt(slice.skippedSourceMs, clip.sourceSpanMs) else 0L

        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        if (slice.leadMs > 0L) sequence.addGap(slice.leadMs * 1_000L)
        val keyed = clip.volumeKeys.isNotEmpty()
        sequence.addItem(
            EditedMediaItem.Builder(trackItem)
                .setRemoveVideo(true)
                // A sound's own ramp, which is almost always none. Speed is a
                // property of a clip now, so a slowed shot no longer drags the
                // music with it - which was never what anyone wanted.
                .setEffects(
                    Effects(
                        buildAudioProcessors(
                            ramp, span, if (keyed) 1f else clip.volume, clip.voice, fadeOf(clip, skippedPlayedMs),
                            clip.pitchFollowsSpeed, curveOf(clip, skippedPlayedMs)
                        ),
                        ImmutableList.of()
                    )
                )
                .build()
        )
        return sequence.build()
    }

    /**
     * A clip's sound: folded to stereo at its level, retimed by its own ramp,
     * faded and levelled over time, then given its voice.
     *
     * The fold-down comes first and is always there, so every input reaches
     * Media3's mixer as mono or stereo whatever it was recorded as (see
     * AudioMixing). The retime takes the same provider the picture does, from
     * the same segments, so the two cannot end up different lengths. Media3's
     * one-argument SpeedChangingAudioProcessor pitches the sound with the rate
     * like a tape - every retimed clip's sound came out shifted while the
     * preview held it - and its three-argument constructor has the switch for
     * that (shouldMaintainPitch, the third; the second, areInputTimestampsAdjusted,
     * is what the one-argument form passes false for), so the pitch is held or
     * let go by Media3's own processor either way, as the preview's player does
     * it (PreviewEngine.pitchFor). The fade and the level curve sit after it,
     * where the stream runs in played time - the clock they are set in (see
     * FadeProcessor).
     */
    private fun buildAudioProcessors(
        ramp: SpeedRamp,
        spanMs: Long,
        volume: Float,
        voice: VoiceEffect = VoiceEffect.None,
        fade: FadeProcessor? = null,
        pitchFollowsSpeed: Boolean = false,
        curve: GainCurveProcessor? = null
    ): ImmutableList<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>(AudioMixing.processor(volume))
        if (!ramp.isIdentity && spanMs > 0L) {
            val segments = ramp.segments(spanMs)
            if (segments.isNotEmpty()) {
                processors.add(
                    SpeedChangingAudioProcessor(
                        RampSpeedProvider(segments),
                        /* areInputTimestampsAdjusted= */ false,
                        /* shouldMaintainPitch= */ !pitchFollowsSpeed
                    )
                )
            }
        }
        fade?.let { processors.add(it) }
        curve?.let { processors.add(it) }
        processors.addAll(voiceProcessors(voice))
        return ImmutableList.copyOf(processors)
    }

    /**
     * A voice effect: a pitch shift that keeps timing, or one of the processed
     * sounds, or nothing. Sonic shifts pitch without touching duration, which is
     * the only way a voice effect can sit on footage without drifting out of sync.
     */
    private fun voiceProcessors(voice: VoiceEffect): List<AudioProcessor> = when {
        voice == VoiceEffect.None -> emptyList()
        voice.pitch != 1f -> listOf(SonicAudioProcessor().apply { setPitch(voice.pitch) })
        else -> listOf(VoiceProcessor { voice })
    }

    private companion object {
        /** Often enough to feel live, rare enough not to compete with the encoder. */
        val PROGRESS_POLL = 200.milliseconds

        const val DEFAULT_FPS = 30
        const val MIN_FPS = 10
        const val MAX_FPS = 60
    }
}
