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
import com.squish.app.editor.FxParams
import com.squish.app.editor.TimedEffect
import com.squish.app.media.ExportPlan
import java.io.IOException

/**
 * The effects library - shake, punch, glitch, flash and the rest - as one shader
 * pass that reads the effects in play each frame.
 *
 * [effectsNow] is read on every frame rather than fixed when the pipeline is
 * built, so in the preview adding, moving or removing an effect is a value
 * change and never a rebuild. The export hands it a fixed list.
 */
class FxEffect(
    private val effectsNow: () -> List<TimedEffect>,
    /**
     * Whether what comes in carries straight alpha to be put over black here:
     * a preview surface's picture, where a mask's cut is alpha and this pass
     * writes the frame opaque. The export's pass runs on the finished,
     * composited frame and leaves it as it is.
     */
    private val overBlack: Boolean = false,
    /**
     * A softness this pass adds on top of whatever the library asks for, the
     * wider of the two winning: the preview's share of a Defocus join, which
     * the file draws in its own TransitionEffect instead (see
     * SurfaceDraw.blur). Zero in the export, whose pass runs on the composited
     * frame and would soften every layer at once.
     *
     * The wider rather than both, which costs one case: on Android 12 and
     * below, where the library is carried by these chains instead of over the
     * canvas (CanvasFx), a placed Blur lying over a Defocus join reads as one
     * softening here and two in the file. From 13 up the library is on the
     * canvas, this pass carries the join alone, and the two agree.
     */
    private val extraBlur: () -> Float = { 0f },
    /**
     * A transition's warp at the frame's presentation time (EDGE_PLAN F3,
     * TransitionWarp): the surface's join in the preview, the shot's own join
     * in the file, where this pass is put into that shot's chain for it.
     */
    private val warpAt: (Long) -> ExportPlan.Warp = { ExportPlan.Warp.NONE },
    /**
     * Straight alpha out, as it came in, instead of opaque: the file's pass on
     * one shot of a join, whose fit margins and cut-outs the compositor must
     * still see through to the backdrop under them.
     */
    private val keepAlpha: Boolean = false
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FxShaderProgram(context, useHdr, effectsNow, overBlack, extraBlur, warpAt, keepAlpha)
}

private class FxShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effectsNow: () -> List<TimedEffect>,
    private val overBlack: Boolean,
    private val extraBlur: () -> Float,
    private val warpAt: (Long) -> ExportPlan.Warp,
    private val keepAlpha: Boolean
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    // The frame's width over its height, for the stages that draw round things.
    private var aspect = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = if (inputHeight > 0) inputWidth.toFloat() / inputHeight else 1f
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = TransitionWarp.merged(FxParams.at(effectsNow(), presentationTimeUs / 1000L), warpAt(presentationTimeUs))
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uOffset", floatArrayOf(p.offsetX, p.offsetY))
            glProgram.setFloatsUniform("uZoom", floatArrayOf(p.zoom.coerceAtLeast(0.1f)))
            glProgram.setFloatsUniform("uSplit", floatArrayOf(p.split))
            glProgram.setFloatsUniform("uGlitch", floatArrayOf(p.glitch))
            glProgram.setFloatsUniform("uFlash", floatArrayOf(p.flash))
            glProgram.setFloatsUniform("uMono", floatArrayOf(p.mono))
            glProgram.setFloatsUniform("uInvert", floatArrayOf(p.invert))
            glProgram.setFloatsUniform("uScan", floatArrayOf(p.scan))
            glProgram.setFloatsUniform("uNoise", floatArrayOf(p.noise))
            // Held to the ring's own ceiling, as every other place that sets it
            // is (ExportPlan.Draw.shaderUniforms). Unclamped, two Blur effects
            // laid over each other add - and nothing bounded the sum.
            glProgram.setFloatsUniform(
                "uBlur",
                floatArrayOf(maxOf(p.blur, extraBlur()).coerceIn(0f, com.squish.app.media.ExportPlan.MAX_BLUR))
            )
            glProgram.setFloatsUniform("uHue", floatArrayOf(p.hue))
            glProgram.setFloatsUniform("uTime", floatArrayOf(p.timeSec))
            glProgram.setFloatsUniform("uOverBlack", floatArrayOf(if (overBlack) 1f else 0f))
            glProgram.setFloatsUniform("uLensA", p.lensA.toArray())
            glProgram.setFloatsUniform("uLensB", p.lensB.toArray())
            glProgram.setFloatsUniform("uLight", p.light.toArray())
            glProgram.setFloatsUniform("uTone", p.tone.toArray())
            glProgram.setFloatsUniform("uParticles", p.particles.toArray())
            glProgram.setFloatsUniform("uGlitch2", p.glitch2.toArray())
            glProgram.setFloatsUniform("uAspect", floatArrayOf(aspect))
            glProgram.setFloatsUniform("uKeepAlpha", floatArrayOf(if (keepAlpha) 1f else 0f))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        const val FRAGMENT_SHADER_PATH = "squish_fx_es2.glsl"
    }
}
