package com.squish.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
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

/** Zoom limits. Below the first a second is invisible; above it a frame is a mile. */
private const val MIN_PPS = 2f
private const val MAX_PPS = 400f

private fun Long.onTimeline(pixelsPerSecond: Float): Dp = (this / 1000f * pixelsPerSecond).dp

/**
 * How far two fingers must change their spread before it counts as a pinch.
 *
 * Fingers resting on glass are never quite still, and a detector that acted on
 * every pixel of that made the strip shiver in place.
 */
private const val PINCH_SLOP_PX = 12f

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
private suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        // The initial pass, so this is offered the gesture before the scroll that
        // wraps it. Nothing is consumed here: at one finger there is no pinch.
        var spread = 0f
        var engaged = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break

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
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val pps = state.pixelsPerSecond
    // Read fresh inside the gesture: the pointerInput block is keyed on Unit so
    // it survives a zoom, and a captured value would go stale on the first pinch.
    val latestZoomTo by rememberUpdatedState(onZoomTo)
    val latestPps by rememberUpdatedState(pps)
    val contentWidth = maxOf(state.durationMs, 8_000L).onTimeline(pps) + 240.dp

    // The strip's own width in pixels, which is what makes following and fitting
    // possible. Zero until the first layout pass, and every use guards for that.
    var viewportPx by remember { mutableIntStateOf(0) }

    /** The zoom the strip was last laid out at, so a pinch knows what it changed from. */
    var previousPps by remember { mutableFloatStateOf(pps) }

    /**
     * Fits the whole edit across the strip.
     *
     * A ten-minute clip at the default zoom is twenty-five thousand dp of
     * timeline, which is why it ran off to the right and stayed there. Fitting is
     * the answer to seeing all of it; following, below, is the answer to seeing
     * the part that is playing.
     */
    LaunchedEffect(fitNonce, viewportPx, state.durationMs) {
        if (fitNonce <= 0L || viewportPx <= 0) return@LaunchedEffect
        val seconds = (state.durationMs / 1000f).coerceAtLeast(1f)
        val usableDp = with(density) { viewportPx.toDp().value } - 24f
        if (usableDp <= 0f) return@LaunchedEffect
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
    LaunchedEffect(state.playheadMs, isPlaying, viewportPx, pps) {
        if (viewportPx <= 0) return@LaunchedEffect
        val playheadPx = with(density) { state.playheadMs.onTimeline(pps).toPx() }
        val visible = playheadPx - scroll.value

        val target = when {
            isPlaying -> playheadPx - viewportPx * FOLLOW_ANCHOR
            visible < 0f -> playheadPx - viewportPx * FOLLOW_ANCHOR
            visible > viewportPx - EDGE_MARGIN_PX -> playheadPx - viewportPx * FOLLOW_ANCHOR
            else -> return@LaunchedEffect
        }

        val clamped = target.toInt().coerceIn(0, scroll.maxValue)
        if (clamped != scroll.value) scroll.scrollTo(clamped)
    }
    /**
     * Holds the playhead still through a zoom.
     *
     * Scroll is in pixels and the content's width changes with the zoom, so a
     * pinch that did nothing else would slide whatever you were looking at off
     * the screen - the further along the edit you were, the further it would go.
     * Keeping the playhead at the same place on screen is what makes a pinch feel
     * like zooming rather than like being thrown.
     *
     * A frame is waited for first: the effect runs before the new, wider content
     * has been laid out, and `scrollTo` clamps against a `maxValue` that is still
     * the old one.
     */
    LaunchedEffect(pps) {
        val previous = previousPps
        previousPps = pps
        if (previous <= 0f || previous == pps || viewportPx <= 0 || isPlaying) return@LaunchedEffect
        val before = with(density) { state.playheadMs.onTimeline(previous).toPx() } - scroll.value
        // Off screen to begin with: the follow effect above has already decided
        // where the strip should land, and re-anchoring would only undo it.
        if (before < 0f || before > viewportPx) return@LaunchedEffect
        withFrameNanos { }
        val after = with(density) { state.playheadMs.onTimeline(pps).toPx() }
        scroll.scrollTo((after - before).toInt().coerceIn(0, scroll.maxValue))
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
                // Pinch to zoom, anchored on the playhead.
                //
                // Ahead of horizontalScroll in the chain so it sees the gesture on
                // the initial pass, before the scroll can claim it. Anchored rather
                // than free because a zoom that keeps the left edge still throws
                // whatever you were looking at off the screen - the playhead is
                // where your attention is, so it stays put.
                .pointerInput(Unit) {
                    detectPinch { zoom ->
                        latestZoomTo((latestPps * zoom).coerceIn(MIN_PPS, MAX_PPS))
                    }
                }
                .horizontalScroll(scroll)
        ) {
            Column(modifier = Modifier.width(contentWidth)) {
                Ruler(
                    durationMs = state.durationMs,
                    pixelsPerSecond = pps,
                    markers = markers,
                    barMarkers = barMarkers,
                    onScrub = onScrub
                )
                overlayLayers.forEach { layer ->
                    Lane(
                        clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == layer },
                        state = state,
                        accent = SquishColors.Magenta,
                        onSelect = onSelect,
                        onMove = onMove,
                        onTrim = onTrim,
                        onScrub = onScrub
                    )
                }
                Lane(
                    clips = state.baseVideoClips,
                    state = state,
                    accent = SquishColors.Violet,
                    onSelect = onSelect,
                    onMove = onMove,
                    onTrim = onTrim,
                    onScrub = onScrub,
                    onTransitionTap = onTransitionTap
                )
                audioLanes.forEach { lane ->
                    Lane(lane, state, SquishColors.Cyan, onSelect, onMove, onTrim, onScrub)
                }
                Lane(state.textClips, state, SquishColors.Amber, onSelect, onMove, onTrim, onScrub)
            }

            // Beat lines run the full height, behind the playhead. A grid you can
            // only see on the ruler tells you where the beats are; a grid that
            // crosses the lanes tells you whether a cut is on one.
            val laneHeight = RULER_HEIGHT + LANE_HEIGHT * laneCount
            markers.forEach { at ->
                val isBar = at in barMarkers
                Box(
                    modifier = Modifier
                        .offset(x = at.onTimeline(pps))
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
                pixelsPerSecond = pps,
                height = laneHeight,
                onScrub = onScrub
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
    pixelsPerSecond: Float,
    height: Dp,
    onScrub: (Long) -> Unit
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val x = atMs.onTimeline(pixelsPerSecond)

    Column(
        modifier = Modifier
            .offset(x = x - PLAYHEAD_HEAD / 2)
            .width(PLAYHEAD_HEAD)
            .zIndex(2f)
            // The whole column drags, head and line alike, so a finger that lands
            // slightly low still moves it.
            .pointerInput(pixelsPerSecond) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaMs = (dragAmount / density / pixelsPerSecond * 1000f).toLong()
                    latestScrub((atMs + deltaMs).coerceAtLeast(0L))
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
    pixelsPerSecond: Float,
    markers: List<Long>,
    barMarkers: List<Long>,
    onScrub: (Long) -> Unit
) {
    val latestScrub by rememberUpdatedState(onScrub)
    // A tick every second is unreadable when zoomed out, so widen the step until
    // labels have room to breathe.
    val stepMs = when {
        pixelsPerSecond >= 90f -> 1_000L
        pixelsPerSecond >= 40f -> 2_000L
        pixelsPerSecond >= 18f -> 5_000L
        else -> 10_000L
    }
    val total = maxOf(durationMs, 8_000L)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            .pointerInput(pixelsPerSecond) {
                detectTapGestures { offset ->
                    latestScrub((offset.x / density / pixelsPerSecond * 1000f).toLong())
                }
            }
            // Dragging the ruler scrubs. Tapping alone meant finding a frame took a
            // series of guesses instead of one continuous movement.
            .pointerInput(pixelsPerSecond) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestScrub((change.position.x / density / pixelsPerSecond * 1000f).toLong())
                }
            }
    ) {
        // Drawn first so the second ticks and their labels sit over them.
        markers.forEach { at ->
            val isBar = at in barMarkers
            Box(
                modifier = Modifier
                    .offset(x = at.onTimeline(pixelsPerSecond))
                    .width(if (isBar) 2.dp else 1.dp)
                    .height(if (isBar) RULER_HEIGHT else RULER_HEIGHT * 0.5f)
                    .background(SquishColors.Cyan.copy(alpha = if (isBar) 0.8f else 0.4f))
            )
        }

        var t = 0L
        while (t <= total) {
            val label = t
            Column(modifier = Modifier.offset(x = label.onTimeline(pixelsPerSecond))) {
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
    accent: Color,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTransitionTap: ((String) -> Unit)? = null
) {
    val latestScrub by rememberUpdatedState(onScrub)
    val pps = state.pixelsPerSecond

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LANE_HEIGHT)
            .padding(vertical = 3.dp)
            // A tap on empty track is a tap on a moment, so it moves the playhead
            // there. Only the bare lane: a clip handles its own tap, because that
            // one also has to select.
            .pointerInput(pps) {
                detectTapGestures { offset ->
                    latestScrub((offset.x / density / pps * 1000f).toLong().coerceAtLeast(0L))
                }
            }
    ) {
        clips.forEach { clip ->
            ClipView(
                clip = clip,
                selected = clip.id == state.selectedClipId,
                pixelsPerSecond = state.pixelsPerSecond,
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
                TransitionBadge(
                    clip = clip,
                    pixelsPerSecond = state.pixelsPerSecond,
                    onTap = { tap(clip.id) }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TransitionBadge(clip: Clip, pixelsPerSecond: Float, onTap: () -> Unit) {
    val active = clip.transitionIn.isActive
    Box(
        modifier = Modifier
            .offset(x = clip.timelineStartMs.onTimeline(pixelsPerSecond) - 9.dp)
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
    pixelsPerSecond: Float,
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
    val width = clip.durationMs.onTimeline(pixelsPerSecond)

    // A short clip must still be trimmable. Fixed 20dp handles covered a two-second
    // clip completely at default zoom, so the grips scale down with the clip and
    // never take more than a third of each end.
    val handleWidth = minOf(HANDLE_WIDTH, width / 3f)

    Box(
        modifier = Modifier
            .offset(x = clip.timelineStartMs.onTimeline(pixelsPerSecond))
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = if (selected) 0.42f else 0.26f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(7.dp)
            )
            // Tap handled as a gesture rather than Modifier.clickable: clickable sat
            // ahead of the drag detector in the chain and swallowed the drag, which
            // is why clips could be selected but never moved.
            .pointerInput(clip.id, pixelsPerSecond) {
                // Selects the clip *and* goes to the moment that was tapped. On a
                // phone the strip is the only place to aim at a frame, so a tap
                // that only selects wastes the one gesture there is room for.
                detectTapGestures { offset ->
                    latestSelect(clip.id)
                    val into = (offset.x / density / pixelsPerSecond * 1000f).toLong()
                    latestScrub((clip.timelineStartMs + into).coerceAtLeast(0L))
                }
            }
            .pointerInput(clip.id, pixelsPerSecond) {
                detectHorizontalDragGestures(
                    onDragStart = { latestSelect(clip.id) }
                ) { change, dragAmount ->
                    change.consume()
                    latestMove(clip.id, (dragAmount / density / pixelsPerSecond * 1000f).toLong())
                }
            }
    ) {
        // The clip's own frames, under everything else it draws. Only a video
        // clip has any: an audio clip's picture is its waveform and a caption's
        // is its words, both of which it already shows.
        val strip = clip.uri?.takeIf { clip.kind == ClipKind.Video }
        if (strip != null) {
            Filmstrip(
                uri = strip,
                sourceInMs = clip.sourceInMs,
                sourceOutMs = clip.sourceOutMs,
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
            val x = (key.atMs / 1000f * pixelsPerSecond).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x - 3.dp, y = (-3).dp)
                    .size(6.dp)
                    .rotate(45f)
                    .background(SquishColors.Amber)
            )
        }

        if (selected) {
            TrimHandle(accent, handleWidth, Alignment.CenterStart) { delta ->
                latestTrim(clip.id, (delta / pixelsPerSecond * 1000f).toLong(), 0L)
            }
            TrimHandle(accent, handleWidth, Alignment.CenterEnd) { delta ->
                latestTrim(clip.id, 0L, (delta / pixelsPerSecond * 1000f).toLong())
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
                style = MaterialTheme.typography.labelLarge,
                color = SquishColors.TextPrimary
            )
            Spacer(modifier = Modifier.width(4.dp))
            // First in the row, because the thing you reach for after a mistake
            // should not be the thing you have to look for.
            MiniAction(
                "↶",
                if (undoLabel == null) SquishColors.TextMuted else SquishColors.Cyan,
                onUndo
            )
            MiniAction(
                "↷",
                if (redoLabel == null) SquishColors.TextMuted else SquishColors.Cyan,
                onRedo
            )
            MiniAction(
                "✂ Cut",
                if (splittable) SquishColors.Primary else SquishColors.TextMuted,
                onSplit
            )
            MiniAction(
                "Delete",
                if (selected == null) SquishColors.TextMuted else SquishColors.Magenta,
                onDelete
            )
            MiniAction("Close gaps", SquishColors.TextSecondary, onCloseGaps)
            // A long edit is a long drag otherwise, and the two ends are where
            // people go most.
            MiniAction("⇤", SquishColors.TextSecondary, onGoToStart)
            MiniAction("⇥", SquishColors.TextSecondary, onGoToEnd)
            Spacer(modifier = Modifier.fillMaxWidth(0.02f))
            MiniAction("−", SquishColors.TextSecondary, onZoomOut)
            MiniAction("+", SquishColors.TextSecondary, onZoomIn)
            // The way back when the strip has been zoomed into a corner of a long
            // edit, which on a phone is most of the time.
            MiniAction("Fit", SquishColors.Cyan, onFit)
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

@Composable
private fun MiniAction(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SquishColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
