package com.squish.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every export Squish ever produced, persisted as a small JSON file under the
 * app's private storage. No cloud, no server, no third-party SDK - matches the
 * "everything happens on your device" promise on the Settings screen.
 */
class HistoryRepository(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _records = MutableStateFlow(loadFromDisk())
    val records: StateFlow<List<ExportRecord>> = _records

    fun add(record: ExportRecord) {
        val updated = listOf(record) + _records.value
        _records.value = updated
        persist(updated)
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
        record?.let { runCatching { File(it.outputPath).delete() } }
    }

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
                    createdAtMillis = obj.getLong("createdAtMillis")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(records: List<ExportRecord>) {
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
                }
            )
        }
        runCatching { file.writeText(array.toString()) }
    }
}
