package com.squish.app.editor

import kotlin.math.abs

/**
 * The decisions the preview engine makes thirty times a second, as plain
 * arithmetic on numbers.
 *
 * Kept apart from [PreviewEngine] so they can be compiled and executed on the JVM
 * (tools/jvm/PreviewRulesChecks.kt). Each one used to be an inline expression in
 * the engine, and each was wrong in a way no amount of reading found: a redraw
 * that walked the frame backwards a millisecond per call, a stall timer that
 * reloaded a slow seek forever, a speed guard a ramp sailed straight through.
 */
object PreviewRules {

    /**
     * How far inside a clip's out point a seek to its end lands.
     *
     * An exact seek shows the first frame at or after the position asked for, so
     * asking for one millisecond before the out point shows the first frame *past*
     * it whenever no frame starts in that last millisecond - which is almost
     * always. The frame the trim was set to hide. Fifty milliseconds is more than
     * a frame at anything above 20 fps, so the frame found is always one of the
     * clip's own.
     */
    const val END_BACKOFF_MS = 50L

    /** Where to seek a player for [sourceMs] of a clip that keeps [sourceInMs] to [sourceOutMs]. */
    fun seekTarget(sourceMs: Long, sourceInMs: Long, sourceOutMs: Long): Long {
        val last = maxOf(sourceInMs, sourceOutMs - END_BACKOFF_MS)
        return sourceMs.coerceIn(sourceInMs, last).coerceAtLeast(0L)
    }

    /**
     * Where to seek a paused player so it hands over a fresh copy of the frame at
     * [target] - after a resize, a grade change, anything the paused picture has
     * to show without playing.
     *
     * A seek to exactly where a player already is gets dropped as a no-op and
     * draws nothing, so when the player is already there this nudges by a
     * millisecond. The nudge is always taken from the true target, never from
     * the player's position: nudging from the position is what walked the parked
     * frame backwards one millisecond per redraw, ~120 of them in a four-second
     * slider drag, until it showed a frame before the trim.
     */
    fun redrawTarget(target: Long, current: Long, sourceInMs: Long, sourceOutMs: Long): Long {
        if (current != target) return target
        // Forward unless that leaves the clip; back never goes before the in point,
        // because the only way forward leaves the clip is a clip at least 2 ms long
        // parked on its last millisecond.
        return if (target + 1 < sourceOutMs || target - 1 < sourceInMs) target + 1 else target - 1
    }

    /** A rate change smaller than this is not worth restarting the audio stretcher for. */
    const val SPEED_DEAD_BAND = 0.03f

    /** And never more often than this, whatever the change. */
    const val SPEED_MIN_INTERVAL_MS = 150L

    /**
     * After this long, any difference at all is pushed, so a ramp that settles a
     * hair away from its last pushed value still arrives at it.
     */
    const val SPEED_SETTLE_MS = 600L

    /**
     * Whether a player's rate should be changed now.
     *
     * Every push restarts the audio time-stretcher, so pushing on every tick of a
     * ramp - which the old thousandth-of-a-unit guard did, because a ramp moves
     * ten times that per tick - turned the sound into a rattle. A new clip is
     * always pushed at once: a hard cut from 1x to 4x must not wait.
     */
    fun shouldPushSpeed(applied: Float?, wanted: Float, sinceLastPushMs: Long, clipChanged: Boolean): Boolean {
        if (applied == null || clipChanged) return true
        val delta = abs(applied - wanted)
        if (delta < 0.001f) return false
        if (delta >= SPEED_DEAD_BAND && sinceLastPushMs >= SPEED_MIN_INTERVAL_MS) return true
        return sinceLastPushMs >= SPEED_SETTLE_MS
    }

    /**
     * The index of the clip in [starts] that begins after [t] and within [window],
     * or -1. [starts] is in timeline order.
     *
     * This is the lookahead: the next shot on an idle player is opened and parked
     * on its first frame while the current one is still playing, so the cut is a
     * surface swap rather than a cold load at the moment it is due.
     */
    fun upcoming(starts: List<Long>, t: Long, window: Long): Int {
        for (i in starts.indices) {
            val start = starts[i]
            if (start > t) return if (start - t <= window) i else -1
        }
        return -1
    }
}

/**
 * Decides when a player that has stopped making progress should be reloaded.
 *
 * The old rule reloaded after two seconds of buffering, full stop, and restarted
 * the timer from the reload - so an exact seek into heavy footage that needed
 * three seconds was torn down at two, started again, torn down again, forever.
 * Now the timer restarts whenever the player shows progress (its buffered
 * position moves), and each reload doubles the wait before the next one, so a
 * slow seek always gets the time it needs in the end.
 */
class StallWatch(
    private val firstWaitMs: Long = 2_000L,
    private val maxWaitMs: Long = 16_000L
) {
    private var since = 0L
    private var lastBuffered = Long.MIN_VALUE
    var waitMs = firstWaitMs
        private set

    /** Healthy this tick: forget the stall and go back to the short wait. */
    fun healthy() {
        since = 0L
        lastBuffered = Long.MIN_VALUE
        waitMs = firstWaitMs
    }

    /**
     * Stuck this tick, with [bufferedMs] buffered. True when it has been stuck
     * without progress for long enough that a reload is the answer.
     */
    fun stuck(now: Long, bufferedMs: Long): Boolean {
        if (since == 0L || bufferedMs > lastBuffered) {
            // First stuck tick, or it is still loading: the clock starts now.
            since = now
            lastBuffered = bufferedMs
            return false
        }
        if (now - since < waitMs) return false
        since = now
        waitMs = (waitMs * 2).coerceAtMost(maxWaitMs)
        return true
    }
}

/**
 * Tells a scrub from a jump.
 *
 * A jump - a nudge, a tap on the ruler - wants the exact frame at once. A scrub is
 * a stream of seeks a frame apart, and each exact one decodes from the previous
 * sync sample; on unproxied footage that is the whole of why scrubbing feels
 * heavy. So seeks arriving close together are a scrub and are served from sync
 * samples, and once they stop the final position is sought exactly, and the
 * sounds are positioned then - once, rather than on every pointer event.
 */
class ScrubDetector(
    private val windowMs: Long = 250L,
    private val settleMs: Long = 200L
) {
    private var lastSeekAt = Long.MIN_VALUE / 2
    var inFlight = false
        private set

    /** A seek at [now]. True when it is part of a scrub. */
    fun onSeek(now: Long): Boolean {
        val quick = now - lastSeekAt < windowMs
        lastSeekAt = now
        if (quick) inFlight = true
        return inFlight
    }

    /** True exactly once, the first time it is asked after a scrub has gone quiet. */
    fun settled(now: Long): Boolean {
        if (!inFlight || now - lastSeekAt < settleMs) return false
        inFlight = false
        return true
    }

    /** The drag ended, so the scrub is over now rather than after the quiet period. */
    fun end() {
        lastSeekAt = Long.MIN_VALUE / 2
    }
}
