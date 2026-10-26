import com.squish.app.media.DecoderBudget
import com.squish.app.media.DecoderBudget.Stream
import kotlin.system.exitProcess

/* EDGE_PLAN C4: the decoder's budget in blocks a second, and which files need their light copy. */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val moto = 489_600L // c2.qti.avc.decoder, blocks-per-second, /vendor/etc/media_codecs_c2.xml
    // Costs.
    check(DecoderBudget.load(1920, 1080, 30f) == 120L * 68 * 30, "1080p30: ${DecoderBudget.load(1920, 1080, 30f)}")
    check(DecoderBudget.load(1920, 1080, 60f) == moto, "1080p60 is not the moto's whole figure")
    check(DecoderBudget.proxyLoad(1920, 1080, 30f) == 60L * 34 * 30, "540p copy: ${DecoderBudget.proxyLoad(1920, 1080, 30f)}")
    check(DecoderBudget.proxyLoad(1080, 1920, 30f) == DecoderBudget.proxyLoad(1920, 1080, 30f), "portrait copy costs differ")
    check(DecoderBudget.proxyLoad(854, 480, 30f) == DecoderBudget.load(854, 480, 30f), "a 480p file got cheaper as a copy")
    // Rows.
    check(DecoderBudget.footageRows(moto) == 6, "moto rows: ${DecoderBudget.footageRows(moto)}")
    check(DecoderBudget.footageRows(0) == 3, "unknown capacity is not the old 3")
    check(DecoderBudget.footageRows(245_760) == 3, "a weak decoder (1080p30) was given more than 3: ${DecoderBudget.footageRows(245_760)}")
    check(DecoderBudget.footageRows(2_000_000) == 6, "a strong decoder past 6 rows")
    // Copies: the base shot plus six 1080p30 overlays together overload the moto.
    val full = DecoderBudget.load(1920, 1080, 30f); val light = DecoderBudget.proxyLoad(1920, 1080, 30f)
    val base = Stream("base", 0, 10_000, full, light)
    val six = (1..6).map { Stream("o$it", 2_000, 8_000, full, light) }
    val copies = DecoderBudget.needingCopies(listOf(base) + six, moto)
    val total = (listOf(base) + six).sumOf { if (it.key in copies) it.lightLoad else it.load }
    check(total <= moto, "still over budget with copies $copies: $total")
    check(copies.size >= 6, "fewer copies than the moment needs: $copies")
    // One overlay with the base fits: no copies made for nothing.
    check(DecoderBudget.needingCopies(listOf(base, six[0]), moto).isEmpty(), "a copy made where two 1080p30 streams fit")
    // An overlay that never meets the busy stretch keeps its original.
    val lone = Stream("lone", 9_000, 10_000, full, light)
    check("lone" !in DecoderBudget.needingCopies(listOf(base) + six + lone, moto), "a file outside the busy stretch got a copy")
    // Nothing cheaper as a copy is never chosen, and the loop ends.
    val small = (1..20).map { Stream("s$it", 0, 1_000, 1000, 1000) }
    check(DecoderBudget.needingCopies(small, 5_000).isEmpty(), "a copy chosen that saves nothing")

    if (problems.isEmpty()) println("DecoderBudgetChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
