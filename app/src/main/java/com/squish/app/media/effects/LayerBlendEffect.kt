@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.squish.app.timeline.LayerBlend
import java.io.IOException

/**
 * A still blended onto the finished picture.
 *
 * This runs on the **composition's** own output, after every layer has been
 * put together - which is the one place in a Media3 export where the picture
 * under the layer exists as a texture we can read. Media3's compositor is where
 * a *video* overlay meets the base, and it blends source-over with no way in
 * (see docs/COMPETITORS.md, G1), so only a still can be blended, and only here.
 *
 * [when] decides, from the composition's clock in milliseconds, whether the
 * still is showing and where: null leaves the frame alone, so one effect can
 * sit on the whole export and only do something over its own stretch.
 */
class LayerBlendEffect(
    private val bitmap: Bitmap,
    private val mode: LayerBlend,
    /** The lookup matrix and the layer's opacity at a moment, or null when it is not showing. */
    private val `when`: (Long) -> Placement?
) : GlEffect {

    /** Where the still sits on the frame at one moment, and how opaque it is. */
    data class Placement(val lookup: FloatArray, val alpha: Float) {
        override fun equals(other: Any?): Boolean =
            other is Placement && other.alpha == alpha && other.lookup.contentEquals(lookup)
        override fun hashCode(): Int = 31 * lookup.contentHashCode() + alpha.hashCode()
    }

    companion object {
        /**
         * The still stretched over the whole frame.
         *
         * A blended still is a *treatment*, not a layer you place: light leaks,
         * dust, grain, bokeh and film burns are all full-frame, and that is the
         * only placement the preview and the file can both be sure of - the
         * preview blends inside the shot's own surface, where the output frame
         * is not yet known. The sheet says so, and Placement is left to the
         * ordinary, unblended overlays.
         */
        val WHOLE_FRAME = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, useHdr, { bitmap }, { mode }, `when`)
}

/**
 * The same shader for the preview, reading its still and its mode live.
 *
 * Put on a base surface once and left there: handing a player a new effect list
 * rebuilds its whole GL pipeline, so picking a blend mode has to change what the
 * effect reads rather than which effects there are. [still] returning null
 * leaves every frame alone.
 */
class LiveLayerBlendEffect(
    private val still: () -> Bitmap?,
    private val mode: () -> LayerBlend,
    private val `when`: (Long) -> LayerBlendEffect.Placement?
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, useHdr, still, mode, `when`)
}

private class Program(
    context: Context,
    useHdr: Boolean,
    private val bitmapNow: () -> Bitmap?,
    private val modeNow: () -> LayerBlend,
    private val placementAt: (Long) -> LayerBlendEffect.Placement?
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var layerTexId = UNSET
    private var loadedBitmap: Bitmap? = null

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

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            val bitmap = bitmapNow()
            uploadLayer(bitmap)
            glProgram.setFloatsUniform("uMode", floatArrayOf(modeNow().ordinal.toFloat()))
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setSamplerTexIdUniform("uLayer", if (layerTexId == UNSET) inputTexId else layerTexId, /* texUnitIndex= */ 1)

            val placement = if (bitmap == null) null else placementAt(presentationTimeUs / 1_000L)
            if (placement == null) {
                // Not showing: a matrix that puts every pixel outside the layer,
                // which the shader reads as "leave the picture alone". Cheaper
                // and simpler than tearing the effect in and out of the chain.
                glProgram.setFloatsUniform("uLayerMatrix", OFF_FRAME)
                glProgram.setFloatsUniform("uLayerAlpha", floatArrayOf(0f))
            } else {
                glProgram.setFloatsUniform("uLayerMatrix", placement.lookup)
                glProgram.setFloatsUniform("uLayerAlpha", floatArrayOf(placement.alpha))
            }

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun uploadLayer(bitmap: Bitmap?) {
        if (bitmap == null || bitmap === loadedBitmap) return
        loadedBitmap = bitmap
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (layerTexId == UNSET) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            layerTexId = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Clamped: the shader already refuses anything outside 0..1, and a wrap
        // would put the far edge of the still against the near one if it did not.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    override fun release() {
        super.release()
        try {
            if (layerTexId != UNSET) {
                GLES20.glDeleteTextures(1, intArrayOf(layerTexId), 0)
                layerTexId = UNSET
                loadedBitmap = null
            }
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_layer_blend_es2.glsl"
        const val UNSET = 0

        /** Every point maps far outside 0..1, so nothing of the layer is drawn. */
        val OFF_FRAME = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 9f, 9f, 1f)
    }
}
