package com.squish.app.data

/**
 * The rules for keeping a draft safe on disk, kept apart from the files so they
 * can be executed on the JVM.
 *
 * Nothing that removes a draft removes it outright any more. A successful
 * export, "Start a new project", discarding from the list and undoing back to
 * the untouched clip all used to delete the live file, its backup and its
 * sidecar in one go, and the backup was rewritten on every save - so the
 * "second parachute" was one tick deep and a logical overwrite went straight
 * through it. Two things fix that: a bin the files are moved into and kept in
 * for a month, and a snapshot that is allowed to fall behind by design.
 */
object DraftHousekeeping {

    /** How long a discarded draft waits in the bin before it really goes. */
    const val TRASH_KEEP_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * How old the snapshot is allowed to get before it is refreshed.
     *
     * The backup is always the save before last, which protects against a
     * corrupt file and nothing else: two saves after a bad state and the good
     * version is gone from both. The snapshot is refreshed at most this often,
     * so it is always a version at least this old - which is the one worth
     * having when the last ten minutes went wrong.
     */
    const val SNAPSHOT_INTERVAL_MS: Long = 10L * 60 * 1000

    /** The name a discarded slot is filed under in the bin: the slot, then when. */
    fun trashName(slot: String, discardedAtMillis: Long): String = "$slot-$discardedAtMillis"

    /**
     * The slot and time a bin entry's name carries, or null for a name this code
     * did not write. Slots may contain dashes themselves (a tool slot is the
     * tool's id and a session id), so the split is at the last one.
     */
    fun parseTrashName(name: String): Pair<String, Long>? {
        val dash = name.lastIndexOf('-')
        if (dash <= 0 || dash == name.lastIndex) return null
        val at = name.substring(dash + 1).toLongOrNull() ?: return null
        if (at <= 0L) return null
        return name.substring(0, dash) to at
    }

    fun isExpired(discardedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - discardedAtMillis > TRASH_KEEP_MS

    /**
     * Whether the snapshot should be taken from the live file before that file
     * is overwritten. [snapshotModifiedMillis] is null when there is no
     * snapshot yet; a clock that went backwards counts as due, since a snapshot
     * "from the future" would otherwise never refresh again.
     */
    fun snapshotDue(snapshotModifiedMillis: Long?, nowMillis: Long): Boolean {
        if (snapshotModifiedMillis == null || snapshotModifiedMillis <= 0L) return true
        val age = nowMillis - snapshotModifiedMillis
        return age < 0L || age >= SNAPSHOT_INTERVAL_MS
    }

    /**
     * Whether a draft counts as changed since it was exported. A save that lands
     * in the same instant as the export stamp is the export's own flush, not a
     * later edit.
     */
    fun editedSinceExport(savedAtMillis: Long, exportedAtMillis: Long?): Boolean =
        exportedAtMillis != null && savedAtMillis > exportedAtMillis
}
