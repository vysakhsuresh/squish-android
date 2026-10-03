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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

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
        LookShaderProgram(context, useHdr) { grade }
}

/**
 * The same shader, reading its grade fresh on every frame.
 *
 * For the preview. Handing a player a new effect list tears down and rebuilds its
 * whole GL pipeline, and a brightness drag asked for that dozens of times a
 * second - the picture froze while the clock kept counting. This effect is put in
 * once and left there; moving a slider only changes what [grade] holds, which the
 * next frame picks up. At the identity grade it passes every pixel through as is.
 */
class LiveLookEffect(private val grade: AtomicReference<Grade>) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LookShaderProgram(context, useHdr) { grade.get() }
}

private class LookShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val gradeNow: () -> Grade
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram

    /** What the uniforms currently hold, so an unchanged grade costs no writes. */
    private var loaded: Grade? = null

    /** The Curves tool's 256-entry table. Made on the first load and reused. */
    private var curveTexId = UNSET

    private fun load(grade: Grade) {
        if (grade == loaded) return
        loaded = grade
        glProgram.setFloatsUniform(
            "uGain",
            floatArrayOf(grade.redScale, grade.greenScale, grade.blueScale)
        )
        glProgram.setFloatsUniform("uBrightness", floatArrayOf(grade.brightness))
        glProgram.setFloatsUniform("uContrast", floatArrayOf(grade.contrast))
        glProgram.setFloatsUniform("uSaturation", floatArrayOf(grade.saturation))
        glProgram.setFloatsUniform("uHighlights", floatArrayOf(grade.highlights))
        glProgram.setFloatsUniform("uShadows", floatArrayOf(grade.shadows))
        glProgram.setFloatsUniform("uHue", floatArrayOf(Math.toRadians(grade.hueDegrees.toDouble()).toFloat()))
        // The bands are eight uniforms rather than one array: GlProgram binds an
        // array uniform as its first element only, so the other seven would
        // never reach the shader.
        glProgram.setFloatsUniform("uHslOn", floatArrayOf(if (grade.hasHsl) 1f else 0f))
        for (i in 0 until HUE_BANDS) glProgram.setFloatsUniform("uHsl$i", grade.bandToFloats(i))
        glProgram.setFloatsUniform("uFade", floatArrayOf(grade.fade))
        glProgram.setFloatsUniform("uShadowTint", grade.tintToFloats(grade.shadowTint))
        glProgram.setFloatsUniform("uHighlightTint", grade.tintToFloats(grade.highlightTint))
        glProgram.setFloatsUniform("uSplit", floatArrayOf(grade.split))
        glProgram.setFloatsUniform("uBloom", floatArrayOf(grade.bloom))
        glProgram.setFloatsUniform("uVignette", floatArrayOf(grade.vignette))
        glProgram.setFloatsUniform("uGrain", floatArrayOf(grade.grain))
        glProgram.setFloatsUniform("uSharpen", floatArrayOf(grade.sharpen))
        glProgram.setFloatsUniform("uCurveOn", floatArrayOf(if (grade.hasCurve) 1f else 0f))
        uploadCurve(grade.curveLut)
    }

    /**
     * The curve's table into its texture. Only on a grade that is not the one
     * already loaded - [load] returns early otherwise - so dragging a point
     * costs one 768-byte upload a frame and nothing else.
     */
    private fun uploadCurve(lut: FloatArray) {
        // The unit this texture is read on, said out loud: otherwise the upload
        // binds to whichever unit the last draw happened to leave active.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (curveTexId == UNSET) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            curveTexId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexId)
            // LINEAR between entries, which is what ToneCurve.sample copies on
            // the CPU; clamped, so black and white read the ends of the table
            // rather than wrapping round to the other one.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexId)
        }
        val bytes = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder())
        for (v in lut) bytes.put((Math.round(v.coerceIn(0f, 1f) * 255f)).toByte())
        bytes.position(0)
        // 256 x 1 RGB: a row of 768 bytes, which the default unpack alignment
        // of 4 divides evenly, so no padding is needed.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
            ToneCurve.LUT_SIZE, 1, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, bytes
        )
    }

    init {
        glProgram = try {
            GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        load(gradeNow())
        glProgram.setFloatsUniform("uTime", floatArrayOf(0f))
        glProgram.setFloatsUniform("uTexel", floatArrayOf(0f, 0f))

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
        // The sharpening taps a fixed share of the frame apart, so the look does
        // not depend on the size the frame happens to be rendered at - the
        // proxy in the preview, the chosen size in the file.
        val step = maxOf(inputWidth, inputHeight) / SHARPEN_TAPS_ACROSS
        glProgram.setFloatsUniform(
            "uTexel",
            floatArrayOf(step / inputWidth.coerceAtLeast(1), step / inputHeight.coerceAtLeast(1))
        )
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            val grade = gradeNow()
            load(grade)
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // Bound on every frame, curve or no curve: a sampler left pointing
            // at nothing is undefined, and on some drivers that is a black frame
            // rather than the ignored read the branch promises.
            glProgram.setSamplerTexIdUniform("uCurve", curveTexId, /* texUnitIndex= */ 1)

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
            if (curveTexId != UNSET) {
                GLES20.glDeleteTextures(1, intArrayOf(curveTexId), 0)
                curveTexId = UNSET
            }
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_look_es2.glsl"

        /** No texture made yet. GL names start at 1, so 0 is free to mean this. */
        const val UNSET = 0

        /** The uHsl0..7 uniforms: one per [HueBand]. */
        val HUE_BANDS = HueBand.entries.size

        /** About two pixels at 1080p: fine enough to read as sharpness, coarse enough not to read as noise. */
        const val SHARPEN_TAPS_ACROSS = 1_000f
    }
}
