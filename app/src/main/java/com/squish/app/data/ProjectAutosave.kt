package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.editor.BeatProgress
import com.squish.app.editor.CropAspect
import com.squish.app.editor.CropRect
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.OutputSize
import com.squish.app.editor.OverlayRules
import com.squish.app.editor.ProjectName
import com.squish.app.editor.TextAlign
import com.squish.app.editor.TextAnimation
import com.squish.app.editor.TextBackground
import com.squish.app.editor.TextBubble
import com.squish.app.editor.TextExit
import com.squish.app.editor.TextFont
import com.squish.app.editor.TextLook
import com.squish.app.editor.TextLoop
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextShadow
import com.squish.app.editor.TextStroke
import com.squish.app.editor.TextStyleSpec
import com.squish.app.editor.EffectKind
import com.squish.app.editor.TimedEffect
import com.squish.app.timeline.VoiceEffect
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.ReversedSource
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The edit in progress, on disk, all the time.
 *
 * Android kills backgrounded apps without warning and video editors are the first
 * to go, because they hold the most memory. Every editor that keeps the timeline
 * only in RAM loses the session when that happens. This one does not.
 *
 * Writes are atomic in the POSIX sense: the new document goes to a temporary file,
 * that file is flushed to the platter, and only then is it renamed over the live
 * one. rename(2) is atomic, so the saved project is always either the previous
 * complete version or the new complete version - never a half-written file, no
 * matter when the process dies. The previous version is kept alongside as a second
 * parachute in case the JSON itself is ever unreadable, and two snapshots are
 * allowed to fall behind on purpose, so that a run of bad saves cannot roll over
 * every good version there was - see [DraftHousekeeping.SNAPSHOT_INTERVAL_MS].
 * The drafts screen can put the older one back.
 *
 * Nothing here deletes a draft. Every path that used to - a finished export,
 * "Start a new project", discarding from the list, undoing back to the untouched
 * clip - moves it into a bin instead, where it is kept for a month and can be
 * put back.
 */
/** One saved edit, as a list needs to know about it — without reading the edit. */
data class DraftSummary(
    val id: String,
    val title: String,
    val sourceUri: Uri,
    val durationMs: Long,
    val clipCount: Int,
    val savedAtMillis: Long,
    /**
     * Which quick tool this is a draft of, or null for a timeline in the editor.
     *
     * The two live in different stores and are restored by different code, but on
     * the dashboard they are one list: the person who left a merge half-set-up and
     * the person who left a cut half-made both came back for the same reason, and
     * sorting their work into two piles by which screen made it would help nobody.
     */
    val toolId: String? = null,
    /**
     * When this edit was last rendered to a file, or null if it never was. An
     * exported edit stays: a test render to check a look, then one more tweak,
     * is the commonest reason to come back to a project.
     */
    val exportedAtMillis: Long? = null,
    /** The edit's content as it is now, and as it was rendered; see [DraftHousekeeping.editedSinceExport]. */
    val editFingerprint: String? = null,
    val exportedFingerprint: String? = null,
    /**
     * When the earlier version the drafts screen can go back to was saved, or
     * null when there is none that differs from this one.
     */
    val earlierSavedAtMillis: Long? = null
) {
    val editedSinceExport: Boolean
        get() = DraftHousekeeping.editedSinceExport(savedAtMillis, exportedAtMillis, editFingerprint, exportedFingerprint)
}

/** A draft in the bin: what it was, and when it went there. */
data class TrashedDraft(
    /** The bin entry's name, which is what puts it back or purges it. */
    val trashId: String,
    val draft: DraftSummary,
    val discardedAtMillis: Long
)

class ProjectAutosave(context: Context) {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    /** Where discarded drafts wait. One folder per discard, holding the slot's files. */
    private val trashDir = File(dir, "trash").apply { mkdirs() }

    /**
     * One draft per source video, keyed by its URI.
     *
     * That is the unit anyone thinks in — "the edit I was doing on that clip" —
     * and it means reopening a video picks its work back up rather than offering
     * a list of anonymous sessions. Previously there was a single slot called
     * `current`, so opening a second video silently destroyed the first one's
     * work the moment anything moved.
     */
    private fun slotFor(uri: Uri): String = "p" + uri.toString().hashCode().toUInt().toString(16)

    private fun liveFile(slot: String) = File(dir, "$slot.json")
    private fun backupFile(slot: String) = File(dir, "$slot.bak.json")
    private fun snapshotFile(slot: String) = File(dir, "$slot.snap.json")
    /** Named to end in .snap.json, so every listing that skips snapshots skips it too. */
    private fun pendingSnapshotFile(slot: String) = File(dir, "$slot.pending.snap.json")
    private fun scratchFile(slot: String) = File(dir, "$slot.tmp.json")
    private fun snapshotScratchFile(slot: String) = File(dir, "$slot.snap.tmp.json")
    private fun metaScratchFile(slot: String) = File(dir, "$slot.meta.tmp.json")

    /**
     * A few hundred bytes written beside each draft: enough to list every draft
     * without parsing any of them. A drafts list that had to decode a dozen full
     * timelines to draw itself would be slower than the editor it leads to.
     */
    private fun metaFile(slot: String) = File(dir, "$slot.meta.json")

    /** The files that make up one slot, in the order they matter. */
    private fun slotFiles(slot: String) =
        listOf(liveFile(slot), backupFile(slot), snapshotFile(slot), pendingSnapshotFile(slot), metaFile(slot))

    /** Cheap change detector, so an idle editor never touches the disk. */
    private val lastSignature = HashMap<String, String>()

    /**
     * Every method that touches the files takes this. The editor's ticker saves on
     * an IO thread while a flush on leaving saves on the main one, and two writers
     * renaming over the same live file would otherwise race.
     */
    private val lock = Any()

    /**
     * Persists the edit if anything has changed since the last write. Safe to call
     * on a timer; it is a no-op when nothing moved.
     *
     * "Anything" includes the playhead and the zoom, so a draft reopens where it
     * was left. Whether the *edit* changed - which is what "edited since export"
     * means - is carried separately, as a fingerprint of [editKey].
     */
    fun save(state: EditorUiState): Boolean = synchronized(lock) {
        val uri = state.sourceUri ?: return false
        if (state.isLoadingSource) return false
        val slot = slotFor(uri)
        val live = liveFile(slot)

        // The signature is taken from the document alone. Stamping the time first
        // would make every tick look like a change and turn "save when something
        // moved" into "write to flash every 1.5 seconds, forever".
        val document = encode(state)
        val signature = document.toString()
        if (signature == lastSignature[slot]) return false

        val now = System.currentTimeMillis()
        val fingerprint = DraftHousekeeping.fingerprint(editKey(state))
        document.put("savedAtMillis", now)
        val previous = readMetaJson(metaFile(slot))

        val ok = runCatching {
            // Nothing below touches the live file until the new version is safely
            // on disk beside it.
            FileOutputStream(scratchFile(slot)).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()          // on the platter, not just in the page cache
            }
            val snapshots = rotateSnapshots(slot, previous, now)
            if (live.exists()) live.copyTo(backupFile(slot), overwrite = true)
            // The sidecar goes first, atomically. It used to be a plain truncating
            // write after the rename, so a kill in the gap left a draft that was
            // whole on disk and missing from the list.
            writeMeta(slot, state, uri, now, fingerprint, previous, snapshots)
            DraftFiles.replace(scratchFile(slot), live)
        }.isSuccess

