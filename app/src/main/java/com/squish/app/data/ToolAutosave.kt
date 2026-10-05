package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.editor.OutputSize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * A one-job tool's session, on disk.
 *
 * The editor has kept its timeline on disk for a while; the quick tools never
 * did, and nobody thinks of a half-set-up merge as less of their work than a
 * half-cut timeline. Six videos picked, ordered and about to be joined is ten
 * minutes of choosing, and a phone call was enough to lose all of it - Android
 * kills a backgrounded video app long before the call ends.
 *
 * Only the choices are saved, never the media: which tool, which files in which
 * order, and where the handles are. Everything measured off those files - their
 * lengths, their shapes, their weights - is probed again on the way back in, so a
 * draft can never disagree with a file that changed underneath it.
 *
 * Written the same way the editor's is: scratch file, flushed to the platter,
 * renamed over the live one. rename(2) is atomic, so what is on disk is always
 * either the last complete session or this one, never half of each. And like the
 * editor's, it keeps a backup and two snapshots that fall behind on purpose, and
 * nothing here is ever deleted outright: a discarded session goes into a bin for
 * a month.
 */
data class ToolDraft(
    /**
     * Which file this session lives in. One per session, not one per tool: with
     * a single slot per tool, starting a second merge from the dashboard wrote
     * over the six-clip one waiting under Unfinished the moment anything moved.
     */
    val slot: String,
    val toolId: String,
    val title: String,
    /** In playing order. One entry for the single-source tools, several for a merge. */
    val uris: List<Uri>,
    val durationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    /** The export's short edge; see [com.squish.app.editor.OutputSize]. */
    val outputP: Int,
    val fitToSize: Boolean,
    val targetSizeMb: Int,
    val savedAtMillis: Long,
    /** When this session last produced a file, or null if it never has. */
    val exportedAtMillis: Long? = null,
    /** Which session that file was made from; see [com.squish.app.data.DraftHousekeeping.editedSinceExport]. */
    val exportedFingerprint: String? = null
)

class ToolAutosave(context: Context) {

    private val dir = File(context.filesDir, "tooldrafts").apply { mkdirs() }
    private val trashDir = File(dir, "trash").apply { mkdirs() }

    private fun liveFile(slot: String) = File(dir, "$slot.json")
    private fun backupFile(slot: String) = File(dir, "$slot.bak.json")
    private fun snapshotFile(slot: String) = File(dir, "$slot.snap.json")
    private fun pendingSnapshotFile(slot: String) = File(dir, "$slot.pending.snap.json")
    private fun scratchFile(slot: String) = File(dir, "$slot.tmp.json")
    private fun snapshotScratchFile(slot: String) = File(dir, "$slot.snap.tmp.json")

    private fun slotFiles(slot: String) =
        listOf(liveFile(slot), backupFile(slot), snapshotFile(slot), pendingSnapshotFile(slot))

    /** Cheap change detector, so an idle tool screen never touches the disk. */
    private val lastSignature = HashMap<String, String>()

    /** The tool's ticker saves on IO and a flush on leaving saves on Main. */
    private val lock = Any()

    /**
     * Saves the session if anything has changed. Safe to call on a timer.
     *
     * A session with no files chosen is not a draft, it is an empty screen, and
     * writing one would put a row on the dashboard offering to resume nothing.
     */
    fun save(draft: ToolDraft): Boolean = synchronized(lock) {
        if (draft.uris.isEmpty()) return false

        // The signature is the session without its export stamp, so the tick
        // after markCompleted - which saves with the stamp - sees nothing new.
        val signature = keyOf(draft)
        if (lastSignature[draft.slot] == signature) return false

        val live = liveFile(draft.slot)
        val onDisk = read(live, draft.slot)
        // A session reopened from the list has no signature yet, so its first
        // tick would rewrite a file that already says exactly this. Skipped:
        // a rewrite is a flash write for nothing.
        if (onDisk != null && keyOf(onDisk) == signature && draft.exportedAtMillis == null) {
            lastSignature[draft.slot] = signature
            return false
        }

        val document = encode(draft)
        // The export stamp is kept from the file already there, so a save after
        // an export does not quietly un-export the session.
        if (draft.exportedAtMillis == null && onDisk?.exportedAtMillis != null) {
            document.put("exportedAtMillis", onDisk.exportedAtMillis)
            onDisk.exportedFingerprint?.let { document.put("exportedFingerprint", it) }
        }
        val now = System.currentTimeMillis()
        document.put("savedAtMillis", now)

        val ok = runCatching {
            FileOutputStream(scratchFile(draft.slot)).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            // The same pair of stale-on-purpose snapshots the editor keeps; a
            // merge rebuilt wrongly over ten minutes is as much lost work as a
            // timeline.
            DraftFiles.rotateSnapshots(
                live, snapshotFile(draft.slot), pendingSnapshotFile(draft.slot), snapshotScratchFile(draft.slot), now
            )
            if (live.exists()) live.copyTo(backupFile(draft.slot), overwrite = true)
            DraftFiles.replace(scratchFile(draft.slot), live)
        }.isSuccess

        if (ok) lastSignature[draft.slot] = signature
        ok
    }

