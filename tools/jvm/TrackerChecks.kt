import com.squish.app.media.video.LumaFrame
import com.squish.app.media.video.ObjectTracker
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * The object tracker's search, executed on synthetic frames.
 *
 * `step` is a coarse sweep every two pixels and then "the immediate neighbours
 * of the winner" - and the fine pass mutated the centre it was reading inside
 * the loop that read it, so after the first improvement the 3x3 was taken about
 * a *moved* centre. Offsets inside the intended square were never scored,
 * offsets outside it were, and which depended on the iteration order. Nine
 * iterations could each take a step, putting the answer nine analysis pixels
 * from the coarse winner - and since every frame searches from the frame
 * before, that compounded along the clip.
 *
 * Nothing had ever run the search. What makes it checkable is that the right
 * answer is known: put a patch somewhere, and the tracker has to find it there.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val W = 96
private const val H = 96
private const val PATCH = 9

/** A deterministic, well-textured background: a flat one gives a degenerate ZNCC. */
private fun background(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0xFF) / 255f * 0.4f
}

/** The background with a bright, asymmetric blob centred at ([cx], [cy]). */
private fun frameWithBlob(cx: Int, cy: Int): LumaFrame {
    val pixels = FloatArray(W * H) { background(it % W, it / W) }
    for (dy in -(PATCH / 2)..(PATCH / 2)) {
        for (dx in -(PATCH / 2)..(PATCH / 2)) {
            val x = cx + dx
            val y = cy + dy
            if (x !in 0 until W || y !in 0 until H) continue
            // Asymmetric on purpose: a symmetric blob correlates equally well a
            // pixel either side and the right answer is not unique.
            pixels[y * W + x] = 0.55f + 0.05f * dx + 0.09f * dy + if (dx == 1 && dy == -2) 0.3f else 0f
        }
    }
    return LumaFrame(W, H, pixels)
}

/** The PATCH-sized template cut out of [frame] at ([cx], [cy]). */
private fun template(frame: LumaFrame, cx: Int, cy: Int): FloatArray {
    val half = PATCH / 2
    val out = FloatArray(PATCH * PATCH)
    for (ty in 0 until PATCH) {
        for (tx in 0 until PATCH) {
            out[ty * PATCH + tx] = frame.pixels[(cy - half + ty) * W + (cx - half + tx)]
        }
    }
    return out
}

fun main() {
    val startX = 48
    val startY = 48
    val first = frameWithBlob(startX, startY)
    val patch = template(first, startX, startY)

    // ---- It finds the patch exactly where the patch is ----------------------
    //
    // Every offset the search radius covers, odd ones included - and the odd
    // ones are the point: the coarse sweep steps by two, so an odd offset is
    // only reachable by the fine pass, which is the pass that was wrong.
    var worst = 0
    var worstAt = ""
    var exact = 0
    var tried = 0
    for (dy in -6..6) {
        for (dx in -6..6) {
            val tracker = ObjectTracker(patch, PATCH, PATCH)
            val moved = frameWithBlob(startX + dx, startY + dy)
            val step = tracker.step(moved, startX.toFloat(), startY.toFloat(), 8)
            val offX = step.x.toInt() - startX
            val offY = step.y.toInt() - startY
            tried++
            if (offX == dx && offY == dy) exact++
            val miss = maxOf(abs(offX - dx), abs(offY - dy))
            if (miss > worst) {
                worst = miss
                worstAt = "($dx, $dy) found at ($offX, $offY)"
            }
        }
    }
    check(worst <= 1, "the search missed by $worst pixels: $worstAt")
    check(
        exact >= tried * 9 / 10,
        "only $exact of $tried offsets were found exactly - the fine pass is not a 3x3 about the coarse winner"
    )

    // ---- It does not wander when nothing moved -----------------------------
    //
    // The frame the template came from, searched again: the answer is the place
    // it came from and no other. With the centre moving inside the fine loop,
    // a tie anywhere in the square could carry it off.
    run {
        val tracker = ObjectTracker(patch, PATCH, PATCH)
        val step = tracker.step(first, startX.toFloat(), startY.toFloat(), 8)
        check(
            step.x.toInt() == startX && step.y.toInt() == startY,
            "the tracker moved off an unmoved frame, to (${step.x}, ${step.y}) from ($startX, $startY)"
        )
        check(step.confidence > 0.9f, "a perfect match scored only ${step.confidence}")
        check(step.scale == 1f, "a perfect match reported scale ${step.scale}")
    }

    // ---- And it does not compound along a clip -----------------------------
    //
    // The real cost of the fine pass's drift: each frame searches from the
    // frame before. A patch that holds still for thirty frames must still be
    // where it started.
    run {
        val tracker = ObjectTracker(patch, PATCH, PATCH)
        var x = startX.toFloat()
        var y = startY.toFloat()
        repeat(30) {
            val step = tracker.step(frameWithBlob(startX, startY), x, y, 8)
            x = step.x
            y = step.y
        }
        check(
            abs(x - startX) <= 1f && abs(y - startY) <= 1f,
            "a still patch drifted to ($x, $y) over thirty frames from ($startX, $startY)"
        )
    }

    // ---- A search at the frame's edge does not read outside it --------------
    run {
        val tracker = ObjectTracker(patch, PATCH, PATCH)
        val step = tracker.step(frameWithBlob(3, 3), 3f, 3f, 8)
        check(step.x.isFinite() && step.y.isFinite(), "a search at the edge gave (${step.x}, ${step.y})")
        check(!step.confidence.isNaN(), "a search at the edge scored NaN")
    }

    println("tracker: ${13 * 13} offsets searched, $exact found exactly")
    if (problems.isEmpty()) println("PASS - the search finds the patch where the patch is, and holds still when it holds still")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
