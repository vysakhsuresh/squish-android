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
     * The rate a player is asked for when the clip wants [wanted]: the same up
     * to [PLAYER_MAX_SPEED], and that ceiling above it.
     *
     * The clip may go to a hundred times, and the file does (Media3's
     * SpeedChangeEffect drops what it must). ExoPlayer's sink does not: Media3
     * 1.11.1's DefaultAudioSink holds speed and pitch to 0.1..8 (MAX_PLAYBACK_SPEED),
     * so above eight the sound ran at eight while the video renderer was asked for
     * a hundred and the clock raced the frames it could not decode. The preview's
     * clock is read off the driving player, so a capped rate is a slower preview
     * of the same edit, never a stuck one; the strip's length and the file are
     * the clip's own rate.
     */
    fun playerRate(wanted: Float): Float = wanted.coerceIn(PLAYER_MIN_SPEED, PLAYER_MAX_SPEED)

    /** DefaultAudioSink.MIN_PLAYBACK_SPEED and MAX_PLAYBACK_SPEED in Media3 1.11.1, and its pitch limits. */
    const val PLAYER_MIN_SPEED = 0.1f
    const val PLAYER_MAX_SPEED = 8f

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

    /**
     * The moment whose picture is shown at [t], on an edit that ends at [end].
     *
     * A clip, a layer or a caption covers up to, not including, its end, so at
     * the very end - where every play-through stops and Jump to end lands -
     * nothing covers the moment at all. The export's last frame is drawn a frame
     * before the end, with everything that runs to it; so is the preview's.
     */
    fun lastFrameTime(t: Long, end: Long): Long = if (end > 0L && t >= end) end - 1 else t

    /**
     * The moment the base track shows at [t], on an edit that runs to [editEnd]
     * and whose last shot ends at [baseEnd].
     *
     * The last frame, as [lastFrameTime] - but only when the shots run to the
     * end of the edit. When a layer or a sound runs on past the last shot, the
     * stretch after it is a real part of the edit with no picture in it, and
     * the clock has to cross it. Looking every moment past the shots up as
     * "the last frame" put the last shot back under the playhead: its player
     * was seeked back onto its out point every few hundred milliseconds, the
     * clock was read off that player, and playback sat on the end of the
     * picture for as long as the overlay ran - playing, going nowhere, never
     * reaching the end.
     */
    fun baseTime(t: Long, baseEnd: Long, editEnd: Long): Long =
        if (baseEnd >= editEnd) lastFrameTime(t, baseEnd) else t

    /**
     * The longest a hard cut holds the outgoing picture while the incoming shot
     * gets ready. Enough for a cold open the lookahead did not finish; past it, a
     * shot that still has not drawn is shown for what it is - nothing.
     */
    const val HOLD_MAX_MS = 2_000L

    /**
     * Whether a hard cut whose incoming shot has not drawn yet keeps the outgoing
     * shot's last frame on screen.
     *
     * Only when the surface being held is showing the shot that ends at this cut
     * ([heldShowsOutgoing]) and the playhead got here by playing across it
     * ([arrivedByPlaying]) - after a jump the held picture is some other part of
     * the edit. Never for a shot that has failed to load: its black is the only
     * sign it is broken. And never for longer than [HOLD_MAX_MS], because a
     * picture that never arrives must not leave the previous one up for the whole
     * of its length.
     */
    fun holdAtCut(heldShowsOutgoing: Boolean, arrivedByPlaying: Boolean, heldForMs: Long, incomingFailed: Boolean): Boolean =
        heldShowsOutgoing && arrivedByPlaying && !incomingFailed && heldForMs <= HOLD_MAX_MS

    /**
     * Whether the clock is read off the first surface (roll A): whenever the
     * shot driving it is the one on that roll now. Asked every pass, not only
     * when the clock changes hands - an edit made while playing re-deals the
     * rolls by parity, and the shot still covering can move to the other one.
     */
    fun clockOnA(clipAId: String?, clockClipId: String?): Boolean =
        clipAId != null && clipAId == clockClipId

    /**
     * Whether the effects library is drawn over the whole composed canvas
     * (CanvasFx), as the file draws it, rather than in each base player's
     * chain: from Android 13, which has runtime shaders. Below it the chain
     * is the only place it can go.
     */
    fun fxOnCanvas(sdkInt: Int): Boolean = sdkInt >= FX_ON_CANVAS_SDK

    const val FX_ON_CANVAS_SDK = 33

    /** One Compose layer's scale and its translation in pixels (see [foldedDraw]). */
    data class Folded(val scale: Float, val translateX: Float, val translateY: Float)

    /**
     * A clip's own placement and its share of a transition, folded into the one
     * `graphicsLayer` the preview draws a surface with - in the order the file
     * applies them.
     *
     * The file is two passes: `ClipTransformEffect` puts the placement on the
     * picture, then `TransitionEffect` works on *that*. So the transition's
     * scale scales the placement's offset as well as the picture, and the
     * transition's own shift is added after. A Compose layer applies its
     * translation *outside* its scale, so the two have to be folded by hand -
     * and they were folded as `placementOffset + transitionShift`, which is the
     * same number only while the transition does not scale. With a Zoom, a Zoom
     * out or a Pop in over a shot that has been moved off centre, the screen put
     * the picture somewhere the file does not.
     *
     * Offsets come in the units each is written in, and the two are *different
     * frames*. The placement runs before the frame's ratio is cut, so its
     * offsets are fractions of a half **canvas** ([widthPx]); the transition
     * runs after, so its shifts are fractions of the whole **output frame**
     * ([keptWidthPx]) - "a fraction of the frame the file is written at", as
     * TransitionEffect puts it. Both were taken against the canvas, so with any
     * crop a slide travelled further on screen than in the file and the
     * outgoing shot left the picture early. With no crop the two are the same
     * number and nothing moves.
     */
    fun foldedDraw(
        placementScale: Float,
        placementOffsetXFraction: Float,
        placementOffsetYFraction: Float,
        transitionScale: Float,
        transitionShiftX: Float,
        transitionShiftY: Float,
        widthPx: Float,
        heightPx: Float,
        keptWidthPx: Float = widthPx,
        keptHeightPx: Float = heightPx
    ): Folded = Folded(
        scale = placementScale * transitionScale,
        translateX = transitionShiftX * keptWidthPx + transitionScale * placementOffsetXFraction * widthPx / 2f,
        translateY = transitionShiftY * keptHeightPx + transitionScale * placementOffsetYFraction * heightPx / 2f
    )
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

    /** The scrub has been settled by other means: nothing is in flight, and nothing will settle later. */
    fun reset() {
        lastSeekAt = Long.MIN_VALUE / 2
        inFlight = false
    }
}
