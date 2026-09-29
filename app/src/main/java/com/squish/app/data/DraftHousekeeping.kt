package com.squish.app.data

import java.security.MessageDigest

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
 * for a month, and snapshots that are allowed to fall behind by design.
 */
object DraftHousekeeping {

    /** How long a discarded draft waits in the bin before it really goes. */
    const val TRASH_KEEP_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * How often the snapshots move along.
     *
     * The backup is always the save before last, which protects against a
     * corrupt file and nothing else: two saves after a bad state and the good
     * version is gone from both. So there are two snapshots besides. The
     * pending one is taken from the live file at most this often; when it has
     * had its interval, it becomes the snapshot and a new pending one is taken.
     * A single snapshot refreshed on a timer could be refreshed a second after
     * the mistake it was meant to guard against; with the pair, once a draft has
     * been worked on for longer than this, the snapshot always holds a version
     * at least this old.
     */
    const val SNAPSHOT_INTERVAL_MS: Long = 10L * 60 * 1000

    /**
     * Whether a draft's picture list reads as an edit. [written] is how many
     * entries the file holds, [read] how many decoded. An empty list is an
     * edit - the last shot deleted, or only sounds and text left - and must
     * reopen as one: refusing it brought a deleted shot back from the backup,
     * or, once the backup was empty too, lost the project with it still listed.
     * Entries that all fail to read are damage, and the backup is the answer.
     */
    fun clipListReadable(written: Int, read: Int): Boolean = read > 0 || written == 0

    /**
     * The document keys that are not the edit: where the playhead and the zoom
     * were, the snap switch, the stabilizer's default, the save time, and the
     * project's name. Stripped before fingerprinting, so "edited since export"
     * and "an earlier version" go by the video alone - a rename is saved (it is
     * enough to keep a draft) but is not an edit to what was rendered.
     */
    val NOT_THE_EDIT: List<String> = listOf(
        "playheadMs", "pixelsPerSecond", "snapToMarkers", "stabilizeStrength", "savedAtMillis", "name"
    )

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
     * Whether the snapshots should move along before the live file is
     * overwritten. [pendingModifiedMillis] is when the pending snapshot was
     * taken, null when there is none yet; a clock that went backwards counts as
     * due, since a snapshot "from the future" would otherwise never move again.
     */
    fun snapshotDue(pendingModifiedMillis: Long?, nowMillis: Long): Boolean {
        if (pendingModifiedMillis == null || pendingModifiedMillis <= 0L) return true
        val age = nowMillis - pendingModifiedMillis
        return age < 0L || age >= SNAPSHOT_INTERVAL_MS
    }

    /**
     * A short, stable name for an edit's content, so two versions can be told
     * apart without keeping either. Not a security measure: SHA-256 because a
     * collision here would silently hide a change, and it costs nothing at these
     * sizes.
     */
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Whether a draft counts as changed since it was exported.
     *
     * Decided by content where both fingerprints are known: the edit as it is
     * now against the edit that was rendered. Times alone gave false alarms -
     * scrubbing the playhead rewrites a draft without changing it, reopening a
     * draft rewrites it on the first tick, and the stamp and the save that
     * carried it could land a millisecond apart - and each of those put
     * "edited since" on a project nobody had touched. Times are the fallback
     * only for drafts stamped before fingerprints existed.
     */
    fun editedSinceExport(
        savedAtMillis: Long,
        exportedAtMillis: Long?,
        editFingerprint: String? = null,
        exportedFingerprint: String? = null
    ): Boolean {
        if (exportedAtMillis == null) return false
        if (editFingerprint != null && exportedFingerprint != null) return editFingerprint != exportedFingerprint
        return savedAtMillis > exportedAtMillis
    }

    /** One of a draft's snapshots, as far as choosing between them goes. */
    data class Version(val savedAtMillis: Long, val fingerprint: String?)

    enum class Earlier { Snapshot, Pending }

    /**
     * Which snapshot to offer as "an earlier version", if either is worth
     * offering: the older one first, since that is the one that survives a run
     * of bad saves, and only one whose edit differs from the draft as it is -
     * a snapshot that differs only in where the playhead was would "restore"
     * nothing. A version whose fingerprint is unknown is offered, since it
     * cannot be shown to be the same.
     */
    fun earlierVersion(currentFingerprint: String?, snapshot: Version?, pending: Version?): Earlier? {
        fun differs(v: Version?) = v != null &&
            (v.fingerprint == null || currentFingerprint == null || v.fingerprint != currentFingerprint)
        return when {
            differs(snapshot) -> Earlier.Snapshot
            differs(pending) -> Earlier.Pending
            else -> null
        }
    }
}
