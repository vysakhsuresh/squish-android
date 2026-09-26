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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.squish.app.editor.Timecode
import com.squish.app.ui.theme.SquishColors

private val LANE_HEIGHT = 54.dp
private val GUTTER = 34.dp
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
    onMove: (String, Long) -> Unit,
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
    onZoomTo: (Float) -> Unit = {}
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
    val rawMove by rememberUpdatedState(onMove)
    val rawTrim by rememberUpdatedState(onTrim)
    val guardedMove: (String, Long) -> Unit = remember { { id, delta -> if (!guard.active) rawMove(id, delta) } }
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
     */
    LaunchedEffect(fitNonce, viewportPx, state.durationMs) {
        if (fitNonce <= 0L || viewportPx <= 0) return@LaunchedEffect
        val seconds = (totalMs / 1000f).coerceAtLeast(0.001f)
        val usableDp = with(density) { viewportPx.toDp().value } - 24f
        if (usableDp <= 0f) return@LaunchedEffect
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

    val overlayLayers = (state.layerCount downTo 1).toList()
    val audioLanes = state.audioLanes.ifEmpty { listOf(emptyList()) }
    val laneCount = overlayLayers.size + audioLanes.size + 2

    Row(modifier = modifier.fillMaxWidth().background(SquishColors.Background)) {

        Column(modifier = Modifier.width(GUTTER)) {
            Spacer(modifier = Modifier.height(RULER_HEIGHT))
            overlayLayers.forEach { LaneBadge(Icons.Filled.Layers, SquishColors.Magenta) }
            LaneBadge(Icons.Filled.Videocam, SquishColors.Violet)
            audioLanes.forEach { LaneBadge(Icons.Filled.MusicNote, SquishColors.Cyan) }
            LaneBadge(Icons.Filled.TextFields, SquishColors.Amber)
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
                    onScrub = guardedScrub
                )
                overlayLayers.forEach { layer ->
                    Lane(
                        clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == layer },
                        state = state,
                        window = window,
                        accent = SquishColors.Magenta,
                        onSelect = guardedSelect,
                        onMove = guardedMove,
                        onTrim = guardedTrim,
                        onScrub = guardedScrub
                    )
                }
                Lane(
                    clips = state.baseVideoClips,
                    state = state,
                    window = window,
                    accent = SquishColors.Violet,
                    onSelect = guardedSelect,
                    onMove = guardedMove,
                    onTrim = guardedTrim,
                    onScrub = guardedScrub,
                    onTransitionTap = onTransitionTap
                )
                audioLanes.forEach { lane ->
                    Lane(lane, state, window, SquishColors.Cyan, guardedSelect, guardedMove, guardedTrim, guardedScrub)
                }
                Lane(state.textClips, state, window, SquishColors.Amber, guardedSelect, guardedMove, guardedTrim, guardedScrub)
            }

            // Beat lines run the full height, behind the playhead. A grid you can
            // only see on the ruler tells you where the beats are; a grid that
            // crosses the lanes tells you whether a cut is on one.
            val laneHeight = RULER_HEIGHT + LANE_HEIGHT * laneCount
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
                onScrubbingChange = { scrubbing = it }
            )
        }
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
    onScrubbingChange: (Boolean) -> Unit
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val latestScrubbing by rememberUpdatedState(onScrubbingChange)
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
                    onDragStart = {
                        positionMs = latestAtMs.toFloat()
                        latestScrubbing(true)
                    },
                    onDragEnd = { latestScrubbing(false) },
                    onDragCancel = { latestScrubbing(false) }
                ) { change, dragAmount ->
                    change.consume()
                    positionMs += latestWindow.msForPx(dragAmount).toFloat()
                    positionMs = positionMs.coerceAtLeast(0f)
                    latestScrub(positionMs.toLong())
                }
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

