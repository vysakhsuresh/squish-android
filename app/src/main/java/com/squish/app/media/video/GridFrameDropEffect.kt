@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.video

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram

/**
 * The export's frame rate, kept on a grid (FrameGrid) rather than by Media3's
 * FrameDropEffect, which wrote a 1.5x shot at 22.5 fps. Frames are handed on
 * untouched - nothing is drawn - and the ones between slots are given straight
 * back to the chain.
 */
class GridFrameDropEffect(private val fps: Float, private val onGrid: Boolean = false) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = GridFrameDropProgram(FrameGrid(fps, onGrid))
}

private class GridFrameDropProgram(private val grid: FrameGrid) : PassthroughShaderProgram() {

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        if (grid.keep(presentationTimeUs)) {
            // On its slot, not its own stamp: dropping alone left the file at the
            // right average rate with uneven frames (33/67 ms for 24 from 30).
            super.queueInputFrame(glObjectsProvider, inputTexture, grid.slotUs)
        } else {
            // Dropped: back to the chain at once, and the next one asked for,
            // as the pass-through does when a kept frame is released.
            inputListener.onInputFrameProcessed(inputTexture)
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    override fun signalEndOfCurrentInputStream() {
        grid.reset()
        super.signalEndOfCurrentInputStream()
    }

    override fun flush() {
        grid.reset()
        super.flush()
    }
}
