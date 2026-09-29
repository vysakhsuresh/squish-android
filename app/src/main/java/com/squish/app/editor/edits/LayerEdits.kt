package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.MAX_LAYER
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.squish.app.editor.*

/** Layers: overlays, their placement and stacking, transitions, green screen and masks. */
internal class LayerEdits(host: EditHost) : EditArea(host) {

    /** Adds a clip on an overlay layer, starting at the playhead. */
    fun addOverlayClip(uri: Uri) {
        viewModelScope.launch {
            val meta = ThumbnailExtractor.probe(app, uri)
            // Refused with a reason rather than added as an invisible layer with
            // no length, the way an unreadable sound used to be.
            if (meta.durationMs <= 0L) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            record("Add overlay") { _state.update { current ->
                val clip = Clip(
                    kind = ClipKind.Video,
                    uri = uri,
                    label = displayNameOf(uri) ?: "Overlay",
                    sourceInMs = 0,
                    sourceOutMs = meta.durationMs,
                    timelineStartMs = current.playheadMs,
                    sourceDurationMs = meta.durationMs,
                    layer = 1,
                    scale = 0.4f,
                    offsetXFraction = 0.45f,
                    offsetYFraction = -0.45f
                )
                // Onto the first row free at this moment, never on top of another
                // overlay: the preview shows one clip per row, the export all of them.
                val placed = TimelineState(clips = current.videoClips).withClipAdded(clip)
                if (placed.clips.size == current.videoClips.size) {
                    current.copy(failure = SquishError.OverlayRowsFull(MAX_LAYER))
                } else {
                    current.copy(videoClips = placed.clips, selectedClipId = clip.id)
                }
            } }
            recomputeEstimate()
            checkDecodable(uri)
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

    fun changeLayer(clipId: String, delta: Int) = record(if (delta > 0) "Raise layer" else "Lower layer") {
        mutateTimeline { it.withLayerChanged(clipId, delta) }
    }

    /**
     * A shot lifted off the main track onto the lowest overlay row free over it;
     * the track closes up behind it. With every row taken there, it says so
     * rather than leaving a button that did nothing.
     */
    fun switchToOverlay(clipId: String) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId && it.isMain } ?: return
        record("To overlay") { mutateTimeline { it.withLayerChanged(clip.id, +1) } }
        if (_state.value.videoClips.firstOrNull { it.id == clipId }?.isMain == true) {
            _state.update { it.copy(failure = SquishError.OverlayRowsFull(MAX_LAYER)) }
        }
    }

    /**
     * An overlay dropped onto the main track, from whichever row it is on, and
     * slotted in where it sits; the shots after it move along to make room.
     * Lowering one row at a time only reaches the main track from the row just
     * above it, so the clip is put on that row first - a step inside this one
     * edit, never seen - and lowered from there.
     */
    fun switchToMain(clipId: String) {
        _state.value.videoClips.firstOrNull { it.id == clipId && it.isOverlay } ?: return
        record("To main track") {
            mutateTimeline { timeline ->
                timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(layer = 1) else it })
                    .withLayerChanged(clipId, -1)
            }
        }
    }

    /** One slider on the overlay sheet. Each slider, on each clip, is a gesture of its own. */
    fun setOverlayGeometry(
        clipId: String,
        opacity: Float? = null,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null
    ) = record(
        if (opacity != null && scale == null && offsetX == null && offsetY == null) "Opacity" else "Placement",
        gesture = "Layer ${fieldsNamed("opacity" to opacity, "scale" to scale, "x" to offsetX, "y" to offsetY)} $clipId"
    ) {
        mutateTimeline { it.withOverlayGeometry(clipId, opacity, scale, offsetX, offsetY) }
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
                val current = clip.mask ?: Mask()
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
                timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(mask = next) else it })
            }
        }
    }
}
