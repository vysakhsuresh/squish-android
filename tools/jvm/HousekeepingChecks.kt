import com.squish.app.data.DraftHousekeeping
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- A bin entry's name round-trips, whatever the slot looks like. ----------
    run {
        val editor = DraftHousekeeping.trashName("p1a2b3c4", 1_700_000_000_000L)
        check(DraftHousekeeping.parseTrashName(editor) == ("p1a2b3c4" to 1_700_000_000_000L), "editor slot did not round-trip: $editor")

        // A tool slot is the tool id and a UUID, which is full of dashes.
        val slot = "stitch-3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val tool = DraftHousekeeping.trashName(slot, 42L)
        check(DraftHousekeeping.parseTrashName(tool) == (slot to 42L), "tool slot did not round-trip: $tool")

        // Legacy tool slots are the bare tool id.
        check(DraftHousekeeping.parseTrashName("stitch-7") == ("stitch" to 7L), "legacy slot did not parse")

        // Names this code never wrote are refused rather than misread.
        check(DraftHousekeeping.parseTrashName("nodash") == null, "a name with no dash parsed")
        check(DraftHousekeeping.parseTrashName("p1-") == null, "a name with nothing after the dash parsed")
        check(DraftHousekeeping.parseTrashName("-5") == null, "a name with no slot parsed")
        check(DraftHousekeeping.parseTrashName("p1-abc") == null, "a name with a non-numeric time parsed")
        check(DraftHousekeeping.parseTrashName("p1-0") == null, "a zero time parsed")
    }

    // --- The bin keeps things for thirty days, not twenty-nine, not forever. ----
    run {
        val day = 24L * 60 * 60 * 1000
        val at = 1_000_000L
        check(!DraftHousekeeping.isExpired(at, at), "expired the instant it was discarded")
        check(!DraftHousekeeping.isExpired(at, at + 29 * day), "expired after 29 days")
        check(!DraftHousekeeping.isExpired(at, at + 30 * day), "expired on the 30th day exactly")
        check(DraftHousekeeping.isExpired(at, at + 30 * day + 1), "not expired after 30 days")
        check(!DraftHousekeeping.isExpired(at, at - day), "a clock that went backwards expired it")
    }

    // --- The snapshot is allowed to be ten minutes stale, and no staler. --------
    run {
        val minute = 60_000L
        val now = 5_000_000L
        check(DraftHousekeeping.snapshotDue(null, now), "no snapshot yet was not due")
        check(DraftHousekeeping.snapshotDue(0L, now), "an unreadable mtime was not due")
        check(!DraftHousekeeping.snapshotDue(now - 1, now), "a fresh snapshot was due")
        check(!DraftHousekeeping.snapshotDue(now - 9 * minute, now), "a nine-minute-old snapshot was due")
        check(DraftHousekeeping.snapshotDue(now - 10 * minute, now), "a ten-minute-old snapshot was not due")
        check(DraftHousekeeping.snapshotDue(now - 60 * minute, now), "an hour-old snapshot was not due")
        // A snapshot stamped in the future would never come due again.
        check(DraftHousekeeping.snapshotDue(now + minute, now), "a future snapshot was not due")
    }

    // --- "Exported" versus "edited since". --------------------------------------
    run {
        check(!DraftHousekeeping.editedSinceExport(10L, null), "never exported counted as edited since")
        check(!DraftHousekeeping.editedSinceExport(10L, 10L), "the export's own flush counted as an edit")
        check(!DraftHousekeeping.editedSinceExport(9L, 10L), "an older save counted as edited since")
        check(DraftHousekeeping.editedSinceExport(11L, 10L), "a later save did not count as edited since")
    }

    if (problems.isNotEmpty()) {
        problems.forEach { println("FAIL - $it") }
        exitProcess(1)
    }
    println("PASS - the bin names, the thirty-day purge and the ten-minute snapshot behave")
}
