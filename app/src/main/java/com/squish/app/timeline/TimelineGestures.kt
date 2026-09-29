package com.squish.app.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged

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
 * clips and the handles. Two fingers are unambiguous, and only then are the
 * events taken, so the scroll underneath does not pan while the zoom happens.
 *
 * [onZoom] receives a ratio - above one for spreading, below for pinching in.
 */
internal suspend fun PointerInputScope.detectPinch(guard: MultiTouchGuard, onZoom: (Float) -> Unit) {
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
 * How far two fingers must change their spread before it counts as a pinch.
 *
 * Fingers resting on glass are never quite still, and a detector that acted on
 * every pixel of that made the strip shiver in place.
 */
private const val PINCH_SLOP_PX = 12f

/**
 * Whether a two-finger gesture is on the strip, or only just left it.
 *
 * A pinch starts and ends with one finger, and the tap detectors on the lanes and
 * ruler saw that finger lift and took it for a tap - at the new zoom, which when
 * zoomed out is usually past the end of the edit. The playhead leapt to the end,
 * the picture went to "gap", and a pinch made during playback stopped it dead.
 * Every tap, lift and trim on the strip now asks this first.
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
 * Press and hold, then drag: how a clip is picked up off the strip.
 *
 * On the strip a plain drag scrolls - which, with the playhead fixed in the
 * middle, is scrubbing - so moving a clip needs a gesture of its own, and a
 * long press is the one every phone editor uses. It runs once for the whole
 * strip rather than on each clip, for two reasons. A clip dragged towards the
 * edge scrolls the strip, and a clip that scrolled out of the drawn window
 * stopped being composed and took its gesture with it, dropping the clip
 * mid-air. And only the strip knows where its rows are, so only it can say
 * which row a clip is being carried to.
 *
 * Until the press has been held long enough, nothing is consumed: a finger that
 * moves first is a scroll (the strip's or the rows'), one that lifts is a tap
 * (the clip's own), a second finger is a pinch, and a trim handle that starts
 * dragging takes the gesture for itself - each of those ends the wait. Once
 * lifted, every event is taken on the way in, so neither scroll underneath moves
 * while a clip is being carried.
 *
 * [canLift] says whether anything liftable is under the press, in this node's
 * coordinates; [onMove] and [onDrop] get the finger in the same coordinates.
 */
internal suspend fun PointerInputScope.detectLift(
    guard: MultiTouchGuard,
    canLift: (Offset) -> Boolean,
    onLift: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit
) {
    awaitEachGesture {
        // Taps below consume the down, and it is still the start of a press here.
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull true
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
                // Anything further out - the strip's own scroll - claims its drag
                // on the main pass after this one; the final pass is where to see it.
                val settled = awaitPointerEvent(PointerEventPass.Final)
                if (settled.changes.any { it.isConsumed }) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
        if (ended != null) return@awaitEachGesture
        if (guard.blocking || !canLift(down.position)) return@awaitEachGesture

        onLift(down.position)
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed) break
                onMove(change.position)
            }
            finished = true
            onDrop()
        } finally {
            // Cancelled - the strip folded away, or the editor left - with the clip
            // still in the air: put it back where it was rather than drop it.
            if (!finished) onCancel()
        }
    }
}
