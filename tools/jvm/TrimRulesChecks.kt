import com.squish.app.tools.TrimRules
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val frame = 33L // 30 fps, as Timecode.frameDurationMs gives it
    val duration = 10_000L

    // --- Pixels and moments round-trip along the strip. ------------------------
    run {
        check(TrimRules.msAtX(0f, 1000f, duration) == 0L, "the strip's left edge was not the start")
        check(TrimRules.msAtX(1000f, 1000f, duration) == duration, "the strip's right edge was not the end")
        check(TrimRules.msAtX(500f, 1000f, duration) == 5_000L, "the middle of the strip was not the middle of the clip")
        check(TrimRules.msAtX(-50f, 1000f, duration) == 0L, "a point off the left was not clamped")
        check(TrimRules.msAtX(1500f, 1000f, duration) == duration, "a point off the right was not clamped")
        check(TrimRules.msAtX(500f, 0f, duration) == 0L, "a strip of no width divided by zero")
        check(TrimRules.xAtMs(2_500L, 1000f, duration) == 250f, "a moment landed at the wrong pixel")
        check(TrimRules.xAtMs(2_500L, 1000f, 0L) == 0f, "an empty clip divided by zero")
        val there = TrimRules.msAtX(TrimRules.xAtMs(7_326L, 1080f, duration), 1080f, duration)
        check(kotlin.math.abs(there - 7_326L) <= duration / 1080 + 1, "a moment drifted through the strip and back: $there")
    }

    // --- Handles land on frames. -----------------------------------------------
    run {
        check(TrimRules.snapToFrame(50L, frame) == 66L, "50 ms did not snap to the second frame")
        check(TrimRules.snapToFrame(40L, frame) == 33L, "40 ms did not snap to the first frame")
        check(TrimRules.snapToFrame(16L, frame) == 0L, "16 ms did not snap to the start")
        check(TrimRules.snapToFrame(17L, frame) == 33L, "17 ms did not snap up")
        check(TrimRules.snapToFrame(123L, 0L) == 123L, "a frame length of nothing changed the time")
    }

    // --- The in point never reaches the out point, nor leaves the clip. --------
    run {
        check(TrimRules.movedStart(-500L, 5_000L, duration, frame) == 0L, "the in point went before the start")
        check(TrimRules.movedStart(4_950L, 5_000L, duration, frame) == 4_900L, "the in point came within a tenth of the out point: ${TrimRules.movedStart(4_950L, 5_000L, duration, frame)}")
        check(TrimRules.movedStart(9_000L, 5_000L, duration, frame) == 4_900L, "the in point crossed the out point")
        check(TrimRules.movedStart(1_000L, 5_000L, duration, frame) == 990L, "the in point did not land on a frame: ${TrimRules.movedStart(1_000L, 5_000L, duration, frame)}")
        // A clip too short to keep the minimum: the in point stays at the start.
        check(TrimRules.movedStart(50L, 80L, 80L, frame) == 0L, "a tiny clip's in point moved")
    }

    // --- The out point never reaches the in point, nor leaves the clip. --------
    run {
        check(TrimRules.movedEnd(20_000L, 5_000L, duration, frame) == duration, "the out point went past the end")
        check(TrimRules.movedEnd(5_050L, 5_000L, duration, frame) == 5_100L, "the out point came within a tenth of the in point: ${TrimRules.movedEnd(5_050L, 5_000L, duration, frame)}")
        check(TrimRules.movedEnd(1_000L, 5_000L, duration, frame) == 5_100L, "the out point crossed the in point")
        check(TrimRules.movedEnd(9_000L, 5_000L, duration, frame) == 9_009L, "the out point did not land on a frame: ${TrimRules.movedEnd(9_000L, 5_000L, duration, frame)}")
        // The end itself is a boundary the grid must not pull the handle off.
        check(TrimRules.movedEnd(duration, 5_000L, duration, frame) == duration, "the out point at the end was pulled onto the grid")
        check(TrimRules.movedEnd(60L, 0L, 80L, frame) == 80L, "a tiny clip's out point left the end")
    }

    // --- A frame button moves exactly one frame, under the same rules. --------
    run {
        check(TrimRules.steppedStart(990L, 1, 5_000L, duration, frame) == 1_023L, "one frame forward was not one frame")
        check(TrimRules.steppedStart(990L, -1, 5_000L, duration, frame) == 957L, "one frame back was not one frame")
        check(TrimRules.steppedStart(0L, -1, 5_000L, duration, frame) == 0L, "a frame back from the start went negative")
        check(TrimRules.steppedEnd(duration, 1, 5_000L, duration, frame) == duration, "a frame past the end went past it")
        check(TrimRules.steppedEnd(5_100L, -1, 5_000L, duration, frame) == 5_100L, "a frame back reached the in point")
        check(TrimRules.steppedEnd(9_000L, 1, 0L, duration, 0L) == 9_001L, "with no frame grid a step was not one millisecond")
    }

    // --- The strip shows as many frames as fit, within reason. ----------------
    run {
        check(TrimRules.tileCount(100f) == TrimRules.MIN_TILES, "a narrow strip showed fewer than the minimum")
        check(TrimRules.tileCount(360f) == 8, "a phone-wide strip showed the wrong count: ${TrimRules.tileCount(360f)}")
        check(TrimRules.tileCount(2_000f) == TrimRules.MAX_TILES, "a wide strip asked for more than the cap")
    }

    if (problems.isEmpty()) {
        println("TrimRulesChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
