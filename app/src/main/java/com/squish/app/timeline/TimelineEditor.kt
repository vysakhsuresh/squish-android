package com.squish.app.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.squish.app.editor.Timecode
import com.squish.app.editor.Concept
import com.squish.app.editor.TransitionGlyph
import com.squish.app.ui.theme.SquishColors
import kotlin.math.roundToLong

private val LANE_HEIGHT = 54.dp

/** The effects lane is slimmer than the rest: its bars carry a mark and a name, not pictures. */
private val FX_LANE_HEIGHT = 32.dp
private val GUTTER = 42.dp
private val RULER_HEIGHT = 26.dp
private val HANDLE_WIDTH = 20.dp

/** Wide enough for a fingertip; the line itself stays two pixels. */
private val PLAYHEAD_HEAD = 18.dp

/** Where the playhead sits while the strip follows it: a third in, not centred. */
private const val FOLLOW_ANCHOR = 0.33f

/** How close to the right edge counts as "about to leave the screen". */
private const val EDGE_MARGIN_PX = 48f

/**
 * Zoom limits.
 *
 * The ceiling was four hundred, and before the strip was windowed it could not
 * safely be more: zoom times length was a layout width, and a long video at a
 * deep zoom was a number Compose refuses. Only a screenful is laid out now, so
 * the ceiling is a question of what is useful rather than what survives - two
 * thousand pixels a second puts a 30fps frame about seventy pixels wide, which
 * is enough to cut on.
 *
 * The floor is deliberately far below anything anyone would choose by hand. It is
 * not a zoom to work at - it is what "fit the whole edit" needs in order to mean
 * something on a long file. Three hours across a phone screen is a thirtieth of a
 * pixel per second, and a floor of two silently left the fit showing a sixtieth
 * of the timeline while claiming to show all of it.
 */
private const val MIN_PPS = ZOOM_MIN
private const val MAX_PPS = ZOOM_MAX

/** Empty run past the end of the edit, so the last clip is not against the edge. */
private const val TAIL_DP = 240f

/** One square for every button on the strip's action bar. */
private val MINI_ACTION_SIZE = 38.dp

/**
 * How far two fingers must change their spread before it counts as a pinch.
 *
 * Fingers resting on glass are never quite still, and a detector that acted on
 * every pixel of that made the strip shiver in place.
 */
private const val PINCH_SLOP_PX = 12f

/** How far apart ruler labels must land to not run into each other - room for "10:00". */
private const val MIN_LABEL_GAP_DP = 52f

/**
 * A pinch, and nothing but a pinch.
 *
 * This replaces `detectTransformGestures`, which was the wrong tool and broke the
 * strip badly. That detector treats a one-finger drag as a pan: once the drag
 * passes touch slop it consumes every event, so the scroll never moved, a clip
 * could not be dragged, and the playhead could not be taken hold of - it only
 * ever answered on the attempts where the gesture happened to start somewhere the
 * detector had already given up on. It also reports a zoom factor that is not
 * exactly 1 for a single pointer, which is what made the timeline shiver and
 * jump scale under a finger that was only trying to scrub.
 *
 * So: nothing happens until a second finger is down, and while only one is down
 * no event is consumed at all - this detector is invisible to the scroll, the
 * clips and the playhead. Two fingers are unambiguous, and only then are the
 * events taken, so the scroll underneath does not pan while the zoom happens.
 *
 * [onZoom] receives a ratio - above one for spreading, below for pinching in.
 */
private suspend fun PointerInputScope.detectPinch(guard: MultiTouchGuard, onZoom: (Float) -> Unit) {
    awaitEachGesture {
        // The initial pass, so this is offered the gesture before the scroll that
        // wraps it. Nothing is consumed here: at one finger there is no pinch.
        var spread = 0f
        var engaged = false
        try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size >= 2) guard.active = true

            if (down.size < 2) {
                // Back to one finger, mid-gesture. Forget the span rather than
                // measure the next one against it, or lifting one finger of a
                // pinch would read as an enormous sudden zoom.
                spread = 0f
                engaged = false
                continue
            }

            val distance = (down[0].position - down[1].position).getDistance()
            if (distance <= 0f) continue

            if (spread <= 0f) {
                spread = distance
                continue
            }

            if (!engaged && kotlin.math.abs(distance - spread) < PINCH_SLOP_PX) continue
            engaged = true

            onZoom(distance / spread)
            spread = distance
            down.forEach { if (it.positionChanged()) it.consume() }
        }
        } finally {
            if (guard.active) guard.release()
        }
    }
}

/**
 * Whether a two-finger gesture is on the strip, or only just left it.
 *
 * A pinch starts and ends with one finger, and the tap detectors on the lanes and
 * ruler saw that finger lift and took it for a tap - at the new zoom, which when
 * zoomed out is usually past the end of the edit. The playhead leapt to the end,
 * the picture went to "gap", and a pinch made during playback stopped it dead.
 * Every tap and scrub on the strip now asks this first.
 */
internal class MultiTouchGuard {
    var active = false
    private var releasedAt = 0L

    fun release() {
        active = false
        releasedAt = android.os.SystemClock.uptimeMillis()
    }

    /** True while two fingers are down, and for a moment after they lift. */
    val blocking: Boolean
        get() = active || android.os.SystemClock.uptimeMillis() - releasedAt < AFTER_PINCH_MS

    private companion object {
        /** Long enough to cover the lift of the last finger, short enough that a real tap is never lost. */
        const val AFTER_PINCH_MS = 350L
    }
}

/**
 * A real multi-track timeline: clips can be selected, dragged, trimmed at either
 * edge, and split at the playhead, with video, audio and captions on their own
 * lanes against a shared ruler.
 */
