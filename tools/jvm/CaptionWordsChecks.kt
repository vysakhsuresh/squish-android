import com.squish.app.editor.CaptionStylePreset
import com.squish.app.editor.CaptionWords
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextStyleSpec
import com.squish.app.editor.WordMode
import com.squish.app.editor.WordState
import com.squish.app.editor.WordStyle
import kotlin.system.exitProcess

/*
 * EDGE_PLAN E2: a caption's words styled by when each is said - the word being
 * said coloured, boxed, popped or alone, karaoke filling, a few words a page.
 * The preview's caption layer and the export's overlay both read a line's
 * words through TextOverlayItem.frameAt and shownAt, so what is executed here
 * is what both draw.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun line(text: String, starts: List<Long> = emptyList(), style: WordStyle, motion: TextMotion = TextMotion.None, endMs: Long = 4_000) =
    TextOverlayItem(id = "l", text = text, startMs = 1_000, endMs = 1_000 + endMs, colorArgb = -1,
        motion = motion, wordStartsMs = starts, wordStyle = style)

fun main() {
    // Even shares when there are no timings, and when the timings are out of step with the words.
    check(CaptionWords.starts(4, emptyList(), 4_000) == listOf(0L, 1_000L, 2_000L, 3_000L), "even starts were ${CaptionWords.starts(4, emptyList(), 4_000)}")
    check(CaptionWords.starts(4, listOf(0L, 10L), 4_000) == listOf(0L, 1_000L, 2_000L, 3_000L), "timings for 2 of 4 words were used")
    check(CaptionWords.starts(2, listOf(300L, 900L), 4_000) == listOf(300L, 900L), "a word's own timing was not used")

    // The word being said: the last to have begun, none before the first.
    val starts = listOf(300L, 800L, 1_500L, 2_600L)
    check(CaptionWords.activeAt(starts, 100) == -1, "a word was being said before the first began")
    check(CaptionWords.activeAt(starts, 300) == 0, "the first word was not said at its start")
    check(CaptionWords.activeAt(starts, 1_499) == 1, "the second word ended early")
    check(CaptionWords.activeAt(starts, 9_000) == 3, "the last word was not held")

    // Pages of three: the page with the word being said, the first page before anything is said.
    val seven = "one two three four five six seven"
    val sevenStarts = List(7) { it * 500L }
    val page = CaptionWords.state(seven, sevenStarts, 2_100, 4_000, WordStyle(WordMode.Highlight, perScreen = 3))
    check(page == WordState(3, 6, 4), "word five's page was $page")
    val last = CaptionWords.state(seven, sevenStarts, 3_100, 4_000, WordStyle(WordMode.Highlight, perScreen = 3))
    check(last == WordState(6, 7, 6), "the short last page was $last")
    val early = CaptionWords.state(seven, listOf(400L) + sevenStarts.drop(1).map { it + 400 }, 100, 4_000, WordStyle(WordMode.Karaoke, perScreen = 3))
    check(early == WordState(0, 3, -1), "before the first word the page was $early")
    check(CaptionWords.state(seven, sevenStarts, 100, 4_000, WordStyle.NONE) == null, "a line with no word style got a state")
    // One word at a time is a page of one whatever perScreen says.
    check(CaptionWords.state(seven, sevenStarts, 1_000, 4_000, WordStyle(WordMode.One, perScreen = 4)) == WordState(2, 3, 2), "One word showed more than one")

    // The coloured stretch, by character, in the shown page's text "four five six".
    val shown = listOf("four", "five", "six")
    val st = WordState(3, 6, 4)
    check(CaptionWords.coloured(WordMode.Highlight, shown, st) == 5..8, "the highlighted word was ${CaptionWords.coloured(WordMode.Highlight, shown, st)}")
    check(CaptionWords.coloured(WordMode.Karaoke, shown, st) == 0..8, "karaoke did not fill the page to the word being said: ${CaptionWords.coloured(WordMode.Karaoke, shown, st)}")
    check(CaptionWords.coloured(WordMode.Highlight, shown, WordState(3, 6, -1)) == null, "a word was lit before it was said")
    check(CaptionWords.coloured(WordMode.One, shown, st) == null, "One word coloured its word")
    // Karaoke on a page the speech has passed: the whole page lit, not a range past its end.
    check(CaptionWords.coloured(WordMode.Karaoke, shown, WordState(3, 6, 8)) == 0..12, "a passed page was ${CaptionWords.coloured(WordMode.Karaoke, shown, WordState(3, 6, 8))}")

    // The line as the renderer is handed it, through the item's own frameAt and shownAt.
    val reel = line(seven, sevenStarts, WordStyle(WordMode.Pop, perScreen = 3))
    val f = reel.frameAt(1_000 + 2_100)!!
    check(reel.shownAt(f) == "four five six", "the page shown was \"${reel.shownAt(f)}\"")
    check(f.words?.active == 4, "the frame's word being said was ${f.words}")
    // A Words arrival still reveals word by word inside the page.
    val revealing = line(seven, sevenStarts, WordStyle(WordMode.Highlight, perScreen = 3), TextMotion.Words)
    val r = revealing.frameAt(1_000 + 1_600)!!
    check(revealing.shownAt(r) == "four", "a Words arrival on page two showed \"${revealing.shownAt(r)}\"")
    // A Typewriter line types letters and has no word style; a plain line is whole.
    val typed = line(seven, sevenStarts, WordStyle(WordMode.Pop, perScreen = 3), TextMotion.Typewriter)
    check(typed.frameAt(1_000 + 3_900)?.words == null, "a typed line carried a word state")
    val plain = line(seven, sevenStarts, WordStyle.NONE)
    check(plain.shownAt(plain.frameAt(2_000)!!) == seven, "a plain line was cut")
    // Untimed: even shares of the line's length.
    val typedByHand = line("a b c d", style = WordStyle(WordMode.One))
    check(typedByHand.shownAt(typedByHand.frameAt(1_000 + 2_500)!!) == "c", "an untimed One-word line showed \"${typedByHand.shownAt(typedByHand.frameAt(3_500)!!)}\"")

    // The style travels with the line's style - Apply to all and a saved style carry it.
    val spec = TextStyleSpec(words = WordStyle(WordMode.Box, 0xFF39E75F.toInt(), 2))
    check(plain.withStyle(spec).style.words == spec.words, "the word style did not travel with the style")

    // Ink on the box: black on yellow, white on a dark green.
    check(CaptionWords.inkOn(WordStyle.HIGHLIGHT_YELLOW) == 0xFF000000.toInt(), "yellow box had white letters")
    check(CaptionWords.inkOn(0xFF0B6E2E.toInt()) == -1, "dark green box had black letters")

    // Twenty and more caption presets, every one distinct, and most of them word-timed.
    val presets = CaptionStylePreset.entries
    check(presets.size >= 20, "only ${presets.size} caption presets")
    check(presets.map { it.style }.toSet().size == presets.size, "two caption presets are the same style")
    check(presets.map { it.label }.toSet().size == presets.size, "two caption presets share a name")
    check(presets.count { it.style.words.isOn } >= 12, "only ${presets.count { it.style.words.isOn }} presets style words")

    if (problems.isEmpty()) println("CaptionWordsChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
