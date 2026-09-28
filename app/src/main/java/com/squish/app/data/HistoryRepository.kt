package com.squish.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Every export Squish ever produced, persisted as a small JSON file under the
 * app's private storage. No cloud, no server, no third-party SDK - matches the
 * "everything happens on your device" promise on the Settings screen.
 *
 * The list in memory changes at once; the file is written behind it, on a
 * thread of its own. Callers are view models on the main thread, and the write
 * used to happen there, right before the export screen opened - the one moment
 * someone is watching for it to appear.
 *
 * Except that a new export waits for its write. Its record is the only thing
 * that can reach its file - exports/ is private, and nothing else lists it - so
 * a record queued and then lost to a kill in the next second left a video of
 * possibly gigabytes on the phone that the app could neither show nor delete.
 */
class HistoryRepository(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _records = MutableStateFlow(loadFromDisk())
    val records: StateFlow<List<ExportRecord>> = _records

    /**
     * One writer, in order. Two writes racing on a pool could land the older
     * list last; on a single thread each write is the list as it stood when it
     * was queued, and the last one queued wins.
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "squish-history").apply { isDaemon = true }
    }

    /** Adds [record] and returns once it is on disk, or the write has failed. */
    suspend fun add(record: ExportRecord) {
        val updated = listOf(record) + _records.value
        _records.value = updated
        val written = persist(updated)
        withContext(Dispatchers.IO) { runCatching { written.get() } }
    }

    /**
     * Forgets an export, and deletes the file it points at.
     *
     * Both, because either on its own is a lie. Exports live in the app's own
     * external directory, which nothing but this list can reach - dropping the
     * record and leaving the file behind would tell the user the video is gone
     * while it quietly went on occupying a gigabyte of their phone, invisibly and
     * forever. Deleting the file and keeping the record would leave a row that
     * opens nothing.
     *
     * A copy the user separately saved to their gallery is theirs and is not
     * touched: it lives in MediaStore under Movies/Squish, this app does not own
     * it, and nobody expects clearing a list inside an app to reach out into their
     * camera roll. The confirmation says so before any of this happens.
     */
    fun delete(id: String) {
        val record = _records.value.firstOrNull { it.id == id }
        val updated = _records.value.filterNot { it.id == id }
        _records.value = updated
        persist(updated)
        record?.let { writer.execute { runCatching { File(it.outputPath).delete() } } }
    }

    /** The record for an export's file, if it is one of these. */
    fun forPath(outputPath: String): ExportRecord? = _records.value.firstOrNull { it.outputPath == outputPath }

    private fun loadFromDisk(): List<ExportRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ExportRecord(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    outputPath = obj.getString("outputPath"),
                    originalSizeBytes = obj.getLong("originalSizeBytes"),
                    outputSizeBytes = obj.getLong("outputSizeBytes"),
                    durationMs = obj.getLong("durationMs"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height"),
                    createdAtMillis = obj.getLong("createdAtMillis"),
                    savedToGallery = if (obj.has("savedToGallery")) obj.getBoolean("savedToGallery") else null,
                    galleryUri = obj.optString("galleryUri").takeIf { it.isNotEmpty() }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(records: List<ExportRecord>): Future<*> = writer.submit { write(records) }

    private fun write(records: List<ExportRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("outputPath", r.outputPath)
                    put("originalSizeBytes", r.originalSizeBytes)
                    put("outputSizeBytes", r.outputSizeBytes)
                    put("durationMs", r.durationMs)
                    put("width", r.width)
                    put("height", r.height)
                    put("createdAtMillis", r.createdAtMillis)
                    r.savedToGallery?.let { put("savedToGallery", it) }
                    r.galleryUri?.let { put("galleryUri", it) }
                }
            )
        }
        // Whole or not at all: a kill mid-write used to leave half a JSON array,
        // which reads back as an empty history.
        runCatching {
            val scratch = File(file.absolutePath + ".tmp")
            scratch.writeText(array.toString())
            if (!scratch.renameTo(file)) {
                file.writeText(array.toString())
                scratch.delete()
            }
        }
    }
}
