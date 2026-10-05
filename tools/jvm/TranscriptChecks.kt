import com.squish.app.editor.Transcript
import com.squish.app.editor.Transcript.Line
import kotlin.system.exitProcess

// The edit read as words, and the stretches a chosen run of them covers. The
// timings have been landing with every auto-caption since B10 and nothing has
// ever shown them to anyone; this is what reading them has to get right.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- A timed line: every word knows its own moment. ---------------------
    run {
        val line = Line("l1", "hello there world", 1_000, 4_000, listOf(0L, 500L, 1_500L))
        val w = Transcript.words(listOf(line))
        check(w.size == 3, "three words read as ${w.size}")
        check(w.map { it.text } == listOf("hello", "there", "world"), "the words came back as ${w.map { it.text }}")
        check(w.all { it.timed }, "a line with a time per word was read as untimed")
        check(w[0].startMs == 1_000L && w[0].endMs == 1_500L, "the first word runs ${w[0].startMs}..${w[0].endMs}")
        check(w[1].startMs == 1_500L && w[1].endMs == 2_500L, "the second word runs ${w[1].startMs}..${w[1].endMs}")
        // The last word runs to the line's end: nothing else says where it stops.
        check(w[2].startMs == 2_500L && w[2].endMs == 4_000L, "the last word runs ${w[2].startMs}..${w[2].endMs}")
        // No word starts before the one before it ends.
        check(w.zipWithNext().all { (a, b) -> b.startMs >= a.startMs }, "the words are out of order")
    }

    // --- A line typed by hand has no timings, and does not pretend to. ------
    run {
        val line = Line("l2", "typed by hand", 2_000, 5_000)
        val w = Transcript.words(listOf(line))
        check(w.size == 3, "an untimed line read ${w.size} words")
        check(w.none { it.timed }, "an untimed line claimed timings")
        check(w.all { it.startMs == 2_000L && it.endMs == 5_000L }, "an untimed word did not take its line's span")
        // Retyping clears the timings (TextEdits does that), so a line whose
        // word count no longer matches its timings must read as untimed rather
        // than lining the wrong times up against the wrong words.
        val mismatched = Line("l3", "one two three four", 0, 1_000, listOf(0L, 100L))
        check(Transcript.words(listOf(mismatched)).none { it.timed }, "a line with the wrong number of timings was trusted")
    }

    // --- Empty and blank lines are not words. -------------------------------
    check(Transcript.words(listOf(Line("x", "   ", 0, 100))).isEmpty(), "a blank line produced words")
    check(Transcript.words(emptyList()).isEmpty(), "no lines produced words")
    run {
        // Several spaces between words is still one gap.
        val w = Transcript.words(listOf(Line("y", "a   b", 0, 100, listOf(0L, 50L))))
        check(w.size == 2 && w.all { it.timed }, "doubled spaces broke the word count")
    }

    // --- Lines come back in time order whatever order they are given. -------
    run {
        val late = Line("b", "second", 5_000, 6_000, listOf(0L))
        val early = Line("a", "first", 1_000, 2_000, listOf(0L))
        check(Transcript.words(listOf(late, early)).map { it.text } == listOf("first", "second"), "the lines were not put in time order")
    }

    // --- The stretch a chosen run covers. -----------------------------------
    run {
        val w = Transcript.words(listOf(Line("l", "one two three four", 0, 4_000, listOf(0L, 1_000L, 2_000L, 3_000L))))
        val span = Transcript.span(w.subList(1, 3))
        check(span != null && span.first == 1_000L && span.last == 3_000L, "two words in the middle cover $span")
        check(Transcript.span(emptyList()) == null, "no words covered a stretch")
        check(Transcript.span(listOf(w[0]))!!.let { it.first == 0L && it.last == 1_000L }, "one word covers the wrong stretch")
    }

    // --- Filler words. ------------------------------------------------------
    run {
        val w = Transcript.words(listOf(
            Line("l", "so um I was uh going", 0, 6_000, listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L))
        ))
        val runs = Transcript.fillerRuns(w)
        check(runs.size == 2, "two fillers gave ${runs.size} stretches")
        check(runs[0].first == 1_000L && runs[0].last == 2_000L, "the first filler covers ${runs[0]}")
        check(runs[1].first == 4_000L && runs[1].last == 5_000L, "the second filler covers ${runs[1]}")
        // "so" is not on the list: it is a real word far more often than it is
        // filler, and a pass that eats it is a pass nobody trusts twice.
        check(!Transcript.isFiller("so") && !Transcript.isFiller("like") && !Transcript.isFiller("right"),
            "a word that is usually meant was treated as filler")
        check(Transcript.isFiller("Um,") && Transcript.isFiller("UH") && Transcript.isFiller("uh."),
            "punctuation or capitals hid a filler")
    }

    // --- Neighbouring fillers are one stretch, not two cuts. ---------------
    run {
        val w = Transcript.words(listOf(
            Line("l", "um uh yes", 0, 3_000, listOf(0L, 1_000L, 2_000L))
        ))
        val runs = Transcript.fillerRuns(w)
        check(runs.size == 1, "two fillers side by side gave ${runs.size} stretches")
        check(runs[0].first == 0L && runs[0].last == 2_000L, "the run covers ${runs[0]}")
    }

    // --- Two fillers in different lines are two cuts, not one long one. -----
    //
    // The word list is every line's words laid end to end, so a line ending in
    // "um" and the next beginning "uh" put two fillers side by side in it with
    // the whole gap between the lines in between. Merged on their position in
    // the list, the pass cut that gap out of the edit too.
    run {
        val w = Transcript.words(listOf(
            Line("a", "hello there um", 0, 3_000, listOf(0L, 1_000L, 2_000L)),
            Line("b", "uh right then", 30_000, 33_000, listOf(0L, 1_000L, 2_000L))
        ))
        val runs = Transcript.fillerRuns(w)
        check(runs.size == 2, "two fillers twenty-seven seconds apart gave ${runs.size} stretch(es): $runs")
        check(runs.getOrNull(0) == 2_000L..3_000L, "the first line's filler covers ${runs.getOrNull(0)}")
        check(runs.getOrNull(1) == 30_000L..31_000L, "the second line's filler covers ${runs.getOrNull(1)}")
        check(runs.sumOf { it.last - it.first } < 3_000L,
            "the pass would cut ${runs.sumOf { it.last - it.first }} ms for two filler words")
    }

    // --- Two lines that run straight on are still one stretch. --------------
    //
    // Auto-captions land one line after another; a filler at the end of one and
    // the start of the next, with nothing between them, is one cut.
    run {
        val w = Transcript.words(listOf(
            Line("a", "hello um", 0, 2_000, listOf(0L, 1_000L)),
            Line("b", "uh right", 2_000, 4_000, listOf(0L, 1_000L))
        ))
        val runs = Transcript.fillerRuns(w)
        check(runs.size == 1, "two fillers meeting across a line break gave ${runs.size} stretches: $runs")
        check(runs[0] == 1_000L..3_000L, "the run across the line break covers ${runs[0]}")
        // And a breath between them is still one, but a pause is not.
        val breath = Transcript.words(listOf(
            Line("a", "hello um", 0, 2_000, listOf(0L, 1_000L)),
            Line("b", "uh right", 2_000 + Transcript.JOIN_SLACK_MS, 4_000, listOf(0L, 1_000L))
        ))
        check(Transcript.fillerRuns(breath).size == 1, "a breath between two fillers split them")
        val pause = Transcript.words(listOf(
            Line("a", "hello um", 0, 2_000, listOf(0L, 1_000L)),
            Line("b", "uh right", 4_000, 6_000, listOf(0L, 1_000L))
        ))
        check(Transcript.fillerRuns(pause).size == 2, "a two-second pause between two fillers was cut out with them")
    }

    // --- An untimed filler is left alone. -----------------------------------
    run {
        val w = Transcript.words(listOf(Line("l", "um hello", 0, 2_000)))
        check(Transcript.fillerRuns(w).isEmpty(), "an untimed filler was cut, which would take its whole line")
    }

    // --- What a line becomes when a stretch is taken out. -------------------
    run {
        val line = Line("l", "one two three four", 1_000, 5_000, listOf(0L, 1_000L, 2_000L, 3_000L))
        // Wholly before the stretch: untouched.
        check(Transcript.afterRemoval(line, 6_000, 7_000) == line, "a line before the stretch moved")
        // Wholly after: back by the stretch's length, words and all.
        val after = Transcript.afterRemoval(line, 0, 500)!!
        check(after.startMs == 500L && after.endMs == 4_500L, "a line after the stretch is at ${after.startMs}..${after.endMs}")
        check(after.wordStartsMs == line.wordStartsMs, "a line moved whole lost its word timings")
        // Wholly inside: gone.
        check(Transcript.afterRemoval(line, 0, 6_000) == null, "a line inside the stretch survived")
    }

    // --- A word taken out of the middle of a line. --------------------------
    run {
        val line = Line("l", "one two three four", 1_000, 5_000, listOf(0L, 1_000L, 2_000L, 3_000L))
        // "three" runs 3000..4000 on the edit's clock.
        val cut = Transcript.afterRemoval(line, 3_000, 4_000)!!
        check(cut.text == "one two four", "the line now reads \"${cut.text}\"")
        check(cut.startMs == 1_000L, "the line's start moved to ${cut.startMs}")
        check(cut.endMs == 4_000L, "the line ends at ${cut.endMs}, not 4000")
        // "four" was at 4000 and the stretch was a second: it is at 3000 now,
        // which is 2000 into a line that still starts at 1000.
        check(cut.wordStartsMs == listOf(0L, 1_000L, 2_000L), "the kept words are timed ${cut.wordStartsMs}")
    }

    // --- A line straddling each edge. ---------------------------------------
    run {
        val line = Line("l", "one two three four", 1_000, 5_000, listOf(0L, 1_000L, 2_000L, 3_000L))
        // The stretch starts inside the line and runs past its end.
        val head = Transcript.afterRemoval(line, 3_000, 9_000)!!
        check(head.text == "one two", "the head kept \"${head.text}\"")
        check(head.startMs == 1_000L && head.endMs == 3_000L, "the head runs ${head.startMs}..${head.endMs}")
        // The stretch starts before the line and ends inside it.
        val tail = Transcript.afterRemoval(line, 0, 3_000)!!
        check(tail.text == "three four", "the tail kept \"${tail.text}\"")
        check(tail.startMs == 0L, "the tail starts at ${tail.startMs}, not where the stretch began")
        check(tail.wordStartsMs == listOf(0L, 1_000L), "the tail's words are timed ${tail.wordStartsMs}")
    }

    // --- An untimed line is trimmed, never part-emptied. --------------------
    run {
        val line = Line("l", "typed by hand", 1_000, 5_000)
        val cut = Transcript.afterRemoval(line, 2_000, 3_000)!!
        check(cut.text == "typed by hand", "an untimed line lost words it had no times for")
        check(cut.startMs == 1_000L && cut.endMs == 4_000L, "an untimed line was trimmed to ${cut.startMs}..${cut.endMs}")
        check(Transcript.afterRemoval(line, 0, 9_000) == null, "an untimed line inside the stretch survived")
    }

    // --- A stretch of nothing changes nothing. ------------------------------
    run {
        val line = Line("l", "one two", 1_000, 3_000, listOf(0L, 1_000L))
        check(Transcript.afterRemoval(line, 2_000, 2_000) == line, "an empty stretch changed a line")
    }

    // --- The chosen run, against a list that changed under it. --------------
    //
    // The panel remembers the two ends as they were tapped and rebuilds the
    // word list whenever the lines change. An edit from outside it - an undo, a
    // trim that drops a line, a Delete on the strip - left the ends pointing
    // past the end of the list, and `words.slice(range)` threw an
    // IndexOutOfBoundsException in the middle of composition: the editor went
    // down rather than the choice going away.
    run {
        check(Transcript.chosenRange(2, 5, 10) == 2..5, "a plain choice is ${Transcript.chosenRange(2, 5, 10)}")
        check(Transcript.chosenRange(5, 2, 10) == 2..5, "a choice made backwards is ${Transcript.chosenRange(5, 2, 10)}")
        check(Transcript.chosenRange(3, 3, 10) == 3..3, "one word is ${Transcript.chosenRange(3, 3, 10)}")
        check(Transcript.chosenRange(null, 4, 10) == null, "half a choice is a choice")
        check(Transcript.chosenRange(4, null, 10) == null, "half a choice is a choice")
        check(Transcript.chosenRange(null, null, 10) == null, "no choice is a choice")
        // The list shrank under it: dropped, not clamped. Clamping would
        // quietly choose a different run of words in a panel whose next button
        // is Delete.
        check(Transcript.chosenRange(2, 8, 5) == null, "a choice past the end was kept: ${Transcript.chosenRange(2, 8, 5)}")
        check(Transcript.chosenRange(7, 9, 5) == null, "a choice wholly past the end was kept")
        check(Transcript.chosenRange(0, 4, 5) == 0..4, "a choice that exactly fits was dropped")
        check(Transcript.chosenRange(0, 5, 5) == null, "a choice one past the end was kept")
        // Every word gone.
        check(Transcript.chosenRange(0, 0, 0) == null, "a choice over no words was kept")
        check(Transcript.chosenRange(3, 3, 0) == null, "a choice over no words was kept")
        // And the thing the panel does with it never throws, for any pair of
        // ends over any length - which is the property the crash was.
        val words: List<String> = (0 until 12).map { "w$it" }
        for (a in -3..15) for (b in -3..15) for (n in 0..12) {
            val range = Transcript.chosenRange(a, b, n) ?: continue
            check(
                range.first >= 0 && range.last < n,
                "chosenRange($a, $b, $n) gave $range, which is not inside the list"
            )
            val sliced = runCatching { words.take(n).slice(range) }
            check(sliced.isSuccess, "chosenRange($a, $b, $n) gave $range, which slicing $n words throws on")
        }
    }

    println("transcript: ${Transcript.FILLERS.size} filler words")
    if (problems.isEmpty()) println("PASS - words carry their own moment, or say they do not")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
