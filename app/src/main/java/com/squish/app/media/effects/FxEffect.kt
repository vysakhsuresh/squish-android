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
import java.io.IOException

/**
 * The effects library - shake, punch, glitch, flash and the rest - as one shader
 * pass that reads the effects in play each frame.
 *
 * [effectsNow] is read on every frame rather than fixed when the pipeline is
 * built, so in the preview adding, moving or removing an effect is a value
 * change and never a rebuild. The export hands it a fixed list.
 */
class FxEffect(private val effectsNow: () -> List<TimedEffect>) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FxShaderProgram(context, useHdr, effectsNow)
}

private class FxShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effectsNow: () -> List<TimedEffect>
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

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = FxParams.at(effectsNow(), presentationTimeUs / 1000L)
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
            glProgram.setFloatsUniform("uBlur", floatArrayOf(p.blur))
            glProgram.setFloatsUniform("uHue", floatArrayOf(p.hue))
            glProgram.setFloatsUniform("uTime", floatArrayOf(p.timeSec))
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
