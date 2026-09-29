package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.media.ProxyEngine
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.video.Reframer
import com.squish.app.media.video.Segmenter
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.Stabilizer
import com.squish.app.media.video.StabilizerSolve
import com.squish.app.media.video.TrackRunner
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.Transform
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.squish.app.editor.*

/**
 * Edits that start with looking at the footage: background removal, auto-reframe,
 * motion tracking and stabilization, and the frames they sample.
 */
internal class AnalysisEdits(host: EditHost, private val clips: ClipEdits) : EditArea(host) {

    private var stabilizeJob: Job? = null
    private var trackJob: Job? = null

    /** A file to run a frame analysis over, and the frame size it will produce. */
    private data class AnalysisSource(val uri: Uri, val width: Int, val height: Int)

    /**
     * Which copy of a clip the motion analyses should read.
     *
     * The proxy, whenever one exists. Both analyses downsample every frame they
     * decode to a few hundred pixels across before looking at it, so decoding a
     * 33-megabyte 4K frame to measure a 320-pixel one is thirty times the memory
     * and thirty times the wait for an identical answer. The proxy has the same
     * frame count and frame rate, so indices and timings carry over unchanged.
     *
     * Falls back to the original when no proxy has been built yet, which is safe
     * now that the batch size is budgeted against the frame size.
     */
    private fun analysisSourceFor(uri: Uri, current: EditorUiState): AnalysisSource {
        val proxy = current.proxyUri
        if (proxy != null && uri == current.sourceUri) {
            return AnalysisSource(proxy, ProxyEngine.PROXY_WIDTH_HINT, ProxyEngine.PROXY_HEIGHT)
        }
        return AnalysisSource(uri, current.sourceWidth, current.sourceHeight)
    }

    // ---- Background removal ---------------------------------------------------------

    private var backgroundJob: Job? = null

    /** The clip background removal acts on: the selected video clip, else the first. */
    fun backgroundTarget(state: EditorUiState = _state.value): Clip? =
        state.videoClips.firstOrNull { it.id == state.selectedClipId }
            ?: state.videoClips.firstOrNull { it.layer == 0 }
            ?: state.videoClips.firstOrNull()

    /** Finds the person through the clip, then blurs what is behind them. */
    fun removeBackground() {
        val current = _state.value
        if (current.backgroundProgress.running) return
        val clip = backgroundTarget(current) ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        backgroundJob?.cancel()
        _state.update { it.copy(backgroundProgress = ReframeProgress(running = true)) }
        backgroundJob = viewModelScope.launch {
            val file = Segmenter.analyze(
                app, uri, clip.sourceInMs, clip.sourceOutMs
            ) { done, total ->
                _state.update { it.copy(backgroundProgress = it.backgroundProgress.copy(done = done, total = total)) }
            }
            if (file == null) {
                _state.update { it.copy(backgroundProgress = ReframeProgress(failed = true)) }
                return@launch
            }
            _state.update { it.copy(backgroundProgress = ReframeProgress()) }
            val fill = clip.background?.fill ?: BackgroundFill.Blur
            val colour = clip.background?.colorArgb ?: BackgroundRemoval(file).colorArgb
            setBackground(clip.id, BackgroundRemoval(file, fill, colour))
        }
    }

    fun cancelBackground() {
        backgroundJob?.cancel()
        backgroundJob = null
        _state.update { it.copy(backgroundProgress = ReframeProgress()) }
    }

