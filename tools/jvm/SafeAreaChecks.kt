import com.squish.app.editor.SafeArea
import kotlin.math.abs
import kotlin.system.exitProcess

// Where a platform's own buttons sit over the picture. Drawn as a hint, never
// written into the file - so what is checked here is that the numbers are
// sane and that "Anywhere" really is the worst of the others.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- Every platform leaves a usable middle. -----------------------------
    SafeArea.entries.forEach { area ->
        val i = area.insets
        check(i.top in 0f..0.5f && i.bottom in 0f..0.5f && i.left in 0f..0.5f && i.right in 0f..0.5f,
            "${area.label} has an edge outside 0..0.5")
        check(i.height > 0.4f, "${area.label} leaves only ${i.height} of the height")
        check(i.width > 0.6f, "${area.label} leaves only ${i.width} of the width")
        // The middle of the frame is always clear, or the guide is useless.
        check(!i.covers(0.5f, 0.5f), "${area.label} covers the middle of the frame")
        // The furniture is along the bottom and the right: a caption low down
        // and the buttons beside it. Anything claiming otherwise is a typo.
        check(i.bottom > i.top, "${area.label} covers more at the top than the bottom")
        check(i.right > i.left, "${area.label} covers more on the left than the right")
    }

    // --- "Anywhere" is the worst of the real ones, worked out not typed. ----
    run {
        val strict = SafeArea.strictest()
        val stated = SafeArea.Everywhere.insets
        check(abs(strict.top - stated.top) < 1e-4f &&
            abs(strict.bottom - stated.bottom) < 1e-4f &&
            abs(strict.left - stated.left) < 1e-4f &&
            abs(strict.right - stated.right) < 1e-4f,
            "Anywhere is $stated but the worst of the others is $strict")
        // Anything clear on Anywhere is clear on each of them.
        SafeArea.entries.filter { it != SafeArea.Everywhere }.forEach { area ->
            for (x in 0..20) for (y in 0..20) {
                val px = x / 20f
                val py = y / 20f
                if (!stated.covers(px, py)) {
                    check(!area.insets.covers(px, py), "($px, $py) is clear on Anywhere but covered on ${area.label}")
                }
            }
        }
    }

    // --- The corners are covered, the edges beyond the insets are too. ------
    run {
        val i = SafeArea.TikTok.insets
        check(i.covers(0.5f, 0.99f), "the bottom of the frame is not covered")
        check(i.covers(0.99f, 0.5f), "the right of the frame is not covered")
        check(i.covers(0.5f, 0.01f), "the top of the frame is not covered")
        check(!i.covers(i.left + 0.01f, i.top + 0.01f), "just inside the corner reads as covered")
    }

    println("safe areas: ${SafeArea.entries.size} platforms")
    if (problems.isEmpty()) println("PASS - every guide leaves a usable middle, and Anywhere is the worst of them")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
