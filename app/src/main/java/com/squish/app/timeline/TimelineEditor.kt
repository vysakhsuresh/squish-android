package com.squish.app.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropSquare
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.editor.Concept
import com.squish.app.editor.Timecode
import com.squish.app.editor.TransitionGlyph
import com.squish.app.ui.theme.SquishColors
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

private val LANE_HEIGHT = 54.dp

/** The effects lane is slimmer than the rest: its bars carry a mark and a name, not pictures. */
private val FX_LANE_HEIGHT = 32.dp

/**
 * A row folded down while the selection is on another track: enough to see
 * where things are and to tap one, not enough to take room from the row being
 * worked on.
 */
private val THIN_LANE_HEIGHT = 20.dp
private val GUTTER = 42.dp
private val RULER_HEIGHT = 26.dp
private val HANDLE_WIDTH = 20.dp

/**
 * The most the rows take before they scroll inside the strip: four full rows.
 * The ruler stays put above them, so where the playhead is in time never
 * scrolls away with the fifth song.
 */
private val ROWS_MAX = LANE_HEIGHT * 4

/** How far from an edge counts as an edge, for snapping: a third of a fingertip. */
private val SNAP_DP = 10.dp

/** How close to the strip's side a carried clip starts scrolling it. */
private val EDGE_ZONE = 48.dp

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

/** One square for every button on the strip's action bar. */
private val MINI_ACTION_SIZE = 38.dp

/** How far apart ruler labels must land to not run into each other - room for "10:00". */
private const val MIN_LABEL_GAP_DP = 52f

/**
 * How long the view holds where a finger left it for the edit's playhead to
 * catch up, before following the playhead again regardless.
 */
private const val SETTLE_MS = 150L

/**
 * A multi-track timeline in the way every phone editor has taught people to
 * expect: the playhead fixed in the middle, the strip moving under it - drag it
 * to scrub, and it scrolls by itself while playing - with the picture, overlays,
 * sound, words and effects on rows against one ruler.
 *
 * A tap selects; a tap on bare track lets go. A long press picks a clip up: on
 * the main track it is carried to a new place in the running order, anywhere
 * else to a new time and, carried up or down, a new row. The handles of the
 * selection trim it. Carried and trimmed edges snap to the playhead, the other
 * clips, markers and beats, with a line and a tick.
 */
