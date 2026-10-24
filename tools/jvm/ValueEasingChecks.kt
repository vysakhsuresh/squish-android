import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.CubicBezier
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.valueAt
import com.squish.app.timeline.valueKeyAt
import com.squish.app.timeline.withValueKeyEasing
import com.squish.app.timeline.withShapeKeyEasing
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * EDGE_PLAN D2: a curve chosen for an opacity, level or look key from the
 * sheet lands on the key under the playhead, and is what the preview and the
 * export then read (valueAt, the one function both call).
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun overlay(start: Long, keys: List<ValueKey>) = Clip(
    id = "o", kind = ClipKind.Video, label = "o", sourceInMs = 0, sourceOutMs = 4_000,
    timelineStartMs = start, sourceDurationMs = 10_000, layer = 1, opacityKeys = keys
)

fun main() {
    val keys = listOf(ValueKey(0L, 0f, KeyframeEasing.Linear), ValueKey(2_000L, 1f, KeyframeEasing.Linear))
    val clip = overlay(start = 5_000, keys = keys)

    // The key under the playhead, in timeline time on a clip that starts at 5 s.
    check(clip.valueKeyAt(ValueTrack.Opacity, 5_000, 33)?.atMs == 0L, "the first key was not found under the playhead")
    check(clip.valueKeyAt(ValueTrack.Opacity, 7_020, 33)?.atMs == 2_000L, "a key 20 ms away was not found")
    check(clip.valueKeyAt(ValueTrack.Opacity, 6_000, 33) == null, "a key was found where there is none")

    // A preset onto the first key: the fade's middle moves the way the curve says.
    val eased = clip.withValueKeyEasing(ValueTrack.Opacity, 5_000, KeyframeEasing.EaseIn, toleranceMs = 33)
    check(eased.opacityKeys[0].easing == KeyframeEasing.EaseIn, "the easing did not reach the key")
    check(eased.opacityKeys[0].curve == null, "a preset kept a curve")
    check(abs(eased.valueAt(ValueTrack.Opacity, 6_000) - KeyframeEasing.EaseIn.ease(0.5f)) < 1e-4f,
        "the fade's middle is ${eased.valueAt(ValueTrack.Opacity, 6_000)}, not Ease in's ${KeyframeEasing.EaseIn.ease(0.5f)}")
    check(eased.opacityKeys[1] == keys[1], "the other key changed")

    // Custom: its handles reach the value, and survive a later preset only until it is chosen.
    val handles = CubicBezier(0.8f, 0f, 0.9f, 0.1f)
    val custom = clip.withValueKeyEasing(ValueTrack.Opacity, 5_000, KeyframeEasing.Custom, handles, toleranceMs = 33)
    check(custom.opacityKeys[0].curve == handles, "Custom lost its handles")
    check(custom.valueAt(ValueTrack.Opacity, 6_000) < 0.2f, "a slow-start curve did not hold the fade back at its middle")
    val plainAgain = custom.withValueKeyEasing(ValueTrack.Opacity, 5_000, KeyframeEasing.Linear, toleranceMs = 33)
    check(plainAgain.opacityKeys[0].curve == null && abs(plainAgain.valueAt(ValueTrack.Opacity, 6_000) - 0.5f) < 1e-4f,
        "going back to Linear kept the old curve")
    // Custom chosen with no handles given starts from the default curve.
    val fresh = clip.withValueKeyEasing(ValueTrack.Opacity, 5_000, KeyframeEasing.Custom, toleranceMs = 33)
    check(fresh.opacityKeys[0].curve == CubicBezier.DEFAULT_CUSTOM, "a fresh Custom key has no curve to draw")

    // Off a key: nothing changes.
    check(clip.withValueKeyEasing(ValueTrack.Opacity, 6_000, KeyframeEasing.Bounce, toleranceMs = 33) == clip, "a curve was set with no key there")

    // A mask's shape keys take curves the same way, in source time.
    run {
        val m = com.squish.app.timeline.Mask(widthFraction = 0.3f, keys = listOf(
            com.squish.app.timeline.MaskKey(1_000L, com.squish.app.timeline.Mask(widthFraction = 0.3f), KeyframeEasing.Linear),
            com.squish.app.timeline.MaskKey(3_000L, com.squish.app.timeline.Mask(widthFraction = 0.7f), KeyframeEasing.Linear)
        ))
        val c = m.withShapeKeyEasing(1_010L, KeyframeEasing.Custom, handles)
        check(c.keys[0].easing == KeyframeEasing.Custom && c.keys[0].curve == handles, "a mask key did not take its Custom curve")
        check(c.keys[1] == m.keys[1], "the other mask key changed")
        val mid = c.at(2_000L).widthFraction
        check(mid < 0.35f, "the mask's middle did not follow the slow-start curve (width $mid)")
        check(m.withShapeKeyEasing(2_000L, KeyframeEasing.Bounce) == m, "a mask curve was set with no key there")
    }

    if (problems.isEmpty()) println("ValueEasingChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
