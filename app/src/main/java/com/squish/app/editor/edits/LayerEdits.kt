package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.editor.OverlayRules.withMainOnOverlay
import com.squish.app.editor.OverlayRules.withOverlayCopiedInPlace
import com.squish.app.editor.OverlayRules.withOverlayOnMain
import com.squish.app.media.SquishError
import com.squish.app.media.StillClips
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.timeline.withSplitScreen
import com.squish.app.timeline.withGridTile
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Mask
import com.squish.app.timeline.withShapeAt
import com.squish.app.timeline.withShapeKeyAdded
import com.squish.app.timeline.withShapeKeyRemoved
import com.squish.app.timeline.hasShapeKeyNear
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.editor.OverlayRules.withOverlayStepped
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.OVERLAY_LANDING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.squish.app.editor.*

/** Layers: overlays, their placement and stacking, transitions, green screen and masks. */
internal class LayerEdits(host: EditHost) : EditArea(host) {

    /** Something picked for an overlay row, ready to place: a video, or a photo kept as a picture. */
    private data class Picked(val uri: Uri, val label: String, val lengthMs: Long, val still: Boolean)

    /**
     * Adds videos and photos as overlays, each starting at the playhead - where
     * it was when they were picked, not where it has got to by the time the
     * photos are made - on the lowest row free there. One undo step for the
     * pick; the last one added is selected, so its box is on the picture.
     *
     * A photo stays a picture (StillClips.overlayFromImage), so a transparent
     * logo is transparent. Each lands no longer than the main track runs past
     * the playhead (OverlayRules.landing); its handles drag it out.
     */
    fun addOverlayClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val at = _state.value.playheadMs
        viewModelScope.launch {
            val resolver = app.contentResolver
            val photo = uris.associateWith { resolver.getType(it)?.startsWith("image/") == true }
            val photos = photo.count { it.value }
            if (photos > 0) _state.update { it.copy(preparingStills = it.preparingStills + photos) }
            // Counted down once the overlays are on the timeline, not as each photo
            // is made: an export started in between would leave them out.
            try {
                val picked = uris.map { uri ->
                    if (photo[uri] == true) {
                        withContext(Dispatchers.IO) { StillClips.overlayFromImage(app, uri) }
                            ?.let { Picked(it, displayNameOf(uri) ?: "Photo", OverlayRules.STILL_LANDING_MS, still = true) }
                    } else {
                        val meta = ThumbnailExtractor.probe(app, uri)
                        // Refused with a reason rather than added as an invisible
                        // layer with no length, the way an unreadable sound used to be.
                        if (meta.durationMs <= 0L) null
                        else Picked(uri, displayNameOf(uri) ?: "Overlay", meta.durationMs, still = false)
                    }
                }
                val ready = picked.filterNotNull()
                if (ready.size < picked.size) _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                if (ready.isEmpty()) return@launch
                place(ready, at)
                recomputeEstimate()
                val videos = ready.filterNot { it.still }.map { it.uri }
                videos.forEach(::checkDecodable)
                ensureProxies(videos)
            } finally {
                if (photos > 0) {
                    _state.update { it.copy(preparingStills = (it.preparingStills - photos).coerceAtLeast(0)) }
                }
            }
        }
    }

    private fun place(ready: List<Picked>, at: Long) {
        record(if (ready.size > 1) "Add ${ready.size} overlays" else "Add overlay") {
            _state.update { current ->
                var timeline = TimelineState(clips = current.videoClips)
                val mainEnd = timeline.baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L
                var refused: Clip? = null
                var last: String? = null
                for (p in ready) {
                    val landing = OverlayRules.landing(p.lengthMs, at, mainEnd)
                    val length = landing.lengthMs
                    val (inMs, outMs, fileMs) = if (p.still) OverlayRules.stillWindow(length)
                    else Triple(0L, length, p.lengthMs)
                    val clip = Clip(
                        kind = ClipKind.Video,
                        uri = p.uri,
                        label = p.label,
                        sourceInMs = inMs,
                        sourceOutMs = outMs,
                        timelineStartMs = landing.startMs,
                        sourceDurationMs = fileMs,
                        layer = 1,
                        scale = OVERLAY_LANDING.scale,
                        offsetXFraction = OVERLAY_LANDING.offsetXFraction,
                        offsetYFraction = OVERLAY_LANDING.offsetYFraction
                    )
                    // Onto the first row free at this moment, never on top of another
                    // overlay: the preview shows one clip per row, the export all of them.
                    val next = timeline.withClipAdded(clip)
                    if (next.clips.size == timeline.clips.size) refused = clip
                    else {
                        timeline = next
                        last = clip.id
                    }
                }
                current.copy(
                    videoClips = timeline.clips,
                    selectedClipId = last ?: current.selectedClipId,
                    failure = refused?.let { SquishError.OverlayRowsFull(footage = !it.isStillPicture) } ?: current.failure
                )
            }
        }
    }

    /**
     * The box's Duplicate: a copy at the same moment, a row up, nudged so both
     * show (OverlayRules.withOverlayCopiedInPlace). Said, when there is no row.
     */
    fun duplicateInPlace(clipId: String) {
        val copyId = UUID.randomUUID().toString()
        record("Duplicate") { mutateTimeline { it.withOverlayCopiedInPlace(clipId, copyId) } }
        val original = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        if (_state.value.videoClips.none { it.id == copyId }) {
            _state.update { it.copy(failure = SquishError.OverlayRowsFull(footage = !original.isStillPicture)) }
        }
    }

    /**
     * Sets the transition into a clip. Picking a kind is a step; dragging its
     * length is one gesture, so the slider's hundred frames are one undo.
     *
     * This and a layer change both re-lay the main track, which can shorten the
     * edit and so cut back the effects at its end - an undo step each, or there
     * is no way back.
     */
    fun setTransition(clipId: String, type: TransitionType, durationMs: Long) {
        val existing = _state.value.videoClips.firstOrNull { it.id == clipId }?.transitionIn
        val lengthOnly = existing != null && existing.type == type && existing.durationMs != durationMs
        record("Transition", gesture = if (lengthOnly) "Transition length $clipId" else null) {
            mutateTimeline { it.withTransition(clipId, Transition(type, durationMs)) }
        }
    }

    /**
     * Bring forward or Send back: past the nearest overlay drawn over or under
     * this one (OverlayRules.withOverlayStepped). It used to be a row up or down,
     * whatever was there - raising a lone overlay five times changed nothing on
     * the picture and left five empty lanes on the strip, and two overlays on
     * rows next to each other could not be swapped. The main track is not a step
     * down from here; it is To main, on the toolbar.
     */
    fun stepLayer(clipId: String, up: Boolean) =
        record(if (up) "Bring forward" else "Send back") {
            mutateTimeline { it.withOverlayStepped(clipId, up) }
        }

    /**
     * A shot lifted off the main track onto the lowest overlay row free over it,
     * landing in the corner an added overlay lands in; the track closes up behind
     * it (OverlayRules.withMainOnOverlay). With every row taken there, it says so
     * rather than leaving a button that did nothing.
     *
     * [keepPlacement] is the Cutout sheet's "Float this clip": the shot stays
     * where it was on the picture, keys and all, so the person in it shows over
     * the shot that slides in under it.
     */
    fun switchToOverlay(clipId: String, keepPlacement: Boolean = false) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId && it.isMain } ?: return
        val levels = _state.value
        record(if (keepPlacement) "Float" else "To overlay") {
            mutateTimeline { it.withMainOnOverlay(clip.id, levels.muteOriginal, levels.originalVolume, keepPlacement) }
        }
        if (_state.value.videoClips.firstOrNull { it.id == clipId }?.isMain == true) {
            _state.update { it.copy(failure = SquishError.OverlayRowsFull(footage = !clip.isStillPicture)) }
        }
    }

    /**
     * An overlay dropped onto the main track at the playhead - on the nearer cut,
     * full frame - with the shots after it moved along to make room
     * (OverlayRules.withOverlayOnMain). It used to keep its corner placement and
     * its place in time, which made a small picture over black in the middle of
     * the edit (O9).
     *
     * A photo kept as a picture is made into a clip first, as a photo added to
     * the main track is, long enough for however far it had been dragged out:
     * the main track plays video files.
     */
    fun switchToMain(clipId: String) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId && it.isOverlay } ?: return
        val at = _state.value.playheadMs
        val picture = clip.uri
        if (!StillClips.isStill(picture) || picture == null) {
            record("To main track") {
                mutateTimeline { it.withOverlayOnMain(clipId, at, _state.value.originalVolume) }
            }
            sayIfSilencedOnMain(clip)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(preparingStills = it.preparingStills + 1) }
            try {
                val made = StillClips.fromImage(app, picture, clip.durationMs + StillClips.DEFAULT_MS)
                val fileMs = made?.let { ThumbnailExtractor.probe(app, it).durationMs } ?: 0L
                if (made == null || fileMs <= 0L) {
                    _state.update { it.copy(failure = SquishError.Unknown(null)) }
                    return@launch
                }
                record("To main track") {
                    mutateTimeline { timeline ->
                        // As it is now, not as it was when this began: it may have
                        // been trimmed, moved or deleted while the clip was made.
                        val now = timeline.clips.firstOrNull { it.id == clipId && it.isOverlay }
                            ?: return@mutateTimeline timeline
                        val filmed = now.copy(
                            uri = made,
                            sourceInMs = 0L,
                            sourceOutMs = now.durationMs.coerceIn(MIN_CLIP_MS, fileMs),
                            sourceDurationMs = fileMs,
                            speedRamp = SpeedRamp()
                        )
                        timeline.copy(clips = timeline.clips.map { if (it.id == clipId) filmed else it })
                            .withOverlayOnMain(clipId, at, _state.value.originalVolume)
                    }
                }
                sayIfSilencedOnMain(clip)
            } finally {
                _state.update { it.copy(preparingStills = (it.preparingStills - 1).coerceAtLeast(0)) }
            }
        }
    }

    /**
     * An overlay with sound, moved onto a main track whose camera sound is off:
     * said, because its Volume came across and it is silent all the same.
     */
    private fun sayIfSilencedOnMain(overlay: Clip) {
        val now = _state.value
        val moved = now.videoClips.firstOrNull { it.id == overlay.id }?.isMain == true
        if (moved && now.muteOriginal && overlay.isHeard && !overlay.isStillPicture) {
            _state.update { it.copy(failure = SquishError.SilentOnMainTrack()) }
        }
    }

    /**
     * The Opacity sheet's slider. Each drag, on each clip, is one step. On a
     * clip with opacity keys the slider keys the playhead (ValueTracks).
     */
    fun setOpacity(clipId: String, opacity: Float) = record("Opacity", gesture = "Opacity $clipId") {
        mutateTimeline { it.withOverlayGeometry(clipId, opacity = opacity) }
    }

    /**
     * How a still's colour meets the picture under it. Offered on photos and
     * stickers only - see LayerBlend for why a video overlay cannot have one.
     */
    fun setBlend(clipId: String, blend: com.squish.app.timeline.LayerBlend) = record("Blend") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(blend = blend) else it })
        }
    }

    /** Opacity's Reset: fully there, its keys gone, as one step. */
    fun resetOpacity(clipId: String) = record("Opacity") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(opacity = 1f, opacityKeys = emptyList()) else it })

        }
    }

    /**
     * The transition on [clipId] put on every join of the main track, as one
     * step: the same kind and length on each shot that has a shot before it.
     * The track is laid out again for the new overlaps (withTransition).
     */
    fun applyTransitionToAll(clipId: String) {
        val source = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        val transition = source.transitionIn
        record("Transition on every cut") {
            mutateTimeline { timeline ->
                val base = timeline.baseVideoClips
                base.drop(1).fold(timeline) { acc, clip -> acc.withTransition(clip.id, transition) }
            }
        }
    }

    // ---- Green screen -------------------------------------------------------------

    /** Turns keying on for a clip, or off when passed null. */
    fun setChromaKey(clipId: String, key: ChromaKey?) = record(if (key == null) "Remove key" else "Green screen") {
        mutateTimeline { timeline ->
            timeline.copy(
                clips = timeline.clips.map { if (it.id == clipId) it.copy(chromaKey = key) else it }
            )
        }
    }

    /**
     * Tunes the key. A colour picked - a chip, a tap on the frame - is a step;
     * the sliders are gestures, one per slider per clip.
     */
    fun updateChromaKey(
        clipId: String,
        keyColorArgb: Int? = null,
        similarity: Float? = null,
        smoothness: Float? = null,
        spill: Float? = null
    ) = record(
        if (keyColorArgb != null) "Key colour" else "Green screen",
        gesture = if (similarity == null && smoothness == null && spill == null) null
        else "Chroma ${fieldsNamed("similarity" to similarity, "smoothness" to smoothness, "spill" to spill)} $clipId"
    ) {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val current = clip.chromaKey ?: ChromaKey()
            val next = current.copy(
                keyColorArgb = keyColorArgb ?: current.keyColorArgb,
                similarity = (similarity ?: current.similarity).coerceIn(0.02f, 0.6f),
                smoothness = (smoothness ?: current.smoothness).coerceIn(0.005f, 0.4f),
                spill = (spill ?: current.spill).coerceIn(0.005f, 0.4f)
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(chromaKey = next) else it })
        }
    }

    /** A shot or overlay placed whole in one quarter of a 2x2 grid, where GridTile.placement puts it. */
    fun gridTile(clipId: String, at: com.squish.app.timeline.GridPlacement) = record("Grid") {
        mutateTimeline { it.withGridTile(clipId, at) }
    }

    /** An overlay made one half of a split screen, the shot under it the other, laid out by SplitSide.layout. */
    fun splitScreen(clipId: String, at: com.squish.app.timeline.SplitLayout) = record("Split screen") {
        mutateTimeline { it.withSplitScreen(clipId, at) }
    }

    // ---- Masking --------------------------------------------------------------

    fun setMask(clipId: String, mask: Mask?) = record(if (mask == null) "Remove mask" else "Mask") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = mask) else it })
        }
    }

    /**
     * Changes the mask. A choice - shape, mode, invert - is a step; a slider is a
     * gesture of its own, per slider and per clip, so dragging Width and then
     * Height is two steps however quickly one follows the other.
     */
    fun updateMask(
        clipId: String,
        shape: MaskShape? = null,
        centerX: Float? = null,
        centerY: Float? = null,
        width: Float? = null,
        height: Float? = null,
        rotation: Float? = null,
        feather: Float? = null,
        cornerRadius: Float? = null,
        inverted: Boolean? = null,
        mode: MaskMode? = null,
        strength: Float? = null
    ) {
        val sliders = fieldsNamed(
            "x" to centerX, "y" to centerY, "width" to width, "height" to height,
            "rotation" to rotation, "feather" to feather, "corner" to cornerRadius, "strength" to strength
        )
        record("Mask", gesture = if (sliders.isEmpty()) null else "Mask $sliders $clipId") {
            mutateTimeline { timeline ->
                val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
                val held = clip.mask ?: Mask()
                // On a keyed shape the sliders read and write the shape *at the
                // playhead*, not the one static shape - writing that would move
                // the slider and leave the picture alone, which is the rule a
                // keyed level and a keyed opacity already follow.
                val sourceMs = clip.sourceAt(timeline.playheadMs)
                val current = held.at(sourceMs)
                val next = current.copy(
                    shape = shape ?: current.shape,
                    centerXFraction = (centerX ?: current.centerXFraction).coerceIn(-1.5f, 1.5f),
                    centerYFraction = (centerY ?: current.centerYFraction).coerceIn(-1.5f, 1.5f),
                    widthFraction = (width ?: current.widthFraction).coerceIn(0.02f, 2f),
                    heightFraction = (height ?: current.heightFraction).coerceIn(0.02f, 2f),
                    rotationDegrees = (rotation ?: current.rotationDegrees).coerceIn(-180f, 180f),
                    feather = (feather ?: current.feather).coerceIn(0.001f, 0.5f),
                    cornerRadius = (cornerRadius ?: current.cornerRadius).coerceIn(0f, 1f),
                    inverted = inverted ?: current.inverted,
                    mode = mode ?: current.mode,
                    strength = (strength ?: current.strength).coerceIn(0f, 1f)
                )
                val written = held.withShapeAt(sourceMs, next)
                timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = written) else it })
            }
        }
    }

    /**
     * The keyframe button on the Mask sheet: a key at the playhead holding the
     * shape the mask has there, or - with one already there - that key taken
     * off again. The first key pins the shape; a second one elsewhere is what
     * makes it move.
     */
    fun toggleMaskKey(clipId: String) = record("Mask keyframe") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val mask = clip.mask ?: return@mutateTimeline timeline
            val sourceMs = clip.sourceAt(timeline.playheadMs)
            val next = if (mask.keys.hasShapeKeyNear(sourceMs)) mask.withShapeKeyRemoved(sourceMs)
            else mask.withShapeKeyAdded(sourceMs)
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = next) else it })
        }
    }

    /** Every shape key off, the shape left standing where the playhead has it. */
    fun clearMaskKeys(clipId: String) = record("Clear mask keyframes") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val mask = clip.mask ?: return@mutateTimeline timeline
            if (mask.keys.isEmpty()) return@mutateTimeline timeline
            val settled = mask.at(clip.sourceAt(timeline.playheadMs)).copy(keys = emptyList())
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = settled) else it })
        }
    }
}