@Composable
fun TimelineEditor(
    state: TimelineState,
    onSelect: (String?) -> Unit,
    /** A clip dragged: its id, and where the finger would have it start. */
    onMoveTo: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTransitionTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Marks to snap to — the beat grid, or anything dropped by hand. */
    markers: List<Long> = emptyList(),
    /** Which of those start a bar, drawn taller so the phrasing is readable. */
    barMarkers: List<Long> = emptyList(),
    /** True while the transport is running, which is when the strip follows along. */
    isPlaying: Boolean = false,
    /**
     * Bumped to ask the strip to fit the whole edit across its width. Only the
     * strip knows how wide it is, so the zoom has to be computed here and handed
     * back rather than worked out by whoever wants it.
     */
    fitNonce: Long = 0L,
    onZoomTo: (Float) -> Unit = {},
    /** An effect dragged along its lane, by this many milliseconds. */
    onEffectMove: (String, Long) -> Unit = { _, _ -> },
    /** An effect's start and end pulled by these many milliseconds. */
    onEffectTrim: (String, Long, Long) -> Unit = { _, _, _ -> },
    /**
     * What each track's icon does when tapped - the way in to adding to that
     * track, beside the track itself. Video offers a menu (see [VideoTrackButton]);
     * an overlay row opens the overlay picker; sound and words open their tools.
     * Null leaves the icon a plain label.
     */
    onAddVideo: (() -> Unit)? = null,
    onAddBlank: (() -> Unit)? = null,
    onAddOverlay: (() -> Unit)? = null,
    onOpenSound: (() -> Unit)? = null,
    onOpenWords: (() -> Unit)? = null,
    /**
     * A finger has taken hold of the playhead (true) or let go (false). The
     * preview serves a drag from sync samples and settles it exactly the moment
     * the finger lifts, rather than guessing both from how close together the
     * seeks arrive.
     */
    onScrubbingChange: (Boolean) -> Unit = {},
    /**
     * Only the ruler and the row the selection is on, while a tool's sheet has
     * the room below. See the rows, further down.
     */
    compact: Boolean = false,
    /** A double tap on the ruler: the whole edit on screen. Pinch zooms; the zoom buttons are gone. */
    onFit: () -> Unit = {}
) {
    val density = LocalDensity.current
    val totalMs = maxOf(state.durationMs, 8_000L)

    // Every tap, scrub and select below goes through the guard, so the fingers of
    // a pinch are never read as a tap on the strip.
    val guard = remember { MultiTouchGuard() }
    val rawScrub by rememberUpdatedState(onScrub)
    val rawSelect by rememberUpdatedState(onSelect)
    val guardedScrub: (Long) -> Unit = remember { { ms -> if (!guard.blocking) rawScrub(ms) } }
    val guardedSelect: (String?) -> Unit = remember { { id -> if (!guard.blocking) rawSelect(id) } }
    val rawMove by rememberUpdatedState(onMoveTo)
    val rawTrim by rememberUpdatedState(onTrim)
    val guardedMove: (String, Long) -> Unit = remember { { id, startMs -> if (!guard.active) rawMove(id, startMs) } }
    val guardedTrim: (String, Long, Long) -> Unit =
        remember { { id, start, end -> if (!guard.active) rawTrim(id, start, end) } }

    // The strip's own width in pixels. Zero until the first layout pass, and
    // every use guards for that.
    var viewportPx by remember { mutableIntStateOf(0) }

    /**
     * Where the left edge of the view is, as a moment in the edit.
     *
     * The strip is no longer laid out whole. It used to be one row as wide as the
     * entire timeline inside a scroll container, which is the obvious way to build
     * it and does not survive a long video: Compose refuses any dimension of
     * 262,143 pixels or more, and three hours at a working zoom is nine hundred
     * thousand. Budgeting the zoom kept it alive at the cost of precision - three
     * hours could only be shown at about eleven pixels a second.
     *
     * Now only what is on screen is built, so what it costs to lay out does not
     * depend on how long the video is, and the zoom ceiling is gone: a three-hour
     * clip zooms to the frame exactly like a three-second one.
     */
    var scrollMs by remember { mutableStateOf(0.0) }

    val window = TimelineWindow(
        pixelsPerSecond = state.pixelsPerSecond.coerceIn(MIN_PPS, MAX_PPS),
        scrollMs = scrollMs,
        density = density.density,
        viewportPx = viewportPx
    )

    // Read fresh inside gestures: the pointerInput blocks are keyed on Unit so
    // they survive a zoom, and a captured value would go stale on the first pinch.
    val latestZoomTo by rememberUpdatedState(onZoomTo)
    val latestWindow by rememberUpdatedState(window)

    /** The zoom the strip was last laid out at, so a pinch knows what it changed from. */
    var previousPps by remember { mutableFloatStateOf(window.pixelsPerSecond) }

    /** True while the playhead is being dragged, which nothing else may interrupt. */
    var scrubbing by remember { mutableStateOf(false) }
    val latestScrubbingChange by rememberUpdatedState(onScrubbingChange)
    // Folded away mid-drag, the gesture is cancelled without its end running, and
    // the preview would go on believing a finger was down.
    DisposableEffect(Unit) {
        onDispose { if (scrubbing) latestScrubbingChange(false) }
    }

    fun scrollTo(ms: Double) {
        scrollMs = ms.coerceIn(0.0, latestWindow.maxScrollMs(totalMs, TAIL_DP))
    }

    /**
     * Dragging the strip moves the view.
     *
     * Its own scroll rather than `horizontalScroll`, because that one needs the
     * content to really be as wide as the timeline - which is the thing that could
     * not be laid out. This converts the drag to time and moves the window, so the
     * distance scrolled is bounded by the length of the video rather than by the
     * length times the zoom.
     */
    val scrollable = rememberScrollableState { deltaPx ->
        val before = scrollMs
        scrollTo(before - latestWindow.msForPx(deltaPx))
        latestWindow.pxForMs(before - scrollMs)
    }

    /**
     * Fits the whole edit across the strip.
     *
     * Fitting is the answer to seeing all of it; following, below, is the answer
     * to seeing the part that is playing.
     *
     * Once per request. This used to be keyed on the edit's length as well, and
     * the nonce is never reset, so every change to where the edit ends - each
     * event of a drag on the last clip's tail, a speed change, a delete - threw
     * the view back to zero and changed the zoom under the finger mid-drag. The
     * length is only waited on (a load asks before its clip has arrived), and
     * the request is marked done in [StripMemory], which outlives the strip
     * being folded away for a panel; otherwise reopening it would refit too.
     */
    val memory: StripMemory = viewModel()
    val hasContent = state.durationMs > 0L
    LaunchedEffect(fitNonce, viewportPx, hasContent) {
        if (fitNonce <= memory.fittedNonce || viewportPx <= 0 || !hasContent) return@LaunchedEffect
        val seconds = (totalMs / 1000f).coerceAtLeast(0.001f)
        val usableDp = with(density) { viewportPx.toDp().value } - 24f
        if (usableDp <= 0f) return@LaunchedEffect
        memory.fittedNonce = fitNonce
        scrollTo(0.0)
        onZoomTo((usableDp / seconds).coerceIn(MIN_PPS, MAX_PPS))
    }

    /**
     * Keeps the playhead on screen.
     *
     * While playing, the strip is pulled along so the playhead sits a third of
     * the way across and the picture moves underneath it - which is what an NLE
     * does, and what stops a long edit playing off the right-hand edge within
     * seconds. While paused it only intervenes when the playhead has left the
     * viewport, so scrolling by hand to look at something is not fought.
     */
    LaunchedEffect(state.playheadMs, isPlaying, viewportPx, window.pixelsPerSecond, scrubbing) {
        if (viewportPx <= 0) return@LaunchedEffect
        // Never while a finger is on the playhead. Following moves the board, and
        // moving the board under a finger that is itself moving means the two chase
        // each other - the strip lurches and the position jumps by seconds.
        if (scrubbing) return@LaunchedEffect

        val visible = window.xPx(state.playheadMs)
        val needsMoving = isPlaying || visible < 0f || visible > viewportPx - EDGE_MARGIN_PX
        if (!needsMoving) return@LaunchedEffect

        scrollTo(state.playheadMs - window.msForPx(viewportPx * FOLLOW_ANCHOR))
    }

    /**
     * Holds a moment still through a zoom.
     *
     * A zoom that keeps the left edge where it is throws whatever you were looking
     * at off the screen, and the further into the edit you are the further it
     * goes. The playhead is where your attention is, so it stays put - unless it
     * is off screen, in which case the middle of what you *are* looking at does.
     */
    LaunchedEffect(window.pixelsPerSecond) {
        val previous = previousPps
        previousPps = window.pixelsPerSecond
        if (previous <= 0f || previous == window.pixelsPerSecond || viewportPx <= 0) {
            return@LaunchedEffect
        }
        val before = window.copy(pixelsPerSecond = previous)
        val playheadPx = before.xPx(state.playheadMs)
        val anchorMs = if (playheadPx in 0f..viewportPx.toFloat()) {
            state.playheadMs
        } else {
            before.msAt(viewportPx / 2f)
        }
        scrollTo(before.zoomedTo(window.pixelsPerSecond, anchorMs, totalMs, TAIL_DP).scrollMs)
    }

    // Keeps the view inside the edit when the edit gets shorter, or the zoom
    // coarser - either can leave the window parked past the end of everything.
    LaunchedEffect(totalMs, window.pixelsPerSecond, viewportPx) {
        scrollTo(scrollMs)
    }

    val showEffects = state.effects.isNotEmpty()
    // The rows, top to bottom: picture layers, the base picture, sound, words and
    // - once there is one - effects. Sound and words are one row each however
    // many there are; overlaps stack in place, see [Lane]'s stacked mode.
    //
    // Compact, while a tool's sheet is open: the ruler and the row the selection
    // is on (the main track when nothing is), so the strip stays in view above
    // the sheet - the playhead, a cut, the thing being worked on - instead of
    // folding away with undo and split inside it.
    val selected = state.selectedClip
    val selectedIsEffect = state.effects.any { it.id == state.selectedClipId }
    val rows: List<StripRow> = if (compact) {
        listOf(
            when {
                selectedIsEffect -> StripRow.Effects
                selected == null -> StripRow.Base
                selected.kind == ClipKind.Audio -> StripRow.Sound
                selected.kind == ClipKind.Text -> StripRow.Words
                selected.layer > 0 -> StripRow.Layer(selected.layer)
                else -> StripRow.Base
            }
        )
    } else {
        (state.layerCount downTo 1).map { StripRow.Layer(it) } +
            listOf(StripRow.Base, StripRow.Sound, StripRow.Words) +
            listOfNotNull(StripRow.Effects.takeIf { showEffects })
    }
    val rawEffectMove by rememberUpdatedState(onEffectMove)
    val rawEffectTrim by rememberUpdatedState(onEffectTrim)
    val guardedEffectMove: (String, Long) -> Unit =
        remember { { id, delta -> if (!guard.active) rawEffectMove(id, delta) } }
    val guardedEffectTrim: (String, Long, Long) -> Unit =
        remember { { id, start, end -> if (!guard.active) rawEffectTrim(id, start, end) } }

    // One rule for picking out of a pile on every stacked row, and one record of
    // the last pick - there is only ever one selection to say "2/3" about. A tap
    // with nothing under it lets go of the selection, as a tap on bare track does.
    var cycle by remember { mutableStateOf<CycleMark?>(null) }
    val latestState by rememberUpdatedState(state)
    val stackTap: (List<Pair<String, LongRange>>, Long) -> Unit = remember {
        { pile, atMs ->
            val under = pile.filter { atMs >= it.second.first && atMs < it.second.last }.asReversed().map { it.first }
            cycle = pickUnderTap(under, latestState.selectedClipId)
            guardedSelect(cycle?.id)
            guardedScrub(atMs)
        }
    }
    val tapAudio: (Long) -> Unit = remember {
        { ms -> stackTap(latestState.audioClips.stackOrder().map { it.id to it.timelineStartMs..it.timelineEndMs }, ms) }
    }
    val tapText: (Long) -> Unit = remember {
        { ms -> stackTap(latestState.textClips.stackOrder().map { it.id to it.timelineStartMs..it.timelineEndMs }, ms) }
    }
    val tapEffects: (Long) -> Unit = remember {
        { ms -> stackTap(latestState.effects.stackOrder().map { it.id to it.startMs..it.endMs }, ms) }
    }
    // Bare track: a moment, and nothing selected - the way back to the main tools.
    val tapBare: (Long) -> Unit = remember {
        { ms ->
            guardedSelect(null)
            guardedScrub(ms)
        }
    }

    /**
     * A tap that lands on the playhead, handed on to the row beneath it.
     *
     * The playhead is a wide grab target over every row, and a tap on a clip
     * moves the playhead to the finger - so a second tap in the same place, which
     * is how a pile is cycled, always landed on the playhead and did nothing.
     */
    val rulerPx = with(density) { RULER_HEIGHT.toPx() }
    val latestRows by rememberUpdatedState(rows)
    val tapThroughPlayhead: (Float) -> Unit = { y ->
        val s = latestState
        val atMs = s.playheadMs
        var top = rulerPx
        val row = if (y < rulerPx) null else latestRows.firstOrNull { r ->
            val bottom = top + with(density) { r.height.toPx() }
            (y < bottom).also { top = bottom }
        }
        when (row) {
            null -> Unit
            is StripRow.Layer -> s.clips
                .firstOrNull { it.kind == ClipKind.Video && it.layer == row.layer && it.spans(atMs) }
                .let { guardedSelect(it?.id) }
            StripRow.Base -> guardedSelect(s.baseVideoClips.firstOrNull { it.spans(atMs) }?.id)
            StripRow.Sound -> tapAudio(atMs)
            StripRow.Words -> tapText(atMs)
            StripRow.Effects -> tapEffects(atMs)
        }
    }

    Row(modifier = modifier.fillMaxWidth().background(SquishColors.Background)) {

        Column(modifier = Modifier.width(GUTTER)) {
            Spacer(modifier = Modifier.height(RULER_HEIGHT))
            // Each track's head is its glyph in its colour, from the one table the
            // toolbar uses too (see Concept). Where it adds to its track it is a
            // button: video offers a menu, an overlay row the overlay picker, sound
            // and words open their tools.
            rows.forEach { row ->
                when (row) {
                    is StripRow.Layer -> if (onAddOverlay != null) {
                        TrackButton(Concept.Overlay.icon, Concept.Overlay.accent, "Add overlay", onAddOverlay)
                    } else {
                        LaneBadge(Concept.Overlay.icon, Concept.Overlay.accent)
                    }
                    StripRow.Base -> if (onAddVideo != null) {
                        VideoTrackButton(onAddVideo, onAddBlank)
                    } else {
                        LaneBadge(Concept.Video.icon, Concept.Video.accent)
                    }
                    StripRow.Sound -> if (onOpenSound != null) {
                        TrackButton(Concept.Sound.icon, Concept.Sound.accent, "Add music or sound", onOpenSound)
                    } else {
                        LaneBadge(Concept.Sound.icon, Concept.Sound.accent)
                    }
                    StripRow.Words -> if (onOpenWords != null) {
                        TrackButton(Concept.Text.icon, Concept.Text.accent, "Add text", onOpenWords)
                    } else {
                        LaneBadge(Concept.Text.icon, Concept.Text.accent)
                    }
                    StripRow.Effects -> LaneBadge(Concept.Effects.icon, Concept.Effects.accent, FX_LANE_HEIGHT)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { viewportPx = it.width }
                // Pinch ahead of the scroll in the chain, so it sees the gesture on
                // the initial pass before the scroll can claim it.
                .pointerInput(Unit) {
                    detectPinch(guard) { zoom ->
                        latestZoomTo(
                            (latestWindow.pixelsPerSecond * zoom).coerceIn(MIN_PPS, MAX_PPS)
                        )
                    }
                }
                .scrollable(state = scrollable, orientation = Orientation.Horizontal)
                // Nothing may be drawn outside the strip. Clips now extend past
                // both edges by design - only the visible part of one is built -
                // and without this the overhang would paint over the gutter.
                .clipToBounds()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Ruler(
                    durationMs = totalMs,
                    window = window,
                    markers = markers,
                    barMarkers = barMarkers,
                    onScrub = guardedScrub,
                    onFit = { if (!guard.blocking) onFit() }
                )
                rows.forEach { row ->
                    when (row) {
                        is StripRow.Layer -> Lane(
                            clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == row.layer },
                            state = state,
                            window = window,
                            accent = Concept.Overlay.accent,
                            onSelect = guardedSelect,
                            onMoveTo = guardedMove,
                            onTrim = guardedTrim,
                            onScrub = guardedScrub,
                            onBareTap = tapBare
                        )
                        StripRow.Base -> Lane(
                            clips = state.baseVideoClips,
                            state = state,
                            window = window,
                            accent = Concept.Video.accent,
                            onSelect = guardedSelect,
                            onMoveTo = guardedMove,
                            onTrim = guardedTrim,
                            onScrub = guardedScrub,
                            onBareTap = tapBare,
                            onTransitionTap = onTransitionTap
                        )
                        StripRow.Sound -> Lane(
                            state.audioClips, state, window, Concept.Sound.accent,
                            guardedSelect, guardedMove, guardedTrim, guardedScrub,
                            onBareTap = tapBare,
                            stackTap = tapAudio, cycle = cycle,
                            clipColor = remember(state.audioClips) { coloursByKey(state.audioClips, MUSIC_COLOURS) { it.uri?.toString() ?: it.id } }
                        )
                        StripRow.Words -> Lane(
                            state.textClips, state, window, Concept.Text.accent,
                            guardedSelect, guardedMove, guardedTrim, guardedScrub,
                            onBareTap = tapBare,
                            stackTap = tapText, cycle = cycle,
                            clipColor = remember(state.textClips) { coloursByKey(state.textClips, TEXT_COLOURS) { it.id } }
                        )
                        StripRow.Effects -> EffectsLane(
                            effects = state.effects,
                            selectedId = state.selectedClipId,
                            window = window,
                            onSelect = guardedSelect,
                            onMove = guardedEffectMove,
                            onTrim = guardedEffectTrim,
                            onTapAt = tapEffects,
                            cycle = cycle
                        )
                    }
                }
            }

            // Beat lines run the full height, behind the playhead. A grid you can
            // only see on the ruler tells you where the beats are; a grid that
            // crosses the lanes tells you whether a cut is on one.
            val laneHeight = RULER_HEIGHT + rows.fold(0.dp) { sum, row -> sum + row.height }
            markers.forEach { at ->
                if (!window.intersects(at, at)) return@forEach
                val isBar = at in barMarkers
                Box(
                    modifier = Modifier
                        .offset(x = window.xDp(at).dp)
                        .width(1.dp)
                        .height(laneHeight)
                        .background(
                            if (isBar) SquishColors.Cyan.copy(alpha = 0.42f)
                            else SquishColors.Cyan.copy(alpha = 0.16f)
                        )
                )
            }

            Playhead(
                atMs = state.playheadMs,
                window = window,
                height = laneHeight,
                onScrub = guardedScrub,
                onScrubbingChange = {
                    scrubbing = it
                    latestScrubbingChange(it)
                },
                onTap = tapThroughPlayhead
            )
        }
    }
}

