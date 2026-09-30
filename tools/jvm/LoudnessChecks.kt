import com.squish.app.media.audio.Loudness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun tone(amp: Float, seconds: Float, rate: Int, silenceAfter: Float = 0f): FloatArray {
    val n = (rate * seconds).toInt()
    val gap = (rate * silenceAfter).toInt()
    return FloatArray(n + gap) { i -> if (i < n) (amp * sin(2 * PI * 300 * i / rate)).toFloat() else 0f }
}

fun main() {
    val rate = 16_000
    val quiet = Loudness.of(tone(0.1f, 2f, rate), rate)
    val loud = Loudness.of(tone(0.4f, 2f, rate), rate)
    check(abs(loud / quiet - 4f) < 0.1f, "loudness did not follow the level: $quiet $loud")

    // Pauses do not make a shot read quieter.
    val paused = Loudness.of(tone(0.1f, 2f, rate, silenceAfter = 6f), rate)
    check(abs(paused - quiet) / quiet < 0.05f, "a shot with pauses read quieter: $paused against $quiet")

    // Silence reads as nothing.
    check(Loudness.of(FloatArray(rate), rate) == 0f, "silence had a loudness")

    // Levels: the loud shot comes down to the quiet one, the quiet stays, silence is left.
    val levels = Loudness.levels(listOf(quiet, loud, 0f))
    check(abs(levels[0] - 1f) < 0.01f, "the quiet shot was changed: ${levels[0]}")
    check(abs(levels[1] - 0.25f) < 0.02f, "the loud shot was not brought down to match: ${levels[1]}")
    check(levels[2] == 1f, "a silent shot was changed")
    // Never up, never below the floor.
    check(Loudness.levels(listOf(0.001f, 1f)).all { it in Loudness.MIN_LEVEL..1f }, "a level left the range")
    // One shot alone has nothing to be matched to.
    check(Loudness.levels(listOf(loud)) == listOf(1f), "a single shot was changed")

    if (problems.isEmpty()) {
        println("LoudnessChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
