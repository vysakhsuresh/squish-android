import com.squish.app.capture.CaptureRules
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G5: the in-app camera's teleprompter, count-in, 9:16 guide and
 * clock, executed.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // A minute at 120 wpm over a script of 10 words a line is 12 lines.
    val px = CaptureRules.scrollPx(60_000, 120, totalWords = 200, lineCount = 20, lineHeightPx = 50f)
    check(abs(px - 600f) < 0.01f, "a minute at 120 wpm, 10 words a line, scrolled $px px, not 600")
    // Twice the pace, twice as far.
    val fast = CaptureRules.scrollPx(30_000, 240, totalWords = 200, lineCount = 20, lineHeightPx = 50f)
    check(abs(fast - 600f) < 0.01f, "half a minute at 240 wpm scrolled $fast px, not 600")
    // Short lines move quicker at the same pace.
    val short = CaptureRules.scrollPx(60_000, 120, totalWords = 200, lineCount = 40, lineHeightPx = 50f)
    check(short > px, "a script of short lines scrolled no faster ($short against $px)")
    // Never past the end; nothing before the start or with nothing to read.
    check(CaptureRules.scrollPx(3_600_000, 240, 200, 20, 50f) == 1000f, "the script scrolled past its last line")
    check(CaptureRules.scrollPx(0, 140, 200, 20, 50f) == 0f && CaptureRules.scrollPx(5_000, 140, 0, 0, 50f) == 0f, "scrolled with nothing read")
    // The pace is held to the slider's range.
    check(CaptureRules.scrollPx(60_000, 10_000, 200, 20, 50f) == CaptureRules.scrollPx(60_000, CaptureRules.MAX_WPM, 200, 20, 50f), "a pace past the slider was not held to it")

    check(CaptureRules.wordsIn("  Hello,   world \n and  you ") == 4, "words counted ${CaptureRules.wordsIn("  Hello,   world \n and  you ")}")
    check(CaptureRules.wordsIn("") == 0, "an empty script has words")

    // 3, 2, 1, then the take.
    check(CaptureRules.countIn(0) == 3 && CaptureRules.countIn(999) == 3, "the count-in does not open on 3")
    check(CaptureRules.countIn(1_000) == 2 && CaptureRules.countIn(2_500) == 1, "the count-in does not count")
    check(CaptureRules.countIn(3_000) == null, "the count-in shows a 0 or runs past 1")

    // A 9:16 guide on a landscape preview is full height, centred.
    val wide = CaptureRules.guide(1920f, 1080f)
    check(abs(wide.height - 1080f) < 0.01f && abs(wide.width - 607.5f) < 0.01f && abs(wide.left - 656.25f) < 0.01f && wide.top == 0f, "9:16 on 16:9: $wide")
    // On a phone's tall preview (9:20) it is full width.
    val tall = CaptureRules.guide(1080f, 2400f)
    check(abs(tall.width - 1080f) < 0.01f && abs(tall.height - 1920f) < 0.01f && abs(tall.top - 240f) < 0.01f, "9:16 on 9:20: $tall")

    check(CaptureRules.clock(0) == "0:00" && CaptureRules.clock(65_400) == "1:05" && CaptureRules.clock(-5) == "0:00", "clock: ${CaptureRules.clock(65_400)}")

    if (problems.isEmpty()) println("CaptureRulesChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
