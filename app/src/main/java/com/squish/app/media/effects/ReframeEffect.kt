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
 * A crop to [ratio] whose window follows the subject - auto-reframe, for the
 * export.
 *
 * The output is the same size the ordinary centred crop would give, so nothing
 * downstream can tell the two apart; only where the window sits changes, frame by
 * frame. The window is kept inside the picture, so following a subject to the
 * edge stops at the edge rather than showing black.
 *
 * One instance per clip, placed before the clip's speed change so its frames
 * carry source time: the first frame it sees is [sourceInMs] into the file, and
 * each later one is as far past that as its timestamp is past the first. The
 * subject's place is asked for by that file time through [focusAt], as
 * top-down fractions of the frame this effect is applied to - the canvas the
 * shot has been cropped, turned and placed on, not the frame the track was
 * measured on (FrameRules.subjectOnCanvas carries it across). Null centres the
 * window.
 *
 * It used to be one offset for the whole edit - the head clip's - added to
 * whatever timestamp arrived. Every clip after the first, every trimmed or
 * reordered one and anything retimed then looked the track up at the wrong
 * moment, and the window followed where the subject had been somewhere else.
 */
class ReframeEffect(
    private val ratio: Float,
    private val sourceInMs: Long = 0L,
    private val focusAt: (sourceMs: Long) -> Pair<Float, Float>?
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ReframeShaderProgram(context, useHdr, ratio, sourceInMs, focusAt)

    companion object {
        /** The kept window's size, as fractions of a frame [aspect] wide per unit tall. */
        fun windowFraction(ratio: Float, aspect: Float): Pair<Float, Float> =
            if (ratio < aspect) (ratio / aspect) to 1f else 1f to (aspect / ratio)

        /** The window's top-left, in top-down fractions, centred on the subject but kept inside the frame. */
        fun windowOrigin(cx: Float, cy: Float, ww: Float, wh: Float): Pair<Float, Float> =
            (cx - ww / 2f).coerceIn(0f, 1f - ww) to (cy - wh / 2f).coerceIn(0f, 1f - wh)
    }
}

private class ReframeShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val ratio: Float,
    private val sourceInMs: Long,
    private val focusAt: (Long) -> Pair<Float, Float>?
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    /** Latched on the first frame, which is the clip's first. */
    private var originUs = Long.MIN_VALUE

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var ww = 1f
    private var wh = 1f

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
        val (fw, fh) = ReframeEffect.windowFraction(ratio, aspect)
        ww = fw
        wh = fh
        // Even, as encoders want.
        val w = ((inputWidth * fw).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((inputHeight * fh).toInt() / 2 * 2).coerceAtLeast(2)
        return Size(w, h)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
            val at = sourceInMs + (presentationTimeUs - originUs) / 1000L
            val focus = focusAt(at)
            val (left, top) = ReframeEffect.windowOrigin(focus?.first ?: 0.5f, focus?.second ?: 0.5f, ww, wh)
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            // GL's texture origin is the bottom-left; the track's is the top-left.
            glProgram.setFloatsUniform("uOrigin", floatArrayOf(left, 1f - top - wh))
            glProgram.setFloatsUniform("uExtent", floatArrayOf(ww, wh))
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
        const val FRAGMENT_SHADER_PATH = "squish_reframe_es2.glsl"
    }
}
