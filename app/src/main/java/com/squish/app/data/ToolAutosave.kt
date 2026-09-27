package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.editor.OutputSize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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
 * either the last complete session or this one, never half of each.
 */
data class ToolDraft(
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
    val savedAtMillis: Long
)

class ToolAutosave(context: Context) {

    private val dir = File(context.filesDir, "tooldrafts").apply { mkdirs() }

    private fun liveFile(toolId: String) = File(dir, "$toolId.json")
    private fun scratchFile(toolId: String) = File(dir, "$toolId.tmp.json")

    /** Cheap change detector, so an idle tool screen never touches the disk. */
    private val lastSignature = HashMap<String, String>()

    /**
     * Saves the session if anything has changed. Safe to call on a timer.
     *
     * A session with no files chosen is not a draft, it is an empty screen, and
     * writing one would put a row on the dashboard offering to resume nothing.
     */
    fun save(draft: ToolDraft): Boolean {
        if (draft.uris.isEmpty()) return false

        val document = encode(draft)
        val signature = document.toString()
        if (lastSignature[draft.toolId] == signature) return false

        document.put("savedAtMillis", System.currentTimeMillis())

        val live = liveFile(draft.toolId)
        val scratch = scratchFile(draft.toolId)
        val ok = runCatching {
            FileOutputStream(scratch).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            check(scratch.renameTo(live)) { "atomic rename refused" }
        }.isSuccess

        if (ok) lastSignature[draft.toolId] = signature
        return ok
    }

    fun peek(toolId: String): ToolDraft? = read(liveFile(toolId))

    /** Everything that makes this session this session, and nothing about when. */
    fun keyOf(draft: ToolDraft): String = encode(draft).toString()

    fun clear(toolId: String) {
        lastSignature.remove(toolId)
        listOf(liveFile(toolId), scratchFile(toolId)).forEach { runCatching { it.delete() } }
    }

    /** Every unfinished tool session, newest first. */
    fun drafts(): List<ToolDraft> = runCatching {
        val files: Array<File> = dir.listFiles() ?: return@runCatching emptyList()
        files.filter { it.name.endsWith(".json") && !it.name.endsWith(".tmp.json") }
            .mapNotNull { read(it) }
            .sortedByDescending { it.savedAtMillis }
    }.getOrDefault(emptyList())

    private fun read(file: File): ToolDraft? {
        if (!file.exists()) return null
        return runCatching { decode(JSONObject(file.readText())) }.getOrNull()
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
    }

    private fun decode(json: JSONObject): ToolDraft? {
        val toolId = json.optString("toolId").takeIf { it.isNotBlank() } ?: return null
        val array = json.optJSONArray("uris") ?: return null
        val uris = (0 until array.length())
            .mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null

        return ToolDraft(
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
            savedAtMillis = json.optLong("savedAtMillis")
        )
    }
}
