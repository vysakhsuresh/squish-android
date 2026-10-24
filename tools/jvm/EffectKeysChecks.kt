import com.squish.app.editor.EffectKind
import com.squish.app.editor.EffectKnob
import com.squish.app.editor.FxParams
import com.squish.app.editor.TimedEffect
import com.squish.app.editor.hasKnobKeyAt
import com.squish.app.editor.knobAt
import com.squish.app.editor.movedBy
import com.squish.app.editor.trimmedTo
import com.squish.app.editor.withKnobAt
import com.squish.app.editor.withKnobKeyAdded
import com.squish.app.editor.withKnobKeyRemoved
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN D3: a placed effect's Strength and its own knob keyed over its
 * length, with diamonds on its row - the effect's twin of a clip's keyed
 * opacity and level, on the same ValueKey and the same curves. FxParams.at is
 * what the preview's effects pass and the export's both read, so what moves
 * here moves on screen and in the file.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps

fun main() {
    val bare = TimedEffect(id = "fx", kind = EffectKind.Mono, startMs = 1_000, endMs = 5_000, intensity = 0.6f)
    check(near(bare.knobAt(EffectKnob.Strength, 3_000), 0.6f), "an unkeyed strength read ${bare.knobAt(EffectKnob.Strength, 3_000)}")
    val set = bare.withKnobAt(EffectKnob.Strength, 3_000, 0.4f)
    check(set.intensityKeys.isEmpty() && near(set.intensity, 0.4f), "a slider moved on an unkeyed effect keyed it")

    // A key at 2 s and another at 4 s: the strength moves between them.
    val keyed = bare.withKnobKeyAdded(EffectKnob.Strength, 2_000).withKnobAt(EffectKnob.Strength, 4_000, 0.2f)
        .withKnobKeyAdded(EffectKnob.Strength, 4_000)
    check(keyed.intensityKeys.size == 2, "two keys made ${keyed.intensityKeys.size}")
    check(keyed.intensityKeys.first().atMs == 1_000L, "a key at 2 s on an effect from 1 s is at ${keyed.intensityKeys.first().atMs} of its own clock")
    check(near(keyed.knobAt(EffectKnob.Strength, 2_000), 0.6f) && near(keyed.knobAt(EffectKnob.Strength, 4_000), 0.2f), "the keys did not hold their values")
    val mid = keyed.knobAt(EffectKnob.Strength, 3_000)
    check(mid < 0.6f && mid > 0.2f, "half way between the keys the strength is $mid")
    check(keyed.hasKnobKeyAt(EffectKnob.Strength, 4_010) && !keyed.hasKnobKeyAt(EffectKnob.Strength, 3_000), "the key under the playhead is not found")

    // What the effects pass reads: Mono's strength is the keyed one.
    check(near(FxParams.at(listOf(keyed), 2_000).mono, 0.6f, 0.01f), "the effects pass read ${FxParams.at(listOf(keyed), 2_000).mono} at the first key")
    check(near(FxParams.at(listOf(keyed), 4_000).mono, 0.2f, 0.01f), "the effects pass read ${FxParams.at(listOf(keyed), 4_000).mono} at the second key")

    // The knob too, on its own track.
    val knob = bare.withKnobKeyAdded(EffectKnob.Amount, 1_500).withKnobAt(EffectKnob.Amount, 4_500, 0.9f)
    check(knob.amountKeys.size == 2 && knob.intensityKeys.isEmpty(), "a key on the knob keyed the strength, or not the knob")

    // Moved, the keys go with it; its head trimmed, they stay on their moments.
    val moved = keyed.movedBy(700)
    check(near(moved.knobAt(EffectKnob.Strength, 4_700), 0.2f), "moved 0.7 s, the second key is not 0.7 s later")
    val trimmed = keyed.trimmedTo(1_800, 5_000)
    check(near(trimmed.knobAt(EffectKnob.Strength, 3_000), mid) && near(trimmed.knobAt(EffectKnob.Strength, 4_000), 0.2f), "a head trim moved the keys off their moments")

    // Removed, the value it had there stays.
    val one = keyed.withKnobKeyRemoved(EffectKnob.Strength, 4_000)
    check(one.intensityKeys.size == 1, "removing a key left ${one.intensityKeys.size}")
    val none = one.withKnobKeyRemoved(EffectKnob.Strength, 2_000)
    check(none.intensityKeys.isEmpty() && near(none.intensity, 0.6f), "the last key off left the strength at ${none.intensity}")

    // Into a clip at 2x: the preview plays the file's clock, and the keys land on the same frames.
    val fast = Clip(id = "c", kind = ClipKind.Video, label = "c", timelineStartMs = 0, sourceInMs = 0, sourceOutMs = 20_000, speedRamp = SpeedRamp.flat(2f))
    val inSource = keyed.shiftedInto(fast)
    val atTimeline = keyed.knobAt(EffectKnob.Strength, 3_000)
    val atSource = inSource.knobAt(EffectKnob.Strength, fast.sourceAt(3_000))
    check(near(atTimeline, atSource, 0.01f), "on a 2x clip the source clock reads $atSource where the timeline has $atTimeline")

    if (problems.isEmpty()) println("EffectKeysChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
