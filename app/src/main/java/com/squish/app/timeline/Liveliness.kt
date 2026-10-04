package com.squish.app.timeline

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which part of a shot is the part worth keeping.
 *
 * "Fit the shots to the song" gives every shot its share and keeps its head,
 * because the part people framed is usually the part they meant. That is the
 * safe answer and the wrong one for the pile of holiday clips AutoCut is aimed
 * at, where the first two seconds are a hand settling and the good bit is in
 * the middle.
 *
 * This is the other answer: the shot keeps its length and its window slides to
 * wherever most is happening. What "happening" means is measured elsewhere
 * (LivelinessProfiler decodes frames and sound); everything about *choosing*
 * from the measurement is here, and tools/jvm/LivelinessChecks.kt executes it.
 */

/**
 * A clip's liveliness over its own footage: [scores] from the start of the
 * file, one every [stepMs]. Any scale will do - only the shape matters, since
 * the choice is a comparison between windows of the same profile.
 */
data class LivelinessProfile(val stepMs: Long, val scores: List<Float>) {
    val isEmpty: Boolean get() = scores.isEmpty() || stepMs <= 0L

    /** How far into the file the profile reaches. */
    val coversMs: Long get() = if (isEmpty) 0L else stepMs * scores.size
}

object Liveliness {

    /**
     * How much of each end of a shot is discounted: the hand still settling at
     * the start, the hand reaching for the button at the end. A share of the
     * *file*, not of the window, so a long clip is discounted no harder than a
     * short one.
     */
    const val EDGE_SHARE = 0.08f

    /** What a sample inside the discounted ends is worth: most of itself, not none. */
    const val EDGE_WEIGHT = 0.55f

    /**
     * The two measurements folded into one score per step.
     *
     * Each is scaled by its own loudest or busiest moment first, because they
     * are in unrelated units - a mean pixel difference and a sample peak - and
     * whichever happened to be the larger number would otherwise decide every
     * shot on its own. A moment is lively if either is: taken as a sum, a
     * silent clip of fast movement scored below a still one of someone talking.
     */
    fun combined(motion: List<Float>, loudness: List<Float>): List<Float> {
        val steps = max(motion.size, loudness.size)
        if (steps == 0) return emptyList()
        val m = normalized(motion)
        val l = normalized(loudness)
        return (0 until steps).map { i ->
            val a = m.getOrElse(i) { 0f }
            val b = l.getOrElse(i) { 0f }
            max(a, b) * 0.75f + (a + b) / 2f * 0.25f
        }
    }

    /** [values] against their own largest, or all zero when there is nothing in them. */
    fun normalized(values: List<Float>): List<Float> {
        val top = values.maxOrNull() ?: 0f
        if (!(top > 0f)) return values.map { 0f }
        return values.map { (it / top).coerceIn(0f, 1f) }
    }

    /**
     * Where a window of [wantMs] should start inside a file [durationMs] long,
     * by [profile].
     *
     * Ties go to the earliest window. A shot with nothing at all to choose
     * between its parts therefore lands just clear of the discounted ends,
     * which is the point of discounting them: the start of a handheld clip is
     * a hand settling, and that reads as *movement*, so leaving it to the
     * measurement would send the window straight to the worst two seconds in
     * the file.
     *
     * A shot with no profile at all keeps its head untouched - that is
     * [withBestBitsKept]'s doing, not this function's, and it is what makes the
     * whole thing safe to run over a timeline of photos and stills.
     */
    fun bestWindowStart(profile: LivelinessProfile, durationMs: Long, wantMs: Long): Long {
        val latest = durationMs - wantMs
        if (latest <= 0L || profile.isEmpty || wantMs <= 0L) return 0L

        val step = profile.stepMs
        val scores = profile.scores
        // Weighted once, so the ends are discounted in the sum rather than in
        // every window that overlaps them.
        val edge = (scores.size * EDGE_SHARE).roundToInt().coerceAtLeast(0)
        val weighted = scores.mapIndexed { i, s ->
            if (i < edge || i >= scores.size - edge) s * EDGE_WEIGHT else s
        }
        // A running sum, so a long file with a short window is one pass rather
        // than one pass per window: a minute of 50 ms steps is 1200 windows.
        val sums = FloatArray(weighted.size + 1)
        weighted.forEachIndexed { i, s -> sums[i + 1] = sums[i] + s }

        val windowSteps = (wantMs / step).toInt().coerceAtLeast(1)
        val lastStart = min(((latest / step).toInt()), weighted.size - windowSteps)
        if (lastStart <= 0) return 0L

        var bestAt = 0
        var best = -1f
        for (at in 0..lastStart) {
            val sum = sums[at + windowSteps] - sums[at]
            // Strictly greater, so the first of equals wins and the head is kept.
            if (sum > best) {
                best = sum
                bestAt = at
            }
        }
        return (bestAt.toLong() * step).coerceIn(0L, latest)
    }
}

/**
 * Every main-track shot keeping the length it has, with its window slid to the
 * liveliest part of its own footage.
 *
 * A shot with no profile - a photo, a still, a file that would not decode -
 * is left exactly as it is, rather than guessed at.
 */
fun List<Clip>.withBestBitsKept(profiles: Map<String, LivelinessProfile>): List<Clip> {
    if (profiles.isEmpty()) return this
    return map { clip ->
        if (clip.isOverlay || clip.kind != ClipKind.Video) return@map clip
        val profile = profiles[clip.id] ?: return@map clip
        val want = clip.sourceSpanMs
        if (want <= 0L || clip.sourceDurationMs <= want) return@map clip
        val at = Liveliness.bestWindowStart(profile, clip.sourceDurationMs, want)
        if (at == clip.sourceInMs) clip else clip.copy(sourceInMs = at, sourceOutMs = at + want)
    }
}

/** The same over a whole timeline. Nothing moves: every shot keeps the length it had. */
fun TimelineState.withBestBitsKept(profiles: Map<String, LivelinessProfile>): TimelineState =
    if (profiles.isEmpty()) this else copy(clips = clips.withBestBitsKept(profiles)).rippleVideo()
