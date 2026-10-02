package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.media.GallerySaver
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
 * The list in memory changes at once; the file is read and written behind it,
 * on a thread of its own. Callers are view models on the main thread, and the
 * write used to happen there, right before the export screen opened - the one
 * moment someone is watching for it to appear - and the read on whichever
 * thread first touched the list, which on a cold start was the dashboard's
 * first frame (S8).
 *
 * Except that a new export waits for its write. Its record is the only thing
 * that can reach its file - exports/ is private, and nothing else lists it - so
 * a record queued and then lost to a kill in the next second left a video of
 * possibly gigabytes on the phone that the app could neither show nor delete.
 */
class HistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "history.json")
    private val _records = MutableStateFlow<List<ExportRecord>>(emptyList())
    val records: StateFlow<List<ExportRecord>> = _records

    /**
     * One writer, in order. Two writes racing on a pool could land the older
     * list last; on a single thread each write is the list as it stood when it
     * was queued, and the last one queued wins. The read goes first on the
     * same thread, so every change queued after it sees the loaded list.
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "squish-history").apply { isDaemon = true }
    }

    init {
        writer.execute { _records.value = loadFromDisk() }
    }

    /** Adds [record] and returns once it is on disk, or the write has failed. */
    suspend fun add(record: ExportRecord) {
        val written = writer.submit {
            val updated = listOf(record) + _records.value
            _records.value = updated
            write(updated)
        }
        withContext(Dispatchers.IO) { runCatching { written.get() } }
    }

    /**
     * Forgets an export, and deletes the file it points at - the gallery copy
     * when that is the one the record keeps, the private file otherwise.
     *
     * Both, because either on its own is a lie. An export is stored once, and
     * the one copy is the app's own: dropping the record and leaving the file
     * behind would tell the user the video is gone while it quietly went on
     * occupying a gigabyte of their phone. Deleting the file and keeping the
     * record would leave a row that opens nothing. The confirmation says the
     * gallery copy goes too before any of this happens. A gallery row this
     * install did not make (a reinstall) cannot be deleted without asking, and
     * is left; the record goes anyway.
     */
    fun delete(id: String) {
        writer.execute {
            val record = _records.value.firstOrNull { it.id == id }
            val updated = _records.value.filterNot { it.id == id }
            _records.value = updated
            write(updated)
            record?.let {
                runCatching { File(it.outputPath).delete() }
                it.galleryUri?.let { uri -> GallerySaver.remove(appContext, Uri.parse(uri)) }
            }
        }
    }

    /**
     * Drops the records for files that are already gone - Settings' storage
     * card clearing the private exports - without touching any file: the
     * record's own delete would also remove a gallery copy, and these have
     * none. A row that opens nothing is what this prevents.
     */
    /**
     * Forgets the exports whose file was deleted from the gallery: the row
     * opened nothing and was counted on the dashboard for good. Only when the
     * gallery row itself is gone - a refused read (a reinstall's files) proves
     * nothing, and those stay. Touches no file. Off the main thread.
     */
    suspend fun forgetDeleted() {
        val gone = withContext(Dispatchers.IO) {
            _records.value.filter { record ->
                val gallery = record.galleryUri
                if (gallery == null) !File(record.outputPath).exists()
                else runCatching {
                    appContext.contentResolver.query(Uri.parse(gallery), arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                        ?.use { it.count == 0 } ?: false
                }.getOrDefault(false)
            }.map { it.id }
        }
        forget(gone)
    }

    fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val gone = ids.toSet()
        writer.execute {
            val updated = _records.value.filterNot { it.id in gone }
            _records.value = updated
            write(updated)
        }
    }

    /** The record for an export's file, if it is one of these. */
    fun forPath(outputPath: String): ExportRecord? = _records.value.firstOrNull { it.outputPath == outputPath }

    /** The record by its id. */
    fun byId(id: String): ExportRecord? = _records.value.firstOrNull { it.id == id }

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
                    galleryUri = obj.optString("galleryUri").takeIf { it.isNotEmpty() },
                    named = obj.optBoolean("named", false)
                )
            }
        }.getOrDefault(emptyList())
    }

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
                    if (r.named) put("named", true)
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
