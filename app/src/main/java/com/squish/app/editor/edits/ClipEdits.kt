package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.media.SquishError
import com.squish.app.media.StillClips
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SlowMotion
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.rippleVideo
import com.squish.app.timeline.withClipMoved
import com.squish.app.timeline.withClipRemoved
import com.squish.app.timeline.withClipDuplicated
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withPlacementReset
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.LaneItem
import com.squish.app.timeline.TimelineLanes
import com.squish.app.timeline.withClipPlaced
import com.squish.app.timeline.withClipReordered
import com.squish.app.timeline.withGapClosed
import com.squish.app.timeline.withClipRetimed
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import com.squish.app.editor.*

/**
 * Edits to the clips themselves and to the timeline they sit on: adding, cutting,
 * moving and trimming; speed; placement and keyframes; markers; frame and look;
 * the timed effects and templates.
 */
internal class ClipEdits(host: EditHost) : EditArea(host) {

    // ---- Markers --------------------------------------------------------------

    fun addMarkerAtPlayhead() = record("Add marker") {
        val position = _state.value.playheadMs
        _state.update { current ->
            if (current.markers.any { abs(it - position) < current.frameMs }) current
            else current.copy(markers = (current.markers + position).sorted())
        }
    }

    fun clearMarkers() = record("Clear markers") {
        _state.update { it.copy(markers = emptyList()) }
    }

    fun setSnapToMarkers(enabled: Boolean) = _state.update { it.copy(snapToMarkers = enabled) }

    // ---- Frame and look -------------------------------------------------------

