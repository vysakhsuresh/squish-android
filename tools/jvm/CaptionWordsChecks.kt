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

    // ---- Keyword emphasis, an emoji after a word, a second language (E2, P4) ----
    run {
        val line = "make it loud and proud"
        // An emoji is held to its word: the line still splits into the same words.
        val dec = CaptionWords.decorated(line, 0, mapOf(2 to "🔥"))
        check(CaptionWords.words(dec).size == 5, "an emoji changed the word count: '$dec'")
        check(CaptionWords.words(dec)[2].startsWith("loud") && CaptionWords.words(dec)[2].endsWith("🔥"), "the emoji is not after word 2: '$dec'")
        check(CaptionWords.words(dec)[3] == "and", "the emoji moved the next word: '$dec'")
        // On a page, the emoji goes by the word's place in the whole line.
        val page = CaptionWords.decorated("loud and", 2, mapOf(2 to "🔥", 0 to "😂"))
        check(page == "loud${CaptionWords.NO_BREAK_SPACE}🔥 and", "a page's emoji went by its place on the page: '$page'")
        // The word being said is still the right one with an emoji before it.
        val st = WordState(0, 5, 3)
        val range = CaptionWords.charRange(CaptionWords.words(dec), st, 3)
        check(range != null && dec.substring(range.first, range.last + 1) == "and", "the lit word after an emoji is '${range?.let { dec.substring(it.first, it.last + 1) }}'")
        // Picked-out words, by their place in the line, on the decorated text and on a page.
        val picked = CaptionWords.emphasised(dec, 0, setOf(0, 4))
        check(picked.map { dec.substring(it.first, it.last + 1) } == listOf("make", "proud"), "picked-out ranges: ${picked.map { dec.substring(it.first, it.last + 1) }}")
        check(CaptionWords.emphasised("loud and", 2, setOf(0, 3)).size == 1, "a page picked out a word not on it")
        // The second language goes after the first, so every range on the first is unchanged.
        val (both, at) = CaptionWords.withSecondLine(dec, "hazlo fuerte", showBoth = true)
        check(at == dec.length + 1 && both.startsWith(dec) && both.substring(at) == "hazlo fuerte", "second line: '$both' at $at")
        check(CaptionWords.charRange(CaptionWords.words(dec), st, 3) == range, "the second line moved the lit word")
        check(CaptionWords.withSecondLine(dec, "hazlo", showBoth = false).second == -1, "a second line drawn with Show both off")
        check(CaptionWords.withSecondLine(dec, "  ", showBoth = true).second == -1, "a blank second line drawn")

        // A two-language block placed low stays inside the frame, at every ratio, and is not moved when it fits.
        for ((fw, fh) in listOf(1080 to 1920, 1080 to 1080, 1920 to 1080)) {
            // Two lines at the default size: the line 28/360 of the short edge, the second at 0.72 of it, padding.
            val lineH = 28f / 360f * minOf(fw, fh) * 1.25f
            val blockH = lineH * (1f + CaptionWords.SECOND_LINE_SCALE) + lineH * 0.4f
            val half = blockH / 2f / fh
            for (placed in listOf(0.05f, 0.5f, 0.85f, 0.97f)) {
                val y = CaptionWords.keptInFrame(placed, half)
                check(y - half >= 0.02f - 1e-4f && y + half <= 0.98f + 1e-4f, "${fw}x$fh at $placed: the block runs from ${y - half} to ${y + half}")
                if (placed - half >= 0.02f && placed + half <= 0.98f) check(y == placed, "${fw}x$fh: a block that fits at $placed was moved to $y")
            }
        }
        check(CaptionWords.keptInFrame(0.9f, 0.7f) == 0.5f, "a block taller than the frame is not centred")

        // Words taken out by the transcript take what they kept with them; the rest keep theirs.
        val styled = TextOverlayItem(
            id = "l", text = line, startMs = 0, endMs = 5_000, colorArgb = -1,
            wordColors = mapOf(1 to 0xFF00FF00.toInt(), 4 to 0xFFFF0000.toInt()),
            emphasis = setOf(2, 4), wordEmoji = mapOf(2 to "🔥", 3 to "😂")
        )
        // "it" (1) and "and" (3) went: kept are 0, 2, 4 -> now 0, 1, 2.
        val after = styled.keptWords(listOf(0, 2, 4))
        check(after.wordColors == mapOf(2 to 0xFFFF0000.toInt()), "colours after removal: ${after.wordColors}")
        check(after.emphasis == setOf(1, 2), "picked-out words after removal: ${after.emphasis}")
        check(after.wordEmoji == mapOf(1 to "🔥"), "emoji after removal: ${after.wordEmoji}")
    }
    // A line pinned to a track grows with what it follows (F8, 28 Oct: TAG on
    // a card that grew 1.6 times stayed its first size), and an unpinned one,
    // or one on a track that never changed size, is drawn as it was.
    run {
        val track = com.squish.app.media.video.MotionTrack(listOf(
            com.squish.app.media.video.TrackSample(0, 0.3f, 0.4f, scale = 1f),
            com.squish.app.media.video.TrackSample(9000, 0.7f, 0.5f, scale = 1.6f)
        ))
        val line = com.squish.app.editor.TextOverlayItem(id = "tag", text = "TAG", startMs = 0, endMs = 9000, colorArgb = -1)
        val pinned = line.copy(track = track, trackGrows = true)
        // A line pinned by an older build: the same track, its size never drawn.
        val older = line.copy(track = track)
        check(older.frameAt(4500)?.scale == line.frameAt(4500)?.scale, "a line pinned before lines grew with their track now changes size")
        val plain = line.frameAt(4500)?.scale ?: 0f
        val grown = pinned.frameAt(4500)?.scale ?: 0f
        check(kotlin.math.abs(grown - plain * 1.3f) < 0.02f, "a pinned line half way along a track growing to 1.6 is drawn at $grown, not ${plain * 1.3f}")
        check(kotlin.math.abs((pinned.frameAt(8900)?.scale ?: 0f) - (line.frameAt(8900)?.scale ?: 0f) * 1.6f) < 0.03f, "a pinned line does not reach its track's 1.6")
        check(line.frameAt(4500)?.scale == plain, "an unpinned line's size moved")
    }


    if (problems.isEmpty()) println("CaptionWordsChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