@Composable
fun TimelineEditor(
    state: TimelineState,
    onSelect: (String?) -> Unit,
    /**
     * A trim handle dragged: the clip, its head (true) or its tail, and the
     * moment of the timeline the edge is wanted at. Absolute, not a step, so the
     * receiver works the trim out from the clip as it is then (see
     * [TimelineLanes.edgeTrim]) - a second touch event can arrive before the
     * first one's trim is on screen, and a step worked out here would be
     * applied twice.
     */
    onTrimEdge: (String, Boolean, Long) -> Unit,
    /**
     * The head handle of a main-track clip, whose edge does not move: where the
     * file should start now (see [TimelineLanes.anchoredHeadIn]).
     */
    onTrimHeadIn: (String, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTransitionTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Marks to snap to — the beat grid, or anything dropped by hand. */
    markers: List<Long> = emptyList(),
    /** Which of those start a bar, drawn taller so the phrasing is readable. */
    barMarkers: List<Long> = emptyList(),
    /**
     * Bumped to ask the strip to fit the whole edit across its width. Only the
     * strip knows how wide it is, so the zoom has to be computed here and handed
     * back rather than worked out by whoever wants it.
     */
    fitNonce: Long = 0L,
    onZoomTo: (Float) -> Unit = {},
    /** A main-track clip carried and dropped: its id, and its place among the others. */
    onReorder: (String, Int) -> Unit = { _, _ -> },
    /**
     * Anything else carried and dropped: its id, where it now starts, and its row
     * - the layer, for an overlay; which of the rows, for a sound or a line.
     */
    onPlace: (String, Long, Int) -> Unit = { _, _, _ -> },
    /** An effect carried along its lane, by this many milliseconds. */
    onEffectMove: (String, Long) -> Unit = { _, _ -> },
    /** An effect's handle dragged: its id, head or tail, and where the edge is wanted. */
    onEffectTrimEdge: (String, Boolean, Long) -> Unit = { _, _, _ -> },
    /**
     * What each track's icon does when tapped - the way in to adding to that
     * track, beside the track itself. Video offers a menu (see [VideoTrackButton]);
     * overlays open the picker; sound and words open their tools. Null leaves the
     * icon a plain label.
     */
    onAddVideo: (() -> Unit)? = null,
    onAddBlank: (() -> Unit)? = null,
    onAddOverlay: (() -> Unit)? = null,
    onOpenSound: (() -> Unit)? = null,
    onOpenWords: (() -> Unit)? = null,
    /**
     * Closes the gap before a main-track clip. Only a draft from before the
     * track was magnetic has gaps; each is drawn, and a tap on it offers this.
     */
    onCloseGap: ((String) -> Unit)? = null,
    /**
     * A finger is moving the strip, or carrying a clip (true), or has let go
     * (false). The preview pauses, serves the scrub from sync samples and settles
     * it exactly the moment the finger lifts.
     */
    onScrubbingChange: (Boolean) -> Unit = {},
    /**
     * Only the ruler and the row the selection is on, while a tool's sheet has
     * the room below.
     */
    compact: Boolean = false,
    /** A double tap on the ruler: the whole edit on screen. Pinch zooms; the zoom buttons are gone. */
    onFit: () -> Unit = {}
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // Every tap, select, lift and trim below goes through the guard, so the
    // fingers of a pinch are never read as any of them.
    val guard = remember { MultiTouchGuard() }
    val rawScrub by rememberUpdatedState(onScrub)
    val rawSelect by rememberUpdatedState(onSelect)
    val guardedSelect: (String?) -> Unit = remember { { id -> if (!guard.blocking) rawSelect(id) } }

    // The strip's own width in pixels. Zero until the first layout pass, and
    // every use guards for that.
    var viewportPx by remember { mutableIntStateOf(0) }

    /**
     * The moment in the middle of the strip while a finger - or a fling, or a
     * clip carried to the edge - is moving it, ahead of the edit's playhead,
     * which only catches up once the scrub has gone round the view model.
     * Null otherwise: then the middle is the playhead, and playing scrolls the
     * strip because the playhead moves.
     */
    var heldMs by remember { mutableStateOf<Double?>(null) }
    val centre = heldMs ?: state.playheadMs.toDouble()

    /**
     * The window onto the edit. Only what is on screen is built, so what the strip
     * costs to lay out does not depend on how long the video is, and a three-hour
     * clip zooms to the frame exactly like a three-second one.
     */
    val window = TimelineWindow.centredOn(
        atMs = centre,
        pixelsPerSecond = state.pixelsPerSecond.coerceIn(MIN_PPS, MAX_PPS),
        density = density.density,
        viewportPx = viewportPx
    )

    // Read fresh inside gestures: the pointerInput blocks are keyed on Unit so
    // they survive a zoom, and a captured value would go stale on the first pinch.
    val latestZoomTo by rememberUpdatedState(onZoomTo)
    val latestWindow by rememberUpdatedState(window)
    val latestState by rememberUpdatedState(state)
    val latestMarkers by rememberUpdatedState(markers)

    /**
     * Dragging the strip is scrubbing. Its own scroll rather than
     * `horizontalScroll`, because that needs content as wide as the timeline,
     * which is what could not be laid out; this turns the drag into time and
     * moves the playhead, stopping at either end of the edit.
     */
    val scrollable = rememberScrollableState { deltaPx ->
        val w = latestWindow
        val from = heldMs ?: latestState.playheadMs.toDouble()
        val to = (from - w.msForPx(deltaPx)).coerceIn(0.0, latestState.durationMs.coerceAtLeast(0L).toDouble())
        heldMs = to
        if (to.toLong() != from.toLong()) rawScrub(to.toLong())
        w.pxForMs(from - to)
    }
    val scrolling = scrollable.isScrollInProgress

    // What is being carried, and what is being trimmed; see [Lift].
    var lift by remember { mutableStateOf<Lift?>(null) }
    var trimming by remember { mutableStateOf<TrimMark?>(null) }
    var trimSnapMs by remember { mutableStateOf<Long?>(null) }

    // A finger on the strip, as far as the preview is concerned: scrolling it
    // or carrying a clip. Playback pauses for either - playing on under a
    // finger that is choosing a frame fought the finger for the playhead.
    val fingerOnStrip = scrolling || lift != null
    val latestScrubbingChange by rememberUpdatedState(onScrubbingChange)
    var reportedScrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(fingerOnStrip) {
        if (reportedScrubbing != fingerOnStrip) {
            reportedScrubbing = fingerOnStrip
            latestScrubbingChange(fingerOnStrip)
        }
    }
    // Folded away mid-drag, the gesture is cancelled without its end running, and
    // the preview would go on believing a finger was down.
    DisposableEffect(Unit) {
        onDispose { if (reportedScrubbing) latestScrubbingChange(false) }
    }

    // Let go: the view stays where the finger left it until the playhead has
    // caught up with the last scrub, then follows the playhead again. Letting go
    // at once would show the playhead from before that scrub for a frame, and
    // the strip would jump back and forward again.
    // Waited for once per release, not re-armed by every move of the playhead:
    // keyed on the playhead, playing straight after letting go restarted the wait
    // on every tick and the strip never let go of the view to follow playback.
    LaunchedEffect(fingerOnStrip) {
        if (fingerOnStrip || heldMs == null) return@LaunchedEffect
        withTimeoutOrNull(SETTLE_MS) {
            snapshotFlow { latestState.playheadMs }.first { at ->
                val held = heldMs
                held == null || abs(at - held) <= 1.0
            }
        }
        heldMs = null
    }

    /**
     * Fits the whole edit on screen, once per request, into half the strip: the
     * playhead is fixed in the middle and the edit can reach from it to either
     * side. The request is marked done in [StripMemory], which outlives the strip
     * being folded away; otherwise reopening it would refit too.
     */
    val memory: StripMemory = viewModel()
    val hasContent = state.durationMs > 0L
    LaunchedEffect(fitNonce, viewportPx, hasContent) {
        if (fitNonce <= memory.fittedNonce || viewportPx <= 0 || !hasContent) return@LaunchedEffect
        memory.fittedNonce = fitNonce
        val viewportDp = with(density) { viewportPx.toDp().value }
        latestZoomTo(TimelineLanes.fitZoom(state.durationMs.coerceAtLeast(1_000L), viewportDp))
    }

    // ---- Rows ------------------------------------------------------------------

    val soundRows = remember(state.audioClips) {
        TimelineLanes.rows(state.audioClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
    }
    val wordRows = remember(state.textClips) {
        TimelineLanes.rows(state.textClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
    }
    val effectRows = remember(state.effects) {
        TimelineLanes.rows(state.effects.map { LaneItem(it.id, it.startMs, it.endMs) })
    }
    val selected = state.selectedClip
    val selectedEffect = state.effects.firstOrNull { it.id == state.selectedClipId }
    val selectedGroup: Group? = when {
        selectedEffect != null -> Group.Effects
        selected == null -> null
        selected.kind == ClipKind.Audio -> Group.Sound
        selected.kind == ClipKind.Text -> Group.Words
        selected.layer > 0 -> Group.Overlay
        else -> Group.Main
    }
    // A track the selection is not on folds to thin bars, so the row being
    // worked on has the room - and a glance still shows where everything is.
    // Not while something is being carried: picking it up selects it, and rows
    // folding and unfolding under the finger would move the row it is over.
    val carried = lift
    val foldFor = if (carried != null) carried.foldFor else selectedGroup
    val latestSelectedGroup by rememberUpdatedState(selectedGroup)
    fun folded(group: Group) = foldFor != null && foldFor != group && group != Group.Main
    val layerCount = state.layerCount
    val soundCount = maxOf(1, TimelineLanes.rowCount(soundRows))
    val wordCount = maxOf(1, TimelineLanes.rowCount(wordRows))
    val effectCount = TimelineLanes.rowCount(effectRows)

    val allRows: List<StripRow> =
        (layerCount downTo 1).map { StripRow(Group.Overlay, it, folded(Group.Overlay), first = it == layerCount) } +
            StripRow(Group.Main, 0, folded = false, first = true) +
            (0 until soundCount).map { StripRow(Group.Sound, it, folded(Group.Sound), first = it == 0) } +
            (0 until wordCount).map { StripRow(Group.Words, it, folded(Group.Words), first = it == 0) } +
            (0 until effectCount).map { StripRow(Group.Effects, it, folded(Group.Effects), first = it == 0) }

    // Compact, while a tool's sheet is open: the ruler and the row the selection
    // is on (the main track when nothing is), so the strip stays in view above
    // the sheet - the playhead, a cut, the thing being worked on.
    val rows: List<StripRow> = if (!compact) allRows else {
        val home = when (selectedGroup) {
            Group.Overlay -> allRows.firstOrNull { it.group == Group.Overlay && it.index == selected?.layer }
            Group.Sound -> allRows.firstOrNull { it.group == Group.Sound && it.index == soundRows[selected?.id] }
            Group.Words -> allRows.firstOrNull { it.group == Group.Words && it.index == wordRows[selected?.id] }
            Group.Effects -> allRows.firstOrNull { it.group == Group.Effects && it.index == effectRows[selectedEffect?.id] }
            else -> null
        } ?: allRows.first { it.group == Group.Main }
        listOf(home.copy(folded = false, first = true))
    }

    val rowScroll = rememberScrollState()
    val rowsHeight = rows.fold(0.dp) { sum, row -> sum + row.height }
    val shownRowsHeight = minOf(rowsHeight, ROWS_MAX)
    val stripHeight = RULER_HEIGHT + shownRowsHeight

    val layout = StripLayout(
        state = state,
        window = window,
        rows = rows,
        rowHeightsPx = rows.map { with(density) { it.height.toPx() } },
        rulerPx = with(density) { RULER_HEIGHT.toPx() },
        rowScroll = { rowScroll.value },
        soundRows = soundRows,
        wordRows = wordRows,
        effectRows = effectRows,
        handlePx = with(density) { HANDLE_WIDTH.toPx() },
        markers = markers,
        snapMs = window.msForDp(SNAP_DP.value).roundToLong(),
        vertical = !compact
    )
    val latestLayout by rememberUpdatedState(layout)

    // ---- Carrying --------------------------------------------------------------

    val latestReorder by rememberUpdatedState(onReorder)
    val latestPlace by rememberUpdatedState(onPlace)
    val latestEffectMove by rememberUpdatedState(onEffectMove)

    // While a clip is held at either side of the strip, the strip scrolls - which
    // moves the playhead, the one thing the middle of the strip always is - so a
    // clip can be carried further than one screen.
    val carrying = lift != null
    LaunchedEffect(carrying) {
        if (!carrying) return@LaunchedEffect
        val zonePx = with(density) { EDGE_ZONE.toPx() }
        while (true) {
            withFrameNanos { }
            val held = lift ?: break
            // Not before the finger has moved: a clip picked up near the side of
            // the strip must not set it scrolling by being picked up.
            if (!held.moved) continue
            val w = latestWindow
            val step = TimelineLanes.edgeScrollMs(held.finger.x, viewportPx, zonePx, w.viewportMs / 60.0)
            if (step == 0.0) continue
            val from = heldMs ?: latestState.playheadMs.toDouble()
            val to = (from + step).coerceIn(0.0, latestState.durationMs.coerceAtLeast(0L).toDouble())
            if (to == from) continue
            heldMs = to
            rawScrub(to.toLong())
        }
    }

    // A tick when a carried or trimmed edge snaps to something, and when a
    // carried shot's landing place moves to another join.
    val drop = lift?.let { layout.resolve(it) }
    val snapLine = drop?.snapMs ?: trimSnapMs
    LaunchedEffect(snapLine) {
        if (snapLine != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val slotIndex = drop?.slot?.index
    var lastSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(slotIndex) {
        if (slotIndex != null && lastSlot != null && slotIndex != lastSlot) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastSlot = slotIndex
    }

    // ---- Trimming --------------------------------------------------------------

    val latestTrimEdge by rememberUpdatedState(onTrimEdge)
    val latestTrimHeadIn by rememberUpdatedState(onTrimHeadIn)
    val latestEffectTrimEdge by rememberUpdatedState(onEffectTrimEdge)
    val trims = remember {
        Trims(
            begin = { id, head -> trimming = TrimMark(id, head) },
            clip = { anchor, head, travelMs ->
                if (!guard.active) {
                    val travel = travelMs.roundToLong()
                    if (anchor.isMain && head) {
                        trimSnapMs = null
                        latestTrimHeadIn(anchor.id, TimelineLanes.anchoredHeadIn(anchor, travel))
                    } else {
                        val s = latestState
                        val raw = (if (head) anchor.timelineStartMs else anchor.timelineEndMs) + travel
                        val exclude = if (head) setOf(anchor.id) else TimelineLanes.movingWithTail(s, anchor.id)
                        val snapped = TimelineLanes.nearest(
                            raw, TimelineLanes.snapTargets(s, latestMarkers, exclude), latestLayout.snapMs
                        )
                        trimSnapMs = snapped
                        latestTrimEdge(anchor.id, head, snapped ?: raw)
                    }
                }
            },
            effect = { anchor, head, travelMs ->
                if (!guard.active) {
                    val raw = (if (head) anchor.startMs else anchor.endMs) + travelMs.roundToLong()
                    val snapped = TimelineLanes.nearest(
                        raw, TimelineLanes.snapTargets(latestState, latestMarkers, setOf(anchor.id)), latestLayout.snapMs
                    )
                    trimSnapMs = snapped
                    latestEffectTrimEdge(anchor.id, head, snapped ?: raw)
                }
            },
            end = {
                trimming = null
                trimSnapMs = null
            }
        )
    }

    val bareTap: () -> Unit = remember { { guardedSelect(null) } }
    val keyTap: (String, Long) -> Unit = remember {
        { id, atMs ->
            if (!guard.blocking) {
                rawSelect(id)
                rawScrub(atMs)
            }
        }
    }

    Row(modifier = modifier.fillMaxWidth().background(SquishColors.Background)) {

        Column(modifier = Modifier.width(GUTTER)) {
            Spacer(modifier = Modifier.height(RULER_HEIGHT))
            // Each track's head is its glyph in its colour, from the one table the
            // toolbar uses too (see Concept), on the first row of the track. Where
            // it adds to its track it is a button: video offers a menu, an overlay
            // opens the picker, sound and words open their tools.
            Column(
                modifier = Modifier
                    .heightIn(max = ROWS_MAX)
                    .verticalScroll(rowScroll)
            ) {
                rows.forEach { row ->
                    if (!row.first) {
                        Spacer(modifier = Modifier.height(row.height))
                        return@forEach
                    }
                    when (row.group) {
                        Group.Overlay -> TrackHead(Concept.Overlay.icon, Concept.Overlay.accent, "Add an overlay", row, onAddOverlay)
                        Group.Main -> if (onAddVideo != null) {
                            VideoTrackButton(onAddVideo, onAddBlank)
                        } else {
                            TrackHead(Concept.Video.icon, Concept.Video.accent, "", row, null)
                        }
                        Group.Sound -> TrackHead(Concept.Sound.icon, Concept.Sound.accent, "Add music or sound", row, onOpenSound)
                        Group.Words -> TrackHead(Concept.Text.icon, Concept.Text.accent, "Add text", row, onOpenWords)
                        Group.Effects -> TrackHead(Concept.Effects.icon, Concept.Effects.accent, "", row, null)
                    }
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
                        latestZoomTo((latestWindow.pixelsPerSecond * zoom).coerceIn(MIN_PPS, MAX_PPS))
                    }
                }
                .scrollable(state = scrollable, orientation = Orientation.Horizontal)
                // After the scroll in the chain, so a held press is seen before the
                // scroll takes the drag, and once a clip is up the drag is the lift's.
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    detectLift(
                        guard = guard,
                        canLift = { at -> latestLayout.liftableAt(at) != null },
                        onLift = { at ->
                            latestLayout.liftableAt(at)?.let { picked ->
                                heldMs = heldMs ?: latestState.playheadMs.toDouble()
                                lift = picked.copy(foldFor = latestSelectedGroup)
                                rawSelect(picked.id)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onMove = { at ->
                            lift = lift?.let { it.copy(finger = at, moved = it.moved || (at - it.origin).getDistance() > slop) }
                        },
                        onDrop = {
                            val held = lift
                            lift = null
                            if (held != null) {
                                val landing = latestLayout.resolve(held)
                                when (held.group) {
                                    Group.Main -> landing.slot?.let { slot ->
                                        if (slot.index != held.fromRow) latestReorder(held.id, slot.index)
                                    }
                                    Group.Effects -> if (landing.startMs != held.startMs) {
                                        latestEffectMove(held.id, landing.startMs - held.startMs)
                                    }
                                    else -> if (landing.startMs != held.startMs || landing.row != held.fromRow) {
                                        latestPlace(held.id, landing.startMs, landing.row)
                                    }
                                }
                            }
                        },
                        onCancel = { lift = null }
                    )
                }
                // Nothing may be drawn outside the strip. Clips extend past both
                // edges by design - only the visible part of one is built - and
                // without this the overhang would paint over the gutter.
                .clipToBounds()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Ruler(
                    durationMs = state.durationMs,
                    window = window,
                    markers = markers,
                    barMarkers = barMarkers,
                    onJump = { ms -> if (!guard.blocking) rawScrub(ms) },
                    onFit = { if (!guard.blocking) onFit() }
                )
                // Bounded always, compact too: the strip sits inside the editor's own
                // scrolling column, and a scroll inside an unbounded one cannot be
                // measured at all. The gutter shares the scroll, so the track heads
                // stay beside their rows.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ROWS_MAX)
                        .verticalScroll(rowScroll)
                ) {
                    rows.forEach { row ->
                        key(row.group, row.index) {
                            when (row.group) {
                                Group.Overlay -> Lane(
                                    clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    window = window,
                                    accent = Concept.Overlay.accent,
                                    waveforms = state.waveforms,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    trimming = trimming,
                                    liftedId = lift?.id,
                                    onKeyTap = keyTap
                                )
                                Group.Main -> Lane(
                                    clips = state.baseVideoClips,
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    window = window,
                                    accent = Concept.Video.accent,
                                    waveforms = state.waveforms,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    trimming = trimming,
                                    liftedId = lift?.id,
                                    onKeyTap = keyTap,
                                    onTransitionTap = onTransitionTap,
                                    gaps = remember(state.baseVideoClips) { state.mainGaps() },
                                    onCloseGap = onCloseGap,
                                    onAddMedia = onAddVideo
                                )
                                Group.Sound -> Lane(
                                    clips = state.audioClips.filter { soundRows[it.id] == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    window = window,
                                    accent = Concept.Sound.accent,
                                    waveforms = state.waveforms,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    trimming = trimming,
                                    liftedId = lift?.id,
                                    onKeyTap = keyTap,
                                    clipColor = remember(state.audioClips) {
                                        coloursByKey(state.audioClips, MUSIC_COLOURS) { it.uri?.toString() ?: it.id }
                                    }
                                )
                                Group.Words -> Lane(
                                    clips = state.textClips.filter { wordRows[it.id] == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    window = window,
                                    accent = Concept.Text.accent,
                                    waveforms = state.waveforms,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    trimming = trimming,
                                    liftedId = lift?.id,
                                    onKeyTap = keyTap,
                                    clipColor = remember(state.textClips) {
                                        coloursByKey(state.textClips, TEXT_COLOURS) { it.id }
                                    }
                                )
                                Group.Effects -> EffectsRow(
                                    effects = state.effects.filter { effectRows[it.id] == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    window = window,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    liftedId = lift?.id
                                )
                            }
                        }
                    }
                }
            }

            // Beat lines run the full height, behind the playhead. A grid you can
            // only see on the ruler tells you where the beats are; a grid that
            // crosses the lanes tells you whether a cut is on one.
            markers.forEach { at ->
                if (!window.intersects(at, at)) return@forEach
                val isBar = at in barMarkers
                Box(
                    modifier = Modifier
                        .offset(x = window.xDp(at).dp)
                        .width(1.dp)
                        .height(stripHeight)
                        .background(
                            if (isBar) SquishColors.Cyan.copy(alpha = 0.42f)
                            else SquishColors.Cyan.copy(alpha = 0.16f)
                        )
                )
            }

            // Where a carried shot would land: a bright bar on the join.
            drop?.slot?.let { slot ->
                val top = layout.rowTopOnScreenPx(layout.rows.indexOfFirst { it.group == Group.Main })
                if (top != null) {
                    Box(
                        modifier = Modifier
                            .offset(x = window.xDp(slot.atMs).dp - 2.dp, y = with(density) { top.toDp() })
                            .width(4.dp)
                            .height(LANE_HEIGHT)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SquishColors.Primary)
                            .zIndex(3f)
                    )
                }
            }

            // What a carried or trimmed edge has snapped to.
            snapLine?.let { at ->
                Box(
                    modifier = Modifier
                        .offset(x = window.xDp(at).dp - 1.dp)
                        .width(2.dp)
                        .height(stripHeight)
                        .background(SquishColors.Amber)
                        .zIndex(3f)
                )
            }

            // The fixed playhead: never a target, so every touch reaches the strip.
            Playhead(x = with(density) { (viewportPx / 2f).toDp() }, height = stripHeight)

            // The carried clip, under the finger, over everything.
            lift?.let { held ->
                val landing = drop ?: return@let
                Ghost(
                    lift = held,
                    x = with(density) { window.xPx(landing.startMs).toDp() },
                    y = with(density) { landing.topPx.toDp() },
                    width = window.widthDp(held.lengthMs).dp,
                    height = with(density) { landing.heightPx.toDp() },
                    maxWidth = with(density) { (viewportPx * 1.5f).toDp() }
                )
            }
        }
    }
}

/** The tracks, top to bottom. */
private enum class Group { Overlay, Main, Sound, Words, Effects }

/**
 * One row of the strip. [index] is the layer on an overlay row and the row's
 * number within its track anywhere else; [first] is the row that carries the
 * track's head.
 */
private data class StripRow(val group: Group, val index: Int, val folded: Boolean, val first: Boolean) {
    val height: Dp
        get() = when {
            folded -> THIN_LANE_HEIGHT
            group == Group.Effects -> FX_LANE_HEIGHT
            else -> LANE_HEIGHT
        }
}

/** A handle being dragged, for the grey stretch of unused footage beside it. */
private data class TrimMark(val id: String, val head: Boolean)

/**
 * What the handles report. The clip or effect as it was when the finger went
 * down, which edge, and how far the finger has gone since, in milliseconds at
 * the zoom of the moment - the strip works out the edge, the snap and the trim.
 */
private class Trims(
    val begin: (String, Boolean) -> Unit,
    val clip: (Clip, Boolean, Double) -> Unit,
    val effect: (EffectSpan, Boolean, Double) -> Unit,
    val end: () -> Unit
)

/**
 * Something picked up off the strip. [fromRow] is where it came from in its
 * track's own terms - its place in the running order on the main track, its
 * layer on an overlay row, its row among the sounds or the words - and [grabMs]
 * is how far into it the finger took hold, so it hangs from the finger at the
 * same place while carried. [finger] is in the strip's coordinates.
 */
private data class Lift(
    val id: String,
    val group: Group,
    val label: String,
    val color: Color,
    val startMs: Long,
    val lengthMs: Long,
    val fromRow: Int,
    val grabMs: Double,
    val finger: Offset,
    /** Which track the rows were folded for when it was picked up; kept until it is let go. */
    val foldFor: Group? = null,
    /** Where the finger picked it up. */
    val origin: Offset = finger,
    /**
     * Whether the finger has gone anywhere since. Until it has, a drop puts it
     * back exactly as it was - a snap target a few pixels off its own edge
     * would otherwise move a clip that was only pressed.
     */
    val moved: Boolean = false
)

/** Where a carried thing would land if let go now, and where to draw it. */
private data class Landing(
    val startMs: Long,
    val row: Int,
    val snapMs: Long?,
    val slot: ReorderSlot?,
    val topPx: Float,
    val heightPx: Float
)

/**
 * The strip as laid out this frame, for the gestures that run between frames:
 * which row is at a height, what is under a finger, where a carried thing would
 * land. Rebuilt every composition and read through `rememberUpdatedState`, so a
 * gesture always asks the layout that is on screen.
 */
private class StripLayout(
    val state: TimelineState,
    val window: TimelineWindow,
    val rows: List<StripRow>,
    val rowHeightsPx: List<Float>,
    val rulerPx: Float,
    val rowScroll: () -> Int,
    val soundRows: Map<String, Int>,
    val wordRows: Map<String, Int>,
    val effectRows: Map<String, Int>,
    val handlePx: Float,
    val markers: List<Long>,
    val snapMs: Long,
    /** Whether a carried thing may change row; not while only one row is shown. */
    val vertical: Boolean
) {
    private val tops: List<Float> = rowHeightsPx.runningFold(0f) { acc, h -> acc + h }

    /** A height on the strip, as a height down the rows' content. */
    private fun contentY(yPx: Float) = yPx - rulerPx + rowScroll()

    private fun rowIndexAtContent(y: Float): Int? {
        if (y < 0f) return null
        for (i in rows.indices) if (y < tops[i + 1]) return i
        return null
    }

    /** The top of a row on the strip, or null for a row that is not there. */
    fun rowTopOnScreenPx(index: Int): Float? =
        if (index !in rows.indices) null else tops[index] + rulerPx - rowScroll()

    /** What a long press at [at] would pick up, or null for nothing (or a trim handle). */
    fun liftableAt(at: Offset): Lift? {
        if (at.y < rulerPx) return null
        val index = rowIndexAtContent(contentY(at.y)) ?: return null
        val row = rows[index]
        val ms = window.exactMsAt(at.x)
        fun inside(start: Long, end: Long) = ms >= start && ms < end
        fun onHandle(id: String, start: Long, end: Long): Boolean {
            if (id != state.selectedClipId) return false
            val startPx = window.xPx(start)
            val endPx = window.xPx(end)
            val grip = minOf(handlePx, (endPx - startPx) / 3f)
            return at.x < startPx + grip || at.x > endPx - grip
        }

        if (row.group == Group.Effects) {
            val effect = state.effects.firstOrNull { effectRows[it.id] == row.index && inside(it.startMs, it.endMs) }
                ?: return null
            if (onHandle(effect.id, effect.startMs, effect.endMs)) return null
            return Lift(
                effect.id, Group.Effects, effect.label, effect.color, effect.startMs,
                effect.endMs - effect.startMs, row.index, ms - effect.startMs, at
            )
        }
        val clip = when (row.group) {
            Group.Overlay -> state.clips.firstOrNull { it.kind == ClipKind.Video && it.layer == row.index && inside(it.timelineStartMs, it.timelineEndMs) }
            Group.Main -> state.baseVideoClips.firstOrNull { inside(it.timelineStartMs, it.timelineEndMs) }
            Group.Sound -> state.audioClips.firstOrNull { soundRows[it.id] == row.index && inside(it.timelineStartMs, it.timelineEndMs) }
            Group.Words -> state.textClips.firstOrNull { wordRows[it.id] == row.index && inside(it.timelineStartMs, it.timelineEndMs) }
            Group.Effects -> null
        } ?: return null
        if (onHandle(clip.id, clip.timelineStartMs, clip.timelineEndMs)) return null
        val (accent, from) = when (row.group) {
            Group.Overlay -> Concept.Overlay.accent to clip.layer
            Group.Main -> Concept.Video.accent to TimelineLanes.mainIndexOf(state.baseVideoClips, clip.id)
            Group.Sound -> Concept.Sound.accent to row.index
            else -> Concept.Text.accent to row.index
        }
        return Lift(
            clip.id, row.group, clip.shownName, accent, clip.timelineStartMs,
            clip.durationMs, from, ms - clip.timelineStartMs, at
        )
    }

    /**
     * Where [lift] would land. The main track by the finger's place among the
     * shots; everything else at the time the finger says, snapped, on the row
     * the finger is over - within its own track: carried above the track's
     * first row it goes to the top one (a new top layer, for an overlay), below
     * the last onto a new row (the lowest layer, for an overlay).
     */
    fun resolve(lift: Lift): Landing {
        val fingerMs = window.exactMsAt(lift.finger.x)
        val wanted = (fingerMs - lift.grabMs).roundToLong()
        val mainIndex = rows.indexOfFirst { it.group == Group.Main }
        if (lift.group == Group.Main) {
            val top = rowTopOnScreenPx(mainIndex) ?: rulerPx
            return Landing(
                startMs = wanted.coerceAtLeast(0L),
                row = lift.fromRow,
                snapMs = null,
                slot = TimelineLanes.reorderSlot(state.baseVideoClips, lift.id, fingerMs.roundToLong()),
                topPx = top,
                heightPx = rowHeightsPx.getOrElse(mainIndex) { 0f }
            )
        }
        val snap = if (!lift.moved) Snap(lift.startMs, null)
        else TimelineLanes.snapSpan(wanted, lift.lengthMs, TimelineLanes.snapTargets(state, markers, setOf(lift.id)), snapMs)

        val own = rows.indices.filter { rows[it].group == lift.group }
        val current = own.firstOrNull { rows[it].index == lift.fromRow } ?: own.firstOrNull() ?: 0
        val fallbackTop = rowTopOnScreenPx(current) ?: rulerPx
        val fallbackHeight = rowHeightsPx.getOrElse(current) { 0f }
        if (!vertical || !lift.moved || lift.group == Group.Effects || own.isEmpty()) {
            return Landing(snap.startMs, lift.fromRow, snap.lineMs, null, fallbackTop, fallbackHeight)
        }

        val y = contentY(lift.finger.y)
        val over = rowIndexAtContent(y)
        val firstTop = tops[own.first()]
        val lastBottom = tops[own.last() + 1]
        var target: Int
        var topPx: Float
        var heightPx: Float
        when {
            over != null && over in own -> {
                target = rows[over].index
                topPx = rowTopOnScreenPx(over) ?: fallbackTop
                heightPx = rowHeightsPx[over]
            }
            y < firstTop -> {
                // Above the track.
                if (lift.group == Group.Overlay) {
                    target = minOf(rows[own.first()].index + 1, MAX_LAYER)
                    if (target == rows[own.first()].index) {
                        topPx = rowTopOnScreenPx(own.first()) ?: fallbackTop
                        heightPx = rowHeightsPx[own.first()]
                    } else {
                        heightPx = rowHeightsPx[own.first()]
                        topPx = firstTop + rulerPx - rowScroll() - heightPx / 2f
                    }
                } else {
                    target = rows[own.first()].index
                    topPx = rowTopOnScreenPx(own.first()) ?: fallbackTop
                    heightPx = rowHeightsPx[own.first()]
                }
            }
            else -> {
                // Below the track.
                if (lift.group == Group.Overlay) {
                    target = rows[own.last()].index
                    topPx = rowTopOnScreenPx(own.last()) ?: fallbackTop
                    heightPx = rowHeightsPx[own.last()]
                } else {
                    // A row of its own below the rest - unless it already has one:
                    // alone on the last row, a new row is the row it is on.
                    val rowsOf = if (lift.group == Group.Sound) soundRows else wordRows
                    val alone = lift.fromRow == rows[own.last()].index && rowsOf.count { it.value == lift.fromRow } <= 1
                    if (alone) {
                        target = lift.fromRow
                        topPx = fallbackTop
                        heightPx = fallbackHeight
                    } else {
                        target = rows[own.last()].index + 1
                        heightPx = maxOf(rowHeightsPx[own.last()], fallbackHeight)
                        topPx = lastBottom + rulerPx - rowScroll() - heightPx / 2f
                    }
                }
            }
        }
        // An overlay only changes layer onto one that is free for it there; the
        // model moves it along its own row otherwise, so that is where it is drawn.
        if (lift.group == Group.Overlay && target != lift.fromRow &&
            !state.layerIsFree(target, snap.startMs, snap.startMs + lift.lengthMs, lift.id)
        ) {
            target = lift.fromRow
            topPx = fallbackTop
            heightPx = fallbackHeight
        }
        return Landing(snap.startMs, target, snap.lineMs, null, topPx, heightPx)
    }
}

/** What the strip calls a clip: its words, its name, or - for a line left blank - that it is empty. */
private val Clip.shownName: String
    get() = text?.takeIf { it.isNotBlank() } ?: if (kind == ClipKind.Text) EMPTY_TEXT else label

private const val EMPTY_TEXT = "Empty text"

/**
 * The playhead: a line down the middle of the strip with a head on the ruler.
 * Fixed - the strip moves under it - and never a touch target, so nothing it
 * crosses is ever hidden from a finger.
 */
@Composable
private fun BoxScope.Playhead(x: Dp, height: Dp) {
    Column(
        modifier = Modifier
            .offset(x = x - PLAYHEAD_HEAD / 2)
            .width(PLAYHEAD_HEAD)
            .zIndex(4f),
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
                .height((height - PLAYHEAD_HEAD * 0.62f).coerceAtLeast(0.dp))
                .background(SquishColors.TextPrimary)
        )
    }
}

private val PLAYHEAD_HEAD = 14.dp

/**
 * A track's head on its first row: its glyph in its colour, framed as a button
 * where it adds to the track, plain where it does not. Folded, it shrinks with
 * the row and stays what it was.
 */
@Composable
private fun TrackHead(icon: ImageVector, tint: Color, label: String, row: StripRow, onClick: (() -> Unit)?) {
    if (onClick == null) {
        Box(modifier = Modifier.height(row.height).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.75f),
                modifier = Modifier.size(if (row.folded) 12.dp else 16.dp)
            )
        }
        return
    }
    TrackButton(icon, tint, label, row.height, row.folded, onClick)
}

/**
 * A track's icon as the way to add to that track - a framed tile in the
 * track's colour, so it reads as a button rather than a label.
 */
@Composable
private fun TrackButton(
    icon: ImageVector,
    tint: Color,
    label: String,
    height: Dp = LANE_HEIGHT,
    small: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(height)
            .fillMaxWidth()
            .padding(vertical = if (small) 2.dp else 5.dp, horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(if (small) 5.dp else 9.dp))
                .background(tint.copy(alpha = 0.16f))
                .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(if (small) 5.dp else 9.dp))
                .clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (small) 12.dp else 18.dp))
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
private fun Ruler(
    durationMs: Long,
    window: TimelineWindow,
    markers: List<Long>,
    barMarkers: List<Long>,
    /** A tap on the ruler: that moment, brought to the playhead. */
    onJump: (Long) -> Unit,
    onFit: () -> Unit = {}
) {
    val latestJump by rememberUpdatedState(onJump)
    val latestFit by rememberUpdatedState(onFit)
    val latestWindow by rememberUpdatedState(window)
    val pixelsPerSecond = window.pixelsPerSecond
    // A tick every second is unreadable when zoomed out, so widen the step until
    // labels have room to breathe. Worked out from how far apart the labels land,
    // against what is on screen rather than how long the video is.
    val readableStepMs = (MIN_LABEL_GAP_DP / pixelsPerSecond.coerceAtLeast(0.001f) * 1000f)
        .toLong().coerceAtLeast(1_000L)
    val stepMs = TimelineSpan.rulerStepMs(window.viewportMs, readableStepMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            // Dragging the ruler is dragging the strip - the strip's own scroll
            // takes it - so only taps are the ruler's. A double tap fits the whole
            // edit on screen; the ruler only, because on a lane a single tap would
            // have to wait to see whether a second came.
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { latestFit() },
                    onTap = { offset -> latestJump(latestWindow.msAt(offset.x)) }
                )
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

        // Only the ticks on screen, and none before the start of the edit: with
        // the playhead in the middle, the left half of the strip is before zero
        // whenever the playhead is near the start.
        var t = (window.firstDrawnMs / stepMs) * stepMs
        if (t < 0L) t = 0L
        val lastTick = minOf(maxOf(durationMs, 0L) + stepMs, window.lastDrawnMs)
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
    row: StripRow,
    selectedId: String?,
    /**
     * Where the view is and how big it is. Passed in rather than read from the
     * state, because a lane that disagreed with the ruler above it about the scale
     * or the scroll would put every clip in the wrong place.
     */
    window: TimelineWindow,
    accent: Color,
    waveforms: Map<String, com.squish.app.media.audio.Waveform>,
    onSelect: (String?) -> Unit,
    /** A tap on bare track, away from every clip: nothing selected any more. */
    onBareTap: () -> Unit,
    trims: Trims,
    trimming: TrimMark?,
    /** A clip being carried, drawn faint where it was. */
    liftedId: String?,
    onKeyTap: (String, Long) -> Unit,
    onTransitionTap: ((String) -> Unit)? = null,
    gaps: List<MainGap> = emptyList(),
    onCloseGap: ((String) -> Unit)? = null,
    /** The main track, empty: a way to put something on it where the playhead is. */
    onAddMedia: (() -> Unit)? = null,
    /**
     * Each clip's own colour, where a lane holds several things worth telling
     * apart at a glance - two songs, a title and a caption. Otherwise every clip
     * wears the lane's [accent].
     */
    clipColor: ((Clip) -> Color)? = null
) {
    val latestBareTap by rememberUpdatedState(onBareTap)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(row.height)
            .padding(vertical = if (row.folded) 2.dp else 3.dp)
            // A lane with nothing on it still has to look like a lane. Without
            // this the audio and caption tracks were bare background, so the
            // playhead read as a line dangling into empty space under the one
            // clip rather than as a line crossing three tracks.
            .clip(RoundedCornerShape(if (row.folded) 4.dp else 7.dp))
            .background(accent.copy(alpha = 0.05f))
            // A tap on empty track lets go of the selection, which is how the
            // toolbar gets back to its main tools. It no longer moves the
            // playhead: the playhead is the middle of the strip, and a tap that
            // scrolled the strip away from what was being looked at was a tap
            // that lost the place.
            .pointerInput(Unit) {
                detectTapGestures { latestBareTap() }
            }
    ) {
        clips.forEach { clip ->
            // Off-screen clips are not built at all. This is what makes a timeline
            // of a hundred cuts cost the same to lay out as one of three.
            if (!window.intersects(clip.timelineStartMs, clip.timelineEndMs)) return@forEach
            // Keyed by clip, so a clip that changes place keeps its own gestures
            // instead of handing the finger to whichever clip now sits in its slot.
            key(clip.id) {
                ClipView(
                    clip = clip,
                    selected = clip.id == selectedId,
                    folded = row.folded,
                    lifted = clip.id == liftedId,
                    waveform = clip.uri?.let { waveforms[it.toString()] },
                    window = window,
                    accent = clipColor?.invoke(clip) ?: accent,
                    onSelect = onSelect,
                    trims = trims,
                    onKeyTap = onKeyTap
                )
            }
        }

        // The stretch of footage past the edge being trimmed that is not used -
        // how far the handle can still go.
        trimming?.let { mark ->
            val clip = clips.firstOrNull { it.id == mark.id } ?: return@let
            val unused = TimelineLanes.unusedBeyond(clip, mark.head)
            if (unused <= 0L) return@let
            val (from, to) = if (mark.head) (clip.timelineStartMs - unused) to clip.timelineStartMs
            else clip.timelineEndMs to (clip.timelineEndMs + unused)
            val span = window.clampToView(from.coerceAtLeast(0L), to) ?: return@let
            Box(
                modifier = Modifier
                    .offset(x = window.xDp(span.first).dp)
                    .spanWidth(window.widthDp(span.last - span.first).dp)
                    .fillMaxHeight()
                    .background(SquishColors.TextMuted.copy(alpha = 0.22f))
                    .border(1.dp, SquishColors.TextMuted.copy(alpha = 0.45f))
            )
        }

        if (!row.folded) {
            gaps.forEach { gap -> GapMark(gap, window, onCloseGap) }

            // A tappable marker on every cut, so adding a dissolve is a tap on the
            // join rather than a hunt through a menu.
            onTransitionTap?.let { tap ->
                clips.drop(1).forEach { clip ->
                    if (!window.intersects(clip.timelineStartMs, clip.timelineStartMs)) return@forEach
                    TransitionBadge(clip = clip, window = window, onTap = { tap(clip.id) })
                }
            }

            if (clips.isEmpty() && onAddMedia != null) {
                AddMedia(x = window.xDp(0L).dp, onClick = onAddMedia)
            }
        }
    }
}

