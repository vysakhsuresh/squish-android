import com.squish.app.media.audio.WhisperText.Word
import com.squish.app.media.audio.WhisperWindows
import kotlin.system.exitProcess

/*
 * EDGE_PLAN E1: auto-captions heard by Whisper a window at a time. On the moto
 * (27 Oct) 57 lines one call each were at line 14 after seven minutes - each
 * call padded to Whisper's 30 s - where the whole 109 s file in one call took
 * 109 s. These hold the grouping and the handing back of the words.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // Two-second lines a second apart: 28 s windows of about nine lines each, none longer, every line in one.
    val lines = List(57) { i -> (i * 3_000L)..(i * 3_000L + 2_000L) }
    val windows = WhisperWindows.group(lines)
    check(windows.flatten() == lines.indices.toList(), "a line was lost or heard twice: ${windows.flatten().size} of ${lines.size}")
    check(windows.all { w -> lines[w.last()].last - lines[w.first()].first <= WhisperWindows.MAX_WINDOW_MS }, "a window ran past ${WhisperWindows.MAX_WINDOW_MS} ms")
    check(windows.size <= 8, "57 short lines took ${windows.size} calls - hardly fewer than one each")
    check(windows.all { it.zipWithNext().all { (a, b) -> b == a + 1 } }, "a window took lines out of order")
    // A line longer than a window is one on its own, and the lines either side stay whole.
    val long = listOf(0L..2_000L, 3_000L..40_000L, 41_000L..42_000L)
    check(WhisperWindows.group(long) == listOf(listOf(0), listOf(1), listOf(2)), "a long line was merged: ${WhisperWindows.group(long)}")
    check(WhisperWindows.group(emptyList()).isEmpty(), "nothing gave a window")

    // Words back to their lines: by the middle of each word; one said in the
    // gap goes to the nearer line; one far from every line is not a caption.
    val spans = listOf(1_000L..3_000L, 5_000L..7_000L)
    val words = listOf(
        Word(1_100, 1_400, "one"), Word(2_700, 3_200, "two"),        // the middle (2950) inside the first
        Word(3_300, 3_700, "three"),                                  // in the gap, nearer the first
        Word(4_500, 4_900, "four"),                                   // in the gap, nearer the second
        Word(5_100, 5_500, "five"), Word(9_000, 9_400, "far")         // far from both: dropped
    )
    val back = WhisperWindows.assign(words, spans)
    check(back[0].map { it.text } == listOf("one", "two", "three"), "the first line got ${back[0].map { it.text }}")
    check(back[1].map { it.text } == listOf("four", "five"), "the second line got ${back[1].map { it.text }}")
    check(back.flatten().none { it.text == "far" }, "a word a second and a half from every line was captioned")

    if (problems.isEmpty()) println("WhisperWindowsChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
