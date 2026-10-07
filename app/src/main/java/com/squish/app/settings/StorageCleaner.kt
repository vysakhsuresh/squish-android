package com.squish.app.settings

import android.content.Context
import com.squish.app.data.SquishRepositories
import com.squish.app.media.IMPORTS_DIR
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.video.Segmenter
import com.squish.app.online.OnlineMusic
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
enum class StorageKind(
    val title: String,
    val blurb: String,
    /**
     * Whether a Clear of this kind leaves behind what a draft still names. For
     * these the card has to say what it kept and why; the caches go whole.
     */
    val keepsReferenced: Boolean = false
) {
    Exports("Exports kept inside Squish", "Renders whose copy to the gallery did not land. The library plays these; clearing removes them from it."),
    Stills("Photos and freezes", "Clips rendered from pictures. Only ones no project uses are cleared.", keepsReferenced = true),
    Renders(
        "Reversed renders, imports and downloads",
        "Files made for Reverse, videos shared into Squish, and the stock clips and music it downloaded. " +
            "Only ones no project uses are cleared.",
        keepsReferenced = true
    ),
    Takes("Voice and speech", "Voiceover takes and lines read aloud. Only ones no project uses are cleared.", keepsReferenced = true),
    Masks("Person masks", "What Cutout found in each shot. Only ones no project uses are cleared.", keepsReferenced = true),
    Proxies("Preview cache", "Light 540p copies of large clips, kept only to keep scrubbing smooth. Exports always read the original."),
    Thumbnails("Thumbnails", "The pictures on the dashboard and in the library, made again as needed.")
}

data class StorageEntry(val kind: StorageKind, val bytes: Long)

/**
 * What one Clear did: the new measurements, and - for a kind that keeps what
 * drafts name - how much went, how much stayed, and how many of the projects
 * holding it are in the bin.
 */
data class ClearResult(
    val entries: List<StorageEntry>,
    val kind: StorageKind,
    val freedBytes: Long,
    val keptBytes: Long,
    val binnedProjects: Int
)

object StorageCleaner {

    suspend fun measure(context: Context): List<StorageEntry> = withContext(Dispatchers.IO) {
        StorageKind.entries.map { kind -> StorageEntry(kind, bytesOf(context, kind)) }
    }

    /** Clears one kind and returns what every kind measures afterwards, and what it did. */
    suspend fun clear(context: Context, kind: StorageKind): ClearResult = withContext(Dispatchers.IO) {
        val before = bytesOf(context, kind)
        when (kind) {
            StorageKind.Exports -> clearExports(context)
            // Into the backdrops too, because the row *counts* them: it measures
            // stills/ whole, and swept only at the top level the number could
            // not be cleared. They are not named by any draft - each is derived
            // from a shot's file and the frame's shape - so every one goes, and
            // the one that is wanted again is made again from the footage, which
            // is what CanvasBackdrop does anyway.
            StorageKind.Stills -> sweep(context, listOf(File(context.filesDir, "stills")))
            StorageKind.Renders -> sweep(context, renderDirs(context))
            StorageKind.Takes -> sweep(context, listOf(File(context.filesDir, "voice"), File(context.filesDir, "speech")))
            StorageKind.Masks -> Segmenter.sweep(context, SquishRepositories.autosave(context).referencedMaskFiles())
            StorageKind.Proxies -> ProxyEngine.clearCache(context)
            StorageKind.Thumbnails -> ThumbnailCache.clearDisk(context)
        }
        val entries = StorageKind.entries.map { k -> StorageEntry(k, bytesOf(context, k)) }
        val after = entries.first { it.kind == kind }.bytes
        ClearResult(
            entries = entries,
            kind = kind,
            freedBytes = (before - after).coerceAtLeast(0L),
            keptBytes = after,
            // Counted only where it explains something: a bin entry's draft
            // names its files like any other, which is what Restore leans on.
            binnedProjects = if (kind.keepsReferenced && after > 0L) {
                runCatching { SquishRepositories.autosave(context).trashed().size }.getOrDefault(0)
            } else 0
        )
    }

    private fun bytesOf(context: Context, kind: StorageKind): Long = when (kind) {
        StorageKind.Exports -> sizeOf(SquishError.exportsDir(context))
        StorageKind.Stills -> sizeOf(File(context.filesDir, "stills"))
        StorageKind.Renders -> renderDirs(context).sumOf { sizeOf(it) }
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

    /**
     * One list, read by both the measuring and the sweeping, so a folder
     * cannot be counted by a row and left out of its Clear.
     *
     * `music/online` is here because it had been left out of *both*: a track
     * downloaded from the Archive sat in `files/music/online` which no kind
     * measured and no sweep touched, so there was no way to get rid of it from
     * inside the app at all. It is a download like the stock clips, which land
     * under `imports`, so it is counted and cleared with them.
     */
    private fun renderDirs(context: Context): List<File> = listOf(
        File(context.filesDir, "reversed"),
        File(context.filesDir, IMPORTS_DIR),
        File(context.filesDir, OnlineMusic.DIR)
    )

    private fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Deletes the files under [dirs], and under anything inside them, that no
     * draft names.
     *
     * Always the whole tree, because [sizeOf] measures the whole tree: a row
     * that counts a file the sweep cannot reach is a number Clear takes to
     * nothing on screen and leaves on the disk. It has now happened twice. The
     * stills were swept at the top level only, so a picture chosen as a
     * background wrote a multi-megabyte file nothing could take; and the
     * renders row counted `files/imports` whole while sweeping only its top,
     * so every downloaded stock clip under `files/imports/stock/` was counted
     * and unreachable. There is no directory here whose contents the rows do
     * not count, so there is none the sweep should stop at.
     */
    private fun sweep(context: Context, dirs: List<File>) {
        val named = SquishRepositories.autosave(context).referencedUris()
            .mapNotNull { StorageRules.pathOf(it) }
            .toSet() +
            SquishRepositories.toolAutosave(context).drafts().flatMap { d -> d.uris.mapNotNull { StorageRules.pathOf(it.toString()) } }
        dirs.forEach { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            StorageRules.unreferenced(files.map { it.absolutePath }, named).forEach { runCatching { File(it).delete() } }
        }
    }
}
