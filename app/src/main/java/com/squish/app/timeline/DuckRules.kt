package com.squish.app.timeline

/**
 * Auto-duck: a song turned down under the talking and back up between, as the
 * volume keys the preview and the file already play (Clip.volumeKeys). What is
 * decided here - where the speech is on the timeline, and the keys that dip the
 * song under it - is kept free of Android so it is executed on the JVM
 * (tools/jvm/DuckChecks.kt); finding the speech in a file is SpeechSegmenter's.
 */
object DuckRules {

    /** How long the song takes to go down before a line and to come back after it. */
    const val FADE_MS = 300L

    /** Two lines closer than this keep the song down between them: a dip per word pumps. */
    const val BRIDGE_MS = 900L

    /** Where the song sits under speech, as a share of its own level: about -12 dB. */
    const val DUCKED_SHARE = 0.25f

    /**
     * The speech [segmentsInSource] (milliseconds of [clip]'s file) as moments of
     * the timeline, through the clip's trim and speed, cut to the part it plays.
     */
    fun onTimeline(clip: Clip, segmentsInSource: List<LongRange>): List<LongRange> =
        segmentsInSource.mapNotNull { seg ->
            val from = maxOf(seg.first, clip.sourceInMs)
            val to = minOf(seg.last, clip.sourceOutMs)
            if (to <= from) return@mapNotNull null
            val start = clip.timelineAtSource(from).coerceIn(clip.timelineStartMs, clip.timelineEndMs)
            val end = clip.timelineAtSource(to).coerceIn(clip.timelineStartMs, clip.timelineEndMs)
            if (end > start) start..end else null
        }

    /** Sorted, and joined wherever two are within [bridgeMs] of each other. */
    fun merged(ranges: List<LongRange>, bridgeMs: Long = BRIDGE_MS): List<LongRange> {
        val sorted = ranges.filter { it.last > it.first }.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first - last.last <= bridgeMs) out[out.size - 1] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        return out
    }

    /**
     * The keys that dip [music] under [speech] (timeline moments): at its own
     * level, down to [DUCKED_SHARE] of it over [FADE_MS] before each line, held
     * through it, and back up over [FADE_MS] after. In the clip's own time, as
     * volume keys are. Empty when no speech falls under the song.
     */
    fun keys(music: Clip, speech: List<LongRange>, fadeMs: Long = FADE_MS): List<ValueKey> {
        val level = music.volume
        val low = level * DUCKED_SHARE
        val length = music.durationMs
        val under = merged(speech.mapNotNull { r ->
            val from = maxOf(r.first, music.timelineStartMs) - music.timelineStartMs
            val to = minOf(r.last, music.timelineEndMs) - music.timelineStartMs
            if (to > from) from..to else null
        }, bridgeMs = maxOf(BRIDGE_MS, fadeMs * 2))
        if (under.isEmpty()) return emptyList()
        val keys = ArrayList<ValueKey>()
        for (r in under) {
            val downFrom = (r.first - fadeMs).coerceAtLeast(0L)
            val upTo = (r.last + fadeMs).coerceAtMost(length)
            if (downFrom > 0L) keys += ValueKey(downFrom, level, KeyframeEasing.Linear)
            keys += ValueKey(r.first.coerceAtLeast(0L), low, KeyframeEasing.Linear)
            keys += ValueKey(r.last.coerceAtMost(length), low, KeyframeEasing.Linear)
            if (upTo < length) keys += ValueKey(upTo, level, KeyframeEasing.Linear)
        }
        // A key at the very start and end keeps the level outside the dips the song's own.
        if (keys.first().atMs > 0L) keys.add(0, ValueKey(0L, level, KeyframeEasing.Linear))
        if (keys.last().atMs < length) keys += ValueKey(length, level, KeyframeEasing.Linear)
        return keys.distinctBy { it.atMs }.sortedBy { it.atMs }
    }
}
