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

/**
 * The last step of a layer's chain: straight alpha made premultiplied.
 *
 * Everything upstream - key, background, mask - writes straight alpha because
 * that is what the export's compositor blends; a view composites premultiplied,
 * and reading one as the other adds every cut-away pixel's colour onto the
 * picture behind it.
 *
 * Two places want it, and both are places where nothing downstream will blend
 * the alpha:
 *
 * - **A preview layer**, which the view composites itself.
 * - **The one-sequence export** (`VideoProcessor`, `rolls == null`), which has
 *   no compositor and whose encoder drops alpha: a Cut out mask or a keyed hole
 *   wrote the whole picture there, the shape ignored. Premultiplied, what the
 *   mask or the key hid is black, as the composited path and the preview show
 *   it. This doc used to say "never in an export chain", which that path had
 *   already contradicted.
 *
 * Not on the *composited* export's chain, where the compositor blends straight
 * alpha and this would darken every soft edge a second time.
 */
class PremultiplyEffect(
    /**
     * An overlay's share of a Defocus join, read on every frame: the nine taps
     * that far apart as a fraction of the frame. A base surface takes its
     * softness from the effects pass that ends its chain; a layer's chain ends
     * here instead, because a layer keeps its transparency all the way to the
     * screen - so this is where its softness goes (see SurfaceDraw.blur).
     */
    private val blurNow: () -> Float = { 0f }
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PremultiplyShaderProgram(context, useHdr, blurNow)
}

private class PremultiplyShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val blurNow: () -> Float
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
            glProgram.setFloatsUniform("uBlur", floatArrayOf(blurNow()))
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
        const val FRAGMENT_SHADER_PATH = "squish_premultiply_es2.glsl"
    }
}
