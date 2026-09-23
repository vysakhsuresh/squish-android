import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/**
 * Media3's SpeedProviderUtil.getDurationAfterSpeedProviderApplied, transcribed
 * from the 1.5.1 source, driven by the SpeedProvider our ramp would hand it.
 * If our duration ever disagrees with this, the clip's end on the strip is in a
 * different place from the clip's end in the rendered file.
 */
fun media3Duration(ramp: SpeedRamp, spanMs: Long): Long {
    val segs = ramp.segments(spanMs)
    if (segs.isEmpty()) return 0L
    // getSpeed(t) / getNextSpeedChangeTimeUs(t), as the provider will implement them.
    fun speedAt(t: Long): Float = segs.last { it.startMs <= t }.speed
    fun nextChange(t: Long): Long = segs.firstOrNull { it.startMs > t }?.startMs ?: Long.MIN_VALUE

    var at = 0L
    var out = 0.0
    while (at < spanMs) {
        var next = nextChange(at)
        if (next == Long.MIN_VALUE) next = Long.MAX_VALUE
        out += (min(next, spanMs) - at) / speedAt(at).toDouble()
        at = next
    }
    return out.roundToLong()
}

fun main() {
    val spans = listOf(0L, 1L, 40L, 41L, 999L, 1000L, 4321L, 60_000L, 600_000L)
    val ramps = buildList {
        add("identity" to SpeedRamp())
        add("flat 1x" to SpeedRamp.flat(1f))
        add("flat 0.25x" to SpeedRamp.flat(0.25f))
        add("flat 4x" to SpeedRamp.flat(4f))
        add("flat min" to SpeedRamp.flat(SpeedRamp.MIN_SPEED))
        add("flat max" to SpeedRamp.flat(SpeedRamp.MAX_SPEED))
        add("out of range" to SpeedRamp.flat(500f))
        for (shape in RampShape.entries) add("preset ${shape.name}" to SpeedRamp.preset(shape, 4321L))
        add("two point" to SpeedRamp(listOf(SpeedPoint(0, 0.5f), SpeedPoint(4321, 2f))))
        add("unsorted" to SpeedRamp(listOf(SpeedPoint(3000, 2f), SpeedPoint(0, 0.5f))))
        add("duplicate times" to SpeedRamp(listOf(SpeedPoint(0, 0.5f), SpeedPoint(0, 3f))))
    }

    // --- 1. Our duration must equal Media3's, exactly. ------------------------
    for ((name, ramp) in ramps) for (span in spans) {
        val ours = ramp.outputDurationMs(span)
        val theirs = media3Duration(ramp, span)
        check(ours == theirs, "$name @${span}ms: duration $ours != Media3 $theirs")
    }

    // --- 2. outputOffsetAt(span) must be the whole duration. ------------------
    for ((name, ramp) in ramps) for (span in spans) {
        val end = ramp.outputOffsetAt(span, span)
        val total = ramp.outputDurationMs(span)
        check(end == total, "$name @${span}ms: offset at end $end != duration $total")
    }

    // --- 3. Round trip. A frame asked for at output time o must come back from
    //        source s with outputOffsetAt(s) landing back on o, within a step.
    val rng = Random(20260923)
    for ((name, ramp) in ramps) {
        val span = 4321L
        val total = ramp.outputDurationMs(span)
        if (total == 0L) continue
        repeat(300) {
            val o = rng.nextLong(0, total + 1)
            val s = ramp.sourceOffsetAt(o, span)
            check(s in 0..span, "$name: source $s outside 0..$span for output $o")
            val back = ramp.outputOffsetAt(s, span)
            // One step of slack: the staircase cannot resolve finer than a tread.
            val slack = (SpeedRamp.STEP_MS / ramp.speedAt(s)).toLong() + 2
            check(abs(back - o) <= slack, "$name: round trip $o -> $s -> $back (slack $slack)")
        }
    }

    // --- 4. Monotonic. Time must never run backwards in either direction. -----
    for ((name, ramp) in ramps) {
        val span = 4321L
        var prev = -1L
        for (s in 0..span step 7) {
            val o = ramp.outputOffsetAt(s, span)
            check(o >= prev, "$name: output went backwards at source $s ($o < $prev)")
            prev = o
        }
        val total = ramp.outputDurationMs(span)
        var prevS = -1L
        for (o in 0..total step 7) {
            val s = ramp.sourceOffsetAt(o, span)
            check(s >= prevS, "$name: source went backwards at output $o ($s < $prevS)")
            prevS = s
        }
    }

    // --- 5. Identity must be free and exact. ----------------------------------
    for (span in spans) {
        check(SpeedRamp().outputDurationMs(span) == span, "identity changed a ${span}ms span")
        check(SpeedRamp.flat(1f).outputDurationMs(span) == span, "flat 1x changed a ${span}ms span")
        check(SpeedRamp().sourceOffsetAt(span / 2, span) == span / 2, "identity moved the midpoint")
    }
    check(SpeedRamp().isIdentity, "empty ramp is not identity")
    check(SpeedRamp.flat(1f).isIdentity, "flat 1x is not identity")
    check(!SpeedRamp.flat(2f).isIdentity, "flat 2x claims to be identity")
    check(!SpeedRamp.preset(RampShape.BulletTime, 4321).isIdentity, "bullet claims identity")

    // --- 6. A flat ramp must stay one segment, or every frame pays for a
    //        shader pass on a clip whose speed is not changing.
    for (speed in listOf(0.5f, 1f, 2f)) {
        val segs = SpeedRamp.flat(speed).segments(60_000L)
        check(segs.size == 1, "flat ${speed}x over 60s became ${segs.size} segments")
    }
    check(SpeedRamp.preset(RampShape.BulletTime, 4321).segments(4321L).size > 50, "bullet ramp is not stepped")

    // --- 7. Speeds are clamped everywhere they can enter. ---------------------
    check(SpeedRamp.flat(500f).flatSpeed == SpeedRamp.MAX_SPEED, "flat did not clamp high")
    check(SpeedRamp.flat(0f).flatSpeed == SpeedRamp.MIN_SPEED, "flat did not clamp low")
    for ((name, ramp) in ramps) for (s in 0..4321L step 13) {
        val v = ramp.speedAt(s)
        check(v >= SpeedRamp.MIN_SPEED && v <= SpeedRamp.MAX_SPEED, "$name: speed $v at $s out of range")
    }
    for ((name, ramp) in ramps) for (seg in ramp.segments(4321L)) {
        check(seg.speed > 0f, "$name: segment speed ${seg.speed} would divide by zero")
        check(seg.endMs > seg.startMs, "$name: empty segment ${seg.startMs}..${seg.endMs}")
    }

    // --- 8. Segments must tile the span with no gap and no overlap. -----------
    for ((name, ramp) in ramps) for (span in spans.filter { it > 0 }) {
        val segs = ramp.segments(span)
        check(segs.first().startMs == 0L, "$name @$span: first segment starts at ${segs.first().startMs}")
        check(segs.last().endMs == span, "$name @$span: last segment ends at ${segs.last().endMs}")
        for (i in 1 until segs.size) {
            check(segs[i].startMs == segs[i - 1].endMs, "$name @$span: gap at segment $i")
        }
    }

    // --- 9. Editing the curve. ------------------------------------------------
    val base = SpeedRamp.flat(1f)
    val added = base.withPoint(2000L, 3f, 4321L)
    check(added.ordered.size == 2, "adding a point did not grow the curve")
    val nudged = added.withPoint(2010L, 4f, 4321L)
    check(nudged.ordered.size == 2, "a point within a tread created a duplicate instead of replacing")
    check(nudged.ordered.any { abs(it.speed - 4f) < 1e-3f }, "the replacement did not take the new speed")
    check(added.withoutPoint(2000L).ordered.size == 1, "removing a point did nothing")
    check(base.withPoint(99_999L, 2f, 4321L).ordered.all { it.atMs <= 4321L }, "a point escaped the clip")

    // --- 10. A ramp must actually change the duration in the expected direction.
    val slow = SpeedRamp.flat(0.5f).outputDurationMs(1000L)
    val fast = SpeedRamp.flat(2f).outputDurationMs(1000L)
    check(slow == 2000L, "0.5x over 1s came out $slow, expected 2000")
    check(fast == 500L, "2x over 1s came out $fast, expected 500")
    val sweep = SpeedRamp.preset(RampShape.Montage, 1000L).outputDurationMs(1000L)
    check(sweep in 400L..900L, "the sweep preset over 1s came out ${sweep}ms, which is not between its ends")

    println("ramp duration table (4321ms source):")
    for ((name, ramp) in ramps) {
        println("  %-22s -> %6d ms  %s".format(name, ramp.outputDurationMs(4321L),
            if (ramp.isRamped) "ramped" else "flat ${"%.2f".format(ramp.flatSpeed)}x"))
    }

    println()
    if (problems.isEmpty()) println("PASS - ramp maths agrees with Media3 and round-trips cleanly")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
