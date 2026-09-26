package com.squish.app.data

import android.content.Context
import android.net.Uri
import com.squish.app.editor.CropAspect
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.OutputSize
import com.squish.app.editor.TextFont
import com.squish.app.editor.TextLook
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
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
 * parachute in case the JSON itself is ever unreadable.
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
    val toolId: String? = null
)

class ProjectAutosave(context: Context) {

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

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
    private fun scratchFile(slot: String) = File(dir, "$slot.tmp.json")

    /**
     * A few hundred bytes written beside each draft: enough to list every draft
     * without parsing any of them. A drafts list that had to decode a dozen full
     * timelines to draw itself would be slower than the editor it leads to.
     */
    private fun metaFile(slot: String) = File(dir, "$slot.meta.json")

    /** Cheap change detector, so an idle editor never touches the disk. */
    @Volatile
    private var lastSignature: String? = null

    /**
     * Persists the edit if anything has changed since the last write. Safe to call
     * on a timer; it is a no-op when nothing moved.
     */
    fun save(state: EditorUiState): Boolean {
        val uri = state.sourceUri ?: return false
        if (state.isLoadingSource) return false
        val slot = slotFor(uri)
        val live = liveFile(slot)
        val backup = backupFile(slot)
        val scratch = scratchFile(slot)

        // The signature is taken from the edit alone. Stamping the time first would
        // make every tick look like a change and turn "save when something moved"
        // into "write to flash every 1.5 seconds, forever".
        val document = encode(state)
        val signature = document.toString()
        if (signature == lastSignature) return false

        document.put("savedAtMillis", System.currentTimeMillis())

        val ok = runCatching {
            FileOutputStream(scratch).use { out ->
                out.write(document.toString().toByteArray())
                out.flush()
                out.fd.sync()          // on the platter, not just in the page cache
            }
            if (live.exists()) {
                backup.delete()
                live.copyTo(backup, overwrite = true)
            }
            check(scratch.renameTo(live)) { "atomic rename refused" }
        }.isSuccess

        if (ok) {
            lastSignature = signature
            writeMeta(slot, state, uri)
        }
        return ok
    }

    /**
     * The edit itself, for telling an edited timeline from an untouched one.
     *
     * Where the playhead sits and how far the strip is zoomed are left out:
     * scrubbing through a clip to look at it is not editing it.
     */
    fun editKey(state: EditorUiState): String =
        encode(state).apply {
            remove("playheadMs")
            remove("pixelsPerSecond")
        }.toString()

    /** A recoverable session for this video, if one survived. */
    fun peek(uri: Uri): ProjectSnapshot? {
        val slot = slotFor(uri)
        return read(liveFile(slot)) ?: read(backupFile(slot))
    }

    /**
     * Every draft, newest first, read from the sidecars alone.
     *
     * A draft whose source video has been deleted from the device is dropped as
     * it is found: it can never be reopened, and leaving it in the list is an
     * offer that fails when taken.
     */
    fun drafts(): List<DraftSummary> = runCatching {
        // listFiles(lambda) is ambiguous between FileFilter and FilenameFilter,
        // so the filtering happens after, on a plainly typed array.
        val files: Array<File> = dir.listFiles() ?: return@runCatching emptyList()
        files.filter { it.name.endsWith(".meta.json") }
            .mapNotNull { readMeta(it) }
            .filter { liveFile(it.id).exists() }
            .sortedByDescending { it.savedAtMillis }
    }.getOrDefault(emptyList())

    fun delete(slot: String) {
        lastSignature = null
        listOf(liveFile(slot), backupFile(slot), scratchFile(slot), metaFile(slot))
            .forEach { runCatching { it.delete() } }
    }

    fun clear(uri: Uri) = delete(slotFor(uri))

    private fun writeMeta(slot: String, state: EditorUiState, uri: Uri) {
        runCatching {
            metaFile(slot).writeText(
                JSONObject().apply {
                    put("id", slot)
                    put("title", state.videoClips.firstOrNull()?.label ?: "Untitled edit")
                    put("uri", uri.toString())
                    put("durationMs", state.trimmedDurationMs)
                    put("clipCount", state.videoClips.size)
                    put("savedAtMillis", System.currentTimeMillis())
                }.toString()
            )
        }
    }

    private fun readMeta(file: File): DraftSummary? = runCatching {
        val json = JSONObject(file.readText())
        val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
        DraftSummary(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            title = json.optString("title", "Untitled edit"),
            sourceUri = Uri.parse(uri),
            durationMs = json.optLong("durationMs"),
            clipCount = json.optInt("clipCount", 1),
            savedAtMillis = json.optLong("savedAtMillis")
        )
    }.getOrNull()

