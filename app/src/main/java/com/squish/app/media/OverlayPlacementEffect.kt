package com.squish.app.media

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation

/**
 * Places an overlay layer: scaled down and moved, in one matrix.
 *
 * This replaced ScaleAndRotateTransformation, which can scale but cannot translate
 * - so the editor's "Across" and "Up / down" sliders moved the layer in the preview
 * and were then ignored at render time, and every picture-in-picture came out
 * centred no matter where it had been put.
 *
 * Media3 transformation matrices work in normalised device coordinates, where the
 * frame spans -1 to 1 on both axes and **+Y points up**. The editor's offsets are
 * screen-space, where down is positive, which is why Y is negated here - and here
 * only, so the convention has exactly one place it can go wrong.
 *
 * BUILD RISK: MatrixTransformation is the one new Media3 interface in the app. If
 * the signature differs in the version you resolve, drop this effect from
 * CompositionFactory.overlayEffects and add back
 * `ScaleAndRotateTransformation.Builder().setScale(scale, scale).build()` - layers
 * then render centred, as they did before, and nothing else changes.
 */
class OverlayPlacementEffect(
    private val scale: Float,
    private val offsetXFraction: Float,
    private val offsetYFraction: Float
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix = Matrix().apply {
        postScale(scale, scale)
        postTranslate(offsetXFraction, -offsetYFraction)
    }
}
