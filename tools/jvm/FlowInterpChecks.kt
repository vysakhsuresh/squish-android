import com.squish.app.media.video.FlowInterp
import com.squish.app.media.video.LumaFrame
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * INSHOT_PARITY P1 / EDGE_PLAN F1: smooth slow motion. An in-between frame
 * drawn from both neighbours moved along the motion, rather than the two
 * cross-faded (which is motion blur, not motion). FlowInterp is the CPU twin
 * of squish_flow_es2.glsl; this executes it on frames whose truth is known.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val W = 320
private const val H = 180
private const val SIDE = 96

/** A textured background, and a textured bright square at [x] - trackable both, and told apart. */
private fun frame(x: Int, y: Int = 42, seed: Int = 7): LumaFrame {
    val px = FloatArray(W * H)
    for (j in 0 until H) for (i in 0 until W) {
        // Noise blurred over 3 px, so it can be matched at sub-pixel steps as a real picture can.
        val bg = 0.15f + 0.3f * smoothNoise(i, j, seed)
        val inSquare = i in x until x + SIDE && j in y until y + SIDE
        px[j * W + i] = if (inSquare) 0.75f + 0.25f * smoothNoise(i - x, j - y, 4242) else bg
    }
    return LumaFrame(W, H, px)
}

private fun hash(i: Int, j: Int, seed: Int): Float {
    var h = i * 374761393 + j * 668265263 + seed * 1442695040
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

private fun smoothNoise(i: Int, j: Int, seed: Int): Float {
    var s = 0f
    for (dy in -1..1) for (dx in -1..1) s += hash((i + dx) / 3, (j + dy) / 3, seed)
    return s / 9f
}

/** Where the bright square sits: the mean column of pixels above 0.7. */
private fun squareX(f: LumaFrame): Float {
    var sum = 0.0; var n = 0
    for (j in 0 until f.height) for (i in 0 until f.width) if (f.pixels[j * f.width + i] > 0.7f) { sum += i; n++ }
    return if (n == 0) Float.NaN else (sum / n).toFloat() - (SIDE - 1) / 2f
}

private fun sad(a: LumaFrame, b: LumaFrame): Double = a.pixels.indices.sumOf { abs(a.pixels[it] - b.pixels[it]).toDouble() }

private fun crossFade(a: LumaFrame, b: LumaFrame, t: Float) =
    LumaFrame(a.width, a.height, FloatArray(a.pixels.size) { a.pixels[it] * (1 - t) + b.pixels[it] * t })

/**
 * Flat colour bars - nothing to match - with a thin bright line across them
 * moving [shift] px down: what tore on the phone (25 Oct, testsrc2 slowed to
 * 0.25x), the line broken into segments in every in-between frame.
 */
private fun barsAndLine(shift: Int, turn: Float = 0f): LumaFrame {
    val px = FloatArray(W * H)
    for (j in 0 until H) for (i in 0 until W) {
        val bar = 0.2f + 0.1f * (i / 46)
        // [turn] tips the line about its left end: its motion grows along it, as testsrc2's does.
        val lineY = 40 + i * (0.3f + turn) + shift
        px[j * W + i] = if (kotlin.math.abs(j - lineY) < 2f) 0.95f else bar
    }
    return LumaFrame(W, H, px)
}

/** The worst of the in-between frame's errors against [truth], summed over a 16 px column: where a tear would show. */
private fun worstColumn(f: LumaFrame, truth: LumaFrame): Double {
    var worst = 0.0
    for (c in 0 until W / 16) {
        var e = 0.0
        for (j in 0 until H) for (i in c * 16 until c * 16 + 16) e += abs(f.pixels[j * W + i] - truth.pixels[j * W + i])
        worst = maxOf(worst, e)
    }
    return worst
}

fun main() {
    // A thin line moving over flat bars: no worse than the cross-fade anywhere along it.
    run {
        val a = barsAndLine(0); val b = barsAndLine(8); val truth = barsAndLine(4)
        val mid = FlowInterp.interpolate(a, b, FlowInterp.field(a, b), 0.5f)
        val fade = crossFade(a, b, 0.5f)
        val flowWorst = worstColumn(mid, truth); val fadeWorst = worstColumn(fade, truth)
        check(flowWorst <= fadeWorst * 1.05, "a thin line moving over flat bars tears: worst column $flowWorst against a cross-fade's $fadeWorst")
        // Turning as it goes: no worse than the cross-fade anywhere along it either.
        val ta = barsAndLine(0); val tb = barsAndLine(0, turn = 0.05f); val tTruth = barsAndLine(0, turn = 0.025f)
        val tMid = FlowInterp.interpolate(ta, tb, FlowInterp.field(ta, tb), 0.5f)
        val tFlow = worstColumn(tMid, tTruth); val tFade = worstColumn(crossFade(ta, tb, 0.5f), tTruth)
        check(tFlow <= tFade * 1.05, "a turning line tears: worst column $tFlow against a cross-fade's $tFade")
    }

    // A square moving 12 px a frame: the frame half way is the square at +6.
    val a = frame(100)
    val b = frame(112)
    val truth = frame(106)
    val field = FlowInterp.field(a, b)
    val mid = FlowInterp.interpolate(a, b, field, 0.5f)
    val at = squareX(mid)
    check(abs(at - 106f) <= 1f, "the square half way is at $at, not 106")
    val flowError = sad(mid, truth)
    val fadeError = sad(crossFade(a, b, 0.5f), truth)
    check(flowError < fadeError * 0.6, "the in-between frame is no closer to the truth than a cross-fade: $flowError against $fadeError")
    // A quarter of the way: +3.
    val quarter = squareX(FlowInterp.interpolate(a, b, field, 0.25f))
    check(abs(quarter - 103f) <= 1f, "the square a quarter of the way is at $quarter, not 103")
    // The field holds most of the picture, and says so.
    check(FlowInterp.heldShare(field) > 0.6f, "the field held only ${FlowInterp.heldShare(field)} of a trackable picture")

    // Nothing moving: the frame comes out as it went in.
    val still = FlowInterp.interpolate(a, a, FlowInterp.field(a, a), 0.5f)
    check(sad(still, a) < 1e-3 * a.pixels.size, "a still scene changed: ${sad(still, a)}")

    // A cut: nothing matches, so no motion is invented - the cross-fade.
    val other = frame(10, y = 70, seed = 991)
    val cutField = FlowInterp.field(a, other)
    val cut = FlowInterp.interpolate(a, other, cutField, 0.5f)
    val fade = crossFade(a, other, 0.5f)
    check(sad(cut, fade) < 0.02 * a.pixels.size, "across a cut the in-between frame is not the cross-fade: ${sad(cut, fade)}")
    check(FlowInterp.usesFlow(cutField) == false, "a cut was trusted as motion (held ${FlowInterp.heldShare(cutField)})")
    check(FlowInterp.usesFlow(field), "a trackable move was not trusted")

    // A small thing moving fast over still ground (27 Oct, the edgemedia
    // fast-motion clip on the moto: a dot drawn twice at half strength in
    // every in-between frame). A 6 px dot - a fraction of one block - moving
    // 36 px: the frame half way has it once, at +18, nearly whole, and nothing
    // where it was or where it is going.
    run {
        fun dotFrame(x: Int): LumaFrame {
            val px = FloatArray(W * H)
            for (j in 0 until H) for (i in 0 until W) {
                val bg = 0.15f + 0.3f * smoothNoise(i, j, 7)
                px[j * W + i] = if (i in x until x + 6 && j in 80 until 86) 0.97f else bg
            }
            return LumaFrame(W, H, px)
        }
        val da = dotFrame(100); val db = dotFrame(136)
        val dm = FlowInterp.interpolate(da, db, FlowInterp.field(da, db), 0.5f)
        fun meanIn(f: LumaFrame, x: Int) = (x until x + 6).sumOf { i -> (80 until 86).sumOf { j -> f.pixels[j * W + i].toDouble() } } / 36.0
        val atHalf = meanIn(dm, 118); val atStart = meanIn(dm, 100); val atEnd = meanIn(dm, 136)
        val bgHere = meanIn(da, 118)
        check(atHalf > 0.85, "a small fast dot is not at the half-way point in the frame between (it reads $atHalf there, the dot is 0.97)")
        check(atStart < bgHere + 0.12 && atEnd < bgHere + 0.12, "a small fast dot left a ghost: $atStart where it was, $atEnd where it went, the ground ${"%.2f".format(java.util.Locale.ROOT, bgHere)}")
    }

    // The same dot as the phone draws it (27 Oct evening, the 720p export of
    // fast_motion_1080p30 at 0.25x): read back point-sampled at READ_WIDTH as
    // FrameBlendEffect.readLuma reads it, and drawn at full size. The
    // in-between dot came out torn into pieces with grey arcs either side:
    // its move, found at 160 px where the dot is under five pixels, was a
    // pixel or more out, its two copies missed each other at full size, and
    // every pixel where they missed fell back to the cross-fade; over the
    // smooth sky any shift of the ground matched too, and drew the dot's old
    // and new places back in. Round, moving on a diagonal by a fraction of a
    // pixel, fast and slow: the frame between holds it whole and nothing else.
    run {
        // Two dots: a bright 40 px one at 720p on short moves, and the clip's own
        // as the phone has it - 56 px at 1080p, a little brighter than its
        // teal-to-blue ground, moving 83 px a frame, mostly up (measured off
        // fast_motion_1080p30: 28 px across moving (17, -38) at 960 wide), and
        // slowing near the top of its path until its two copies overlap.
        class Case(val fw: Int, val fh: Int, val rad: Float, val dot: Float, val lo: Float, val span: Float, val moves: List<Pair<Float, Float>>)
        for (case in listOf(
            Case(1280, 720, 20f, 0.9f, 0.2f, 0.1f, listOf(37f to -23f, 29f to 31f, -45f to 13f)),
            Case(1920, 1080, 28f, 0.68f, 0.25f, 0.07f, listOf(34f to -76f, 31f to -71f, 34f to -46f, 20f to -31f, 11f to -15f))
        )) {
        val fw = case.fw; val fh = case.fh; val rad = case.rad
        fun ground(i: Int, j: Int) = case.lo + case.span * (i + j).toFloat() / (fw + fh)
        val contrast = case.dot - case.lo
        fun dotAt(cx: Float, cy: Float): LumaFrame {
            val px = FloatArray(fw * fh)
            for (j in 0 until fh) for (i in 0 until fw) {
                val d = kotlin.math.hypot(i + 0.5f - cx, j + 0.5f - cy)
                // Anti-aliased edge, as a drawn dot has.
                val cover = (rad + 0.5f - d).coerceIn(0f, 1f)
                px[j * fw + i] = ground(i, j) * (1 - cover) + case.dot * cover
            }
            return LumaFrame(fw, fh, px)
        }
        fun pointSampled(f: LumaFrame): LumaFrame {
            val aw = FlowInterp.READ_WIDTH; val ah = aw * fh / fw
            return LumaFrame(aw, ah, FloatArray(aw * ah) { k ->
                val i = ((k % aw + 0.5f) * fw / aw).toInt(); val j = ((k / aw + 0.5f) * fh / ah).toInt()
                f.pixels[j * fw + i]
            })
        }
        val s = fw.toFloat() / FlowInterp.READ_WIDTH
        for ((mx, my) in case.moves) {
            val ax = fw * 0.39f + 0.3f; val ay = fh * 0.5f + 0.6f
            val fa = dotAt(ax, ay); val fb = dotAt(ax + mx, ay + my)
            val small = FlowInterp.field(pointSampled(fa), pointSampled(fb))
            val full = FlowInterp.Field(small.cols, small.rows, small.cell * s,
                FloatArray(small.dx.size) { small.dx[it] * s }, FloatArray(small.dy.size) { small.dy[it] * s },
                small.confidence, small.trust,
                FloatArray(small.dx2.size) { small.dx2[it] * s }, FloatArray(small.dy2.size) { small.dy2[it] * s })
            for (t in listOf(0.25f, 0.5f, 0.75f)) {
                val m = FlowInterp.interpolate(fa, fb, full, t)
                val cx = ax + t * mx; val cy = ay + t * my
                var inside = 0; var insideBad = 0; var outside = 0; var outsideBad = 0
                for (j in (cy - 110).toInt()..(cy + 110).toInt()) for (i in (cx - 100).toInt()..(cx + 100).toInt()) {
                    val d = kotlin.math.hypot(i + 0.5f - cx, j + 0.5f - cy)
                    val v = m.pixels[j * fw + i]
                    if (d < rad - 3) { inside++; if (abs(v - case.dot) > 0.25f * contrast) insideBad++ }
                    else if (d > rad + 3) { outside++; if (abs(v - ground(i, j)) > 0.2f * contrast) outsideBad++ }
                }
                val what = "a ${(2 * rad).toInt()} px dot moving ($mx, $my) at ${fh}p"
                check(insideBad <= inside * 0.03, "$what is torn at t=$t: $insideBad of $inside pixels inside it are not the dot")
                check(outsideBad <= outside * 0.005, "$what leaves a ghost at t=$t: $outsideBad of $outside pixels round it are not the ground")
            }
        }
        }
    }

    // Grain, which changes scattered pixels past CHANGED in every frame: no
    // small thing is invented from it, and the frame between stays the
    // cross-fade of the two to within a few percent.
    run {
        fun grainy(seed: Int): LumaFrame {
            val base = frame(100)
            return LumaFrame(W, H, FloatArray(W * H) { k -> (base.pixels[k] + (hash(k % W, k / W, seed) - 0.5f) * 0.3f).coerceIn(0f, 1f) })
        }
        val ga = grainy(11); val gb = grainy(12)
        val gm = FlowInterp.interpolate(ga, gb, FlowInterp.field(ga, gb), 0.5f)
        val gFade = crossFade(ga, gb, 0.5f)
        val off = sad(gm, gFade) / sad(ga, gb)
        check(off < 0.08, "grain was taken for motion: the frame between is ${"%.3f".format(java.util.Locale.ROOT, off)} of the two frames' difference away from their cross-fade")
    }

    // The packed field the shader reads gives the field back.
    val packed = FlowInterp.pack(field)
    val back = FlowInterp.unpack(packed, field.cols, field.rows)
    var worst = 0f
    for (k in field.dx.indices) worst = maxOf(worst, abs(back.dx[k] - field.dx[k]), abs(back.dy[k] - field.dy[k]))
    check(worst < 0.02f, "the packed field is off by $worst analysis pixels")
    // And the second moves, packed for the shader's third texture, come back as the CPU chose them.
    run {
        val da = FlowInterp.shrink(frame(100), 1); val fd = FlowInterp.field(da, frame(112))
        val sb = FlowInterp.unpack(FlowInterp.packSecond(fd), fd.cols, fd.rows)
        var w2 = 0f
        for (k in fd.dx2.indices) w2 = maxOf(w2, abs(sb.dx[k] - fd.dx2[k]), abs(sb.dy[k] - fd.dy2[k]))
        check(w2 < 0.02f, "the packed second moves are off by $w2 analysis pixels")
    }

    if (problems.isEmpty()) println("FlowInterpChecks: all checks passed (half way at $at, error $flowError against a cross-fade's $fadeError)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
