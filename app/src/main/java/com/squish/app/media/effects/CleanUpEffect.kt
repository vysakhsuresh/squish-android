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
import com.squish.app.media.video.CleanUp
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Remove flicker and Reduce noise on one clip (EDGE_PLAN F9), CleanUp's
 * arithmetic on the GPU.
 *
 * Reduce noise mixes each pixel towards this pass's own last output where the
 * two agree as noise does, and not where something moved, so it keeps that
 * output: after drawing, the frame is copied into a texture of its own for the
 * next one. Remove flicker measures the frame's brightness first - drawn into
 * 16 x 16 cells, each the average of sixteen taps, and read back, a kilobyte -
 * and hands it to a CleanUp.Deflicker, whose gain the draw applies.
 *
 * In the preview it is on a surface's chain only while the surface's clip asks
 * for it (PreviewEngine), and in the file only on the chain of a clip that does
 * (VideoProcessor): every pass is a full frame of GPU time, and nobody else
 * should pay for this one. HDR frames are passed through untouched, since the
 * copy wants an ordinary texture.
 */
class CleanUpEffect(
    private val denoise: () -> Float,
    private val deflicker: () -> Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CleanUpShaderProgram(context, useHdr, denoise, deflicker)
}

private class CleanUpShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val denoise: () -> Float,
    private val deflicker: () -> Float
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var width = 0
    private var height = 0
    private var history = -1
    private var hasHistory = false
    private var lastUs = Long.MIN_VALUE
    private var measureTex = -1
    private var measureFbo = -1
    private val cells = ByteBuffer.allocateDirect(CELLS * CELLS * 4).order(ByteOrder.nativeOrder())
    private var flicker: CleanUp.Deflicker? = null
    private var flickerStrength = -1f

    init {
        glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            width = inputWidth; height = inputHeight
            releaseTextures()
            hasHistory = false
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // A jump back, or a gap of more than a few frames, is a seek: the last output is not this one's past.
            if (lastUs == Long.MIN_VALUE || presentationTimeUs < lastUs || presentationTimeUs - lastUs > GAP_US) {
                hasHistory = false
                flicker = null
            }
            lastUs = presentationTimeUs
            val strength = if (useHdr) 0f else denoise().coerceIn(0f, 1f)
            val flickerAt = if (useHdr) 0f else deflicker().coerceIn(0f, 1f)
            var gain = 1f
            if (flickerAt > 0f) {
                if (flicker == null || flickerStrength != flickerAt) { flicker = CleanUp.Deflicker(flickerAt); flickerStrength = flickerAt }
                measure(inputTexId)?.let { mean -> gain = flicker!!.gain(mean, presentationTimeUs) }
            }
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            ensureHistory()
            glProgram.setSamplerTexIdUniform("uHistory", history, 1)
            glProgram.setFloatsUniform("uMode", floatArrayOf(0f))
            glProgram.setFloatsUniform("uGain", floatArrayOf(gain))
            glProgram.setFloatsUniform("uDenoise", floatArrayOf(if (hasHistory) strength else 0f))
            glProgram.setFloatsUniform("uRule", floatArrayOf(CleanUp.QUIET, CleanUp.MOVED, CleanUp.MAX_KEEP))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (strength > 0f) {
                // What was just drawn is the next frame's past: copied from the output, which is still bound.
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, history)
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
                GlUtil.checkGlError()
                hasHistory = true
            } else hasHistory = false
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** The frame's mean brightness, 0..1, from 16 x 16 cells read back; the output framebuffer is put back after. */
    private fun measure(inputTexId: Int): Float? {
        val fbo = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        return try {
            if (measureTex < 0) {
                measureTex = GlUtil.createTexture(CELLS, CELLS, false)
                measureFbo = GlUtil.createFboForTexture(measureTex)
            }
            GlUtil.focusFramebufferUsingCurrentContext(measureFbo, CELLS, CELLS)
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            ensureHistory()
            glProgram.setSamplerTexIdUniform("uHistory", history, 1)
            glProgram.setFloatsUniform("uMode", floatArrayOf(1f))
            glProgram.setFloatsUniform("uGain", floatArrayOf(1f))
            glProgram.setFloatsUniform("uDenoise", floatArrayOf(0f))
            glProgram.setFloatsUniform("uRule", floatArrayOf(CleanUp.QUIET, CleanUp.MOVED, CleanUp.MAX_KEEP))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cells.rewind()
            GLES20.glReadPixels(0, 0, CELLS, CELLS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, cells)
            GlUtil.checkGlError()
            var sum = 0.0
            for (i in 0 until CELLS * CELLS) {
                val r = cells.get(i * 4).toInt() and 0xFF
                val g = cells.get(i * 4 + 1).toInt() and 0xFF
                val b = cells.get(i * 4 + 2).toInt() and 0xFF
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }
            (sum / (CELLS * CELLS) / 255.0).toFloat()
        } catch (e: GlUtil.GlException) {
            null
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }
    }

    private fun ensureHistory() {
        if (history < 0 && width > 0 && height > 0) {
            history = GlUtil.createTexture(width, height, false)
            hasHistory = false
        }
    }

    private fun releaseTextures() {
        runCatching { if (history >= 0) GlUtil.deleteTexture(history) }
        history = -1
    }

    override fun release() {
        super.release()
        try {
            releaseTextures()
            if (measureFbo >= 0) GlUtil.deleteFbo(measureFbo)
            if (measureTex >= 0) GlUtil.deleteTexture(measureTex)
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_cleanup_es2.glsl"
        const val CELLS = 16
        const val GAP_US = 200_000L
    }
}
