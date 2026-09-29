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
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRules
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/**
 * A clip's own crop, for the export: the window kept of its picture, with the
 * picture mirrored and turned under it (see ClipCrop). One pass, because
 * Media3's Crop and ScaleAndRotateTransformation would each be a pass and,
 * between them, would grow the frame to the turned picture's bounds and then
 * cut a window out of *that* - a different rectangle from the one drawn on
 * the preview. Here each output pixel reads straight from the source frame
 * through CropRules.sourcePoint, the maths the preview's layers invert.
 *
 * The output is the window's size in the source's pixels, so a cropped shot
 * is a smaller picture that the Presentation after it fits into the canvas -
 * as the preview shows it, and as CapCut does it.
 */
class ClipCropEffect(private val crop: ClipCrop) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipCropShaderProgram(context, useHdr, crop)
}

private class ClipCropShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val crop: ClipCrop
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
        val radians = Math.toRadians(crop.straightenDegrees.toDouble())
        glProgram.setFloatsUniform("uOrigin", floatArrayOf(crop.rect.left, crop.rect.top))
        glProgram.setFloatsUniform("uExtent", floatArrayOf(crop.rect.width, crop.rect.height))
        glProgram.setFloatsUniform("uCos", floatArrayOf(cos(radians).toFloat()))
        glProgram.setFloatsUniform("uSin", floatArrayOf(sin(radians).toFloat()))
        glProgram.setFloatsUniform(
            "uFlip",
            floatArrayOf(if (crop.flipHorizontal) -1f else 1f, if (crop.flipVertical) -1f else 1f)
        )
        glProgram.setFloatsUniform("uAspect", floatArrayOf(1f))
        glProgram.setFloatsUniform("uZoom", floatArrayOf(1f))
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
        glProgram.setFloatsUniform("uAspect", floatArrayOf(aspect))
        glProgram.setFloatsUniform("uZoom", floatArrayOf(CropRules.zoomToCover(crop.straightenDegrees, aspect)))
        // Even, as encoders want.
        val w = ((inputWidth * crop.rect.width).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((inputHeight * crop.rect.height).toInt() / 2 * 2).coerceAtLeast(2)
        return Size(w, h)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
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
        const val FRAGMENT_SHADER_PATH = "squish_clip_crop_es2.glsl"
    }
}
