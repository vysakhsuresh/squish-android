package com.squish.app.timeline

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One control point on a clip's speed curve.
 *
 * [atMs] is an offset into the clip's own **source** window, not the timeline.
 * Source time is the only clock that survives the curve being edited: express a
 * point in output time and changing the speed anywhere before it moves the point
 * as well, so dragging one handle would drag every handle after it.
 */
data class SpeedPoint(val atMs: Long, val speed: Float)

/** A stretch of source time played at one rate. */
data class SpeedSegment(val startMs: Long, val endMs: Long, val speed: Float) {
    val sourceLengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * How fast a clip plays, and where that changes.
 *
 * Empty is 1x. One point is a constant rate. Two or more is a ramp: the rate
 * moves linearly between the points, holding the first rate before the first
 * point and the last rate after the last one.
 *
 * ## Why it is a staircase underneath
 *
 * Media3 drives both the video and the audio from a `SpeedProvider`, which is a
 * step function - it answers "what rate at this instant" and "when does it next
 * change". A continuous curve therefore has to become steps somewhere, and the
 * only safe place is here, once, so that the rate the preview plays, the length
 * the strip draws and the file the encoder writes all come from the same list of
 * segments. Computing the duration from the smooth curve and rendering from the
 * steps would put the clip's end in a different place on screen than in the file,
 * and that error would grow with every ramp in the edit.
 *
 * [STEP_MS] is the tread width. At 40ms a 2-second ramp is fifty steps, which is
 * below the threshold where a rate change is audible as a step in pitch, and the
 * audio processor's own windowing smooths what is left.
 */
data class SpeedRamp(val points: List<SpeedPoint> = emptyList()) {

