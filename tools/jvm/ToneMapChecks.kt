import com.squish.app.av.ToneMap

/**
 * HDR to SDR for Reverse, the filmstrip and thumbnails (ToneMap), executed:
 * the tables squishav.c looks up, and the per-pixel loop it runs over them
 * (ToneMap.pixel is that loop, line for line).
 *
 *   sh tools/jvm/jc.sh ToneMapChecks app/src/main/java/com/squish/app/av/ToneMap.kt tools/jvm/ToneMapChecks.kt
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun px(t: FloatArray, r: Double, g: Double, b: Double, srgb: Boolean = false): IntArray {
    val out = IntArray(3)
    ToneMap.pixel(t, (r * 65535).toInt(), (g * 65535).toInt(), (b * 65535).toInt(), srgb, out)
    return out
}

/** The PQ signal for [nits], by bisection on the EOTF. */
private fun pqSignal(nits: Double): Double {
    var lo = 0.0
    var hi = 1.0
    repeat(60) {
        val mid = (lo + hi) / 2
        if (ToneMap.pqToNits(mid) < nits) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

fun main() {
    val hlg = ToneMap.tables(ToneMap.TRC_HLG)!!
    val pq = ToneMap.tables(ToneMap.TRC_PQ)!!
    check(ToneMap.tables(1) == null, "an SDR transfer was given tables")
    check(ToneMap.tables(0) == null, "an unknown transfer was given tables")
    check(hlg.size == ToneMap.LENGTH && pq.size == ToneMap.LENGTH, "the packed table is not its declared length")
    check(hlg.none { it.isNaN() || it.isInfinite() } && pq.none { it.isNaN() || it.isInfinite() }, "a table holds a NaN or an infinity")

    // ---- The transfer functions themselves, at their published points. -----
    check(kotlin.math.abs(ToneMap.hlgToScene(0.5) - 1.0 / 12) < 1e-9, "HLG 0.5 is not 1/12 of scene light")
    check(kotlin.math.abs(ToneMap.hlgToScene(1.0) - 1.0) < 1e-6, "HLG 1.0 is not full scene light: ${ToneMap.hlgToScene(1.0)}")
    check(kotlin.math.abs(ToneMap.pqToNits(1.0) - 10000) < 1e-3, "PQ 1.0 is not 10000 nits")
    check(kotlin.math.abs(ToneMap.pqToNits(0.58) - 203) < 12, "PQ 0.58 is not about 203 nits: ${ToneMap.pqToNits(0.58)}")

    // ---- The curve: identity to the knee, continuous, the peak on white. ---
    check(ToneMap.curve(0.5) == 0.5, "the curve is not the identity below the knee")
    check(kotlin.math.abs(ToneMap.curve(ToneMap.KNEE + 1e-6) - ToneMap.KNEE) < 1e-5, "the curve jumps at the knee")
    val slope = (ToneMap.curve(ToneMap.KNEE + 1e-4) - ToneMap.curve(ToneMap.KNEE)) / 1e-4
    check(kotlin.math.abs(slope - 1) < 0.01, "the curve bends at the knee rather than after it: slope $slope")
    check(kotlin.math.abs(ToneMap.curve(ToneMap.top) - 1) < 1e-9, "the peak does not land on white")
    var prev = -1.0
    for (i in 0..1000) {
        val y = ToneMap.curve(i * ToneMap.top / 1000)
        check(y >= prev, "the curve falls at ${i * ToneMap.top / 1000}")
        prev = y
    }

    for ((name, t) in listOf("HLG" to hlg, "PQ" to pq)) {
        // Black is black.
        check(px(t, 0.0, 0.0, 0.0).all { it == 0 }, "$name black is not black: ${px(t, 0.0, 0.0, 0.0).toList()}")
        // A grey stays grey (the primaries' matrix rows each sum to one) and
        // gets no darker as the signal rises.
        var last = -1
        for (i in 0..100) {
            val v = i / 100.0
            val o = px(t, v, v, v)
            check(o.max() - o.min() <= 1, "$name grey $v came out tinted: ${o.toList()}")
            check(o[1] >= last, "$name grey falls at $v")
            last = o[1]
        }
        // Full signal is white.
        check(px(t, 1.0, 1.0, 1.0).all { it >= 254 }, "$name full signal is not white: ${px(t, 1.0, 1.0, 1.0).toList()}")
        // A bright saturated colour keeps its hue: a BT.2020 orange is still
        // red over green over blue, its green not clipped away to a red.
        // (Given as light for PQ - 600, 240 and 30 nits - since PQ's signal
        // is so far from linear that 0.9 and 0.55 are 3900 nits and 150.)
        val orange = if (t === pq) px(t, pqSignal(600.0), pqSignal(240.0), pqSignal(30.0)) else px(t, 0.9, 0.55, 0.1)
        check(orange[0] > orange[1] && orange[1] > orange[2] && orange[1] > 40, "$name orange lost its hue: ${orange.toList()}")
        // BT.2020's green is outside BT.709 and comes in clipped, still green.
        val green = px(t, 0.0, 0.7, 0.0)
        check(green[1] > 150 && green[0] == 0 && green[2] == 0, "$name 2020 green is not a 709 green: ${green.toList()}")
        // The sRGB table is the same light through a different curve: lighter
        // in the darks than BT.709's, the same at the ends.
        val dark709 = px(t, 0.2, 0.2, 0.2)[1]
        val darkSrgb = px(t, 0.2, 0.2, 0.2, srgb = true)[1]
        check(darkSrgb > dark709, "$name sRGB is not lighter in the darks: $darkSrgb vs $dark709")
        // The table's top index is reached without running off its end.
        runCatching { px(t, 1.0, 1.0, 1.0) }.onFailure { problems += "$name full signal threw $it" }
        runCatching { ToneMap.pixel(t, 65535, 65535, 65535, false, IntArray(3)) }.onFailure { problems += "$name 65535 threw $it" }
    }

    // ---- The primaries are converted: BT.2020's red is redder than BT.709's,
    // so in 709 it needs more red than its own grey has (x1.66 through the
    // matrix, against x0.77 from HLG's gain on its lower luminance). Without
    // the matrix it came out darker than the grey.
    check(px(hlg, 0.5, 0.0, 0.0)[0] > px(hlg, 0.5, 0.5, 0.5)[0], "HLG 2020 red is not widened into 709")

    // ---- A highlight keeps the hue it has when dim: the curve is on the
    // largest channel and the others scale with it. Taken on each channel,
    // a bright orange's red was squeezed and its green was not, and it went
    // to yellow-white.
    fun linear(v: Int): Double {
        val e = v / 255.0
        return if (e < 0.081) e / 4.5 else Math.pow((e + 0.099) / 1.099, 1 / 0.45)
    }
    fun hue(o: IntArray) = linear(o[1]) / linear(o[0])
    val dimOrange = px(pq, pqSignal(60.0), pqSignal(24.0), pqSignal(3.0))
    val brightOrange = px(pq, pqSignal(600.0), pqSignal(240.0), pqSignal(30.0))
    check(kotlin.math.abs(hue(brightOrange) / hue(dimOrange) - 1) < 0.1,
        "a bright orange changed hue: green/red ${hue(brightOrange)} against ${hue(dimOrange)} dim")

    // ---- Where white lands: SDR white is near white, not grey and not clipped.
    // HLG's reference white is 75% signal (BT.2408), about 203 nits on a
    // 1000-nit display through the system gamma.
    val hlgWhite = px(hlg, 0.75, 0.75, 0.75)[1]
    check(hlgWhite in 225..250, "HLG reference white is $hlgWhite, not near white")
    val pqWhite = px(pq, pqSignal(203.0), pqSignal(203.0), pqSignal(203.0))[1]
    check(pqWhite in 225..250, "PQ 203 nits is $pqWhite, not near white")
    check(kotlin.math.abs(hlgWhite - pqWhite) <= 8, "HLG and PQ disagree on white: $hlgWhite vs $pqWhite")
    // Below the knee light passes as it is: PQ 18% grey of SDR white.
    val g18 = pqSignal(0.18 * 203)
    val o18 = px(pq, g18, g18, g18)[1]
    val want18 = (ToneMap.bt709Oetf(0.18) * 255).toInt()
    check(kotlin.math.abs(o18 - want18) <= 2, "PQ 18% grey is $o18, not $want18")
    // The peak is white, and past it stays white.
    check(px(pq, pqSignal(1000.0), pqSignal(1000.0), pqSignal(1000.0))[1] >= 254, "PQ 1000 nits is not white")
    // Highlights between white and the peak are not all one value.
    val p400 = px(pq, pqSignal(400.0), pqSignal(400.0), pqSignal(400.0))[1]
    check(p400 in (pqWhite + 3)..253, "PQ 400 nits ($p400) is not between white ($pqWhite) and the peak")

    if (problems.isEmpty()) {
        println("PASS: HLG and PQ come to BT.709 and sRGB with black black, grey grey, white near white, hue kept and the peak rolled into white")
    } else {
        problems.forEach { println("FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
