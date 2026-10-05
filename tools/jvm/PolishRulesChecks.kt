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
        // Normal is one rate at 1x - what a tap on the chip lays - so it lights
        // only where the tap would change nothing. A flat 2x lights no chip:
        // it did, and a tap on the lit chip dropped the shot to 1x.
        check(PolishRules.activeRampShape(SpeedRamp(), 5_000L) == RampShape.Normal, "an untouched clip is not Normal")
        check(PolishRules.activeRampShape(SpeedRamp.flat(1f), 5_000L) == RampShape.Normal, "a flat 1x is not Normal")
        check(PolishRules.activeRampShape(SpeedRamp.flat(1.005f), 5_000L) == RampShape.Normal, "a flat 1.005x is not Normal")
        check(PolishRules.activeRampShape(SpeedRamp.flat(2f), 5_000L) == null, "a flat 2x lights a chip")
        check(PolishRules.activeRampShape(SpeedRamp.flat(0.25f), 5_000L) == null, "a flat quarter lights a chip")
        check(PolishRules.activeRampShape(SpeedRamp.flat(1.5f), 5_000L) == null, "a flat 1.5x lights a chip")
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
        // Two points of the same rate is not a ramp at all: Normal at 1x, nothing at 2x.
        check(
            PolishRules.activeRampShape(SpeedRamp(listOf(SpeedPoint(0L, 1f), SpeedPoint(span, 1f))), span) == RampShape.Normal,
            "two points at one rate is not Normal"
        )
        check(
            PolishRules.activeRampShape(SpeedRamp(listOf(SpeedPoint(0L, 2f), SpeedPoint(span, 2f))), span) == null,
            "two points at 2x light a chip"
        )
    }

    // --- The line under the chips is the chosen shape's, or what a tap does. ---
    run {
        check(PolishRules.rampHint(RampShape.BulletTime) == RampShape.BulletTime.hint, "Bullet's hint is not its own")
        check(PolishRules.rampHint(RampShape.Normal) == "One rate, no ramp", "Normal's hint is ${PolishRules.rampHint(RampShape.Normal)}")
        check(PolishRules.rampHint(null).startsWith("Tap a curve"), "no shape reads as ${PolishRules.rampHint(null)}")
        check(RampShape.entries.map { it.hint }.distinct().size == RampShape.entries.size, "two shapes share a hint")
        // A flat shot off 1x has no chip lit, so the line carries its rate.
        val flat2 = PolishRules.rampHint(null, SpeedRamp.flat(2f))
        check(flat2.startsWith("One rate, 2x.") && flat2.contains("Tap a curve"), "a flat 2x reads as $flat2")
        check(PolishRules.rampHint(null, SpeedRamp.flat(0.25f)).startsWith("One rate, 0.25x."), "a flat quarter reads as ${PolishRules.rampHint(null, SpeedRamp.flat(0.25f))}")
        // A dragged Hero is a ramp: the line says to tap a curve, with no rate.
        val hero = SpeedRamp.preset(RampShape.Hero, 5_000L).ordered
        val dragged = SpeedRamp(hero.mapIndexed { i, p -> if (i == 1) p.copy(speed = 0.6f) else p })
        check(PolishRules.rampHint(null, dragged) == "Tap a curve to lay it across the whole shot", "a dragged curve reads as ${PolishRules.rampHint(null, dragged)}")
        // And the lit chip's own line wins whatever the ramp.
        check(PolishRules.rampHint(RampShape.Normal, SpeedRamp.flat(1f)) == "One rate, no ramp", "Normal's line lost to the rate")
        check(PolishRules.rateLabel(2f) == "2x" && PolishRules.rateLabel(0.5f) == "0.5x" && PolishRules.rateLabel(0.25f) == "0.25x" &&
            PolishRules.rateLabel(1.5f) == "1.5x" && PolishRules.rateLabel(10f) == "10x", "rate labels read wrong")
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

    // --- Numbers read the same on every phone --------------------------------
    //
    // `"%.2f".format(v)` uses the phone's own locale, so on one set to German or
    // French it writes "2,00" - and the trim that follows takes the zeros and
    // cannot take the comma, so every speed chip read "2,x", every effect knob
    // "1,/s" and the Settings transition list "0,5 s". Seven labels were built
    // that way; they all come through `number` now, which formats against
    // Locale.ROOT.
    run {
        check(PolishRules.number(2f) == "2", "2 reads \"${PolishRules.number(2f)}\"")
        check(PolishRules.number(0.5f) == "0.5", "0.5 reads \"${PolishRules.number(0.5f)}\"")
        check(PolishRules.number(0.25f) == "0.25", "0.25 reads \"${PolishRules.number(0.25f)}\"")
        check(PolishRules.number(1.5f, 1) == "1.5", "1.5 at one place reads \"${PolishRules.number(1.5f, 1)}\"")
        check(PolishRules.number(10f, 1) == "10", "10 at one place reads \"${PolishRules.number(10f, 1)}\"")
        check(PolishRules.number(0f) == "0", "nothing reads \"${PolishRules.number(0f)}\"")
        // The thing itself: under a locale whose separator is a comma.
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            check(PolishRules.number(2f) == "2", "under a comma locale 2 reads \"${PolishRules.number(2f)}\"")
            check(PolishRules.number(0.5f) == "0.5", "under a comma locale 0.5 reads \"${PolishRules.number(0.5f)}\"")
            check(PolishRules.rateLabel(2f) == "2x", "under a comma locale the rate reads \"${PolishRules.rateLabel(2f)}\"")
            check(PolishRules.rateLabel(0.25f) == "0.25x", "under a comma locale a slow rate reads \"${PolishRules.rateLabel(0.25f)}\"")
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    // ---- The strip and the Speed sheet print one rate the same way. --------
    //
    // The badge on a retimed clip used PolishRules.number (two decimals) where
    // every other place in the app uses rateLabel (one above 1x, whose own doc
    // says it is "a rate as the Speed sheet prints it everywhere"). The slider
    // rounds to two decimals below 10x, so two-decimal rates are the ordinary
    // case: a shot dragged to 1.25x wore "1.25x" on the strip and said "1.3x"
    // the moment the sheet was opened. They agree below 1x, which is what made
    // it read as working.
    // Which of the two is used where is a source assertion, in
    // tools/jvm/ControlChecks.kt - nothing executable can see what a composable
    // passes. What is executable is that the two really do differ above 1x, so
    // that assertion is about something.
    run {
        val differ = listOf(1.07f, 1.25f, 1.37f, 2.63f, 3.14f).filter {
            PolishRules.number(it) + "x" != PolishRules.rateLabel(it)
        }
        check(differ.size == 5, "number and rateLabel agree on $differ, so using the wrong one would be invisible")
        check(PolishRules.rateLabel(1.25f) == "1.3x", "1.25x reads \"${PolishRules.rateLabel(1.25f)}\"")
        check(PolishRules.rateLabel(3.14f) == "3.1x", "3.14x reads \"${PolishRules.rateLabel(3.14f)}\"")
        check(PolishRules.rateLabel(0.78f) == "0.78x", "0.78x reads \"${PolishRules.rateLabel(0.78f)}\"")
        check(PolishRules.rateLabel(2f) == "2x", "a whole rate carries a decimal")
    }

    if (problems.isEmpty()) {
        println("PolishRulesChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