@Composable
private fun LaneBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier.height(LANE_HEIGHT).fillMaxWidth(),
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
    onScrub: (Long) -> Unit
) {
    val latestScrub by rememberUpdatedState(onScrub)
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
                detectTapGestures { offset -> latestScrub(latestWindow.msAt(offset.x)) }
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
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTransitionTap: ((String) -> Unit)? = null
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val latestWindow by rememberUpdatedState(window)

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
            // there. Only the bare lane: a clip handles its own tap, because that
            // one also has to select.
            .pointerInput(Unit) {
                detectTapGestures { offset -> latestScrub(latestWindow.msAt(offset.x)) }
            }
    ) {
        clips.forEach { clip ->
            // Off-screen clips are not built at all. This is what makes a timeline
            // of a hundred cuts cost the same to lay out as one of three.
            if (!window.intersects(clip.timelineStartMs, clip.timelineEndMs)) return@forEach
            ClipView(
                clip = clip,
                selected = clip.id == state.selectedClipId,
                window = window,
                accent = accent,
                onSelect = onSelect,
                onMove = onMove,
                onTrim = onTrim,
                onScrub = onScrub
            )
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

@Composable
private fun BoxScope.TransitionBadge(clip: Clip, window: TimelineWindow, onTap: () -> Unit) {
    val active = clip.transitionIn.isActive
    Box(
        modifier = Modifier
            .offset(x = window.xDp(clip.timelineStartMs).dp - 9.dp)
            .align(Alignment.CenterStart)
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) SquishColors.Primary else SquishColors.Surface)
            .border(
                width = 1.dp,
                color = if (active) SquishColors.Primary else SquishColors.Border,
                shape = RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "✕" else "|",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) SquishColors.Background else SquishColors.TextMuted
        )
    }
}

@Composable
private fun ClipView(
    clip: Clip,
    selected: Boolean,
    window: TimelineWindow,
    accent: Color,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit
) {
    val latestMove by rememberUpdatedState(onMove)
    val latestTrim by rememberUpdatedState(onTrim)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestScrub by rememberUpdatedState(onScrub)
    val latestWindow by rememberUpdatedState(window)

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
            .width(width)
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
                    latestSelect(clip.id)
                    latestScrub(latestWindow.msAt(offset.x + latestWindow.xPx(latestDrawnStart)))
                }
            }
            // The leftover fraction is carried between events rather than thrown
            // away. Zoomed in, one pixel is a fraction of a millisecond, and
            // rounding each event on its own turned most of a slow drag into zero.
            .pointerInput(clip.id) {
                var carriedMs = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        carriedMs = 0f
                        latestSelect(clip.id)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    carriedMs += latestWindow.msForPx(dragAmount).toFloat()
                    val wholeMs = carriedMs.toLong()
                    if (wholeMs != 0L) {
                        carriedMs -= wholeMs
                        latestMove(clip.id, wholeMs)
                    }
                }
            }
    ) {
        // The clip's own frames, under everything else it draws. Only a video
        // clip has any: an audio clip's picture is its waveform and a caption's
        // is its words, both of which it already shows.
        val strip = clip.uri?.takeIf { clip.kind == ClipKind.Video }
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

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = handleWidth + 3.dp)
                // Over pictures the label needs its own ground to stand on; over
                // flat colour it does not, and a chip there would just be clutter.
                .then(
                    if (strip != null) {
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
                maxLines = 1
            )
            if (width > 88.dp) {
                Text(
                    text = Timecode.format(clip.durationMs).removeSuffix(".000"),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextPrimary.copy(alpha = 0.6f),
                    maxLines = 1
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
                    maxLines = 1
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
        // chose - it is the edge of the view, not the edge of the shot.
        if (selected && headVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterStart) { deltaDp ->
                latestTrim(clip.id, latestWindow.msForDp(deltaDp).toLong(), 0L)
            }
        }
        if (selected && tailVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterEnd) { deltaDp ->
                latestTrim(clip.id, 0L, latestWindow.msForDp(deltaDp).toLong())
            }
        }
    }
}