        if (ok) lastSignature[slot] = signature
        ok
    }

    /**
     * Moves the two snapshots along when they are due and returns what the
     * sidecar should now say about them. The version a new pending snapshot is
     * taken from is the live file, read here for its time and fingerprint -
     * which happens once every ten minutes at most.
     */
    private fun rotateSnapshots(slot: String, previous: JSONObject?, now: Long): SnapshotInfo {
        val carried = SnapshotInfo(
            snapshot = versionIn(previous, "snapshot"),
            pending = versionIn(previous, "pending")
        )
        val live = liveFile(slot)
        val pending = pendingSnapshotFile(slot)
        if (!live.exists() || !DraftHousekeeping.snapshotDue(pending.takeIf { it.exists() }?.lastModified(), now)) {
            return carried
        }
        val liveVersion = runCatching { JSONObject(live.readText()) }.getOrNull()?.let { json ->
            DraftHousekeeping.Version(json.optLong("savedAtMillis"), DraftHousekeeping.fingerprint(editKeyOf(json)))
        }
        val rotation = DraftFiles.rotateSnapshots(live, snapshotFile(slot), pending, snapshotScratchFile(slot), now)
        return SnapshotInfo(
            snapshot = if (rotation.promoted) carried.pending else carried.snapshot,
            pending = when {
                rotation.taken -> liveVersion
                rotation.promoted -> null
                else -> carried.pending
            }
        )
    }

    private data class SnapshotInfo(val snapshot: DraftHousekeeping.Version?, val pending: DraftHousekeeping.Version?)

    private fun versionIn(meta: JSONObject?, prefix: String): DraftHousekeeping.Version? {
        val at = meta?.optLong("${prefix}SavedAtMillis", 0L)?.takeIf { it > 0L } ?: return null
        return DraftHousekeeping.Version(at, meta.optString("${prefix}Fingerprint").takeIf { it.isNotBlank() })
    }

    /**
     * The edit itself, for telling an edited timeline from an untouched one.
     *
     * Where the playhead sits and how far the strip is zoomed are left out:
     * scrubbing through a clip to look at it is not editing it. Nor are the
     * snapping switch and the stabilizer's strength dial, which are settings
     * for edits rather than edits - they are saved with a draft, but they do
     * not make one.
     */
    fun editKey(state: EditorUiState): String = editKeyOf(encode(state))

    /**
     * The same key read off a saved document, so a snapshot on disk can be
     * compared with the edit on screen. A document read back prints exactly as
     * it was written - org.json keeps the order of the keys and the spelling of
     * the numbers - so the two agree whenever the edits do. Takes the document
     * apart, so it is only ever handed a fresh one.
     */
    private fun editKeyOf(document: JSONObject): String = document.apply {
        remove("playheadMs")
        remove("pixelsPerSecond")
        remove("snapToMarkers")
        remove("stabilizeStrength")
        remove("savedAtMillis")
    }.toString()

    /**
     * A recoverable session for this video, if one survived. The snapshots are
     * the last resort, behind the live file and its backup.
     */
    fun peek(uri: Uri): ProjectSnapshot? = synchronized(lock) {
        val slot = slotFor(uri)
        read(liveFile(slot)) ?: read(backupFile(slot)) ?: read(pendingSnapshotFile(slot)) ?: read(snapshotFile(slot))
    }

    /**
     * Every draft, newest first, read from the sidecars where they exist.
     *
     * A live file with no readable sidecar is listed from its own header rather
     * than dropped: the sidecar is a convenience for the list, not the draft, and
     * a draft that had vanished from the list while sitting whole on disk was one
     * of the ways work looked lost.
     */
    fun drafts(): List<DraftSummary> = synchronized(lock) {
        runCatching {
            // listFiles(lambda) is ambiguous between FileFilter and FilenameFilter,
            // so the filtering happens after, on a plainly typed array.
            val files: Array<File> = dir.listFiles() ?: return@runCatching emptyList()
            files.filter { it.isFile && it.name.endsWith(".json") && !it.name.contains(".tmp.") }
                .filter { !it.name.endsWith(".bak.json") && !it.name.endsWith(".snap.json") && !it.name.endsWith(".meta.json") }
                .mapNotNull { live ->
                    val slot = live.name.removeSuffix(".json")
                    summaryOf(slot, live, metaFile(slot))?.let { summary ->
                        summary.copy(earlierSavedAtMillis = earlierOf(slot, summary)?.second?.savedAtMillis)
                    }
                }
                .sortedByDescending { it.savedAtMillis }
        }.getOrDefault(emptyList())
    }

    private fun summaryOf(slot: String, live: File, meta: File): DraftSummary? {
        readMeta(meta)?.let { return it.copy(id = slot) }
        val json = runCatching { JSONObject(live.readText()) }.getOrNull() ?: return null
        val snapshot = decode(json) ?: return null
        return DraftSummary(
            id = slot,
            title = snapshot.name ?: snapshot.clips.firstOrNull()?.label ?: "Untitled edit",
            sourceUri = snapshot.sourceUri,
            durationMs = snapshot.totalDurationMs,
            clipCount = snapshot.clipCount,
            savedAtMillis = snapshot.savedAtMillis,
            editFingerprint = DraftHousekeeping.fingerprint(editKeyOf(json))
        )
    }

    /**
     * The snapshot worth offering as an earlier version of this draft, and what
     * the sidecar knows of it; null when neither is on disk or both hold the
     * same edit as the draft.
     */
    private fun earlierOf(slot: String, summary: DraftSummary): Pair<File, DraftHousekeeping.Version>? {
        val meta = readMetaJson(metaFile(slot))
        val snapshot = snapshotFile(slot).takeIf { it.exists() }?.let { file ->
            versionIn(meta, "snapshot") ?: DraftHousekeeping.Version(file.lastModified(), null)
        }
        val pending = pendingSnapshotFile(slot).takeIf { it.exists() }?.let { file ->
            versionIn(meta, "pending") ?: DraftHousekeeping.Version(file.lastModified(), null)
        }
        return when (DraftHousekeeping.earlierVersion(summary.editFingerprint, snapshot, pending)) {
            DraftHousekeeping.Earlier.Snapshot -> snapshot?.let { snapshotFile(slot) to it }
            DraftHousekeeping.Earlier.Pending -> pending?.let { pendingSnapshotFile(slot) to it }
            null -> null
        }
    }

    /**
     * Puts the draft's earlier version back in its place. The version it
     * replaces goes into the bin whole - live file, backup, snapshots and all -
     * so this is undone by restoring that bin entry. Returns the entry's name,
     * or null when there was nothing to go back to or the swap could not be
     * made, in which case the draft is as it was.
     */
    fun revertToEarlier(slot: String): String? = synchronized(lock) {
        val summary = summaryOf(slot, liveFile(slot), metaFile(slot)) ?: return null
        val (file, version) = earlierOf(slot, summary) ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val earlier = runCatching { decode(JSONObject(text)) }.getOrNull() ?: return null
        val binned = delete(slot) ?: return null
        val placed = runCatching {
            // The sidecar first, as a save writes it.
            val meta = JSONObject().apply {
                put("id", slot)
                put("title", earlier.name ?: earlier.clips.firstOrNull()?.label ?: "Untitled edit")
                put("uri", earlier.sourceUri.toString())
                put("durationMs", earlier.totalDurationMs)
                put("clipCount", earlier.clipCount)
                put("savedAtMillis", earlier.savedAtMillis.takeIf { it > 0L } ?: version.savedAtMillis)
                put("editFingerprint", DraftHousekeeping.fingerprint(editKeyOf(JSONObject(text))))
            }
            DraftFiles.writeAtomically(metaScratchFile(slot), metaFile(slot), meta.toString().toByteArray())
            DraftFiles.writeAtomically(scratchFile(slot), liveFile(slot), text.toByteArray())
        }.isSuccess
        if (placed) return binned
        // Whatever got written is dropped - it is a copy of a file still in the
        // bin - and the draft comes back as it was.
        slotFiles(slot).forEach { it.delete() }
        restore(binned)
        null
    }

    /**
     * Moves a slot into the bin and returns the bin entry's name, or null when
     * there was nothing on disk to move or the move failed - in which case the
     * slot is exactly as it was.
     */
    fun delete(slot: String): String? = synchronized(lock) {
        lastSignature.remove(slot)
        scratchFile(slot).delete()
        snapshotScratchFile(slot).delete()
        metaScratchFile(slot).delete()
        DraftFiles.moveToBin(trashDir, slot, slotFiles(slot))
    }

    fun clear(uri: Uri): String? = delete(slotFor(uri))

    /**
     * Stamps the draft as exported: when, and which edit - [rendered] is the
     * state the file was made from, so a change made while it rendered still
     * reads as "edited since". The draft stays where it is, exported and all:
     * the work reached the gallery, and the commonest thing to want next is one
     * more change to it. Nothing to stamp when the edit never differed from the
     * bare clip, since no draft was ever written for it.
     */
    fun markCompleted(rendered: EditorUiState) {
        val uri = rendered.sourceUri ?: return
        val fingerprint = DraftHousekeeping.fingerprint(editKey(rendered))
        synchronized(lock) {
            val slot = slotFor(uri)
            if (!liveFile(slot).exists()) return
            val existing = readMetaJson(metaFile(slot)) ?: return
            existing.put("exportedAtMillis", System.currentTimeMillis())
            existing.put("exportedFingerprint", fingerprint)
            runCatching {
                DraftFiles.writeAtomically(metaScratchFile(slot), metaFile(slot), existing.toString().toByteArray())
            }
        }
    }

    /** Everything in the bin, newest first, with anything past its month gone. */
    fun trashed(): List<TrashedDraft> = synchronized(lock) {
        runCatching {
            val now = System.currentTimeMillis()
            val entries: Array<File> = trashDir.listFiles() ?: return@runCatching emptyList()
            entries.filter { it.isDirectory }.mapNotNull { entry ->
                val (slot, at) = DraftHousekeeping.parseTrashName(entry.name) ?: return@mapNotNull null
                if (DraftHousekeeping.isExpired(at, now)) {
                    entry.deleteRecursively()
                    return@mapNotNull null
                }
                val live = File(entry, liveFile(slot).name)
                val summary = summaryOf(slot, live, File(entry, metaFile(slot).name))
                    ?: read(File(entry, backupFile(slot).name))?.let { s ->
                        DraftSummary(slot, s.name ?: s.clips.firstOrNull()?.label ?: "Untitled edit", s.sourceUri, s.totalDurationMs, s.clipCount, s.savedAtMillis)
                    }
                    ?: return@mapNotNull null
                TrashedDraft(trashId = entry.name, draft = summary, discardedAtMillis = at)
            }.sortedByDescending { it.discardedAtMillis }
        }.getOrDefault(emptyList())
    }

    /**
     * Puts a bin entry back as the live draft of its clip. A newer draft already
     * in that slot goes into the bin in its place, so nothing is overwritten
     * either way - and if that draft cannot be moved aside, nothing is restored.
     * A rename replaces its target, so going ahead regardless wrote the old
     * draft over the newer one with no copy of it anywhere.
     */
    fun restore(trashId: String): Boolean = synchronized(lock) {
        val entry = File(trashDir, trashId)
        if (!entry.isDirectory) return false
        val (slot, _) = DraftHousekeeping.parseTrashName(trashId) ?: return false
        if (slotFiles(slot).any { it.exists() } && delete(slot) == null) return false
        lastSignature.remove(slot)
        DraftFiles.moveOutOfBin(entry, slotFiles(slot))
    }

    /** Removes a bin entry for good. Only ever from a confirmed tap on the list. */
    fun purge(trashId: String) {
        synchronized(lock) {
            val entry = File(trashDir, trashId)
            if (DraftHousekeeping.parseTrashName(trashId) != null && entry.isDirectory) entry.deleteRecursively()
        }
    }

    private fun writeMeta(
        slot: String,
        state: EditorUiState,
        uri: Uri,
        savedAtMillis: Long,
        fingerprint: String,
        previous: JSONObject?,
        snapshots: SnapshotInfo
    ) {
        val json = JSONObject().apply {
            put("id", slot)
            put("title", state.projectName ?: state.videoClips.firstOrNull()?.label ?: "Untitled edit")
            put("uri", uri.toString())
            put("durationMs", state.trimmedDurationMs)
            put("clipCount", state.videoClips.size)
            put("savedAtMillis", savedAtMillis)
            put("editFingerprint", fingerprint)
            // The export stamp is the one thing in the sidecar the edit does not
            // carry, so it is kept from the previous sidecar rather than lost on
            // the next save.
            previous?.optLong("exportedAtMillis", 0L)?.takeIf { it > 0L }?.let { put("exportedAtMillis", it) }
            previous?.optString("exportedFingerprint")?.takeIf { it.isNotBlank() }?.let { put("exportedFingerprint", it) }
            snapshots.snapshot?.let { v ->
                put("snapshotSavedAtMillis", v.savedAtMillis)
                v.fingerprint?.let { put("snapshotFingerprint", it) }
            }
            snapshots.pending?.let { v ->
                put("pendingSavedAtMillis", v.savedAtMillis)
                v.fingerprint?.let { put("pendingFingerprint", it) }
            }
        }
        DraftFiles.writeAtomically(metaScratchFile(slot), metaFile(slot), json.toString().toByteArray())
    }

    private fun readMetaJson(file: File): JSONObject? =
        if (!file.exists()) null else runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun readMeta(file: File): DraftSummary? = runCatching {
        val json = readMetaJson(file) ?: return null
        val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
        DraftSummary(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            title = json.optString("title", "Untitled edit"),
            sourceUri = Uri.parse(uri),
            durationMs = json.optLong("durationMs"),
            clipCount = json.optInt("clipCount", 1),
            savedAtMillis = json.optLong("savedAtMillis"),
            exportedAtMillis = json.optLong("exportedAtMillis", 0L).takeIf { it > 0L },
            editFingerprint = json.optString("editFingerprint").takeIf { it.isNotBlank() },
            exportedFingerprint = json.optString("exportedFingerprint").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun read(file: File): ProjectSnapshot? {
        if (!file.exists()) return null
        return runCatching { decode(JSONObject(file.readText())) }.getOrNull()
    }

    // ---- Encoding -------------------------------------------------------------

    private fun encode(state: EditorUiState): JSONObject = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("sourceUri", state.sourceUri.toString())
        // Only when there is one, so every draft saved before names existed keeps
        // the edit key it had and is not read as changed.
        state.projectName?.let { put("name", it) }
        put("durationMs", state.durationMs)
        put("playheadMs", state.playheadMs)
        put("outputP", state.outputP)
        put("fitToSize", state.fitToSize)
        put("targetSizeMb", state.targetSizeMb)
        put("audioOnly", state.audioOnly)
        put("muteOriginal", state.muteOriginal)
        put("originalVolume", state.originalVolume.toDouble())
        put("rotationDegrees", state.rotationDegrees)
        put("cropAspect", state.cropAspect.name)
        // The hand-drawn rectangle. Saved only when it means something: a
        // Custom crop that came back as the whole frame exported uncropped while
        // the panel still said Custom.
        if (!state.cropRect.isFull) {
            put("cropRect", JSONObject().apply {
                put("left", state.cropRect.left.toDouble())
                put("top", state.cropRect.top.toDouble())
                put("right", state.cropRect.right.toDouble())
                put("bottom", state.cropRect.bottom.toDouble())
            })
        }
        put("snapToMarkers", state.snapToMarkers)
        put("stabilizeStrength", state.stabilizeStrength.toDouble())
        put("brightness", state.brightness.toDouble())
        put("contrast", state.contrast.toDouble())
        put("saturation", state.saturation.toDouble())
        put("lookId", state.lookId ?: JSONObject.NULL)
        put("lookIntensity", state.lookIntensity.toDouble())
        put("pixelsPerSecond", state.pixelsPerSecond.toDouble())
        put("markers", JSONArray().apply { state.markers.forEach { put(it) } })
        // The grid on screen, whatever the last listen did. A second listen still
        // running, or one that failed, keeps the grid already found - the panel
        // says so - and writing it only after a listen that succeeded left it out
        // of the draft, and out of the edit key, so a failed listen could even
        // make the edit read as undone back to the bare clip and bin the draft.
        if (state.beats.hasBeats) {
            put("beats", JSONObject().apply {
                put("bpm", state.beats.bpm.toDouble())
                put("confidence", state.beats.confidence.toDouble())
                put("downbeatOffset", state.beats.downbeatOffset)
                put("clipLabel", state.beats.clipLabel)
                put("every", state.beats.every)
                state.beats.clipId?.let { put("clipId", it) }
                // Only a grid on the camera audio has beats here; a sound's are
                // saved on the sound, with the clip they belong to.
                put("beatsMs", JSONArray().apply { state.beats.beatsMs.forEach { put(it) } })
            })
        }
        put("clips", JSONArray().apply { state.videoClips.forEach { put(encodeClip(it)) } })
        put("textOverlays", JSONArray().apply { state.textOverlays.forEach { put(encodeText(it)) } })
        state.reframe?.let { track ->
            put("reframe", JSONArray().apply {
                track.samples.forEach { s ->
                    put(JSONObject().apply {
                        put("atMs", s.atMs)
                        put("x", s.xFraction.toDouble())
                        put("y", s.yFraction.toDouble())
                    })
                }
            })
        }
        put("effects", JSONArray().apply {
            state.effects.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind.name)
                    put("startMs", e.startMs)
                    put("endMs", e.endMs)
                    put("intensity", e.intensity.toDouble())
                })
            }
        })

        put("audioClips", JSONArray().apply { state.audioClips.forEach { put(encodeClip(it)) } })
    }

    private fun encodeClip(clip: Clip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("uri", clip.uri?.toString() ?: JSONObject.NULL)
        put("label", clip.label)
        put("sourceInMs", clip.sourceInMs)
        put("sourceOutMs", clip.sourceOutMs)
        put("timelineStartMs", clip.timelineStartMs)
        put("sourceDurationMs", clip.sourceDurationMs)
        put("volume", clip.volume.toDouble())
        if (clip.fadeInMs > 0L) put("fadeInMs", clip.fadeInMs)
        if (clip.fadeOutMs > 0L) put("fadeOutMs", clip.fadeOutMs)
        if (clip.voice != VoiceEffect.None) put("voice", clip.voice.name)
        if (clip.beats.isNotEmpty()) put("beats", JSONArray().apply { clip.beats.forEach { put(it) } })
        put(
            "speedPoints",
            JSONArray().apply {
                clip.speedRamp.ordered.forEach { point ->
                    put(JSONObject().apply {
                        put("atMs", point.atMs)
                        put("speed", point.speed.toDouble())
                    })
                }
            }
        )
        put("transitionType", clip.transitionIn.type.name)
        put("transitionMs", clip.transitionIn.durationMs)
        put("layer", clip.layer)
        put("opacity", clip.opacity.toDouble())
        put("scale", clip.scale.toDouble())
        put("offsetXFraction", clip.offsetXFraction.toDouble())
        put("offsetYFraction", clip.offsetYFraction.toDouble())
        put("rotation", clip.rotation.toDouble())
        put("keyframes", JSONArray().apply { clip.keyframes.forEach { put(encodeKeyframe(it)) } })
        put("stabilizer", JSONArray().apply { clip.stabilizer.forEach { put(encodeKeyframe(it)) } })
        clip.mask?.let { m ->
            put("mask", JSONObject().apply {
                put("shape", m.shape.name)
                put("centerXFraction", m.centerXFraction.toDouble())
                put("centerYFraction", m.centerYFraction.toDouble())
                put("widthFraction", m.widthFraction.toDouble())
                put("heightFraction", m.heightFraction.toDouble())
                put("rotationDegrees", m.rotationDegrees.toDouble())
                put("feather", m.feather.toDouble())
                put("cornerRadius", m.cornerRadius.toDouble())
                put("inverted", m.inverted)
                put("mode", m.mode.name)
                put("strength", m.strength.toDouble())
                m.track?.let { t ->
                    put("track", JSONArray().apply {
                        t.samples.forEach { sample ->
                            put(JSONObject().apply {
                                put("atMs", sample.atMs)
                                put("x", sample.xFraction.toDouble())
                                put("y", sample.yFraction.toDouble())
                                put("scale", sample.scale.toDouble())
                                put("confidence", sample.confidence.toDouble())
                            })
                        }
                    })
                }
            })
        }
        clip.background?.let { bg ->
            put("background", JSONObject().apply {
                put("maskFile", bg.maskFile)
                put("fill", bg.fill.name)
                put("colorArgb", bg.colorArgb)
            })
        }
        clip.chromaKey?.let { key ->
            put("chromaKey", JSONObject().apply {
                put("keyColorArgb", key.keyColorArgb)
                put("similarity", key.similarity.toDouble())
                put("smoothness", key.smoothness.toDouble())
                put("spill", key.spill.toDouble())
            })
        }
        // Written only when set, so an older build reading the draft sees
        // nothing it does not know.
        if (clip.mirrored) put("mirrored", true)
        if (clip.quarterTurns != 0) put("quarterTurns", clip.quarterTurns)
        clip.reversedFrom?.let { from ->
            put("reversedFrom", JSONObject().apply {
                put("uri", from.uri?.toString() ?: JSONObject.NULL)
                put("sourceInMs", from.sourceInMs)
                put("sourceOutMs", from.sourceOutMs)
                put("durationMs", from.durationMs)
                // The person masks measured on the original, kept for Reverse
                // again (see Clip.reversed). Written as the clip's own are.
                from.background?.let { bg ->
                    put("background", JSONObject().apply {
                        put("maskFile", bg.maskFile)
                        put("fill", bg.fill.name)
                        put("colorArgb", bg.colorArgb)
                    })
                }
            })
        }
    }

    private fun encodeKeyframe(key: Keyframe): JSONObject = JSONObject().apply {
        put("atMs", key.atMs)
        put("scale", key.transform.scale.toDouble())
        put("offsetXFraction", key.transform.offsetXFraction.toDouble())
        put("offsetYFraction", key.transform.offsetYFraction.toDouble())
        put("rotationDegrees", key.transform.rotationDegrees.toDouble())
        put("easing", key.easing.name)
    }

    private fun encodeText(item: TextOverlayItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("text", item.text)
        put("startMs", item.startMs)
        put("endMs", item.endMs)
        put("xFraction", item.xFraction.toDouble())
        put("yFraction", item.yFraction.toDouble())
        TextStyleJson.write(this, item.style)
        put("rotation", item.rotationDegrees.toDouble())
        put("flipped", item.flipped)
        put("motion", item.motion.name)
        put("motionInMs", item.motionInMs)
        put("motionOut", item.motionOut.name)
        put("motionOutMs", item.motionOutMs)
        put("loop", item.loop.name)
        put("loopMs", item.loopMs)
        if (item.wordStartsMs.isNotEmpty()) put("wordStarts", JSONArray(item.wordStartsMs))
        put("sticker", item.sticker)
        put("stripRow", item.stripRow)
        item.track?.let { t ->
            put("track", JSONArray().apply {
                t.samples.forEach { sample ->
                    put(JSONObject().apply {
                        put("atMs", sample.atMs)
                        put("x", sample.xFraction.toDouble())
                        put("y", sample.yFraction.toDouble())
                        put("scale", sample.scale.toDouble())
                        put("confidence", sample.confidence.toDouble())
                    })
                }
            })
        }
    }

    // ---- Decoding -------------------------------------------------------------

    private fun decode(json: JSONObject): ProjectSnapshot? {
        // Every version since the last incompatible change is read: the fields
        // added since then all have defaults, and a bump that orphaned every
        // draft on the phone would be the very loss this file exists to prevent.
        val version = json.optInt("version")
        if (version !in OLDEST_READABLE_VERSION..FORMAT_VERSION) return null
        val sourceUri = json.optString("sourceUri").takeIf { it.isNotBlank() } ?: return null

        val saved = json.optJSONArray("clips")?.let { array ->
            (0 until array.length()).mapNotNull { i -> decodeClip(array.optJSONObject(i), ClipKind.Video) }
        }.orEmpty()
        if (saved.isEmpty()) return null
        // Saved before each picture had its own level: the edit-wide camera level
        // moves onto the shots, and the overlays - silent then - stay silent.
        val perClip = version < PER_CLIP_VOLUME_VERSION
        val savedLevel = json.optDouble("originalVolume", 1.0).toFloat()
        val levelled = if (perClip) OverlayRules.withPerClipVolume(saved, savedLevel) else saved
        // Saved when the voice was one setting for the edit, applied to every
        // main-track shot: it goes onto each of them, and overlays - never
        // voiced then - stay as they were. Told by the field, not the version:
        // nothing has written "voiceEffect" since the voice moved onto the
        // clips, but a build from the text branch wrote it under the same
        // version number (see FORMAT_VERSION), and its drafts keep their voice.
        val legacyVoice = enumOrNull<VoiceEffect>(json.optString("voiceEffect")) ?: VoiceEffect.None
        val clips = if (legacyVoice != VoiceEffect.None) {
            levelled.map { if (it.isOverlay) it else it.copy(voice = legacyVoice) }
        } else levelled

        val audio = json.optJSONArray("audioClips")?.let { array ->
            (0 until array.length()).mapNotNull { i -> decodeClip(array.optJSONObject(i), ClipKind.Audio) }
        }.orEmpty()

        val overlays = json.optJSONArray("textOverlays")?.let { array ->
            (0 until array.length()).mapNotNull { i -> decodeText(array.optJSONObject(i)) }
        }.orEmpty()

        val markers = json.optJSONArray("markers")?.let { array ->
            (0 until array.length()).map { i -> array.optLong(i) }
        }.orEmpty()

        return ProjectSnapshot(
            sourceUri = Uri.parse(sourceUri),
            name = json.optString("name").takeIf { it.isNotBlank() }?.let(ProjectName::clean),
            savedAtMillis = json.optLong("savedAtMillis"),
            clipCount = clips.size,
            clips = clips,
            audioClips = audio,
            textOverlays = overlays,
            reframe = json.optJSONArray("reframe")?.let { array ->
                MotionTrack((0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { o ->
                        TrackSample(
                            atMs = o.optLong("atMs"),
                            xFraction = o.optDouble("x", 0.5).toFloat(),
                            yFraction = o.optDouble("y", 0.5).toFloat()
                        )
                    }
                })
            }?.takeIf { !it.isEmpty },
            effects = json.optJSONArray("effects")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    TimedEffect(
                        id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                        kind = enumOrNull<EffectKind>(o.optString("kind")) ?: return@mapNotNull null,
                        startMs = o.optLong("startMs"),
                        endMs = o.optLong("endMs"),
                        intensity = o.optDouble("intensity", 0.7).toFloat()
                    )
                }
            }.orEmpty(),
            markers = markers,
            playheadMs = json.optLong("playheadMs"),
            outputP = if (json.has("outputP")) json.optInt("outputP") else OutputSize.fromLegacyQuality(json.optString("quality")) ?: OutputSize.ORIGINAL,
            fitToSize = json.optBoolean("fitToSize"),
            targetSizeMb = json.optInt("targetSizeMb", 16),
            audioOnly = json.optBoolean("audioOnly"),
            muteOriginal = json.optBoolean("muteOriginal"),
            originalVolume = if (perClip) 1f else savedLevel,
            rotationDegrees = json.optInt("rotationDegrees"),
            cropAspect = enumOrNull<CropAspect>(json.optString("cropAspect")) ?: CropAspect.Original,
            cropRect = json.optJSONObject("cropRect")?.let { r ->
                CropRect.of(
                    left = r.optDouble("left", 0.0).toFloat(),
                    top = r.optDouble("top", 0.0).toFloat(),
                    right = r.optDouble("right", 1.0).toFloat(),
                    bottom = r.optDouble("bottom", 1.0).toFloat()
                )
            } ?: CropRect(),
            snapToMarkers = json.optBoolean("snapToMarkers", true),
            stabilizeStrength = json.optDouble("stabilizeStrength", 0.5).toFloat().coerceIn(0f, 1f),
            beats = json.optJSONObject("beats")?.let { b ->
                val beatsMs = b.optJSONArray("beatsMs")?.let { array ->
                    (0 until array.length()).map { i -> array.optLong(i) }
                }.orEmpty()
                // A sound named here that is no longer in the draft carries no
                // grid, and the card must not say it does.
                val clipId = b.optString("clipId").takeIf { id -> id.isNotBlank() && audio.any { it.id == id } }
                BeatProgress(
                    finished = true,
                    bpm = b.optDouble("bpm", 0.0).toFloat(),
                    confidence = b.optDouble("confidence", 0.0).toFloat(),
                    beatsMs = beatsMs,
                    downbeatOffset = b.optInt("downbeatOffset"),
                    clipLabel = b.optString("clipLabel"),
                    clipId = clipId,
                    every = b.optInt("every", 1).coerceIn(1, 4)
                ).takeIf { it.hasBeats }
            } ?: BeatProgress(),
            brightness = json.optDouble("brightness").toFloat(),
            contrast = json.optDouble("contrast").toFloat(),
            saturation = json.optDouble("saturation").toFloat(),
            lookId = json.optString("lookId").takeIf { it.isNotBlank() && it != "null" },
            lookIntensity = json.optDouble("lookIntensity", 1.0).toFloat(),
            pixelsPerSecond = json.optDouble("pixelsPerSecond", 42.0).toFloat()
        )
    }

    /**
     * A clip's speed curve.
     *
     * Falls back to the single "speed" number a project saved before ramps existed
     * would carry, read as a flat curve. A recovery offer that silently dropped
     * someone's speed change would be worse than not offering one.
     */
    private fun decodeRamp(json: JSONObject): SpeedRamp {
        val array = json.optJSONArray("speedPoints")
        if (array != null && array.length() > 0) {
            val points = (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                SpeedPoint(entry.optLong("atMs"), entry.optDouble("speed", 1.0).toFloat())
            }
            if (points.isNotEmpty()) return SpeedRamp(points)
        }
        val legacy = json.optDouble("speed", 1.0).toFloat()
        return if (legacy == 1f) SpeedRamp() else SpeedRamp.flat(legacy)
    }

    private fun decodeClip(json: JSONObject?, kind: ClipKind): Clip? {
        if (json == null) return null
        return Clip(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            kind = kind,
            uri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
            label = json.optString("label", "Clip"),
            sourceInMs = json.optLong("sourceInMs"),
            sourceOutMs = json.optLong("sourceOutMs"),
            timelineStartMs = json.optLong("timelineStartMs"),
            sourceDurationMs = json.optLong("sourceDurationMs"),
            volume = json.optDouble("volume", 1.0).toFloat(),
            fadeInMs = json.optLong("fadeInMs").coerceAtLeast(0L),
            fadeOutMs = json.optLong("fadeOutMs").coerceAtLeast(0L),
            voice = enumOrNull<VoiceEffect>(json.optString("voice")) ?: VoiceEffect.None,
            beats = json.optJSONArray("beats")?.let { array ->
                (0 until array.length()).map { i -> array.optLong(i) }
            }.orEmpty().sorted(),
            speedRamp = decodeRamp(json),
            transitionIn = Transition(
                type = enumOrNull<TransitionType>(json.optString("transitionType")) ?: TransitionType.None,
                durationMs = json.optLong("transitionMs", 500L)
            ),
            layer = json.optInt("layer"),
            opacity = json.optDouble("opacity", 1.0).toFloat(),
            scale = json.optDouble("scale", 1.0).toFloat(),
            offsetXFraction = json.optDouble("offsetXFraction").toFloat(),
            offsetYFraction = json.optDouble("offsetYFraction").toFloat(),
            rotation = json.optDouble("rotation").toFloat(),
            // Sorted on the way in: evaluation on the render thread trusts the
            // order and does not sort, so a hand-edited file cannot break it.
            keyframes = json.optJSONArray("keyframes")?.let { array ->
                (0 until array.length()).mapNotNull { i -> decodeKeyframe(array.optJSONObject(i)) }
            }.orEmpty().sortedBy { it.atMs },
            stabilizer = json.optJSONArray("stabilizer")?.let { array ->
                (0 until array.length()).mapNotNull { i -> decodeKeyframe(array.optJSONObject(i)) }
            }.orEmpty().sortedBy { it.atMs },
            background = json.optJSONObject("background")?.let(::decodeBackground),
            chromaKey = json.optJSONObject("chromaKey")?.let { k ->
                ChromaKey(
                    keyColorArgb = k.optInt("keyColorArgb", ChromaKey.STANDARD_GREEN),
                    similarity = k.optDouble("similarity", 0.38).toFloat(),
                    smoothness = k.optDouble("smoothness", 0.1).toFloat(),
                    spill = k.optDouble("spill", 0.12).toFloat()
                )
            },
            mask = json.optJSONObject("mask")?.let { m ->
                Mask(
                    shape = enumOrNull<MaskShape>(m.optString("shape")) ?: MaskShape.Ellipse,
                    centerXFraction = m.optDouble("centerXFraction").toFloat(),
                    centerYFraction = m.optDouble("centerYFraction").toFloat(),
                    widthFraction = m.optDouble("widthFraction", 0.6).toFloat(),
                    heightFraction = m.optDouble("heightFraction", 0.6).toFloat(),
                    rotationDegrees = m.optDouble("rotationDegrees").toFloat(),
                    feather = m.optDouble("feather", 0.04).toFloat(),
                    cornerRadius = m.optDouble("cornerRadius").toFloat(),
                    inverted = m.optBoolean("inverted"),
                    mode = enumOrNull<MaskMode>(m.optString("mode")) ?: MaskMode.Cutout,
                    strength = m.optDouble("strength", 0.5).toFloat(),
                    track = m.optJSONArray("track")?.let { array ->
                        MotionTrack(
                            (0 until array.length()).mapNotNull { i ->
                                array.optJSONObject(i)?.let { o ->
                                    TrackSample(
                                        atMs = o.optLong("atMs"),
                                        xFraction = o.optDouble("x", 0.5).toFloat(),
                                        yFraction = o.optDouble("y", 0.5).toFloat(),
                                        scale = o.optDouble("scale", 1.0).toFloat(),
                                        confidence = o.optDouble("confidence", 1.0).toFloat()
                                    )
                                }
                            }
                        ).takeIf { !it.isEmpty }
                    }
                )
            },
            mirrored = json.optBoolean("mirrored"),
            quarterTurns = (json.optInt("quarterTurns") % 4 + 4) % 4,
            reversedFrom = json.optJSONObject("reversedFrom")?.let { r ->
                ReversedSource(
                    uri = r.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
                    sourceInMs = r.optLong("sourceInMs"),
                    sourceOutMs = r.optLong("sourceOutMs"),
                    durationMs = r.optLong("durationMs"),
                    background = r.optJSONObject("background")?.let(::decodeBackground)
                )
            }
        )
    }

    private fun decodeBackground(b: JSONObject): BackgroundRemoval? =
        b.optString("maskFile").takeIf { it.isNotBlank() }?.let { path ->
            BackgroundRemoval(
                maskFile = path,
                fill = enumOrNull<BackgroundFill>(b.optString("fill")) ?: BackgroundFill.Blur,
                colorArgb = b.optInt("colorArgb", 0xFF101828.toInt())
            )
        }

    private fun decodeKeyframe(json: JSONObject?): Keyframe? {
        if (json == null) return null
        return Keyframe(
            atMs = json.optLong("atMs"),
            transform = Transform(
                scale = json.optDouble("scale", 1.0).toFloat(),
                offsetXFraction = json.optDouble("offsetXFraction").toFloat(),
                offsetYFraction = json.optDouble("offsetYFraction").toFloat(),
                rotationDegrees = json.optDouble("rotationDegrees").toFloat()
            ),
            easing = enumOrNull<KeyframeEasing>(json.optString("easing")) ?: KeyframeEasing.Smooth
        )
    }

    private fun decodeText(json: JSONObject?): TextOverlayItem? {
        if (json == null) return null
        val motion = enumOrNull<TextMotion>(json.optString("motion")) ?: TextMotion.None
        val item = TextOverlayItem(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            text = json.optString("text"),
            startMs = json.optLong("startMs"),
            endMs = json.optLong("endMs"),
            colorArgb = json.optInt("colorArgb"),
            xFraction = json.optDouble("xFraction", 0.5).toFloat(),
            yFraction = json.optDouble("yFraction", 0.85).toFloat(),
            rotationDegrees = json.optDouble("rotation", 0.0).toFloat(),
            flipped = json.optBoolean("flipped", false),
            motion = motion,
            motionInMs = json.optLong("motionInMs", TextAnimation.DEFAULT_IN_MS),
            // Before a line had its own leaving, every arrival left by fading
            // and a still line did not; a draft from then keeps that.
            motionOut = enumOrNull<TextExit>(json.optString("motionOut"))
                ?: if (motion != TextMotion.None) TextExit.Fade else TextExit.None,
            motionOutMs = json.optLong("motionOutMs", TextAnimation.DEFAULT_OUT_MS),
            loop = enumOrNull<TextLoop>(json.optString("loop")) ?: TextLoop.None,
            loopMs = json.optLong("loopMs", TextAnimation.DEFAULT_LOOP_MS),
            wordStartsMs = json.optJSONArray("wordStarts")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty(),
            sticker = json.optBoolean("sticker", false),
            stripRow = json.optInt("stripRow", 0).coerceAtLeast(0),
            track = json.optJSONArray("track")?.let { array ->
                MotionTrack(
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { o ->
                            TrackSample(
                                atMs = o.optLong("atMs"),
                                xFraction = o.optDouble("x", 0.5).toFloat(),
                                yFraction = o.optDouble("y", 0.85).toFloat(),
                                scale = o.optDouble("scale", 1.0).toFloat(),
                                confidence = o.optDouble("confidence", 1.0).toFloat()
                            )
                        }
                    }
                ).takeIf { !it.isEmpty }
            }
        )
        return item.withStyle(TextStyleJson.read(json))
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        /**
         * Bump when the shape changes. Documents from [OLDEST_READABLE_VERSION] up
         * are still read; anything older is ignored rather than misread.
         *
         * 10: the hand-drawn crop, the beat grid, snapping and the stabilizer
         * strength, none of which survived a kill before.
         *
         * 11: a picture's "volume" is its own level, heard - on the main track
         * under the camera level, on an overlay alone. Before it the field was
         * written and never read; see [PER_CLIP_VOLUME_VERSION].
         *
         * 12: a clip's fades, its own voice effect (the edit-wide "voiceEffect"
         * moves onto the main-track shots) and a sound's beats in its file's
         * time; the beat grid names its sound and its density. In the same
         * version, a line's decorations are its own fields (see
         * [TextStyleJson]) rather than a named look, and it has a turn, an
         * opacity, a leaving and a loop. Older lines are read through their
         * look. The two arrived on separate branches (B9 and B10) that each
         * wrote 12 before they met, so a 12 may hold either half without the
         * other: both are told apart by their fields, not by this number.
         *
         * 13: a clip's own mirror and quarter turns, and the file a reversed
         * clip was rendered from (B11). Each is written only when set, and
         * read as unset when missing, so a 12 reads as before.
         */
        const val FORMAT_VERSION = 13
        const val OLDEST_READABLE_VERSION = 9

        /** The first version whose pictures' levels are their own; older ones are moved over on reading. */
        const val PER_CLIP_VOLUME_VERSION = 11
    }
}

