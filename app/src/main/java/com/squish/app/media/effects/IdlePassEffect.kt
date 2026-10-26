@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.effects

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor

/**
 * A preview pass that hands a frame straight on, without drawing, when
 * [idle] says it would leave the frame as it is. PassStage says why and
 * holds the routing; the export's chains never use this.
 *
 * [idle] is asked on the GL thread for each frame, with its presentation
 * time, and reads the same live state the pass itself reads - so a slider
 * moved off its identity takes the very next frame through the shader, and
 * one moved back lets the frame after skip it.
 */
class IdlePassEffect(
    private val inner: GlEffect,
    private val idle: (presentationTimeUs: Long) -> Boolean
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        IdlePassProgram(inner.toGlShaderProgram(context, useHdr), idle)
}

private class IdlePassProgram(
    private val inner: GlShaderProgram,
    idle: (Long) -> Boolean
) : GlShaderProgram {

    private var upstream: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var downstream: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var provider: GlObjectsProvider? = null

    private val stage = PassStage<GlTextureInfo>(
        idle = idle,
        innerQueue = { t, pts -> inner.queueInputFrame(checkNotNull(provider), t, pts) },
        innerRelease = { t -> inner.releaseOutputFrame(t) },
        innerFlush = { inner.flush() },
        handOn = { t, pts -> downstream.onOutputFrameAvailable(t, pts) },
        handBack = { t -> upstream.onInputFrameProcessed(t) },
        credit = { upstream.onReadyToAcceptInputFrame() }
    )

    // The wrapped program talks to the same neighbours directly: its credits,
    // its hand-backs and its flushes are the stage's own.
    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        upstream = inputListener
        inner.setInputListener(inputListener)
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        downstream = outputListener
        inner.setOutputListener(outputListener)
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) =
        inner.setErrorListener(executor, errorListener)

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        provider = glObjectsProvider
        stage.queue(inputTexture, presentationTimeUs)
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) = stage.release(outputTexture)

    override fun signalEndOfCurrentInputStream() = inner.signalEndOfCurrentInputStream()

    override fun flush() = stage.flush()

    override fun release() = inner.release()
}
