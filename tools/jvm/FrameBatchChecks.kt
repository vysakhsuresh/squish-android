import com.squish.app.media.video.FrameBatch
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** ARGB_8888, the config the retriever hands back. */
fun frameBytes(w: Int, h: Int) = w.toLong() * h * 4

fun main() {
    // --- The budget. This is the crash, as arithmetic. -------------------------
    val sizes = listOf(
        "4K" to (3840 to 2160),
        "1440p" to (2560 to 1440),
        "1080p" to (1920 to 1080),
        "720p" to (1280 to 720),
        "proxy" to (1280 to 540),
        "tiny" to (320 to 240),
    )
    println("%-8s %-12s %-6s %s".format("size", "per frame", "batch", "peak"))
    for ((label, wh) in sizes) {
        val (w, h) = wh
        val per = FrameBatch.framesPerCall(w, h)
        val peak = frameBytes(w, h) * per
        println("%-8s %-12s %-6d %.1f MB".format(
            label, "%.1f MB".format(frameBytes(w, h) / 1e6), per, peak / 1e6))

        check(per >= 1, "$label: batch of $per is not usable")
        // 64MB, a little over the 48MB budget, allows the single-frame floor for a
        // frame that is itself larger than the budget.
        check(peak <= 64_000_000 || per == 1, "$label: a batch would peak at ${peak / 1_000_000} MB")
    }
    check(FrameBatch.framesPerCall(0, 0) == 1, "an unknown frame size did not fall back to one")
    check(FrameBatch.framesPerCall(-1, 100) == 1, "a negative width did not fall back to one")
    check(FrameBatch.framesPerCall(3840, 2160) == 1, "4K should decode one frame at a time")
    check(FrameBatch.framesPerCall(320, 240) > 1, "a small frame should still batch")

    // --- The walk. Every wanted frame once, no unwanted frame at all. ---------
    val ranges = listOf(0 to 0, 0 to 1, 0 to 9, 5 to 5, 5 to 100, 0 to 5399, 100 to 53999)
    for ((first, last) in ranges) {
        for (stride in listOf(1, 2, 3, 7, 30, 97)) {
            for (perCall in listOf(1, 2, 5, 12)) {
                val calls = FrameBatch.planCalls(first, last, stride, perCall)

                val visited = calls.flatMap { c -> (c.startIndex until c.startIndex + c.count) }
                val wanted = (first..last step stride).toList()
                val tag = "[$first..$last] stride=$stride per=$perCall"

                check(visited.size == visited.distinct().size, "$tag: a frame is decoded twice")
                check(visited.all { it in first..last }, "$tag: decoded outside the range")
                check(calls.all { it.count in 1..perCall }, "$tag: a call exceeds the batch size")

                if (stride > 1) {
                    // Strided: decode exactly the frames that will be used.
                    check(visited.sorted() == wanted, "$tag: decoded ${visited.size} frames for ${wanted.size} wanted")
                } else {
                    // Unstrided: every frame is wanted, so all of them, once.
                    check(visited.sorted() == (first..last).toList(), "$tag: did not cover the range")
                }
            }
        }
    }

    // --- Degenerate input must not loop or throw. -----------------------------
    check(FrameBatch.planCalls(10, 5, 1, 4).isEmpty(), "an inverted range produced calls")
    check(FrameBatch.planCalls(0, 3, 0, 0).isNotEmpty(), "a zero stride or batch produced nothing")
    check(FrameBatch.planCalls(0, 3, -5, -5).isNotEmpty(), "a negative stride or batch produced nothing")

    // --- The regression, stated as a number. ----------------------------------
    // The old loop asked for BATCH * stride frames. Half an hour at 30fps is
    // 54,000 frames, so stride 30 and a batch of 12 meant 360 frames in one call.
    run {
        val oldPeak = 360L * frameBytes(1920, 1080)
        val newPeak = FrameBatch.planCalls(0, 53_999, 30, FrameBatch.framesPerCall(1920, 1080))
            .maxOf { it.count } * frameBytes(1920, 1080)
        println()
        println("half an hour of 1080p: was %.0f MB a call, now %.0f MB".format(oldPeak / 1e6, newPeak / 1e6))
        check(newPeak < 64_000_000, "the peak is still ${newPeak / 1_000_000} MB")
        check(oldPeak > 2_000_000_000, "the fixture no longer reproduces the old peak")
    }

    println()
    if (problems.isEmpty()) println("PASS - frame batches stay inside the budget and decode nothing twice")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
