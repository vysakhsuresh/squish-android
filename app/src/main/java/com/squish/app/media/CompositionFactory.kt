@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.squish.app.editor.EditorUiState
import com.squish.app.media.effects.ChromaKeyEffect
import com.squish.app.media.effects.MaskEffect
import com.squish.app.timeline.Clip
import com.squish.app.timeline.TransitionType

/**
 * Builds the video side of a [Composition].
 *
 * Two strategies, chosen by what the edit actually needs:
 *
 *  - **Cuts only** — one sequence, clips end to end. Uses nothing beyond the APIs
 *    the rest of the app already relies on, and is what every export took before
 *    transitions existed.
 *
 *  - **Composited** — A/B roll. Transitions require two shots to be on screen at
 *    once, and a single sequence plays its items strictly one after another, so
 *    alternating clips are dealt onto two sequences with gaps opposite each
 *    other. Where they overlap you get a transition; overlay layers ride on
 *    further sequences above. This is how an NLE has always done dissolves.
 *
 * The composited path is only taken when the edit contains a transition or an
 * overlay, so a plain cuts export never touches the newer compositing APIs.
 *
 * BUILD RISK: gaps (`EditedMediaItemSequence.Builder.addGap`) and per-input
 * compositing arrived in Media3 1.5, which is why the module was moved off
 * 1.4.1. If those signatures differ in the version you resolve, this file is the
 * only one to fix - `buildCutsOnly` keeps working regardless. See BUILD_NOTES.md.
 */
object CompositionFactory {

    /**
     * Cuts-only can render a track that runs end to end. The moment clips are
     * layered, overlap for a transition, or sit apart with a gap between them, the
     * edit needs real positioning and goes down the compositing path.
     */
    fun needsCompositing(state: EditorUiState): Boolean {
        if (state.videoClips.any { it.isOverlay || it.transitionIn.isActive }) return true
        val base = state.videoClips.filter { !it.isOverlay }.sortedBy { it.timelineStartMs }
        if (base.isEmpty()) return false
        if (base.first().timelineStartMs > 0L) return true
        return base.zipWithNext().any { (a, b) -> b.timelineStartMs != a.timelineEndMs }
    }

    fun buildCutsOnly(items: List<EditedMediaItem>): List<EditedMediaItemSequence> {
        val sequence = EditedMediaItemSequence.Builder()
        items.forEach { sequence.addItem(it) }
        return listOf(sequence.build())
    }

    /**
     * Deals the base track onto two alternating sequences so consecutive shots can
     * overlap, and puts each overlay layer on its own sequence above them.
     */
    fun buildComposited(
        state: EditorUiState,
        editedFor: (Clip) -> EditedMediaItem
    ): List<EditedMediaItemSequence> {
        val base = state.videoClips.filter { !it.isOverlay }.sortedBy { it.timelineStartMs }
        if (base.isEmpty()) return emptyList()

        val rollA = EditedMediaItemSequence.Builder()
        val rollB = EditedMediaItemSequence.Builder()
        var cursorA = 0L
        var cursorB = 0L

        base.forEachIndexed { index, clip ->
            val ontoA = index % 2 == 0
            val start = clip.timelineStartMs
            if (ontoA) {
                if (start > cursorA) rollA.addGap(msToUs(start - cursorA))
                rollA.addItem(editedFor(clip))
                cursorA = start + clip.durationMs
            } else {
                if (start > cursorB) rollB.addGap(msToUs(start - cursorB))
                rollB.addItem(editedFor(clip))
                cursorB = start + clip.durationMs
            }
        }

        val sequences = mutableListOf(rollA.build())
        if (base.size > 1) sequences.add(rollB.build())

        state.videoClips.filter { it.isOverlay }.sortedBy { it.layer }.forEach { overlay ->
            val builder = EditedMediaItemSequence.Builder()
            if (overlay.timelineStartMs > 0) builder.addGap(msToUs(overlay.timelineStartMs))
            builder.addItem(editedFor(overlay))
            sequences.add(builder.build())
        }

        return sequences
    }

    /**
     * Geometry and opacity for an overlay: scaled down, moved where it was put, and
     * dimmed to taste. Applied as effects on the clip itself rather than through
     * compositor settings, so it degrades to a plain scaled inset if per-input
     * compositing is unavailable.
     *
     * Scale, position and rotation are one matrix rather than several effects,
     * which is also what lets them be animated: see ClipTransformEffect.
     */
    fun overlayEffects(clip: Clip, canvasWidth: Int, canvasHeight: Int): List<Effect> {
        if (!clip.isOverlay || canvasWidth <= 0 || canvasHeight <= 0) return emptyList()
        return buildList {
            // Keyed first, on the raw frame, so the matte is cut from the pixels the
            // camera saw rather than from a scaled and resampled copy of them.
            clip.chromaKey?.let { add(ChromaKeyEffect(it)) }
            // After the key, so the two mattes multiply rather than one replacing
            // the other, and before the transform, so the shape is cut from the
            // frame the camera saw rather than from a scaled copy of it.
            clip.mask?.let { add(MaskEffect(it, clip.sourceInMs)) }
            add(ClipTransformEffect(clip.keyframes, clip.staticTransform, clip.stabilizer, clip.sourceInMs))
            add(Presentation.createForWidthAndHeight(canvasWidth, canvasHeight, Presentation.LAYOUT_SCALE_TO_FIT))
            if (clip.opacity < 1f) add(AlphaScale(clip.opacity))
        }
    }

    /**
     * How a transition reads on the incoming shot. A dissolve fades it up; a dip
     * takes the outgoing shot down to black first. Both need alpha that changes
     * over the overlap, which the compositor supplies per presentation time.
     */
    fun transitionAlphaAt(clip: Clip, positionInClipMs: Long): Float {
        val transition = clip.transitionIn
        if (!transition.isActive) return 1f
        if (positionInClipMs >= transition.durationMs) return 1f
        val progress = (positionInClipMs.toFloat() / transition.durationMs).coerceIn(0f, 1f)
        return when (transition.type) {
            TransitionType.CrossFade, TransitionType.SlideLeft, TransitionType.WipeRight -> progress
            // Black in the middle: out to nothing, then back up.
            TransitionType.DipToBlack -> if (progress < 0.5f) 0f else (progress - 0.5f) * 2f
            TransitionType.None -> 1f
        }
    }

    private fun msToUs(ms: Long): Long = ms * 1000L
}
