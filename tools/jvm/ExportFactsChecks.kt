import com.squish.app.export.ExportFacts
import java.util.Locale
import kotlin.system.exitProcess

/* EDGE_PLAN I2: the done screen's speed line, from measured numbers only. */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val en = Locale.ENGLISH
    fun line(r: Long, l: Long) = ExportFacts.speedLine(r, l, en)
    check(line(906, 10_014) == "Made in 0.9 s · 11× real time", "copy: ${line(906, 10_014)}")
    check(line(7_100, 10_000) == "Made in 7.1 s · 1.4× real time", "encode: ${line(7_100, 10_000)}")
    check(line(37_000, 51_000) == "Made in 37 s · 1.4× real time", "51 s reel: ${line(37_000, 51_000)}")
    check(line(80_000, 60_000) == "Made in 1 min 20 s · 0.8× real time", "slower than real time: ${line(80_000, 60_000)}")
    check(line(120_000, 600_000) == "Made in 2 min · 5.0× real time", "whole minutes: ${line(120_000, 600_000)}")
    check(line(0, 10_000) == null && line(5_000, 0) == null && line(-1, 5) == null, "a line from nothing measured")
    // On screen in the reader's own digits and separator; the numbers are the same.
    val de = ExportFacts.speedLine(7_100, 10_000, Locale.GERMAN)
    check(de == "Made in 7,1 s · 1,4× real time", "German: $de")

    if (problems.isEmpty()) println("ExportFactsChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
