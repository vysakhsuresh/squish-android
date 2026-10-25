import com.squish.app.media.video.MaskRefine
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F7: cut-out masks made cleaner. The guided filter pulls a soft,
 * misplaced edge onto the picture's own edge without spreading it, and leaves
 * a flat mask flat; the temporal pass takes isolated flips out (the outline
 * no longer boils) while a person who moves is followed without lag.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val W = 96
private const val H = 64

/** Where a row's mask crosses one half, by interpolation: the edge's position. */
private fun crossing(row: FloatArray, near: Float = row.size / 2f): Float {
    // The crossing nearest [near]: a stray pixel elsewhere in the row is not the edge.
    var best = Float.NaN
    for (x in 1 until row.size) {
        val a = row[x - 1]; val b = row[x]
        if ((a - 0.5f) * (b - 0.5f) <= 0f && a != b) {
            val at = x - 1 + (0.5f - a) / (b - a)
            if (best.isNaN() || abs(at - near) < abs(best - near)) best = at
        }
    }
    return best
}

/** How many pixels the mask takes to go from a tenth to nine tenths along a row. */
private fun width(row: FloatArray): Int = row.indexOfFirst { it >= 0.9f } - row.indexOfFirst { it >= 0.1f }

fun main() {
    // A picture with an edge at x = 48 (dark left, bright right, with a little texture),
    // and a mask that is soft (a 6 px ramp, two of the 256-wide model's cells) and two pixels off it, as the model answers.
    val guide = FloatArray(W * H) { i -> val x = i % W; (if (x >= 48) 0.8f else 0.2f) + 0.02f * ((i * 7919) % 5) / 5f }
    val mask = FloatArray(W * H) { i -> val x = i % W; ((x - 50 + 3) / 6f).coerceIn(0f, 1f) }
    val out = MaskRefine.guided(guide, mask, W, H)
    val mid = H / 2
    val before = mask.copyOfRange(mid * W, mid * W + W)
    val after = out.copyOfRange(mid * W, mid * W + W)
    check(abs(crossing(after) - 48f) < 1.2f, "the refined edge crosses at ${crossing(after)}, not at the picture's edge (48); it was at ${crossing(before)}")
    // At the mask's own size the filter moves the edge rather than narrowing it: it may not spread it.
    check(width(after) <= width(before) + 2, "the refined edge is ${width(after)} px wide, the model's ${width(before)}: spread out")
    // Flat stays flat.
    val ones = MaskRefine.guided(guide, FloatArray(W * H) { 1f }, W, H)
    val zeros = MaskRefine.guided(guide, FloatArray(W * H) { 0f }, W, H)
    check(ones.all { it > 0.99f } && zeros.all { it < 0.01f }, "a mask with no edge grew one")

    // Twenty samples of a person stepping right a pixel a sample, with five in a hundred pixels flipped for one sample.
    val size = W * H
    var seed = 99
    fun rnd(): Int { seed = seed * 1103515245 + 12345; return (seed ushr 16) and 0x7FFF }
    val truth = List(20) { t -> ByteArray(size) { i -> if (i % W >= 30 + t) 255.toByte() else 0 } }
    val noisy = truth.map { m -> ByteArray(size) { i -> if (rnd() % 100 < 5) (255 - (m[i].toInt() and 0xFF)).toByte() else m[i] } }
    val smoothed = MaskRefine.temporal(noisy, size)
    fun flips(seq: List<ByteArray>): Long {
        var n = 0L
        for (t in 1 until seq.size) for (i in 0 until size) {
            val x = i % W
            // Pixels the edge itself crosses between t-1 and t are motion, not flicker.
            if (x == 30 + t - 1 || x == 30 + t) continue
            n += abs((seq[t][i].toInt() and 0xFF) - (seq[t - 1][i].toInt() and 0xFF))
        }
        return n
    }
    val was = flips(noisy); val now = flips(smoothed)
    check(now < was * 0.3, "the outline still boils: $now of the flicker the model gave ($was)")
    // Where the person is, sample by sample, within a pixel.
    for (t in 2 until 18) {
        val row = FloatArray(W) { x -> (smoothed[t][(H / 2) * W + x].toInt() and 0xFF) / 255f }
        val at = crossing(row, 30 + t - 0.5f)
        check(abs(at - (30 + t - 0.5f)) < 1.1f, "sample $t: the edge is at $at, the person at ${30 + t - 0.5f}")
    }

    if (problems.isEmpty()) println("MaskRefineChecks: all checks passed (flicker $was -> $now)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
