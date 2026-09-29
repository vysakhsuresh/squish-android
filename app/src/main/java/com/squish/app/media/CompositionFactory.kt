@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.squish.app.media.effects.BackgroundEffect
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.MaskEffect
import com.squish.app.timeline.Clip
import java.io.File

/**
 * Builds the video side of a [Composition].
 *
 * Two strategies, chosen by what the edit actually needs (ExportPlan.needsCompositing):
 *
 *  - **Cuts only** — one sequence, clips end to end. What every export took
 *    before transitions existed, and still the cheapest.
 *
 *  - **Composited** — every layer its own sequence, stacked by Media3's
 *    compositor. The base track is dealt onto as many rolls as its overlaps need
 *    (a transition is two shots on screen at once, and one sequence plays its
 *    items strictly one after another); each overlay layer rides above them; and
 *    on top of everything a transparent clock sequence the length of the edit.
 *
 * What this leans on in Media3 1.11, and nothing more: sequences are handed to
 * the compositor in order, the first is the primary whose timestamps the output
 * takes, and the compositor draws the first input on top and each later one
 * underneath (DefaultCompositorGlProgram, "draw textures from back to front"),
 * blending straight alpha. Transitions are drawn into each shot's own pixels
 * (TransitionEffect), and the empty stretches of a layer are a transparent still
 * rather than Media3's gap, so per-input compositor settings are used only to
 * hide the clock and to name the output size.
 *
 * One consequence to know about: the file's colour is set from the primary's
 * first format (VideoSampleExporter), and the primary is the clock, an sRGB
 * still, which Media3 maps to SDR BT.709. A composited export is therefore
 * always SDR, and any HDR clip in it is tone-mapped on the way in, while the
 * same clips cut end to end keep HDR. Adding a transition to an HLG edit
 * changes the look of the whole file. Deliberate for now - HDR through the
 * compositor is the least proven path Media3 has - and it is what
 * SquishError.MixedColourRanges tells the user when tone-mapping fails.
 */
object CompositionFactory {

    fun needsCompositing(state: com.squish.app.editor.EditorUiState): Boolean =
        ExportPlan.needsCompositing(state.videoClips)

    /** The one-sequence export: every clip end to end, with the tracks [trackTypes] names. */
    fun buildCutsOnly(items: List<EditedMediaItem>, trackTypes: Set<Int>): List<EditedMediaItemSequence> =
        listOf(EditedMediaItemSequence.Builder(trackTypes).addItems(items).build())

    /** What the composited path hands the Composition. */
    class Composited(
        val sequences: List<EditedMediaItemSequence>,
        val settings: VideoCompositorSettings?
    )

    /**
     * Every layer of [layers] as a sequence, top first.
     *
     * @param videoOut false for a sound-only export: then only the base rolls are
     *   built, as sound, and their empty stretches are Media3's own audio gaps.
     * @param baseAudio whether the base rolls carry the clips' own sound. Every
     *   roll that does is declared with an audio track from its first moment, so
     *   a roll that opens on a photo, a blank or an empty stretch is filled with
     *   silence rather than refused - which is how a Dissolve between a video and
     *   a photo failed every export on the device.
     * @param filler a transparent still [durationMs] long, for the empty stretches.
     * @param overlaySound whether an overlay clip is heard: footage at a level
     *   above nothing. An overlay row with one such clip carries a sound track
     *   from its first moment, as a base roll does, and every other stretch of
     *   it - a gap, a photo, a muted clip - is silence.
     * @param editedFor the clip as an item of its layer.
     */
    fun buildComposited(
        layers: ExportPlan.Layers,
        canvas: ExportPresets.Resolution?,
        videoOut: Boolean,
        baseAudio: Boolean,
        filler: (durationMs: Long) -> EditedMediaItem,
        overlaySound: (Clip) -> Boolean = { false },
        editedFor: (Clip, ExportPlan.Layer) -> EditedMediaItem
    ): Composited {
        // LayerSettings hides input 0 as the clock. Anything else there would be
        // a real layer drawn at nothing - a black file - so it is refused here,
        // loudly, rather than rendered.
        if (videoOut) {
            check(layers.layers.firstOrNull()?.role == ExportPlan.Role.Clock) { "the first layer is not the clock" }
        }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        for (layer in layers.layers) {
            val heard = layer.role == ExportPlan.Role.Overlay && layer.clips.any(overlaySound)
            // Sound only: the base rolls, and the overlay rows that have any.
            if (!videoOut && layer.role != ExportPlan.Role.Base && !heard) continue
            val types = when {
                !videoOut -> setOf(C.TRACK_TYPE_AUDIO)
                layer.role == ExportPlan.Role.Base && baseAudio -> setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
                heard -> setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
                else -> setOf(C.TRACK_TYPE_VIDEO)
            }
            // A layer with nothing at all in it has no pieces; the clock always
            // has one, the edit's length, however short (ExportPlan.pieces).
            val pieces = ExportPlan.pieces(layer, layers.endMs)
            if (pieces.isEmpty()) continue
            val builder = EditedMediaItemSequence.Builder(types)
            for (piece in pieces) {
                when {
                    // Sound only, an overlay that makes none is a stretch of silence.
                    piece is ExportPlan.Piece.Item && (videoOut || layer.role == ExportPlan.Role.Base || overlaySound(piece.clip)) ->
                        builder.addItem(editedFor(piece.clip, layer))
                    videoOut -> builder.addItem(filler(piece.durationMs))
                    else -> builder.addGap(piece.durationMs * 1_000L)
                }
            }
            sequences.add(builder.build())
        }
        return Composited(sequences, if (videoOut) LayerSettings(canvas) else null)
    }