    fun setBackgroundFill(clipId: String, fill: BackgroundFill, colorArgb: Int? = null) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return
        val current = clip.background ?: return
        setBackground(clipId, current.copy(fill = fill, colorArgb = colorArgb ?: current.colorArgb))
    }

    fun setBackground(clipId: String, background: BackgroundRemoval?) = record("Background") {
        mutateTimeline { timeline ->
            timeline.copy(clips = timeline.clips.map { if (it.id == clipId) it.copy(background = background) else it })
        }
    }

    // ---- Auto-reframe -------------------------------------------------------------

    private var reframeJob: Job? = null

    /**
     * Finds the subject through every shot on the main track and makes the
     * frame-shape crop follow it, shot by shot. Needs a fixed shape; if none
     * is chosen yet, 9:16 is - the shape this is nearly always wanted for.
     *
     * Each shot is analysed over its own window of its own file and keeps its
     * own track (Clip.reframe), so a second clip follows its own subject and
     * a trimmed or reordered shot still reads its track at the right moment.
     * One track for the whole edit, read off the first file, had the window
     * chasing where the subject had been in different footage (V11). Two
     * shots of one stretch of one file share the measurement. The result is
     * one undo step, filed when it lands, beneath anything edited meanwhile.
     */
    fun autoReframe() {
        val current = _state.value
        if (current.reframeProgress.running) return
        val shots = current.videoClips.filter { it.isMain && !it.isStillPicture && (it.uri ?: current.sourceUri) != null }
        if (shots.isEmpty()) return
        if (current.cropAspect.ratio == null) clips.setCropAspect(CropAspect.Portrait)

        reframeJob?.cancel()
        _state.update { it.copy(reframeProgress = ReframeProgress(running = true)) }
        reframeJob = viewModelScope.launch {
            // The same stretch of the same file analysed once, however many
            // shots were cut from it.
            val windows = shots.map { Triple(it.uri ?: current.sourceUri!!, it.sourceInMs, it.sourceOutMs) }.distinct()
            val total = windows.size
            val tracks = HashMap<Triple<Uri, Long, Long>, MotionTrack>()
            windows.forEachIndexed { index, window ->
                val (uri, fromMs, toMs) = window
                val track = Reframer.analyze(app, uri, fromMs, toMs) { done, count ->
                    _state.update {
                        it.copy(reframeProgress = it.reframeProgress.copy(done = index * 100 + done * 100 / count.coerceAtLeast(1), total = total * 100))
                    }
                }
                if (track != null) tracks[window] = track
            }
            if (tracks.isEmpty()) {
                _state.update { it.copy(reframeProgress = ReframeProgress(failed = true)) }
                return@launch
            }
            recordLate(
                "Auto-reframe",
                edit = { snapshot ->
                    snapshot.copy(videoClips = snapshot.videoClips.map { clip ->
                        val uri = clip.uri ?: current.sourceUri
                        if (!clip.isMain || uri == null) clip
                        else tracks[Triple(uri, clip.sourceInMs, clip.sourceOutMs)]?.let { clip.copy(reframe = it) } ?: clip
                    })
                },
                alongside = { it.copy(reframeProgress = ReframeProgress()) }
            )
        }
    }

    fun cancelReframe() {
        reframeJob?.cancel()
        reframeJob = null
        _state.update { it.copy(reframeProgress = ReframeProgress()) }
    }

    /** Back to a centred crop, on every shot. */
    fun clearReframe() = record("Centre crop") {
        _state.update { s -> s.copy(videoClips = s.videoClips.map { if (it.reframe == null) it else it.copy(reframe = null) }) }
    }

    /**
     * The frame on screen at [atMs] of [clip], for sampling the screen colour or
     * placing a tracking box.
     *
     * Through the clip's speed curve: on a retimed clip the frame the preview
     * shows is not "in-point plus time since the clip started", and the swatch
     * was sampled from somewhere else. From the proxy when there is one - the
     * answer is scaled down to a few hundred pixels anyway, and decoding a 4K
     * frame to get there was most of the wait.
     */
    suspend fun sampleFrame(clip: Clip, atMs: Long): android.graphics.Bitmap? {
        val current = _state.value
        val uri = clip.uri ?: current.sourceUri ?: return null
        val inClip = clip.sourceAt(atMs).coerceIn(clip.sourceInMs, clip.sourceOutMs)
        return ThumbnailExtractor.frameAt(app, analysisSourceFor(uri, current).uri, inClip)
    }

    /**
     * One clip's picture at [timelineMs]: which file, and where in it. What
     * the look chips are graded on, so they show the clip being graded - an
     * overlay's as much as a shot's - rather than the first file at a time
     * that may not even be in it. A photo kept as a picture has no frames to
     * take.
     */
    fun pictureOf(current: EditorUiState, clip: Clip, timelineMs: Long): Pair<Uri, Long>? {
        if (clip.isStillPicture) return null
        val uri = clip.uri ?: current.sourceUri ?: return null
        return analysisSourceFor(uri, current).uri to clip.sourceAt(timelineMs).coerceIn(clip.sourceInMs, clip.sourceOutMs)
    }

    // ---- Motion tracking ------------------------------------------------------------

    fun setTrackPoint(x: Float, y: Float) = _state.update {
        it.copy(tracking = it.tracking.copy(pointX = x.coerceIn(0f, 1f), pointY = y.coerceIn(0f, 1f)))
    }

    fun setTrackBox(fraction: Float) = _state.update {
        it.copy(tracking = it.tracking.copy(boxFraction = fraction.coerceIn(0.06f, 0.35f)))
    }

    fun clearTrack() {
        trackJob?.cancel()
        _state.update { it.copy(tracking = TrackProgress(pointX = it.tracking.pointX, pointY = it.tracking.pointY)) }
    }

    /**
     * Follows whatever is under the chosen point through the clip. The result is
     * held rather than applied: a track is a measurement, and what it drives - a
     * caption, a layer - is a separate decision.
     */
    fun startTracking(clipId: String) {
        val current = _state.value
        if (current.tracking.running) return
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        trackJob?.cancel()
        _state.update {
            it.copy(tracking = it.tracking.copy(running = true, finished = false, failed = false, track = null))
        }

        trackJob = viewModelScope.launch {
            val analysis = analysisSourceFor(uri, current)
            val result = TrackRunner.track(
                context = app,
                uri = analysis.uri,
                sourceWidth = analysis.width,
                sourceHeight = analysis.height,
                fps = current.fps,
                fromMs = clip.sourceInMs,
                toMs = clip.sourceOutMs,
                startXFraction = current.tracking.pointX,
                startYFraction = current.tracking.pointY,
                boxFraction = current.tracking.boxFraction,
                onProgress = { done, total ->
                    _state.update { it.copy(tracking = it.tracking.copy(done = done, total = total)) }
                }
            )
            _state.update {
                it.copy(
                    tracking = it.tracking.copy(
                        running = false,
                        finished = true,
                        failed = result == null,
                        track = result,
                        clipId = clipId
                    )
                )
            }
        }
    }

    /** Source time of the tracked clip into timeline time. */
    private fun trackInTimelineTime(track: MotionTrack, clip: Clip): MotionTrack {
        val delta = clip.timelineStartMs - clip.sourceInMs
        return MotionTrack(track.samples.map { it.copy(atMs = it.atMs + delta) })
    }

    /**
     * Pins a mask to the track - the point of which is hiding a face or a plate.
     *
     * The mask lives on the clip being tracked, so the track goes in as it was
     * measured: in source time, which is also how the mask evaluates it. No
     * conversion, and nothing to get backwards.
     */
    fun pinMaskToTrack(clipId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        if (current.tracking.clipId != clipId) return
        record("Pin mask") {
            mutateTimeline { timeline ->
                val clip = timeline.clips.firstOrNull { it.id == clipId } ?: return@mutateTimeline timeline
                val existing = clip.mask ?: Mask(
                    shape = MaskShape.Ellipse,
                    widthFraction = current.tracking.boxFraction * 1.4f,
                    heightFraction = current.tracking.boxFraction * 1.4f,
                    mode = MaskMode.Pixelate
                )
                timeline.copy(
                    clips = timeline.clips.map {
                        if (it.id == clipId) it.copy(mask = existing.copy(track = track)) else it
                    }
                )
            }
        }
    }

    fun unpinMask(clipId: String) = record("Unpin mask") {
        mutateTimeline { timeline ->
            timeline.copy(
                clips = timeline.clips.map {
                    if (it.id == clipId) it.copy(mask = it.mask?.copy(track = null)) else it
                }
            )
        }
    }

    fun pinCaptionToTrack(captionId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        val clip = current.videoClips.firstOrNull { it.id == current.tracking.clipId } ?: return
        val timed = trackInTimelineTime(track, clip)
        record("Pin text") {
            _state.update { state ->
                state.copy(
                    textOverlays = state.textOverlays.map {
                        if (it.id == captionId) it.copy(track = timed) else it
                    }
                )
            }
        }
    }

    fun unpinCaption(captionId: String) = record("Unpin text") {
        _state.update { state ->
            state.copy(textOverlays = state.textOverlays.map {
                if (it.id == captionId) it.copy(track = null) else it
            })
        }
    }

    /**
     * Pins a layer to the track, as keyframes on that layer.
     *
     * Written into the ordinary keyframe track rather than a private one: unlike
     * stabilization, this *is* an edit, and you should be able to nudge it
     * afterward without the app arguing.
     */
    fun pinLayerToTrack(layerClipId: String) {
        val current = _state.value
        val track = current.tracking.track ?: return
        val source = current.videoClips.firstOrNull { it.id == current.tracking.clipId } ?: return
        val layer = current.videoClips.firstOrNull { it.id == layerClipId } ?: return
        val timed = trackInTimelineTime(track, source)

        val keys = timed.samples.map { sample ->
            Keyframe(
                atMs = (sample.atMs - layer.timelineStartMs).coerceAtLeast(0L),
                transform = Transform(
                    scale = layer.scale * sample.scale,
                    // Track fractions run 0 to 1 across the frame; transform
                    // offsets run -1 to 1 from the center.
                    offsetXFraction = (sample.xFraction - 0.5f) * 2f,
                    offsetYFraction = (sample.yFraction - 0.5f) * 2f,
                    rotationDegrees = layer.rotation
                ),
                easing = KeyframeEasing.Linear
            )
        }.sortedBy { it.atMs }

        record("Pin layer") {
            _state.update { state ->
                state.copy(
                    videoClips = state.videoClips.map {
                        if (it.id == layerClipId) it.copy(keyframes = keys) else it
                    }
                )
            }
        }
    }

    // ---- Stabilization ------------------------------------------------------------

    /**
     * One clip's strength, and that clip solved again at it from the
     * measurement it kept (StabilizerSolve) - in the same step, so the slider
     * is seen on the picture as it moves and undone with it. This clip alone:
     * the slider is on its sheet, and one strength for the edit had a nudge on
     * one shot quietly re-solving every other. The edit's default follows it,
     * for the next shot measured. A clip from before measurements were kept has
     * only its keys, and holds them until it is measured again.
     */
    fun setStabilizeStrength(clipId: String, value: Float) = record("Stabilize strength", gesture = "Stabilize strength $clipId") {
        val strength = value.coerceIn(0f, 1f)
        _state.update { state ->
            state.copy(
                stabilizeStrength = strength,
                videoClips = state.videoClips.map { clip ->
                    if (clip.id != clipId) clip
                    else {
                        val measurement = clip.stabilizerMeasurement
                        val solved = if (measurement == null || clip.stabilizer.isEmpty()) null else StabilizerSolve.solve(measurement, strength)
                        clip.copy(stabilizeStrength = strength, stabilizer = solved?.keyframes ?: clip.stabilizer)
                    }
                }
            )
        }
    }

    /** The strength a clip is, or would be, solved at: its own once set, the edit's default until then. */
    fun strengthFor(clip: Clip, state: EditorUiState): Float = clip.stabilizeStrength ?: state.stabilizeStrength

    /**
     * Measures the shake in a clip and writes the correction.
     *
     * Analysis only - it produces a keyframe track, which the existing transform
     * effect then applies in the preview and the export alike. Nothing about
     * rendering changes.
     *
     * The result is an undo step of its own, recorded when it lands rather than
     * when the button was pressed: anything edited during the half-minute of
     * measuring stays its own step, before this one.
     */
    fun stabilizeClip(clipId: String) {
        val current = _state.value
        if (current.stabilize.running) return
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return

        stabilizeJob?.cancel()
        _state.update { it.copy(stabilize = StabilizeProgress(running = true, clipId = clipId)) }

        stabilizeJob = viewModelScope.launch {
            val analysis = analysisSourceFor(uri, current)
            val strength = strengthFor(clip, current)
            val result = Stabilizer.analyze(
                context = app,
                uri = analysis.uri,
                sourceWidth = analysis.width,
                sourceHeight = analysis.height,
                fps = current.fps,
                fromMs = clip.sourceInMs,
                toMs = clip.sourceOutMs,
                strength = strength,
                onProgress = { done, total ->
                    _state.update { it.copy(stabilize = it.stabilize.copy(done = done, total = total)) }
                }
            )

            if (result == null) {
                _state.update {
                    it.copy(stabilize = StabilizeProgress(finished = true, failed = true, clipId = clipId))
                }
                return@launch
            }

            recordLate(
                "Stabilize",
                edit = { snapshot ->
                    snapshot.copy(videoClips = snapshot.videoClips.map {
                        // The measurement with the keys, so Strength re-solves from
                        // it, and the strength it was solved at, so the slider reads it.
                        if (it.id == clipId) it.copy(stabilizer = result.keyframes, stabilizerMeasurement = result.measurement, stabilizeStrength = strength) else it
                    })
                },
                alongside = {
                    it.copy(
                        stabilize = StabilizeProgress(
                            finished = true,
                            crop = result.crop,
                            framesAnalysed = result.framesAnalysed,
                            clipId = clipId
                        )
                    )
                }
            )
        }
    }

    /**
     * Takes the correction off one clip. A measurement running on some other
     * clip carries on; it used to be cancelled by removing this one's.
     */
    fun clearStabilization(clipId: String) {
        val running = _state.value.stabilize
        if (running.clipId == clipId) stabilizeJob?.cancel()
        record("Remove stabilization") {
            _state.update { state ->
                state.copy(
                    videoClips = state.videoClips.map {
                        if (it.id == clipId) it.copy(stabilizer = emptyList(), stabilizerMeasurement = null) else it
                    },
                    stabilize = if (state.stabilize.clipId == clipId) StabilizeProgress() else state.stabilize
                )
            }
        }
    }
}