    /** The session in this slot, if there is one; the backup and then the snapshots stand in for a bad file. */
    fun peek(slot: String): ToolDraft? = synchronized(lock) {
        read(liveFile(slot), slot) ?: read(backupFile(slot), slot)
            ?: read(pendingSnapshotFile(slot), slot) ?: read(snapshotFile(slot), slot)
    }

    /** Everything that makes this session this session, and nothing about when. */
    fun keyOf(draft: ToolDraft): String = encode(draft.copy(exportedAtMillis = null, exportedFingerprint = null)).toString()

    /** [keyOf], shortened for keeping. */
    fun fingerprintOf(draft: ToolDraft): String = DraftHousekeeping.fingerprint(keyOf(draft))

    /** Moves a session into the bin; the bin entry's name, or null if nothing was on disk or it could not be moved. */
    fun delete(slot: String): String? = synchronized(lock) {
        lastSignature.remove(slot)
        scratchFile(slot).delete()
        snapshotScratchFile(slot).delete()
        DraftFiles.moveToBin(trashDir, slot, slotFiles(slot))
    }

    /**
     * Takes a session off disk without binning it: the file the tool screen
     * wrote a moment ago and has since taken back, by undoing its own change.
     *
     * [delete] is the person's Delete, and it is right that it goes to the bin
     * for a month. This is not that: the tool screen saves on a tick, and a
     * choice made and then unmade retracts the file it wrote. Through [delete]
     * that showed up on the drafts screen as "Recently deleted", for a session
     * nobody had deleted - and taking it back out of the bin restored a draft
     * that was never meant to exist.
     */
    fun retract(slot: String) = synchronized(lock) {
        lastSignature.remove(slot)
        scratchFile(slot).delete()
        snapshotScratchFile(slot).delete()
        slotFiles(slot).forEach { it.delete() }
    }

    /**
     * Stamps the session as exported: when, and which session - [exported] is
     * the one the file was made from. It stays, so "back to the tool" can carry
     * on with it.
     *
     * The save time and the stamp are the same instant, and the fingerprint is
     * what "edited since" is decided by, so neither the millisecond between
     * them nor a later rewrite of the same session can make an untouched
     * export look changed.
     */
    fun markCompleted(slot: String, exported: ToolDraft) {
        synchronized(lock) {
            val current = read(liveFile(slot), slot) ?: return
            val stamped = current.copy(
                exportedAtMillis = System.currentTimeMillis(),
                exportedFingerprint = fingerprintOf(exported)
            )
            // Through the same path as any save, but bypassing the change detector:
            // the stamp is the change.
            lastSignature.remove(slot)
            save(stamped)
        }
    }

    /** Every unfinished tool session, newest first. */
    fun drafts(): List<ToolDraft> = synchronized(lock) {
        runCatching {
            val files: Array<File> = dir.listFiles() ?: return@runCatching emptyList()
            files.filter { it.isFile && it.name.endsWith(".json") }
                .filter { !it.name.endsWith(".tmp.json") && !it.name.endsWith(".bak.json") && !it.name.endsWith(".snap.json") }
                .mapNotNull { read(it, it.name.removeSuffix(".json")) }
                .sortedByDescending { it.savedAtMillis }
        }.getOrDefault(emptyList())
    }

    /**
     * When the earlier version of this session that [revertToEarlier] would put
     * back was saved, or null when there is none that differs from it.
     */
    fun earlierSavedAt(draft: ToolDraft): Long? = synchronized(lock) { earlierOf(draft)?.savedAtMillis }

    private fun earlierOf(draft: ToolDraft): ToolDraft? {
        val snapshot = read(snapshotFile(draft.slot), draft.slot)
        val pending = read(pendingSnapshotFile(draft.slot), draft.slot)
        return when (
            DraftHousekeeping.earlierVersion(
                fingerprintOf(draft),
                snapshot?.let { DraftHousekeeping.Version(it.savedAtMillis, fingerprintOf(it)) },
                pending?.let { DraftHousekeeping.Version(it.savedAtMillis, fingerprintOf(it)) }
            )
        ) {
            DraftHousekeeping.Earlier.Snapshot -> snapshot
            DraftHousekeeping.Earlier.Pending -> pending
            null -> null
        }
    }

