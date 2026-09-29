@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.squish.app.media.effects.BackgroundEffect
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.ClipCropEffect
import com.squish.app.media.effects.ColorGrade
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
 * The clock opens on Media3's own gap and is a transparent still from there
 * (ExportPlan.pieces says why the two, in that order). The gap is declared with
 * sound whenever any layer is (ExportPlan.sequenceTracks): its loader announces
 * both of its tracks before it starts and, being the first sequence, makes the
 * sound exporter and then the picture's, so a layer that opens on a still can
 * never ask for its sound track before the exporter exists. The gap's one frame
 * is opaque black and the still is clear; both are hidden the same way,
 * LayerSettings drawing input 0 at nothing. The gap is built here rather than
 * by the sequence builder's addGap, so it can carry the resampler that sets the
 * mixer's rate (clockGap).
 *
 * One consequence to know about: the file's colour is set from the primary's
 * first format (VideoSampleExporter), and the primary is the clock, whose gap
 * frame is sRGB, which Media3 maps to SDR BT.709. A composited export is
 * therefore always SDR, and any HDR clip in it is tone-mapped on the way in,
 * while the same clips cut end to end keep HDR. Adding a transition to an HLG
 * edit changes the look of the whole file. Deliberate for now - HDR through the
 * compositor is the least proven path Media3 has - and it is what
 * SquishError.MixedColourRanges tells the user when tone-mapping fails.
 */
object CompositionFactory {

