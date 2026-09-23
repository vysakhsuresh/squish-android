package com.squish.app.media.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.IOException

/**
 * The whole grade in one pass, for looks that need more than a colour transform.
 *
 * Vignette and grain depend on where a pixel is, and bloom depends on its
 * neighbours - none of the three can be written as the per-pixel maths Media3's
 * built-in colour effects do. So the looks that use them come through here, and
 * the shader does the colour work too rather than stacking on top of three more
 * passes.
 *
 * Looks that need none of it never reach this class. [ColorGrade] keeps sending
 * those to the built-in effects, which are hardware-backed and cheaper than
 * anything written by hand.
 */
class LookEffect(private val grade: Grade) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LookShaderProgram(context, useHdr, grade)
}

private class LookShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val grade: Grade
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

        glProgram.setFloatsUniform(
            "uGain",
            floatArrayOf(grade.redScale, grade.greenScale, grade.blueScale)
        )
        glProgram.setFloatsUniform("uContrast", floatArrayOf(grade.contrast))
        glProgram.setFloatsUniform("uSaturation", floatArrayOf(grade.saturation))
        glProgram.setFloatsUniform("uFade", floatArrayOf(grade.fade))
        glProgram.setFloatsUniform("uShadowTint", grade.tintToFloats(grade.shadowTint))
        glProgram.setFloatsUniform("uHighlightTint", grade.tintToFloats(grade.highlightTint))
        glProgram.setFloatsUniform("uSplit", floatArrayOf(grade.split))
        glProgram.setFloatsUniform("uBloom", floatArrayOf(grade.bloom))
        glProgram.setFloatsUniform("uVignette", floatArrayOf(grade.vignette))
        glProgram.setFloatsUniform("uGrain", floatArrayOf(grade.grain))
        glProgram.setFloatsUniform("uTime", floatArrayOf(0f))

        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    /**
     * The vignette needs the frame's shape, which is only known here. Without it a
     * vignette on 16:9 footage is an ellipse squeezed into the corners rather than
     * an even falloff from the middle.
     */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val aspect = if (inputHeight > 0) inputWidth.toFloat() / inputHeight else 1f
        glProgram.setFloatsUniform("uAspect", floatArrayOf(aspect))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)

            // Only when there is grain to move. Still grain does not read as film,
            // it reads as a dirty lens - but an unused uniform write every frame is
            // work for nothing.
            if (grade.grain > 1e-4f) {
                // Wrapped, so a long export never loses precision in a mediump float.
                val seconds = (presentationTimeUs / 1_000L % 10_000L) / 1_000f
                glProgram.setFloatsUniform("uTime", floatArrayOf(seconds))
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
        const val FRAGMENT_SHADER_PATH = "squish_look_es2.glsl"
    }
}