/** One row of the strip, and how tall it is. */
private sealed interface StripRow {
    val height: Dp get() = LANE_HEIGHT

    data class Layer(val layer: Int) : StripRow
    data object Base : StripRow
    data object Sound : StripRow
    data object Words : StripRow
    data object Effects : StripRow {
        override val height: Dp get() = FX_LANE_HEIGHT
    }
}

/**
 * The playhead, with something to take hold of.
 *
 * A two-pixel line is a fine thing to read a position off and a hopeless thing to
 * aim a finger at. The head on top is the target: wide enough to grab, tall
 * enough to see, and draggable, so positioning the playhead is one movement
 * rather than a series of taps at the ruler hoping to land on the right frame.
 */
@Composable
private fun BoxScope.Playhead(
    atMs: Long,
    window: TimelineWindow,
    height: Dp,
    onScrub: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    /** A tap, not a drag, at this height down the strip - for the row underneath. */
    onTap: (Float) -> Unit = {}
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val latestScrubbing by rememberUpdatedState(onScrubbingChange)
    val latestTap by rememberUpdatedState(onTap)
    val latestAtMs by rememberUpdatedState(atMs)
    val latestWindow by rememberUpdatedState(window)
    val x = window.xDp(atMs).dp

    Column(
        modifier = Modifier
            .offset(x = x - PLAYHEAD_HEAD / 2)
            .width(PLAYHEAD_HEAD)
            .zIndex(2f)
            // The whole column drags, head and line alike, so a finger that lands
            // slightly low still moves it.
            //
            // The gesture keeps its own running position rather than adding each
            // delta to wherever the playhead currently is. It has to: `dragAmount`
            // is one event's movement, not the gesture's, and this block does not
            // restart when the playhead moves - which meant every event computed
            // "where the playhead was when I grabbed it, plus three pixels", over
            // and over. The playhead sat a few milliseconds from where it started
            // and jittered there while the finger travelled the width of the
            // screen, which is exactly what "I cannot move the play header" looks
            // like.
            //
            // Keyed on nothing, not on the zoom. A restart mid-gesture - which a
            // pinch caused on every step - dropped the drag without its end or
            // cancel ever running, and left the strip believing a finger was still
            // on the playhead, so it stopped following playback. The window is read
            // fresh on every event instead.
            //
            // Kept as a float, because at a high zoom one pixel is under two
            // milliseconds and rounding every event to a whole one would lose most
            // of a slow drag.
            .pointerInput(Unit) {
                var positionMs = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        // From where the finger is once the drag is recognised, not
                        // from the playhead: the travel spent crossing the touch slop
                        // used to be dropped, so the line trailed the finger by it for
                        // the whole drag.
                        val fromLinePx = offset.x - PLAYHEAD_HEAD.toPx() / 2f
                        positionMs = (latestAtMs + latestWindow.msForPx(fromLinePx)).toFloat().coerceAtLeast(0f)
                        latestScrubbing(true)
                        latestScrub(positionMs.toLong())
                    },
                    onDragEnd = { latestScrubbing(false) },
                    onDragCancel = { latestScrubbing(false) }
                ) { change, dragAmount ->
                    change.consume()
                    positionMs += latestWindow.msForPx(dragAmount).toFloat()
                    positionMs = positionMs.coerceAtLeast(0f)
                    latestScrub(positionMs.toLong())
                }
            }
            // It covers every row, and nothing under it hears a touch it takes -
            // so a tap is passed on rather than lost.
            .pointerInput(Unit) {
                detectTapGestures { offset -> latestTap(offset.y) }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(PLAYHEAD_HEAD)
                .height(PLAYHEAD_HEAD * 0.62f)
                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 7.dp))
                .background(SquishColors.TextPrimary)
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(height - PLAYHEAD_HEAD * 0.62f)
                .background(SquishColors.TextPrimary)
        )
    }
}