/**
 * A gap on the main track, from a draft made before the track was magnetic:
 * drawn as an outline, and a tap on it offers to close it - this one only.
 */
@Composable
private fun BoxScope.GapMark(gap: MainGap, window: TimelineWindow, onClose: ((String) -> Unit)?) {
    val span = window.clampToView(gap.fromMs, gap.toMs) ?: return
    val width = window.widthDp(span.last - span.first).dp
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .offset(x = window.xDp(span.first).dp)
            .spanWidth(width)
            .fillMaxHeight()
            .border(1.dp, SquishColors.TextMuted.copy(alpha = 0.5f), RoundedCornerShape(7.dp))
            .then(
                if (onClose != null) Modifier.clickable(onClickLabel = "Close this gap") { open = true }
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (width > 36.dp) {
            Text("Gap", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted, maxLines = 1)
        }
        if (onClose != null) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = SquishColors.SurfaceElevated
            ) {
                DropdownMenuItem(
                    text = { Text("Close gap", color = SquishColors.TextPrimary) },
                    onClick = {
                        open = false
                        onClose(gap.clipId)
                    }
                )
            }
        }
    }
}

/** The main track with nothing on it: the one thing to do next, where the edit begins. */
@Composable
private fun BoxScope.AddMedia(x: Dp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .offset(x = x + 6.dp)
            .align(Alignment.CenterStart)
            .clip(RoundedCornerShape(9.dp))
            .background(Concept.Video.accent.copy(alpha = 0.16f))
            .border(1.dp, Concept.Video.accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
            .clickable(onClickLabel = "Add media", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = Concept.Video.accent, modifier = Modifier.size(16.dp))
        Text("Add media", style = MaterialTheme.typography.labelMedium, color = SquishColors.TextPrimary, maxLines = 1)
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
    clip: Clip,
    selected: Boolean,
    /** On a folded row: a bar of colour, and nothing else - no frames, words or handles. */
    folded: Boolean,
    /** Being carried: drawn faint where it was, the carried copy is under the finger. */
    lifted: Boolean,
    waveform: com.squish.app.media.audio.Waveform?,
    window: TimelineWindow,
    accent: Color,
    onSelect: (String?) -> Unit,
    trims: Trims,
    onKeyTap: (String, Long) -> Unit
) {
    val latestSelect by rememberUpdatedState(onSelect)
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
    val width = window.widthDp(drawnEndMs - drawnStartMs).dp

    /** Whether the clip's real edges are in the part being drawn. */
    val headVisible = drawnStartMs <= clip.timelineStartMs
    val tailVisible = drawnEndMs >= clip.timelineEndMs
    val shape = barShape(headVisible, tailVisible, if (folded) 4.dp else 7.dp)

    // A short clip must still be trimmable. Fixed 20dp handles covered a two-second
    // clip completely at default zoom, so the grips scale down with the clip and
    // never take more than a third of each end.
    val handleWidth = minOf(HANDLE_WIDTH, width / 3f)

    Box(
        modifier = Modifier
            .offset(x = window.xDp(drawnStartMs).dp)
            .spanWidth(width)
            .fillMaxHeight()
            .graphicsLayer { alpha = if (lifted) 0.35f else 1f }
            // Corners only where the clip really ends. A rounded edge in the
            // middle of a long clip would read as a cut that is not there.
            .clip(shape)
            .background(accent.copy(alpha = if (selected) 0.42f else if (folded) 0.5f else 0.26f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else accent.copy(alpha = 0.5f),
                shape = shape
            )
            // A tap selects, and does nothing else. It used to move the playhead
            // to the finger as well; with the playhead fixed in the middle that
            // would scroll the strip out from under the tap.
            .pointerInput(clip.id) {
                detectTapGestures { latestSelect(clip.id) }
            }
    ) {
        if (folded) return@Box

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
            Filmstrip(
                uri = strip,
                sourceInMs = clip.sourceAt(drawnStartMs),
                sourceOutMs = clip.sourceAt(drawnEndMs),
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

        val labelInset = handleWidth + 3.dp
        if (width > labelInset * 2 + 16.dp) Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = width)
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
            // A line added and left blank still has to be findable on the strip.
            val empty = clip.kind == ClipKind.Text && clip.text.isNullOrBlank()
            Text(
                text = clip.shownName,
                style = MaterialTheme.typography.labelSmall,
                color = if (empty) SquishColors.TextMuted else SquishColors.TextPrimary,
                fontStyle = if (empty) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (width > 88.dp) {
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
        // readable as animated from the strip, and a tap on a diamond goes to it -
        // the playhead lands on the key, so the pose there is on the picture.
        clip.keyframes.forEach { frame ->
            val atTimeline = clip.timelineStartMs + frame.atMs
            if (atTimeline < drawnStartMs || atTimeline > drawnEndMs) return@forEach
            val x = window.widthDp(atTimeline - drawnStartMs).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x - KEY_TARGET / 2)
                    .size(KEY_TARGET)
                    .pointerInput(clip.id, frame.atMs) {
                        detectTapGestures { onKeyTap(clip.id, latestClip.timelineStartMs + frame.atMs) }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .size(7.dp)
                        .rotate(45f)
                        .background(SquishColors.Amber)
                )
            }
        }

        // Only on an edge that is really there. A handle at the side of a clip
        // that carries on past the screen would trim from a point the user never
        // chose - it is the edge of the view, not the edge of the shot.
        if (selected && headVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterStart, window,
                onStart = { trims.begin(clip.id, true) },
                onTravel = { anchor, travel -> trims.clip(anchor, true, travel) },
                anchor = { latestClip },
                onEnd = trims.end
            )
        }
        if (selected && tailVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterEnd, window,
                onStart = { trims.begin(clip.id, false) },
                onTravel = { anchor, travel -> trims.clip(anchor, false, travel) },
                anchor = { latestClip },
                onEnd = trims.end
            )
        }
    }
}

