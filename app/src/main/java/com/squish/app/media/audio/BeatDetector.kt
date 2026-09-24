package com.squish.app.media.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Where the beats are, how fast, and how much to believe it. */
data class BeatMap(
    /** Beat positions in milliseconds from the start of the analysed audio. */
    val beatsMs: List<Long> = emptyList(),
    val bpm: Float = 0f,
    /**
     * From 0 to 1. How periodic the music actually was, not how sure the tracker is
     * it followed its own guess. Something with no pulse in it scores low here
     * however neatly the beats came out, which is the only useful thing to show
     * someone before they cut forty clips to it.
     */
    val confidence: Float = 0f,
    /**
     * Which beat starts each bar, as an index into [beatsMs]. Chosen by which
     * phase carries the strongest onsets, which is right for most music with a
     * drum kit in it and a guess otherwise.
     */
    val downbeatOffset: Int = 0
) {
    val isEmpty: Boolean get() = beatsMs.isEmpty()

    /**
     * The same pulse counted twice as fast, or half as fast.
     *
     * Not a workaround for a weak detector - the question is genuinely ambiguous.
     * A slow track with busy hi-hats has two defensible tempos and two people
     * tapping along will disagree, so the answer has to be something anyone can
     * move by an octave. Every tool that does this has these two buttons.
     */
    fun halved(): BeatMap {
        if (beatsMs.size < 4) return this
        val kept = beatsMs.filterIndexed { i, _ -> (i - downbeatOffset).mod(2) == 0 }
        return copy(beatsMs = kept, bpm = bpm / 2f, downbeatOffset = 0)
    }

    fun doubled(): BeatMap {
        if (beatsMs.size < 2) return this
        val kept = ArrayList<Long>(beatsMs.size * 2)
        for (i in beatsMs.indices) {
            kept.add(beatsMs[i])
            if (i < beatsMs.lastIndex) kept.add((beatsMs[i] + beatsMs[i + 1]) / 2)
        }
        return copy(beatsMs = kept, bpm = bpm * 2f, downbeatOffset = downbeatOffset * 2)
    }

    /** Shifts which beat counts as the start of the bar. */
    fun withDownbeat(offset: Int): BeatMap =
        copy(downbeatOffset = offset.mod(4))

    /** Every nth beat from the downbeat: the cut points for "on the bar". */
    fun every(n: Int): List<Long> {
        if (n <= 1) return beatsMs
        return beatsMs.filterIndexed { i, _ -> (i - downbeatOffset).mod(n) == 0 }
    }
}

/**
 * Finds the pulse in a piece of audio.
 *
 * Three stages, each the standard one for the job:
 *
 * 1. **An onset envelope** from spectral flux - how much energy arrived in each
 *    bin that was not there in the previous frame. Level alone does not work on
 *    real music: a snare over a sustained bass note barely moves the overall
 *    loudness but lands almost entirely in bins the bass is not using.
 * 2. **A tempo** from the autocorrelation of that envelope, weighted by a
 *    log-normal prior around 120 BPM. The prior is doing real work - the raw
 *    autocorrelation of a four-to-the-floor track peaks just as hard at half and
 *    double the true tempo, and without a preference between them the answer is a
 *    coin toss between 70 and 140.
 * 3. **Beat positions** by dynamic programming (Ellis, 2007). Every frame gets the
 *    best score achievable by arriving there from a plausible previous beat, with
 *    a penalty that grows as the gap departs from the tempo; then the best path is
 *    walked back. This is what makes the beats survive a bar where the drummer
 *    drops out - a peak-picker loses the pulse there and never recovers it.
 *
 * All of it is plain arithmetic over a FloatArray, so it runs on the JVM and is
 * tested against click tracks at known tempos rather than argued about.
 */
object BeatDetector {

    /** Analysis frame and hop. 64ms windows every 16ms at 8kHz. */
    const val WINDOW = 512
    const val HOP = 128

    const val MIN_BPM = 60f
    const val MAX_BPM = 200f