/**
 * A track's icon as the way to add to that track - a framed tile in the
 * track's colour, so it reads as a button rather than a label.
 */
@Composable
private fun TrackButton(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(LANE_HEIGHT).fillMaxWidth().padding(vertical = 5.dp, horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.16f))
                .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                .clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The video track's button, and what it offers: a video or photo from the
 * gallery, or a blank - the two ways a shot gets onto the main track.
 */
@Composable
private fun VideoTrackButton(onAddVideo: () -> Unit, onAddBlank: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        TrackButton(Concept.Video.icon, Concept.Video.accent, "Add to the video track") {
            if (onAddBlank == null) onAddVideo() else open = true
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SquishColors.SurfaceElevated
        ) {
            DropdownMenuItem(
                text = { Text("Video or photo", color = SquishColors.TextPrimary) },
                leadingIcon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = Concept.Video.accent) },
                onClick = { open = false; onAddVideo() }
            )
            onAddBlank?.let { blank ->
                DropdownMenuItem(
                    text = { Text("Blank", color = SquishColors.TextPrimary) },
                    leadingIcon = { Icon(Icons.Filled.CropSquare, contentDescription = null, tint = Concept.Video.accent) },
                    onClick = { open = false; blank() }
                )
            }
        }
    }
}

@Composable
private fun LaneBadge(icon: ImageVector, tint: Color, height: Dp = LANE_HEIGHT) {
    Box(
        modifier = Modifier.height(height).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Ruler(
    durationMs: Long,
    window: TimelineWindow,
    markers: List<Long>,
    barMarkers: List<Long>,
    onScrub: (Long) -> Unit,
    onFit: () -> Unit = {}
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val latestFit by rememberUpdatedState(onFit)
    val latestWindow by rememberUpdatedState(window)
    val pixelsPerSecond = window.pixelsPerSecond
    val total = maxOf(durationMs, 8_000L)
    // A tick every second is unreadable when zoomed out, so widen the step until
    // labels have room to breathe - and widen it again if the timeline is long
    // enough that the readable step would mean a thousand of them.
    //
    // Worked out from how far apart the labels land, rather than from a table of
    // zoom bands that stopped at ten seconds - zoomed out past that, "0:00",
    // "0:10" and "0:20" were drawn on top of one another.
    val readableStepMs = (MIN_LABEL_GAP_DP / pixelsPerSecond.coerceAtLeast(0.001f) * 1000f)
        .toLong().coerceAtLeast(1_000L)
    // Against what is on screen, not how long the video is. Only the visible
    // ticks are built now, so the length of the edit no longer has a say in how
    // finely it can be marked.
    val stepMs = TimelineSpan.rulerStepMs(window.viewportMs, readableStepMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            .pointerInput(Unit) {
                // A double tap fits the whole edit on screen, the one thing the zoom
                // buttons did that a pinch does not. The ruler only: on a lane a
                // single tap would wait to see whether a second came.
                detectTapGestures(
                    onDoubleTap = { latestFit() },
                    onTap = { offset -> latestScrub(latestWindow.msAt(offset.x)) }
                )
            }
            // Dragging the ruler scrubs. Tapping alone meant finding a frame took a
            // series of guesses instead of one continuous movement.
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestScrub(latestWindow.msAt(change.position.x))
                }
            }
    ) {
        // Drawn first so the second ticks and their labels sit over them.
        markers.forEach { at ->
            if (!window.intersects(at, at)) return@forEach
            val isBar = at in barMarkers
            Box(
                modifier = Modifier
                    .offset(x = window.xDp(at).dp)
                    .width(if (isBar) 2.dp else 1.dp)
                    .height(if (isBar) RULER_HEIGHT else RULER_HEIGHT * 0.5f)
                    .background(SquishColors.Cyan.copy(alpha = if (isBar) 0.8f else 0.4f))
            )
        }

        // Only the ticks on screen. The ruler used to build one column, line and
        // label for every step across the whole timeline whether or not any of it
        // was visible - a thousand of them at three hours, ten thousand zoomed in.
        var t = (window.firstDrawnMs / stepMs) * stepMs
        if (t < 0L) t = 0L
        val lastTick = minOf(total, window.lastDrawnMs)
        while (t <= lastTick) {
            val label = t
            Column(modifier = Modifier.offset(x = window.xDp(label).dp)) {
                Box(modifier = Modifier.width(1.dp).height(6.dp).background(SquishColors.Border))
                Text(
                    Timecode.format(label).removeSuffix(".000"),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            t += stepMs
        }
    }
}

@Composable
private fun Lane(
    clips: List<Clip>,
    state: TimelineState,
    /**
     * Where the view is and how big it is. Passed in rather than read from the
     * state, because a lane that disagreed with the ruler above it about the scale
     * or the scroll would put every clip in the wrong place.
     */
    window: TimelineWindow,
    accent: Color,
    onSelect: (String?) -> Unit,
    onMoveTo: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    /** A tap on bare track, away from every clip. Without it, such a tap only moves the playhead. */
    onBareTap: ((Long) -> Unit)? = null,
    onTransitionTap: ((String) -> Unit)? = null,
    /**
     * Given, everything goes on one row, overlaps and all - for sound and words,
     * which would otherwise need a row per overlap. Shortest on top so every clip
     * keeps a part to grab, names in whatever stretch is left showing, the
     * selection's outline and grips drawn over the lot, and every tap handed to
     * this, which picks from the pile under it (see [pickUnderTap]).
     */
    stackTap: ((Long) -> Unit)? = null,
    /** The last pick from a pile, for the "2/3" on the selection. */
    cycle: CycleMark? = null,
    /**
     * Each clip's own colour, where a lane holds several things worth telling
     * apart at a glance - two songs, a title and a caption. Otherwise every clip
     * wears the lane's [accent].
     */
    clipColor: ((Clip) -> Color)? = null
) {
    val stacked = stackTap != null
    val latestScrub by rememberUpdatedState(onScrub)
    val latestWindow by rememberUpdatedState(window)
    val latestStackTap by rememberUpdatedState(stackTap)
    val latestBareTap by rememberUpdatedState(onBareTap)
    val ordered = if (stacked) clips.stackOrder() else clips

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LANE_HEIGHT)
            .padding(vertical = 3.dp)
            // A lane with nothing on it still has to look like a lane. Without
            // this the audio and caption tracks were bare background, so the
            // playhead read as a line dangling into empty space under the one
            // clip rather than as a line crossing three tracks.
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = 0.05f))
            // A tap on empty track is a tap on a moment, so it moves the playhead
            // there - and lets go of the selection, which is how the toolbar gets
            // back to its main tools. Only the bare lane: a clip handles its own
            // tap, because that one also has to select.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val atMs = latestWindow.msAt(offset.x)
                    latestStackTap?.invoke(atMs) ?: latestBareTap?.invoke(atMs) ?: latestScrub(atMs)
                }
            }
    ) {
        ordered.forEachIndexed { i, clip ->
            // Off-screen clips are not built at all. This is what makes a timeline
            // of a hundred cuts cost the same to lay out as one of three.
            if (!window.intersects(clip.timelineStartMs, clip.timelineEndMs)) return@forEachIndexed
            // Keyed by clip, so a clip that changes place in the order - a
            // main-track drag past a neighbour - keeps its own gesture instead
            // of handing the finger to whichever clip now sits in its slot.
            key(clip.id) {
                ClipView(
                    clip = clip,
                    selected = clip.id == state.selectedClipId,
                    waveform = clip.uri?.let { state.waveforms[it.toString()] },
                    window = window,
                    accent = clipColor?.invoke(clip) ?: accent,
                    onSelect = onSelect,
                    onMoveTo = onMoveTo,
                    onTrim = onTrim,
                    onScrub = onScrub,
                    stacked = stacked,
                    onTapAt = stackTap,
                    labelSpan = if (!stacked) null else openStretch(
                        clip.timelineStartMs,
                        clip.timelineEndMs,
                        ordered.drop(i + 1).map { it.timelineStartMs..it.timelineEndMs }
                    )
                )
            }
        }

        if (stacked) {
            ordered.firstOrNull { it.id == state.selectedClipId }?.let { clip ->
                SelectionFrame(
                    id = clip.id,
                    startMs = clip.timelineStartMs,
                    endMs = clip.timelineEndMs,
                    color = clipColor?.invoke(clip) ?: accent,
                    window = window,
                    onTrim = onTrim,
                    cycle = cycle?.takeIf { it.id == clip.id }
                )
            }
        }

        // A tappable marker on every cut, so adding a dissolve is a tap on the
        // join rather than a hunt through a menu.
        onTransitionTap?.let { tap ->
            clips.drop(1).forEach { clip ->
                if (!window.intersects(clip.timelineStartMs, clip.timelineStartMs)) return@forEach
                TransitionBadge(
                    clip = clip,
                    window = window,
                    onTap = { tap(clip.id) }
                )
            }
        }
    }
}

