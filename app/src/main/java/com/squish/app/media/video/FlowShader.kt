@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * The program that draws an in-between frame moved along the motion
 * (squish_flow_es2.glsl, FlowInterp's twin), for FrameBlendEffect. A file of
 * its own so the shader and the uniforms set on it sit together, where
 * tools/jvm/ShaderUniformChecks.kt reads them.
 */
internal class FlowShader(context: Context) {

    private val program = GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    /** The frame [mix] of the way from [a] to [b], moved along [field] (in [fieldTexId]), measured on [analysis]'s grid. */
    fun draw(target: GlTextureInfo, a: Int, b: Int, mix: Float, field: FlowInterp.Field, fieldTexId: Int, trustTexId: Int, secondTexId: Int, analysis: LumaFrame) {
        val aw = analysis.width.toFloat()
        val ah = analysis.height.toFloat()
        GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
        GlUtil.clearFocusedBuffers()
        program.use()
        program.setSamplerTexIdUniform("uTexA", a, 0)
        program.setSamplerTexIdUniform("uTexB", b, 1)
        program.setSamplerTexIdUniform("uField", fieldTexId, 2)
        program.setSamplerTexIdUniform("uTrust", trustTexId, 3)
        program.setSamplerTexIdUniform("uField2", secondTexId, 4)
        program.setFloatsUniform("uMix", floatArrayOf(mix))
        program.setFloatsUniform("uFieldSize", floatArrayOf(field.cols.toFloat(), field.rows.toFloat()))
        program.setFloatsUniform("uCellUv", floatArrayOf(field.cell / aw, field.cell / ah))
        program.setFloatsUniform("uRangeUv", floatArrayOf(FlowInterp.RANGE / aw, FlowInterp.RANGE / ah))
        program.setFloatsUniform("uPatchUv", floatArrayOf(FlowInterp.PATCH[1].first / aw, FlowInterp.PATCH[1].first / ah))
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    fun delete() = program.delete()

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_flow_es2.glsl"
    }
}
