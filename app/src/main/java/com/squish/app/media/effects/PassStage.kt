package com.squish.app.media.effects

/**
 * The routing of a preview pass that hands a frame straight on when it has
 * nothing to do to it (IdlePassEffect), kept free of Media3 - generic over
 * the texture - so it can be executed against a model of Media3's chain
 * (tools/jvm/PassLedgerChecks.kt).
 *
 * Why it exists: every frame the preview shows went through seven full-frame
 * shader passes on the base surface - HDR lift, chroma key, background, mask,
 * look, blended still, effects - whether or not any of them did anything.
 * On the moto that was the scrub's frame budget (26 Oct: with the chain
 * taken off, scrub p90 65 -> 35 ms and the GPU's p90 43 -> 16 ms). A pass at
 * its identity now forwards its input texture rather than drawing a copy.
 *
 * Media3's rules this keeps: a stage gives its upstream one credit per frame
 * it can take (onReadyToAcceptInputFrame), hands every input back exactly once
 * (onInputFrameProcessed), and is given back every output exactly once
 * (releaseOutputFrame); a flush runs from the last stage to the first, and
 * drops whatever was in flight on both sides. The wrapped program has room
 * for one frame, as every pass here does, and a forwarded frame takes that
 * same room: one frame with this stage at a time, whichever way it went.
 */
class PassStage<T>(
    private val idle: (presentationTimeUs: Long) -> Boolean,
    /** The wrapped program: take a frame, take one of its outputs back, flush. */
    private val innerQueue: (T, Long) -> Unit,
    private val innerRelease: (T) -> Unit,
    private val innerFlush: () -> Unit,
    /** The neighbours: a frame out to the next stage, an input back to the last, a credit to the last. */
    private val handOn: (T, Long) -> Unit,
    private val handBack: (T) -> Unit,
    private val credit: () -> Unit
) {
    /** The input handed on as it came, until the next stage gives it back. */
    private var forwarded: T? = null

    /**
     * Frames that came while a forwarded one was still out - which this
     * stage gave no credit for, so it never should. Drawn rather than
     * thrown, since a throw here is on the GL thread; the check counts them.
     */
    var outOfTurn: Int = 0
        private set

    fun queue(input: T, presentationTimeUs: Long) {
        val skip = forwarded == null && runCatching { idle(presentationTimeUs) }.getOrDefault(false)
        if (forwarded != null) outOfTurn++
        if (skip) {
            forwarded = input
            handOn(input, presentationTimeUs)
        } else {
            innerQueue(input, presentationTimeUs)
        }
    }

    /**
     * The next stage giving a texture back: the forwarded one goes back
     * upstream with a credit for the next frame; any other is the wrapped
     * program's own, which it answers itself. By identity - a texture is
     * the same object on its way out and back.
     */
    fun release(output: T) {
        val f = forwarded
        if (f != null && f === output) {
            forwarded = null
            handBack(f)
            credit()
        } else {
            innerRelease(output)
        }
    }

    /** A flush: what was out is dropped on both sides and nothing is handed back. */
    fun flush() {
        forwarded = null
        innerFlush()
    }
}
