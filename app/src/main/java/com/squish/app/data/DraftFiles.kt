package com.squish.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The file moves both draft stores make, written once.
 *
 * The editor's drafts and the quick tools' sessions are different documents
 * with the same safety needs - an atomic write, snapshots that fall behind on
 * purpose, a bin a slot can be moved into and back out of - and the two copies
 * of that code had already drifted: one had snapshots and the other did not,
 * and both could lose a draft on a restore whose first step failed.
 */
internal object DraftFiles {

    /**
     * Scratch file, flushed to the platter, renamed over the target. rename(2)
     * is atomic, so the target is always either its old complete self or the
     * new complete text. Throws when any step fails; the target is untouched.
     */
    fun writeAtomically(scratch: File, target: File, bytes: ByteArray) {
        FileOutputStream(scratch).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()          // on the platter, not just in the page cache
        }
        replace(scratch, target)
    }

    /**
     * rename(2), replacing the target in one step. File.renameTo leaves whether
     * an existing target is replaced up to the platform, and on some it is not.
     * Throws when refused.
     */
    fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * A copy made the same way. A plain copyTo truncates the target first, so
     * a kill part-way left the snapshot - the last file anything falls back to -
     * half-written.
     */
    fun copyAtomically(from: File, scratch: File, target: File) =
        writeAtomically(scratch, target, from.readBytes())

    /** What a snapshot rotation did, so the caller can describe the snapshots it now has. */
    data class Rotation(val promoted: Boolean, val taken: Boolean)

    /**
     * Moves the snapshots along if the pending one has had its interval: the
     * pending snapshot becomes the snapshot, and a new pending one is taken
     * from the live file that is about to be overwritten. See
     * [DraftHousekeeping.SNAPSHOT_INTERVAL_MS] for why there are two.
     *
     * Never throws. A snapshot is a spare; failing to refresh one must not stop
     * the save it rides on.
     */
    fun rotateSnapshots(live: File, snapshot: File, pending: File, scratch: File, nowMillis: Long): Rotation {
        if (!live.exists()) return Rotation(promoted = false, taken = false)
        val pendingAt = pending.takeIf { it.exists() }?.lastModified()
        if (!DraftHousekeeping.snapshotDue(pendingAt, nowMillis)) return Rotation(promoted = false, taken = false)
        // A rename keeps the file's time, which is when it was taken - the age
        // the next rotation measures.
        val promoted = pending.exists() && runCatching { replace(pending, snapshot) }.isSuccess
        val taken = runCatching { copyAtomically(live, scratch, pending) }.isSuccess
        if (!taken) scratch.delete()
        return Rotation(promoted, taken)
    }

    /**
     * Moves whichever of [files] exist into a new bin entry for [slot] and
     * returns the entry's name; null when there was nothing to move or the move
     * failed, in which case everything is put back where it was.
     */
    fun moveToBin(trashDir: File, slot: String, files: List<File>): String? {
        val present = files.filter { it.exists() }
        if (present.isEmpty()) return null
        // A name of its own, even if the same slot was binned this millisecond -
        // a restore that finds a newer draft in the slot bins that one first.
        var at = System.currentTimeMillis()
        while (File(trashDir, DraftHousekeeping.trashName(slot, at)).exists()) at += 1
        val name = DraftHousekeeping.trashName(slot, at)
        val target = File(trashDir, name)
        return runCatching {
            check(target.mkdirs()) { "bin folder refused" }
            present.forEach { file -> check(file.renameTo(File(target, file.name))) { "move into bin refused" } }
            name
        }.getOrElse {
            // Half-moved is worse than not moved: put back whatever went across.
            present.forEach { file -> File(target, file.name).takeIf { it.exists() }?.renameTo(file) }
            target.delete()
            null
        }
    }

    /**
     * Moves a bin entry's files back over [files], which the caller has already
     * emptied. All or nothing: if any move is refused, the ones already made are
     * moved back into the entry, so a failed restore leaves the draft whole in
     * the bin rather than split between the bin and the slot.
     */
    fun moveOutOfBin(entry: File, files: List<File>): Boolean {
        val moved = mutableListOf<File>()
        val ok = runCatching {
            files.forEach { file ->
                val kept = File(entry, file.name)
                if (kept.exists()) {
                    // Never over something: a rename replaces its target without a word.
                    check(!file.exists() && kept.renameTo(file)) { "move out of bin refused" }
                    moved += file
                }
            }
        }.isSuccess
        if (!ok) {
            moved.forEach { file -> file.renameTo(File(entry, file.name)) }
            return false
        }
        entry.deleteRecursively()
        return true
    }
}