    /**
     * A photo kept as a picture on an overlay row, shown for [durationMs] at
     * [frameRate]. Handed over as the image it is - PNG, alpha and all - so a
     * transparent logo is transparent in the file; a still rendered to H.264, as
     * a main-track photo is, has no alpha to keep.
     */
    fun stillItem(uri: Uri, durationMs: Long, frameRate: Int, effects: List<Effect>): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.IMAGE_PNG)
            .setImageDurationMs(durationMs.coerceAtLeast(1L))
            .build()
        return EditedMediaItem.Builder(item)
            .setFrameRate(frameRate)
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects)))
            .build()
    }

    /**
     * A transparent still of [durationMs] at [frameRate], from [clearFrame] (see
     * StillClips.clearFrame). The alpha is also scaled to nothing, so the still
     * stays invisible even if some step between the file and the compositor
     * drops its transparency and hands on black.
     */
    fun filler(clearFrame: File, durationMs: Long, frameRate: Int): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(clearFrame))
            .setMimeType(MimeTypes.IMAGE_PNG)
            .setImageDurationMs(durationMs.coerceAtLeast(1L))
            .build()
        val hide: List<Effect> = listOf(AlphaScale(0f))
        return EditedMediaItem.Builder(item)
            .setFrameRate(frameRate)
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(hide)))
            .build()
    }

    /**
     * An overlay's picture effects: keyed and masked on the frame the camera saw,
     * fitted to the canvas the file is written at, and only then placed.
     *
     * Placement after the fit is what makes it canvas-relative. Done before, the
     * offsets were fractions of the overlay's own frame: a portrait PiP 45% right
     * of centre over a landscape edit landed 14% right of it in the file, and
     * the size was measured against the source's frame while the base had been
     * scaled to the chosen output, so a 4K source exported at 1080p drew every
     * PiP twice the size it was placed at.
     *
     * No rotation and no grade: the preview draws neither on an overlay, and the
     * two have to agree. Captions and the effects library are drawn once for the
     * whole frame, above every layer (VideoProcessor.compositionEffects).
     */
    fun overlayEffects(clip: Clip, canvas: ExportPresets.Resolution?, speed: List<Effect>): List<Effect> =
        buildList {
            // Keyed first, on the raw frame, so the matte is cut from the pixels the
            // camera saw rather than from a scaled and resampled copy of them.
            clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
            // After the key, so the two mattes multiply rather than one replacing
            // the other, and before the transform, so the shape is cut from the
            // frame the camera saw rather than from a scaled copy of it.
            clip.background?.let { add(BackgroundEffect(it, clip.sourceInMs)) }
            clip.mask?.let { add(MaskEffect(it, clip.sourceInMs)) }
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.Stabilizer)?.let { add(it) }
            if (canvas != null && canvas.width > 0 && canvas.height > 0) {
                add(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.User)?.let { add(it) }
            addAll(speed)
            if (clip.opacity < 1f) add(AlphaScale(clip.opacity.coerceIn(0f, 1f)))
        }

    /**
     * The compositor told the two things it cannot know: how big the output is,
     * and that the clock on top is not to be seen.
     *
     * Every layer arrives at the canvas's size already - fitted in its own chain
     * - so each is drawn one to one. The size is stated rather than left to
     * Media3, whose default is "whatever the first input is", and the first input
     * is the clock: a 16 pixel still.
     */
    private class LayerSettings(private val canvas: ExportPresets.Resolution?) : VideoCompositorSettings {

        /** When nothing measured the canvas, the first real frame's size, kept for the whole export. */
        private var latched: Size? = null

        override fun getOutputSize(inputSizes: List<Size>): Size {
            if (canvas != null && canvas.width > 0 && canvas.height > 0) return Size(canvas.width, canvas.height)
            latched?.let { return it }
            val biggest = inputSizes.drop(1).maxByOrNull { it.width.toLong() * it.height }
            return if (biggest != null && biggest.width > FILLER_SIDE && biggest.height > FILLER_SIDE) {
                biggest.also { latched = it }
            } else {
                inputSizes.first()
            }
        }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
            if (inputId == CLOCK_INPUT) HIDDEN else SHOWN
    }

    private const val CLOCK_INPUT = 0
    private const val FILLER_SIDE = 16
    private val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val SHOWN: OverlaySettings = StaticOverlaySettings.Builder().build()
}
