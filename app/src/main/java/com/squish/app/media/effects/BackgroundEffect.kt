@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.effects

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.squish.app.media.video.PersonMasks
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Replaces everything behind the person: blurs it, paints it one colour, or cuts
 * it out. The person comes from masks [com.squish.app.media.video.Segmenter]
 * worked out ahead of time, so each frame only uploads a 128 px picture.
 *
 * The setting is read every frame, the way [LiveLookEffect] reads its grade, so
 * the preview installs this once and never rebuilds for it. A rebuild under a
 * loaded player froze the picture; with nothing set, frames pass straight through.
 *
 * Time follows [MaskEffect]'s rules: the preview's clock is the source file's,
 * the export's starts at the clip.
 */
class BackgroundEffect(
    private val background: () -> BackgroundRemoval?,
    private val sourceInMs: () -> Long = { 0L },
    private val timesAreSourceTime: Boolean = false
) : GlEffect {

    /** A fixed setting, for the export. */
    constructor(background: BackgroundRemoval, sourceInMs: Long) :
        this({ background }, { sourceInMs }, timesAreSourceTime = false)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BackgroundShaderProgram(context, useHdr, background, sourceInMs, timesAreSourceTime)
}

private class BackgroundShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val background: () -> BackgroundRemoval?,
    private val sourceInMs: () -> Long,
    private val timesAreSourceTime: Boolean
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private val maskTexture: Int
    private var masks: PersonMasks? = null
    private var masksPath: String? = null
    private var uploaded = -1
    private var originUs = Long.MIN_VALUE

    init {
        glProgram = try {
            GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setIntUniform("uFill", FILL_NONE)
        glProgram.setFloatsUniform("uColour", floatArrayOf(0f, 0f, 0f))
        glProgram.setFloatsUniform("uTexel", floatArrayOf(0f, 0f))
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        maskTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Until the first mask arrives, and for good if the masks are missing: all
        // person, so a lost mask file leaves the clip as it was instead of blank.
        uploadMask(1, 1, byteArrayOf(-1))
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // Blur taps a fixed share of the frame apart, so the look does not depend
        // on the resolution it happens to be rendered at.
        val step = max(inputWidth, inputHeight) / 400f
        glProgram.setFloatsUniform(
            "uTexel",
            floatArrayOf(step / inputWidth.coerceAtLeast(1), step / inputHeight.coerceAtLeast(1))
        )
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val bg = background()
            val m = bg?.let { masksFor(it.maskFile) }
            if (bg == null || m == null) {
                glProgram.setIntUniform("uFill", FILL_NONE)
            } else {
                glProgram.setIntUniform(
                    "uFill",
                    when (bg.fill) {
                        BackgroundFill.Blur -> 0
                        BackgroundFill.Colour -> 1
                        BackgroundFill.Remove -> 2
                    }
                )
                val c = bg.colorArgb
                glProgram.setFloatsUniform(
                    "uColour",
                    floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
                )
                if (originUs == Long.MIN_VALUE) originUs = presentationTimeUs
                val sourceMs = if (timesAreSourceTime) presentationTimeUs / 1_000L
                else sourceInMs() + (presentationTimeUs - originUs) / 1_000L
                val index = m.indexAt(sourceMs)
                if (index >= 0 && index != uploaded) {
                    uploadMask(m.width, m.height, m.masks[index])
                    uploaded = index
                }
            }
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setSamplerTexIdUniform("uMask", maskTexture, /* texUnitIndex= */ 1)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Loaded once per file; a clip analysed again gets a new file, so a new load. */
    private fun masksFor(path: String): PersonMasks? {
        if (path != masksPath) {
            masksPath = path
            masks = PersonMasks.load(path)
            uploaded = -1
        }
        return masks
    }

    private fun uploadMask(width: Int, height: Int, bytes: ByteArray) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
        )
    }

    override fun release() {
        super.release()
        try {
            GLES20.glDeleteTextures(1, intArrayOf(maskTexture), 0)
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER_PATH = "squish_vertex_copy_es2.glsl"
        const val FRAGMENT_SHADER_PATH = "squish_background_es2.glsl"
        const val FILL_NONE = 3
    }
}
