@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Frame blending for slow motion, in the export.
 *
 * Slowing footage stretches its timestamps and invents no frames (SlowMotion),
 * so a shot at a quarter speed reaches the encoder as a frame every 133 ms and
 * the file steps. This puts a frame at every [intervalUs] between each pair the
 * footage has, each one the two mixed by where it sits (FrameBlendPlan): the
 * stepping becomes motion blur, which is the trade "frame blending" has always
 * made. It is not optical flow, and does not claim to be.
 *
 * Sits after the speed change, where the frames carry played time and the gaps
 * are the ones the file will have. Export only: the preview plays the frames
 * the footage has, and says so on the Speed sheet.
 *
 * The one program in the app that is not a [androidx.media3.effect.BaseGlShaderProgram]:
 * that class draws exactly one frame out per frame in, and this draws several.
 * It keeps its own textures - a copy of the last frame in, and a handful to
 * draw into - and hands the chain one input's worth of output before asking
 * for the next.
 */
class FrameBlendEffect(
    private val intervalUs: Long,
    /** Frames out per frame in, at most (FrameBlendPlan.framesPerInput): what the pool is sized to. */
    private val framesPerInput: Int = FrameBlendPlan.MAX_FRAMES_PER_INPUT
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FrameBlendShaderProgram(context, useHdr, intervalUs, framesPerInput.coerceIn(1, FrameBlendPlan.MAX_FRAMES_PER_INPUT))

    /** Nothing to blend into a gap shorter than the interval: identity for the frame rate itself. */
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = intervalUs <= 0L
}

private class FrameBlendShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val intervalUs: Long,
    private val capacity: Int
) : GlShaderProgram {

    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener {}
    private var errorExecutor: Executor = MoreExecutors.directExecutor()

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var width = -1
    private var height = -1

    /** The last frame in, copied: the input texture is the chain's and goes back the moment it is processed. */
    private var held: GlTextureInfo? = null
    private var heldUs = Long.MIN_VALUE
    private var holding = false

    /** Textures drawn into and handed on; back on [free] when the chain releases them. */
    private val outputs = ArrayList<GlTextureInfo>(capacity)
    private val free = ArrayDeque<GlTextureInfo>(capacity)

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
        inputListener = listener
        // One input at a time, taken only with every output back: a slow
        // stretch may want most of them for the one frame.
        if (free.size == outputs.size) listener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(listener: GlShaderProgram.OutputListener) {
        outputListener = listener
    }

    override fun setErrorListener(executor: Executor, listener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        errorListener = listener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            ensureConfigured(glObjectsProvider, inputTexture.width, inputTexture.height)
            val previous = held
            if (holding && previous != null) {
                // Everything but one texture, which the frame itself needs.
                val blends = FrameBlendPlan.between(heldUs, presentationTimeUs, intervalUs, free.size - 1)
                for (blend in blends) {
                    val out = free.removeFirst()
                    draw(out, previous.texId, inputTexture.texId, blend.mix)
                    outputListener.onOutputFrameAvailable(out, blend.timeUs)
                }
            }
            val own = free.removeFirst()
            draw(own, inputTexture.texId, inputTexture.texId, 1f)
            // Kept for the next gap, before the chain takes its texture back.
            val keep = checkNotNull(held)
            draw(keep, inputTexture.texId, inputTexture.texId, 1f)
            heldUs = presentationTimeUs
            holding = true
            inputListener.onInputFrameProcessed(inputTexture)
            outputListener.onOutputFrameAvailable(own, presentationTimeUs)
        } catch (e: VideoFrameProcessingException) {
            fail(e)
        } catch (e: GlUtil.GlException) {
            fail(e)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        // A texture that is not this program's - one from before it joined the
        // chain - is not ours to count.
        if (outputs.none { it === outputTexture } || free.any { it === outputTexture }) return
        free.addLast(outputTexture)
        if (free.size == outputs.size) inputListener.onReadyToAcceptInputFrame()
    }

    override fun signalEndOfCurrentInputStream() {
        // The last frame in has already gone out at its own time; nothing follows
        // it to blend towards. The next stream starts from nothing held.
        holding = false
        outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        free.clear()
        free.addAll(outputs)
        holding = false
        inputListener.onFlush()
        if (outputs.isNotEmpty()) inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            deleteTextures()
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun ensureConfigured(provider: GlObjectsProvider, w: Int, h: Int) {
        if (w == width && h == height && outputs.isNotEmpty()) return
        val wasEmpty = outputs.isEmpty()
        deleteTextures()
        width = w
        height = h
        held = newTexture(provider, w, h)
        // As many as the slowest stretch needs, and fewer if the GPU will not
        // give them: each is a whole frame of the file, and at 4K on a phone
        // that is short of memory the allocation can fail part way. Down to
        // the one texture the frame itself needs, the export still completes
        // - with thinner blends, or none, rather than no file at all.
        var wanted = capacity
        while (outputs.size < wanted) {
            try {
                outputs.add(newTexture(provider, w, h))
            } catch (e: GlUtil.GlException) {
                if (outputs.isEmpty()) throw e
                wanted = outputs.size
            }
        }
        free.addAll(outputs)
        holding = false
        // The first time, the chain was told nothing was ready (no textures
        // yet); it is being handed a frame now, so no extra signal is due.
        if (!wasEmpty) inputListener.onReadyToAcceptInputFrame()
    }

    private fun newTexture(provider: GlObjectsProvider, w: Int, h: Int): GlTextureInfo {
        val texId = GlUtil.createTexture(w, h, useHdr)
        return provider.createBuffersForTexture(texId, w, h)
    }

    private fun deleteTextures() {
        held?.release()
        held = null
        outputs.forEach { it.release() }
        outputs.clear()
        free.clear()
    }

    /** [mix] of texture [b] over [a] into [target]. */
    private fun draw(target: GlTextureInfo, a: Int, b: Int, mix: Float) {
        GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
        GlUtil.clearFocusedBuffers()
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexA", a, 0)
        glProgram.setSamplerTexIdUniform("uTexB", b, 1)
        glProgram.setFloatsUniform("uMix", floatArrayOf(mix))
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun fail(e: Exception) {
        errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e)) }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_blend_es2.glsl"
    }
}
