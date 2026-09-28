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
import com.squish.app.media.ExportPlan
import com.squish.app.timeline.Clip
import java.io.IOException

/**
 * A base shot's share of the transitions it takes part in - faded, slid or cut
 * away, frame by frame - drawn on the shot itself before the compositor stacks
 * the rolls.
 *
 * Media3's compositor can be asked for a per-input opacity at each moment, but
 * which moment it asks about, and whether a gap in a roll is ever asked about at
 * all, are details of one implementation that nothing documents. Drawing the
 * answer into the shot's own pixels leaves the compositor with one job it does
 * document: laying straight-alpha layers over each other, first input on top.
 *
 * Goes last in the chain, after the speed change and on the finished canvas, so
 * the frames it sees carry played time and a slide moves the picture by a
 * fraction of the frame the file is written at. [rolls] need only hold the
 * clips that can share the screen with [clip] (ExportPlan.neighbourhood).
 */
class TransitionEffect(
    private val clip: Clip,
    private val rolls: List<List<Clip>>
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TransitionShaderProgram(context, useHdr, clip, rolls)
}

private class TransitionShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val clip: Clip,
    private val rolls: List<List<Clip>>
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    /** Latched on the first frame: see ClipTransformEffect for why it is derived, not assumed. */
    private var originUs = Long.MIN_VALUE

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
            if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
            val at = ExportPlan.timelineUs(clip, presentationTimeUs - originUs)
            val draw = ExportPlan.drawAt(rolls, clip, at)
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uAlpha", floatArrayOf(draw.alpha.coerceIn(0f, 1f)))
            glProgram.setFloatsUniform("uShift", floatArrayOf(draw.shiftX))
            glProgram.setFloatsUniform("uKeep", floatArrayOf(draw.keepFrom, draw.keepTo))
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
        const val FRAGMENT_SHADER_PATH = "squish_transition_es2.glsl"
    }
}