/** A recovered edit, ready to be poured back into the editor. */
data class ProjectSnapshot(
    val sourceUri: Uri,
    val savedAtMillis: Long,
    val clipCount: Int,
    val clips: List<Clip>,
    val audioClips: List<Clip>,
    val textOverlays: List<TextOverlayItem>,
    val effects: List<TimedEffect>,
    val reframe: MotionTrack?,
    val markers: List<Long>,
    val playheadMs: Long,
    val outputP: Int,
    val fitToSize: Boolean,
    val targetSizeMb: Int,
    val audioOnly: Boolean,
    val muteOriginal: Boolean,
    val originalVolume: Float,
    val rotationDegrees: Int,
    val cropAspect: CropAspect,
    val cropRect: CropRect,
    val snapToMarkers: Boolean,
    val stabilizeStrength: Float,
    val beats: BeatProgress,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val lookId: String?,
    val lookIntensity: Float,
    val pixelsPerSecond: Float,
    /** What the project was named, if it was; see [EditorUiState.projectName]. */
    val name: String? = null
) {
    /**
     * How long the edit runs: where its last picture or sound ends, overlays
     * included - the same number as EditorUiState.trimmedDurationMs. It used to
     * be the clips' lengths added up, which counted an overlay on top of the
     * shots it sits over: the card offered 0:16 of edit for an 8 s video with an
     * 8 s overlay, a length the timeline could never show.
     */
    val totalDurationMs: Long
        get() = maxOf(clips.maxOfOrNull { it.timelineEndMs } ?: 0L, audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L)

    /**
     * A single untrimmed clip with nothing else on it - the state a freshly opened
     * file is already in. There is nothing here to recover.
     */
    val isTrivial: Boolean
        get() = clips.size == 1 && name == null &&
            textOverlays.isEmpty() &&
            effects.isEmpty() &&
            audioClips.isEmpty() &&
            markers.isEmpty() &&
            reframe == null &&
            // A look, a crop, a found beat or a changed voice is work too, as
            // much as a trim is.
            !beats.hasBeats &&
            lookId == null && brightness == 0f && contrast == 0f && saturation == 0f &&
            cropAspect == CropAspect.Original && cropRect.isFull && rotationDegrees == 0 &&
            !muteOriginal && originalVolume == 1f &&
            clips.first().let {
                it.sourceInMs == 0L && it.timelineStartMs == 0L && it.sourceOutMs >= it.sourceDurationMs &&
                    it.volume == 1f && it.voice == VoiceEffect.None && it.fadeInMs == 0L && it.fadeOutMs == 0L &&
                    it.chromaKey == null && it.mask == null && it.background == null &&
                    !it.mirrored && it.quarterTurns == 0 && it.reversedFrom == null &&
                    it.keyframes.isEmpty() && it.stabilizer.isEmpty() && it.speedRamp == com.squish.app.timeline.SpeedRamp()
            }
}

