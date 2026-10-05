import com.squish.app.media.StillPrune
import kotlin.system.exitProcess

/*
 * Which backdrop stills are let go of, executed.
 *
 * The fault: prune ran after every write, including the writes the export
 * itself was making, so an export of a padded canvas with more stretches than
 * the cap deleted the earliest of its own backdrops out from under its own
 * plan - the plan names each file and the encoder opens it minutes later, by
 * which time it was unlinked, so the export stopped on a missing image item or
 * ran with those stretches black. The whole defence was a sixty-second grace
 * on the file's own age, which does not protect a still made for the same
 * export a minute earlier, nor one the preview made while scrubbing before the
 * export began. The cap was therefore a silent ceiling on how many shots a
 * blurred canvas could export.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** [n] stills, the first written longest ago. */
private fun stills(n: Int, from: Long = 1_000L) = (0 until n).map { "blur_$it" to from + it * 1_000L }

fun main() {
    // ---- Held: nothing goes, however many there are. -----------------------
    //
    // This is the one that matters. An export of sixty stretches makes sixty
    // stills and names all sixty; none may go while it runs, old or new.
    run {
        val sixty = stills(60)
        check(
            StillPrune.victims(sixty, keep = 48, held = true).isEmpty(),
            "a hold did not stop the prune: ${StillPrune.victims(sixty, keep = 48, held = true)}"
        )
        // And the age of the oldest makes no difference to that - the grace it
        // replaced was a wall clock, and the scrub's stills are minutes old.
        val aged = stills(60, from = -5_000_000L)
        check(StillPrune.victims(aged, keep = 48, held = true).isEmpty(), "a hold let an old still go")
        // Released, the same list trims to the cap, oldest first.
        val gone = StillPrune.victims(sixty, keep = 48, held = false)
        check(gone.size == 12, "releasing the hold let ${gone.size} go rather than 12")
        check(gone == (0 until 12).map { "blur_$it" }, "the wrong twelve went: $gone")
    }

    // ---- Under the cap, nothing goes. --------------------------------------
    run {
        check(StillPrune.victims(stills(48), keep = 48).isEmpty(), "48 stills with a cap of 48 lost one")
        check(StillPrune.victims(stills(1), keep = 48).isEmpty(), "one still was pruned")
        check(StillPrune.victims(emptyList(), keep = 48).isEmpty(), "an empty folder produced victims")
        check(StillPrune.victims(stills(49), keep = 48) == listOf("blur_0"), "one over the cap lost the wrong one")
    }

    // ---- The file just written is kept whatever its age. --------------------
    run {
        // 49 files, and the one being written is the oldest by timestamp. It is
        // not a candidate, so the next-oldest goes instead and the folder is
        // left at the cap.
        val list = listOf("blur_new" to 0L) + stills(48)
        val gone = StillPrune.victims(list, keep = 48, keeping = "blur_new")
        check(gone.isEmpty(), "the file just written was counted and something went: $gone")
        val fifty = listOf("blur_new" to 0L) + stills(49)
        check(
            StillPrune.victims(fifty, keep = 48, keeping = "blur_new") == listOf("blur_0"),
            "with the new file held out, the victim was ${StillPrune.victims(fifty, keep = 48, keeping = "blur_new")}"
        )
    }

    // ---- The answer is a function of the input, not of the listing order. ---
    //
    // Two stills written in the same millisecond used to be ordered by whatever
    // the directory happened to say, so which one went was not reproducible -
    // and a prune nobody can predict is a prune nobody can check.
    run {
        val same = listOf("blur_b" to 7L, "blur_a" to 7L, "blur_c" to 7L, "blur_d" to 9L)
        val once = StillPrune.victims(same, keep = 2)
        val again = StillPrune.victims(same.reversed(), keep = 2)
        check(once == again, "the same stills in another order gave $once and $again")
        check(once == listOf("blur_a", "blur_b"), "the tie was not broken by name: $once")
    }

    // ---- A cap of nothing takes everything. --------------------------------
    run {
        check(StillPrune.victims(stills(3), keep = 0).size == 3, "a cap of nothing kept something")
        check(StillPrune.victims(stills(3), keep = 0, held = true).isEmpty(), "a cap of nothing beat the hold")
    }

    if (problems.isEmpty()) {
        println("StillPruneChecks: a render's own backdrops survive it, and the folder is still capped")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}
