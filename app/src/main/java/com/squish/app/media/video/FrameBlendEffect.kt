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

    /**
     * The in-between frames moved along the motion (FlowInterp, EDGE_PLAN F1).
     * Null where it could not be built, and for HDR frames, whose float
     * textures this does not read back: those blend, as every frame used to.
     */
    private val flowProgram: FlowShader? = if (useHdr) null else runCatching { FlowShader(context) }.getOrNull()

    /** Each frame in, drawn small, to measure the motion on; and the last frame's luma. */
    private var probe: GlTextureInfo? = null
    private var probeBytes: java.nio.ByteBuffer? = null
    private var heldLuma: LumaFrame? = null
    private var fieldTexId = 0
    private var trustTexId = 0
    private var secondTexId = 0

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
            val luma = if (flowProgram != null) readLuma(glObjectsProvider, inputTexture) else null
            if (holding && previous != null) {
                // Everything but one texture, which the frame itself needs.
                val blends = FrameBlendPlan.between(heldUs, presentationTimeUs, intervalUs, free.size - 1)
                // Moved along the motion where the field holds enough of the
                // picture; a cut, a flash or a blur blends, as it always did.
                val field = if (blends.isNotEmpty() && luma != null) heldLuma?.let { FlowInterp.field(it, luma) } else null
                val flow = field != null && FlowInterp.usesFlow(field) && upload(field)
                for (blend in blends) {
                    val out = free.removeFirst()
                    if (flow) checkNotNull(flowProgram).draw(out, previous.texId, inputTexture.texId, blend.mix, field!!, fieldTexId, trustTexId, secondTexId, luma!!)
                    else draw(out, previous.texId, inputTexture.texId, blend.mix)
                    outputListener.onOutputFrameAvailable(out, blend.timeUs)
                }
            }
            val own = free.removeFirst()
            draw(own, inputTexture.texId, inputTexture.texId, 1f)
            // Kept for the next gap, before the chain takes its texture back.
            val keep = checkNotNull(held)
            draw(keep, inputTexture.texId, inputTexture.texId, 1f)
            heldUs = presentationTimeUs
            heldLuma = luma
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
        heldLuma = null
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
            probe?.release()
            probe = null
            if (fieldTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(fieldTexId), 0)
            if (trustTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(trustTexId), 0)
            if (secondTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(secondTexId), 0)
            fieldTexId = 0
            trustTexId = 0
            secondTexId = 0
            flowProgram?.delete()
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

    /**
     * [input] drawn at FlowInterp's analysis width and read back as luma. Null
     * when it cannot be read; that pair then blends.
     */
    private fun readLuma(provider: GlObjectsProvider, input: GlTextureInfo): LumaFrame? = try {
        val w = FlowInterp.ANALYSIS_WIDTH.coerceAtMost(input.width)
        val h = (w.toLong() * input.height / input.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        var target = probe
        if (target == null || target.width != w || target.height != h) {
            target?.release()
            target = provider.createBuffersForTexture(GlUtil.createTexture(w, h, false), w, h)
            probe = target
            probeBytes = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        }
        draw(target, input.texId, input.texId, 1f)
        val bytes = checkNotNull(probeBytes)
        bytes.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        val luma = FloatArray(w * h)
        for (k in 0 until w * h) {
            val r = bytes.get(k * 4).toInt() and 0xFF
            val g = bytes.get(k * 4 + 1).toInt() and 0xFF
            val b = bytes.get(k * 4 + 2).toInt() and 0xFF
            luma[k] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        LumaFrame(w, h, luma)
    } catch (e: GlUtil.GlException) {
        null
    }

    /** A texture for a few dozen texels, clamped at its edges, filtered by [filter]. */
    private fun smallTexture(filter: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun direct(bytes: ByteArray): java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder()).apply { put(bytes); flip() }

    /** The field into its texture, a texel a block (FlowInterp.pack). False when it could not be. */
    private fun upload(field: FlowInterp.Field): Boolean = try {
        // Nearest for the vectors: a texel is sixteen bits split over two
        // channels, which filtering between texels would mix into nonsense.
        // Linear for the trust, blended between blocks as FlowInterp.trustAt does.
        if (fieldTexId == 0) fieldTexId = smallTexture(GLES20.GL_NEAREST)
        if (trustTexId == 0) trustTexId = smallTexture(GLES20.GL_LINEAR)
        if (secondTexId == 0) secondTexId = smallTexture(GLES20.GL_NEAREST)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fieldTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, field.cols, field.rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(FlowInterp.pack(field)))
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, trustTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, field.cols, field.rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(FlowInterp.packTrust(field)))
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, secondTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, field.cols, field.rows, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct(FlowInterp.packSecond(field)))
        GlUtil.checkGlError()
        true
    } catch (e: GlUtil.GlException) {
        false
    }

    private fun fail(e: Exception) {
        errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e)) }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_blend_es2.glsl"
    }
}
