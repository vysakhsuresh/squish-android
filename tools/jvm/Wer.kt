import java.io.File
import java.text.Normalizer
import kotlin.system.exitProcess

/*
 * EDGE_PLAN E1: how well a transcript matches what was said, as the word
 * error rate and the character error rate (the second is the fairer one for
 * Hindi and Malayalam, whose words are long and whose spellings vary). Both
 * after the same normalising - NFC, lower case, punctuation off, spaces made
 * one - so a comma is not an error.
 *
 *   java -cp <out;kotlin-stdlib> WerKt reference.txt hypothesis.txt
 *   java -cp <out;kotlin-stdlib> WerKt              (its own checks, as tools/jvm/run.sh runs it)
 */
fun normalise(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
        .replace(Regex("[\\p{P}\\p{S}]"), " ")
        .replace(Regex("\\s+"), " ").trim()

/** Levenshtein distance between two sequences. */
fun <T> edits(a: List<T>, b: List<T>): Int {
    var prev = IntArray(b.size + 1) { it }
    for (i in 1..a.size) {
        val cur = IntArray(b.size + 1)
        cur[0] = i
        for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.size]
}

fun wer(ref: String, hyp: String): Double {
    val r = normalise(ref).split(' ').filter { it.isNotEmpty() }
    val h = normalise(hyp).split(' ').filter { it.isNotEmpty() }
    return if (r.isEmpty()) (if (h.isEmpty()) 0.0 else 1.0) else edits(r, h).toDouble() / r.size
}

fun cer(ref: String, hyp: String): Double {
    fun cps(s: String) = normalise(s).replace(" ", "").codePoints().toArray().toList()
    val r = cps(ref)
    val h = cps(hyp)
    return if (r.isEmpty()) (if (h.isEmpty()) 0.0 else 1.0) else edits(r, h).toDouble() / r.size
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.first() == "--check") {
        val problems = mutableListOf<String>()
        fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
        check(wer("Hello, world.", "hello world") == 0.0, "punctuation counted as an error")
        check(wer("the cat sat", "the cat sat on") == 1.0 / 3, "an inserted word: ${wer("the cat sat", "the cat sat on")}")
        check(wer("the cat sat", "the bat sat") == 1.0 / 3, "a substituted word")
        check(wer("a b c d", "") == 1.0, "nothing heard is not 100%")
        check(cer("നമസ്കാരം", "നമസ്കാരം") == 0.0, "a Malayalam word against itself")
        check(cer("नमस्ते", "नमस्ते।") == 0.0, "a danda counted as an error")
        check(cer("abcd", "abxd") == 0.25, "one character in four: ${cer("abcd", "abxd")}")
        if (problems.isEmpty()) println("WerChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
        return
    }
    val ref = File(args[0]).readText()
    val hyp = File(args[1]).readText()
    println("WER %.1f%%  CER %.1f%%  (%d reference words)".format(java.util.Locale.ROOT, wer(ref, hyp) * 100, cer(ref, hyp) * 100,
        normalise(ref).split(' ').count { it.isNotEmpty() }))
}