    /**
     * Marks the current edit as finished. Called after a successful export: the
     * work reached the gallery, so there is nothing left to recover and offering
     * to restore it on next launch would only confuse.
     */
    fun markCompleted(uri: Uri) = clear(uri)

    private fun read(file: File): ProjectSnapshot? {
        if (!file.exists()) return null
        return runCatching { decode(JSONObject(file.readText())) }.getOrNull()
    }

    // ---- Encoding -------------------------------------------------------------

    private fun encode(state: EditorUiState): JSONObject = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("sourceUri", state.sourceUri.toString())
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
        put("brightness", state.brightness.toDouble())
        put("contrast", state.contrast.toDouble())
        put("saturation", state.saturation.toDouble())
        put("lookId", state.lookId ?: JSONObject.NULL)
        put("lookIntensity", state.lookIntensity.toDouble())
        put("pixelsPerSecond", state.pixelsPerSecond.toDouble())
        put("markers", JSONArray().apply { state.markers.forEach { put(it) } })
        put("clips", JSONArray().apply { state.videoClips.forEach { put(encodeClip(it)) } })
        put("textOverlays", JSONArray().apply { state.textOverlays.forEach { put(encodeText(it)) } })

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
        clip.chromaKey?.let { key ->
            put("chromaKey", JSONObject().apply {
                put("keyColorArgb", key.keyColorArgb)
                put("similarity", key.similarity.toDouble())
                put("smoothness", key.smoothness.toDouble())
                put("spill", key.spill.toDouble())
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
        put("colorArgb", item.colorArgb)
        put("xFraction", item.xFraction.toDouble())
        put("yFraction", item.yFraction.toDouble())
        put("sizeSp", item.sizeSp)
        put("font", item.font.name)
        put("look", item.look.name)
        put("motion", item.motion.name)
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
        if (json.optInt("version") != FORMAT_VERSION) return null
        val sourceUri = json.optString("sourceUri").takeIf { it.isNotBlank() } ?: return null

        val clips = json.optJSONArray("clips")?.let { array ->
            (0 until array.length()).mapNotNull { i -> decodeClip(array.optJSONObject(i), ClipKind.Video) }
        }.orEmpty()
        if (clips.isEmpty()) return null

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
            savedAtMillis = json.optLong("savedAtMillis"),
            clipCount = clips.size,
            clips = clips,
            audioClips = audio,
            textOverlays = overlays,
            markers = markers,
            playheadMs = json.optLong("playheadMs"),
            outputP = if (json.has("outputP")) json.optInt("outputP") else OutputSize.fromLegacyQuality(json.optString("quality")) ?: OutputSize.ORIGINAL,
            fitToSize = json.optBoolean("fitToSize"),
            targetSizeMb = json.optInt("targetSizeMb", 16),
            audioOnly = json.optBoolean("audioOnly"),
            muteOriginal = json.optBoolean("muteOriginal"),
            originalVolume = json.optDouble("originalVolume", 1.0).toFloat(),
            rotationDegrees = json.optInt("rotationDegrees"),
            cropAspect = enumOrNull<CropAspect>(json.optString("cropAspect")) ?: CropAspect.Original,
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
            }
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
        return TextOverlayItem(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            text = json.optString("text"),
            startMs = json.optLong("startMs"),
            endMs = json.optLong("endMs"),
            colorArgb = json.optInt("colorArgb"),
            xFraction = json.optDouble("xFraction", 0.5).toFloat(),
            yFraction = json.optDouble("yFraction", 0.85).toFloat(),
            sizeSp = json.optInt("sizeSp", 28),
            // Captions saved before styles existed were plain white letters.
            font = enumOrNull<TextFont>(json.optString("font")) ?: TextFont.Sans,
            look = enumOrNull<TextLook>(json.optString("look")) ?: TextLook.Plain,
            motion = enumOrNull<TextMotion>(json.optString("motion")) ?: TextMotion.None,
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
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private companion object {
        /** Bump when the shape changes; older documents are then ignored rather than misread. */
        const val FORMAT_VERSION = 9
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
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val lookId: String?,
    val lookIntensity: Float,
    val pixelsPerSecond: Float
) {
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }

    /**
     * A single untrimmed clip with nothing else on it - the state a freshly opened
     * file is already in. There is nothing here to recover.
     */
    val isTrivial: Boolean
        get() = clips.size == 1 &&
            textOverlays.isEmpty() &&
            audioClips.isEmpty() &&
            markers.isEmpty() &&
            clips.first().let { it.sourceInMs == 0L && it.timelineStartMs == 0L && it.sourceOutMs >= it.sourceDurationMs }
}
