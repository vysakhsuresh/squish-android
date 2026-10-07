package com.squish.app.settings

/**
 * Which of the files the app made for its projects may go, kept free of
 * Android so it is executed on the JVM (tools/jvm/ProjectRulesChecks.kt).
 *
 * Settings' storage card clears by kind - stills, renders, takes - and a
 * clear that took a file a draft still names would break that draft the next
 * time it opened. So a file goes only when no draft on disk, live or binned,
 * names it; and a still's companions go with it, since a rendered still is
 * two files (the clip, and the picture it exports as, beside it with a .jpg
 * ending) that nothing names separately.
 */
object StorageRules {

    /**
     * The files under one folder that no draft names, given every URI the
     * drafts name as a set of absolute paths. A file that is a companion of a
     * named file - the same name with another ending - is kept with it. The
     * transparent gap still (clear_*.png) is the export's own and always kept.
     */
    fun unreferenced(paths: List<String>, referenced: Set<String>): List<String> {
        val stems = referenced.map { stem(it) }.toSet()
        return paths.filter { path ->
            val name = path.substringAfterLast('/')
            !name.startsWith("clear_") && path !in referenced && stem(path) !in stems
        }
    }

    /** "…/photo_3" for "…/photo_3.mp4" and "…/photo_3.jpg" alike. */
    private fun stem(path: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot > slash) path.substring(0, dot) else path
    }

    /** The absolute path a file:// URI names, or null for anything else. */
    fun pathOf(uri: String): String? =
        if (uri.startsWith("file://")) uri.removePrefix("file://").substringBefore('?').substringBefore('#') else null

    /**
     * What the storage card says after a Clear.
     *
     * A row that reads 694 MB and frees 21 of them looks broken, and the
     * blurb's "only ones no project uses" does not say which, or how many,
     * or that a project in the bin still counts - which on a phone with a
     * month of editing on it is most of what is held. So the card says it
     * in numbers after the fact.
     *
     * [keepsReferenced] is false for the caches, which go whole: there is
     * nothing kept to explain.
     */
    fun clearedLine(
        freedBytes: Long,
        keptBytes: Long,
        binnedProjects: Int,
        keepsReferenced: Boolean,
        format: (Long) -> String
    ): String {
        if (!keepsReferenced) return "Freed ${format(freedBytes)}."
        val bin = if (binnedProjects > 0) ", $binnedProjects of them in the bin" else ""
        return when {
            keptBytes <= 0L -> "Freed ${format(freedBytes)}."
            freedBytes <= 0L -> "Nothing to clear - every file here belongs to a project$bin."
            else -> "Freed ${format(freedBytes)}. ${format(keptBytes)} belongs to projects$bin."
        }
    }
}
