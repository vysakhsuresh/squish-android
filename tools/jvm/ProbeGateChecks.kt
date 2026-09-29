import com.squish.app.editor.ProbeGate
import kotlin.system.exitProcess

/**
 * The export sheet's encoder probe, replayed with answers landing out of
 * order: the sheet must end up with the answer to the size chosen, never with
 * an earlier size's answer standing in for it.
 */
val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // The sheet's own memory: the answer kept, by the size it was asked for.
    var kept: Pair<String, String>? = null
    val gate = ProbeGate<String> { kept?.first == it }
    var chosen = "1080p"

    // Tap 1080p: asked once, not again while it is on its way.
    check(gate.ask(chosen), "the first question was not sent")
    check(!gate.ask(chosen), "a question on its way was sent again")
    check(gate.pending == "1080p", "pending ${gate.pending}")

    // Tap 4K before the answer: its own question goes out.
    chosen = "4K"
    check(gate.ask(chosen), "a new size was not asked about while another was pending")

    // 4K answers first, while 4K is chosen: kept.
    check(gate.keep("4K", chosen), "the answer to the size chosen was dropped")
    kept = "4K" to "1440x1080"
    check(gate.pending == null, "an answered question is still pending: ${gate.pending}")

    // 1080p's answer lands last, with 4K chosen: dropped - it would have stood
    // in for 4K's, and the sheet would have promised a size the encoder does
    // not write.
    check(!gate.keep("1080p", chosen), "a stale answer was kept over the current size's")
    check(kept.first == "4K", "the kept answer changed")
    check(!gate.ask("4K"), "an answered size was asked about again")

    // Back to 1080p: its answer was dropped, so it is asked again, and kept.
    chosen = "1080p"
    check(gate.ask(chosen), "a size whose answer was dropped was not asked again")
    check(gate.keep("1080p", chosen), "the fresh answer was dropped")
    kept = "1080p" to "1920x1080"

    // Every order of answers, for three sizes tapped in a row: whatever lands
    // last, the answer kept is for the size chosen last, and that size was
    // asked about after it was chosen.
    val sizes = listOf("720p", "1080p", "4K")
    val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
    for (order in orders) {
        var memory: String? = null
        val g = ProbeGate<String> { memory == it }
        val sent = mutableListOf<String>()
        for (s in sizes) if (g.ask(s)) sent += s
        check(sent == sizes, "not every size tapped was asked about: $sent")
        val current = sizes.last()
        for (i in order) if (g.keep(sizes[i], current)) memory = sizes[i]
        check(memory == current, "answers in order $order left the sheet with $memory for $current")
    }

    // Tapping the same size twice while the answer is on its way, then a
    // different one, then the first again: the first's late answer still lands
    // on the first, because it is chosen again.
    run {
        var memory: String? = null
        val g = ProbeGate<String> { memory == it }
        check(g.ask("720p"), "720p not asked")
        check(!g.ask("720p"), "720p asked twice")
        check(g.ask("4K"), "4K not asked")
        check(g.ask("720p"), "720p, chosen again with 4K pending, was not asked again")
        if (g.keep("720p", "720p")) memory = "720p"
        check(memory == "720p", "720p's answer was dropped while 720p was chosen")
        check(!g.keep("4K", "720p"), "4K's answer was kept with 720p chosen")
    }

    if (problems.isEmpty()) {
        println("PASS - the sheet keeps the answer for the size chosen, whatever order the answers land in")
    } else {
        println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1)
    }
}