/** Receives drag in dp so the caller only has to convert time. */
@Composable
private fun BoxScope.TrimHandle(
    accent: Color,
    width: Dp,
    alignment: Alignment,
    onDragDp: (Float) -> Unit
) {
    val latestDrag by rememberUpdatedState(onDragDp)
    Box(
        modifier = Modifier
            .align(alignment)
            .width(width)
            .fillMaxHeight()
            .background(accent)
            .pointerInput(alignment) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    latestDrag(dragAmount / density)
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

/** Contextual actions for whatever is selected, shown under the timeline. */
@Composable
fun TimelineActionBar(
    state: TimelineState,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onCloseGaps: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onGoToStart: () -> Unit,
    onGoToEnd: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    undoLabel: String?,
    redoLabel: String?,
    modifier: Modifier = Modifier
) {
    val selected = state.selectedClip
    val splittable = state.clips.any { it.spans(state.playheadMs) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Timecode.format(state.playheadMs),
                // Tabular figures: every digit the same width, so a running
                // timecode does not change width thirty times a second. With
                // proportional digits a 1 is narrower than a 0, the readout
                // breathed in and out as it counted, and everything to the right
                // of it was pushed back and forth - which is what made the bar
                // look unstable while a video played.
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                // A floor rather than a fixed width, so a long edit that needs
                // three digits of minutes is not clipped.
                modifier = Modifier.widthIn(min = 76.dp)
            )
        }

        // The actions scroll rather than compete for the width.
        //
        // Eleven buttons do not fit across a phone, so the row was compressing
        // them, and "Close gaps" was the one that gave - wrapping to two lines and
        // back as the timecode beside it changed width. Nothing here is squeezed
        // any more: each button is its own size and the row slides.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // First in the row, because the thing you reach for after a mistake
            // should not be the thing you have to look for.
            MiniAction(
                Icons.AutoMirrored.Filled.Undo,
                "Undo${undoLabel?.let { ": $it" } ?: ""}",
                SquishColors.Cyan,
                onUndo,
                enabled = undoLabel != null
            )
            MiniAction(
                Icons.AutoMirrored.Filled.Redo,
                "Redo${redoLabel?.let { ": $it" } ?: ""}",
                SquishColors.Cyan,
                onRedo,
                enabled = redoLabel != null
            )
            MiniAction(
                Icons.Filled.ContentCut,
                "Cut at the playhead",
                SquishColors.Primary,
                onSplit,
                enabled = splittable
            )
            MiniAction(
                Icons.Filled.DeleteOutline,
                "Delete the selected clip",
                SquishColors.Magenta,
                onDelete,
                enabled = selected != null
            )
            MiniAction(
                Icons.Filled.Compress,
                "Close the gaps between clips",
                SquishColors.TextSecondary,
                onCloseGaps
            )
            // A long edit is a long drag otherwise, and the two ends are where
            // people go most.
            MiniAction(Icons.Filled.FirstPage, "Go to the start", SquishColors.TextSecondary, onGoToStart)
            MiniAction(Icons.AutoMirrored.Filled.LastPage, "Go to the end", SquishColors.TextSecondary, onGoToEnd)
            // A gap, not a fraction: inside a scrolling row the width is
            // unbounded, and a proportion of infinity measures nothing.
            Spacer(modifier = Modifier.width(10.dp))
            MiniAction(Icons.Filled.Remove, "Zoom out", SquishColors.TextSecondary, onZoomOut)
            MiniAction(Icons.Filled.Add, "Zoom in", SquishColors.TextSecondary, onZoomIn)
            // The way back when the strip has been zoomed into a corner of a long
            // edit, which on a phone is most of the time.
            MiniAction(Icons.Filled.FitScreen, "Fit the whole edit on screen", SquishColors.Cyan, onFit)
        }

        // Says what the buttons will act on, because a razor that cuts the wrong
        // track - or nothing at all - is worse than no razor.
        Text(
            when {
                // What undo would reverse, when there is one, because a button
                // marked only "↶" is a button you press and then look at the
                // screen to find out what happened.
                undoLabel != null -> "Undo: $undoLabel · tap anywhere to move the playhead"
                selected != null -> "${selected.label} selected · drag to move, drag its ends to trim"
                splittable -> "Cut splits every track under the playhead"
                else -> "Tap anywhere to move the playhead · pinch to zoom"
            },
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
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