    /** Sorted and clamped. The editor keeps this true; evaluation relies on it. */
    val ordered: List<SpeedPoint>
        get() = points
            .map { it.copy(speed = it.speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
            .sortedBy { it.atMs }

    val isRamped: Boolean
        get() = ordered.size >= 2 && ordered.any { abs(it.speed - ordered.first().speed) > 1e-3f }

    /** The single rate this clip plays at, when it is not ramped. */
    /**
     * The slowest rate anywhere in this clip.
     *
     * A ramp is only as smooth as its slowest moment - the stepping shows where
     * the footage is thinnest, not on average - so anything judging smoothness
     * has to ask for this rather than for the flat rate.
     */
    val slowestSpeed: Float
        get() = ordered.minOfOrNull { it.speed }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: flatSpeed

    val flatSpeed: Float
        get() = ordered.firstOrNull()?.speed?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f

    val isIdentity: Boolean get() = !isRamped && abs(flatSpeed - 1f) < 1e-3f

    /** The rate at a source offset, on the smooth curve. */
    fun speedAt(sourceMs: Long): Float {
        val pts = ordered
        if (pts.isEmpty()) return 1f
        if (pts.size == 1) return pts[0].speed
        if (sourceMs <= pts.first().atMs) return pts.first().speed
        if (sourceMs >= pts.last().atMs) return pts.last().speed
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (sourceMs <= b.atMs) {
                val span = (b.atMs - a.atMs).coerceAtLeast(1L)
                val t = (sourceMs - a.atMs).toFloat() / span
                return (a.speed + (b.speed - a.speed) * t).coerceIn(MIN_SPEED, MAX_SPEED)
            }
        }
        return pts.last().speed
    }

    /**
     * The curve as steps over [spanMs] of source time.
     *
     * A flat ramp is one segment, which matters: `SpeedChangeEffect` can only
     * recognise itself as a no-op when the provider reports no upcoming change, so
     * chopping an unramped clip into fifty identical steps would put a shader pass
     * on every frame of a clip that is not changing speed at all.
     */
    fun segments(spanMs: Long): List<SpeedSegment> {
        val span = spanMs.coerceAtLeast(0L)
        if (span == 0L) return emptyList()
        if (!isRamped) return listOf(SpeedSegment(0L, span, flatSpeed))

        val out = ArrayList<SpeedSegment>((span / STEP_MS).toInt() + 2)
        var at = 0L
        while (at < span) {
            val end = (at + STEP_MS).coerceAtMost(span)
            // Sampled at the segment's start, which is where Media3 samples it.
            out.add(SpeedSegment(at, end, speedAt(at)))
            at = end
        }
        return out
    }

    /**
     * How long [spanMs] of source takes to play.
     *
     * This is SpeedProviderUtil.getDurationAfterSpeedProviderApplied, deliberately:
     * accumulate each segment's source length over its rate as a double, round
     * once at the end. Rounding per segment instead would drift by up to half a
     * millisecond per step, which over a long ramp is a visible gap on the strip.
     */
    fun outputDurationMs(spanMs: Long): Long {
        val segs = segments(spanMs)
        if (segs.isEmpty()) return 0L
        var total = 0.0
        for (s in segs) total += s.sourceLengthMs / s.speed.toDouble()
        return total.roundToLong().coerceAtLeast(0L)
    }

    /**
     * Where a source offset lands in the played clip.
     *
     * A negative offset is footage before the window - what dragging a head
     * handle outwards is about to reveal - and comes back negative. It used to be
     * clamped to zero, so revealing earlier footage never moved the clip's start
     * and the clip grew out of its tail instead, over whatever came next. The
     * curve holds its opening rate before its first point, which is also the rate
     * [sliced] gives the revealed stretch, so the two agree on how long it plays.
     */
    fun outputOffsetAt(sourceMs: Long, spanMs: Long): Long {
        if (sourceMs < 0L) return -((-sourceMs) / speedAt(0L).toDouble()).roundToLong()
        val target = sourceMs.coerceIn(0L, spanMs.coerceAtLeast(0L))
        var total = 0.0
        for (s in segments(spanMs)) {
            if (target >= s.endMs) {
                total += s.sourceLengthMs / s.speed.toDouble()
            } else {
                total += (target - s.startMs).coerceAtLeast(0L) / s.speed.toDouble()
                break
            }
        }
        return total.roundToLong().coerceAtLeast(0L)
    }

    /**
     * The inverse: which source frame is on screen at a moment of playback.
     *
     * This is the one the preview asks on every tick, and the one that makes a
     * ramp look right rather than merely finish at the right time.
     */
    fun sourceOffsetAt(outputMs: Long, spanMs: Long): Long {
        val span = spanMs.coerceAtLeast(0L)
        if (span == 0L) return 0L
        val target = outputMs.coerceAtLeast(0L).toDouble()
        var elapsed = 0.0
        for (s in segments(span)) {
            val segOut = s.sourceLengthMs / s.speed.toDouble()
            if (target < elapsed + segOut) {
                val into = (target - elapsed) * s.speed
                return (s.startMs + into).roundToLong().coerceIn(0L, span)
            }
            elapsed += segOut
        }
        return span
    }

    /**
     * The curve over part of the clip, re-based so the kept range starts at zero.
     *
     * Every edit that moves a clip's source window has to bring the curve with it,
     * or the ramp stays where it was in the file while the footage slides under
     * it. A razor cut is the clearest case: without this, cutting a ramped shot in
     * half gives two clips that both start from the beginning of the curve, so the
     * slow part happens twice.
     *
     * The ends are pinned to whatever the curve read there, so a slice through the
     * middle of a ramp keeps its rate continuous across the cut instead of jumping
     * back to the shape's starting rate.
     *
     * [fromMs] may be negative: a head trim that reveals earlier footage. The
     * revealed stretch plays at the curve's opening rate, the same rate
     * [outputOffsetAt] assumes for it.
     */
    fun sliced(fromMs: Long, toMs: Long): SpeedRamp {
        if (!isRamped) return this
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs).coerceAtLeast(from)
        if (to == from) return flattenedTo(speedAt(from))

        val inner = ordered
            .filter { it.atMs > from && it.atMs < to }
            .map { it.copy(atMs = it.atMs - from) }
        val head = SpeedPoint(0L, speedAt(from))
        val tail = SpeedPoint(to - from, speedAt(to))
        return SpeedRamp(listOf(head) + inner + tail)
    }

    /** Replaces the curve with a single rate, keeping it un-ramped. */
    fun flattenedTo(speed: Float): SpeedRamp =
        SpeedRamp(listOf(SpeedPoint(0L, speed.coerceIn(MIN_SPEED, MAX_SPEED))))

    /**
     * Adds or moves a point.
     *
     * Two points closer together than [STEP_MS] cannot be told apart by the
     * staircase, so a new point that near an existing one replaces it rather than
     * creating a pair that argue over the same tread.
     */
    fun withPoint(atMs: Long, speed: Float, spanMs: Long): SpeedRamp {
        val at = atMs.coerceIn(0L, spanMs.coerceAtLeast(0L))
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val kept = ordered.filterNot { abs(it.atMs - at) < STEP_MS }
        return SpeedRamp((kept + SpeedPoint(at, clamped)).sortedBy { it.atMs })
    }

    fun withoutPoint(atMs: Long): SpeedRamp =
        SpeedRamp(ordered.filterNot { it.atMs == atMs })

