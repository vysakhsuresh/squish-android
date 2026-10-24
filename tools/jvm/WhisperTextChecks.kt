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
    if (problems.isEmpty()) println("WhisperTextChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
