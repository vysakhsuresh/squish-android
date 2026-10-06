import com.squish.app.data.SrtCue
import com.squish.app.data.PickedText
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
        // A blank line inside a caption. It *ends a cue* in this format, so the
        // writer cannot carry one: it used to trim only the ends, so a line
        // somebody had typed two returns into went out with a blank line in
        // the middle, and the parser - correctly - read that as the end of the
        // cue and dropped every line after it. The round trip lost half the
        // caption and this section, which promises that what it writes it
        // reads, did not look.
        run {
            val awkward = listOf(
                SrtCue(0L, 1_000L, "Top\n\nBottom"),
                SrtCue(2_000L, 3_000L, "  padded  \n   \n  lines  "),
                SrtCue(4_000L, 5_000L, "\n\nleading blanks"),
                SrtCue(6_000L, 7_000L, "trailing blanks\n\n")
            )
            val written = SrtFile.format(awkward)
            check(
                !written.contains("\n\n\n"),
                "a blank line went into the file inside a cue:\n$written"
            )
            val read = SrtFile.parse(written)
            check(read.size == awkward.size, "${read.size} cues came back of ${awkward.size}:\n$written")
            check(
                read.getOrNull(0)?.text == "Top\nBottom",
                "a caption with a blank line in it came back as \"${read.getOrNull(0)?.text}\""
            )
            check(
                read.getOrNull(1)?.text == "padded\nlines",
                "a caption with padded lines came back as \"${read.getOrNull(1)?.text}\""
            )
            check(
                read.getOrNull(2)?.text == "leading blanks" && read.getOrNull(3)?.text == "trailing blanks",
                "leading or trailing blanks survived: ${read.map { it.text }}"
            )
            // And every cue's timing survives it, which is the part that would
            // have been silently wrong: a dropped line shifts nothing, a
            // dropped *cue* shifts every number after it.
            awkward.zip(read).forEach { (a, b) ->
                check(a.startMs == b.startMs && a.endMs == b.endMs, "\"${a.text}\" moved to ${b.startMs}..${b.endMs}")
            }
            // A cue with nothing to say is not written, because the parser
            // would not produce one either.
            check(
                SrtFile.parse(SrtFile.format(listOf(SrtCue(0L, 1_000L, "   \n \n ")))).isEmpty(),
                "a blank caption was written and read back"
            )
        }

        // The digits, on a phone that does not use ours.
        //
        // `format` without a locale takes Locale.getDefault(FORMAT), and %02d
        // then emits that locale's own digit set - so on Arabic, Persian,
        // Burmese, Bengali or Nepali, "Export subtitles" wrote Eastern
        // Arabic-Indic or Devanagari numerals into the timing lines. No
        // subtitle tool can read that, and neither can this one: STAMP matches
        // \d, which in Java is [0-9] without UNICODE_CHARACTER_CLASS, so the
        // app's own export came back as no cues at all.
        run {
            val was = java.util.Locale.getDefault()
            try {
                for (tag in listOf("ar-EG", "fa-IR", "my-MM", "bn-IN", "ne-NP", "de-DE", "hi-IN")) {
                    java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                    val written = SrtFile.format(listOf(SrtCue(3_661_001L, 3_665_250L, "Hello")))
                    check(
                        written.contains("01:01:01,001 --> 01:01:05,250"),
                        "on $tag the timing line reads ${written.lines().getOrNull(1)}"
                    )
                    check(
                        written.all { it.code < 128 || it == '\n' },
                        "on $tag the file is not ASCII: $written"
                    )
                    val back = SrtFile.parse(written)
                    check(back.size == 1 && back[0].startMs == 3_661_001L, "on $tag a round trip gave $back")
                }
            } finally {
                java.util.Locale.setDefault(was)
            }
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

    // ---- A file from elsewhere is read in the encoding it is in. -----------
    //
    // It used to go through bufferedReader(), which is UTF-8 and *replaces*
    // what it cannot read. A subtitle file in a legacy single-byte encoding -
    // which is what Notepad's "ANSI" writes and what most subtitle archives
    // hold - has ASCII timing lines, so the cues parsed, the import reported
    // success, and every accented or non-Latin letter in the words had become
    // U+FFFD. The app's own round trip was safe, because it writes UTF-8; only
    // files from elsewhere were corrupted, and nothing warned.
    run {
        val line = "1\n00:00:01,000 --> 00:00:04,000\n"
        fun cue(text: String) = (line + text + "\n").toByteArray(Charsets.UTF_8)

        // UTF-8, with and without a byte-order mark.
        check(PickedText.decode(cue("Un café très chaud")).contains("café très"), "plain UTF-8 was mangled")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + cue("Un café")
        check(
            PickedText.decode(bom).let { it.contains("café") && !it.startsWith("﻿") },
            "a UTF-8 BOM was not taken off: ${PickedText.decode(bom).take(8)}"
        )

        // windows-1252, the one the sweep found: the words come back whole
        // rather than peppered with U+FFFD, and the cue still parses.
        val ansi = (line + "Un café très chaud\n").toByteArray(charset("windows-1252"))
        val read = PickedText.decode(ansi, language = "fr")
        check(!read.contains('�'), "a windows-1252 file still decoded to replacement characters: $read")
        check(read.contains("café très chaud"), "a windows-1252 file read as \"$read\"")
        check(SrtFile.parse(read).size == 1, "the decoded cue did not parse")
        check(SrtFile.parse(read).first().text == "Un café très chaud", "the cue read \"${SrtFile.parse(read).first().text}\"")

        // Cyrillic and Arabic, each in the encoding its language's files come
        // in, read on a phone set to that language.
        listOf(
            Triple("windows-1251", "ru", "Привет мир"),
            Triple("windows-1256", "ar", "مرحبا بالعالم"),
            Triple("windows-1253", "el", "Γειά σου κόσμε")
        ).forEach { (name, language, words) ->
            val bytes = (line + words + "\n").toByteArray(charset(name))
            val out = PickedText.decode(bytes, language = language)
            check(!out.contains('�'), "$name under $language gave replacement characters: $out")
            check(out.contains(words), "$name under $language read as \"$out\"")
            check(SrtFile.parse(out).firstOrNull()?.text == words, "$name under $language did not parse back")
        }

        // UTF-16, which a "Unicode" save from Notepad writes: both ways round,
        // and the timing line has to survive - it used to decode to nothing
        // readable at all, which was at least refused with a message.
        listOf(Charsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
               Charsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte())).forEach { (charset, mark) ->
            val bytes = mark + (line + "Un café\n").toByteArray(charset)
            val out = PickedText.decode(bytes)
            check(SrtFile.parse(out).firstOrNull()?.text == "Un café", "$charset read as \"$out\"")
        }

        // Plain ASCII is plain ASCII under every language.
        listOf("en", "ru", "ar", "ja", "zz").forEach { language ->
            check(
                PickedText.decode(cue("Hello"), language) == line + "Hello\n",
                "ASCII under $language read as \"${PickedText.decode(cue("Hello"), language)}\""
            )
        }
        // And nothing at all is nothing, not a crash.
        check(PickedText.decode(ByteArray(0)) == "", "no bytes gave something")
        check(PickedText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) == "", "a bare BOM gave something")
        // A file that is valid UTF-8 is never second-guessed, whatever the
        // phone's language is: UTF-8 is tried first and strictly.
        check(
            PickedText.decode(cue("Привет"), language = "ru").contains("Привет"),
            "a UTF-8 Cyrillic file was re-read as windows-1251"
        )
    }

    println("srt: the subtitle file, read and written")
    if (problems.isEmpty()) println("PASS - a round trip holds, and every shape a real file comes in reads")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
