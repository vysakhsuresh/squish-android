package com.squish.app.media

/**
 * Whether the last export finished, across a process that may not have
 * (EDGE_PLAN B6).
 *
 * ExportService keeps the process in front while a render runs, but Android
 * can still kill it - under memory pressure, or the person swiping the app
 * away - and nothing resumes a half-written Transformer (START_NOT_STICKY says
 * why). The render then simply vanished: no file, no failure, and the next
 * open of the app said nothing. An entry is written when an export starts and
 * removed when it ends, whichever way; one found on a later launch, written by
 * another process, is an export that was killed, and the dashboard says so.
 *
 * Pure (a line format, no org.json), so tools/jvm/ExportJournalChecks.kt runs
 * it; ExportJournalStore keeps the file.
 */
data class ExportEntry(
    val title: String,
    /** The project's slot, to open it again; null for a quick tool's export. */
    val projectId: String?,
    val startedAtMillis: Long,
    /** The process that wrote it: an entry from this one is an export still running. */
    val pid: Int
)

object ExportJournal {

    /** An entry older than this is not worth raising: the person has moved on. */
    const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    fun encode(e: ExportEntry): String = buildString {
        append("v=1\n")
        append("pid=").append(e.pid).append('\n')
        append("started=").append(e.startedAtMillis).append('\n')
        e.projectId?.let { append("project=").append(it).append('\n') }
        // Last, and the rest of the line whole: a title may hold '=' or anything else but a newline.
        append("title=").append(e.title.replace('\n', ' ')).append('\n')
    }

    fun decode(text: String): ExportEntry? {
        val fields = text.lineSequence().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
        }.toMap()
        if (fields["v"] != "1") return null
        val pid = fields["pid"]?.toIntOrNull() ?: return null
        val started = fields["started"]?.toLongOrNull() ?: return null
        return ExportEntry(
            title = fields["title"]?.takeIf { it.isNotBlank() } ?: "your video",
            projectId = fields["project"]?.takeIf { it.isNotBlank() },
            startedAtMillis = started,
            pid = pid
        )
    }

    /**
     * An export that was killed, to tell the person about - or null: none was
     * written, it is this process's own (still running), or it is stale.
     */
    fun interrupted(entry: ExportEntry?, currentPid: Int, nowMillis: Long): ExportEntry? = when {
        entry == null -> null
        entry.pid == currentPid -> null
        nowMillis - entry.startedAtMillis > STALE_AFTER_MS -> null
        // A clock set back after the entry was written: still an interruption.
        else -> entry
    }

    /** What the dashboard says about it. */
    fun message(entry: ExportEntry): String =
        "The export of “${entry.title}” didn't finish - Android closed Squish while it was being written. " +
            "Nothing was saved; export it again when you're ready."
}
