import com.squish.app.media.audio.WhisperText
import kotlin.system.exitProcess

/** EDGE_PLAN E1: whisper.cpp's word lines read into caption words with their times. */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val raw = "0\t320\tHello\n320\t600\t,\n640\t900\tworld\n900\t910\t.\n1000\t1200\t[BLANK_AUDIO]\nlang\ten\n"
    val r = WhisperText.parse(raw, offsetMs = 5_000)
    check(r != null, "a good answer did not parse")
    if (r != null) {
        check(r.words.map { it.text } == listOf("Hello,", "world."), "words were ${r.words.map { it.text }}")
        check(r.words[0].startMs == 5_000L && r.words[1].startMs == 5_640L, "word starts were not moved to the stretch's place: ${r.words.map { it.startMs }}")
        check(r.words[0].endMs == 5_600L, "a merged comma did not extend its word to ${r.words[0].endMs}")
        check(r.language == "en", "the language was ${r.language}")
        check(r.text == "Hello, world.", "the line read \"${r.text}\"")
    }
    check(WhisperText.parse(null) == null, "a failed run parsed")
    check(WhisperText.parse("lang\t\n")?.words?.isEmpty() == true, "silence gave words")
    check(WhisperText.parse("x\ty\tz\n1\n")?.words?.isEmpty() == true, "garbage gave words")
    // Malayalam and Hindi words, tabs inside a word kept out of the time fields.
    val ml = WhisperText.parse("0\t400\tനമസ്കാരം\n400\t800\tनमस्ते\nlang\tml\n")
    check(ml?.words?.map { it.text } == listOf("നമസ്കാരം", "नमस्ते") && ml.language == "ml", "non-Latin words were ${ml?.words}")
    // An end before its start (whisper.cpp can) is held at the start.
    check(WhisperText.parse("500\t400\tok\n")?.words?.first()?.endMs == 500L, "an end before its start was kept")
    // Word starts handed to a caption are from its stretch's start, as the segmenter's are:
    // whisper's are on the file's clock, and passed on as they were, every line after
    // the first was offset twice (seen on the phone: 2380 ms into a line that starts at 2310).
    val later = WhisperText.parse("0\t200\tIt\n300\t700\ttakes\n", offsetMs = 7_370)!!
    check(later.startsWithin(7_370) == listOf(0L, 300L), "starts within the stretch were ${later.startsWithin(7_370)}")
    check(later.startsWithin(7_500) == listOf(0L, 170L), "a start before the stretch was not held at 0: ${later.startsWithin(7_500)}")
    // ---- Bytes, not a JNI string (26 Oct, seen on the moto): whisper.cpp's
    // pieces can cut a Devanagari or Malayalam character's bytes in two, and
    // NewStringUTF aborted the app on the first Hindi file (CheckJNI: invalid
    // modified UTF-8). The text comes back as bytes; a word left mid-character
    // is joined to the next, and a byte nothing completes becomes U+FFFD.
    fun line(t0: Int, t1: Int, text: ByteArray) = "$t0\t$t1\t".toByteArray() + text + "\n".toByteArray()
    val namaste = "नमस्ते".toByteArray(Charsets.UTF_8)
    val cut = 4 // न is three bytes, so four is one byte into म
    val bytes = line(0, 300, namaste.copyOfRange(0, cut)) + line(300, 600, namaste.copyOfRange(cut, namaste.size)) +
        line(700, 900, "भाई".toByteArray()) + "lang\thi\n".toByteArray()
    val hi = WhisperText.parseBytes(bytes)
    check(hi?.words?.map { it.text } == listOf("नमस्ते", "भाई"), "a character cut across two pieces was not joined: ${hi?.words?.map { it.text }}")
    check(hi?.words?.firstOrNull()?.let { it.startMs == 0L && it.endMs == 600L } == true, "a joined word did not span both pieces: ${hi?.words?.firstOrNull()}")
    check(hi?.language == "hi", "the language was lost reading bytes: ${hi?.language}")
    // Every cut point of a Malayalam word and an emoji (four bytes, which
    // modified UTF-8 cannot carry at all) gives the word back whole.
    for (word in listOf("നമസ്കാരം", "🎉ok", "café")) {
        val b = word.toByteArray(Charsets.UTF_8)
        for (k in 1 until b.size) {
            val r2 = WhisperText.parseBytes(line(0, 100, b.copyOfRange(0, k)) + line(100, 200, b.copyOfRange(k, b.size)))
            val got = r2?.words?.joinToString("") { it.text }
            // Cut on a character boundary it is two whole words; cut inside one, one word.
            check(got == word, "\"$word\" cut at byte $k came back as ${r2?.words?.map { it.text }}")
            check(r2?.words?.none { '�' in it.text } == true, "\"$word\" cut at byte $k gained a replacement mark: ${r2?.words?.map { it.text }}")
        }
    }
    // A byte nothing completes, at the very end, is a mark rather than a crash or a lost word.
    val tail = WhisperText.parseBytes(line(0, 100, "ok".toByteArray()) + line(100, 200, byteArrayOf(0xE0.toByte(), 0xA4.toByte())))
    check(tail?.words?.size == 2 && tail.words[1].text == "�", "a dangling half character was ${tail?.words?.map { it.text }}")
    // And plain ASCII reads exactly as the string path does.
    check(WhisperText.parseBytes(raw.toByteArray())?.words == WhisperText.parse(raw)?.words, "the byte path disagrees with the string path on ASCII")
    check(WhisperText.parseBytes(null) == null, "a failed run parsed from bytes")

    // ---- The language's own script (26 Oct, on the moto): told "ml", whisper's
    // small model heard the converted Malayalam voice right and wrote it in
    // Devanagari - "नमस्कारं, चंद्त्येगे स्वागतं" - and a prompt in Malayalam did
    // not move it. The Brahmic blocks are laid out letter for letter, so the
    // words are moved into the script asked for.
    check(WhisperText.inScriptOf("नमस्कारं", "ml") == "നമസ്കാരം", "Devanagari was not carried into Malayalam: ${WhisperText.inScriptOf("नमस्कारं", "ml")}")
    check(WhisperText.inScriptOf("स्वागतं", "ml") == "സ്വാഗതം", "a conjunct was not carried: ${WhisperText.inScriptOf("स्वागतं", "ml")}")
    // A word ending on a consonant and virama is the chillu modern Malayalam writes.
    check(WhisperText.inScriptOf("सादनंगल्", "ml") == "സാദനംഗൽ", "a final l was not a chillu: ${WhisperText.inScriptOf("सादनंगल्", "ml")}")
    check(WhisperText.inScriptOf("अवन् वन्नु", "ml") == "അവൻ വന്നു", "a final n was not a chillu, or an inner one was: ${WhisperText.inScriptOf("अवन् वन्नु", "ml")}")
    // A nukta has no Malayalam letter and goes; Latin, digits and marks stay.
    check(WhisperText.inScriptOf("चंद़ ok 12,", "ml") == "ചംദ ok 12,", "a nukta, Latin or digits were mishandled: ${WhisperText.inScriptOf("चंद़ ok 12,", "ml")}")
    // Already in the right script, or a language with no Brahmic script: untouched.
    check(WhisperText.inScriptOf("നമസ്കാരം", "ml") == "നമസ്കാരം", "Malayalam was changed")
    check(WhisperText.inScriptOf("नमस्ते", "hi") == "नमस्ते" && WhisperText.inScriptOf("नमस्ते", "en") == "नमस्ते" && WhisperText.inScriptOf("नमस्ते", null) == "नमस्ते",
        "Hindi, or a language with no script of its own, was changed")
    // Hindi asked for and Bengali written: carried the other way too.
    check(WhisperText.inScriptOf("নমস্তে", "hi") == "नमस्ते", "Bengali was not carried into Devanagari: ${WhisperText.inScriptOf("নমস্তে", "hi")}")
    // Tamil has no letter for most of Devanagari's, so it is left as heard rather than half-carried.
    check(WhisperText.inScriptOf("नमस्ते", "ta") == "नमस्ते", "Devanagari was half-carried into Tamil")
    // And the whole result: every word of a parse in the asked-for script.
    val wrote = WhisperText.parse("0\t300\tनमस्कारं,\n300\t700\tस्वागतं\nlang\tml\n")!!.inScript("ml")
    check(wrote.words.map { it.text } == listOf("നമസ്കാരം,", "സ്വാഗതം") && wrote.words[1].startMs == 300L, "a result was not carried whole: ${wrote.words}")

    if (problems.isEmpty()) println("WhisperTextChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
