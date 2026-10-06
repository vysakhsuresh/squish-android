package com.squish.app.data

import android.net.Uri
import com.squish.app.editor.BeatProgress
import com.squish.app.editor.CanvasBackground
import com.squish.app.editor.CanvasFill
import com.squish.app.editor.CaptionSource
import com.squish.app.editor.CropAspect
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.editor.CropRect
import com.squish.app.editor.EditorUiState
import com.squish.app.media.effects.Adjust
import com.squish.app.editor.OutputSize
import com.squish.app.editor.OverlayRules
import com.squish.app.editor.ProjectName
import com.squish.app.editor.EffectKind
import com.squish.app.editor.TimedEffect
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.ClipKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * A project's draft, as JSON and back.
 *
 * Lifted out of [ProjectAutosave], which is two things in one file: the file
 * handling - atomic writes, the backup, the snapshot rotation, the sidecar, the
 * bin - which needs a Context, and this, which touches none. The split is what
 * makes the app's third guarantee testable: "no edit is ever lost" is a
 * property of a *round trip*, and until the codec could be built without a
 * Context nothing could encode a populated edit, decode it and compare the two.
 * tools/jvm/DraftRoundTripChecks.kt now does, which is the only way to catch a
 * field whose name and key are both present and whose *value* does not survive
 * - the fault that clamped a colour wheel to half its range for as long as
 * anybody had been grading with it.
 *
 * Nothing inside moved a line when it came across: the cut was proved by the
 * compiler and the behaviour by the round trip. Three version constants came
 * with it, because they are the format's and not the file handling's.
 */
internal object DraftCodec {

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

    /** The first version whose pictures' levels are their own; older ones are moved over on reading. */
    const val PER_CLIP_VOLUME_VERSION = 11

    fun encode(state: EditorUiState): JSONObject = JSONObject().apply {
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
        putFinite("originalVolume", state.originalVolume)
        put("rotationDegrees", state.rotationDegrees)
        put("cropAspect", state.cropAspect.name)
        // The hand-drawn rectangle. Saved only when it means something: a
        // Custom crop that came back as the whole frame exported uncropped while
        // the panel still said Custom.
        if (!state.cropRect.isFull) {
            put("cropRect", JSONObject().apply {
                putFinite("left", state.cropRect.left)
                putFinite("top", state.cropRect.top)
                putFinite("right", state.cropRect.right)
                putFinite("bottom", state.cropRect.bottom)
            })
        }
        put("snapToMarkers", state.snapToMarkers)
        putFinite("stabilizeStrength", state.stabilizeStrength)
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
        putFinite("pixelsPerSecond", state.pixelsPerSecond)
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
                putFinite("bpm", state.beats.bpm)
                putFinite("confidence", state.beats.confidence)
                put("downbeatOffset", state.beats.downbeatOffset)
                put("clipLabel", state.beats.clipLabel)
                put("every", state.beats.every)
                state.beats.clipId?.let { put("clipId", it) }
                // Only a grid on the camera audio has beats here; a sound's are
                // saved on the sound, with the clip they belong to.
                put("beatsMs", JSONArray().apply { state.beats.beatsMs.forEach { put(it) } })
            })
        }
        put("clips", JSONArray().apply { state.videoClips.forEach { put(DraftClipCodec.encodeClip(it)) } })
        put("textOverlays", JSONArray().apply { state.textOverlays.forEach { put(DraftTextCodec.encodeText(it)) } })
        put("effects", JSONArray().apply {
            state.effects.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind.name)
                    put("startMs", e.startMs)
                    put("endMs", e.endMs)
                    putFinite("intensity", e.intensity)
                    putFinite("amount", e.amount)
                })
            }
        })

        put("audioClips", JSONArray().apply { state.audioClips.forEach { put(DraftClipCodec.encodeClip(it)) } })
    }


    // ---- Decoding -------------------------------------------------------------

    fun decode(json: JSONObject): ProjectSnapshot? {
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
        val saved = (0 until savedArray.length()).mapNotNull { i -> DraftClipCodec.decodeClip(savedArray.optJSONObject(i), ClipKind.Video) }
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
        val legacyReframe = DraftClipCodec.decodeTrack(json.optJSONArray("reframe"))
        val clips = if (legacyReframe == null) graded else {
            val firstUri = graded.firstOrNull { !it.isOverlay }?.uri
            graded.map { if (!it.isOverlay && it.uri == firstUri && it.reframe == null) it.copy(reframe = legacyReframe) else it }
        }

        val audio = json.optJSONArray("audioClips")?.let { array ->
            (0 until array.length()).mapNotNull { i -> DraftClipCodec.decodeClip(array.optJSONObject(i), ClipKind.Audio) }
        }.orEmpty()

        val overlays = json.optJSONArray("textOverlays")?.let { array ->
            (0 until array.length()).mapNotNull { i -> DraftTextCodec.decodeText(array.optJSONObject(i)) }
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


    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

}
