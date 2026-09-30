package com.squish.app.media.video

/**
 * Which frames a file at [fps] keeps, from frames arriving at any rate: for
 * each slot of a fixed grid, one frame period apart, the frame nearest it.
 *
 * Media3's default frame dropper measures from the last frame it kept, so a
 * steady input always steps by the same whole number of frames: a 30 fps shot
 * at 1.5x arrives at 45 fps and was written at 22.5 (every second frame), and
 * at 2.72x at 27.2 (every third) - found by reading a file's frame table. On a
 * grid the steps alternate, 2 then 1 then 2, and the file comes out at the
 * rate asked for.
 *
 * "Nearest" without waiting for the next frame: it is expected one gap after
 * this one, the gap the last two had, so a frame is kept when it is nearer
 * the slot than that one would be - which also keeps every frame of footage
 * whose timestamps wobble around the rate itself.
 *
 * A grid left behind by a stretch slower than the rate (a slowed shot, or
 * footage below it) is picked up again from the frame that caught it, so a
 * fast stretch after it does not pour frames through to catch up.
 */
class FrameGrid(fps: Float) {

    private val periodUs: Long = (1_000_000.0 / fps.coerceIn(1f, 240f)).toLong()
    private var nextUs = UNSET
    private var lastUs = UNSET

    /** Whether the frame at [timeUs] goes into the file. Frames come in time order. */
    fun keep(timeUs: Long): Boolean {
        val gap = if (lastUs == UNSET) 0L else (timeUs - lastUs).coerceAtLeast(0L)
        lastUs = timeUs
        if (nextUs == UNSET) {
            nextUs = timeUs + periodUs
            return true
        }
        if (timeUs < nextUs - gap / 2 - SLACK_US) return false
        nextUs += periodUs
        if (nextUs < timeUs + periodUs / 2) nextUs = timeUs + periodUs
        return true
    }

    /** A new stream: its first frame is kept and starts the grid again. */
    fun reset() {
        nextUs = UNSET
        lastUs = UNSET
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        /** Timestamps rounded to the microsecond land a hair early; a frame due on the slot is on it. */
        const val SLACK_US = 500L
    }
}
