import com.squish.app.media.ExportEntry
import com.squish.app.media.ExportJournal
import kotlin.system.exitProcess

/**
 * EDGE_PLAN B6: an export killed with its process is reported on the next
 * launch, once, and never one that is still running or long forgotten.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val now = 1_800_000_000_000L
    val e = ExportEntry("Goa trip, 12 Oct", "j123abc", now - 60_000, pid = 4242)

    // Round trip, including a title with '=' and a newline in it.
    check(ExportJournal.decode(ExportJournal.encode(e)) == e, "an entry did not survive its own file")
    val odd = e.copy(title = "a = b\nc", projectId = null)
    val back = ExportJournal.decode(ExportJournal.encode(odd))
    check(back?.title == "a = b c", "a title with '=' and a newline came back as ${back?.title}")
    check(back?.projectId == null, "a quick tool's export gained a project")
    check(ExportJournal.decode("") == null, "an empty file read as an export")
    check(ExportJournal.decode("v=2\npid=1\nstarted=1\n") == null, "a journal from a future format was trusted")
    check(ExportJournal.decode("v=1\npid=x\nstarted=1\n") == null, "a broken pid was read")

    // The verdict.
    check(ExportJournal.interrupted(e, currentPid = 999, nowMillis = now) == e, "an export from a dead process was not reported")
    check(ExportJournal.interrupted(e, currentPid = 4242, nowMillis = now) == null, "this process's own running export was called interrupted")
    check(ExportJournal.interrupted(null, 1, now) == null, "no journal reported something")
    val old = e.copy(startedAtMillis = now - ExportJournal.STALE_AFTER_MS - 1)
    check(ExportJournal.interrupted(old, 999, now) == null, "a week-old export was raised")
    val future = e.copy(startedAtMillis = now + 3_600_000)
    check(ExportJournal.interrupted(future, 999, now) == future, "a clock set back hid an interrupted export")
    check(ExportJournal.message(e).contains("Goa trip, 12 Oct"), "the message does not name the export")

    if (problems.isEmpty()) println("ExportJournalChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
