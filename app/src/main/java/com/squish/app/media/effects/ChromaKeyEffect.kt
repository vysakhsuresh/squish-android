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
import com.squish.app.timeline.ChromaKey
import java.io.IOException

/**
 * Green screen, as a Media3 video effect.
 *
 * This is the app's only custom GL shader, and it is custom out of necessity
 * rather than ambition: every built-in Media3 color effect maps RGB to RGB, and
 * chroma key has to produce **per-pixel alpha**. No combination of Contrast,
 * HslAdjustment, RgbAdjustment or a 3D LUT can cut a hole in a frame, so there is
 * no version of this feature that avoids a shader.
 *
 * The same effect object runs in the preview and in the export - the preview
 * player is handed it through setVideoEffects - so the key you tune is the key
 * that renders, to the pixel.
 *
 * BUILD RISK: this is the only file in the app that touches Media3's shader API
 * (BaseGlShaderProgram, GlProgram, GlUtil). It was written against the 1.5.1
 * sources rather than from memory, but if those signatures differ in the version
 * you resolve, it is self-contained: delete this file and the two `chromaKeyOf`
 * call sites in VideoProcessor/CompositionFactory, and everything else builds
 * exactly as before. See BUILD_NOTES.md.
 *
 * The key is read every frame, like [BackgroundEffect]'s setting, so the preview
 * installs this once per surface and dragging Similarity changes a value instead
 * of stopping the player, rebuilding its pipeline and reloading the file on
 * every tick of the drag. With no key set, frames pass through.
 */
class ChromaKeyEffect(private val key: () -> ChromaKey?) : GlEffect {

    /** A fixed key, for the export. */
    constructor(key: ChromaKey) : this({ key })

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ChromaKeyShaderProgram(context, useHdr, key)
}

private class ChromaKeyShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val key: () -> ChromaKey?
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram

    /** The key the uniforms hold, compared by identity - see MaskEffect. */
    private var loaded: ChromaKey? = null
    private var anyLoaded = false

    init {
        glProgram = try {
            GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        // Draw over the whole normalized device coordinate space, -1 to 1 on both axes.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    private fun load(key: ChromaKey?) {
        if (key == null) {
            // A similarity below zero puts every pixel far outside the key, so both
            // the matte and the spill weight saturate at 1: colour unchanged, fully
            // opaque. This effect sits first in the chain, where every frame
            // arriving is opaque video anyway.
            glProgram.setFloatsUniform("uKeyUV", floatArrayOf(0.5f, 0.5f))
            glProgram.setFloatsUniform("uSimilarity", floatArrayOf(PASS_THROUGH_SIMILARITY))
            glProgram.setFloatsUniform("uSmoothness", floatArrayOf(1f))
            glProgram.setFloatsUniform("uSpill", floatArrayOf(1f))
            return
        }
        glProgram.setFloatsUniform("uKeyUV", key.keyUV())
        glProgram.setFloatsUniform("uSimilarity", floatArrayOf(key.similarity))
        glProgram.setFloatsUniform("uSmoothness", floatArrayOf(key.safeSmoothness))
        glProgram.setFloatsUniform("uSpill", floatArrayOf(key.safeSpill))
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val now = key()
            if (!anyLoaded || now !== loaded) {
                load(now)
                loaded = now
                anyLoaded = true
            }
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.bindAttributesAndUniforms()
            // Four vertices as a triangle strip make the quad.
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
        const val FRAGMENT_SHADER_PATH = "squish_chroma_key_es2.glsl"

        /** Chroma distances are at most about 1.2, so this is further than any pixel can be. */
        const val PASS_THROUGH_SIMILARITY = -10f
    }
}
