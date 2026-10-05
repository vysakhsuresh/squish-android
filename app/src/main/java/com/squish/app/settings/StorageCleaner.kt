package com.squish.app.settings

import android.content.Context
import com.squish.app.data.SquishRepositories
import com.squish.app.media.IMPORTS_DIR
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.video.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the app keeps on the phone, by kind, and how each is cleared.
 *
 * Settings used to measure and clear the preview cache alone, so a "phone
 * full" had no answer inside the app while a gigabyte of exports sat in a
 * folder nothing listed. Every kind is measured off the main thread; the
 * ones a draft may still lean on - stills, renders, takes, imports, person
 * masks - are cleared only of what no draft names (StorageRules), the caches
 * outright. Exports here are the private copies: since exports are stored
 * once, only a copy whose gallery publish failed is still in this folder.
 */
enum class StorageKind(val title: String, val blurb: String) {
    Exports("Exports kept inside Squish", "Renders whose copy to the gallery did not land. The library plays these; clearing removes them from it."),
    Stills("Photos and freezes", "Clips rendered from pictures. Only ones no project uses are cleared."),
    Renders("Reversed renders and imports", "Files made for Reverse, and videos shared into Squish. Only ones no project uses are cleared."),
    Takes("Voice and speech", "Voiceover takes and lines read aloud. Only ones no project uses are cleared."),
    Masks("Person masks", "What Cutout found in each shot. Only ones no project uses are cleared."),
    Proxies("Preview cache", "Light 540p copies of large clips, kept only to keep scrubbing smooth. Exports always read the original."),
    Thumbnails("Thumbnails", "The pictures on the dashboard and in the library, made again as needed.")
}

data class StorageEntry(val kind: StorageKind, val bytes: Long)

object StorageCleaner {

    suspend fun measure(context: Context): List<StorageEntry> = withContext(Dispatchers.IO) {
        StorageKind.entries.map { kind -> StorageEntry(kind, bytesOf(context, kind)) }
    }

    /** Clears one kind and returns what every kind measures afterwards. */
    suspend fun clear(context: Context, kind: StorageKind): List<StorageEntry> = withContext(Dispatchers.IO) {
        when (kind) {
            StorageKind.Exports -> clearExports(context)
            // Into the backdrops too, because the row *counts* them: it measures
            // stills/ whole, and swept only at the top level the number could
            // not be cleared. They are not named by any draft - each is derived
            // from a shot's file and the frame's shape - so every one goes, and
            // the one that is wanted again is made again from the footage, which
            // is what CanvasBackdrop does anyway.
            StorageKind.Stills -> sweep(context, listOf(File(context.filesDir, "stills")), recurse = true)
            StorageKind.Renders -> sweep(context, listOf(File(context.filesDir, "reversed"), File(context.filesDir, IMPORTS_DIR)), recurse = false)
            StorageKind.Takes -> sweep(context, listOf(File(context.filesDir, "voice"), File(context.filesDir, "speech")), recurse = false)
            StorageKind.Masks -> Segmenter.sweep(context, SquishRepositories.autosave(context).referencedMaskFiles())
            StorageKind.Proxies -> ProxyEngine.clearCache(context)
            StorageKind.Thumbnails -> ThumbnailCache.clearDisk(context)
        }
        StorageKind.entries.map { k -> StorageEntry(k, bytesOf(context, k)) }
    }

    private fun bytesOf(context: Context, kind: StorageKind): Long = when (kind) {
        StorageKind.Exports -> sizeOf(SquishError.exportsDir(context))
        StorageKind.Stills -> sizeOf(File(context.filesDir, "stills"))
        StorageKind.Renders -> sizeOf(File(context.filesDir, "reversed")) + sizeOf(File(context.filesDir, IMPORTS_DIR))
        StorageKind.Takes -> sizeOf(File(context.filesDir, "voice")) + sizeOf(File(context.filesDir, "speech"))
        StorageKind.Masks -> sizeOf(File(context.filesDir, "segments"))
        StorageKind.Proxies -> ProxyEngine.cacheSizeBytes(context)
        StorageKind.Thumbnails -> ThumbnailCache.diskBytes(context)
    }

    /**
     * The private exports go, and with them the library rows that had no
     * other copy to open - a publish that failed, or a record from before
     * exports were stored once. The card's blurb promises as much; the files
     * alone used to go, leaving rows greyed "File no longer on this phone"
     * with a dead detail screen behind each.
     */
    private fun clearExports(context: Context) {
        val dir = SquishError.exportsDir(context)
        val history = SquishRepositories.history(context)
        val stranded = history.records.value
            .filter { it.onPrivateCopy && File(it.outputPath).parentFile?.absolutePath == dir.absolutePath }
            .map { it.id }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        history.forget(stranded)
    }

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Deletes the files under [dirs] that no draft names.
     *
     * [recurse] for a tree whose whole size the card reports - the stills,
     * backdrops and all. It used to be false for those on the grounds that
     * CanvasBackdrop prunes itself, which was true only of the blurred ones:
     * a picture chosen as a background wrote a multi-megabyte file that was
     * counted by the row and swept by nothing, so Clear could not take the
     * number down. Both are pruned now and both are swept.
     */
    private fun sweep(context: Context, dirs: List<File>, recurse: Boolean) {
        val named = SquishRepositories.autosave(context).referencedUris()
            .mapNotNull { StorageRules.pathOf(it) }
            .toSet() +
            SquishRepositories.toolAutosave(context).drafts().flatMap { d -> d.uris.mapNotNull { StorageRules.pathOf(it.toString()) } }
        dirs.forEach { dir ->
            val files = (if (recurse) dir.walkTopDown().toList() else dir.listFiles()?.toList().orEmpty())
                .filter { it.isFile }
            StorageRules.unreferenced(files.map { it.absolutePath }, named).forEach { runCatching { File(it).delete() } }
        }
    }
}
