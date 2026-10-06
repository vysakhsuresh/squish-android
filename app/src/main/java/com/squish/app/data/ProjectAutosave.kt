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
        val document = DraftCodec.encode(state)
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
            //
            // What that order costs, so nobody reverses it hoping to win: a kill
            // in *this* gap leaves the sidecar describing the edit the scratch
            // file holds while the live file is still the previous one, so the
            // card shows the new length, cover and clip count over the old
            // draft until the next save puts them back in step. The draft
            // itself is whole either way - the live file is only ever replaced
            // atomically - and a card a save behind is a smaller fault than a
            // project missing from the grid, which is what the other order
            // produced. Note the backup copy above is deliberately not atomic
            // and need not be: it runs before the live file is touched, so a
            // kill during it leaves the good live file in place and only the
            // backup stale, and the next save rewrites it.
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
    fun editKey(state: EditorUiState): String = editKeyOf(DraftCodec.encode(state))

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
        val snapshot = DraftCodec.decode(json) ?: return null
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
        val earlier = runCatching { DraftCodec.decode(JSONObject(text)) }.getOrNull() ?: return null
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
        val snapshot = DraftCodec.decode(JSONObject(liveFile(slot).readText())) ?: return null
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
        return runCatching { DraftCodec.decode(JSONObject(file.readText())) }.getOrNull()
    }

    // ---- Encoding -------------------------------------------------------------

    private companion object {
        /** A clip's "maskFile" entry, as DraftCodec.encodeClip writes it. */
        private val MASK_FILE = Regex("\"maskFile\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** Every "uri" and "sourceUri" entry, as the encoders write them. */
        private val URI_FIELD = Regex("\"(?:uri|sourceUri|coverUri|canvasImage)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
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