    fun toggleRotate() = record("Rotate") {
        _state.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    /** Rotate's Reset: the edit the way up it was shot. */
    fun resetRotation() = record("Rotate") {
        _state.update { it.copy(rotationDegrees = 0) }
    }

    /** Ratio's Reset: the whole picture, uncropped and not following anything - one step. */
    fun resetCrop() = record("Crop") {
        _state.update { it.copy(cropAspect = CropAspect.Original, reframe = null) }
    }

    fun setCropAspect(aspect: CropAspect) = record("Crop") {
        _state.update { current ->
            // Switching to Custom starts from whatever was already framed, not
            // from the whole picture. Having chosen a square and then wanting it
            // moved off centre, being handed the full frame back means doing the
            // work twice.
            val rect = if (aspect == CropAspect.Custom) {
                current.effectiveCrop.takeIf { !it.isFull }
                    ?: CropRect.centred(1f, current.sourceFrameAspect)
            } else {
                current.cropRect
            }
            current.copy(cropAspect = aspect, cropRect = rect)
        }
    }

    /**
     * Moves the hand-drawn crop.
     *
     * Not recorded per drag event - the drag is one gesture, so it is one undo
     * step rather than one per frame of movement.
     */
    fun setCropRect(rect: CropRect) = record("Crop", gesture = "Crop rect") {
        _state.update { it.copy(cropAspect = CropAspect.Custom, cropRect = rect) }
    }

    // ---- Speed ----------------------------------------------------------------

    /**
     * Which clip the speed controls act on.
     *
     * Whatever is selected, of either kind. A sound can be ramped too - pitching a
     * music bed up into a drop is the same operation as ramping a shot, and making
     * it a special case would mean writing it twice.
     */
    fun speedTargetClip(current: EditorUiState = _state.value): Clip? =
        (current.videoClips + current.audioClips).firstOrNull { it.id == current.selectedClipId }
            ?: current.videoClips.firstOrNull()

    /**
     * Retimes a clip, and closes up behind it.
     *
     * A clip's length on the strip is derived from its ramp, so changing the ramp
     * moves where everything after it starts. Leaving those in place would open a
     * gap on a speed-up and overlap on a slow-down - and an overlap on the base
     * track is read as a transition, so slowing one shot would quietly dissolve it
     * into the next.
     */
    private fun retime(clipId: String, ramp: SpeedRamp) {
        mutateTimeline { timeline ->
            val target = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            // The main track ripples in the model, joins and all (see withClipRetimed).
            if (target.isMain) return@mutateTimeline timeline.withClipRetimed(clipId, ramp)
            val updated = timeline.clips.map { if (it.id == clipId) it.copy(speedRamp = ramp) else it }
            timeline.copy(clips = resequenceAfterRetime(updated, target))
        }
    }

    /**
     * Re-lays the clips that shared a lane with the retimed one.
     *
     * Only the ones after it, only on its own layer and kind, and only when they
     * were butted up against what came before - a clip the editor deliberately
     * placed in a gap stays where it was put. Anything else would make a speed
     * change silently rearrange an edit someone had already timed by hand.
     */
    private fun resequenceAfterRetime(clips: List<Clip>, target: Clip): List<Clip> {
        val lane = clips
            .filter { it.kind == target.kind && it.layer == target.layer }
            .sortedBy { it.timelineStartMs }
        val startIndex = lane.indexOfFirst { it.id == target.id }
        if (startIndex < 0) return clips

        val moved = HashMap<String, Long>()
        // Where the retimed clip now ends, and where it used to. The first is what
        // the followers are moved to; the second is what decides which of them
        // were following in the first place.
        var cursor = lane[startIndex].timelineEndMs
        var previousEnd = target.timelineEndMs

        for (i in startIndex + 1 until lane.size) {
            val next = lane[i]
            // A gap the editor put there is part of the edit. Only a butt cut,
            // within a frame or so, is treated as "follows on from".
            if (next.timelineStartMs - previousEnd > TOUCHING_MS) break
            moved[next.id] = cursor
            previousEnd = next.timelineEndMs
            cursor += next.durationMs
        }

        return clips.map { clip -> moved[clip.id]?.let { clip.copy(timelineStartMs = it) } ?: clip }
    }

    /**
     * One rate across the whole clip, which is what the slider and presets set.
     * [dragging] is the slider: its frames are one step, where two taps on the
     * preset chips are two.
     */
    fun setClipSpeed(clipId: String, speed: Float, dragging: Boolean = false) =
        record("Speed", gesture = if (dragging) "Speed $clipId" else null) {
            retime(clipId, SpeedRamp.flat(speed))
        }

    /**
     * "Keep it smooth": nothing in the clip slower than its footage can carry.
     *
     * Raises only the slow parts. It used to set one flat rate for the whole clip,
     * so a bullet-time ramp - fast, slow, fast - silently became a constant crawl.
     */
    fun keepSmooth(clipId: String) {
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        val limit = SlowMotion.smoothestSpeed(current.fps)
        record("Keep it smooth") { retime(clipId, EditRules.heldAtLeast(clip.speedRamp, limit)) }
    }

    /** Lays a ready-made ramp across the clip. */
    fun applyRampShape(clipId: String, shape: RampShape) {
        val clip = _state.value.let { current ->
            (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }
        } ?: return
        record("Speed ramp") { retime(clipId, SpeedRamp.preset(shape, clip.sourceSpanMs)) }
    }

    /** Adds or moves a control point, at the source frame under the playhead. */
    fun setSpeedPointAtPlayhead(clipId: String, speed: Float) {
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        // The playhead is in played time; a point is anchored in source time, so
        // that editing the curve elsewhere does not drag this point along with it.
        val at = (clip.sourceAt(current.playheadMs) - clip.sourceInMs).coerceIn(0L, clip.sourceSpanMs)
        val base = if (clip.speedRamp.ordered.isEmpty()) {
            SpeedRamp.flat(1f)
        } else {
            clip.speedRamp
        }
        record("Speed point") { retime(clipId, base.withPoint(at, speed, clip.sourceSpanMs)) }
    }

    fun removeSpeedPoint(clipId: String, atMs: Long) {
        val clip = _state.value.let { current ->
            (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }
        } ?: return
        record("Remove speed point") { retime(clipId, clip.speedRamp.withoutPoint(atMs)) }
    }

    fun clearSpeed(clipId: String) = record("Reset speed") {
        retime(clipId, SpeedRamp())
    }

    /**
     * Picks a look. Choosing the same one again clears it, so the chip you just
     * tapped is also the way back to the untouched picture.
     */
    fun setLook(lookId: String?) = record("Look") {
        _state.update { current ->
            val next = if (lookId == null || lookId == current.lookId) null else lookId
            current.copy(
                lookId = next,
                lookIntensity = if (next == null) 1f else current.lookIntensity
            )
        }
    }

    fun setLookIntensity(value: Float) = record("Look strength", gesture = "Look strength") {
        _state.update { it.copy(lookIntensity = value.coerceIn(0f, 1f)) }
    }

    fun setBrightness(value: Float) = record("Brightness", gesture = "Brightness") {
        _state.update { it.copy(brightness = value) }
    }
    fun setContrast(value: Float) = record("Contrast", gesture = "Contrast") {
        _state.update { it.copy(contrast = value) }
    }
    fun setSaturation(value: Float) = record("Saturation", gesture = "Saturation") {
        _state.update { it.copy(saturation = value) }
    }

    /** Adjust's Reset: all three back to where they started, as one step. */
    fun resetAdjust() = record("Adjust") {
        _state.update { it.copy(brightness = 0f, contrast = 0f, saturation = 0f) }
    }

    // ---- Templates ----------------------------------------------------------------

    /**
     * Applies [template] as one undoable step. Replaces the look and the frame
     * shape, and the effects and title an earlier template added; keeps every
     * caption, sticker and effect added by hand.
     */
    fun applyTemplate(template: Template) = record("Template ${template.label}") {
        _state.update { current ->
            val total = current.timelineDurationMs.coerceAtLeast(1L)
            val placed = template.effects.map { (kind, at) ->
                val start = (total * at).toLong().coerceIn(0L, (total - MIN_EFFECT_MS).coerceAtLeast(0L))
                // A slow push fills the whole edit; the others are a moment each.
                val length = if (kind == EffectKind.ZoomIn) total else DEFAULT_EFFECT_MS
                TimedEffect(
                    id = TEMPLATE_PREFIX + UUID.randomUUID(),
                    kind = kind,
                    startMs = start,
                    endMs = (start + length).coerceAtMost(total)
                )
            }
            val title = template.title?.let { preset ->
                TextOverlayItem(
                    id = TEMPLATE_PREFIX + UUID.randomUUID(),
                    text = template.titleText ?: preset.sample,
                    startMs = 0L,
                    endMs = DEFAULT_TITLE_MS.coerceAtMost(total),
                    colorArgb = preset.colorArgb,
                    yFraction = preset.yFraction,
                    sizeSp = preset.sizeSp,
                    font = preset.font,
                    look = preset.look,
                    motion = preset.motion
                )
            }
            current.copy(
                cropAspect = template.crop ?: CropAspect.Original,
                lookId = template.lookId,
                lookIntensity = 1f,
                effects = current.effects.filterNot { it.id.startsWith(TEMPLATE_PREFIX) } + placed,
                textOverlays = current.textOverlays.filterNot { it.id.startsWith(TEMPLATE_PREFIX) } +
                    listOfNotNull(title)
            )
        }
    }

    // ---- Effects library --------------------------------------------------------

    /** An effect from the playhead for [DEFAULT_EFFECT_MS], or to the end if that is sooner. */
    fun addEffect(kind: EffectKind) = record("Add ${kind.label}") {
        val current = _state.value
        val total = current.timelineDurationMs
        val start = current.playheadMs.coerceIn(0L, (total - MIN_EFFECT_MS).coerceAtLeast(0L))
        val end = (start + DEFAULT_EFFECT_MS).coerceAtMost(total.takeIf { it > start } ?: (start + DEFAULT_EFFECT_MS))
        val effect = TimedEffect(id = UUID.randomUUID().toString(), kind = kind, startMs = start, endMs = end)
        _state.update { it.copy(effects = it.effects + effect) }
    }

    fun changeEffect(id: String, change: (TimedEffect) -> TimedEffect) = record("Effect change", gesture = "Effect $id") {
        _state.update { current ->
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) e else change(e).let { c ->
                    // Never shorter than a tenth of a second, never inside out.
                    val start = c.startMs.coerceAtLeast(0L)
                    c.copy(startMs = start, endMs = c.endMs.coerceAtLeast(start + MIN_EFFECT_MS))
                }
            })
        }
    }

    /** Slides an effect along the timeline, keeping its length and staying inside the edit. */
    fun moveEffect(id: String, deltaMs: Long) = record("Move effect", gesture = "Move $id") {
        _state.update { current ->
            val total = current.timelineDurationMs
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) return@map e
                val delta = deltaMs.coerceIn(-e.startMs, (total - e.endMs).coerceAtLeast(0L))
                e.copy(startMs = e.startMs + delta, endMs = e.endMs + delta)
            })
        }
    }

    /** Pulls an effect's start and end by the given amounts, never past each other or the edit's ends. */
    fun trimEffect(id: String, startDeltaMs: Long, endDeltaMs: Long) = record("Trim effect", gesture = "Trim $id") {
        _state.update { current ->
            val total = current.timelineDurationMs
            current.copy(effects = current.effects.map { e ->
                if (e.id != id) return@map e
                val start = (e.startMs + startDeltaMs).coerceIn(0L, (e.endMs - MIN_EFFECT_MS).coerceAtLeast(0L))
                val end = (e.endMs + endDeltaMs).coerceIn(start + MIN_EFFECT_MS, maxOf(total, start + MIN_EFFECT_MS))
                e.copy(startMs = start, endMs = end)
            })
        }
    }

    fun removeEffect(id: String) = record("Remove effect") {
        _state.update { it.copy(effects = it.effects.filterNot { e -> e.id == id }) }
    }

    // ---- Timeline -------------------------------------------------------------

    fun addVideoClip(uri: Uri) = addVideoClips(listOf(uri))

    /**
     * Adds videos to the end of the main track, in the order they were picked.
     *
     * Probed one after another and added in a single step, so the order is the
     * picking order - not whichever file happened to finish probing first - and
     * one undo takes back the whole batch. The strip is refitted afterwards so
     * what was just added is on screen rather than past its right-hand edge.
     */
    fun addVideoClips(uris: List<Uri>) = addVideoSources(uris, atPlayhead = false)

    /**
     * Adds videos and photos to the main track at the playhead - on the nearer cut
     * of the shot under it, never inside one - and moves everything after along
     * to make room. The way a picked clip lands in CapCut; the append above is
     * what the strip's "+" at the end of the track means.
     */
    fun insertSourcesAtPlayhead(uris: List<Uri>) = addVideoSources(uris, atPlayhead = true)

    private fun addVideoSources(uris: List<Uri>, atPlayhead: Boolean) {
        if (uris.isEmpty()) return
        // Where the playhead was when the files were picked, not where it has got
        // to by the time the photos have been rendered into clips.
        val at = if (atPlayhead) _state.value.playheadMs else null
        viewModelScope.launch {
            // Photos come in the same pick as videos. Each is made into a short
            // clip first (see StillClips); the order picked is kept either way.
            val resolver = app.contentResolver
            val isPhoto = uris.associateWith { resolver.getType(it)?.startsWith("image/") == true }
            val photos = isPhoto.count { it.value }
            if (photos > 0) _state.update { it.copy(preparingStills = it.preparingStills + photos) }
            // Counted down only once the clips are on the timeline, not when each
            // photo finishes rendering: probing and placing them comes after, and
            // an export started in that gap left the photo out of the file.
            try {
                val sources = uris.map { uri ->
                    if (isPhoto[uri] == true) {
                        StillClips.fromImage(app, uri)?.let { StillSource(it, displayNameOf(uri) ?: "Photo") }
                    } else {
                        StillSource(uri, null)
                    }
                }
                addSources(sources.filterNotNull(), failedAny = sources.any { it == null }, at = at)
            } finally {
                if (photos > 0) {
                    _state.update { s -> s.copy(preparingStills = (s.preparingStills - photos).coerceAtLeast(0)) }
                }
            }
        }
    }

    /** A blank - plain black, the edit's own shape - at the end of the video track. */
    fun addBlankClip() {
        viewModelScope.launch {
            _state.update { it.copy(preparingStills = it.preparingStills + 1) }
            try {
                val current = _state.value
                val made = StillClips.blank(app, current.framedWidth, current.framedHeight)
                if (made == null) {
                    _state.update { it.copy(failure = SquishError.Unknown(null)) }
                    return@launch
                }
                addSources(listOf(StillSource(made, "Blank")), failedAny = false)
            } finally {
                // After it is placed, for the reason given in addVideoClips.
                _state.update { it.copy(preparingStills = (it.preparingStills - 1).coerceAtLeast(0)) }
            }
        }
    }

    /**
     * A file to put on the video track, and the name to show for it. A still is
     * rendered longer than it is placed, so its end can be dragged out; [label]
     * is set for those, and marks them.
     */
    private data class StillSource(val uri: Uri, val label: String?)

    /**
     * Puts probed files on the main track: at the end, or with [at] set, into the
     * track at the cut nearest that moment, with every later main-track clip
     * moved along by what was added.
     */
    private suspend fun addSources(sources: List<StillSource>, failedAny: Boolean, at: Long? = null) {
        if (sources.isEmpty()) {
            _state.update { it.copy(failure = SquishError.FileUnreadable()) }
            return
        }
        if (failedAny) _state.update { it.copy(failure = SquishError.FileUnreadable()) }
        run {
            val probed = sources.map { src ->
                val meta = ThumbnailExtractor.probe(app, src.uri)
                Triple(src.uri, meta, src.label)
            }.filter { (_, meta, _) -> meta.durationMs > 0L }
            if (probed.isEmpty()) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return
            }
            val what = when {
                probed.size > 1 -> "${probed.size} clips"
                probed[0].third == "Blank" -> "blank"
                probed[0].third != null -> "photo"
                else -> "clip"
            }
            record("Add $what") {
                _state.update { current ->
                    val base = current.videoClips.filter { it.layer == 0 }.sortedBy { it.timelineStartMs }
                    // A still lands short and can be dragged out to its full render.
                    val lengths = probed.map { (_, meta, label) ->
                        if (label != null) minOf(StillClips.DEFAULT_MS, meta.durationMs) else meta.durationMs
                    }
                    val insertion = if (at == null) null
                    else EditRules.insertion(base.map { Span(it.timelineStartMs, it.timelineEndMs) }, at, lengths)
                    val insertAt = insertion?.atMs ?: base.maxOfOrNull { c -> c.timelineEndMs } ?: 0L
                    var start = insertAt
                    val added = probed.mapIndexed { i, (uri, meta, label) ->
                        val placed = lengths[i]
                        Clip(
                            kind = ClipKind.Video,
                            uri = uri,
                            label = label ?: displayNameOf(uri) ?: "Clip ${current.videoClips.size + i + 1}",
                            sourceInMs = 0,
                            sourceOutMs = placed,
                            timelineStartMs = start,
                            sourceDurationMs = meta.durationMs
                        ).also { start += placed }
                    }
                    // Only when inserting: an append has nothing after it to move.
                    // Which clips follow is by track order, not by start time - a
                    // clip transitioning in starts before the cut it follows.
                    val followers = insertion?.let { ins -> base.drop(ins.index).map { it.id }.toSet() }.orEmpty()
                    val existing = if (insertion == null) current.videoClips else current.videoClips.map { c ->
                        if (c.id in followers) c.copy(timelineStartMs = c.timelineStartMs + insertion.followersShiftMs)
                        else c
                    }
                    current.copy(
                        videoClips = existing + added,
                        selectedClipId = if (at == null) current.selectedClipId else added.first().id,
                        fitNonce = current.fitNonce + 1
                    )
                }
            }
            recomputeEstimate()
            probed.forEach { (uri, _, label) -> if (label == null) checkDecodable(uri) }
        }
    }

    /**
     * Moves a main-track clip to [index] in the track's order and lays the track
     * end to end again - long-press, drag, drop. Transitions and everything else
     * on the clip travel with it; the clip that ends up first has nothing to
     * transition in from, so its transition does nothing until it moves again.
     */
    fun reorderClip(clipId: String, index: Int) {
        val base = _state.value.videoClips.filter { it.layer == 0 }.sortedBy { it.timelineStartMs }
        if (base.none { it.id == clipId }) return
        val order = EditRules.reordered(base.map { it.id }, clipId, index)
        if (order == base.map { it.id }) return
        // The model's own reorder, which keeps any spacing an old draft's shots
        // had from each other. Laying the track out from scratch closed every gap
        // in the edit on a reorder, and slid the shots after them out from under
        // the sound placed against them.
        record("Reorder") { mutateTimeline { it.withClipReordered(clipId, index) } }
    }

    /**
     * A clip carried on the strip and let go: to start at [startMs] on [row] -
     * an overlay's layer, or which of the sound or text rows it is drawn on (see
     * [withClipPlaced]). A line of words keeps inside the picture, as a drag
     * always kept it; the other lines keep the rows they are shown on, so only
     * the one carried changes row.
     */
    fun placeClip(clipId: String, startMs: Long, row: Int) = record("Move clip") {
        val text = _state.value.textOverlays.firstOrNull { it.id == clipId }
        if (text == null) {
            mutateTimeline { it.withClipPlaced(clipId, startMs, row) }
            return@record
        }
        _state.update { s ->
            val prefs = TimelineLanes.preferencesAfterMove(
                // As the strip draws them (see toTimeline): never shorter than a clip can be.
                s.textOverlays.map {
                    LaneItem(it.id, it.startMs, it.startMs + (it.endMs - it.startMs).coerceAtLeast(MIN_CLIP_MS), it.stripRow)
                },
                clipId,
                row
            )
            val delta = EditRules.clampedShift(text.startMs, text.endMs, startMs - text.startMs, s.trimmedDurationMs)
            s.copy(
                textOverlays = s.textOverlays.map { item ->
                    val lane = prefs[item.id] ?: item.stripRow
                    if (item.id != clipId) item.copy(stripRow = lane)
                    else item.copy(startMs = item.startMs + delta, endMs = item.endMs + delta, stripRow = lane)
                }
            )
        }
    }

    /**
     * A trim handle dragged: [clipId]'s head or tail to [edgeMs] on the timeline,
     * as near as the clip allows. Worked out here, from the clip as it is now,
     * rather than sent as a step by the strip: a second touch event can arrive
     * before the first one's trim is on screen, and a step worked out from what
     * the strip last drew would be applied twice. One drag is one undo step.
     */
    fun trimEdgeTo(clipId: String, head: Boolean, edgeMs: Long) {
        val current = _state.value
        val text = current.textOverlays.firstOrNull { it.id == clipId }
        if (text != null) {
            val delta = edgeMs - if (head) text.startMs else text.endMs
            if (delta != 0L) trimClip(clipId, if (head) delta else 0L, if (head) 0L else delta)
            return
        }
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        val (startDelta, endDelta) = TimelineLanes.edgeTrim(clip, head, edgeMs)
        if (startDelta != 0L || endDelta != 0L) trimClip(clipId, startDelta, endDelta)
    }

    /**
     * The head handle of a main-track shot, whose edge stays butted to the shot
     * before it: the file to start at [sourceInMs] (see TimelineLanes.anchoredHeadIn).
     */
    fun trimHeadInTo(clipId: String, sourceInMs: Long) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        if (sourceInMs != clip.sourceInMs) trimClip(clipId, sourceInMs - clip.sourceInMs, 0L)
    }

    /** An effect's handle dragged: its head or tail to [edgeMs], worked out from the effect as it is now. */
    fun trimEffectTo(id: String, head: Boolean, edgeMs: Long) {
        val effect = _state.value.effects.firstOrNull { it.id == id } ?: return
        val delta = edgeMs - if (head) effect.startMs else effect.endMs
        if (delta != 0L) trimEffect(id, if (head) delta else 0L, if (head) 0L else delta)
    }

    /**
     * The one gap on the main track before [clipId] closed - an old draft's; the
     * track has been magnetic since. The shots after it move up; every other gap,
     * and whatever was placed against the picture, stays where it was.
     */
    fun closeGap(clipId: String) = record("Close gap") { mutateTimeline { it.withGapClosed(clipId) } }

    // ---- Motion and keyframes ---------------------------------------------------

    /**
     * Edits the placement of a clip at the playhead.
     *
     * If the clip is not animated this simply moves it. If it *is* animated, the
     * edit lands as a keyframe at the playhead - creating one if there is not
     * already a key there. That is auto-keying, and it is how every editor behaves:
     * once you have said "this shot moves", changing the picture at a moment in time
     * can only sensibly mean "and here is where it should be at that moment".
     *
     * Starts from the user's own transform, never the drawn one (the
     * stabilizer's correction is measured, not placed). Each slider on each clip
     * is its own gesture.
     *
     * The one way placement is written, for shots and overlays alike, through
     * [withOverlayGeometry]: with the playhead off an animated clip there is no
     * moment of it to key, and keying the nearer end - which this did on its own
     * - quietly rewrote the end of the move; there the whole move shifts instead.
     */
    fun setClipTransform(
        clipId: String,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null,
        rotation: Float? = null
    ) = record(
        "Placement",
        gesture = "Motion ${fieldsNamed("scale" to scale, "x" to offsetX, "y" to offsetY, "rotation" to rotation)} $clipId"
    ) {
        mutateTimeline { it.withOverlayGeometry(clipId, scale = scale, offsetX = offsetX, offsetY = offsetY, rotation = rotation) }
    }

    /**
     * Placement's Reset: the clip still again, where a clip of its kind lands -
     * filling the frame on the main track, the corner an overlay is added to -
     * one undo step. It used to set a full-frame placement through the
     * auto-keying path, which on an animated clip added a snap-to-full-frame key
     * in the middle of the move instead of undoing it, and blew a corner overlay
     * up over the whole picture.
     */
    fun resetPlacement(clipId: String) = record("Reset placement") {
        mutateTimeline { it.withPlacementReset(clipId) }
    }

    /**
     * Pins the clip's placement at the playhead as a control point - the
     * placement the editor set, not the stabilizer's correction for this one frame.
     */
    fun addKeyframeAtPlayhead(clipId: String) = record("Add key") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val at = (timeline.playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
            val here = clip.userTransformAt(timeline.playheadMs)
            val updated = clip.copy(keyframes = clip.keyframes.upsert(Keyframe(at, here, easingNear(clip, at))))
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    fun removeKeyframe(clipId: String, atMs: Long) = record("Remove key") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val updated = clip.copy(keyframes = clip.keyframes.filterNot { it.atMs == atMs })
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    fun setKeyframeEasing(clipId: String, atMs: Long, easing: KeyframeEasing) = record("Key easing") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val updated = clip.copy(
                keyframes = clip.keyframes.map { if (it.atMs == atMs) it.copy(easing = easing) else it }
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /** Drops the animation, leaving the clip wherever it was on its first frame. */
    fun clearKeyframes(clipId: String) = record("Clear keys") {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            // The pose on the clip's first frame, which is the first key's only
            // while no trim has hidden a key before it.
            val settled = clip.placementAt(clip.timelineStartMs)
            val updated = clip.copy(
                keyframes = emptyList(),
                scale = settled.scale,
                offsetXFraction = settled.offsetXFraction,
                offsetYFraction = settled.offsetYFraction,
                rotation = settled.rotationDegrees
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /** The one-tap moves people actually want, as a pair of keys across the clip. */
    fun applyMotionPreset(clipId: String, preset: MotionPreset) = record(preset.label) {
        mutateTimeline { timeline ->
            val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
            val end = clip.durationMs.coerceAtLeast(MIN_CLIP_MS)
            val (from, to) = preset.endpoints()
            val updated = clip.copy(
                keyframes = listOf(
                    Keyframe(0L, from, KeyframeEasing.Smooth),
                    Keyframe(end, to, KeyframeEasing.Smooth)
                )
            )
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) updated else it })
        }
    }

    /**
     * A new key inherits the easing of the segment it lands in, so inserting a
     * control point in the middle of a smooth move does not put a linear kink in it.
     */
    private fun easingNear(clip: Clip, atMs: Long): KeyframeEasing =
        clip.keyframes.lastOrNull { it.atMs <= atMs }?.easing
            ?: clip.keyframes.firstOrNull()?.easing
            ?: KeyframeEasing.Smooth

    /**
     * Inserts a key, or replaces the one already at that moment. The tolerance is a
     * frame: two keys a millisecond apart are a fight, not an animation.
     */
    private fun List<Keyframe>.upsert(key: Keyframe): List<Keyframe> {
        val tolerance = _state.value.frameMs
        val without = filterNot { abs(it.atMs - key.atMs) <= tolerance }
        return (without + key).sortedBy { it.atMs }
    }

    /** Timeline drag. Each lane writes back to whichever model owns it. */
    /**
     * A strip drag: the clip to start at [startMs], as near as its track allows.
     * The step is taken from the clip as it is now, not as the strip last drew it
     * - a second touch event can arrive before the first one's move is on screen,
     * and a step worked out there would be applied twice.
     */
    fun moveClipTo(clipId: String, startMs: Long) {
        val current = _state.value
        val from = current.textOverlays.firstOrNull { it.id == clipId }?.startMs
            ?: (current.videoClips + current.audioClips).firstOrNull { it.id == clipId }?.timelineStartMs
            ?: return
        if (startMs != from) moveClip(clipId, startMs - from)
    }

    // One gesture per clip, so dragging one, then another, is two steps - but
    // the hundred frames of a single drag are one. A drag on the main track that
    // has not yet crossed a neighbour's middle changes nothing, and [record]
    // files nothing for it.
    fun moveClip(clipId: String, deltaMs: Long) = record("Move clip", gesture = "Move $clipId") {
        if (_state.value.textOverlays.any { it.id == clipId }) shiftOverlay(clipId, deltaMs)
        else mutateTimeline { it.withClipMoved(clipId, deltaMs) }
    }

    /** Timeline edge drag - the handles on a selected clip. One drag is one step. */
    fun trimClip(clipId: String, startDeltaMs: Long, endDeltaMs: Long) =
        record("Trim clip", gesture = "Trim $clipId") { applyTrim(clipId, startDeltaMs, endDeltaMs) }

    private fun applyTrim(clipId: String, startDeltaMs: Long, endDeltaMs: Long) {
        if (_state.value.textOverlays.any { it.id == clipId }) resizeOverlay(clipId, startDeltaMs, endDeltaMs)
        else mutateTimeline { it.withClipTrimmed(clipId, startDeltaMs, endDeltaMs) }
    }

    /**
     * Cut at the playhead.
     *
     * With a caption or sticker selected and the playhead on it, that item is cut
     * in two, same words and style on both sides; with an effect, the effect - a
     * sliver too short to stand is refused, as a clip's is. Otherwise a razor
     * through picture and sound alike - this used to be handed only the video
     * clips, which is why a music bed could never be cut on the strip.
     *
     * A selected caption used to be ignored: the button lit, the caption stayed
     * whole, and the video and music underneath were cut instead. A cut that
     * changes nothing - no clip under the playhead, or a sliver too short to
     * split - no longer leaves an "Undo: Cut" that undoes nothing.
     */
    fun splitAtPlayhead() {
        val current = _state.value
        val selected = current.selectedClipId
        val at = current.playheadMs
        val text = current.textOverlays.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }
        if (text != null) {
            val halves = EditRules.splitAt(text.startMs, text.endMs, at, MIN_CLIP_MS) ?: return
            record("Cut") {
                val second = text.copy(
                    id = if (text.isAutoCaption) AUTO_CAPTION_PREFIX + UUID.randomUUID() else UUID.randomUUID().toString(),
                    startMs = halves.second.startMs,
                    endMs = halves.second.endMs
                )
                _state.update { s ->
                    s.copy(
                        textOverlays = s.textOverlays.flatMap {
                            if (it.id == text.id) listOf(it.copy(endMs = halves.first.endMs), second) else listOf(it)
                        },
                        selectedClipId = second.id
                    )
                }
            }
            return
        }
        val effect = current.effects.firstOrNull { it.id == selected && EditRules.cutsItem(it.startMs, it.endMs, at) }
        if (effect != null) {
            val halves = EditRules.splitAt(effect.startMs, effect.endMs, at, MIN_EFFECT_MS) ?: return
            record("Cut") {
                val second = effect.copy(
                    id = UUID.randomUUID().toString(),
                    startMs = halves.second.startMs,
                    endMs = halves.second.endMs
                )
                _state.update { s ->
                    s.copy(
                        effects = s.effects.flatMap {
                            if (it.id == effect.id) listOf(it.copy(endMs = halves.first.endMs), second) else listOf(it)
                        },
                        selectedClipId = second.id
                    )
                }
            }
            return
        }
        record("Cut") { mutateTimeline { it.withSplitAtPlayhead() } }
    }

    /** Pull the base track back end to end. Deliberate, never automatic. */
    fun closeGaps() = record("Close gaps") { mutateTimeline { it.rippleVideo() } }

    fun deleteSelectedClip() {
        val selected = _state.value.selectedClipId ?: return
        record("Delete") {
            if (_state.value.textOverlays.any { it.id == selected }) dropTextOverlay(selected)
            else if (_state.value.effects.any { it.id == selected }) {
                _state.update { it.copy(effects = it.effects.filterNot { e -> e.id == selected }) }
            } else mutateTimeline { it.withClipRemoved(selected) }
            _state.update { it.copy(selectedClipId = null) }
        }
    }

    /**
     * A copy of whatever is selected, straight after it, and selected in its
     * place so the next tap works on the copy. A shot is slotted into the main
     * track (see [withClipDuplicated]); a caption, sticker or effect goes where
     * the original ends, as long as it is, kept inside the picture.
     */
    fun duplicateSelected() {
        val current = _state.value
        val selected = current.selectedClipId ?: return
        val text = current.textOverlays.firstOrNull { it.id == selected }
        val effect = current.effects.firstOrNull { it.id == selected }
        when {
            text != null -> {
                val span = EditRules.placedAt(text.endMs, text.endMs - text.startMs, current.trimmedDurationMs, MIN_CLIP_MS)
                // A hand-made copy, even of an auto-caption: another run of
                // auto-captions replaces its own lines, not ones made from them.
                val copy = text.copy(id = UUID.randomUUID().toString(), startMs = span.startMs, endMs = span.endMs)
                record("Duplicate") {
                    _state.update { it.copy(textOverlays = it.textOverlays + copy, selectedClipId = copy.id) }
                }
            }
            effect != null -> {
                val total = current.timelineDurationMs
                val length = effect.endMs - effect.startMs
                val start = effect.endMs.coerceAtMost((total - length).coerceAtLeast(0L))
                val copy = effect.copy(id = UUID.randomUUID().toString(), startMs = start, endMs = start + length)
                record("Duplicate") {
                    _state.update { it.copy(effects = it.effects + copy, selectedClipId = copy.id) }
                }
            }
            else -> {
                val copyId = UUID.randomUUID().toString()
                record("Duplicate") { mutateTimeline { it.withClipDuplicated(selected, copyId) } }
                // Every overlay row is taken where the copy would go: said, rather
                // than a button that did nothing.
                val after = _state.value
                if (after.videoClips.none { it.id == copyId } && after.audioClips.none { it.id == copyId } &&
                    current.videoClips.any { it.id == selected && it.isOverlay }
                ) {
                    _state.update { it.copy(failure = SquishError.OverlayRowsFull(MAX_LAYER)) }
                }
            }
        }
    }

    /** Close enough to a butt cut that a retime should carry the next clip along. */
    private val TOUCHING_MS = 40L

    /**
     * A caption or sticker dragged along the strip: whole, and inside the
     * picture. See [EditRules.clampedShift].
     */
    private fun shiftOverlay(id: String, deltaMs: Long) {
        _state.update { current ->
            val picture = current.trimmedDurationMs
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else {
                        val delta = EditRules.clampedShift(it.startMs, it.endMs, deltaMs, picture)
                        // A pinned track stays put: it says where the thing being
                        // followed is at each moment, and moving the caption in
                        // time does not move that.
                        it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta)
                    }
                }
            )
        }
    }

    /** A caption or sticker's ends dragged: see [EditRules.resized]. */
    private fun resizeOverlay(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _state.update { current ->
            val picture = current.trimmedDurationMs
            current.copy(
                textOverlays = current.textOverlays.map {
                    if (it.id != id) it
                    else {
                        val span = EditRules.resized(it.startMs, it.endMs, startDeltaMs, endDeltaMs, picture, MIN_CLIP_MS)
                        it.copy(startMs = span.startMs, endMs = span.endMs)
                    }
                }
            )
        }
    }

    private companion object {
        /** An effect lasts two seconds unless stretched - long enough to see, short enough to be a moment. */
        const val DEFAULT_EFFECT_MS = 2_000L

        /** Marks what a template added, so the next template replaces it rather than piling on. */
        const val TEMPLATE_PREFIX = "tpl-"
    }
}