/**
 * The mark on a join: tap it for the transition sheet. Two wedges meeting, solid
 * in the video track's colour once a transition is set - the way the join reads
 * at a glance - and an outline on a plain cut.
 */
@Composable
private fun BoxScope.TransitionBadge(clip: Clip, window: TimelineWindow, onTap: () -> Unit) {
    val active = clip.transitionIn.isActive
    Box(
        modifier = Modifier
            .offset(x = window.xDp(clip.timelineStartMs).dp - 10.dp)
            .align(Alignment.CenterStart)
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Concept.Video.accent else SquishColors.Surface)
            .border(
                width = 1.dp,
                color = if (active) Concept.Video.accent else SquishColors.Border,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClickLabel = if (active) "Change the transition" else "Add a transition", onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            TransitionGlyph,
            contentDescription = if (active) "Transition" else "Cut",
            tint = if (active) SquishColors.Background else SquishColors.TextSecondary,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun ClipView(
    waveform: com.squish.app.media.audio.Waveform? = null,
    clip: Clip,
    selected: Boolean,
    window: TimelineWindow,
    accent: Color,
    onSelect: (String?) -> Unit,
    onMoveTo: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    /** On a stacked lane: solid, so it hides what it lies over, and its grips live in the lane's frame. */
    stacked: Boolean = false,
    /** On a stacked lane, a tap goes to the lane, which decides which clip under it to pick. */
    onTapAt: ((Long) -> Unit)? = null,
    /** On a stacked lane, the stretch of this clip the ones on top leave showing, for its name. */
    labelSpan: LongRange? = null
) {
    val latestMove by rememberUpdatedState(onMoveTo)
    val latestTrim by rememberUpdatedState(onTrim)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestScrub by rememberUpdatedState(onScrub)
    val latestTapAt by rememberUpdatedState(onTapAt)
    val latestWindow by rememberUpdatedState(window)
    val latestClip by rememberUpdatedState(clip)

    /**
     * The part of this clip that is on screen, and only that.
     *
     * A clip is as wide as its own length times the zoom, and that is unbounded:
     * an hour-long shot examined at frame level is millions of pixels, which
     * Compose will not lay out at all. So the box drawn is the screenful of the
     * clip you can see, positioned where that part of the whole clip would have
     * been - which looks identical and costs the same whatever the clip's length.
     */
    val span = window.clampToView(clip.timelineStartMs, clip.timelineEndMs) ?: return
    val drawnStartMs = span.first
    val drawnEndMs = span.last
    // Read fresh by the tap below, whose gesture block outlives any one zoom or scroll.
    val latestDrawnStart by rememberUpdatedState(drawnStartMs)
    val width = window.widthDp(drawnEndMs - drawnStartMs).dp

    /** Whether the clip's real edges are in the part being drawn. */
    val headVisible = drawnStartMs <= clip.timelineStartMs
    val tailVisible = drawnEndMs >= clip.timelineEndMs

    // A short clip must still be trimmable. Fixed 20dp handles covered a two-second
    // clip completely at default zoom, so the grips scale down with the clip and
    // never take more than a third of each end.
    val handleWidth = minOf(HANDLE_WIDTH, width / 3f)

    Box(
        modifier = Modifier
            .offset(x = window.xDp(drawnStartMs).dp)
            .spanWidth(width)
            .fillMaxHeight()
            // Corners only where the clip really ends. A rounded edge in the
            // middle of a long clip would read as a cut that is not there.
            .clip(
                RoundedCornerShape(
                    topStart = if (headVisible) 7.dp else 0.dp,
                    bottomStart = if (headVisible) 7.dp else 0.dp,
                    topEnd = if (tailVisible) 7.dp else 0.dp,
                    bottomEnd = if (tailVisible) 7.dp else 0.dp
                )
            )
            .then(if (stacked) Modifier.background(SquishColors.Background) else Modifier)
            .background(accent.copy(alpha = if (selected) 0.42f else 0.26f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(
                    topStart = if (headVisible) 7.dp else 0.dp,
                    bottomStart = if (headVisible) 7.dp else 0.dp,
                    topEnd = if (tailVisible) 7.dp else 0.dp,
                    bottomEnd = if (tailVisible) 7.dp else 0.dp
                )
            )
            // Tap handled as a gesture rather than Modifier.clickable: clickable sat
            // ahead of the drag detector in the chain and swallowed the drag, which
            // is why clips could be selected but never moved.
            .pointerInput(clip.id) {
                // Selects the clip *and* goes to the moment that was tapped. On a
                // phone the strip is the only place to aim at a frame, so a tap
                // that only selects wastes the one gesture there is room for.
                //
                // The window is asked where the tap landed rather than measuring
                // from the clip's start: what is drawn may begin partway into the
                // clip, so "the clip's start plus this far in" is not the moment
                // the finger is over.
                detectTapGestures { offset ->
                    val atMs = latestWindow.msAt(offset.x + latestWindow.xPx(latestDrawnStart))
                    val lane = latestTapAt
                    if (lane != null) {
                        lane(atMs)
                    } else {
                        latestSelect(clip.id)
                        latestScrub(atMs)
                    }
                }
            }
            // Measured from where the clip was when the finger went down, and
            // each event asks for the clip to start where the finger now puts it
            // - not for a step. Sent as the step from one event to the next, the
            // main track never moved: a clip there only changes place when its
            // middle crosses a neighbour's, no single event's few milliseconds
            // ever got it there, and the clip did not move in between for them
            // to add up. The same goes for an overlay held against a neighbour:
            // it follows the finger again as soon as there is room where the
            // finger is. The receiver works out the step from the clip as it is
            // then, which this composable may not have been shown yet. Nothing
            // is rounded away per event, so a slow drag zoomed in still moves.
            .pointerInput(clip.id) {
                var anchorMs = 0L
                var draggedMs = 0.0
                detectHorizontalDragGestures(
                    onDragStart = {
                        anchorMs = latestClip.timelineStartMs
                        draggedMs = 0.0
                        latestSelect(clip.id)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    draggedMs += latestWindow.msForPx(dragAmount)
                    latestMove(clip.id, anchorMs + draggedMs.roundToLong())
                }
            }
    ) {
        // The clip's own frames, under everything else it draws. Only a video
        // clip has any: an audio clip's picture is its waveform and a caption's
        // is its words, both of which it already shows.
        val strip = clip.uri?.takeIf { clip.kind == ClipKind.Video }
        // A sound's picture: its waveform across the part being drawn, so where
        // the loud bits and the beats fall can be read off the strip.
        if (clip.kind == ClipKind.Audio && waveform != null && waveform.peaks.isNotEmpty() && waveform.durationMs > 0) {
            val fromMs = clip.sourceAt(drawnStartMs)
            val toMs = clip.sourceAt(drawnEndMs)
            Canvas(modifier = Modifier.matchParentSize().padding(vertical = 6.dp)) {
                val bars = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
                val mid = size.height / 2f
                val barWidth = 2.dp.toPx()
                for (b in 0 until bars) {
                    val atMs = fromMs + (toMs - fromMs) * (b + 0.5f) / bars
                    val index = (atMs / waveform.durationMs.toFloat() * waveform.peaks.size).toInt()
                    val peak = waveform.peaks.getOrElse(index.coerceIn(0, waveform.peaks.lastIndex)) { 0f }
                    val half = (peak.coerceIn(0.05f, 1f) * size.height * 0.48f)
                    val x = (b + 0.5f) * size.width / bars
                    drawLine(
                        color = accent.copy(alpha = 0.75f),
                        start = Offset(x, mid - half),
                        end = Offset(x, mid + half),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        if (strip != null) {
            // The frames under the part being drawn, not under the whole clip.
            // The box is a window onto the clip, so sampling the clip's whole
            // source into it would show the wrong moments - and on a long clip
            // would space them minutes apart.
            val spanSourceIn = clip.sourceAt(drawnStartMs)
            val spanSourceOut = clip.sourceAt(drawnEndMs)
            Filmstrip(
                uri = strip,
                sourceInMs = spanSourceIn,
                sourceOutMs = spanSourceOut,
                widthDp = width.value,
                modifier = Modifier.matchParentSize()
            )
            // A wash of the lane's colour over the frames. Without it the strip
            // stops saying which lane it is on - and the lane colours are how a
            // floating layer is told from the base picture at a glance.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(accent.copy(alpha = if (selected) 0.36f else 0.24f))
            )
        }

        // Where the name can go: the whole clip, or on a stacked lane the stretch
        // the clips on top leave showing - clear of the grips when they are out.
        val labelFrom = labelSpan?.let { maxOf(it.first, drawnStartMs) } ?: drawnStartMs
        val labelRoom = labelSpan
            ?.let { window.widthDp((minOf(it.last, drawnEndMs) - labelFrom).coerceAtLeast(0L)).dp }
            ?: width
        val labelInset = when {
            !stacked -> handleWidth + 3.dp
            selected -> STACKED_GRIP + 2.dp
            else -> 5.dp
        }
        if (labelRoom > labelInset * 2 + 16.dp) Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = window.widthDp(labelFrom - drawnStartMs).dp)
                .widthIn(max = labelRoom)
                .padding(horizontal = labelInset)
                // Over pictures the label needs its own ground to stand on; over
                // flat colour it does not, and a chip there would just be clutter.
                .then(
                    if (strip != null || (clip.kind == ClipKind.Audio && waveform != null)) {
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(SquishColors.Background.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = clip.text ?: clip.label,
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (labelRoom > 88.dp) {
                Text(
                    text = Timecode.format(clip.durationMs).removeSuffix(".000"),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextPrimary.copy(alpha = 0.6f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // A retimed clip says so on the strip. Its length already tells you
        // something changed, but not what - and "this is 1.7 seconds long" is not
        // the same information as "this is running at half speed".
        if (!clip.speedRamp.isIdentity && width > 52.dp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = handleWidth + 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(SquishColors.Background.copy(alpha = 0.72f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (clip.speedRamp.isRamped) "ramp"
                    else "${"%.2f".format(clip.speedRamp.flatSpeed).trimEnd('0').trimEnd('.')}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Cyan,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Keyframes, where they sit along the clip. An animated shot should be
        // readable as animated from the strip, without opening a panel.
        clip.keyframes.forEach { key ->
            val atTimeline = clip.timelineStartMs + key.atMs
            if (atTimeline < drawnStartMs || atTimeline > drawnEndMs) return@forEach
            val x = window.widthDp(atTimeline - drawnStartMs).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x - 3.dp, y = (-3).dp)
                    .size(6.dp)
                    .rotate(45f)
                    .background(SquishColors.Amber)
            )
        }

        // Only on an edge that is really there. A handle at the side of a clip
        // that carries on past the screen would trim from a point the user never
        // chose - it is the edge of the view, not the edge of the shot. On a
        // stacked lane the grips are the lane's, drawn over whatever lies on top.
        if (selected && headVisible && !stacked) {
            TrimHandle(accent, handleWidth, Alignment.CenterStart, { latestWindow.msForDp(it) }) { ms ->
                latestTrim(clip.id, ms, 0L)
            }
        }
        if (selected && tailVisible && !stacked) {
            TrimHandle(accent, handleWidth, Alignment.CenterEnd, { latestWindow.msForDp(it) }) { ms ->
                latestTrim(clip.id, 0L, ms)
            }
        }
    }
}

/**
 * How an effect is shown on the strip: where it sits, and the mark and colour
 * that tell it from its neighbours. The editor fills these in, so the timeline
 * never has to know what an effect does.
 */
data class EffectSpan(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val icon: ImageVector,
    val color: Color
)

/**
 * Every effect on one slim lane.
 *
 * Deliberately one row however many there are: effects are short and usually
 * sparse, and stacking overlaps into rows of their own - the way sounds are -
 * would grow the strip every time two landed on the same beat. Where they
 * overlap the shorter one always sits on top, so every bar keeps a part that
 * can be grabbed. Selecting one does not lift it forward - a long effect raised
 * over the short ones would hide them until it was let go.
 */
@Composable
private fun EffectsLane(
    effects: List<EffectSpan>,
    selectedId: String?,
    window: TimelineWindow,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    /** Every tap on the lane, which picks from the pile under it. */
    onTapAt: (Long) -> Unit,
    /** The last pick from a pile, for the "2/3" on the selection. */
    cycle: CycleMark?
) {
    val latestTapAt by rememberUpdatedState(onTapAt)
    val latestWindow by rememberUpdatedState(window)
    val ordered = effects.stackOrder()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FX_LANE_HEIGHT)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Concept.Effects.accent.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectTapGestures { offset -> latestTapAt(latestWindow.msAt(offset.x)) }
            }
    ) {
        ordered.forEachIndexed { i, effect ->
            if (!window.intersects(effect.startMs, effect.endMs)) return@forEachIndexed
            // The name goes in the widest stretch the bars on top leave showing,
            // so a long effect under a short one is still labelled.
            val above = ordered.drop(i + 1)
            val label = openStretch(effect.startMs, effect.endMs, above.map { it.startMs..it.endMs })
            EffectBar(effect, effect.id == selectedId, label, window, onSelect, onMove, onTapAt)
        }
        ordered.firstOrNull { it.id == selectedId }?.let { effect ->
            SelectionFrame(
                id = effect.id,
                startMs = effect.startMs,
                endMs = effect.endMs,
                color = effect.color,
                window = window,
                onTrim = onTrim,
                cycle = cycle?.takeIf { it.id == effect.id }
            )
        }
    }
}

/**
 * One colour per distinct [key] on a lane, handed out in the order the keys first
 * appear along the timeline - so neighbours always differ, which a hash of the id
 * could not promise. The first keeps the lane's own colour, so a lone track looks
 * as it always did.
 */
private fun coloursByKey(clips: List<Clip>, palette: List<Color>, key: (Clip) -> String): (Clip) -> Color {
    val order = clips.sortedBy { it.timelineStartMs }.map(key).distinct()
    val byKey = order.withIndex().associate { (i, k) -> k to palette[i % palette.size] }
    return { clip -> byKey[key(clip)] ?: palette.first() }
}

/** Songs: by source, so the two halves of a split song stay one colour. Cyan first, as the lane always was. */
private val MUSIC_COLOURS = listOf(
    Color(0xFF3DE0C0), Color(0xFF4FA8FF), Color(0xFF7EE08A), Color(0xFFC39BFF), Color(0xFFFF8FB1), Color(0xFFFFB547)
)

/** Titles, captions and stickers: one each. Amber first, as the lane always was. */
private val TEXT_COLOURS = listOf(
    Color(0xFFFFC53D), Color(0xFFFF8FB1), Color(0xFF7CF0FF), Color(0xFFC39BFF), Color(0xFF7EE08A), Color(0xFFFF9A62)
)

/** Drawing order on a stacked lane: longest first, so the shortest ends up on top. */
private fun List<Clip>.stackOrder() = sortedByDescending { it.durationMs }

@JvmName("effectStackOrder")
private fun List<EffectSpan>.stackOrder() = sortedByDescending { it.endMs - it.startMs }

/** The longest part of start..end that none of [covers] lies over. */
private fun openStretch(start: Long, end: Long, covers: List<LongRange>): LongRange {
    var best = start..start
    var from = start
    covers.filter { it.first < end && it.last > start }.sortedBy { it.first }.forEach { c ->
        if (c.first > from && c.first - from > best.last - best.first) best = from..c.first
        from = maxOf(from, c.last)
    }
    if (end > from && end - from > best.last - best.first) best = from..end
    return best
}

@Composable
private fun EffectBar(
    effect: EffectSpan,
    selected: Boolean,
    /** Where along the bar its mark and name can be seen. */
    labelSpan: LongRange,
    window: TimelineWindow,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    /** A tap goes to the lane, which picks among whatever lies under it. */
    onTapAt: (Long) -> Unit
) {
    val latestMove by rememberUpdatedState(onMove)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestTapAt by rememberUpdatedState(onTapAt)
    val latestWindow by rememberUpdatedState(window)

    val span = window.clampToView(effect.startMs, effect.endMs) ?: return
    val drawnStartMs = span.first
    val drawnEndMs = span.last
    val latestDrawnStart by rememberUpdatedState(drawnStartMs)
    val width = window.widthDp(drawnEndMs - drawnStartMs).dp
    val shape = barShape(drawnStartMs <= effect.startMs, drawnEndMs >= effect.endMs)

    Box(
        modifier = Modifier
            .offset(x = window.xDp(drawnStartMs).dp)
            .spanWidth(width)
            .fillMaxHeight()
            .clip(shape)
            // Solid ground first: a bar lying over a longer one must hide it, or
            // the two names print through each other.
            .background(SquishColors.Background)
            .background(effect.color.copy(alpha = if (selected) 0.5f else 0.3f))
            .border(1.dp, effect.color.copy(alpha = 0.6f), shape)
            .pointerInput(effect.id) {
                detectTapGestures { offset ->
                    latestTapAt(latestWindow.msAt(offset.x + latestWindow.xPx(latestDrawnStart)))
                }
            }
            .pointerInput(effect.id) {
                var carriedMs = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        carriedMs = 0f
                        latestSelect(effect.id)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    carriedMs += latestWindow.msForPx(dragAmount).toFloat()
                    val wholeMs = carriedMs.toLong()
                    if (wholeMs != 0L) {
                        carriedMs -= wholeMs
                        latestMove(effect.id, wholeMs)
                    }
                }
            }
    ) {
        // The mark whenever it fits, the name when there is room for it too -
        // measured in the stretch left showing, from wherever that starts on screen.
        val labelFrom = maxOf(labelSpan.first, drawnStartMs)
        val labelTo = minOf(labelSpan.last, drawnEndMs)
        val labelRoom = window.widthDp((labelTo - labelFrom).coerceAtLeast(0L)).dp
        val inset = if (selected) STACKED_GRIP + 2.dp else 5.dp
        if (labelRoom > inset * 2 + 14.dp) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = window.widthDp(labelFrom - drawnStartMs).dp)
                    .width(labelRoom)
                    // Clear of the grips when they are showing; otherwise a small
                    // inset, so the mark on a short bar is not squeezed to nothing.
                    .padding(horizontal = inset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(effect.icon, contentDescription = null, tint = SquishColors.TextPrimary, modifier = Modifier.size(13.dp))
                if (labelRoom > 64.dp) {
                    Text(
                        effect.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val STACKED_GRIP = 14.dp

/** Corners only where the thing really ends, as for clips. */
private fun barShape(headVisible: Boolean, tailVisible: Boolean) = RoundedCornerShape(
    topStart = if (headVisible) 6.dp else 0.dp,
    bottomStart = if (headVisible) 6.dp else 0.dp,
    topEnd = if (tailVisible) 6.dp else 0.dp,
    bottomEnd = if (tailVisible) 6.dp else 0.dp
)

/** Which of a pile a tap picked, and how deep: shown as "2/3" on the selection. */
private data class CycleMark(val id: String, val position: Int, val count: Int)

/**
 * What a tap on a stacked lane selects, given what is under it, top first.
 *
 * The top one - unless the selection is already in the pile, in which case the
 * one below it, wrapping back to the top. So tapping the same spot again walks
 * down through everything there, and a thing buried completely under shorter
 * ones can still be reached from the strip.
 */
private fun pickUnderTap(topFirst: List<String>, current: String?): CycleMark? {
    if (topFirst.isEmpty()) return null
    val at = topFirst.indexOf(current)
    val next = if (at < 0) 0 else (at + 1) % topFirst.size
    return CycleMark(topFirst[next], next + 1, topFirst.size)
}

/**
 * The selection's outline and trim grips on a stacked lane, drawn over every
 * bar. The outline takes no touches, so what it passes over can still be tapped
 * and dragged - only the grips answer. Where the tap that selected it landed on
 * a pile, it says how deep in the pile this one is.
 */
@Composable
private fun SelectionFrame(
    id: String,
    startMs: Long,
    endMs: Long,
    color: Color,
    window: TimelineWindow,
    onTrim: (String, Long, Long) -> Unit,
    cycle: CycleMark?
) {
    val latestTrim by rememberUpdatedState(onTrim)
    val latestWindow by rememberUpdatedState(window)
    val span = window.clampToView(startMs, endMs) ?: return
    val width = window.widthDp(span.last - span.first).dp
    val headVisible = span.first <= startMs
    val tailVisible = span.last >= endMs
    val shape = barShape(headVisible, tailVisible)
    val grip = minOf(STACKED_GRIP, width / 3f)

    Box(
        modifier = Modifier
            .offset(x = window.xDp(span.first).dp)
            .spanWidth(width)
            .fillMaxHeight()
            .clip(shape)
            .border(2.dp, color, shape)
    ) {
        if (cycle != null && cycle.count > 1 && width > grip * 2 + 34.dp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = (if (tailVisible) grip else 0.dp) + 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    "${cycle.position}/${cycle.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Background,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        if (headVisible) {
            TrimHandle(color, grip, Alignment.CenterStart, { latestWindow.msForDp(it) }) { ms ->
                latestTrim(id, ms, 0L)
            }
        }
        if (tailVisible) {
            TrimHandle(color, grip, Alignment.CenterEnd, { latestWindow.msForDp(it) }) { ms ->
                latestTrim(id, 0L, ms)
            }
        }
    }
}

/**
 * A bar's width, even when that is wider than the strip.
 *
 * A bar is built for what is on screen plus half a screen either side, so
 * zoomed in it is wider than the strip itself. Plain `width` quietly caps a
 * child at its parent's width - the bar kept its offset half a screen to the
 * left and lost its right-hand end, so a clip or effect looked as if it
 * stopped partway across the screen. It showed as soon as a pinch zoomed in and
 * playback scrolled the strip, and it squeezed the filmstrip's frames, which
 * were laid out for the full width, into the capped one.
 */
private fun Modifier.spanWidth(width: Dp): Modifier =
    wrapContentWidth(Alignment.Start, unbounded = true).width(width)

/**
 * A trim grip. Reports whole milliseconds, converted by [msForDp] at the zoom of
 * the moment.
 *
 * The leftover fraction is carried between events, as the clip drag and the
 * playhead already did. It used to be truncated per event, and zoomed in - where
 * a pixel is a fraction of a millisecond, and precise trimming is the point - a
 * slow drag was all remainders: the handle did not move at all.
 */
@Composable
private fun BoxScope.TrimHandle(
    accent: Color,
    width: Dp,
    alignment: Alignment,
    msForDp: (Float) -> Double,
    onDragMs: (Long) -> Unit
) {
    val latestToMs by rememberUpdatedState(msForDp)
    val latestDrag by rememberUpdatedState(onDragMs)
    Box(
        modifier = Modifier
            .align(alignment)
            .width(width)
            .fillMaxHeight()
            .background(accent)
            .pointerInput(alignment) {
                var carriedMs = 0.0
                detectHorizontalDragGestures(onDragStart = { carriedMs = 0.0 }) { change, dragAmount ->
                    change.consume()
                    carriedMs += latestToMs(dragAmount / density)
                    val wholeMs = carriedMs.toLong()
                    if (wholeMs != 0L) {
                        carriedMs -= wholeMs
                        latestDrag(wholeMs)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SquishColors.Background.copy(alpha = 0.7f))
        )
    }
}

/**
 * The three things done most to whatever is selected, under the strip: Split,
 * Duplicate, Delete - and a line saying what they will act on.
 *
 * Only where the toolbar is not already showing them. A selected clip's toolbar
 * leads with Split and ends with Duplicate and Delete, and the same three again
 * here, in other colours, was six buttons for three actions. So with a clip's
 * tools up this is the line alone; with nothing selected it is Split (the main
 * track at the playhead, which level 0 has no button for); and with a sheet
 * open over the toolbar it is all three, so a split or a delete is never more
 * taps with a sheet open than without.
 *
 * It used to be eleven buttons in a scrolling row: undo and redo (in the header
 * now, where they never leave the screen), close gaps (the main track closes up
 * by itself), start and end, zoom in, out and fit (pinch zooms and a double tap
 * on the ruler fits), and a second timecode beside the transport's.
 */
@Composable
fun TimelineActionBar(
    state: TimelineState,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    /** The toolbar under the strip is a clip's, with its own Split, Duplicate and Delete. */
    toolbarHasThem: Boolean,
    /** The selection's colour - the one its toolbar wears - so a button looks the same in both places. */
    accent: Color,
    modifier: Modifier = Modifier,
    /** An effect is selected: it is not a clip, but it can be split, copied and deleted too. */
    effectSelected: Boolean = false,
    /** Split is lit when the editor says a cut here would change something; see EditorScreen. */
    splittable: Boolean = false,
    /**
     * Given only when the main track has gaps a draft from before it was
     * magnetic kept (see TimelineModels): the one way to close them, shown
     * only while there is something to close.
     */
    onCloseGaps: (() -> Unit)? = null
) {
    val selected = state.selectedClip
    val anything = selected != null || effectSelected
    val cuttable = state.copy(clips = state.clips.filter { it.kind != ClipKind.Text })
    val nearEdge = !splittable && selected == null && cuttable.mainClipAt(state.playheadMs) != null

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            // As tall with the buttons gone as with them, so the strip does not jump
            // when a clip is selected.
            modifier = Modifier.fillMaxWidth().heightIn(min = MINI_ACTION_SIZE).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!toolbarHasThem) {
                MiniAction(
                    Icons.Filled.ContentCut,
                    "Split at the playhead",
                    accent,
                    onSplit,
                    enabled = splittable
                )
                if (anything) {
                    MiniAction(
                        Icons.Filled.ContentCopy,
                        "Duplicate the selection",
                        accent,
                        onDuplicate
                    )
                    MiniAction(
                        Icons.Filled.DeleteOutline,
                        "Delete the selection",
                        SquishColors.Pink,
                        onDelete
                    )
                }
            }
            onCloseGaps?.let { close ->
                MiniAction(Icons.Filled.Compress, "Close the gaps between shots", SquishColors.TextSecondary, close)
            }
            // Says what the buttons will act on, because a razor that cuts the
            // wrong track - or nothing at all - is worse than no razor. The
            // selection first: what undo would reverse is on the header's button.
            Text(
                when {
                    selected != null -> "${selected.text?.takeIf { it.isNotBlank() } ?: selected.label} · drag to move, drag its ends to trim"
                    effectSelected -> "Effect selected · drag to move, drag its ends to retime"
                    splittable -> "Split cuts the clip under the playhead"
                    nearEdge -> "Too close to the end of the clip to split here"
                    else -> "Tap a clip to edit it · pinch to zoom, double-tap the ruler to fit"
                },
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }
    }
}

/**
 * One action on the strip's bar.
 *
 * A glyph rather than a word, and every one the same square. Scissors mean cut
 * and a bin means delete in every editor anyone has ever used, so the words were
 * buying width to say what the picture says faster - and words of different
 * lengths made a row of controls read as a ransom note. The characters that stood
 * in for some of them before ("↶", "⇤", "✂ Cut") were worse than either: glyphs
 * the font may or may not carry, at whatever size it happened to set them.
 *
 * [description] is not decoration. It is what a screen reader announces and what
 * a long press shows, so it says what the button does rather than naming it -
 * "Cut at the playhead", not "Cut".
 */
@Composable
private fun MiniAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    /**
     * Whether the button would do anything right now.
     *
     * Live buttons are lit in their own colour and dead ones sink into the bar,
     * and both states move when they change. A row where undo looks the same
     * whether or not there is anything to undo makes you press it to find out.
     */
    enabled: Boolean = true
) {
    val fill by animateColorAsState(
        if (enabled) tint.copy(alpha = 0.16f) else SquishColors.Surface,
        label = "actionFill"
    )
    val edge by animateColorAsState(
        if (enabled) tint.copy(alpha = 0.45f) else SquishColors.Border.copy(alpha = 0.4f),
        label = "actionEdge"
    )
    val glyph by animateColorAsState(
        if (enabled) tint else SquishColors.TextMuted.copy(alpha = 0.55f),
        label = "actionGlyph"
    )
    // A press that visibly gives is the difference between a control and a
    // picture of one. Sprung rather than linear, so it settles rather than stops.
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(), label = "actionScale")

    Box(
        modifier = Modifier
            .size(MINI_ACTION_SIZE)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = glyph,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * What the strip has to remember for longer than it is on screen.
 *
 * The strip is folded away while a panel has the room, and everything it
 * `remember`s goes with it. Scoped to the editor's screen, like the editor's own
 * view model, so it lasts exactly as long as the edit it belongs to.
 */
class StripMemory : ViewModel() {
    /** The last fit request carried out; requests are numbers that only go up. */
    var fittedNonce: Long = 0L
}
