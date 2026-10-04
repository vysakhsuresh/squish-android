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
 * The shape is read every frame, the way [BackgroundEffect] reads its setting, so
 * the preview installs this once per surface and a slider moves a value rather
 * than rebuilding the player's pipeline. Rebuilding on every tick of a Feather or
 * Width drag stopped and reloaded the file thirty times a second, which is a
 * frozen picture with extra steps. With no shape set, frames pass through.
 */
class MaskEffect(
    private val mask: () -> Mask?,
    /** Where in the source file the clip starts, so a tracked shape lines up after a trim. */
    private val sourceInMs: () -> Long = { 0L },
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

    /** A fixed shape, for the export. */
    constructor(mask: Mask, sourceInMs: Long = 0L, timesAreSourceTime: Boolean = false) :
        this({ mask }, { sourceInMs }, timesAreSourceTime)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MaskShaderProgram(context, useHdr, mask, sourceInMs, timesAreSourceTime)
}

private class MaskShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val mask: () -> Mask?,
    private val sourceInMs: () -> Long,
    private val timesAreSourceTime: Boolean
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram

    /**
     * The shape the uniforms were last loaded from. Compared by identity: a slider
     * produces a new Mask per value, and an unchanged one is the same object, so
     * a shape sitting still costs one reference check a frame.
     */
    private var loaded: Mask? = null
    private var anyLoaded = false

    init {
        glProgram = try {
            GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    private fun load(mask: Mask?) {
        if (mask == null) {
            // A rectangle far larger than the frame, cut-out mode, not inverted:
            // every pixel is inside it, so the matte is 1 everywhere and the frame
            // passes through untouched.
            glProgram.setFloatsUniform("uShape", floatArrayOf(0f))
            glProgram.setFloatsUniform("uCenter", floatArrayOf(0f, 0f))
            glProgram.setFloatsUniform("uHalfSize", floatArrayOf(PASS_THROUGH_HALF_SIZE, PASS_THROUGH_HALF_SIZE))
            glProgram.setFloatsUniform("uRotation", floatArrayOf(0f))
            glProgram.setFloatsUniform("uFeather", floatArrayOf(0.001f))
            glProgram.setFloatsUniform("uCornerRadius", floatArrayOf(0f))
            glProgram.setFloatsUniform("uInvert", floatArrayOf(0f))
            glProgram.setFloatsUniform("uMode", floatArrayOf(0f))
            glProgram.setFloatsUniform("uPixelSize", floatArrayOf(0.015f))
            glProgram.setFloatsUniform("uBlurRadius", floatArrayOf(0.015f))
            return
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

    /** The moment of the source this frame is, which both the track and the keys are written in. */
    private fun sourceAt(presentationTimeUs: Long): Long {
        if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
        return if (timesAreSourceTime) presentationTimeUs / 1_000L
        else sourceInMs() + (presentationTimeUs - originUs) / 1_000L
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val now = mask()
            // A keyed shape is a different shape every frame, so the identity
            // check cannot stand in for it: the uniforms are reloaded from the
            // shape at this moment, which is what makes a circle grow in the
            // file as it does on the preview.
            val keyedNow = if (now?.isKeyed == true) now.at(sourceAt(presentationTimeUs)) else null
            if (keyedNow != null) {
                load(keyedNow)
                loaded = now
                anyLoaded = true
            } else if (!anyLoaded || now !== loaded) {
                load(now)
                loaded = now
                anyLoaded = true
            }

            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)

            // Re-aimed every frame when the shape is following something. A static
            // mask loads its centre once and never touches it again.
            if (now?.track != null) {
                val (x, y) = now.centerAt(sourceAt(presentationTimeUs))
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

        /** In frame fractions: a thousand frames wide, so nothing is ever outside it. */
        const val PASS_THROUGH_HALF_SIZE = 1_000f
    }
}
