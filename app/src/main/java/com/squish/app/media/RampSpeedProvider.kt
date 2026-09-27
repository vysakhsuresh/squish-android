@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.squish.app.timeline.SpeedSegment

/**
 * A clip's speed curve, in the shape Media3 asks for.
 *
 * `SpeedProvider` is a step function - what rate now, and when does it next
 * change - so this hands it the same staircase [com.squish.app.timeline.SpeedRamp]
 * hands the preview and the strip. That shared list is the point: Media3 computes
 * the output duration from this provider, and if it stepped the curve differently
 * from the way the strip measured it, the clip would end in one place on screen
 * and another in the file, by a margin that grows with every ramp in the edit.
 *
 * One instance drives both tracks: `SpeedChangeEffect` retimes the picture and
 * `SpeedChangingAudioProcessor` retimes the sound, from these same segments, so
 * they cannot drift apart.
 */
class RampSpeedProvider(segments: List<SpeedSegment>) : SpeedProvider {

    /** Segment starts in microseconds, ascending, with the rate that begins there. */
    private val startsUs: LongArray = LongArray(segments.size) { segments[it].startMs * 1_000L }
    private val speeds: FloatArray = FloatArray(segments.size) { segments[it].speed }

    override fun getSpeed(timeUs: Long): Float {
        if (speeds.isEmpty()) return 1f
        val index = indexAt(timeUs)
        return speeds[index]
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (startsUs.isEmpty()) return C.TIME_UNSET
        // A flat clip is one segment and reports no upcoming change, which is what
        // lets SpeedChangeEffect recognise a 1x clip as a no-op and skip the pass
        // entirely rather than retiming every frame by one.
        val index = indexAt(timeUs)
        val next = index + 1
        return if (next < startsUs.size) startsUs[next] else C.TIME_UNSET
    }

    /** The last segment that has started by [timeUs]; binary, because ramps are long. */
    private fun indexAt(timeUs: Long): Int {
        if (timeUs <= startsUs[0]) return 0
        var low = 0
        var high = startsUs.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (startsUs[mid] <= timeUs) low = mid else high = mid - 1
        }
        return low
    }
}
