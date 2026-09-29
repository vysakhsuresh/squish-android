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
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.graphics.Path
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
import com.squish.app.editor.EditRules
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
 * How close to the top or bottom of the rows a carried clip scrolls them, and
 * how fast at most, a frame. Narrower than the sides: a row is only 54 dp, and
 * a clip carried along the bottom row must not scroll the rows by being there.
 */
private val ROW_EDGE_ZONE = 16.dp
private val ROW_EDGE_STEP = 6.dp

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

/** The action bar under the strip, as the editor lays it out: a row of buttons and 6 dp above and below. */
private val ACTION_BAR_HEIGHT = MINI_ACTION_SIZE + 12.dp

/**
 * How tall to make the rows when the strip and its action bar have [room]
 * between them: whatever is left under the ruler and over the bar, from one
 * full row up to four. Four rows and a bar did not fit a short phone's share
 * of the screen, and the bar - Split, and the line saying what it acts on -
 * scrolled out of sight under the rows.
 */
fun stripRowsHeight(room: Dp): Dp = (room - RULER_HEIGHT - ACTION_BAR_HEIGHT).coerceIn(LANE_HEIGHT, ROWS_MAX)

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
    /** The finger lifted off a trim handle, however the drag ended. */
    onTrimEnd: () -> Unit = {},
    /** Marks to snap to, drawn as lines across the rows: anything dropped by hand, and beats snapped to. */
    markers: List<Long> = emptyList(),
    /** Which of those start a bar, drawn taller so the phrasing is readable. */
    barMarkers: List<Long> = emptyList(),
    /**
     * The beat grid on the timeline, at its chosen density: snapped to like
     * the markers, but drawn as dots on the sound they belong to (see
     * [soundBeats]) rather than as lines - forty lines across every row was
     * a cage.
     */
    beats: List<Long> = emptyList(),
    /** The beats a sound carries, in its file's time, to draw on it at the chosen density. */
    soundBeats: (Clip) -> List<Long> = { emptyList() },
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
    /**
     * A clip taken hold of by a long press, before it is carried. Selected
     * through here rather than through [onSelect]: a lift is not a tap, and
     * while Select more is on a tap toggles - which took the lifted clip out
     * of the set the moment it lifted, so the set could never be carried by
     * one of its own.
     */
    onLift: (String) -> Unit = { onSelect(it) },
    /**
     * Whether the rows of tracks the selection is not on fold to thin bars.
     * Not while clips are being added to a selection: the caption to add is
     * on a row the lead is not, and a folded row cannot be read.
     */
    foldRows: Boolean = true,
    /**
     * A clip to keep in view while [compact] and nothing is selected: the row
     * it is on is the one shown. The take being recorded, which is drawn on a
     * sound row but selected by nobody.
     */
    focusClipId: String? = null,
    /** A double tap on the ruler: the whole edit on screen. Pinch zooms; the zoom buttons are gone. */
    onFit: () -> Unit = {},
    /**
     * The playhead to exactly this moment, not snapped. Dragging the strip snaps
     * here, where the strip can draw the moment it snapped to (see [snapScrub]);
     * snapped again by the receiver, the picture showed a cut the line down the
     * middle was not on, and the strip jumped when the finger lifted.
     */
    onSeek: (Long) -> Unit = onScrub,
    /**
     * Whether the markers and beats are snapped to - by a drag of the strip,
     * of a clip or of a handle. Cuts and the playhead are always snapped to.
     */
    snapScrub: Boolean = true,
    /**
     * How tall the rows are, whatever is in them - the editor's to say, from the
     * room it has. A fixed height, so selecting, which folds the rows of other
     * tracks, never resizes the picture above; the rows scroll inside it.
     */
    rowsHeight: Dp = ROWS_MAX
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // Every tap, select, lift and trim below goes through the guard, so the
    // fingers of a pinch are never read as any of them.
    val guard = remember { MultiTouchGuard() }
    val rawScrub by rememberUpdatedState(onScrub)
    val rawSelect by rememberUpdatedState(onSelect)
    val rawLift by rememberUpdatedState(onLift)
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
    // What a drag snaps to: the markers and the beat dots alike - and neither
    // with the switch off. Cuts and the playhead always snap; the marks were
    // folded in whatever the switch said, so at "Every beat" on a 120 BPM song
    // a title could not be put between two dots without clearing the grid.
    val snapMarks = remember(markers, beats, snapScrub) {
        if (!snapScrub) emptyList() else if (beats.isEmpty()) markers else (markers + beats).distinct()
    }
    val latestMarkers by rememberUpdatedState(snapMarks)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestSnapScrub by rememberUpdatedState(snapScrub)

    /**
     * Where the finger has taken the strip, before any snap: what the next step
     * of the drag is measured from. [heldMs] is where it is drawn, which a snap
     * holds on a cut while the finger goes on a little past it; measured from
     * that instead, a slow drag would never get off the cut again.
     */
    var scrubRawMs by remember { mutableStateOf<Double?>(null) }
    /** The cut, marker or beat a drag of the strip is being held on, for the tick. */
    var scrubSnapMs by remember { mutableStateOf<Long?>(null) }

    /**
     * Dragging the strip is scrubbing. Its own scroll rather than
     * `horizontalScroll`, because that needs content as wide as the timeline,
     * which is what could not be laid out; this turns the drag into time and
     * moves the playhead, stopping at either end of the edit.
     *
     * Snapped here, and the playhead sent exactly where the strip is drawn, so
     * the line down the middle and the picture always agree and the view has
     * nothing to catch up with when the finger lifts.
     */
    val scrollable = rememberScrollableState { deltaPx ->
        val w = latestWindow
        val s = latestState
        val end = s.durationMs.coerceAtLeast(0L).toDouble()
        // From the edit as it is: a playhead left past the end of a shortened
        // edit would otherwise report a step the opposite way to the finger.
        val from = (scrubRawMs ?: heldMs ?: s.playheadMs.toDouble()).coerceIn(0.0, end)
        val to = (from - w.msForPx(deltaPx)).coerceIn(0.0, end)
        scrubRawMs = to
        val snapped = if (!latestSnapScrub) null
        else TimelineLanes.nearest(
            to.roundToLong(), TimelineLanes.scrubTargets(s, latestMarkers), w.msForDp(SNAP_DP.value).roundToLong()
        )
        scrubSnapMs = snapped
        val shown = snapped?.toDouble() ?: to
        val before = heldMs
        heldMs = shown
        if (before == null || shown.toLong() != before.toLong()) latestSeek(shown.toLong())
        w.pxForMs(from - to)
    }
    val scrolling = scrollable.isScrollInProgress
    LaunchedEffect(scrubSnapMs) {
        if (scrubSnapMs != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // What is being carried, and what is being trimmed; see [Lift].
    var lift by remember { mutableStateOf<Lift?>(null) }
    var trimming by remember { mutableStateOf<TrimMark?>(null) }
    var trimSnapMs by remember { mutableStateOf<Long?>(null) }

    // A finger on the strip, as far as the preview is concerned: scrolling it,
    // carrying a clip or trimming one. Playback pauses for each - playing on
    // under a finger that is choosing a frame fought the finger for the
    // playhead, and under a trim handle it scrolled the strip away from the
    // edge being dragged, so the trim landed where nobody pointed.
    val fingerOnStrip = scrolling || lift != null || trimming != null
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
        if (fingerOnStrip) return@LaunchedEffect
        scrubSnapMs = null
        if (heldMs == null) {
            scrubRawMs = null
            return@LaunchedEffect
        }
        withTimeoutOrNull(SETTLE_MS) {
            snapshotFlow { latestState.playheadMs }.first { at ->
                val held = heldMs
                held == null || abs(at - held) <= 1.0
            }
        }
        heldMs = null
        scrubRawMs = null
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

    val liveRows = RowMaps(
        sound = remember(state.audioClips) {
            TimelineLanes.rows(state.audioClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
        },
        words = remember(state.textClips) {
            TimelineLanes.rows(state.textClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
        },
        effects = remember(state.effects) {
            TimelineLanes.rows(state.effects.map { LaneItem(it.id, it.startMs, it.endMs) })
        }
    )
    val latestLiveRows by rememberUpdatedState(liveRows)
    /**
     * The rows as they were when a trim began, kept until it ends. Worked out
     * afresh, a trim that took a clip over its neighbour moved it to another row
     * under the finger - a new composable, and the old one's drag died with it,
     * leaving the handle behind and its grey stretch and snap line on screen.
     * Overlapping on one row for the length of a drag, and sorted out when it ends.
     */
    var frozenRows by remember { mutableStateOf<RowMaps?>(null) }
    val shownRows = frozenRows?.let { f ->
        RowMaps(liveRows.sound + f.sound, liveRows.words + f.words, liveRows.effects + f.effects)
    } ?: liveRows
    val soundRows = shownRows.sound
    val wordRows = shownRows.words
    val effectRows = shownRows.effects
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
    val foldFor = when {
        carried != null -> carried.foldFor
        !foldRows -> null
        else -> selectedGroup
    }
    // What a lift keeps the rows folded for: read at the lift, not captured
    // when the gesture handler was made.
    val latestFoldFor by rememberUpdatedState(if (foldRows) selectedGroup else null)
    fun folded(group: Group) = foldFor != null && foldFor != group && group != Group.Main
    // One overlay row even with no overlay on it, like sound and words: its head
    // is the way to add the first one, beside the track it will go on.
    val layerCount = maxOf(1, state.layerCount)
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
    // As it was when a clip was picked up, until it is let go: picking one up
    // selects it, which can close the sheet, and the strip opening out to every
    // row under the finger put the drop on a row nobody pointed at.
    val compactShown = carried?.compact ?: compact
    val latestCompactShown by rememberUpdatedState(compactShown)
    val focus = if (selected == null) state.clips.firstOrNull { it.id == focusClipId } else null
    val rows: List<StripRow> = if (!compactShown) allRows else {
        val home = when {
            focus != null && focus.kind == ClipKind.Audio -> allRows.firstOrNull { it.group == Group.Sound && it.index == soundRows[focus.id] }
            selectedGroup == Group.Overlay -> allRows.firstOrNull { it.group == Group.Overlay && it.index == selected?.layer }
            selectedGroup == Group.Sound -> allRows.firstOrNull { it.group == Group.Sound && it.index == soundRows[selected?.id] }
            selectedGroup == Group.Words -> allRows.firstOrNull { it.group == Group.Words && it.index == wordRows[selected?.id] }
            selectedGroup == Group.Effects -> allRows.firstOrNull { it.group == Group.Effects && it.index == effectRows[selectedEffect?.id] }
            else -> null
        } ?: allRows.first { it.group == Group.Main }
        listOf(home.copy(folded = false, first = true))
    }

    val rowScroll = rememberScrollState()
    // Fixed, not the rows' own height: that changed with every selection, as
    // rows folded and unfolded, and the picture above resized each time.
    val rowsBox = if (compactShown) LANE_HEIGHT else rowsHeight.coerceAtLeast(LANE_HEIGHT)
    val stripHeight = RULER_HEIGHT + rowsBox

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
        effectGripPx = with(density) { STACKED_GRIP.toPx() },
        markers = snapMarks,
        snapMs = window.msForDp(SNAP_DP.value).roundToLong(),
        vertical = !compactShown
    )
    val latestLayout by rememberUpdatedState(layout)

    // ---- Carrying --------------------------------------------------------------

    val latestReorder by rememberUpdatedState(onReorder)
    val latestPlace by rememberUpdatedState(onPlace)
    val latestEffectMove by rememberUpdatedState(onEffectMove)

    // While a clip is held at either side of the strip, the strip scrolls - which
    // moves the playhead, the one thing the middle of the strip always is - so a
    // clip can be carried further than one screen.
    // Held at the top or bottom of the rows, the rows scroll the same way, so a
    // row scrolled out of sight can still be carried to.
    val carrying = lift != null
    val latestRowsBoxPx by rememberUpdatedState(with(density) { rowsBox.toPx() })
    LaunchedEffect(carrying) {
        if (!carrying) return@LaunchedEffect
        val zonePx = with(density) { EDGE_ZONE.toPx() }
        val rowZonePx = with(density) { ROW_EDGE_ZONE.toPx() }
        val rowStepPx = with(density) { ROW_EDGE_STEP.toPx() }
        while (true) {
            withFrameNanos { }
            val held = lift ?: break
            // Not before the finger has moved: a clip picked up near the side of
            // the strip must not set it scrolling by being picked up.
            if (!held.moved) continue
            val layoutNow = latestLayout
            if (layoutNow.vertical) {
                val dy = TimelineLanes.edgeScrollMs(
                    held.finger.y - layoutNow.rulerPx, latestRowsBoxPx.toInt(), rowZonePx, rowStepPx.toDouble()
                )
                if (dy != 0.0) rowScroll.dispatchRawDelta(dy.toFloat())
            }
            val w = latestWindow
            val step = TimelineLanes.edgeScrollMs(held.finger.x, viewportPx, zonePx, w.viewportMs / 60.0)
            if (step == 0.0) continue
            val from = heldMs ?: latestState.playheadMs.toDouble()
            val to = (from + step).coerceIn(0.0, latestState.durationMs.coerceAtLeast(0L).toDouble())
            if (to == from) continue
            heldMs = to
            scrubRawMs = to
            latestSeek(to.toLong())
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
    val latestTrimEnd by rememberUpdatedState(onTrimEnd)
    val latestEffectTrimEdge by rememberUpdatedState(onEffectTrimEdge)
    val trims = remember {
        Trims(
            begin = { id, head, anchor ->
                trimming = TrimMark(id, head, anchor?.takeIf { head && it.isMain })
                frozenRows = latestLiveRows
                // The view holds still under the handle - see fingerOnStrip.
                heldMs = heldMs ?: latestState.playheadMs.toDouble()
            },
            clip = { anchor, head, travelMs ->
                if (!guard.active) {
                    val s = latestState
                    val raw = (if (head) anchor.timelineStartMs else anchor.timelineEndMs) + travelMs.roundToLong()
                    // The head of a main-track shot moves every shot from there on
                    // (see mainShown), the tail every shot after it: none of them
                    // can be a target, being always under the edge.
                    val exclude = if (head && !anchor.isMain) setOf(anchor.id) else TimelineLanes.movingWithTail(s, anchor.id)
                    val snapped = TimelineLanes.nearest(
                        raw, TimelineLanes.snapTargets(s, latestMarkers, exclude), latestLayout.snapMs
                    )
                    trimSnapMs = snapped
                    val edge = snapped ?: raw
                    if (anchor.isMain && head) {
                        latestTrimHeadIn(anchor.id, TimelineLanes.anchoredHeadIn(anchor, edge - anchor.timelineStartMs))
                    } else {
                        latestTrimEdge(anchor.id, head, edge)
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
                frozenRows = null
                latestTrimEnd()
            }
        )
    }

    /**
     * The main track as drawn. While the head of a shot is being trimmed, that
     * shot and every one after it are drawn moved on by as much as it has lost,
     * so the handle stays under the finger and a beat can be aimed at - and the
     * track closes up when the finger lifts. In the edit the head never moves:
     * the track is magnetic, so the footage moves under it and the tail and the
     * shots after it come back instead, which left the handle standing still
     * while the finger went on without it.
     */
    val headTrim = trimming?.mainHead
    val mainShown = if (headTrim == null) state.baseVideoClips else {
        val now = state.baseVideoClips.firstOrNull { it.id == headTrim.id }
        val shift = if (now == null) 0L else headTrim.durationMs - now.durationMs
        if (shift == 0L) state.baseVideoClips else {
            val moving = TimelineLanes.movingWithTail(state, headTrim.id)
            state.baseVideoClips.map { if (it.id in moving) it.copy(timelineStartMs = it.timelineStartMs + shift) else it }
        }
    }

    val bareTap: () -> Unit = remember { { guardedSelect(null) } }
    val keyTap: (String, Long) -> Unit = remember {
        { id, atMs ->
            if (!guard.blocking) {
                rawSelect(id)
                latestSeek(atMs)
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
                    .height(rowsBox)
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
                                lift = picked.copy(foldFor = latestFoldFor, compact = latestCompactShown)
                                rawLift(picked.id)
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
                        .height(rowsBox)
                        .verticalScroll(rowScroll)
                ) {
                    rows.forEach { row ->
                        key(row.group, row.index) {
                            when (row.group) {
                                Group.Overlay -> Lane(
                                    clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    alsoSelected = state.selectedIds,
                                    window = window,
                                    accent = Concept.Overlay.accent,
                                    waveforms = state.waveforms,
                                    onSelect = guardedSelect,
                                    onBareTap = bareTap,
                                    trims = trims,
                                    trimming = trimming,
                                    liftedId = lift?.id,
                                    onKeyTap = keyTap,
                                    // An overlay butted after another on its row takes
                                    // a transition as a shot does, marked the same way.
                                    onTransitionTap = onTransitionTap
                                )
                                Group.Main -> Lane(
                                    clips = mainShown,
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    alsoSelected = state.selectedIds,
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
                                    alsoSelected = state.selectedIds,
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
                                    },
                                    soundBeats = soundBeats
                                )
                                Group.Words -> Lane(
                                    clips = state.textClips.filter { wordRows[it.id] == row.index },
                                    row = row,
                                    selectedId = state.selectedClipId,
                                    alsoSelected = state.selectedIds,
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
                                    alsoSelected = state.selectedIds,
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

/**
 * A handle being dragged, for the grey stretch of unused footage beside it -
 * and, on the head of a main-track shot, the shot as it was when the finger
 * went down, to draw the track from (see mainShown in [TimelineEditor]).
 */
private data class TrimMark(val id: String, val head: Boolean, val mainHead: Clip? = null)

/**
 * What the handles report. The clip or effect as it was when the finger went
 * down, which edge, and how far the finger has gone since, in milliseconds at
 * the zoom of the moment - the strip works out the edge, the snap and the trim.
 */
private class Trims(
    val begin: (String, Boolean, Clip?) -> Unit,
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
    /** Whether the strip was showing only one row when it was picked up; kept until it is let go. */
    val compact: Boolean = false,
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

/** Each item's row on the sound, words and effects tracks, by id. */
private data class RowMaps(val sound: Map<String, Int>, val words: Map<String, Int>, val effects: Map<String, Int>)

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
    /** An effect's grip, which is narrower than a clip's (see [EffectBar]). */
    val effectGripPx: Float,
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
        // The grip as each bar draws it, so the rest of a selected bar lifts: a
        // clip's handle is wider than an effect's, and measured with a clip's the
        // strip beside an effect's grip neither trimmed nor lifted.
        fun onHandle(id: String, start: Long, end: Long, gripPx: Float): Boolean {
            if (id != state.selectedClipId) return false
            val startPx = window.xPx(start)
            val endPx = window.xPx(end)
            val grip = minOf(gripPx, (endPx - startPx) / 3f)
            return at.x < startPx + grip || at.x > endPx - grip
        }

        if (row.group == Group.Effects) {
            val effect = state.effects.firstOrNull { effectRows[it.id] == row.index && inside(it.startMs, it.endMs) }
                ?: return null
            if (onHandle(effect.id, effect.startMs, effect.endMs, effectGripPx)) return null
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
        if (onHandle(clip.id, clip.timelineStartMs, clip.timelineEndMs, handlePx)) return null
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
     * Where [lift] would land, worked out the way the edit will work it out on
     * the drop, so what is drawn under the finger is where it goes. The main
     * track by the finger's place among the shots; everything else at the time
     * the finger says, snapped, on the row the finger is over - within its own
     * track: carried above the track's first row it goes to the top one (a new
     * top layer, for an overlay), below the last onto a new row (the lowest
     * layer, for an overlay) - and then as the edit has it: an overlay onto a
     * layer only where it is free, else along its own and stopped by what is in
     * the way; a sound or a line onto the nearest row free for it; words kept
     * inside the picture and an effect inside the edit.
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

        val own = rows.indices.filter { rows[it].group == lift.group }
        val current = own.firstOrNull { rows[it].index == lift.fromRow } ?: own.firstOrNull() ?: 0
        val fallbackTop = rowTopOnScreenPx(current) ?: rulerPx
        val fallbackHeight = rowHeightsPx.getOrElse(current) { 0f }
        // Not moved yet: exactly where it was - a snap target a few pixels off its
        // own edge would otherwise move a clip that was only pressed.
        if (!lift.moved) return Landing(lift.startMs, lift.fromRow, null, null, fallbackTop, fallbackHeight)

        val snap = TimelineLanes.snapSpan(
            wanted, lift.lengthMs, TimelineLanes.snapTargets(state, markers, setOf(lift.id)), snapMs
        )
        val asked = askedRow(lift, own)

        // Where to draw the track's row [index] (in its own terms); a new one
        // straddles the edge of the track it will open at.
        fun drawnAt(index: Int, above: Boolean): Pair<Float, Float> {
            val shown = own.firstOrNull { rows[it].index == index }
            if (shown != null) return (rowTopOnScreenPx(shown) ?: fallbackTop) to rowHeightsPx[shown]
            if (own.isEmpty()) return fallbackTop to fallbackHeight
            val edge = if (above) own.first() else own.last()
            val height = maxOf(rowHeightsPx[edge], fallbackHeight)
            val boundary = tops[if (above) edge else edge + 1] + rulerPx - rowScroll()
            return (boundary - height / 2f) to height
        }

        val startMs: Long
        val row: Int
        val drawn: Pair<Float, Float>
        when (lift.group) {
            Group.Effects -> {
                // As moveEffect keeps it: whole, and inside the edit.
                startMs = snap.startMs.coerceIn(0L, (state.durationMs - lift.lengthMs).coerceAtLeast(0L))
                row = lift.fromRow
                drawn = fallbackTop to fallbackHeight
            }
            Group.Overlay -> {
                val placed = state.withClipPlaced(lift.id, snap.startMs, asked).clips.firstOrNull { it.id == lift.id }
                startMs = placed?.timelineStartMs ?: lift.startMs
                row = placed?.layer ?: lift.fromRow
                // Overlay rows are drawn top layer first, so a new layer opens above them.
                drawn = if (!vertical) fallbackTop to fallbackHeight
                else drawnAt(row, above = own.isNotEmpty() && row > rows[own.first()].index)
            }
            else -> {
                val words = lift.group == Group.Words
                startMs = if (!words) snap.startMs.coerceAtLeast(0L) else {
                    // As placeClip keeps a line: whole, and inside the picture.
                    val picture = state.pictureEndMs ?: state.videoClips.maxOfOrNull { it.timelineEndMs } ?: Long.MAX_VALUE
                    lift.startMs + EditRules.clampedShift(
                        lift.startMs, lift.startMs + lift.lengthMs, snap.startMs - lift.startMs, picture
                    )
                }
                val items = (if (words) state.textClips else state.audioClips)
                    .map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) }
                row = TimelineLanes.placedRow(items, lift.id, asked, startMs)
                drawn = if (!vertical) fallbackTop to fallbackHeight else drawnAt(row, above = false)
            }
        }
        // The line only where the edge really went: a snap that the picture's end
        // or a neighbouring overlay then moved it off is not where it lands.
        return Landing(startMs, row, snap.lineMs.takeIf { startMs == snap.startMs }, null, drawn.first, drawn.second)
    }

    /** The row the finger asks for, in the track's own terms: see [resolve]. */
    private fun askedRow(lift: Lift, own: List<Int>): Int {
        if (!vertical || lift.group == Group.Effects || own.isEmpty()) return lift.fromRow
        val y = contentY(lift.finger.y)
        val over = rowIndexAtContent(y)
        return when {
            over != null && over in own -> rows[over].index
            y < tops[own.first()] ->
                // Above the track: a new top layer for an overlay, the top row otherwise.
                if (lift.group == Group.Overlay) minOf(rows[own.first()].index + 1, MAX_LAYER)
                else rows[own.first()].index
            // Below it: the lowest layer - never the main track (see withClipPlaced).
            lift.group == Group.Overlay -> rows[own.last()].index
            else -> {
                // A row of its own below the rest - unless it already has one:
                // alone on the last row, a new row is the row it is on.
                val rowsOf = if (lift.group == Group.Sound) soundRows else wordRows
                val alone = lift.fromRow == rows[own.last()].index && rowsOf.count { it.value == lift.fromRow } <= 1
                if (alone) lift.fromRow else rows[own.last()].index + 1
            }
        }
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
    clipColor: ((Clip) -> Color)? = null,
    /** A sound's beats to draw on it, in its file's time. */
    soundBeats: (Clip) -> List<Long> = { emptyList() },
    /** Clips selected alongside [selectedId] (Select more): drawn selected too. */
    alsoSelected: Set<String> = emptySet()
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
                    selected = clip.id == selectedId || clip.id in alsoSelected,
                    folded = row.folded,
                    lifted = clip.id == liftedId,
                    waveform = clip.uri?.let { waveforms[it.toString()] },
                    window = window,
                    accent = clipColor?.invoke(clip) ?: accent,
                    onSelect = onSelect,
                    trims = trims,
                    onKeyTap = onKeyTap,
                    beats = if (clip.kind == ClipKind.Audio) soundBeats(clip) else emptyList()
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
            // join rather than a hunt through a menu. On the main track every
            // shot but the first has a join; on an overlay row only an overlay
            // butted after another does (Clip.hasOverlayJoin), and the mark
            // goes when the join does, as the transition itself does.
            onTransitionTap?.let { tap ->
                val joined = if (clips.any { it.isMain }) clips.drop(1) else clips.filter { it.hasOverlayJoin(clips) }
                joined.forEach { clip ->
                    if (!window.intersects(clip.timelineStartMs, clip.timelineStartMs)) return@forEach
                    TransitionBadge(clip = clip, window = window, accent = accent, onTap = { tap(clip.id) })
                }
            }

            if (clips.isEmpty() && onAddMedia != null) {
                // Beside the playhead, where the eye is: at zero it was off screen whenever
                // sound or words ran on past where the picture had been.
                AddMedia(x = window.xDp(window.centreMs.roundToLong()).dp, onClick = onAddMedia)
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

/** The main track with nothing on it: the one thing to do next, where the playhead is. */
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
 * in the lane's colour once a transition is set - the way the join reads at a
 * glance - and an outline on a plain cut.
 */
@Composable
private fun BoxScope.TransitionBadge(clip: Clip, window: TimelineWindow, accent: Color, onTap: () -> Unit) {
    val active = clip.transitionIn.isActive
    Box(
        modifier = Modifier
            .offset(x = window.xDp(clip.timelineStartMs).dp - 10.dp)
            .align(Alignment.CenterStart)
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent else SquishColors.Surface)
            .border(
                width = 1.dp,
                color = if (active) accent else SquishColors.Border,
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
    onKeyTap: (String, Long) -> Unit,
    /** A sound's beats, in its file's time, drawn as dots along it. */
    beats: List<Long> = emptyList()
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
        // A sound's fades, as CapCut draws them: the corner above the ramp
        // shaded out, with the ramp's own line, at each end that fades. Drawn
        // over the part on screen, measured from the clip's real edges, so a
        // fade on a clip half off screen still starts where the clip does.
        if (clip.kind == ClipKind.Audio && (clip.fadeInMs > 0L || clip.fadeOutMs > 0L)) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val pxPerMs = size.width / (drawnEndMs - drawnStartMs).coerceAtLeast(1L).toFloat()
                val shade = SquishColors.Background.copy(alpha = 0.55f)
                if (clip.fadeInMs > 0L) {
                    val x0 = (clip.timelineStartMs - drawnStartMs) * pxPerMs
                    val x1 = (clip.timelineStartMs + clip.fadeInMs - drawnStartMs) * pxPerMs
                    val wedge = Path().apply {
                        moveTo(x0, size.height); lineTo(x0, 0f); lineTo(x1, 0f); close()
                    }
                    drawPath(wedge, shade)
                    drawLine(accent, Offset(x0, size.height), Offset(x1, 0f), strokeWidth = 1.5.dp.toPx())
                }
                if (clip.fadeOutMs > 0L) {
                    val x1 = (clip.timelineEndMs - drawnStartMs) * pxPerMs
                    val x0 = (clip.timelineEndMs - clip.fadeOutMs - drawnStartMs) * pxPerMs
                    val wedge = Path().apply {
                        moveTo(x0, 0f); lineTo(x1, 0f); lineTo(x1, size.height); close()
                    }
                    drawPath(wedge, shade)
                    drawLine(accent, Offset(x0, 0f), Offset(x1, size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        // The beats a sound carries, as dots along its bottom edge that travel
        // with it - through its speed curve too, so a slowed song's dots spread
        // out as its beats do. What "Cut on the beat" lands on.
        if (beats.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val pxPerMs = size.width / (drawnEndMs - drawnStartMs).coerceAtLeast(1L).toFloat()
                val radius = 2.5.dp.toPx()
                val y = size.height - radius - 2.dp.toPx()
                beats.forEach { at ->
                    val onTimeline = clip.timelineAtSource(at)
                    if (onTimeline < drawnStartMs || onTimeline > drawnEndMs) return@forEach
                    val x = (onTimeline - drawnStartMs) * pxPerMs
                    drawCircle(SquishColors.Background, radius + 1.dp.toPx(), Offset(x, y))
                    drawCircle(SquishColors.Amber, radius, Offset(x, y))
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // A take made in the editor wears a mic, so it is told from a song at a glance.
                if (clip.isVoiceover) {
                    Icon(Icons.Filled.Mic, contentDescription = "Voiceover", tint = SquishColors.Cyan, modifier = Modifier.size(11.dp))
                }
                Text(
                    text = clip.shownName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (empty) SquishColors.TextMuted else SquishColors.TextPrimary,
                    fontStyle = if (empty) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
        // The placement keys in amber; a keyed opacity or level in its sheet's
        // colour, smaller, the same tap - a ducked song used to show nothing on
        // the strip, and its keys had to be hunted for with the sheet's diamond.
        clip.keyframes.forEach { frame -> KeyDiamond(clip, frame.atMs, SquishColors.Amber, 7.dp, drawnStartMs, drawnEndMs, window, latestClip, onKeyTap) }
        clip.opacityKeys.forEach { key -> KeyDiamond(clip, key.atMs, SquishColors.Violet, 6.dp, drawnStartMs, drawnEndMs, window, latestClip, onKeyTap) }
        clip.volumeKeys.forEach { key -> KeyDiamond(clip, key.atMs, SquishColors.Cyan, 6.dp, drawnStartMs, drawnEndMs, window, latestClip, onKeyTap) }

        // Only on an edge that is really there. A handle at the side of a clip
        // that carries on past the screen would trim from a point the user never
        // chose - it is the edge of the view, not the edge of the shot.
        if (selected && headVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterStart, window,
                onStart = { trims.begin(clip.id, true, latestClip) },
                onTravel = { anchor, travel -> trims.clip(anchor, true, travel) },
                anchor = { latestClip },
                onEnd = trims.end
            )
        }
        if (selected && tailVisible) {
            TrimHandle(accent, handleWidth, Alignment.CenterEnd, window,
                onStart = { trims.begin(clip.id, false, latestClip) },
                onTravel = { anchor, travel -> trims.clip(anchor, false, travel) },
                anchor = { latestClip },
                onEnd = trims.end
            )
        }
    }
}

/** One key's diamond at [atMs] into the clip, if it is on the drawn part; a tap on it parks the playhead there. */
@Composable
private fun BoxScope.KeyDiamond(
    clip: Clip,
    atMs: Long,
    colour: Color,
    size: Dp,
    drawnStartMs: Long,
    drawnEndMs: Long,
    window: TimelineWindow,
    latestClip: Clip,
    onKeyTap: (String, Long) -> Unit
) {
    val atTimeline = clip.timelineStartMs + atMs
    if (atTimeline < drawnStartMs || atTimeline > drawnEndMs) return
    val x = window.widthDp(atTimeline - drawnStartMs).dp
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .offset(x = x - KEY_TARGET / 2)
            .size(KEY_TARGET)
            .pointerInput(clip.id, atMs) {
                detectTapGestures { onKeyTap(clip.id, latestClip.timelineStartMs + atMs) }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 3.dp)
                .size(size)
                .rotate(45f)
                .background(colour)
        )
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
    liftedId: String?,
    alsoSelected: Set<String> = emptySet()
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
                EffectBar(effect, effect.id == selectedId || effect.id in alsoSelected, row.folded, effect.id == liftedId, window, onSelect, trims)
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
                onStart = { trims.begin(effect.id, true, null) },
                onTravel = { anchor, travel -> trims.effect(anchor, true, travel) },
                anchor = { latestEffect },
                onEnd = trims.end
            )
        }
        if (selected && tailVisible) {
            TrimHandle(effect.color, grip, Alignment.CenterEnd, window,
                onStart = { trims.begin(effect.id, false, null) },
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
    // A handle taken off the screen mid-drag - its clip scrolled out of the drawn
    // window, or its row folded away - has its gesture cancelled without
    // onDragCancel running, and the strip went on believing a trim was under way:
    // playback held, the grey stretch and the snap line left drawn.
    val dragging = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (dragging[0]) {
                dragging[0] = false
                latestEnd()
            }
        }
    }
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
                        dragging[0] = true
                        latestStart()
                    },
                    onDragEnd = { from = null; dragging[0] = false; latestEnd() },
                    onDragCancel = { from = null; dragging[0] = false; latestEnd() }
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