/** A keyframe's touch target: the diamond is small, the finger is not. */
private val KEY_TARGET = 22.dp

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
 * One row of effects. Overlapping effects go on rows of their own, the way
 * sounds and words do - they used to share one row with the shortest on top and
 * a tap cycling down the pile, which hid a long effect under a short one.
 */
@Composable
private fun EffectsRow(
    effects: List<EffectSpan>,
    row: StripRow,
    selectedId: String?,
    window: TimelineWindow,
    onSelect: (String?) -> Unit,
    onBareTap: () -> Unit,
    trims: Trims,
    liftedId: String?
) {
    val latestBareTap by rememberUpdatedState(onBareTap)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(row.height)
            .padding(vertical = if (row.folded) 2.dp else 3.dp)
            .clip(RoundedCornerShape(if (row.folded) 4.dp else 7.dp))
            .background(Concept.Effects.accent.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectTapGestures { latestBareTap() }
            }
    ) {
        effects.forEach { effect ->
            if (!window.intersects(effect.startMs, effect.endMs)) return@forEach
            key(effect.id) {
                EffectBar(effect, effect.id == selectedId, row.folded, effect.id == liftedId, window, onSelect, trims)
            }
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

@Composable
private fun EffectBar(
    effect: EffectSpan,
    selected: Boolean,
    folded: Boolean,
    lifted: Boolean,
    window: TimelineWindow,
    onSelect: (String?) -> Unit,
    trims: Trims
) {
    val latestSelect by rememberUpdatedState(onSelect)
    val latestEffect by rememberUpdatedState(effect)

    val span = window.clampToView(effect.startMs, effect.endMs) ?: return
    val drawnStartMs = span.first
    val drawnEndMs = span.last
    val width = window.widthDp(drawnEndMs - drawnStartMs).dp
    val headVisible = drawnStartMs <= effect.startMs
    val tailVisible = drawnEndMs >= effect.endMs
    val shape = barShape(headVisible, tailVisible, if (folded) 4.dp else 6.dp)
    val grip = minOf(STACKED_GRIP, width / 3f)

    Box(
        modifier = Modifier
            .offset(x = window.xDp(drawnStartMs).dp)
            .spanWidth(width)
            .fillMaxHeight()
            .graphicsLayer { alpha = if (lifted) 0.35f else 1f }
            .clip(shape)
            .background(effect.color.copy(alpha = if (selected) 0.5f else 0.3f))
            .border(if (selected) 2.dp else 1.dp, effect.color.copy(alpha = if (selected) 1f else 0.6f), shape)
            .pointerInput(effect.id) {
                detectTapGestures { latestSelect(effect.id) }
            }
    ) {
        if (folded) return@Box
        val inset = if (selected) grip + 2.dp else 5.dp
        if (width > inset * 2 + 14.dp) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(width)
                    .padding(horizontal = inset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(effect.icon, contentDescription = null, tint = SquishColors.TextPrimary, modifier = Modifier.size(13.dp))
                if (width > 64.dp) {
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
        if (selected && headVisible) {
            TrimHandle(effect.color, grip, Alignment.CenterStart, window,
                onStart = { trims.begin(effect.id, true) },
                onTravel = { anchor, travel -> trims.effect(anchor, true, travel) },
                anchor = { latestEffect },
                onEnd = trims.end
            )
        }
        if (selected && tailVisible) {
            TrimHandle(effect.color, grip, Alignment.CenterEnd, window,
                onStart = { trims.begin(effect.id, false) },
                onTravel = { anchor, travel -> trims.effect(anchor, false, travel) },
                anchor = { latestEffect },
                onEnd = trims.end
            )
        }
    }
}

private val STACKED_GRIP = 14.dp

/** Corners only where the thing really ends. */
private fun barShape(headVisible: Boolean, tailVisible: Boolean, radius: Dp) = RoundedCornerShape(
    topStart = if (headVisible) radius else 0.dp,
    bottomStart = if (headVisible) radius else 0.dp,
    topEnd = if (tailVisible) radius else 0.dp,
    bottomEnd = if (tailVisible) radius else 0.dp
)

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
 * A trim grip.
 *
 * Reports how far the finger has gone since it went down, in milliseconds, with
 * the thing being trimmed as it was then ([anchor], read once at the start) -
 * the strip turns that into an edge, snaps it, and asks for the trim. The
 * running total is kept as a double: at a high zoom one pixel is a fraction of
 * a millisecond, and a slow drag whose steps were each rounded to whole ones
 * did not move the handle at all.
 */
@Composable
private fun <T> BoxScope.TrimHandle(
    accent: Color,
    width: Dp,
    alignment: Alignment,
    window: TimelineWindow,
    onStart: () -> Unit,
    onTravel: (T, Double) -> Unit,
    anchor: () -> T,
    onEnd: () -> Unit
) {
    val latestWindow by rememberUpdatedState(window)
    val latestStart by rememberUpdatedState(onStart)
    val latestTravel by rememberUpdatedState(onTravel)
    val latestAnchor by rememberUpdatedState(anchor)
    val latestEnd by rememberUpdatedState(onEnd)
    Box(
        modifier = Modifier
            .align(alignment)
            .width(width)
            .fillMaxHeight()
            .background(accent)
            .pointerInput(alignment) {
                var travelMs = 0.0
                var from: T? = null
                detectHorizontalDragGestures(
                    onDragStart = {
                        travelMs = 0.0
                        from = latestAnchor()
                        latestStart()
                    },
                    onDragEnd = { from = null; latestEnd() },
                    onDragCancel = { from = null; latestEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    travelMs += latestWindow.msForPx(dragAmount)
                    from?.let { latestTravel(it, travelMs) }
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
 * The carried clip: a copy of the bar, lifted - a touch larger, with a shadow -
 * where it would land, its name on it. Capped in width: a long shot carried at a
 * deep zoom would otherwise be millions of pixels, which Compose will not lay
 * out; past the cap the copy runs off the side of the strip, which is where the
 * rest of it would be anyway.
 */
@Composable
private fun Ghost(lift: Lift, x: Dp, y: Dp, width: Dp, height: Dp, maxWidth: Dp) {
    val shown = width.coerceIn(24.dp, maxOf(24.dp, maxWidth))
    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .zIndex(5f)
            .spanWidth(shown)
            .height(height)
            .padding(vertical = 2.dp)
            .graphicsLayer {
                scaleX = 1.04f
                scaleY = 1.08f
                shadowElevation = 12.dp.toPx()
                shape = RoundedCornerShape(7.dp)
                clip = true
            }
            .background(SquishColors.SurfaceElevated)
            .background(lift.color.copy(alpha = 0.55f))
            .border(2.dp, lift.color, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        if (shown > 40.dp && height > 24.dp) {
            Text(
                lift.label,
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
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
 * by itself, and a gap left by an old draft is closed from the gap), start and
 * end, zoom in, out and fit (pinch zooms and a double tap on the ruler fits),
 * and a second timecode beside the transport's.
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
    splittable: Boolean = false
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
            // Says what the buttons will act on, because a razor that cuts the
            // wrong track - or nothing at all - is worse than no razor. The
            // selection first: what undo would reverse is on the header's button.
            Text(
                when {
                    selected != null -> "${selected.shownName} · hold to move, drag its ends to trim"
                    effectSelected -> "Effect selected · hold to move, drag its ends to retime"
                    splittable -> "Split cuts the clip under the playhead"
                    nearEdge -> "Too close to the end of the clip to split here"
                    else -> "Drag the timeline to find a moment · tap a clip to edit it"
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
 * lengths made a row of controls read as a ransom note.
 *
 * [description] is not decoration. It is what a screen reader announces and what
 * a long press shows, so it says what the button does rather than naming it -
 * "Split at the playhead", not "Split".
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
     * and both states move when they change.
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
 * The strip is folded away while the keyboard is up, and everything it
 * `remember`s goes with it. Scoped to the editor's screen, like the editor's own
 * view model, so it lasts exactly as long as the edit it belongs to.
 */
class StripMemory : ViewModel() {
    /** The last fit request carried out; requests are numbers that only go up. */
    var fittedNonce: Long = 0L
}
