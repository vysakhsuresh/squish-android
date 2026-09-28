import com.squish.app.data.DraftFiles
import com.squish.app.data.DraftHousekeeping
import java.io.File
import java.nio.file.Files
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

    // --- The snapshots move along every ten minutes, and no more often. ---------
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

    // --- Fingerprints tell edits apart and nothing else. ------------------------
    run {
        val a = DraftHousekeeping.fingerprint("""{"clips":[1,2]}""")
        check(a == DraftHousekeeping.fingerprint("""{"clips":[1,2]}"""), "the same text fingerprinted two ways")
        check(a != DraftHousekeeping.fingerprint("""{"clips":[2,1]}"""), "two different edits fingerprinted the same")
        check(a.length == 64 && a.all { it in "0123456789abcdef" }, "a fingerprint was not 64 hex digits: $a")
    }

    // --- "Exported" versus "edited since". --------------------------------------
    run {
        val same = DraftHousekeeping.fingerprint("edit")
        val other = DraftHousekeeping.fingerprint("edit, then a cut")
        check(!DraftHousekeeping.editedSinceExport(10L, null), "never exported counted as edited since")
        check(!DraftHousekeeping.editedSinceExport(10L, null, same, same), "never exported counted as edited since, with fingerprints")

        // The false alarms the fingerprints are for: a rewrite with a later time
        // and the same edit - a scrub, a reopen, the stamp a millisecond early.
        check(!DraftHousekeeping.editedSinceExport(11L, 10L, same, same), "a later save of the same edit counted as edited since")
        check(!DraftHousekeeping.editedSinceExport(99_999L, 10L, same, same), "a much later save of the same edit counted as edited since")
        check(DraftHousekeeping.editedSinceExport(11L, 10L, other, same), "a different edit did not count as edited since")
        // Content decides, even where the clock disagrees: an edit made while
        // the render ran is saved before the stamp lands.
        check(DraftHousekeeping.editedSinceExport(9L, 10L, other, same), "an edit saved before the stamp was missed")

        // Stamped before fingerprints existed: the times are all there is.
        check(!DraftHousekeeping.editedSinceExport(10L, 10L), "the export's own flush counted as an edit")
        check(!DraftHousekeeping.editedSinceExport(9L, 10L), "an older save counted as edited since")
        check(DraftHousekeeping.editedSinceExport(11L, 10L), "a later save did not count as edited since")
        check(DraftHousekeeping.editedSinceExport(11L, 10L, same, null), "one missing fingerprint did not fall back to times")
    }

    // --- Which earlier version is offered. ---------------------------------------
    run {
        val now = DraftHousekeeping.fingerprint("now")
        val old = DraftHousekeeping.Version(1L, DraftHousekeeping.fingerprint("old"))
        val older = DraftHousekeeping.Version(0L, DraftHousekeeping.fingerprint("older"))
        val sameAsNow = DraftHousekeeping.Version(2L, now)
        val unknown = DraftHousekeeping.Version(3L, null)
        check(DraftHousekeeping.earlierVersion(now, null, null) == null, "offered a version when there was none")
        check(DraftHousekeeping.earlierVersion(now, older, old) == DraftHousekeeping.Earlier.Snapshot, "the older snapshot was not preferred")
        check(DraftHousekeeping.earlierVersion(now, null, old) == DraftHousekeeping.Earlier.Pending, "the pending snapshot was not offered alone")
        check(DraftHousekeeping.earlierVersion(now, sameAsNow, old) == DraftHousekeeping.Earlier.Pending, "a snapshot identical to now was offered")
        check(DraftHousekeeping.earlierVersion(now, sameAsNow, sameAsNow) == null, "two snapshots identical to now were offered")
        check(DraftHousekeeping.earlierVersion(now, unknown, null) == DraftHousekeeping.Earlier.Snapshot, "a snapshot of unknown content was hidden")
        check(DraftHousekeeping.earlierVersion(null, sameAsNow, null) == DraftHousekeeping.Earlier.Snapshot, "an unknown current edit hid the snapshot")
    }

    val root = Files.createTempDirectory("housekeeping").toFile()
    try {
        // --- The snapshot pair, driven through an hour of saves. ----------------
        // One save a minute. The real clock stands in for nothing here: each
        // file's time is set to the simulated one, which is what the rotation
        // reads. The promise is that once the snapshot exists, what it holds was
        // saved at least the interval ago - never a second before the mistake it
        // is there to undo - and that it exists once the draft is that old.
        run {
            val dir = File(root, "rotate").apply { mkdirs() }
            val live = File(dir, "s.json")
            val snap = File(dir, "s.snap.json")
            val pending = File(dir, "s.pending.snap.json")
            val scratch = File(dir, "s.snap.tmp.json")
            val interval = DraftHousekeeping.SNAPSHOT_INTERVAL_MS
            val minute = 60_000L
            val start = 1_000_000_000L
            for (i in 0..60) {
                val now = start + i * minute
                val rotation = DraftFiles.rotateSnapshots(live, snap, pending, scratch, now)
                if (rotation.taken) pending.setLastModified(now)
                live.writeText(now.toString())          // this save's version: the time it was saved
                if (snap.exists()) {
                    val heldFrom = snap.readText().toLong()
                    check(now - heldFrom >= interval, "at minute $i the snapshot held a version only ${(now - heldFrom) / minute} min old")
                    // And it keeps moving: a snapshot that stopped at the first
                    // one ever taken would pass the line above forever.
                    check(now - heldFrom <= 2 * interval + minute, "at minute $i the snapshot was ${(now - heldFrom) / minute} min old; it stopped moving")
                }
                if (i >= 2 * interval / minute) check(snap.exists(), "no snapshot after $i minutes of saving")
                check(!scratch.exists(), "a rotation left its scratch file behind at minute $i")
            }
        }

        // --- Nothing to rotate from: no live file, nothing happens. -------------
        run {
            val dir = File(root, "empty").apply { mkdirs() }
            val r = DraftFiles.rotateSnapshots(File(dir, "x.json"), File(dir, "x.snap.json"), File(dir, "x.pending.snap.json"), File(dir, "x.tmp"), 5L)
            check(!r.promoted && !r.taken, "rotated a slot with no live file")
        }

        // --- The bin: in and out, all or nothing. -------------------------------
        run {
            val dir = File(root, "bin").apply { mkdirs() }
            val trash = File(dir, "trash").apply { mkdirs() }
            val files = listOf(File(dir, "a.json"), File(dir, "a.bak.json"), File(dir, "a.meta.json"))
            files.forEachIndexed { i, f -> f.writeText("v$i") }

            val entry = DraftFiles.moveToBin(trash, "a", files)
            check(entry != null, "a slot was not moved into the bin")
            check(files.none { it.exists() }, "the slot still had files after moving to the bin")
            check(DraftFiles.moveToBin(trash, "a", files) == null, "an empty slot produced a bin entry")

            val entryDir = File(trash, entry!!)
            check(DraftFiles.moveOutOfBin(entryDir, files), "a bin entry did not come back")
            check(files.mapIndexed { i, f -> f.readText() == "v$i" }.all { it }, "the restored files were not the ones binned")
            check(!entryDir.exists(), "a restored bin entry was left behind")

            // Out of the bin, half-way: the second file's place is taken by a
            // directory, so its rename is refused. The first must go back in.
            val again = File(trash, DraftFiles.moveToBin(trash, "a", files)!!)
            File(dir, "a.bak.json").apply { mkdirs(); File(this, "occupant").writeText("x") }
            check(!DraftFiles.moveOutOfBin(again, files), "a refused restore reported success")
            check(!File(dir, "a.json").exists(), "a refused restore left the first file in the slot")
            check(File(again, "a.json").readText() == "v0", "a refused restore lost the first file from the bin")
            check(File(again, "a.meta.json").exists(), "a refused restore lost a file it never reached")
            File(dir, "a.bak.json").deleteRecursively()

            // Into the bin when the bin cannot be made: nothing moves.
            files.forEachIndexed { i, f -> f.writeText("w$i") }
            val blocked = File(dir, "blocked").apply { writeText("a file where the bin should be") }
            check(DraftFiles.moveToBin(blocked, "a", files) == null, "a bin that could not be made reported success")
            check(files.mapIndexed { i, f -> f.readText() == "w$i" }.all { it }, "a refused move into the bin disturbed the slot")
        }

        // --- An atomic write leaves the old file whole when it cannot finish. ---
        run {
            val dir = File(root, "atomic").apply { mkdirs() }
            val target = File(dir, "t.json").apply { writeText("old") }
            DraftFiles.writeAtomically(File(dir, "t.tmp"), target, "new".toByteArray())
            check(target.readText() == "new", "an atomic write did not land")
            val refused = runCatching {
                DraftFiles.writeAtomically(File(File(dir, "missing"), "t.tmp"), target, "newer".toByteArray())
            }.isFailure
            check(refused, "a write into a missing folder reported success")
            check(target.readText() == "new", "a failed atomic write touched the target")
        }
    } finally {
        root.deleteRecursively()
    }

    if (problems.isNotEmpty()) {
        problems.forEach { println("FAIL - $it") }
        exitProcess(1)
    }
    println("PASS - bin names, the thirty-day purge, the snapshot pair, fingerprints, and all-or-nothing moves behave")
}
