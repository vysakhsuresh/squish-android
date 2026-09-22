package com.squish.app.media.audio

/** A stretch of the audio that sounds like someone talking. */
data class SpeechSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * Finds where the speech is.
 *
 * This is the half of captioning that is actually tedious. Typing a sentence takes
 * seconds; finding the exact frame someone starts and stops talking, forty times in
 * a row, is the part that eats an afternoon. Getting the boundaries right is
 * therefore worth more than it looks, and it can be done honestly on the PCM the
 * app already decodes for waveforms and A/V sync - no model, no download, no
 * network.
 *
 * Energy-based with hysteresis, which is the classic approach and behaves well on
 * the kind of audio phones record. The threshold is derived from the recording's own
 * statistics rather than being a constant, because a fixed level is wrong for every
 * clip that was not recorded at the level it was tuned on.
 */
object SpeechSegmenter {

    /** Analysis window. Short enough to catch a plosive, long enough to be stable. */
    private const val FRAME_MS = 20

    /** Consecutive loud frames before speech is declared: rejects clicks and taps. */
    private const val OPEN_FRAMES = 3

    /**
     * Consecutive quiet frames before speech is declared over. Generous on purpose -
     * the gap between words inside a sentence is often 150-250 ms, and closing on the
     * first one shreds a sentence into a caption per word.
     */
    private const val CLOSE_FRAMES = 12

    private const val MIN_SPEECH_MS = 320L
    private const val MERGE_GAP_MS = 220L

    /** Speech starts slightly before it gets loud, so the opening consonant survives. */
    private const val PAD_MS = 110L

    /** Longer than this and a caption stops being readable, so it is split. */
    private const val MAX_SEGMENT_MS = 4_200L

    /**
     * Where in the noise-to-peak range the speech threshold sits. Low, because
     * missing a quiet word costs more than catching a breath.
     */
    private const val THRESHOLD_FRACTION = 0.22f

    /**
     * A recording needs both kinds of separation before it can contain speech: the
     * loud parts must stand well clear of the quiet ones *as a ratio*, and the gap
     * must be more than nothing *in absolute terms*.
     *
     * Either test alone lets something through. Room tone has a ratio near 1 but a
     * measurable spread, and a near-silent recording can show a large ratio between
     * two tiny numbers. Requiring both is what stops a silent clip being carved into
     * arbitrary captions.
     */
    private const val MIN_DYNAMIC_RANGE = 2.5f
    private const val MIN_ABSOLUTE_RANGE = 1e-4f

    fun segment(pcm: MonoPcm): List<SpeechSegment> {
        val rate = pcm.sampleRate
        if (rate <= 0 || pcm.samples.isEmpty()) return emptyList()

        val frameSize = (rate * FRAME_MS / 1000).coerceAtLeast(1)
        val frameCount = pcm.samples.size / frameSize
        if (frameCount < OPEN_FRAMES * 2) return emptyList()

        val energy = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0.0
            val base = f * frameSize
            for (i in 0 until frameSize) {
                val s = pcm.samples[base + i]
                sum += s.toDouble() * s
            }
            energy[f] = kotlin.math.sqrt(sum / frameSize).toFloat()
        }

        val sorted = energy.sortedArray()
        val floor = percentile(sorted, 0.20f)
        val peak = percentile(sorted, 0.95f)

        // A recording with no separation between its quiet and loud parts is either
        // silent or solid noise. Either way there is no speech to find, and inventing
        // a threshold would just carve the noise into arbitrary captions.
        if (peak <= floor * MIN_DYNAMIC_RANGE || peak - floor < MIN_ABSOLUTE_RANGE) {
            return emptyList()
        }

        val threshold = floor + (peak - floor) * THRESHOLD_FRACTION

        val raw = mutableListOf<SpeechSegment>()
        var speaking = false
        var loudRun = 0
        var quietRun = 0
        var startFrame = 0

        for (f in 0 until frameCount) {
            val loud = energy[f] > threshold
            if (loud) {
                loudRun++
                quietRun = 0
                if (!speaking && loudRun >= OPEN_FRAMES) {
                    speaking = true
                    startFrame = f - OPEN_FRAMES + 1
                }
            } else {
                quietRun++
                loudRun = 0
                if (speaking && quietRun >= CLOSE_FRAMES) {
                    speaking = false
                    raw.add(SpeechSegment(frameToMs(startFrame), frameToMs(f - quietRun + 1)))
                }
            }
        }
        if (speaking) raw.add(SpeechSegment(frameToMs(startFrame), frameToMs(frameCount)))

        val totalMs = pcm.durationMs
        return raw
            .let { mergeClose(it) }
            .map { pad(it, totalMs) }
            .filter { it.durationMs >= MIN_SPEECH_MS }
            .flatMap { splitLong(it) }
    }

    private fun frameToMs(frame: Int): Long = frame.toLong() * FRAME_MS

    private fun percentile(sortedAscending: FloatArray, p: Float): Float {
        if (sortedAscending.isEmpty()) return 0f
        val i = ((sortedAscending.size - 1) * p).toInt().coerceIn(0, sortedAscending.size - 1)
        return sortedAscending[i]
    }

    /** Two segments a breath apart are one sentence, not two captions. */
    private fun mergeClose(segments: List<SpeechSegment>): List<SpeechSegment> {
        if (segments.isEmpty()) return segments
        val out = mutableListOf(segments.first())
        segments.drop(1).forEach { next ->
            val last = out.last()
            if (next.startMs - last.endMs <= MERGE_GAP_MS) {
                out[out.size - 1] = last.copy(endMs = next.endMs)
            } else {
                out.add(next)
            }
        }
        return out
    }

    private fun pad(segment: SpeechSegment, totalMs: Long): SpeechSegment = SpeechSegment(
        startMs = (segment.startMs - PAD_MS).coerceAtLeast(0L),
        endMs = (segment.endMs + PAD_MS).coerceAtMost(if (totalMs > 0) totalMs else segment.endMs + PAD_MS)
    )

    /**
     * A long run of unbroken speech is split into even pieces rather than at its
     * quietest point: an even split keeps every caption readable, where cutting at
     * the quietest frame can still leave one card holding eight seconds of talking.
     */
    private fun splitLong(segment: SpeechSegment): List<SpeechSegment> {
        if (segment.durationMs <= MAX_SEGMENT_MS) return listOf(segment)
        val pieces = ((segment.durationMs + MAX_SEGMENT_MS - 1) / MAX_SEGMENT_MS).toInt()
        val each = segment.durationMs / pieces
        return (0 until pieces).map { i ->
            SpeechSegment(
                startMs = segment.startMs + i * each,
                endMs = if (i == pieces - 1) segment.endMs else segment.startMs + (i + 1) * each
            )
        }
    }
}