/**
 * A line's style as JSON: the fields of [TextStyleSpec], flat, beside the
 * line's own in a draft and on their own in a saved style. One reader for both,
 * so a style saved from a line reads back exactly as the line would.
 */
object TextStyleJson {
    fun write(json: JSONObject, s: TextStyleSpec) {
        json.put("font", s.font.name)
        s.fontFile?.let { json.put("fontFile", it) }
        json.put("bold", s.bold)
        json.put("italic", s.italic)
        json.put("underline", s.underline)
        json.put("align", s.align.name)
        json.put("letterSpacing", s.letterSpacing.toDouble())
        json.put("lineSpacing", s.lineSpacing.toDouble())
        json.put("colorArgb", s.colorArgb)
        json.put("sizeSp", s.sizeSp)
        json.put("strokeColor", s.stroke.colorArgb)
        json.put("strokeWidth", s.stroke.width.toDouble())
        json.put("shadowColor", s.shadow.colorArgb)
        json.put("shadowOpacity", s.shadow.opacity.toDouble())
        json.put("shadowBlur", s.shadow.blur.toDouble())
        json.put("shadowOffset", s.shadow.offset.toDouble())
        json.put("shadowAngle", s.shadow.angleDegrees.toDouble())
        json.put("bgColor", s.background.colorArgb)
        json.put("bgOpacity", s.background.opacity.toDouble())
        json.put("bgRadius", s.background.radius.toDouble())
        json.put("bubble", s.background.bubble.name)
        json.put("glow", s.glow)
        json.put("opacity", s.opacity.toDouble())
    }

