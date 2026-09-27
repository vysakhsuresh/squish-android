import com.squish.app.timeline.SlowMotion
import com.squish.app.timeline.SpeedRamp

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

/** The rates phones actually shoot at. */
private val rates = floatArrayOf(24f, 25f, 30f, 50f, 60f, 120f, 240f)

fun main() {
    for (fps in rates) {
        // Full speed is always smooth: anything shot to be watched is watchable.
        check("$fps fps at 1x is smooth", SlowMotion.isSmooth(fps, 1f))

        // The frame rate follows the speed exactly. This is the fact the whole
        // panel rests on, and the reason slowing down cannot help itself.
        check("$fps fps halved is half", SlowMotion.effectiveFps(fps, 0.5f) == fps / 2f)

        // The slowest smooth speed really is smooth, and a touch slower is not.
        val slowest = SlowMotion.smoothestSpeed(fps)
        check("$fps fps is smooth at its slowest smooth speed", SlowMotion.isSmooth(fps, slowest))
        if (slowest > SpeedRamp.MIN_SPEED + 0.01f) {
            check(
                "$fps fps is no longer smooth below it",
                !SlowMotion.isSmooth(fps, slowest * 0.9f)
            )
        }
        check("$fps fps never proposes speeding up to be smooth", slowest <= 1f)
        check("$fps fps stays inside what a ramp allows", slowest >= SpeedRamp.MIN_SPEED)
    }

    // 30fps quarter speed is the case in the complaint: 7.5 frames a second.
    check("a quarter of 30 fps is 7.5", SlowMotion.effectiveFps(30f, 0.25f) == 7.5f)
    check("and that reads as a slideshow", SlowMotion.verdict(30f, 0.25f) == SlowMotion.Verdict.Slideshow)
    check("120 fps at a quarter is smooth", SlowMotion.verdict(120f, 0.25f) == SlowMotion.Verdict.Smooth)
    check("60 fps at a half is smooth", SlowMotion.verdict(60f, 0.5f) == SlowMotion.Verdict.Smooth)
    check("30 fps at a half steps", SlowMotion.verdict(30f, 0.5f) == SlowMotion.Verdict.Stepped)

    // What to shoot at, to get this slow.
    check("a quarter speed wants 96 fps", SlowMotion.fpsNeededFor(0.25f) == 96)
    check("a half speed wants 48 fps", SlowMotion.fpsNeededFor(0.5f) == 48)

    // The advice always names the number, never hedges, and never crashes.
    for (fps in rates + floatArrayOf(0f, -1f)) {
        for (speed in floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 10f)) {
            val line = SlowMotion.advice(fps, speed)
            if (fps > 0f) {
                check("$fps at $speed says something", line.isNotBlank())
                check("$fps at $speed names the rate", line.contains("fps"))
            }
        }
    }
    check("an unmeasured source says nothing rather than something wrong", SlowMotion.advice(0f, 0.5f).isEmpty())
    check("a zero speed does not divide by zero", SlowMotion.effectiveFps(30f, 0f) == 0f)

    // A ramp is only as smooth as its slowest moment.
    val ramp = SpeedRamp.preset(com.squish.app.timeline.RampShape.BulletTime, 4_000L)
    check("a ramp reports its slowest point", ramp.slowestSpeed <= ramp.flatSpeed + 0.001f)
    check("a flat ramp's slowest is its rate", SpeedRamp.flat(0.4f).slowestSpeed == 0.4f)
    check("the identity ramp is full speed", SpeedRamp().slowestSpeed == 1f)

    println("30 fps at a quarter: " + SlowMotion.advice(30f, 0.25f))
    println("120 fps at a quarter: " + SlowMotion.advice(120f, 0.25f))
    println("30 fps goes to %.2fx before it steps".format(SlowMotion.smoothestSpeed(30f)))
    println("240 fps goes to %.2fx before it steps".format(SlowMotion.smoothestSpeed(240f)))

    if (failures.isEmpty()) {
        println("PASS - the frame rate a speed produces is stated honestly at every rate a phone shoots")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