    fun needsCompositing(state: com.squish.app.editor.EditorUiState): Boolean =
        ExportPlan.needsCompositing(state.videoClips, state.trimmedDurationMs, state.paddedCanvas)

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
     * @param filler a transparent still [durationMs] long, at the edit's frame
     *   rate: a layer's empty stretches, and the clock after its opening gap.
     * @param clockLeadMs how long the clock's opening gap is; ExportPlan.clockLeadMs.
     * @param mixerSampleRateHz the rate every sound is mixed at; ExportPlan.mixerSampleRate.
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
        clockLeadMs: Long,
        mixerSampleRateHz: Int = ExportPlan.DEFAULT_SAMPLE_RATE_HZ,
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
        val tracks = ExportPlan.sequenceTracks(layers, videoOut, baseAudio, overlaySound)
        for ((layer, declared) in layers.layers.zip(tracks)) {
            // Sound only: the base rolls, and the overlay rows that have any.
            if (declared == null) continue
            val types = buildSet {
                if (declared.video) add(C.TRACK_TYPE_VIDEO)
                if (declared.sound) add(C.TRACK_TYPE_AUDIO)
            }
            // A layer with nothing at all in it has no pieces; the clock always
            // has some, the edit's length, however short (ExportPlan.pieces).
            val pieces = ExportPlan.pieces(layer, layers.endMs, clockLeadMs)
            if (pieces.isEmpty()) continue
            val builder = EditedMediaItemSequence.Builder(types)
            for (piece in pieces) {
                when (piece) {
                    is ExportPlan.Piece.Item ->
                        // Sound only, an overlay that makes none is a stretch of silence.
                        if (videoOut || layer.role == ExportPlan.Role.Base || overlaySound(piece.clip)) {
                            builder.addItem(editedFor(piece.clip, layer))
                        } else {
                            builder.addGap(piece.durationMs * 1_000L)
                        }
                    // The clock's gap sets the mixer's rate; any other is plain.
                    is ExportPlan.Piece.Gap ->
                        if (layer.role == ExportPlan.Role.Clock && declared.sound) builder.addItem(clockGap(piece.durationMs, mixerSampleRateHz))
                        else builder.addGap(piece.durationMs * 1_000L)
                    // A layer's empty stretch is the still, not a gap: the gap's
                    // frames are opaque black, and the still is the path the
                    // device has rendered. Sound only, it is silence.
                    is ExportPlan.Piece.Clear ->
                        if (videoOut) builder.addItem(filler(piece.durationMs)) else builder.addGap(piece.durationMs * 1_000L)
                }
            }
            sequences.add(builder.build())
        }
        return Composited(sequences, if (videoOut) LayerSettings(canvas) else null)
    }

    /**
     * The clock's opening gap, [durationMs] long, with the resampler that makes
     * the mixer run at [mixerSampleRateHz].
     *
     * Media3's mixer takes its format from the first sound it is handed, which
     * in a composited export is this gap's: a fixed 44.1 kHz stereo, so every
     * 48 kHz camera track was stepped down to it. A sequence's first item is
     * what the sound input is built from, its own audio processors included,
     * so a resampler here is the one place the mixer's rate can be set. The
     * builder's addGap makes an item just like this one, but takes no effects;
     * Media3 knows a gap by its media id (EditedMediaItem.isGap), which is not
     * public, so the id is spelt out - a rename would not be a quiet
     * regression: the first item would be a media item with no file, and the
     * export would fail on it at once.
     */
    private fun clockGap(durationMs: Long, mixerSampleRateHz: Int): EditedMediaItem {
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(mixerSampleRateHz) }
        val processors: List<AudioProcessor> = listOf(resampler)
        return EditedMediaItem.Builder(MediaItem.Builder().setMediaId(GAP_MEDIA_ID).build())
            .setDurationUs(durationMs * 1_000L)
            .setEffects(Effects(ImmutableList.copyOf(processors), ImmutableList.of()))
            .build()
    }

    /** What EditedMediaItemSequence.Builder.addGap names its item, in Media3 1.11.1. */
    private const val GAP_MEDIA_ID = "androidx-media3-GapMediaItem"

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
     * A picture of a padded canvas's background, shown for [durationMs] at
     * [frameRate], filling the canvas: the blurred still, the colour or the
     * chosen picture that CanvasBackdrop wrote, already at the canvas's shape.
     */
    fun backdropItem(uri: Uri, durationMs: Long, frameRate: Int, canvas: ExportPresets.Resolution?): EditedMediaItem {
        val fill: List<Effect> = if (canvas != null && canvas.width > 0 && canvas.height > 0) {
            listOf(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        } else emptyList()
        return stillItem(uri, durationMs, frameRate, fill)
    }

    /**
     * A transparent still of [durationMs] at [frameRate], from [clearFrame] (see
     * StillClips.clearFrame). The alpha is also scaled to nothing, so the still
     * stays invisible even if some step between the file and the compositor
     * drops its transparency and hands on black. As the clock's body its
     * [frameRate] is the file's: one output frame per frame of it.
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
     * cropped and graded as its own, fitted to the canvas the file is written
     * at, and only then placed.
     *
     * Placement after the fit is what makes it canvas-relative. Done before, the
     * offsets were fractions of the overlay's own frame: a portrait PiP 45% right
     * of centre over a landscape edit landed 14% right of it in the file, and
     * the size was measured against the source's frame while the base had been
     * scaled to the chosen output, so a 4K source exported at 1080p drew every
     * PiP twice the size it was placed at.
     *
     * No rotation: the preview draws none on an overlay, and the two have to
     * agree. The grade is the clip's own, as it is on every surface of the
     * preview now. Captions and the effects library are drawn once for the
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
            // The grade on the frame the camera saw - the player's shader,
            // before the view's crop - so a vignette falls off to the corners
            // of the picture, not of the window; then the clip's own crop, on
            // its own picture, before the fit, where the preview's layers cut it.
            addAll(ColorGrade.effects(clip.grade))
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.Stabilizer)?.let { add(it) }
            // Mirrored and turned as footage, before it is fitted: a turned
            // landscape layer is then fitted standing, as the preview lays its
            // view out (TimelinePreview.turnedInside). The crop after the turn:
            // its window is a fraction of the footage as seen, which is what
            // the Crop tool draws it on.
            turn(clip)?.let { add(it) }
            clip.crop?.takeIf { !it.isIdentity }?.let { add(ClipCropEffect(it)) }
            if (canvas != null && canvas.width > 0 && canvas.height > 0) {
                add(Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            ClipTransformEffect.of(clip, ExportPlan.MotionPart.User)?.let { add(it) }
            addAll(speed)
            if (clip.opacity < 1f) add(AlphaScale(clip.opacity.coerceIn(0f, 1f)))
        }

    /**
     * A clip's own mirror and quarter turns, or null when it has neither. One
     * effect: the flip is a scale of -1 across, applied before the turn, which
     * is the order the preview's layer applies them (scale, then rotation).
     * The clip's turns are clockwise as seen and Media3's degrees run the other
     * way, so the number is negated - the same disagreement PreviewBox.screenRotation
     * settles for the edit-wide rotation, from the other side.
     */
    fun turn(clip: Clip): Effect? {
        if (!clip.mirrored && clip.quarterTurns % 4 == 0) return null
        return ScaleAndRotateTransformation.Builder()
            .setScale(if (clip.mirrored) -1f else 1f, 1f)
            .setRotationDegrees(ExportPlan.turnDegrees(clip.quarterTurns))
            .build()
    }

    /**
     * The compositor told the two things it cannot know: how big the output is,
     * and that the clock on top is not to be seen.
     *
     * Every layer arrives at the canvas's size already - fitted in its own chain
     * - so each is drawn one to one. The size is stated rather than left to
     * Media3, whose default is "whatever the first input is", and the first input
     * is the clock: the 16 pixel black frame Media3 fills a gap with, then a
     * 16 pixel still.
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
