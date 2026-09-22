package com.squish.app.media

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.Transform
import com.squish.app.timeline.composeTransform

/**
 * Moves a clip's picture - scaled, turned and shifted - and animates it if the
 * clip carries keyframes.
 *
 * This is the whole reason keyframes are possible on this stack. Media3's
 * per-frame effects are static: Contrast, HslAdjustment and AlphaScale are handed
 * one value and keep it. MatrixTransformation is the exception - it is asked for a
 * matrix *per presentation time*, which is precisely the hook an animated
 * transform needs. Scale, position and rotation can therefore move over time using
 * an API the app already relies on, with no custom shader in sight.
 *
 * It also replaced ScaleAndRotateTransformation, which can scale but cannot
 * translate - so the editor's position sliders moved a layer in the preview and
 * were then discarded at render time, and every picture-in-picture came out
 * centred.
 *
 * Media3 matrices work in normalised device coordinates, where the frame spans -1
 * to 1 and **+Y points up**. The editor's offsets are screen-space, where down is
 * positive, which is why Y is negated here - and here only, so the convention has
 * exactly one place it can go wrong.
 *
 * BUILD RISK: MatrixTransformation is the one new Media3 interface in the app. If
 * the signature differs in the version you resolve, see BUILD_NOTES.md for the
 * one-line fallback; layers then render centred and unanimated, as before.
 */
class ClipTransformEffect(
    private val keyframes: List<Keyframe>,
    private val staticTransform: Transform,
    private val stabilizer: List<Keyframe> = emptyList(),
    /** Where in the source file this clip starts, so stabilization lines up after a trim. */
    private val sourceInMs: Long = 0L
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

    /** Reused rather than allocated: this is called once per frame of the export. */
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
        val tInClipMs = (presentationTimeUs - originUs) / 1_000L

        val transform = composeTransform(
            keyframes, staticTransform, stabilizer, tInClipMs, sourceInMs + tInClipMs
        )

        matrix.reset()
        // Post-concatenation, so these read in application order: turn about the
        // centre, scale about the centre, then move.
        matrix.postRotate(transform.rotationDegrees)
        matrix.postScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.offsetXFraction, -transform.offsetYFraction)
        return matrix
    }
}