    /**
     * Finds the beats in mono PCM.
     *
     * @param samples mono float PCM, nominally -1 to 1.
     * @param sampleRate its rate. The analysis is rate-agnostic; 8kHz is plenty,
     *   since everything that marks a beat has energy well below 4kHz.
     */
    fun detect(samples: FloatArray, sampleRate: Int): BeatMap {
        if (sampleRate <= 0 || samples.size < WINDOW * 4) return BeatMap()

        val envelope = onsetEnvelope(samples, sampleRate)
        if (envelope.size < 16) return BeatMap()

        val framesPerSecond = sampleRate.toDouble() / HOP
        val tempo = estimateTempo(envelope, framesPerSecond) ?: return BeatMap()

        val frames = trackBeats(envelope, tempo.periodFrames)
        if (frames.size < 2) return BeatMap()

        // A frame stands for the moment at the centre of its window, not its
        // start. The window is 512 samples and the hop 128, so a transient only
        // raises the flux once it is somewhere near the middle of the window that
        // contains it - measured against click tracks, reporting the window start
        // put every beat a consistent 32ms early, which is a visible slip on a cut.
        val centreMs = WINDOW / 2.0 * 1000.0 / sampleRate
        val beatsMs = frames.map { (it * 1000.0 / framesPerSecond + centreMs).roundToLong() }
        return BeatMap(
            beatsMs = beatsMs,
            bpm = (60.0 * framesPerSecond / tempo.periodFrames).toFloat(),
            confidence = tempo.confidence,
            downbeatOffset = pickDownbeat(envelope, frames)
        )
    }

    // ---- 1. Onset envelope ------------------------------------------------------

    /**
     * Spectral flux over mel bands, log-compressed, with the local mean removed.
     *
     * **Mel bands, not raw bins.** Measuring this against click tracks showed why:
     * a hi-hat is a broadband noise burst that lights up two hundred bins at once,
     * while a kick is a low sine that moves three. Summed per bin, a quiet tick
     * outweighs a loud kick by an order of magnitude, and the tracker locks onto
     * the off-beats - at 150 BPM it found the tempo perfectly and then placed
     * every beat exactly half a beat late. Grouping into forty bands on a mel
     * scale gives the hat a handful of bands instead of half the spectrum, which
     * is both how hearing works and what makes the downbeat win.
     *
     * **Log compression** stops a loud chorus swamping a quiet verse, which would
     * otherwise make the tracker follow the loudest section rather than the pulse.
     *
     * **The local mean** is taken over a window far longer than any beat period.
     * A short one would be a high-pass filter with its cutoff sitting on the
     * tempo, removing the very periodicity being measured.
     */
    fun onsetEnvelope(samples: FloatArray, sampleRate: Int): FloatArray {
        val window = Fft.hann(WINDOW)
        val bins = WINDOW / 2
        val frameCount = (samples.size - WINDOW) / HOP + 1
        if (frameCount < 2) return FloatArray(0)

        val edges = melEdges(bins, sampleRate, BANDS)
        val bandCount = edges.size - 1

        val real = FloatArray(WINDOW)
        val imaginary = FloatArray(WINDOW)
        val windowed = FloatArray(WINDOW)
        val magnitude = FloatArray(bins)
        val band = FloatArray(bandCount)
        val previous = FloatArray(bandCount)

        val flux = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val start = f * HOP
            for (i in 0 until WINDOW) windowed[i] = samples[start + i] * window[i]
            Fft.magnitudes(windowed, real, imaginary, magnitude)

            var sum = 0f
            for (b in 0 until bandCount) {
                var energy = 0f
                for (k in edges[b] until edges[b + 1]) energy += magnitude[k]
                band[b] = ln(1f + energy * COMPRESSION)
                val rise = band[b] - previous[b]
                if (rise > 0f) sum += rise
                previous[b] = band[b]
            }
            flux[f] = sum
        }

        val framesPerSecond = sampleRate.toDouble() / HOP
        val meanSpan = max(3, (MEAN_SPAN_SECONDS * framesPerSecond).roundToInt())
        val out = FloatArray(frameCount)
        var running = 0.0
        var count = 0
        for (f in flux.indices) {
            running += flux[f]
            count++
            if (count > meanSpan) {
                running -= flux[f - meanSpan]
                count--
            }
            out[f] = max(0.0, flux[f] - running / count).toFloat()
        }

