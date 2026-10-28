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

    // ---- Its size is followed, and a lost thing is found again (EDGE_PLAN F8) ----
    run {
        val w = 240
        val h = 180
        // A textured square, half-size [s], at ([cx], [cy]): the same pattern at every size.
        fun scene(cx: Float, cy: Float, s: Float?): LumaFrame {
            val px = FloatArray(w * h) { background(it % w, it / w) }
            if (s != null) for (y in 0 until h) for (x in 0 until w) {
                val u = (x - cx) / s
                val v = (y - cy) / s
                if (abs(u) <= 1f && abs(v) <= 1f) px[y * w + x] = 0.5f + 0.25f * kotlin.math.sin(4f * u) + 0.2f * kotlin.math.cos(5f * v + u) + if (u > 0.4f && v < -0.3f) 0.2f else 0f
            }
            return LumaFrame(w, h, px)
        }
        val s0 = 12f
        val size = 25
        val start = scene(70f, 90f, s0)
        val tmpl = FloatArray(size * size) { i -> start.pixels[(90 - 12 + i / size) * w + (70 - 12 + i % size)] }
        val tracker = ObjectTracker(tmpl, size, size)
        var x = 70f
        var y = 90f
        // Twenty-five frames walking right and growing to 1.6 times its size.
        for (f in 1..25) {
            val step = tracker.step(scene(70f + f * 2f, 90f, s0 * (1f + 0.6f * f / 25f)), x, y, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { x = step.x; y = step.y }
        }
        check(abs(tracker.scale - 1.6f) <= 0.12f, "the size was followed to ${tracker.scale}, not 1.6")
        check(abs(x - 120f) <= 2f && abs(y - 90f) <= 2f, "the growing thing was followed to ($x, $y), not (120, 90)")
        val grownAt = tracker.scale
        // Gone for six frames: lost, and not found in the background.
        var lostSaid = 0
        for (f in 1..6) {
            val step = tracker.step(scene(0f, 0f, null), x, y, 16)
            if (step.confidence < com.squish.app.media.video.MotionTrack.LOST_BELOW) lostSaid++ else { x = step.x; y = step.y }
        }
        check(lostSaid == 6, "only $lostSaid of 6 empty frames were said to be lost")
        // Back, 80 px away - far outside one step's search - and found again within three frames.
        var foundIn = -1
        for (f in 1..6) {
            val step = tracker.step(scene(40f, 60f, s0 * grownAt), x, y, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { x = step.x; y = step.y }
            if (foundIn < 0 && abs(x - 40f) <= 2f && abs(y - 60f) <= 2f) foundIn = f
        }
        check(foundIn in 1..3, "the thing that came back was found ${if (foundIn < 0) "never" else "after $foundIn frames"}")

        // The same growth taken slowly, as a thing walking towards the camera
        // does: 1.6 times over 270 frames, under a fifth of a per cent a frame.
        // On the moto (28 Oct, a card keyed from 25% to 40% across nine
        // seconds) the size stopped at 1.15: each frame's growth was learned
        // into the template before a 5% step could ever look better.
        val slow = ObjectTracker(tmpl, size, size)
        var sx = 70f
        var sy = 90f
        for (f in 1..270) {
            val step = slow.step(scene(70f + f * 50f / 270f, 90f, s0 * (1f + 0.6f * f / 270f)), sx, sy, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { sx = step.x; sy = step.y }
        }
        check(abs(slow.scale - 1.6f) <= 0.12f, "a thing growing slowly to 1.6 was followed to ${slow.scale}")
        check(abs(sx - 120f) <= 3f && abs(sy - 90f) <= 3f, "the slowly growing thing was followed to ($sx, $sy), not (120, 90)")
        // Gone behind something busy for a second: what is there only half
        // matches, and the size must not follow it. On the moto (28 Oct) the card
        // hidden over the fractal matched it at 0.47-0.5, the size walked from
        // 1.09 to 0.76 over the gap, and the line pinned to it shrank.
        val before = slow.scale
        val rnd = java.util.Random(5)
        for (f in 1..30) {
            val faint = scene(sx, sy, s0 * before * 0.85f)
            val noisy = LumaFrame(w, h, FloatArray(w * h) { k -> 0.6f * faint.pixels[k] + 0.4f * rnd.nextFloat() })
            slow.step(noisy, sx, sy, 16)
        }
        check(abs(slow.scale - before) <= 0.06f, "a weak match over a second's gap moved the size from $before to ${slow.scale}")
        // And it went on growing while it was hidden: it comes back a quarter
        // bigger, and the size is found again, not left where it was (the card
        // on the moto came back at 1.3 and the size stayed at 1.04 to the end).
        val back = before * 1.25f
        for (f in 1..10) {
            val step = slow.step(scene(sx, sy, s0 * back), sx, sy, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { sx = step.x; sy = step.y }
        }
        check(abs(slow.scale - back) <= 0.12f, "a thing back from a gap a quarter bigger (${back}) was followed at ${slow.scale}")
    }

    // ---- The same with colour bars, whose stripes repeat (28 Oct, the moto) ----
    // A test card: seven bars over a row of blocks. Its stripes match themselves
    // a bar's width off at the wrong size too, so a size a step either way can be
    // no better than the size it has - and after a gap the card, grown on while
    // hidden, came back at 1.3 and was followed at 1.04 to the end.
    run {
        val w = 320
        val h = 200
        val levels = floatArrayOf(0.75f, 0.7f, 0.6f, 0.55f, 0.45f, 0.4f, 0.3f)
        fun card(cx: Float, cy: Float, half: Float?): LumaFrame {
            val px = FloatArray(w * h) { background(it % w, it / w) }
            if (half != null) for (y in 0 until h) for (x in 0 until w) {
                val u = (x - cx) / half
                val v = (y - cy) / (half * 0.6f)
                if (abs(u) <= 1f && abs(v) <= 1f) {
                    val bar = ((u + 1f) / 2f * 7f).toInt().coerceIn(0, 6)
                    px[y * w + x] = if (v < 0.4f) levels[bar] else if (((u + 1f) * 3f).toInt() % 2 == 0) 0.95f else 0.05f
                }
            }
            return LumaFrame(w, h, px)
        }
        val h0 = 20f
        val size = 25
        val first = card(80f, 100f, h0)
        val tmpl = FloatArray(size * size) { i -> first.pixels[(100 - 12 + i / size) * w + (80 - 12 + i % size)] }
        val t = ObjectTracker(tmpl, size, size)
        var x = 80f
        var y = 100f
        // Growing 1.0 to 1.2 over 90 frames, a third of a second each way.
        for (f in 1..90) {
            val step = t.step(card(80f + f * 0.4f, 100f, h0 * (1f + 0.2f * f / 90f)), x, y, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { x = step.x; y = step.y }
        }
        val grown = t.scale
        check(abs(grown - 1.2f) <= 0.08f, "a growing test card was followed to $grown, not 1.2")
        // Gone for 30 frames - and while it is, something like it elsewhere: a
        // faded, noisy copy (on the moto the fractal took the track at 0.74 over
        // the second the card was hidden, far from it). Not taken for it.
        val rnd = java.util.Random(9)
        for (f in 1..30) {
            val fake = card(240f, 140f, h0 * grown)
            val step = t.step(LumaFrame(w, h, FloatArray(w * h) { k -> 0.45f * fake.pixels[k] + 0.55f * rnd.nextFloat() }), x, y, 16)
            check(step.confidence < com.squish.app.media.video.MotionTrack.LOST_BELOW || abs(step.x - x) < 30f,
                "a faded copy 100 px away was taken for the hidden card at frame $f (${step.confidence} at ${step.x}, ${step.y})")
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { x = step.x; y = step.y }
        }
        val back = 1.2f * 1.25f
        for (f in 1..12) {
            val step = t.step(card(116f + f * 0.4f, 100f, h0 * back), x, y, 16)
            if (step.confidence >= com.squish.app.media.video.MotionTrack.LOST_BELOW) { x = step.x; y = step.y }
        }
        check(abs(t.scale - back) <= 0.12f, "a test card back from a gap at $back was followed at ${t.scale}")
    }

    println("tracker: ${13 * 13} offsets searched, $exact found exactly")
    if (problems.isEmpty()) println("PASS - the search finds the patch where the patch is, and holds still when it holds still")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
