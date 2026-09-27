@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.squish.app.timeline.Mask
import java.io.IOException

/**
 * Restricts a clip to a shape.
 *
 * A second alpha shader rather than a branch inside the chroma key one, so the two
 * chain: keying writes a matte, masking multiplies into whatever alpha arrived. A
 * clip can therefore be keyed *and* masked and neither overwrites the other's work.
 *
 * Like the key, the same effect runs in the preview through setVideoEffects, so
 * the shape you drag is the shape that renders.
 */
class MaskEffect(
    private val mask: Mask,
    /** Where in the source file the clip starts, so a tracked shape lines up after a trim. */
    private val sourceInMs: Long = 0L,
    /**
     * True when presentation times already *are* source time, which is the case in
     * the preview: the player holds the whole file, so its clock is the file's clock.
     * The export normalizes instead, latching its first frame as the clip's origin.
     *
     * Getting this wrong does not fail loudly - the shape simply follows the object
     * at the wrong moment, or races ahead of it.
     */
    private val timesAreSourceTime: Boolean = false
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MaskShaderProgram(context, useHdr, mask, sourceInMs, timesAreSourceTime)
}

private class MaskShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val mask: Mask,
    private val sourceInMs: Long,
    private val timesAreSourceTime: Boolean
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram

    init {
        glProgram = try {
            GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        glProgram.setFloatsUniform("uShape", floatArrayOf(mask.shapeIndex))
        glProgram.setFloatsUniform(
            "uCenter",
            floatArrayOf(mask.centerXFraction, mask.centerYFraction)
        )
        glProgram.setFloatsUniform(
            "uHalfSize",
            floatArrayOf(mask.widthFraction, mask.heightFraction)
        )
        glProgram.setFloatsUniform(
            "uRotation",
            floatArrayOf(Math.toRadians(mask.rotationDegrees.toDouble()).toFloat())
        )
        glProgram.setFloatsUniform("uFeather", floatArrayOf(mask.safeFeather))
        glProgram.setFloatsUniform("uCornerRadius", floatArrayOf(mask.safeCornerRadius))
        glProgram.setFloatsUniform("uInvert", floatArrayOf(if (mask.inverted) 1f else 0f))
        glProgram.setFloatsUniform("uMode", floatArrayOf(mask.modeIndex))
        glProgram.setFloatsUniform("uPixelSize", floatArrayOf(mask.pixelSize))
        glProgram.setFloatsUniform("uBlurRadius", floatArrayOf(mask.blurRadius))

        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    /**
     * The frame's shape is only known here, and the mask needs it: rotation and
     * corner rounding happen in pixel-isotropic units, so a turned rectangle stays
     * a rectangle instead of shearing into a rhombus on anything but a square frame.
     */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val aspect = if (inputHeight > 0) inputWidth.toFloat() / inputHeight else 1f
        glProgram.setFloatsUniform("uAspect", floatArrayOf(aspect))
        return Size(inputWidth, inputHeight)
    }

    /** Latched on the first frame when presentation times are not already source time. */
    private var originUs = Long.MIN_VALUE

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)

            // Re-aimed every frame when the shape is following something. A static
            // mask sets this once in the constructor and never touches it again.
            if (mask.track != null) {
                if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
                val sourceMs = if (timesAreSourceTime) presentationTimeUs / 1_000L
                else sourceInMs + (presentationTimeUs - originUs) / 1_000L
                val (x, y) = mask.centerAt(sourceMs)
                glProgram.setFloatsUniform("uCenter", floatArrayOf(x, y))
            }

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_mask_es2.glsl"
    }
}
