package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.editor.AnnotationShape
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.BeatProgress
import com.squish.app.editor.CanvasBackground
import com.squish.app.editor.CanvasFill
import com.squish.app.editor.CaptionSource
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropAspect
import com.squish.app.editor.CropRatio
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.editor.CropRect
import com.squish.app.editor.CropRules
import com.squish.app.editor.EditorUiState
import com.squish.app.media.effects.Adjust
import com.squish.app.media.effects.AdjustField
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
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
import com.squish.app.media.video.FrameMotion
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.Segmenter
import com.squish.app.media.video.StabilizerMeasurement
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipAnimation
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskKey
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
    /** What the list shows: [name], or the first clip's name when there is none. */
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
    val earlierSavedAtMillis: Long? = null,
    /**
     * The file and the moment a cover frame is taken from - the first shot on
     * the main track, a little way in (ProjectRules.coverTimeMs) - so the grid
     * shows the edit rather than whatever the source's first frame is.
     */
    val coverUri: Uri? = null,
    val coverAtMs: Long = 0L,
    /**
     * The name the project was given, or null when it follows its first clip.
     * The rename dialog needs the two apart: the fallback prefilled as text
     * became the name on Save, and the project stopped following its file.
     */
    val name: String? = null,
    /**
     * Staged on disk (ProjectAutosave.stageStart) but never saved: the editor
     * was left, or killed, before its first save. Listed so the project is
     * not lost from the grid; it opens on its files and has no name, cover
     * frame or length yet.
     */
    val staged: Boolean = false,
    /** What the project keeps under the app's own storage; see [ProjectRules.ownedFile]. */
    val sizeBytes: Long = 0L,
    /** When it was started, which tells two same-day projects apart (ProjectRules.distinctTitles). */
    val createdAtMillis: Long = 0L
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

    private val appContext: Context = context.applicationContext

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    /** Where discarded drafts wait. One folder per discard, holding the slot's files. */
    private val trashDir = File(dir, "trash").apply { mkdirs() }

    /**
     * One draft per project, keyed by the project's own id
     * (EditorUiState.projectId, from ProjectRules.newId).
     *
     * It used to be keyed by the first video's URI - "the edit I was doing on
     * that clip" - which meant one project per video: a second cut of the same
     * footage could not exist, and reopening a clip from the dashboard asked
     * whether this was the old edit or a new one. Drafts written then are named
     * "p" and a hash; they are still read, since a slot is only a file name
     * and the id an old draft is opened by is whatever its file is called.
     */
    private fun liveFile(slot: String) = File(dir, "$slot.json")
    /**
     * The files a new project starts from, staged by whoever made it (the
     * dashboard's picker, "Open with", a quick tool) and taken by the editor
     * on its first open. On disk rather than handed over in memory so a kill
     * between the two still opens the project on its files. Not ".json", so
     * no listing mistakes it for a draft.
     */
    private fun startFile(slot: String) = File(dir, "$slot.start")
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
    /**
     * Everything a slot owns, for the bin and for a restore out of it.
     *
     * The `.start` file is in this list because a project staged and never
     * saved has *only* that file: left out, [delete] destroyed it in place,
     * [DraftFiles.moveToBin] found nothing to move and said so, and the
     * dashboard's Delete on a "not opened yet" card quietly removed the project
     * with no bin entry and no Undo. Worse, the files it named went with it:
     * nothing on disk named them any more, so the picker grants it held could
     * never be let go of, and the phone caps how many of those an app may keep.
     */
    private fun slotFiles(slot: String) =
        listOf(liveFile(slot), backupFile(slot), snapshotFile(slot), pendingSnapshotFile(slot), metaFile(slot), startFile(slot))

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
        val slot = state.projectId.ifBlank { return false }
        if (state.isLoadingSource) return false
        val live = liveFile(slot)

        // The signature is taken from the document alone. Stamping the time first
        // would make every tick look like a change and turn "save when something
        // moved" into "write to flash every 1.5 seconds, forever".
        val document = encode(state)
        val signature = document.toString()
        if (signature == lastSignature[slot]) return false

        val fingerprint = DraftHousekeeping.fingerprint(editKey(state))
        val previous = readMetaJson(metaFile(slot))
        // Only the playhead, the zoom or the like moved - opening a project
        // puts the playhead at 0:00 - and the edit is the one on disk: saved
        // with its old time, so a project looked at and left is not taken for
        // one just edited and moved to the top of the dashboard.
        val untouched = previous?.optString("editFingerprint") == fingerprint
        val now = if (untouched) previous?.optLong("savedAtMillis")?.takeIf { it > 0L } ?: System.currentTimeMillis()
        else System.currentTimeMillis()
        document.put("savedAtMillis", now)

        val ok = runCatching {
            // Nothing below touches the live file until the new version is safely
            // on disk beside it.
            FileOutputStream(scratchFile(slot)).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()          // on the platter, not just in the page cache
            }
            // The clock, not the kept save time: the rotation is about when it runs.
            val snapshots = if (untouched) SnapshotInfo(versionIn(previous, "snapshot"), versionIn(previous, "pending"))
            else rotateSnapshots(slot, previous, System.currentTimeMillis())
            if (live.exists()) live.copyTo(backupFile(slot), overwrite = true)
            // The sidecar goes first, atomically. It used to be a plain truncating
            // write after the rename, so a kill in the gap left a draft that was
            // whole on disk and missing from the list.
            writeMeta(slot, state, uri, now, fingerprint, previous, snapshots)
            DraftFiles.replace(scratchFile(slot), live)
            // The project has a draft now; what it started from is in it.
            startFile(slot).delete()
        }.isSuccess

        if (ok) lastSignature[slot] = signature
        ok
    }

    /**
     * Stages the files a new project is to be made from; see [startFile].
     * [copyIn] says they were handed over with a grant that ends with this
     * process, so the editor copies them into its own storage first.
     */
    fun stageStart(slot: String, uris: List<Uri>, copyIn: Boolean = false, openedFromOutside: Boolean = false) {
        synchronized(lock) {
            val json = JSONObject().apply {
                put("uris", JSONArray().apply { uris.forEach { put(it.toString()) } })
                put("copyIn", copyIn)
                put("openedFromOutside", openedFromOutside)
            }
            runCatching { DraftFiles.writeAtomically(File(dir, "$slot.start.tmp"), startFile(slot), json.toString().toByteArray()) }
        }
    }

    /** The staged start of a project that has no draft yet, if there is one. Left in place until the first save. */
    fun peekStart(slot: String): ProjectStart? = synchronized(lock) {
        val file = startFile(slot)
        if (!file.exists()) return null
        runCatching {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("uris") ?: return null
            ProjectStart(
                uris = (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }.map(Uri::parse),
                copyIn = json.optBoolean("copyIn", false),
                openedFromOutside = json.optBoolean("openedFromOutside", false)
            )
        }.getOrNull()
    }

    /** The staged start as raw JSON, for [save] to read one field off it. */
    private fun readStartJson(slot: String): JSONObject? {
        val file = startFile(slot)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /**
     * Whether [slot] was opened from another app and has not been edited since
     * - a look, not a project. Survives the process, unlike the field the
     * editor used to keep it in; see the sidecar's "openedFromOutside".
     */
    fun openedJustToLook(slot: String): Boolean = synchronized(lock) {
        readMetaJson(metaFile(slot))?.optBoolean("openedFromOutside", false)
            ?: readStartJson(slot)?.optBoolean("openedFromOutside", false)
            ?: false
    }

    /**
     * Gives the project a name, or with null takes it away. Written into the
     * draft as a save writes it, and into the sidecar the list reads; an
     * editor open on the project is never the caller - the dashboard is
     * always under it - so nothing is writing the slot at the same time.
     */
    fun rename(slot: String, name: String?): Boolean = synchronized(lock) {
        val live = liveFile(slot)
        val json = runCatching { JSONObject(live.readText()) }.getOrNull() ?: return false
        if (name == null) json.remove("name") else json.put("name", name)
        val meta = readMetaJson(metaFile(slot)) ?: return false
        meta.put("title", name ?: json.optJSONArray("clips")?.optJSONObject(0)?.optString("label")?.takeIf { it.isNotBlank() } ?: "Untitled edit")
        if (name == null) meta.remove("name") else meta.put("name", name)
        meta.put("editFingerprint", DraftHousekeeping.fingerprint(editKeyOf(JSONObject(json.toString()))))
        runCatching {
            DraftFiles.writeAtomically(scratchFile(slot), live, json.toString().toByteArray())
            DraftFiles.writeAtomically(metaScratchFile(slot), metaFile(slot), meta.toString().toByteArray())
        }.onSuccess { lastSignature.remove(slot) }.isSuccess
    }

    /**
     * A second project with the same edit in it, named after the first
     * (ProjectRules.copyName), never exported, and its own from here on. The
     * files the edit made for itself - stills, renders, takes - are shared by
     * name; nothing here deletes those, so sharing costs nothing.
     */
    fun duplicate(slot: String, taken: Collection<String>): String? = synchronized(lock) {
        val live = liveFile(slot)
        val json = runCatching { JSONObject(live.readText()) }.getOrNull() ?: return null
        val meta = readMetaJson(metaFile(slot)) ?: return null
        val copy = ProjectRules.newId()
        // After the name on its card: an unnamed project's title is its first
        // file's, and a copy named after that read "1001319364.mp4 copy".
        val shown = json.optString("name").takeIf { it.isNotBlank() }
            ?: meta.optString("name").takeIf { it.isNotBlank() }
            ?: ProjectRules.displayTitle(
                meta.optString("title"),
                meta.optLong("createdAtMillis").takeIf { it > 0L } ?: meta.optLong("savedAtMillis")
            )
        val name = ProjectRules.copyName(shown, taken)
        json.put("name", name)
        val now = System.currentTimeMillis()
        json.put("savedAtMillis", now)
        meta.put("id", copy)
        meta.put("title", name)
        meta.put("name", name)
        meta.put("savedAtMillis", now)
        meta.put("editFingerprint", DraftHousekeeping.fingerprint(editKeyOf(JSONObject(json.toString()))))
        listOf("exportedAtMillis", "exportedFingerprint", "snapshotSavedAtMillis", "snapshotFingerprint", "pendingSavedAtMillis", "pendingFingerprint")
            .forEach { meta.remove(it) }
        val placed = runCatching {
            DraftFiles.writeAtomically(metaScratchFile(copy), metaFile(copy), meta.toString().toByteArray())
            DraftFiles.writeAtomically(scratchFile(copy), liveFile(copy), json.toString().toByteArray())
        }.isSuccess
        if (placed) copy else {
            slotFiles(copy).forEach { it.delete() }
            null
        }
    }

    /**
     * Every media URI a draft on disk names - live, backup, snapshot or in the
     * bin - read off the documents as text, as [referencedMaskFiles] is: what
     * a purge may let go of is decided against this (ProjectRules.releasable).
     */
    fun referencedUris(): Set<String> = synchronized(lock) { urisUnder(dir) }

    /** The media URIs a bin entry's documents name. */
    fun urisInTrash(trashId: String): Set<String> = synchronized(lock) {
        val entry = File(trashDir, trashId)
        if (DraftHousekeeping.parseTrashName(trashId) == null || !entry.isDirectory) emptySet() else urisUnder(entry)
    }

    private fun urisUnder(root: File): Set<String> {
        val found = HashSet<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            URI_FIELD.findAll(text).forEach { match ->
                val value = match.groupValues[1].replace("\\/", "/").replace("\\\\", "\\")
                if (value.isNotBlank() && value != "null") found += value
            }
        }
        // A project staged but not yet saved (stageStart) names its files too:
        // a purge that let go of a grant a staged project was about to open
        // opened it on an unreadable file.
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".start") }.forEach { file ->
            val array = runCatching { JSONObject(file.readText()).optJSONArray("uris") }.getOrNull() ?: return@forEach
            (0 until array.length()).mapNotNullTo(found) { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }
        return found
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
        DraftHousekeeping.NOT_THE_EDIT.forEach { remove(it) }
    }.toString()

    /**
     * The project's saved edit, if it has one. The snapshots are the last
     * resort, behind the live file and its backup.
     */
    fun peek(slot: String): ProjectSnapshot? = synchronized(lock) {
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
            val saved = files.filter { it.isFile && it.name.endsWith(".json") && !it.name.contains(".tmp.") }
                .filter { !it.name.endsWith(".bak.json") && !it.name.endsWith(".snap.json") && !it.name.endsWith(".meta.json") }
                .mapNotNull { live ->
                    val slot = live.name.removeSuffix(".json")
                    summaryOf(slot, live, metaFile(slot))?.let { summary ->
                        summary.copy(earlierSavedAtMillis = earlierOf(slot, summary)?.second?.savedAtMillis)
                    }
                }
            // A project staged and not yet saved is a project too: ten photos
            // take a while to render, and Back before the first save used to
            // leave nothing on the grid and a start file nothing listed.
            val staged = files.filter { it.isFile && it.name.endsWith(".start") }.mapNotNull { file ->
                val slot = file.name.removeSuffix(".start")
                if (liveFile(slot).exists()) return@mapNotNull null
                val start = peekStart(slot) ?: return@mapNotNull null
                val first = start.uris.firstOrNull() ?: return@mapNotNull null
                DraftSummary(
                    id = slot,
                    title = "New project",
                    sourceUri = first,
                    durationMs = 0L,
                    clipCount = start.uris.size,
                    savedAtMillis = file.lastModified(),
                    coverUri = first,
                    staged = true
                )
            }
            val all = (saved + staged).sortedByDescending { it.savedAtMillis }
            val titles = ProjectRules.distinctTitles(all.map { Triple(it.title, it.name != null, it.createdAtMillis) })
            all.mapIndexed { i, d -> if (titles[i] == d.title) d else d.copy(title = titles[i]) }
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
            editFingerprint = DraftHousekeeping.fingerprint(editKeyOf(json)),
            name = snapshot.name
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
        // What the sidecar carries and the draft does not, read before the bin
        // move takes the file away: the export stamp and the day the project
        // was started live only here, and [writeMeta] carries them forward
        // through every ordinary save. Written fresh, "Earlier version" wiped
        // the export badge off a project for good - the next save then read the
        // stamp from this sidecar, which no longer had one - and reset the
        // project's start to the snapshot's day, which is the date its card
        // shows when it has no name of its own.
        val keep = runCatching { JSONObject(metaFile(slot).readText()) }.getOrNull()
        val binned = delete(slot) ?: return null
        val placed = runCatching {
            // The sidecar first, as a save writes it.
            val meta = JSONObject().apply {
                put("id", slot)
                keep?.optLong("createdAtMillis", 0L)?.takeIf { it > 0L }?.let { put("createdAtMillis", it) }
                keep?.optLong("exportedAtMillis", 0L)?.takeIf { it > 0L }?.let { put("exportedAtMillis", it) }
                keep?.optString("exportedFingerprint")?.takeIf { it.isNotBlank() }?.let { put("exportedFingerprint", it) }
                put("title", earlier.name ?: earlier.clips.firstOrNull()?.label ?: "Untitled edit")
                earlier.name?.let { put("name", it) }
                put("uri", earlier.sourceUri.toString())
                put("durationMs", earlier.totalDurationMs)
                put("clipCount", earlier.clipCount)
                put("savedAtMillis", earlier.savedAtMillis.takeIf { it > 0L } ?: version.savedAtMillis)
                put("editFingerprint", DraftHousekeeping.fingerprint(editKeyOf(JSONObject(text))))
                val (coverUri, coverAt, owned) = coverAndOwned(earlier.clips, earlier.audioClips, earlier.sourceUri)
                putCover(coverUri, coverAt, owned + text.toByteArray().size)
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
        // The three half-written scratch files go; the `.start` does not - it is
        // in [slotFiles] now and goes into the bin with the rest, which is what
        // keeps a staged project restorable and its files still named while it
        // is in there.
        scratchFile(slot).delete()
        snapshotScratchFile(slot).delete()
        metaScratchFile(slot).delete()
        DraftFiles.moveToBin(trashDir, slot, slotFiles(slot))
    }

    /**
     * Stamps the draft as exported: when, and which edit - [rendered] is the
     * state the file was made from, so a change made while it rendered still
     * reads as "edited since". The draft stays where it is, exported and all:
     * the work reached the gallery, and the commonest thing to want next is one
     * more change to it.
     */
    fun markCompleted(rendered: EditorUiState) {
        val slot = rendered.projectId.ifBlank { return }
        val fingerprint = DraftHousekeeping.fingerprint(editKey(rendered))
        synchronized(lock) {
            if (!liveFile(slot).exists()) return
            val existing = readMetaJson(metaFile(slot)) ?: return
            existing.put("exportedAtMillis", System.currentTimeMillis())
            existing.put("exportedFingerprint", fingerprint)
            runCatching {
                DraftFiles.writeAtomically(metaScratchFile(slot), metaFile(slot), existing.toString().toByteArray())
            }
        }
    }

    /**
     * Takes out everything past its month, and says which files those entries
     * were the last thing to name.
     *
     * Separate from [trashed] so the caller can let go of the read grants, the
     * way it does after a Delete forever. The expiry used to happen inside the
     * listing, which has no way to release anything - so a project binned and
     * left to age out held its picker grant until the app was uninstalled, and
     * the phone caps how many of those an app may keep.
     */
    fun expireOldTrash(): Set<String> = synchronized(lock) {
        runCatching {
            val now = System.currentTimeMillis()
            val entries: Array<File> = trashDir.listFiles() ?: return@runCatching emptySet()
            val released = HashSet<String>()
            entries.filter { it.isDirectory }.forEach { entry ->
                val (_, at) = DraftHousekeeping.parseTrashName(entry.name) ?: return@forEach
                if (DraftHousekeeping.isExpired(at, now)) {
                    released += urisUnder(entry)
                    entry.deleteRecursively()
                }
            }
            released
        }.getOrDefault(emptySet())
    }

    /** Everything in the bin, newest first. Anything past its month is taken out by [expireOldTrash]. */
    fun trashed(): List<TrashedDraft> = synchronized(lock) {
        runCatching {
            val entries: Array<File> = trashDir.listFiles() ?: return@runCatching emptyList()
            entries.filter { it.isDirectory }.mapNotNull { entry ->
                val (slot, at) = DraftHousekeeping.parseTrashName(entry.name) ?: return@mapNotNull null
                val live = File(entry, liveFile(slot).name)
                val summary = summaryOf(slot, live, File(entry, metaFile(slot).name))
                    ?: read(File(entry, backupFile(slot).name))?.let { s ->
                        DraftSummary(slot, s.name ?: s.clips.firstOrNull()?.label ?: "Untitled edit", s.sourceUri, s.totalDurationMs, s.clipCount, s.savedAtMillis, name = s.name)
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

    /**
     * Every person-mask file a draft on disk still names - live, backup,
     * snapshot or in the bin - so the segmenter's folder can be swept of the
     * rest (Segmenter.sweep, V19). Read off the documents as text rather than
     * decoded: a draft too old or too broken to decode still holds its
     * references, and sweeping what it names would break it further.
     */
    fun referencedMaskFiles(): Set<String> = synchronized(lock) {
        val found = HashSet<String>()
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            MASK_FILE.findAll(text).forEach { match ->
                // org.json writes a path's slashes escaped.
                found += match.groupValues[1].replace("\\/", "/").replace("\\\\", "\\")
            }
        }
        found
    }

    /**
     * Removes a bin entry for good. Only ever from a confirmed tap on the list.
     * The person masks it alone named go with it: a binned draft keeps its
     * masks, so putting it back brings its cut-out back too; purged, nothing
     * can want them.
     */
    fun purge(trashId: String) {
        synchronized(lock) {
            val entry = File(trashDir, trashId)
            if (DraftHousekeeping.parseTrashName(trashId) != null && entry.isDirectory) entry.deleteRecursively()
        }
        runCatching { Segmenter.sweep(appContext, referencedMaskFiles()) }
    }

    /**
     * The cover and the size a sidecar carries: the first shot on the main
     * track as it stands, a little way in, and what the edit keeps under the
     * app (its owned stills, freezes and recordings). Shared by a save and by
     * "Earlier version", which used to write a sidecar without them - the card
     * then showed the source's first frame and no size until the next save.
     */
    private fun coverAndOwned(clips: List<Clip>, audio: List<Clip>, fallback: Uri): Triple<Uri, Long, Long> {
        val lead = clips.filter { it.isMain }.minByOrNull { it.timelineStartMs }
        val coverUri = lead?.uri ?: fallback
        val coverAt = lead?.let { ProjectRules.coverTimeMs(it.sourceInMs, it.sourceOutMs) } ?: 0L
        val filesDir = appContext.filesDir.absolutePath
        val owned = (clips + audio).mapNotNull { it.uri?.toString() }.distinct()
            .filter { ProjectRules.ownedFile(it, filesDir) }
            .sumOf { File(Uri.parse(it).path.orEmpty()).length() }
        return Triple(coverUri, coverAt, owned)
    }

    /**
     * A cover the app can still read, when the one the sidecar names cannot be:
     * a gallery video opened through "Open with" is read on that grant alone
     * (the app holds no video permission), and once it lapses the card was a
     * blank clapper although the project's own photos, freezes and other shots
     * were all there. The first readable shot on the main track, then any clip.
     */
    fun readableCover(slot: String): Pair<Uri, Long>? = runCatching {
        val snapshot = decode(JSONObject(liveFile(slot).readText())) ?: return null
        val shots = snapshot.clips.sortedWith(compareBy({ !it.isMain }, { it.timelineStartMs }))
        shots.firstNotNullOfOrNull { clip ->
            val uri = clip.uri ?: return@firstNotNullOfOrNull null
            val readable = runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (readable) uri to ProjectRules.coverTimeMs(clip.sourceInMs, clip.sourceOutMs) else null
        }
    }.getOrNull()

    private fun JSONObject.putCover(coverUri: Uri, coverAtMs: Long, sizeBytes: Long) {
        put("coverUri", coverUri.toString())
        put("coverAtMs", coverAtMs)
        put("sizeBytes", sizeBytes)
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
        val (coverUri, coverAt, owned) = coverAndOwned(state.videoClips, state.audioClips, uri)
        val json = JSONObject().apply {
            put("id", slot)
            put("title", state.projectName ?: state.videoClips.firstOrNull()?.label ?: "Untitled edit")
            state.projectName?.let { put("name", it) }
            put("uri", uri.toString())
            put("durationMs", state.trimmedDurationMs)
            put("clipCount", state.videoClips.size)
            put("savedAtMillis", savedAtMillis)
            // When the project was started, for the name an unnamed one is shown by.
            put("createdAtMillis", previous?.optLong("createdAtMillis", 0L)?.takeIf { it > 0L } ?: savedAtMillis)
            put("editFingerprint", fingerprint)
            // "Opened from another app just to look", kept across a process
            // death. Its only record used to be the .start file that this very
            // save deletes - and loadFresh saves - so a look closed by the
            // process going rather than by a back press (swiping the app off
            // recents does exactly that) stayed on the grid for good as a
            // project nobody made, holding a picker grant that nothing could
            // release, because a draft names the file. Dropped the moment the
            // edit differs from the one the project was opened with: after a
            // real edit it is a project, whatever it was opened by.
            val startedOutside = previous?.optBoolean("openedFromOutside", false)
                ?: readStartJson(slot)?.optBoolean("openedFromOutside", false)
                ?: false
            val startFingerprint = previous?.optString("startFingerprint")
                ?.takeIf { it.isNotBlank() } ?: fingerprint
            if (startedOutside && fingerprint == startFingerprint) {
                put("openedFromOutside", true)
                put("startFingerprint", startFingerprint)
            }
            putCover(coverUri, coverAt, owned + scratchFile(slot).length())
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

    /** When [slot] was started - its first save - or 0 for one not saved yet. */
    fun startedAt(slot: String): Long = synchronized(lock) {
        val meta = readMetaJson(metaFile(slot)) ?: return 0L
        meta.optLong("createdAtMillis", 0L).takeIf { it > 0L } ?: meta.optLong("savedAtMillis", 0L)
    }

    private fun readMetaJson(file: File): JSONObject? =
        if (!file.exists()) null else runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun readMeta(file: File): DraftSummary? = runCatching {
        val json = readMetaJson(file) ?: return null
        val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
        DraftSummary(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            // Named projects show their name; the rest a title made from the
            // first clip, or the day they were started (ProjectRules.displayTitle).
            title = json.optString("name").takeIf { it.isNotBlank() }
                ?: ProjectRules.displayTitle(json.optString("title"), json.optLong("createdAtMillis").takeIf { it > 0L } ?: json.optLong("savedAtMillis")),
            sourceUri = Uri.parse(uri),
            durationMs = json.optLong("durationMs"),
            clipCount = json.optInt("clipCount", 1),
            savedAtMillis = json.optLong("savedAtMillis"),
            exportedAtMillis = json.optLong("exportedAtMillis", 0L).takeIf { it > 0L },
            editFingerprint = json.optString("editFingerprint").takeIf { it.isNotBlank() },
            exportedFingerprint = json.optString("exportedFingerprint").takeIf { it.isNotBlank() },
            // A sidecar from before covers were named falls back to the source.
            coverUri = json.optString("coverUri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Uri.parse(uri),
            coverAtMs = json.optLong("coverAtMs", 0L),
            name = json.optString("name").takeIf { it.isNotBlank() },
            createdAtMillis = json.optLong("createdAtMillis").takeIf { it > 0L } ?: json.optLong("savedAtMillis"),
            sizeBytes = json.optLong("sizeBytes", 0L)
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
        // The sheet's other choices. Written under their own names: "quality"
        // is the old Small / Medium / High field, still read for drafts from
        // before sizes existed.
        put("outputFps", state.outputFps)
        put("exportQuality", state.quality.name)
        put("hevc", state.hevc)
        put("keepHdr", state.keepHdr)
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
        // What auto-captions listen to, and in which language. Neither was
        // written, so both went back to their defaults every time a project was
        // reopened: a second run on the same edit listened to the camera again
        // however the Captions panel had been set, and Read aloud spoke in the
        // phone's language rather than the one chosen. Silent, because the
        // panel reads the state and the state had just been rebuilt.
        //
        // Written only when they are not the defaults, so a draft saved before
        // this keeps the edit key it had and is not read as changed.
        if (state.captionSource != CaptionSource.Camera) put("captionSource", state.captionSource.name)
        state.captionLanguage?.let { put("captionLanguage", it) }
        // Only when there is one, so every draft saved before the canvas had a
        // background keeps the edit key it had.
        if (state.canvasBackground != CanvasBackground.NONE) {
            put("canvasFill", state.canvasBackground.fill.name)
            put("canvasColour", state.canvasBackground.colorArgb)
            state.canvasBackground.imageUri?.let { put("canvasImage", it) }
        }
        put("pixelsPerSecond", state.pixelsPerSecond.toDouble())
        put("markers", JSONArray().apply { state.markers.forEach { put(it) } })
        // The grid on screen, whatever the last listen did. A second listen still
        // running, or one that failed, keeps the grid already found - the panel
        // says so - and writing it only after a listen that succeeded left it out
        // of the draft, and out of the edit key, so a failed listen could even
        // make the edit read as undone back to the bare clip and bin the draft.
        // `worthSaving`, not `hasBeats`: one beat tapped with "Add beat" is
        // drawn on the ruler and snapped to, and under hasBeats - which wants
        // two before it calls it a grid - it went missing on every save, taking
        // the density and the downbeat with it.
        if (state.beats.worthSaving) {
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
        put("effects", JSONArray().apply {
            state.effects.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind.name)
                    put("startMs", e.startMs)
                    put("endMs", e.endMs)
                    put("intensity", e.intensity.toDouble())
                    put("amount", e.amount.toDouble())
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
        if (clip.muted) put("muted", true)
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
        // The measurement as columns rather than an object per frame: a long
        // shot has thousands, and this is written on every autosave.
        clip.stabilizerMeasurement?.takeIf { !it.isEmpty }?.let { m ->
            put("stabilizerMeasurement", JSONObject().apply {
                put("width", m.analysisWidth)
                put("height", m.analysisHeight)
                put("times", JSONArray(m.timesMs))
                put("dx", JSONArray().apply { m.motions.forEach { put(it.dx.toDouble()) } })
                put("dy", JSONArray().apply { m.motions.forEach { put(it.dy.toDouble()) } })
                put("rotation", JSONArray().apply { m.motions.forEach { put(it.rotationDegrees.toDouble()) } })
                put("confidence", JSONArray().apply { m.motions.forEach { put(it.confidence.toDouble()) } })
            })
        }
        clip.stabilizeStrength?.let { put("stabilizeStrength", it.toDouble()) }
        if (clip.opacityKeys.isNotEmpty()) put("opacityKeys", JSONArray().apply { clip.opacityKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.blend != com.squish.app.timeline.LayerBlend.Normal) put("blend", clip.blend.name)
        if (clip.lookKeys.isNotEmpty()) put("lookKeys", JSONArray().apply { clip.lookKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.volumeKeys.isNotEmpty()) put("volumeKeys", JSONArray().apply { clip.volumeKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.arrival != ClipArrival.None) put("arrival", clip.arrival.name)
        if (clip.leaving != ClipLeaving.None) put("leaving", clip.leaving.name)
        if (clip.loop != ClipLoop.None) put("loop", clip.loop.name)
        put("arrivalMs", clip.arrivalMs)
        put("leavingMs", clip.leavingMs)
        put("loopMs", clip.loopMs)
        if (clip.frameBlend) put("frameBlend", true)
        if (clip.pitchFollowsSpeed) put("pitchFollowsSpeed", true)
        clip.mask?.let { m ->
            put("mask", JSONObject().apply {
                putMaskShape(m)
                // The shape keyed. Written only when it is, so a mask that
                // stands still reads exactly as it always did.
                if (m.keys.isNotEmpty()) {
                    put("keys", JSONArray().apply {
                        m.keys.forEach { key ->
                            put(JSONObject().apply {
                                put("atMs", key.atMs)
                                put("easing", key.easing.name)
                                putMaskShape(key.mask)
                            })
                        }
                    })
                }
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
        // Each only when it means something, so a clip with none keeps the
        // document it had, and its edit key with it.
        clip.lookId?.let { put("lookId", it) }
        if (clip.lookIntensity != 1f) put("lookIntensity", clip.lookIntensity.toDouble())
        if (!clip.adjust.isIdentity) put("adjust", encodeAdjust(clip.adjust))
        // A crop that only holds a shape chip - the window still the whole
        // frame - is kept too: the chip is what the next drag of a corner is
        // held to, and it read Free after a reload.
        clip.crop?.takeIf { !it.isIdentity || it.ratio != CropRatio.Free }?.let { crop ->
            put("crop", JSONObject().apply {
                put("left", crop.rect.left.toDouble())
                put("top", crop.rect.top.toDouble())
                put("right", crop.rect.right.toDouble())
                put("bottom", crop.rect.bottom.toDouble())
                put("straighten", crop.straightenDegrees.toDouble())
                put("flipH", crop.flipHorizontal)
                put("flipV", crop.flipVertical)
                put("ratio", crop.ratio.name)
            })
        }
        clip.reframe?.let { put("reframe", encodeTrack(it)) }
    }

    private fun encodeAdjust(adjust: Adjust): JSONObject = JSONObject().apply {
        AdjustField.entries.forEach { field ->
            val v = field.of(adjust)
            if (v != 0f) put(field.name, v.toDouble())
        }
        // The LUT by name only - the cube itself is a megabyte and lives under
        // files/luts/, read back by LutStore when the project opens.
        adjust.lutFile?.let {
            put("lut", it)
            put("lutStrength", adjust.lutStrength.toDouble())
        }
        // Only the channels that were drawn on, and only their points: a curve
        // is four straight lines until someone moves one.
        if (!adjust.curve.isIdentity) {
            put("curve", JSONObject().apply {
                listOf("m" to adjust.curve.master, "r" to adjust.curve.red, "g" to adjust.curve.green, "b" to adjust.curve.blue)
                    .forEach { (key, curve) ->
                        if (!curve.isIdentity) put(key, JSONArray().apply {
                            curve.points.forEach { p -> put(JSONArray().apply { put(p.x.toDouble()); put(p.y.toDouble()) }) }
                        })
                    }
            })
        }
        // The three wheels, written only when one has been moved - so a draft
        // of a graded shot that never touched them reads exactly as it did.
        if (!adjust.wheels.isIdentity) {
            put("wheels", JSONObject().apply {
                listOf("lift" to adjust.wheels.lift, "gamma" to adjust.wheels.gamma, "gain" to adjust.wheels.gain)
                    .forEach { (key, wheel) ->
                        if (!wheel.isIdentity) put(key, JSONArray().apply {
                            put(wheel.r.toDouble()); put(wheel.g.toDouble()); put(wheel.b.toDouble())
                        })
                    }
            })
        }
        if (adjust.hsl.any { !it.isIdentity }) {
            put("hsl", JSONArray().apply {
                adjust.hsl.forEach { band ->
                    put(JSONObject().apply {
                        put("h", band.hue.toDouble())
                        put("s", band.saturation.toDouble())
                        put("l", band.luminance.toDouble())
                    })
                }
            })
        }
    }

    private fun decodeAdjust(json: JSONObject?): Adjust {
        if (json == null) return Adjust.NONE
        var adjust = Adjust()
        AdjustField.entries.forEach { field ->
            if (json.has(field.name)) adjust = field.set(adjust, json.optDouble(field.name, 0.0).toFloat())
        }
        json.optString("lut").takeIf { it.isNotEmpty() }?.let { name ->
            adjust = adjust.copy(
                lutFile = name,
                lutStrength = json.optDouble("lutStrength", 1.0).toFloat().coerceIn(0f, 1f)
            )
        }
        json.optJSONObject("curve")?.let { c ->
            fun curveOf(key: String): com.squish.app.media.effects.Curve {
                val array = c.optJSONArray(key) ?: return com.squish.app.media.effects.Curve()
                val points = (0 until array.length()).mapNotNull { i ->
                    array.optJSONArray(i)?.let { p ->
                        com.squish.app.media.effects.CurvePoint(
                            p.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f),
                            p.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f)
                        )
                    }
                }
                // A curve written with fewer than two points cannot be drawn;
                // a straight one is the honest reading of it. Sorted on the way
                // in, so what is stored is canonical from the next save on -
                // everything that reads a curve reads it through Curve.ordered,
                // but a file is a file and this is where it stops mattering.
                return if (points.size >= 2) com.squish.app.media.effects.Curve(points.sortedBy { it.x })
                else com.squish.app.media.effects.Curve()
            }
            adjust = adjust.copy(curve = com.squish.app.media.effects.ToneCurve(
                master = curveOf("m"), red = curveOf("r"), green = curveOf("g"), blue = curveOf("b")
            ))
        }
        json.optJSONObject("wheels")?.let { w ->
            fun wheelOf(key: String): com.squish.app.media.effects.Wheel {
                val a = w.optJSONArray(key) ?: return com.squish.app.media.effects.Wheel.NONE
                return com.squish.app.media.effects.Wheel(
                    a.optDouble(0, 0.0).toFloat().coerceIn(-1f, 1f),
                    a.optDouble(1, 0.0).toFloat().coerceIn(-1f, 1f),
                    a.optDouble(2, 0.0).toFloat().coerceIn(-1f, 1f)
                )
            }
            adjust = adjust.copy(
                wheels = com.squish.app.media.effects.ColorWheels(
                    lift = wheelOf("lift"), gamma = wheelOf("gamma"), gain = wheelOf("gain")
                )
            )
        }
        val bands = json.optJSONArray("hsl")?.let { array ->
            List(HueBand.entries.size) { i ->
                array.optJSONObject(i)?.let { o ->
                    HslBand(
                        hue = o.optDouble("h", 0.0).toFloat().coerceIn(-1f, 1f),
                        saturation = o.optDouble("s", 0.0).toFloat().coerceIn(-1f, 1f),
                        luminance = o.optDouble("l", 0.0).toFloat().coerceIn(-1f, 1f)
                    )
                } ?: HslBand()
            }
        }
        return if (bands != null) adjust.copy(hsl = bands) else adjust
    }

    private fun encodeTrack(track: MotionTrack): JSONArray = JSONArray().apply {
        track.samples.forEach { s ->
            put(JSONObject().apply {
                put("atMs", s.atMs)
                put("x", s.xFraction.toDouble())
                put("y", s.yFraction.toDouble())
                put("scale", s.scale.toDouble())
                put("confidence", s.confidence.toDouble())
            })
        }
    }

    private fun decodeTrack(array: JSONArray?): MotionTrack? {
        if (array == null) return null
        return MotionTrack(
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

    private fun encodeKeyframe(key: Keyframe): JSONObject = JSONObject().apply {
        put("atMs", key.atMs)
        put("scale", key.transform.scale.toDouble())
        put("offsetXFraction", key.transform.offsetXFraction.toDouble())
        put("offsetYFraction", key.transform.offsetYFraction.toDouble())
        put("rotationDegrees", key.transform.rotationDegrees.toDouble())
        put("easing", key.easing.name)
    }

    private fun encodeValueKey(key: ValueKey): JSONObject = JSONObject().apply {
        put("atMs", key.atMs)
        put("value", key.value.toDouble())
        put("easing", key.easing.name)
    }

    private fun decodeValueKeys(array: JSONArray?): List<ValueKey> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ValueKey(
                atMs = o.optLong("atMs"),
                value = o.optDouble("value", 1.0).toFloat(),
                easing = enumOrNull<KeyframeEasing>(o.optString("easing")) ?: KeyframeEasing.Smooth
            )
        }.sortedBy { it.atMs }
    }

    private fun decodeMeasurement(json: JSONObject?): StabilizerMeasurement? {
        if (json == null) return null
        val times = json.optJSONArray("times") ?: return null
        val dx = json.optJSONArray("dx") ?: return null
        val dy = json.optJSONArray("dy") ?: return null
        val rotation = json.optJSONArray("rotation") ?: return null
        val confidence = json.optJSONArray("confidence") ?: return null
        val n = minOf(times.length(), dx.length(), dy.length(), rotation.length(), confidence.length())
        val measurement = StabilizerMeasurement(
            analysisWidth = json.optInt("width"),
            analysisHeight = json.optInt("height"),
            timesMs = (0 until n).map { times.optLong(it) },
            motions = (0 until n).map {
                FrameMotion(
                    dx = dx.optDouble(it).toFloat(),
                    dy = dy.optDouble(it).toFloat(),
                    rotationDegrees = rotation.optDouble(it).toFloat(),
                    confidence = confidence.optDouble(it, 1.0).toFloat()
                )
            }
        )
        return measurement.takeIf { !it.isEmpty }
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
        // Written only when it is one, so a draft of words is the shape it
        // always was and an older build reading this one simply finds no shape.
        if (item.isShape) {
            put("shape", item.shape.name)
            put("shapeAspect", item.shapeAspect.toDouble())
            put("shapeFilled", item.shapeFilled)
        }
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

        // An empty "clips" is a real edit: the last shot can be deleted (the
        // main track then offers Add media), or only sounds and text left.
        // What is refused is a file with no list at all, or a list whose
        // entries all failed to read - that is damage, and the backup is
        // the better answer. Refusing the empty list brought a deleted shot
        // back from the backup, or lost the project once both were empty.
        val savedArray = json.optJSONArray("clips") ?: return null
        val saved = (0 until savedArray.length()).mapNotNull { i -> decodeClip(savedArray.optJSONObject(i), ClipKind.Video) }
        if (!DraftHousekeeping.clipListReadable(savedArray.length(), saved.size)) return null
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
        val voiced = if (legacyVoice != VoiceEffect.None) {
            levelled.map { if (it.isOverlay) it else it.copy(voice = legacyVoice) }
        } else levelled
        // Saved when the look and the colour sliders were one setting for the
        // edit: they go onto every main-track shot, which is what they graded.
        // Told by the fields, like the voice: a draft is only ever read once
        // this way, since the next save writes them on the clips. Brightness
        // was a gain then and is an offset now, so the number is converted
        // to the offset that leaves the midtones where they were (see
        // Adjust.brightnessFromLegacyGain) rather than read as it stands.
        val legacyLook = json.optString("lookId").takeIf { it.isNotBlank() && it != "null" }
        val legacyAdjust = Adjust(
            brightness = Adjust.brightnessFromLegacyGain(json.optDouble("brightness", 0.0).toFloat().coerceIn(-1f, 1f)),
            contrast = json.optDouble("contrast", 0.0).toFloat().coerceIn(-1f, 1f),
            saturation = json.optDouble("saturation", 0.0).toFloat().coerceIn(-1f, 1f)
        )
        val graded = if (legacyLook == null && legacyAdjust.isIdentity) voiced else voiced.map { clip ->
            if (clip.isOverlay) clip
            else clip.copy(
                lookId = legacyLook,
                lookIntensity = json.optDouble("lookIntensity", 1.0).toFloat().coerceIn(0f, 1f),
                adjust = legacyAdjust
            )
        }
        // And the one reframe track the edit carried, measured on its first file:
        // onto the shots of that file, whose source clock it is in.
        val legacyReframe = decodeTrack(json.optJSONArray("reframe"))
        val clips = if (legacyReframe == null) graded else {
            val firstUri = graded.firstOrNull { !it.isOverlay }?.uri
            graded.map { if (!it.isOverlay && it.uri == firstUri && it.reframe == null) it.copy(reframe = legacyReframe) else it }
        }

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
            canvasBackground = enumOrNull<CanvasFill>(json.optString("canvasFill"))?.let { fill ->
                CanvasBackground(
                    fill = fill,
                    colorArgb = json.optInt("canvasColour", CanvasBackground.DEFAULT_COLOUR),
                    imageUri = json.optString("canvasImage").takeIf { it.isNotBlank() }
                )
            } ?: CanvasBackground.NONE,
            effects = json.optJSONArray("effects")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    TimedEffect(
                        id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                        kind = enumOrNull<EffectKind>(o.optString("kind")) ?: return@mapNotNull null,
                        startMs = o.optLong("startMs"),
                        endMs = o.optLong("endMs"),
                        intensity = o.optDouble("intensity", 0.7).toFloat(),
                        amount = o.optDouble("amount", TimedEffect.DEFAULT_AMOUNT.toDouble()).toFloat()
                    )
                }
            }.orEmpty(),
            markers = markers,
            playheadMs = json.optLong("playheadMs"),
            outputP = if (json.has("outputP")) json.optInt("outputP") else OutputSize.fromLegacyQuality(json.optString("quality")) ?: OutputSize.ORIGINAL,
            fitToSize = json.optBoolean("fitToSize"),
            targetSizeMb = json.optInt("targetSizeMb", 16),
            audioOnly = json.optBoolean("audioOnly"),
            outputFps = json.optInt("outputFps", ExportSettings.SOURCE_FPS),
            quality = ExportQuality.fromName(json.optString("exportQuality")),
            hevc = json.optBoolean("hevc"),
            keepHdr = json.optBoolean("keepHdr"),
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
            // A draft from before these were written, or one whose values were
            // the defaults, reads as the defaults - which is what it did.
            captionSource = json.optString("captionSource").takeIf { it.isNotBlank() }
                ?.let { name -> CaptionSource.entries.firstOrNull { it.name == name } } ?: CaptionSource.Camera,
            captionLanguage = json.optString("captionLanguage").takeIf { it.isNotBlank() },
            beats = json.optJSONObject("beats")?.let { b ->
                val beatsMs = b.optJSONArray("beatsMs")?.let { array ->
                    (0 until array.length()).map { i -> array.optLong(i) }
                }.orEmpty()
                // A sound named here that is no longer in the draft carries no
                // grid, and the card must not say it does.
                val clipId = b.optString("clipId").takeIf { id -> id.isNotBlank() && audio.any { it.id == id } }
                BeatProgress(
                    // "Found a grid" wants two beats; one tapped beat is still
                    // read back, and the card then says what it is rather than
                    // claiming a grid.
                    finished = beatsMs.size >= 2 || clipId != null,
                    bpm = b.optDouble("bpm", 0.0).toFloat(),
                    confidence = b.optDouble("confidence", 0.0).toFloat(),
                    beatsMs = beatsMs,
                    downbeatOffset = b.optInt("downbeatOffset"),
                    clipLabel = b.optString("clipLabel"),
                    clipId = clipId,
                    every = b.optInt("every", 1).coerceIn(1, 4)
                ).takeIf { it.worthSaving }
            } ?: BeatProgress(),
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
        // In order and never negative: optLong answers zero for a key that is
        // not there, so a half-written object can hand back an out-point before
        // its in-point, and everything that clamps a moment into the window
        // calls coerceIn(in, out), which throws on an inverted range rather
        // than returning anything. See ProjectRules.window.
        val (readInMs, readOutMs) = ProjectRules.window(
            json.optLong("sourceInMs"),
            json.optLong("sourceOutMs")
        )
        return Clip(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            kind = kind,
            uri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
            label = json.optString("label", "Clip"),
            sourceInMs = readInMs,
            sourceOutMs = readOutMs,
            timelineStartMs = json.optLong("timelineStartMs"),
            sourceDurationMs = json.optLong("sourceDurationMs"),
            volume = json.optDouble("volume", 1.0).toFloat(),
            muted = json.optBoolean("muted", false),
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
            stabilizerMeasurement = decodeMeasurement(json.optJSONObject("stabilizerMeasurement")),
            // Absent on a draft from before the strength was the clip's: the
            // edit's one strength then, which the sheet falls back to.
            stabilizeStrength = if (json.has("stabilizeStrength")) json.optDouble("stabilizeStrength", 0.5).toFloat().coerceIn(0f, 1f) else null,
            opacityKeys = decodeValueKeys(json.optJSONArray("opacityKeys")),
            blend = runCatching { com.squish.app.timeline.LayerBlend.valueOf(json.optString("blend", "Normal")) }.getOrDefault(com.squish.app.timeline.LayerBlend.Normal),
            lookKeys = decodeValueKeys(json.optJSONArray("lookKeys")),
            volumeKeys = decodeValueKeys(json.optJSONArray("volumeKeys")),
            arrival = enumOrNull<ClipArrival>(json.optString("arrival")) ?: ClipArrival.None,
            leaving = enumOrNull<ClipLeaving>(json.optString("leaving")) ?: ClipLeaving.None,
            loop = enumOrNull<ClipLoop>(json.optString("loop")) ?: ClipLoop.None,
            arrivalMs = json.optLong("arrivalMs", ClipAnimation.DEFAULT_IN_MS),
            leavingMs = json.optLong("leavingMs", ClipAnimation.DEFAULT_OUT_MS),
            loopMs = json.optLong("loopMs", ClipAnimation.DEFAULT_LOOP_MS),
            frameBlend = json.optBoolean("frameBlend", false),
            pitchFollowsSpeed = json.optBoolean("pitchFollowsSpeed", false),
            background = json.optJSONObject("background")?.let(::decodeBackground),
            chromaKey = json.optJSONObject("chromaKey")?.let { k ->
                ChromaKey(
                    keyColorArgb = k.optInt("keyColorArgb", ChromaKey.STANDARD_GREEN),
                    similarity = k.optDouble("similarity", 0.38).toFloat(),
                    smoothness = k.optDouble("smoothness", 0.1).toFloat(),
                    spill = k.optDouble("spill", 0.12).toFloat()
                )
            },
            lookId = json.optString("lookId").takeIf { it.isNotBlank() && it != "null" },
            lookIntensity = json.optDouble("lookIntensity", 1.0).toFloat().coerceIn(0f, 1f),
            adjust = decodeAdjust(json.optJSONObject("adjust")),
            crop = json.optJSONObject("crop")?.let { c ->
                ClipCrop(
                    rect = CropRect.of(
                        left = c.optDouble("left", 0.0).toFloat(),
                        top = c.optDouble("top", 0.0).toFloat(),
                        right = c.optDouble("right", 1.0).toFloat(),
                        bottom = c.optDouble("bottom", 1.0).toFloat()
                    ),
                    straightenDegrees = c.optDouble("straighten", 0.0).toFloat()
                        .coerceIn(-CropRules.MAX_STRAIGHTEN_DEGREES, CropRules.MAX_STRAIGHTEN_DEGREES),
                    flipHorizontal = c.optBoolean("flipH", false),
                    flipVertical = c.optBoolean("flipV", false),
                    ratio = enumOrNull<CropRatio>(c.optString("ratio")) ?: CropRatio.Free
                ).takeIf { !it.isIdentity || it.ratio != CropRatio.Free }
            },
            reframe = decodeTrack(json.optJSONArray("reframe")),
            mask = json.optJSONObject("mask")?.let { m ->
                maskShapeOf(m).copy(
                    keys = m.optJSONArray("keys")?.let { array ->
                        (0 until array.length()).mapNotNull { i ->
                            array.optJSONObject(i)?.let { k ->
                                MaskKey(
                                    atMs = k.optLong("atMs"),
                                    mask = maskShapeOf(k),
                                    easing = enumOrNull<KeyframeEasing>(k.optString("easing")) ?: KeyframeEasing.Smooth
                                )
                            }
                        }.sortedBy { it.atMs }
                    }.orEmpty(),
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
                // The window Reverse again puts back, read the same way: in
                // order and never negative (ProjectRules.window).
                val (wasInMs, wasOutMs) = ProjectRules.window(
                    r.optLong("sourceInMs"),
                    r.optLong("sourceOutMs")
                )
                ReversedSource(
                    uri = r.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
                    sourceInMs = wasInMs,
                    sourceOutMs = wasOutMs,
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
            shape = AnnotationShape.named(json.optString("shape").takeIf { it.isNotEmpty() }),
            shapeAspect = json.optDouble("shapeAspect", 1.0).toFloat()
                .coerceIn(ShapeGeometry.MIN_ASPECT, ShapeGeometry.MAX_ASPECT),
            shapeFilled = json.optBoolean("shapeFilled", false),
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

    /**
     * A mask's shape, apart from what it is following and the keys it moves on.
     *
     * Written once for the mask itself and once per shape key, so the two can
     * never drift: a field added to one and not the other would read back as
     * its default on every key, which is a mask that snaps between shapes.
     */
    private fun JSONObject.putMaskShape(m: Mask) {
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
    }

    private fun maskShapeOf(m: JSONObject): Mask = Mask(
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
        strength = m.optDouble("strength", 0.5).toFloat()
    )

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
         * clip was rendered from (B11); a clip's own look, colour sliders,
         * crop and reframe track (the edit-wide "lookId", "brightness",
         * "contrast", "saturation" and "reframe" move onto the main-track
         * shots), and the canvas background (B12). Each is written only when
         * set and told by the fields, like the voice was, so a 12 reads as
         * before.
         */
        /** 14 writes a clip's tone curve; 13 and older simply have none. */
        const val FORMAT_VERSION = 14
        const val OLDEST_READABLE_VERSION = 9

        /** A clip's "maskFile" entry, as encodeClip writes it. */
        private val MASK_FILE = Regex("\"maskFile\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** Every "uri" and "sourceUri" entry, as the encoders write them. */
        private val URI_FIELD = Regex("\"(?:uri|sourceUri|coverUri|canvasImage)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

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
    val canvasBackground: CanvasBackground,
    val pixelsPerSecond: Float,
    /** What the project was named, if it was; see [EditorUiState.projectName]. */
    val name: String? = null,
    // The export sheet's other choices; defaults for every draft written before the rows existed.
    val outputFps: Int = ExportSettings.SOURCE_FPS,
    val quality: ExportQuality = ExportQuality.Recommended,
    val hevc: Boolean = false,
    val keepHdr: Boolean = false,
    /** What auto-captions listen to, and in which language; see the encoder. */
    val captionSource: CaptionSource = CaptionSource.Camera,
    val captionLanguage: String? = null
) {
    /**
     * How long the edit runs: where its last picture or sound ends, overlays
     * included - the same number as EditorUiState.trimmedDurationMs once
     * anything has been laid down. It used to be the clips' lengths added up,
     * which counted an overlay on top of the shots it sits over: the card
     * offered 0:16 of edit for an 8 s video with an 8 s overlay, a length the
     * timeline could never show.
     *
     * With nothing laid down the two differ on purpose: this is nothing, which
     * is the honest length of an empty edit, while the state falls back to the
     * source file's own window because the strip has to be drawn over
     * something. They used to differ when only the *pictures* were gone, which
     * was not on purpose - a five-second edit of one song reported the thirty
     * seconds of the video it had been opened on, six times out in the card,
     * the header, the export sheet and the size estimate alike.
     */
    val totalDurationMs: Long
        get() = maxOf(clips.maxOfOrNull { it.timelineEndMs } ?: 0L, audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L)
}

/**
 * What a project not yet saved is to be made from: the files picked for it,
 * in order, and whether they must be copied in first (ProjectAutosave.stageStart).
 */
/**
 * The files a new project starts from. [openedFromOutside]: handed over by
 * "Open with" or a share rather than made on the dashboard - left without a
 * single edit, such a project is not kept (EditorViewModel.onCleared).
 */
data class ProjectStart(val uris: List<Uri>, val copyIn: Boolean = false, val openedFromOutside: Boolean = false)

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
