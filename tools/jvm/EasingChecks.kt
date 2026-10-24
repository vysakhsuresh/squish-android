import com.squish.app.timeline.CubicBezier
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Transform
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.transformAt
import com.squish.app.timeline.valueAt
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * EDGE_PLAN D2: the keyframe curves. One function (KeyframeEasing.ease) for
 * the preview and the export, so what is checked here is what both draw.
 *  - every curve starts exactly at 0 and lands exactly on 1 (Hold excepted: it
 *    holds, and the next key is the jump);
 *  - the monotone ones never move backwards;
 *  - the overshooting ones do leave 0..1, as their names promise;
 *  - the bezier's inverse is right to 1e-4 everywhere, including the steep
 *    handles where Newton's method alone leaves the segment;
 *  - a Custom key's handles reach the evaluators the preview and export call.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    for (e in KeyframeEasing.entries) {
        if (e == KeyframeEasing.Hold) {
            check(e.ease(0.5f) == 0f, "Hold moved before the next key")
            continue
        }
        check(e.ease(0f) == 0f, "${e.name} does not start at 0 (${e.ease(0f)})")
        check(abs(e.ease(1f) - 1f) < 1e-6f, "${e.name} does not land on 1 (${e.ease(1f)})")
        val samples = (0..400).map { e.ease(it / 400f) }
        if (e.isMonotone) {
            samples.zipWithNext().forEachIndexed { i, (a, b) ->
                if (b < a - 1e-5f) { check(false, "${e.name} moves backwards at ${i / 400f}: $a -> $b"); return@forEachIndexed }
            }
        } else {
            check(samples.any { it > 1.001f || it < -0.001f } || e == KeyframeEasing.Bounce,
                "${e.name} never overshoots, which is the point of it")
        }
        samples.forEach { check(it.isFinite(), "${e.name} gave a non-finite value") }
    }
    check(KeyframeEasing.Bounce.ease(0.5f) in 0f..1f, "Bounce leaves 0..1 between bounces")
    check(KeyframeEasing.EaseIn.ease(0.25f) < 0.25f, "Ease in is not slow to start")
    check(KeyframeEasing.EaseOut.ease(0.25f) > 0.25f, "Ease out is not quick to start")
    check(KeyframeEasing.StrongInOut.ease(0.2f) < KeyframeEasing.Smooth.ease(0.2f), "Strong is not harder than Smooth at the start")

    // The bezier's inverse, over gentle and steep handles.
    val curves = listOf(
        CubicBezier(0.25f, 0.1f, 0.25f, 1f), CubicBezier(0.42f, 0f, 1f, 1f), CubicBezier(0f, 0f, 0.58f, 1f),
        CubicBezier(0.83f, 0f, 0.17f, 1f), CubicBezier(1f, 0f, 0f, 1f), CubicBezier(0f, 1f, 1f, 0f),
        CubicBezier(0.9f, -0.5f, 0.1f, 1.5f), CubicBezier(0.01f, 0.99f, 0.99f, 0.01f)
    )
    for (c in curves) {
        var worst = 0f
        for (i in 0..1000) {
            val x = i / 1000f
            val s = c.parameterAt(x)
            worst = maxOf(worst, abs(c.xAt(s) - x))
        }
        check(worst < 1e-4f, "$c: the inverse is off by $worst")
        check(c.y(0f) == 0f && c.y(1f) == 1f, "$c: does not pass through (0,0) and (1,1)")
    }
    // Handles outside 0..1 in time are clamped, not followed off the segment.
    check(CubicBezier(-1f, 0f, 2f, 1f).y(0.5f).isFinite(), "handles outside the segment gave nonsense")

    // A Custom key's own curve reaches what the preview and the export call.
    val custom = CubicBezier(0.9f, 0f, 0.9f, 0f)
    val keys = listOf(Keyframe(0, Transform(scale = 1f), KeyframeEasing.Custom, custom), Keyframe(1000, Transform(scale = 2f)))
    val atHalf = keys.transformAt(500, Transform.Identity).scale
    check(abs(atHalf - (1f + KeyframeEasing.Custom.ease(0.5f, custom))) < 1e-5f, "a Custom key's handles did not reach transformAt ($atHalf)")
    check(atHalf < 1.2f, "a slow-start Custom curve was read as the default (scale $atHalf at the midpoint)")
    val vk = listOf(ValueKey(0, 0f, KeyframeEasing.Custom, custom), ValueKey(1000, 1f))
    check(abs(vk.valueAt(500, 0f) - KeyframeEasing.Custom.ease(0.5f, custom)) < 1e-5f, "a Custom value key's handles did not reach valueAt")
    // The three easings drafts were written with before D2 read as they always did.
    check(abs(KeyframeEasing.Smooth.ease(0.25f) - 0.15625f) < 1e-6f, "Smooth changed - every old draft would move differently")
    check(KeyframeEasing.Linear.ease(0.3f) == 0.3f, "Linear changed")

    if (problems.isEmpty()) println("EasingChecks: all checks passed (${KeyframeEasing.entries.size} easings, ${curves.size} curves)")
    else { problems.take(20).forEach { println("FAIL: $it") }; exitProcess(1) }
}
