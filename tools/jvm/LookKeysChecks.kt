import com.squish.app.media.effects.Looks
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.hasValueKeyAt
import com.squish.app.timeline.valueAt
import com.squish.app.timeline.withValueAt
import kotlin.math.abs
import kotlin.system.exitProcess

// A filter's strength keyed over a clip: the third number to get the treatment
// opacity and level already had, and the one the roadmap named as the next use
// of ValueKey.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

private fun shot(span: Long = 10_000, start: Long = 0, look: String? = "vivid", strength: Float = 1f, keys: List<ValueKey> = emptyList()) = Clip(
    id = "a", kind = ClipKind.Video, label = "a", sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 60_000, lookId = look, lookIntensity = strength, lookKeys = keys
)

fun main() {
    // --- With no keys it is the one number, and nothing is animated. --------
    run {
        val clip = shot(strength = 0.6f)
        check(!clip.lookAnimated, "a clip with no keys read as animated")
        check(near(clip.valueAt(ValueTrack.Look, 5_000), 0.6f), "the strength without keys is ${clip.valueAt(ValueTrack.Look, 5_000)}")
        check(clip.gradeAt(5_000) == clip.grade, "the grade at a moment differs with no keys")
    }

    // --- A look brought up across the clip. ---------------------------------
    run {
        val clip = shot(keys = listOf(ValueKey(0L, 0f, KeyframeEasing.Linear), ValueKey(10_000L, 1f, KeyframeEasing.Linear)))
        check(clip.lookAnimated, "a keyed clip did not read as animated")
        check(near(clip.valueAt(ValueTrack.Look, 0), 0f), "it does not start at nothing")
        check(near(clip.valueAt(ValueTrack.Look, 10_000), 1f), "it does not end at full")
        check(near(clip.valueAt(ValueTrack.Look, 5_000), 0.5f), "half way along it is ${clip.valueAt(ValueTrack.Look, 5_000)}")

        // The grade really changes with it - the whole point, and the thing a
        // static Grade handed to the shader once could not do.
        val start = clip.gradeAt(0)
        val end = clip.gradeAt(10_000)
        check(start.isIdentity, "a look at no strength is not an identity grade")
        check(!end.isIdentity, "a look at full strength does nothing")
        check(start != end, "the grade is the same at both ends of the keys")
        check(clip.gradeAt(5_000) != start && clip.gradeAt(5_000) != end, "the middle is one of the ends")
    }

    // --- The slider on a keyed clip writes a key at the playhead. -----------
    run {
        val clip = shot(keys = listOf(ValueKey(0L, 0f, KeyframeEasing.Linear), ValueKey(10_000L, 1f, KeyframeEasing.Linear)))
        val keyed = clip.withValueAt(ValueTrack.Look, 4_000, 0.9f, 0f..1f)
        check(keyed.lookKeys.size == 3, "the slider made ${keyed.lookKeys.size} keys, not 3")
        check(near(keyed.valueAt(ValueTrack.Look, 4_000), 0.9f), "the new key is not where it was put")
        check(near(keyed.lookIntensity, clip.lookIntensity), "the slider wrote the static number on a keyed clip")
        // Off the clip there is no moment to key, so the whole track moves.
        val shifted = clip.withValueAt(ValueTrack.Look, 20_000, 0.5f, 0f..1f)
        check(shifted.lookKeys.size == 2, "a drag off the clip added a key")
        check(shifted.lookKeys.all { it.value <= 1f && it.value >= 0f }, "a shifted track left 0..1")
    }

    // --- With no keys at all the slider sets the one number. ----------------
    run {
        val plain = shot(strength = 0.4f).withValueAt(ValueTrack.Look, 1_000, 0.8f, 0f..1f)
        check(plain.lookKeys.isEmpty(), "the slider keyed a clip that had no keys")
        check(near(plain.lookIntensity, 0.8f), "the one number is ${plain.lookIntensity}")
    }

    // --- The keyframe button knows whether there is a key here. ------------
    run {
        val clip = shot(keys = listOf(ValueKey(0L, 0f, KeyframeEasing.Linear), ValueKey(4_000L, 1f, KeyframeEasing.Linear)))
        check(clip.hasValueKeyAt(ValueTrack.Look, 4_000, 33L), "the key at 4 s was not found")
        check(!clip.hasValueKeyAt(ValueTrack.Look, 2_000, 33L), "a key was found where there is none")
    }

    // --- A strength of nothing is an identity grade, keyed or not. ---------
    check(Looks.grade("vivid", 0f).isIdentity, "a look at no strength is not identity")

    println("look keys: strength keyed over a clip")
    if (problems.isEmpty()) println("PASS - the grade is a function of the clock when the strength is keyed")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
