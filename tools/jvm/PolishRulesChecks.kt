import com.squish.app.editor.MotionPreset
import com.squish.app.editor.PolishRules
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import kotlin.system.exitProcess

// The sheets' chips, executed: which curve or move a clip is on, read back
// off the clip, since neither is stored by name (docs/ROADMAP.md batch B16:
// "ramp/preset chips show the active choice with the right hint"), and the
// proxy notice's percentage.

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- A curve laid by a tap lights its own chip, at any clip length. ---------
    run {
        for (span in listOf(2_000L, 5_000L, 12_345L, 180_000L)) {
            for (shape in RampShape.entries) {
                val ramp = SpeedRamp.preset(shape, span)
                val active = PolishRules.activeRampShape(ramp, span)
                check(active == shape, "$shape laid over $span ms reads as $active")
            }
        }
        // Normal is any one rate: the hint says "one rate, no ramp".
        check(PolishRules.activeRampShape(SpeedRamp(), 5_000L) == RampShape.Normal, "an untouched clip is not Normal")
        check(PolishRules.activeRampShape(SpeedRamp.flat(2f), 5_000L) == RampShape.Normal, "a flat 2x is not Normal")
        check(PolishRules.activeRampShape(SpeedRamp.flat(0.25f), 5_000L) == RampShape.Normal, "a flat quarter is not Normal")
    }

    // --- A point dragged, added or the clip cut: no chip claims the curve. -----
    run {
        val span = 5_000L
        val hero = SpeedRamp.preset(RampShape.Hero, span).ordered
        val slower = SpeedRamp(hero.mapIndexed { i, p -> if (i == 1) p.copy(speed = p.speed - 0.1f) else p })
        check(PolishRules.activeRampShape(slower, span) == null, "a point's rate moved by 0.1 still reads as Hero")
        val later = SpeedRamp(hero.mapIndexed { i, p -> if (i == 1) p.copy(atMs = p.atMs + span / 20) else p })
        check(PolishRules.activeRampShape(later, span) == null, "a point moved 5% along still reads as Hero")
        val nudged = SpeedRamp(hero.mapIndexed { i, p -> if (i == 1) p.copy(atMs = p.atMs + span / 100) else p })
        check(PolishRules.activeRampShape(nudged, span) == RampShape.Hero, "a point rounded 1% along lost the Hero chip")
        val added = SpeedRamp(hero + SpeedPoint(2_500L, 0.4f))
        check(PolishRules.activeRampShape(added, span) == null, "a fifth point still reads as Hero")
        // The clip cut in half keeps points over footage it no longer shows; the
        // half is no preset.
        check(PolishRules.activeRampShape(SpeedRamp(hero), span / 2) == null, "half of a Hero clip still reads as Hero")
        // Two points of the same rate is not a ramp at all.
        check(
            PolishRules.activeRampShape(SpeedRamp(listOf(SpeedPoint(0L, 1f), SpeedPoint(span, 1f))), span) == RampShape.Normal,
            "two points at one rate is not Normal"
        )
    }

    // --- The line under the chips is the chosen shape's, or what a tap does. ---
    run {
        check(PolishRules.rampHint(RampShape.BulletTime) == RampShape.BulletTime.hint, "Bullet's hint is not its own")
        check(PolishRules.rampHint(RampShape.Normal) == "One rate, no ramp", "Normal's hint is ${PolishRules.rampHint(RampShape.Normal)}")
        check(PolishRules.rampHint(null).startsWith("Tap a curve"), "no shape reads as ${PolishRules.rampHint(null)}")
        check(RampShape.entries.map { it.hint }.distinct().size == RampShape.entries.size, "two shapes share a hint")
    }

    // --- A move laid by a tap lights its own chip. -----------------------------
    run {
        for (length in listOf(200L, 3_000L, 60_000L)) {
            for (preset in MotionPreset.entries) {
                val (from, to) = preset.endpoints()
                // As ClipEdits.applyMotionPreset lays them.
                val keys = listOf(Keyframe(0L, from, KeyframeEasing.Smooth), Keyframe(length, to, KeyframeEasing.Smooth))
                val active = PolishRules.activeMotionPreset(keys, length)
                check(active == preset, "$preset over $length ms reads as $active")
            }
        }
        check(MotionPreset.entries.map { it.endpoints() }.distinct().size == MotionPreset.entries.size, "two presets lay the same keys")
    }

    // --- A key moved, added, eased or trimmed away: no chip claims the move. --
    run {
        val length = 3_000L
        val (from, to) = MotionPreset.PushIn.endpoints()
        fun keys(first: Keyframe = Keyframe(0L, from), last: Keyframe = Keyframe(length, to)) = listOf(first, last)

        check(PolishRules.activeMotionPreset(emptyList(), length) == null, "no keys reads as a preset")
        check(PolishRules.activeMotionPreset(listOf(Keyframe(0L, from)), length) == null, "one key reads as a preset")
        check(PolishRules.activeMotionPreset(keys() + Keyframe(1_500L, Transform(scale = 1.1f)), length) == null, "three keys read as Push in")
        check(PolishRules.activeMotionPreset(keys(last = Keyframe(length, to.copy(scale = 1.3f))), length) == null, "a moved endpoint reads as Push in")
        check(PolishRules.activeMotionPreset(keys(first = Keyframe(0L, from, KeyframeEasing.Linear)), length) == null, "a linear move reads as Push in")
        // The last key's easing describes no segment, so it does not count.
        check(PolishRules.activeMotionPreset(keys(last = Keyframe(length, to, KeyframeEasing.Hold)), length) == MotionPreset.PushIn, "the last key's easing lost the chip")
        // A frame trimmed off the tail keeps the chip; a real trim loses it.
        check(PolishRules.activeMotionPreset(keys(), length - 33L) == MotionPreset.PushIn, "a frame trimmed lost the chip")
        check(PolishRules.activeMotionPreset(keys(), length / 2) == null, "half the clip still reads as Push in")
        // Keys out of order are the same keys.
        check(PolishRules.activeMotionPreset(keys().reversed(), length) == MotionPreset.PushIn, "reversed key order lost the chip")
        check(PolishRules.motionHint(MotionPreset.Settle) == MotionPreset.Settle.hint, "Settle's hint is not its own")
        check(PolishRules.motionHint(null).startsWith("A preset lays two keys"), "no move reads as ${PolishRules.motionHint(null)}")
    }

    // --- The proxy notice: a percentage once the encoder has one. --------------
    run {
        val plain = PolishRules.proxyBuildingLine(null, 0, 1)
        check(plain == "Building a light preview copy — editing stays responsive while it works", "plain line is $plain")
        check(PolishRules.proxyBuildingLine(0, 0, 1) == plain, "0% is shown")
        check(PolishRules.proxyBuildingLine(37, 0, 1).contains(" · 37%"), "37% is missing: ${PolishRules.proxyBuildingLine(37, 0, 1)}")
        check(PolishRules.proxyBuildingLine(100, 0, 1).contains(" · 100%"), "100% is missing")
        check(PolishRules.proxyBuildingLine(150, 0, 1) == plain, "a percentage past 100 is shown")
        val two = PolishRules.proxyBuildingLine(42, 1, 3)
        check(two.contains(" · 42% (1 of 3 ready)"), "the count and percentage read wrong: $two")
        check(!PolishRules.proxyBuildingLine(42, 0, 1).contains("of 1"), "one file is counted")
    }

    if (problems.isEmpty()) {
        println("PolishRulesChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
