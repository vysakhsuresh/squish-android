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
import com.squish.app.av.ToneMap
import java.io.IOException

/**
 * The first step of an HDR clip's chain when it is shown or written as SDR:
 * Media3's tone-mapped picture lifted to SDR white (ToneMap.liftMedia3, the
 * shader squish_hdr_lift_es2.glsl). First, so a key, a grade and a mask all
 * work on the picture as it will be seen.
 *
 * [on] is read every frame: a preview surface plays one clip after another
 * through the same chain, and only an HDR one wants this. The export builds a
 * chain per clip and passes a constant. Never on a "Keep HDR" export, whose
 * frames are still HDR here.
 */
class HdrLiftEffect(private val on: () -> Boolean) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        HdrLiftShaderProgram(context, useHdr, on)
}

private class HdrLiftShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val on: () -> Boolean
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

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
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // A pipeline still carrying HDR (Keep HDR) is never lifted: its
            // values are not Media3's SDR output.
            glProgram.setFloatsUniform("uOn", floatArrayOf(if (!useHdr && on()) 1f else 0f))
            glProgram.setFloatsUniform("uTop", floatArrayOf(ToneMap.top.toFloat()))
            glProgram.setFloatsUniform("uGamma", floatArrayOf(ToneMap.MEDIA3_GAMMA.toFloat()))
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
        const val FRAGMENT_SHADER_PATH = "squish_hdr_lift_es2.glsl"
    }
}
