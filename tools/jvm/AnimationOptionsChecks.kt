import com.squish.app.editor.TextAnimation
import com.squish.app.editor.TextExit
import com.squish.app.editor.TextLoop
import com.squish.app.editor.TextMotion
import com.squish.app.timeline.ClipAnimation
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float, eps: Float = 2e-3f) = abs(a - b) <= eps

/**
 * Every animation offered, old and new: an arrival lands exactly at rest,
 * a leaving ends gone (invisible, or off the canvas, or shrunk to nothing),
 * a loop starts at rest so nothing jumps on its first frame, and nothing is
 * ever NaN - for the clip's and the caption's alike.
 */
fun main() {
    val total = 4_000L
    for (a in ClipArrival.entries) {
        val end = ClipAnimation.frameAt(a, ClipLeaving.None, ClipLoop.None, 500, 500, 1200, 500, total)
        check(near(end.alpha, 1f) && near(end.scale, 1f) && near(end.dx, 0f) && near(end.dy, 0f) && near(end.tilt, 0f, 0.05f),
            "clip arrival $a does not land at rest: $end")
        val mid = ClipAnimation.frameAt(a, ClipLeaving.None, ClipLoop.None, 500, 500, 1200, 250, total)
        if (a != ClipArrival.None) check(!mid.isStill, "clip arrival $a does nothing halfway")
        for (t in 0L..500L step 25) {
            val f = ClipAnimation.frameAt(a, ClipLeaving.None, ClipLoop.None, 500, 500, 1200, t, total)
            check(listOf(f.alpha, f.scale, f.dx, f.dy, f.tilt).all { it.isFinite() } && f.alpha in 0f..1f && f.scale > 0f,
                "clip arrival $a at $t ms is out of range: $f")
        }
    }
    for (l in ClipLeaving.entries) {
        if (l == ClipLeaving.None) continue
        val end = ClipAnimation.frameAt(ClipArrival.None, l, ClipLoop.None, 500, 500, 1200, total, total)
        val gone = end.alpha < 0.02f || abs(end.dx) >= 1.9f || abs(end.dy) >= 1.9f || end.scale < 0.05f
        check(gone, "clip leaving $l is still on screen at the end: $end")
        val start = ClipAnimation.frameAt(ClipArrival.None, l, ClipLoop.None, 500, 500, 1200, total - 500, total)
        check(near(start.alpha, 1f) && near(start.scale, 1f, 0.01f) && near(start.dx, 0f) && near(start.dy, 0f) && near(start.tilt, 0f, 0.1f),
            "clip leaving $l jumps as it begins: $start")
    }
    for (p in ClipLoop.entries) {
        val first = ClipAnimation.frameAt(ClipArrival.None, ClipLeaving.None, p, 500, 500, 1200, 0, total)
        check(near(first.scale, 1f, 0.01f) && near(first.dx, 0f, 0.01f) && near(first.dy, 0f, 0.01f) && near(first.tilt % 360f, 0f, 0.5f),
            "clip loop $p does not start at rest: $first")
        val later = (0L until 1200L step 50).map { ClipAnimation.frameAt(ClipArrival.None, ClipLeaving.None, p, 500, 500, 1200, it, total) }
        if (p != ClipLoop.None) check(later.any { !it.isStill }, "clip loop $p never moves")
        check(later.all { it.alpha in 0f..1f && it.scale in 0.8f..1.2f && abs(it.dx) < 0.1f && abs(it.dy) < 0.1f }, "clip loop $p moves too far")
    }

    for (m in TextMotion.entries) {
        val end = TextAnimation.frameAt(m, TextExit.None, TextLoop.None, 450, 450, 1000, 450, total)
        check(near(end.alpha, 1f) && near(end.scale, 1f) && near(end.rise, 0f) && near(end.reveal, 1f) && near(end.tilt, 0f, 0.05f),
            "text arrival $m does not land at rest: $end")
        for (t in 0L..450L step 25) {
            val f = TextAnimation.frameAt(m, TextExit.None, TextLoop.None, 450, 450, 1000, t, total)
            check(listOf(f.alpha, f.scale, f.rise, f.tilt, f.reveal).all { it.isFinite() } && f.alpha in 0f..1f && f.scale > 0f,
                "text arrival $m at $t ms is out of range: $f")
        }
    }
    for (x in TextExit.entries) {
        if (x == TextExit.None) continue
        val end = TextAnimation.frameAt(TextMotion.None, x, TextLoop.None, 450, 450, 1000, total, total)
        check(end.alpha < 0.02f, "text leaving $x is still visible at the end: $end")
        val start = TextAnimation.frameAt(TextMotion.None, x, TextLoop.None, 450, 450, 1000, total - 450, total)
        check(near(start.alpha, 1f) && near(start.scale, 1f, 0.01f) && near(start.rise, 0f) && near(start.tilt, 0f, 0.1f),
            "text leaving $x jumps as it begins: $start")
    }
    for (p in TextLoop.entries) {
        val first = TextAnimation.frameAt(TextMotion.None, TextExit.None, p, 450, 450, 1000, 0, total)
        check(near(first.scale, 1f, 0.01f) && near(first.rise, 0f, 0.002f) && near(first.tilt, 0f, 0.5f),
            "text loop $p does not start at rest: $first")
    }
    val counts = "${ClipArrival.entries.size} in, ${ClipLeaving.entries.size} out, ${ClipLoop.entries.size} loops; text ${TextMotion.entries.size}/${TextExit.entries.size}/${TextLoop.entries.size}"
    if (problems.isEmpty()) println("AnimationOptionsChecks: all checks passed ($counts)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
