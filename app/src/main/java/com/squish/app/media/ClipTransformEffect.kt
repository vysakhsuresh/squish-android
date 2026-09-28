@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.squish.app.timeline.Clip

/**
 * Moves a clip's picture - scaled, turned and shifted - and animates it if the
 * clip carries keyframes.
 *
 * This is the whole reason keyframes are possible on this stack. Media3's
 * per-frame effects are static: Contrast, HslAdjustment and AlphaScale are handed
 * one value and keep it. MatrixTransformation is the exception - it is asked for a
 * matrix *per presentation time*, which is precisely the hook an animated
 * transform needs.
 *
 * It also replaced ScaleAndRotateTransformation, which can scale but cannot
 * translate - so the editor's position sliders moved a layer in the preview and
 * were then discarded at render time, and every picture-in-picture came out
 * centered.
 *
 * One clip's placement is two effects, at two places in its chain, each with
 * its own clock ([part]):
 *
 *  - the stabilizer's correction belongs to the footage, so it goes on first, in
 *    the frame the camera recorded, keyed by source time;
 *  - what the editor asked for belongs to the picture as it is seen, so it goes
 *    on after the edit's rotation (for a base shot) or after the layer has been
 *    fitted to the canvas (for an overlay) - which is where the preview applies
 *    it, to the view. Keyed by played time.
 *
 * Done as one effect before the rotation, a pan on a clip turned a quarter turn
 * ran up the screen in the file and across it in the preview. Measured in the
 * overlay's own frame rather than the canvas, a PiP placed 45% right of centre
 * landed 14% right of it whenever the overlay's shape differed from the edit's.
 *
 * Both parts see frames before the clip's speed change, so what they are handed
 * is source time; ExportPlan.motionAt converts it for the keyframes.
 */
class ClipTransformEffect(
    private val clip: Clip,
    private val part: ExportPlan.MotionPart
) : MatrixTransformation {

    /**
     * Frames arrive in order and each clip gets its own instance, so the first
     * presentation time seen *is* this clip's origin.
     *
     * Deriving it rather than assuming it is deliberate: Media3 has offered both
     * item-relative and composition-relative presentation times across versions,
     * and an animation anchored to the wrong origin would not fail loudly - it
     * would simply play at the wrong moment, or be over before the clip appears.
     */
    private var originUs = Long.MIN_VALUE

    /** Width over height of the frame this is applied to; the turn is done in its pixels. */
    private var aspect = 1f

    /** Reused rather than allocated: this is called once per frame of the export. */
    private val matrix = Matrix()
    private val values = FloatArray(9)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth > 0 && inputHeight > 0) aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
        val sourceElapsedMs = (presentationTimeUs - originUs) / 1_000L

        val m = ExportPlan.placementMatrix(ExportPlan.motionAt(clip, part, sourceElapsedMs), aspect)
        values[0] = m[0]; values[1] = m[1]; values[2] = m[4]
        values[3] = m[2]; values[4] = m[3]; values[5] = m[5]
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        return matrix
    }

    companion object {
        /** The effect for [part] of [clip]'s motion, or null when that part never moves it. */
        fun of(clip: Clip, part: ExportPlan.MotionPart): ClipTransformEffect? =
            if (ExportPlan.hasMotion(clip, part)) ClipTransformEffect(clip, part) else null
    }
}
