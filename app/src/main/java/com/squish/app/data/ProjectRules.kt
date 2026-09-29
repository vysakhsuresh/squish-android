package com.squish.app.data

import java.util.UUID

/**
 * The decisions about a project as a thing in a list, kept free of Android so
 * they are executed on the JVM (tools/jvm/ProjectRulesChecks.kt).
 *
 * A project is its own id, not its first video. Keyed by the video's URI, two
 * cuts of the same footage could not coexist, and reopening a clip meant a
 * banner asking which edit this was; keyed by an id handed out when the
 * project is made, the grid on the dashboard is the list of projects, each
 * with a name, a cover and a date of its own, as CapCut's is.
 */
object ProjectRules {

    /** A slot no other project has: the ids are what the files are named after. */
    fun newId(): String = "j" + UUID.randomUUID().toString().replace("-", "")

    /**
     * The name a duplicate takes: "Holiday copy", then "Holiday copy 2" when
     * that one is taken too, never "Holiday copy copy". [taken] is every name
     * already on the list.
     */
    fun copyName(original: String, taken: Collection<String>): String {
        val base = original.trim().ifBlank { "Untitled" }.removeSuffix(" copy").replace(COPY_SUFFIX, "")
        val first = "$base copy"
        if (first !in taken) return first
        var n = 2
        while ("$base copy $n" in taken) n += 1
        return "$base copy $n"
    }

    /**
     * The moment a cover frame is taken from: a little way into the first shot,
     * not its very first frame - a clip's first frame is often black or a
     * hand over the lens - and never past its end. A still is the same picture
     * throughout, so any moment of it will do.
     */
    fun coverTimeMs(sourceInMs: Long, sourceOutMs: Long): Long {
        val start = sourceInMs.coerceAtLeast(0L)
        val span = (sourceOutMs - start).coerceAtLeast(0L)
        return start + minOf(span / 3, COVER_LEAD_MS)
    }

    /**
     * Which of a purged project's files the app may let go of its right to
     * read: those no other project on disk - live or in the bin - still names.
     * Releasing a grant another project relies on would blank that one.
     */
    fun releasable(purged: Set<String>, stillNamed: Set<String>): Set<String> = purged - stillNamed

    /**
     * The files a project's own bytes are counted over: what it made itself
     * (stills, reversed renders, voice takes, spoken lines) and keeps under the
     * app's storage. The footage picked from the gallery is the phone's, not
     * the project's, so a file:// under the app is the one kind that counts.
     */
    fun ownedFile(uri: String, filesDir: String): Boolean =
        uri.startsWith("file://") && uri.removePrefix("file://").startsWith(filesDir)

    /** "0 B", "1.2 MB", "3.4 GB" - the size on a project card. */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> "%.0f KB".format(bytes / 1_000.0)
        bytes < 1_000_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.2f GB".format(bytes / 1_000_000_000.0)
    }

    /** How far into a shot a cover is taken, at most. */
    const val COVER_LEAD_MS = 1_000L

    private val COPY_SUFFIX = Regex(" copy \\d+$")
}