        return normalized(out)
    }

    /**
     * Band edges, evenly spaced on the mel scale.
     *
     * Rectangular rather than triangular: overlapping triangles matter when the
     * bands are a feature vector someone will compare, and not at all when they
     * are summed into one number per frame.
     */
    private fun melEdges(bins: Int, sampleRate: Int, bandCount: Int): IntArray {
        fun toMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun fromMel(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val nyquist = sampleRate / 2.0
        val topMel = toMel(nyquist)
        val edges = IntArray(bandCount + 1)
        for (b in 0..bandCount) {
            val hz = fromMel(topMel * b / bandCount)
            edges[b] = (hz / nyquist * bins).roundToInt().coerceIn(0, bins)
        }
        // A band that rounded onto its neighbour would contribute nothing and make
        // the flux sum depend on the frame size in a way nobody would expect.
        for (b in 1..bandCount) if (edges[b] <= edges[b - 1]) edges[b] = (edges[b - 1] + 1).coerceAtMost(bins)
        return edges
    }

    // ---- 2. Tempo ---------------------------------------------------------------

    private data class Tempo(val periodFrames: Double, val confidence: Float)

    /**
     * The tempo, as the period best supported by its own multiples.
     *
     * The plain autocorrelation peak is not the beat, and measuring it showed
     * exactly why: on a bar of kick-snare-kick-snare the strongest periodicity is
     * the **bar**, because that is where the whole pattern repeats. At 120 BPM the
     * correlation at two seconds was nearly twice the correlation at half a
     * second, so argmax reported 60 BPM with total confidence. A weighted prior
     * cannot fix that - it only expresses which octave is more likely in general,
     * and the gap here was far too large for it to close.
     *
     * The fix is Ellis's: score a candidate period by the correlation at that
     * period *plus* the correlation at its multiples. A real beat period is
     * supported at two and three and four beats; a bar is supported only at two
     * and four bars, and a one-and-a-half-beat lag is barely supported anywhere.
     * Both a duple and a triple hypothesis are tried, so a waltz is not forced
     * into four.
     *
     * The neighbours of each multiple are included because a multiple rarely lands
     * exactly on a frame boundary, and reading only the exact bin throws away most
     * of the support at fast tempos.
     */
    private fun estimateTempo(envelope: FloatArray, framesPerSecond: Double): Tempo? {
        val slowestPeriod = (60.0 / MIN_BPM * framesPerSecond).roundToInt()
        val fastestPeriod = max(2, (60.0 / MAX_BPM * framesPerSecond).roundToInt())
        // Correlations are needed out to three times the slowest period, since
        // that is where a slow tempo's support lives.
        val maxLag = min(envelope.size - 2, slowestPeriod * 3)
        if (maxLag <= fastestPeriod + 2) return null

        val weighted = DoubleArray(maxLag + 1)
        for (lag in fastestPeriod..maxLag) {
            var correlation = 0.0
            for (t in 0 until envelope.size - lag) correlation += envelope[t] * envelope[t + lag]
            correlation /= (envelope.size - lag)

            val bpm = 60.0 * framesPerSecond / lag
            val octaves = ln(bpm / PREFERRED_BPM) / LN2
            weighted[lag] = correlation * exp(-0.5 * (octaves / PRIOR_WIDTH_OCTAVES).let { it * it })
        }

        fun at(lag: Int): Double = if (lag in weighted.indices) weighted[lag] else 0.0

        var bestPeriod = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var scoreSum = 0.0
        var scoreCount = 0

        for (period in fastestPeriod..slowestPeriod) {
            if (period * 3 + 1 > maxLag && period * 2 + 1 > maxLag) continue

            val duple = at(period) +
                0.5 * at(2 * period) + 0.25 * at(2 * period - 1) + 0.25 * at(2 * period + 1) +
                0.25 * at(4 * period)
            val triple = at(period) +
                0.33 * (at(3 * period) + at(3 * period - 1) + at(3 * period + 1))

            val score = max(duple, triple)
            scoreSum += score
            scoreCount++
            if (score > bestScore) {
                bestScore = score
                bestPeriod = period
            }
        }
        if (bestPeriod < 0) return null

        // How far the winner stands above the average candidate. Music with a
        // pulse peaks several times the mean; a field recording does not.
        val mean = if (scoreCount > 0) scoreSum / scoreCount else 0.0
        val ratio = if (mean > 1e-9) bestScore / mean else 0.0
        val confidence = ((ratio - 1.0) / 3.0).coerceIn(0.0, 1.0).toFloat()

        // Refined off the raw correlation around the winner, so the answer is not
        // stuck on the 16ms frame grid. At 174 BPM one frame is already 2.5 BPM.
        val refined = parabolicPeak(weighted, bestPeriod)
        return Tempo(periodFrames = refined, confidence = confidence)
    }

    /**
     * Sub-frame interpolation through three samples around a peak.
     *
     * Without it the reported tempo can only be one of the values the frame grid
     * allows, which at fast tempos is a coarse ladder - 170.45, 174.09, 177.84 and
     * nothing in between. The beats themselves would land on the same ladder and
     * drift audibly over a long clip.
     */
    private fun parabolicPeak(values: DoubleArray, index: Int): Double {
        if (index <= 0 || index >= values.size - 1) return index.toDouble()
        val before = values[index - 1]
        val here = values[index]
        val after = values[index + 1]
        val denominator = before - 2 * here + after
        if (abs(denominator) < 1e-12) return index.toDouble()
        val shift = 0.5 * (before - after) / denominator
        return index + shift.coerceIn(-0.5, 0.5)
    }

    // ---- 3. Beat positions ------------------------------------------------------

    /**
     * Ellis's dynamic-programming beat tracker.
     *
     * Each frame records the best score for a beat landing there, taken over every
     * plausible previous beat: the onset strength here, plus the best predecessor's
     * score, minus a penalty that grows with the square of the log ratio between
     * the gap and the expected period. Then the best ending is walked backwards.
     *
     * The penalty being on the *log* ratio is the part that matters. It makes being
     * 10% early cost the same as being 10% late, so the tracker does not drift
     * steadily sharp or flat over a long track the way a linear penalty does.
     */
    fun trackBeats(envelope: FloatArray, periodFrames: Double): List<Int> {
        @Suppress("NAME_SHADOWING")
        val n = envelope.size
        if (n < 4 || periodFrames < 2) return emptyList()

        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }

        val earliest = max(1, (periodFrames * 0.5).roundToInt())
        val latest = max(earliest + 1, (periodFrames * 2.0).roundToInt())

        for (t in 0 until n) {
            var bestScore = Double.NEGATIVE_INFINITY
            var bestFrom = -1
            val from = max(0, t - latest)
            val to = t - earliest
            for (candidate in from..to) {
                val gap = (t - candidate).toDouble()
                val logRatio = ln(gap / periodFrames)
                val transition = -TIGHTNESS * logRatio * logRatio
                val value = transition + score[candidate]
                if (value > bestScore) {
                    bestScore = value
                    bestFrom = candidate
                }
            }
            if (bestFrom < 0) {
                // Too early in the track for a previous beat to exist. Start fresh.
                score[t] = envelope[t].toDouble()
            } else {
                score[t] = (1 - ALPHA) * envelope[t] + ALPHA * bestScore
                back[t] = bestFrom
            }
        }

        // End on the best score in the final stretch, not simply the last frame:
        // a track that fades out should not have its last beat pinned to silence.
        val tail = max(0, n - latest)
        var end = tail
        for (t in tail until n) if (score[t] > score[end]) end = t

        val reversed = ArrayList<Int>()
        var at = end
        while (at >= 0) {
            reversed.add(at)
            at = back[at]
        }
        reversed.reverse()

        return trimAndExtend(envelope, reversed, periodFrames)
    }

    /**
     * Trims the ends until the spacing has settled, then rebuilds the grid outward.
     *
     * The chain has to begin somewhere, and that first hop is the tracker's choice
     * rather than a detected beat. Measuring it showed the shape exactly: at 120
     * BPM every gap in the track was 496ms except the second, which was 736 - two
     * junk beats at the very front while the rest was perfect. So a beat is
     * dropped from an end while either of the next two gaps is off the median,
     * which settles immediately on a clean track and cannot eat more than the few
     * beats it takes for the pulse to establish itself.
     *
     * Then the grid is extended back to the top of the track at the established
     * period. The first beat of a piece of music is exactly where someone wants a
     * cut, and losing it to the tracker's warm-up would be a strange thing to ship.
     */
    private fun trimAndExtend(
        envelope: FloatArray,
        beats: MutableList<Int>,
        periodFrames: Double
    ): List<Int> {
        if (beats.size < 6) return beats

        val gaps = IntArray(beats.size - 1) { beats[it + 1] - beats[it] }
        val median = gaps.sorted()[gaps.size / 2]
        if (median <= 0) return beats
        val tolerance = max(1, (median * SETTLE_TOLERANCE).roundToInt())
        fun ragged(i: Int) = i in gaps.indices && abs(gaps[i] - median) > tolerance

        var first = 0
        while (first < beats.size - 4 && (ragged(first) || ragged(first + 1))) first++
        var last = beats.size - 1
        while (last > first + 4 && (ragged(last - 1) || ragged(last - 2))) last--

        val kept = ArrayList(beats.subList(first, last + 1))

        // Extended at the *fractional* period, not the median whole-frame gap.
        // At 174 BPM a beat is 21.55 frames, so stepping by 22 is 7ms of error a
        // beat - which was invisible over four beats and 140ms out by the end of
        // the track. Accumulating in a double costs nothing and does not drift.
        var before = kept.first() - periodFrames
        while (before >= 0) {
            kept.add(0, before.roundToInt())
            before -= periodFrames
        }

        // Forward only as far as the tail that was trimmed, and never further.
        // Past the end of what was tracked these are not detected beats, they are
        // guesses, and a long tail of them is worse than an honest stop.
        var after = kept.last() + periodFrames
        var invented = 0
        while (after < envelope.size && invented < MAX_INVENTED_TAIL) {
            kept.add(after.roundToInt())
            after += periodFrames
            invented++
        }
        return kept
    }

    // ---- Downbeat ----------------------------------------------------------------

    /**
     * Which of the four phases carries the strongest beats.
     *
     * A heuristic, and named as one: in most music with a kit in it the bar starts
     * on the loudest onset of the four, and where that is not true the answer is at
     * worst off by a beat, which is what the offset control is for.
     */
    private fun pickDownbeat(envelope: FloatArray, frames: List<Int>): Int {
        if (frames.size < 8) return 0
        var best = 0
        var bestMean = Double.NEGATIVE_INFINITY
        for (phase in 0 until 4) {
            var sum = 0.0
            var count = 0
            var i = phase
            while (i < frames.size) {
                sum += envelope.getOrElse(frames[i]) { 0f }
                count++
                i += 4
            }
            val mean = if (count > 0) sum / count else 0.0
            if (mean > bestMean) {
                bestMean = mean
                best = phase
            }
        }
        return best
    }

    // ---- Shared -------------------------------------------------------------------

    /** Zero mean is already gone; this only puts the scale somewhere predictable. */
    private fun normalized(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var variance = 0.0
        for (v in values) variance += (v - mean) * (v - mean)
        val deviation = sqrt(variance / values.size)
        if (deviation < 1e-9) return FloatArray(values.size)
        return FloatArray(values.size) { (values[it] / deviation).toFloat() }
    }

    /** The closest beat to a moment, or null if none is near enough to mean it. */
    fun nearestBeat(beatsMs: List<Long>, atMs: Long, withinMs: Long): Long? {
        val nearest = beatsMs.minByOrNull { abs(it - atMs) } ?: return null
        return if (abs(nearest - atMs) <= withinMs) nearest else null
    }

    /** Forty bands: enough to separate a kick from a hat, few enough to be cheap. */
    private const val BANDS = 40
    /** Far longer than any beat period, so the mean cannot filter out the tempo. */
    private const val MEAN_SPAN_SECONDS = 1.5
    private const val COMPRESSION = 40f
    private const val PREFERRED_BPM = 120.0
    private const val PRIOR_WIDTH_OCTAVES = 0.9
    private const val LN2 = 0.6931471805599453
    private const val TIGHTNESS = 6.0
    /** How far a gap may sit from the median before the pulse counts as unsettled. */
    private const val SETTLE_TOLERANCE = 0.15
    /** How many beats may be extended past the last one actually tracked. */
    private const val MAX_INVENTED_TAIL = 2
    private const val ALPHA = 0.8
}