    /**
     * Puts the session's earlier version back. The version it replaces goes
     * into the bin whole, so this is undone by restoring that entry; its name is
     * returned, or null when nothing changed.
     */
    fun revertToEarlier(slot: String): String? = synchronized(lock) {
        val current = read(liveFile(slot), slot) ?: return null
        val earlier = earlierOf(current) ?: return null
        val text = runCatching {
            encode(earlier).apply { put("savedAtMillis", earlier.savedAtMillis) }.toString()
        }.getOrNull() ?: return null
        val binned = delete(slot) ?: return null
        val placed = runCatching { DraftFiles.writeAtomically(scratchFile(slot), liveFile(slot), text.toByteArray()) }.isSuccess
        if (placed) return binned
        slotFiles(slot).forEach { it.delete() }
        restore(binned)
        null
    }

    /** Everything in the bin, newest first, with anything past its month gone. */
    fun trashed(): List<Pair<String, ToolDraft>> = synchronized(lock) {
        runCatching {
            val now = System.currentTimeMillis()
            val entries: Array<File> = trashDir.listFiles() ?: return@runCatching emptyList()
            entries.filter { it.isDirectory }.mapNotNull { entry ->
                val (slot, at) = DraftHousekeeping.parseTrashName(entry.name) ?: return@mapNotNull null
                if (DraftHousekeeping.isExpired(at, now)) {
                    entry.deleteRecursively()
                    return@mapNotNull null
                }
                val draft = read(File(entry, liveFile(slot).name), slot) ?: read(File(entry, backupFile(slot).name), slot)
                    ?: return@mapNotNull null
                entry.name to draft
            }.sortedByDescending { (name, _) -> DraftHousekeeping.parseTrashName(name)?.second ?: 0L }
        }.getOrDefault(emptyList())
    }

    /**
     * Puts a bin entry back. A newer session in the same slot goes into the bin
     * in its place, and if it cannot be moved, nothing is restored - a rename
     * over it would have destroyed it with no copy left.
     */
    fun restore(trashId: String): Boolean = synchronized(lock) {
        val entry = File(trashDir, trashId)
        if (!entry.isDirectory) return false
        val (slot, _) = DraftHousekeeping.parseTrashName(trashId) ?: return false
        if (slotFiles(slot).any { it.exists() } && delete(slot) == null) return false
        lastSignature.remove(slot)
        DraftFiles.moveOutOfBin(entry, slotFiles(slot))
    }

    /** Removes a bin entry for good. Only ever from a confirmed tap on the list. */
    fun purge(trashId: String) {
        synchronized(lock) {
            val entry = File(trashDir, trashId)
            if (DraftHousekeeping.parseTrashName(trashId) != null && entry.isDirectory) entry.deleteRecursively()
        }
    }

    /**
     * The slot is handed in rather than read off the file's name: a snapshot or
     * a backup is named after its slot but is not called "<slot>.json", and a
     * session written before slots existed - "stitch.json" - still resumes as
     * itself because its slot is its name.
     */
    private fun read(file: File, slot: String): ToolDraft? {
        if (!file.exists()) return null
        return runCatching { decode(slot, JSONObject(file.readText())) }.getOrNull()
    }

    private fun encode(draft: ToolDraft): JSONObject = JSONObject().apply {
        put("toolId", draft.toolId)
        put("title", draft.title)
        put("uris", JSONArray().apply { draft.uris.forEach { put(it.toString()) } })
        put("durationMs", draft.durationMs)
        put("trimStartMs", draft.trimStartMs)
        put("trimEndMs", draft.trimEndMs)
        put("outputP", draft.outputP)
        put("fitToSize", draft.fitToSize)
        put("targetSizeMb", draft.targetSizeMb)
        draft.exportedAtMillis?.let { put("exportedAtMillis", it) }
        draft.exportedFingerprint?.let { put("exportedFingerprint", it) }
    }

    private fun decode(slot: String, json: JSONObject): ToolDraft? {
        val toolId = json.optString("toolId").takeIf { it.isNotBlank() } ?: return null
        val array = json.optJSONArray("uris") ?: return null
        val uris = (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null

        return ToolDraft(
            slot = slot,
            toolId = toolId,
            title = json.optString("title", "Unfinished"),
            uris = uris,
            durationMs = json.optLong("durationMs"),
            trimStartMs = json.optLong("trimStartMs"),
            trimEndMs = json.optLong("trimEndMs"),
            outputP = if (json.has("outputP")) json.optInt("outputP")
            else OutputSize.fromLegacyQuality(json.optString("quality")) ?: 720,
            fitToSize = json.optBoolean("fitToSize"),
            targetSizeMb = json.optInt("targetSizeMb", 16),
            savedAtMillis = json.optLong("savedAtMillis"),
            exportedAtMillis = json.optLong("exportedAtMillis", 0L).takeIf { it > 0L },
            exportedFingerprint = json.optString("exportedFingerprint").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        /** A slot for a session nobody has started yet: the tool, and a name that will not repeat. */
        fun freshSlot(toolId: String): String = "$toolId-${UUID.randomUUID()}"
    }
}
