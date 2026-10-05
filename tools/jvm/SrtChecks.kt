import com.squish.app.data.SrtCue
import com.squish.app.data.SrtFile
import kotlin.system.exitProcess

/*
 * The subtitle file, read and written.
 *
 * This is the escape hatch: where on-device speech recognition is missing or
 * poor in someone's language, bringing a transcript in from another tool is
 * the difference between captions being a feature and a dead end. So the
 * parser is deliberately forgiving, and "forgiving" is a promise with edges -
 * a BOM, CRLF, a dot before the milliseconds, a missing index, a caption whose
 * text happens to be a number, no blank line between cues. Every one of those
 * is a real file someone will hand us, and none of them had ever been run.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun one(raw: String) = SrtFile.parse(raw)

fun main() {
    // ---- What it writes, it reads ------------------------------------------
    run {
        val cues = listOf(
            SrtCue(0L, 1_500L, "First line"),
            SrtCue(2_000L, 4_250L, "Second line\nover two"),
            SrtCue(3_600_000L + 61_001L, 3_600_000L + 65_000L, "Past an hour")
        )
        val back = SrtFile.parse(SrtFile.format(cues))
        check(back.size == cues.size, "a round trip gave ${back.size} cues of ${cues.size}")
        cues.zip(back).forEach { (a, b) ->
            check(a.startMs == b.startMs && a.endMs == b.endMs, "a round trip moved ${a.text}: ${b.startMs}..${b.endMs}")
            check(a.text == b.text, "a round trip changed \"${a.text}\" into \"${b.text}\"")
        }
        // Written in order and numbered from one, whatever order they came in.
        val jumbled = SrtFile.format(listOf(SrtCue(5_000L, 6_000L, "b"), SrtCue(1_000L, 2_000L, "a")))
        check(jumbled.startsWith("1\n00:00:01,000 --> 00:00:02,000\na\n"), "out-of-order cues were not sorted:\n$jumbled")
    }

    // ---- The shapes real files come in --------------------------------------
    val plain = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n"
    run {
        check(one(plain).map { it.text } == listOf("Hello", "World"), "a plain file did not read")
        // A byte-order mark, CRLF, and lone CR. Written as a number rather
        // than as a character, so this file has no stray U+FEFF in it either.
        val bom = 0xFEFF.toChar().toString()
        check(one("$bom$plain").size == 2, "a BOM broke it")
        // And it is *gone*, not merely survived. The parse used to pass this
        // by being forgiving about the index line while the mark itself was
        // still on the front - which is fine until the first cue has no index,
        // when the mark lands in its text instead.
        check(
            one("$bom$plain").none { it.text.contains(bom) },
            "a BOM ended up inside a cue's text: ${one("$bom$plain").map { it.text }}"
        )
        val noIndex = "00:00:01,000 --> 00:00:02,000\nHello\n"
        check(
            one("$bom$noIndex").map { it.text } == listOf("Hello"),
            "a BOM on a file with no indices: ${one("$bom$noIndex").map { it.text }}"
        )
        check(one(plain.replace("\n", "\r\n")).size == 2, "CRLF broke it")
        check(one(plain.replace("\n", "\r")).size == 2, "lone CR broke it")
        // A dot before the milliseconds, as several tools write.
        check(one(plain.replace(',', '.')).first().endMs == 2_000L, "a dot before the milliseconds broke it")
        // No index at all.
        check(one("00:00:01,000 --> 00:00:02,000\nHello\n").size == 1, "a file with no indices did not read")
        // No blank line between cues.
        check(one("1\n00:00:01,000 --> 00:00:02,000\nHello\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n").size == 2,
            "cues with no blank line between them did not read")
        // Hours left off, and one- and two-digit fractions.
        check(one("00:01,5 --> 00:02,25\nHi\n").first().startMs == 1_500L, "a one-digit fraction is not tenths")
        check(one("00:01,5 --> 00:02,25\nHi\n").first().endMs == 2_250L, "a two-digit fraction is not hundredths")
    }

    // ---- A caption that looks like something else ---------------------------
    run {
        // Text that is a bare number, which is also what an index looks like.
        val numbered = "1\n00:00:01,000 --> 00:00:02,000\n42\n\n2\n00:00:03,000 --> 00:00:04,000\nNext\n"
        check(one(numbered).map { it.text } == listOf("42", "Next"), "a caption reading \"42\" was eaten as an index")
        // Text containing an arrow, which is also what a timing line looks like.
        val arrowed = "1\n00:00:01,000 --> 00:00:02,000\nhe said --> go\nand then left\n"
        val read = one(arrowed)
        check(read.size == 1, "a caption containing \"-->\" gave ${read.size} cues, want 1")
        check(
            read.firstOrNull()?.text == "he said --> go\nand then left",
            "a caption containing \"-->\" came out as \"${read.firstOrNull()?.text}\""
        )
    }

    // ---- What is not a cue is not kept --------------------------------------
    run {
        check(one("").isEmpty(), "an empty file gave cues")
        check(one("\n\n\n").isEmpty(), "blank lines gave cues")
        check(one("not a subtitle file at all").isEmpty(), "a file with no timings gave cues")
        // A timing with no text, and one that ends before it starts.
        check(one("00:00:01,000 --> 00:00:02,000\n\n").isEmpty(), "a cue with no words was kept")
        check(one("00:00:02,000 --> 00:00:01,000\nBackwards\n").isEmpty(), "a backwards cue was kept")
        check(one("00:00:01,000 --> 00:00:01,000\nNo length\n").isEmpty(), "a cue of no length was kept")
    }

    println("srt: the subtitle file, read and written")
    if (problems.isEmpty()) println("PASS - a round trip holds, and every shape a real file comes in reads")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