    /**
     * A point dragged: the one at [fromMs] taken to [toMs] at [speed]. What the
     * curve editor does with a finger on a point. It stops short of its
     * neighbours by a tread, so two points never argue over one step and the
     * order of the points - which is the shape of the curve - is kept.
     */
    fun withPointMoved(fromMs: Long, toMs: Long, speed: Float, spanMs: Long): SpeedRamp {
        val pts = ordered
        val index = pts.indexOfFirst { it.atMs == fromMs }
        if (index < 0) return withPoint(toMs, speed, spanMs)
        val floor = pts.getOrNull(index - 1)?.let { it.atMs + STEP_MS } ?: 0L
        val ceiling = pts.getOrNull(index + 1)?.let { it.atMs - STEP_MS } ?: spanMs.coerceAtLeast(0L)
        val at = if (ceiling < floor) pts[index].atMs else toMs.coerceIn(floor, ceiling)
        val moved = pts.toMutableList()
        moved[index] = SpeedPoint(at, speed.coerceIn(MIN_SPEED, MAX_SPEED))
        return SpeedRamp(moved)
    }

    companion object {
        const val MIN_SPEED = 0.1f
        /**
         * A hundred times: a minute of footage as a half-second flash. It was ten,
         * which is where a time-lapse of a drive starts rather than ends.
         */
        const val MAX_SPEED = 100f
        const val STEP_MS = 40L

        val Normal = SpeedRamp()

        /** A constant rate, which is what the speed slider produces. */
        fun flat(speed: Float): SpeedRamp = SpeedRamp(listOf(SpeedPoint(0L, speed)))

        /**
         * The ready-made shapes, laid across a clip of [spanMs].
         *
         * Fractions rather than absolute times so a preset means the same thing on
         * a two-second clip and a two-minute one.
         */
        fun preset(shape: RampShape, spanMs: Long): SpeedRamp {
            val span = spanMs.coerceAtLeast(1L)
            fun at(f: Double) = (span * f).toLong()
            val points = when (shape) {
                RampShape.Normal -> return Normal
                // Slow, then release. The single most-used shape there is.
                RampShape.SlowStart -> listOf(
                    SpeedPoint(0L, 0.35f),
                    SpeedPoint(at(0.35), 1f),
                    SpeedPoint(span, 1f)
                )
                RampShape.SlowEnd -> listOf(
                    SpeedPoint(0L, 1f),
                    SpeedPoint(at(0.65), 1f),
                    SpeedPoint(span, 0.35f)
                )
                // Full speed in, hold the middle, full speed out: the hero beat.
                RampShape.BulletTime -> listOf(
                    SpeedPoint(0L, 2f),
                    SpeedPoint(at(0.3), 0.25f),
                    SpeedPoint(at(0.7), 0.25f),
                    SpeedPoint(span, 2f)
                )
                // The gentler hero: normal in, a held moment in the middle, normal out.
                RampShape.Hero -> listOf(
                    SpeedPoint(0L, 1f),
                    SpeedPoint(at(0.4), 0.4f),
                    SpeedPoint(at(0.6), 0.4f),
                    SpeedPoint(span, 1f)
                )
                // The opposite: rush through the middle of a long take.
                RampShape.Jump -> listOf(
                    SpeedPoint(0L, 1f),
                    SpeedPoint(at(0.25), 4f),
                    SpeedPoint(at(0.75), 4f),
                    SpeedPoint(span, 1f)
                )
                RampShape.Montage -> listOf(
                    SpeedPoint(0L, 0.5f),
                    SpeedPoint(span, 3f)
                )
                // A rush in that lands on normal speed, and its mirror.
                RampShape.FlashIn -> listOf(
                    SpeedPoint(0L, 4f),
                    SpeedPoint(at(0.3), 1f),
                    SpeedPoint(span, 1f)
                )
                RampShape.FlashOut -> listOf(
                    SpeedPoint(0L, 1f),
                    SpeedPoint(at(0.7), 1f),
                    SpeedPoint(span, 4f)
                )
            }
            return SpeedRamp(points)
        }
    }
}

/**
 * The ramp shapes offered as one tap, named the way every editor names them so
 * a curve learnt elsewhere is found here. The shape [preset] lays for each is
 * fractions of the clip; the names are not persisted, only the points are.
 */
enum class RampShape(val label: String, val hint: String) {
    Normal("Normal", "One rate, no ramp"),
    Montage("Montage", "Slow to fast across the whole clip"),
    Hero("Hero", "Normal in, held slow through the moment, normal out"),
    BulletTime("Bullet", "Fast in, slow through the middle, fast out"),
    Jump("Jump cut", "Rushes the middle of a long take"),
    FlashIn("Flash in", "Rushes in and lands on normal speed"),
    FlashOut("Flash out", "Normal speed, then rushes out"),
    SlowStart("Slow in", "Starts slow, releases to full speed"),
    SlowEnd("Slow out", "Full speed, settling into slow motion")
}
