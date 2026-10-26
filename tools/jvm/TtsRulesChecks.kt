import com.squish.app.media.audio.TtsRules
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/* EDGE_PLAN E5: what passes between an on-device voice's three models. */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // ---- Characters to ids ---------------------------------------------------
    val latin = listOf("<pad>", " ", "a", "b", "c", "e", "h", "l", "o", ",", ".")
    check(TtsRules.ids("Hello,  bob.", latin).toList() == listOf(6, 5, 7, 7, 8, 9, 1, 3, 8, 3, 10), "Latin ids: ${TtsRules.ids("Hello,  bob.", latin).toList()}")
    check(TtsRules.ids("   ", latin).isEmpty(), "a blank line gave ids")
    check(TtsRules.ids("hex!", latin).toList() == listOf(6, 5), "a character the list does not hold was not dropped")
    // Malayalam: a consonant, a vowel sign and a virama are each their own symbol.
    val ml = listOf("<pad>", " ", "ന", "മ", "സ", "്", "ക", "ാ", "ര", "ം")
    check(TtsRules.ids("നമസ്കാരം", ml).toList() == listOf(2, 3, 4, 5, 6, 7, 8, 9), "Malayalam ids: ${TtsRules.ids("നമസ്കാരം", ml).toList()}")
    // The first of two equal symbols is the one used, and the pad is never produced by text.
    check(TtsRules.ids("a", listOf("<pad>", "a", "a")).toList() == listOf(1), "a repeated symbol took the later id")

    // ---- Sentences the encoder takes in one call -------------------------------
    check(TtsRules.sentences("Hello there. How are you? Fine!", 192) == listOf("Hello there.", "How are you?", "Fine!"), "sentences: ${TtsRules.sentences("Hello there. How are you? Fine!", 192)}")
    check(TtsRules.sentences("नमस्ते। आप कैसे हैं॥ ठीक", 192) == listOf("नमस्ते।", "आप कैसे हैं॥", "ठीक"), "Hindi sentences: ${TtsRules.sentences("नमस्ते। आप कैसे हैं॥ ठीक", 192)}")
    val long = (1..60).joinToString(" ") { "word$it" }
    val parts = TtsRules.sentences(long, 40)
    check(parts.all { it.length <= 40 }, "a piece over the limit: ${parts.maxOf { it.length }}")
    check(parts.joinToString(" ") == long, "words lost or split splitting a long sentence")
    check(TtsRules.sentences("a, b, c, d, e, f", 6).all { it.length <= 6 } && TtsRules.sentences("a, b, c, d, e, f", 6).joinToString(" ").replace(" ", "") == "a,b,c,d,e,f", "commas: ${TtsRules.sentences("a, b, c, d, e, f", 6)}")
    check(TtsRules.sentences("x".repeat(25), 10).map { it.length } == listOf(10, 10, 5), "an overlong word is not cut at the limit")
    check(TtsRules.sentences(" . ! ", 50).isEmpty(), "punctuation alone was spoken")

    // ---- Durations, as FastPitch rounds them ----------------------------------
    val log = floatArrayOf(0f, kotlin.math.ln(4.0).toFloat(), kotlin.math.ln(10.5).toFloat(), -3f)
    check(TtsRules.durations(log).toList() == listOf(1, 3, 10, 1), "durations: ${TtsRules.durations(log).toList()}")
    check(TtsRules.durations(log, 2f).toList() == listOf(1, 6, 19, 1), "slowed durations: ${TtsRules.durations(log, 2f).toList()}")

    // ---- Expansion -----------------------------------------------------------
    val c = 3
    val feats = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f) // t = 2
    val ex = TtsRules.expand(feats, 2, c, intArrayOf(2, 1))
    check(ex.toList() == listOf(1f, 2f, 3f, 1f, 2f, 3f, 4f, 5f, 6f), "expanded: ${ex.toList()}")
    check(TtsRules.expand(feats, 2, c, intArrayOf(0, 0)).isEmpty(), "zero durations gave frames")

    // ---- Vocoder chunks cover everything once, and the seams do not click ------
    for (frames in listOf(1, 99, 256, 257, 600, 1000)) {
        val starts = TtsRules.chunkStarts(frames, 256, 16)
        check(starts.first() == 0, "chunks of $frames do not start at 0")
        check(starts.last() + 256 >= frames, "chunks of $frames stop short: last at ${starts.last()}")
        check(starts.zipWithNext().all { (a, b) -> b - a == 240 }, "chunks of $frames are not 240 apart")
    }
    // A sine voiced in chunks and stitched comes back as the sine, seams included.
    val hop = 4
    val frames = 300
    val total = frames * hop
    val sine = FloatArray(total) { sin(2 * PI * it / 37.0).toFloat() * 0.5f }
    val starts = TtsRules.chunkStarts(frames, 64, 8)
    val chunks = starts.map { s -> FloatArray(64 * hop) { j -> sine.getOrElse(s * hop + j) { 0f } } }
    val back = TtsRules.stitch(chunks, starts.map { it * hop }, 8 * hop, total)
    val worst = back.indices.maxOf { abs(back[it] - sine[it]) }
    check(worst < 1e-5f, "a stitched sine is off by $worst")
    // And two chunks that disagree meet with no step: the fade takes it.
    val a = FloatArray(64) { 1f }
    val b = FloatArray(64) { -1f }
    val joined = TtsRules.stitch(listOf(a, b), listOf(0, 48), 16, 112)
    val steps = joined.toList().zipWithNext().maxOf { (x, y) -> abs(y - x) }
    check(steps <= 2f / 16 + 1e-4f, "two chunks meet in a step of $steps")

    if (problems.isEmpty()) println("TtsRulesChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
