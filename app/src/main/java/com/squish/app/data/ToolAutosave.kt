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
 * editor's, nothing here is ever deleted outright: a discarded session goes into
 * a bin for a month.
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
    val exportedAtMillis: Long? = null
)

class ToolAutosave(context: Context) {

    private val dir = File(context.filesDir, "tooldrafts").apply { mkdirs() }
    private val trashDir = File(dir, "trash").apply { mkdirs() }

    private fun liveFile(slot: String) = File(dir, "$slot.json")
    private fun backupFile(slot: String) = File(dir, "$slot.bak.json")
    private fun scratchFile(slot: String) = File(dir, "$slot.tmp.json")

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

        val document = encode(draft)
        // The signature is the session without its export stamp, so the tick
        // after markCompleted - which saves with the stamp - sees nothing new.
        // With the stamp in it, that tick rewrote the file with a later save
        // time and the list read "edited since" the moment the export ended.
        val signature = keyOf(draft)
        if (lastSignature[draft.slot] == signature) return false

        // The export stamp is kept from the file already there, so a save after
        // an export does not quietly un-export the session. Read only once a
        // write is certain, so an idle screen still never touches the disk.
        if (draft.exportedAtMillis == null) {
            read(liveFile(draft.slot))?.exportedAtMillis?.let { document.put("exportedAtMillis", it) }
        }
        document.put("savedAtMillis", System.currentTimeMillis())

        val live = liveFile(draft.slot)
        val backup = backupFile(draft.slot)
        val scratch = scratchFile(draft.slot)
        val ok = runCatching {
            FileOutputStream(scratch).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (live.exists()) {
                backup.delete()
                live.copyTo(backup, overwrite = true)
            }
            check(scratch.renameTo(live)) { "atomic rename refused" }
        }.isSuccess

        if (ok) lastSignature[draft.slot] = signature
        ok
    }

    fun peek(slot: String): ToolDraft? = synchronized(lock) {
        read(liveFile(slot)) ?: read(backupFile(slot))
    }

    /** Everything that makes this session this session, and nothing about when. */
    fun keyOf(draft: ToolDraft): String = encode(draft).apply { remove("exportedAtMillis") }.toString()

    /** Moves a session into the bin; the bin entry's name, or null if nothing was on disk. */
    fun delete(slot: String): String? = synchronized(lock) {
        lastSignature.remove(slot)
        scratchFile(slot).delete()
        val present = listOf(liveFile(slot), backupFile(slot)).filter { it.exists() }
        if (present.isEmpty()) return null
        var at = System.currentTimeMillis()
        while (File(trashDir, DraftHousekeeping.trashName(slot, at)).exists()) at += 1
        val name = DraftHousekeeping.trashName(slot, at)
        val target = File(trashDir, name)
        runCatching {
            check(target.mkdirs()) { "bin folder refused" }
            present.forEach { file -> check(file.renameTo(File(target, file.name))) { "move into bin refused" } }
            name
        }.getOrElse {
            present.forEach { file -> File(target, file.name).takeIf { it.exists() }?.renameTo(file) }
            target.delete()
            null
        }
    }

    /** Stamps the session as exported. It stays, so "back to the tool" can carry on with it. */
    fun markCompleted(slot: String) {
        synchronized(lock) {
            val current = read(liveFile(slot)) ?: return
            val stamped = current.copy(exportedAtMillis = System.currentTimeMillis())
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
            files.filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".tmp.json") && !it.name.endsWith(".bak.json") }
                .mapNotNull { read(it) }
                .sortedByDescending { it.savedAtMillis }
        }.getOrDefault(emptyList())
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
                val draft = read(File(entry, liveFile(slot).name)) ?: read(File(entry, backupFile(slot).name))
                    ?: return@mapNotNull null
                entry.name to draft
            }.sortedByDescending { (name, _) -> DraftHousekeeping.parseTrashName(name)?.second ?: 0L }
        }.getOrDefault(emptyList())
    }

    /** Puts a bin entry back. A newer session in the same slot goes into the bin in its place. */
    fun restore(trashId: String): Boolean = synchronized(lock) {
        val entry = File(trashDir, trashId)
        if (!entry.isDirectory) return false
        val (slot, _) = DraftHousekeeping.parseTrashName(trashId) ?: return false
        if (liveFile(slot).exists()) delete(slot)
        lastSignature.remove(slot)
        val moved = runCatching {
            listOf(liveFile(slot), backupFile(slot)).forEach { file ->
                val kept = File(entry, file.name)
                if (kept.exists()) check(kept.renameTo(file)) { "move out of bin refused" }
            }
        }.isSuccess
        if (moved) entry.deleteRecursively()
        moved
    }

    /** Removes a bin entry for good. Only ever from a confirmed tap on the list. */
    fun purge(trashId: String) {
        synchronized(lock) {
            val entry = File(trashDir, trashId)
            if (DraftHousekeeping.parseTrashName(trashId) != null && entry.isDirectory) entry.deleteRecursively()
        }
    }

    private fun read(file: File): ToolDraft? {
        if (!file.exists()) return null
        // The slot is the file's name, which is what makes a session written
        // before slots existed - "stitch.json" - still resume as itself.
        val slot = file.name.removeSuffix(".bak.json").removeSuffix(".json")
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
            exportedAtMillis = json.optLong("exportedAtMillis", 0L).takeIf { it > 0L }
        )
    }

    companion object {
        /** A slot for a session nobody has started yet: the tool, and a name that will not repeat. */
        fun freshSlot(toolId: String): String = "$toolId-${UUID.randomUUID()}"
    }
}
