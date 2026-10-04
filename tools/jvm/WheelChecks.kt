import com.squish.app.media.effects.ColorWheels
import com.squish.app.media.effects.Wheel
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

// Lift, gamma and gain. Two things have to hold: the dot in the disc and the
// three numbers are the same thing both ways, and the shader does to a pixel
// exactly what Kotlin does - the second is read out of the .glsl, because a
// grade that is one picture on screen and another in the file is the fault
// this codebase keeps coming back to.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, msg: String, tol: Float = 1e-4f) {
    if (abs(a - b) > tol) problems += "$msg: $a vs $b"
}

fun main() {
    // --- The pad and the numbers are the same thing, both ways. --------------
    val pads = buildList {
        for (i in -10..10) for (j in -10..10) add(i / 10f to j / 10f)
    }.filter { hypot(it.first.toDouble(), it.second.toDouble()) <= 1.0 }
    val levels = listOf(-1f, -0.4f, 0f, 0.25f, 1f)

    pads.forEach { (x, y) ->
        levels.forEach { level ->
            val w = Wheel.of(x, y, level)
            near(w.master, level, "the level came back wrong from ($x, $y)")
            val (bx, by) = w.pad()
            near(bx, x, "x did not round-trip from ($x, $y) at $level")
            near(by, y, "y did not round-trip from ($x, $y) at $level")
        }
    }

    // --- The three axes sum to the level and nothing else. -------------------
    pads.forEach { (x, y) ->
        val w = Wheel.of(x, y, 0f)
        near(w.r + w.g + w.b, 0f, "the colour part of ($x, $y) is not level")
    }

    // --- Red is up, and the other two where the wheel puts them. -------------
    run {
        val up = Wheel.of(0f, 1f, 0f)
        check(up.r > up.g && up.r > up.b, "the top of the disc is not red: $up")
        near(up.g, up.b, "the top of the disc is not on the red axis")
        val lowerLeft = Wheel.of(-0.866f, -0.5f, 0f)
        check(lowerLeft.g > lowerLeft.r && lowerLeft.g > lowerLeft.b, "seven o'clock is not green: $lowerLeft")
        val lowerRight = Wheel.of(0.866f, -0.5f, 0f)
        check(lowerRight.b > lowerRight.r && lowerRight.b > lowerRight.g, "five o'clock is not blue: $lowerRight")
    }

    // --- Outside the rim is pulled back to it, keeping its hue. --------------
    run {
        val inside = Wheel.of(0.6f, 0.8f, 0f)
        val outside = Wheel.of(6f, 8f, 0f)
        near(outside.r, inside.r, "a dot past the rim changed its hue (r)")
        near(outside.g, inside.g, "a dot past the rim changed its hue (g)")
        near(outside.b, inside.b, "a dot past the rim changed its hue (b)")
        near(Wheel.of(3f, 4f, 0f).radius, 1f, "a dot past the rim is not on it")
    }

    // --- The level moves without touching the colour, and the other way. -----
    run {
        val w = Wheel.of(0.3f, -0.5f, 0.2f)
        val louder = w.withMaster(0.9f)
        near(louder.master, 0.9f, "withMaster did not set the level")
        val (ax, ay) = w.pad()
        val (bx, by) = louder.pad()
        near(bx, ax, "the level moved the dot (x)")
        near(by, ay, "the level moved the dot (y)")
    }

    check(Wheel.NONE.isIdentity, "a neutral wheel said it was not")
    check(Wheel.of(0f, 0f, 0f).isIdentity, "the middle of the disc at nothing is not neutral")
    check(!Wheel.of(0.5f, 0f, 0f).isIdentity, "a dot off the middle said it was neutral")
    check(ColorWheels.NONE.isIdentity, "three neutral wheels said they were not")
    check(!ColorWheels(gain = Wheel.of(0f, 0.2f, 0f)).isIdentity, "one wheel moved and the set said it was neutral")

    // --- Each wheel does what it says, and only that. -------------------------
    run {
        val lifted = ColorWheels(lift = Wheel(0.5f, 0.5f, 0.5f))
        val l = lifted.liftRgb()[0]
        near(lifted.applyChannel(0f, l, lifted.gammaRgb()[0], lifted.gainRgb()[0]), l, "lift did not raise black to itself")
        near(lifted.applyChannel(1f, l, lifted.gammaRgb()[0], lifted.gainRgb()[0]), 1f, "lift moved white")

        val gained = ColorWheels(gain = Wheel(0.5f, 0.5f, 0.5f))
        near(gained.applyChannel(0f, gained.liftRgb()[0], gained.gammaRgb()[0], gained.gainRgb()[0]), 0f, "gain moved black")
        near(
            gained.applyChannel(1f, gained.liftRgb()[0], gained.gammaRgb()[0], gained.gainRgb()[0]),
            gained.gainRgb()[0],
            "gain did not scale white"
        )

        val bent = ColorWheels(gamma = Wheel(0.5f, 0.5f, 0.5f))
        near(bent.applyChannel(0f, bent.liftRgb()[0], bent.gammaRgb()[0], bent.gainRgb()[0]), 0f, "gamma moved black")
        near(bent.applyChannel(1f, bent.liftRgb()[0], bent.gammaRgb()[0], bent.gainRgb()[0]), 1f, "gamma moved white")
        check(
            bent.applyChannel(0.5f, bent.liftRgb()[0], bent.gammaRgb()[0], bent.gainRgb()[0]) > 0.5f,
            "a positive gamma did not brighten the midtones"
        )
    }

    // --- Nothing comes back as not-a-number, however it is set. --------------
    listOf(-1f, -0.999f, 0f, 1f).forEach { v ->
        val w = ColorWheels(lift = Wheel(v, v, v), gamma = Wheel(v, v, v), gain = Wheel(v, v, v))
        listOf(0f, 0.001f, 0.5f, 1f).forEach { c ->
            val out = w.applyChannel(c, w.liftRgb()[0], w.gammaRgb()[0], w.gainRgb()[0])
            check(out.isFinite(), "a wheel at $v on $c gave $out")
        }
        check(w.gammaRgb().all { it > 0f }, "a wheel at $v gave a gamma of ${w.gammaRgb()[0]}")
    }

    // --- The shader does the same three steps, in the same order. ------------
    run {
        val glsl = File("app/src/main/assets/squish_look_es2.glsl")
        if (!glsl.isFile) problems += "the look shader is not where it was"
        else {
            val text = glsl.readText()
            listOf("uWheelsOn", "uWheelLift", "uWheelGamma", "uWheelGain").forEach {
                check(text.contains("uniform") && text.contains(it), "the shader has no $it")
            }
            val lift = text.indexOf("uWheelLift * (vec3(1.0) - c)")
            val gamma = text.indexOf("vec3(1.0) / uWheelGamma")
            val gain = text.indexOf("c *= uWheelGain")
            check(lift > 0, "the shader does not lift the way ColorWheels.applyChannel does")
            check(gamma > 0, "the shader does not take gamma as the exponent's denominator")
            check(gain > 0, "the shader does not scale by the gain")
            check(lift < gamma && gamma < gain, "the shader applies the three wheels in another order")
            // And after the channel gains and the brightness, where Kotlin has them.
            val brightness = text.indexOf("c += vec3(uBrightness)")
            check(brightness in 1 until lift, "the shader puts the wheels before the brightness")
            val contrast = text.indexOf("float f = (1.0 + uContrast)")
            check(gain < contrast, "the shader puts the wheels after the contrast")
        }
    }

    println("wheels: lift, gamma and gain, and the dot that sets them")
    if (problems.isEmpty()) println("PASS - the pad round-trips exactly and the shader does what Kotlin does")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