    fun encode(s: TextStyleSpec): JSONObject = JSONObject().also { write(it, s) }

    fun read(json: JSONObject): TextStyleSpec {
        val defaults = TextStyleSpec()
        // Captions saved before styles existed were plain white letters.
        val font = enumOrNull<TextFont>(json.optString("font")) ?: TextFont.Sans
        val colour = json.optInt("colorArgb", defaults.colorArgb)
        val size = json.optInt("sizeSp", defaults.sizeSp)
        if (!json.has("glow")) {
            // Saved before a line's decorations were its own fields: the look named them.
            val look = enumOrNull<TextLook>(json.optString("look")) ?: TextLook.Plain
            return look.applied(TextStyleSpec(font = font, colorArgb = colour, sizeSp = size))
        }
        return TextStyleSpec(
            font = font,
            fontFile = json.optString("fontFile").takeIf { it.isNotBlank() },
            bold = json.optBoolean("bold", false),
            italic = json.optBoolean("italic", false),
            underline = json.optBoolean("underline", false),
            align = enumOrNull<TextAlign>(json.optString("align")) ?: TextAlign.Center,
            letterSpacing = json.optDouble("letterSpacing", 0.0).toFloat(),
            lineSpacing = json.optDouble("lineSpacing", 1.0).toFloat(),
            colorArgb = colour,
            sizeSp = size,
            stroke = TextStroke(
                json.optInt("strokeColor", TextStroke.NONE.colorArgb),
                json.optDouble("strokeWidth", 0.0).toFloat()
            ),
            shadow = TextShadow(
                json.optInt("shadowColor", TextShadow.NONE.colorArgb),
                json.optDouble("shadowOpacity", 0.0).toFloat(),
                json.optDouble("shadowBlur", TextShadow.NONE.blur.toDouble()).toFloat(),
                json.optDouble("shadowOffset", TextShadow.NONE.offset.toDouble()).toFloat(),
                json.optDouble("shadowAngle", TextShadow.NONE.angleDegrees.toDouble()).toFloat()
            ),
            background = TextBackground(
                json.optInt("bgColor", TextBackground.NONE.colorArgb),
                json.optDouble("bgOpacity", TextBackground.NONE.opacity.toDouble()).toFloat(),
                json.optDouble("bgRadius", TextBackground.NONE.radius.toDouble()).toFloat(),
                enumOrNull<TextBubble>(json.optString("bubble")) ?: TextBubble.None
            ),
            glow = json.optBoolean("glow", false),
            opacity = json.optDouble("opacity", 1.0).toFloat()
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
