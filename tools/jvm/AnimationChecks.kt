import com.squish.app.timeline.AnimFrame
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipAnimation
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.KEY_TOLERANCE_MS
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.animated
import com.squish.app.timeline.hasValueKeyAt
import com.squish.app.timeline.shiftedBy
import com.squish.app.timeline.upserted
import com.squish.app.timeline.valueAt
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.withValueAt
import com.squish.app.timeline.withValueKeyAdded
import com.squish.app.timeline.withValueKeyRemoved
import com.squish.app.timeline.withValueKeysCleared
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * The animation sheet's arithmetic, executed: a number keyed over a clip, the
 * arrival / leaving / loop laid over the keyframes, and both surviving a cut
 * and a trim the way the placement keys do.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float, tol: Float = 1e-3f) = abs(a - b) <= tol

fun overlay(id: String, start: Long, lengthMs: Long, layer: Int = 1) = Clip(
    id = id, kind = ClipKind.Video, label = id,
    sourceInMs = 0L, sourceOutMs = lengthMs, timelineStartMs = start,
    sourceDurationMs = lengthMs + 10_000L, layer = layer
)

fun main() {
    // --- A value track: holds outside its keys, eases between them. ------------
    run {
        val keys = listOf(ValueKey(1000L, 0f, KeyframeEasing.Linear), ValueKey(2000L, 1f))
        check(emptyList<ValueKey>().valueAt(500L, 0.4f) == 0.4f, "no keys did not fall back")
        check(keys.valueAt(0L, 0.4f) == 0f, "before the first key the value did not hold it")
        check(keys.valueAt(5000L, 0.4f) == 1f, "after the last key the value did not hold it")
        check(near(keys.valueAt(1500L, 0f), 0.5f), "linear midpoint is ${keys.valueAt(1500L, 0f)}")
        val smooth = listOf(ValueKey(0L, 0f, KeyframeEasing.Smooth), ValueKey(1000L, 1f))
        check(near(smooth.valueAt(500L, 0f), 0.5f), "smooth midpoint is ${smooth.valueAt(500L, 0f)}")
        check(smooth.valueAt(250L, 0f) < 0.25f, "smooth easing did not ease in")
        val hold = listOf(ValueKey(0L, 0.2f, KeyframeEasing.Hold), ValueKey(1000L, 1f))
        check(hold.valueAt(999L, 0f) == 0.2f && hold.valueAt(1000L, 0f) == 1f, "hold did not step")
        check(listOf(ValueKey(300L, 0.7f)).valueAt(0L, 0f) == 0.7f, "one key is not a constant")

        val shifted = keys.shiftedBy(-1500L)
        check(shifted.map { it.atMs } == listOf(-500L, 500L), "shift dropped or moved keys wrongly: $shifted")
        val up = keys.upserted(ValueKey(1010L, 0.3f), KEY_TOLERANCE_MS)
        check(up.size == 2 && up.first().value == 0.3f, "a key a frame from another did not replace it: $up")
        check(keys.upserted(ValueKey(1500L, 0.3f), KEY_TOLERANCE_MS).size == 3, "a new key between two was not added")
    }

    // --- The arrival, leaving and loop. -----------------------------------------
    run {
        fun at(arr: ClipArrival = ClipArrival.None, lv: ClipLeaving = ClipLeaving.None, loop: ClipLoop = ClipLoop.None,
               elapsed: Long, total: Long = 4000L, inMs: Long = 500L, outMs: Long = 500L, loopMs: Long = 1000L) =
            ClipAnimation.frameAt(arr, lv, loop, inMs, outMs, loopMs, elapsed, total)

        check(at(elapsed = 100L) == AnimFrame.STILL, "nothing set is not still")
        check(at(ClipArrival.Fade, elapsed = 0L).alpha == 0f, "a fade in does not start at nothing")
        check(at(ClipArrival.Fade, elapsed = 500L).alpha == 1f, "a fade in has not finished at its length")
        check(at(ClipArrival.Fade, elapsed = 2000L).isStill, "a fade in is still doing something in the middle")
        check(at(lv = ClipLeaving.Fade, elapsed = 4000L).alpha == 0f, "a fade out does not end at nothing")
        check(at(lv = ClipLeaving.Fade, elapsed = 3500L).alpha == 1f, "a fade out starts early")
        check(at(lv = ClipLeaving.Fade, elapsed = 3750L).alpha in 0.01f..0.99f, "a fade out is not fading half way through")

        for (arr in ClipArrival.entries.filter { it != ClipArrival.None }) {
            val end = at(arr, elapsed = 500L)
            check(end.isStill, "$arr has not landed at the end of its arrival: $end")
            val start = at(arr, elapsed = 0L)
            check(!start.isStill, "$arr does nothing at its start")
        }
        for (lv in ClipLeaving.entries.filter { it != ClipLeaving.None }) {
            check(at(lv = lv, elapsed = 3500L).isStill, "$lv has already begun with the leaving still to come")
            check(!at(lv = lv, elapsed = 4000L).isStill, "$lv does nothing at the very end")
        }
        // A slide starts far enough out that a full-frame picture has cleared the canvas.
        check(at(ClipArrival.SlideLeft, elapsed = 0L).dx >= 2f, "slide left starts on screen")
        check(at(ClipArrival.SlideUp, elapsed = 0L).dy >= 2f, "slide up starts on screen")
        check(at(lv = ClipLeaving.SlideLeft, elapsed = 4000L).dx <= -2f, "slide left leaves on screen")
        // Zoom grows into place, Shrink settles from larger.
        check(at(ClipArrival.Zoom, elapsed = 0L).scale < 1f && at(ClipArrival.Shrink, elapsed = 0L).scale > 1f, "zoom directions")

        // In and out are each cut back to half the clip, whatever the sliders say.
        val short = ClipAnimation.frameAt(ClipArrival.Fade, ClipLeaving.Fade, ClipLoop.None, 3000L, 3000L, 1000L, 1000L, 2000L)
        check(short.alpha == 1f, "on a 2 s clip the 3 s fades overlap at the middle: $short")
        // A loop is periodic and never leaves the clip at rest for its whole life.
        val a = at(loop = ClipLoop.Pulse, elapsed = 250L)
        val b = at(loop = ClipLoop.Pulse, elapsed = 1250L)
        check(near(a.scale, b.scale), "pulse is not periodic: ${a.scale} vs ${b.scale}")
        check(a.scale != 1f, "pulse does nothing a quarter way through its period")
        check(at(loop = ClipLoop.Flicker, elapsed = 250L).alpha > 0.5f, "flicker goes to black")
        check(at(loop = ClipLoop.Swing, elapsed = 250L).tilt != 0f, "swing does not turn")
        check(at(loop = ClipLoop.Drift, elapsed = 0L).isStill, "drift does not start from rest")

        // Laid over a placement: multiplied scale, added offsets and tilt.
        val placed = Transform(scale = 0.5f, offsetXFraction = 0.4f, rotationDegrees = 10f)
        val over = placed.animated(AnimFrame(scale = 2f, dx = 0.1f, dy = -0.2f, tilt = 5f))
        check(near(over.scale, 1f) && near(over.offsetXFraction, 0.5f) && near(over.offsetYFraction, -0.2f) && near(over.rotationDegrees, 15f),
            "animation over a placement came out $over")
        check(placed.animated(AnimFrame.STILL) === placed, "a still frame allocated a new transform")
    }

    // --- On a clip: drawn with the keys, faded with the opacity track. ---------
    run {
        val clip = overlay("o", 1000L, 4000L).copy(
            keyframes = listOf(Keyframe(0L, Transform(scale = 0.5f), KeyframeEasing.Linear), Keyframe(4000L, Transform(scale = 1f), KeyframeEasing.Linear)),
            arrival = ClipArrival.SlideLeft, arrivalMs = 1000L, opacity = 0.8f,
            opacityKeys = listOf(ValueKey(2000L, 0.8f, KeyframeEasing.Linear), ValueKey(4000L, 0f, KeyframeEasing.Linear))
        )
        val start = clip.transformAt(1000L)
        check(near(start.scale, 0.5f) && start.offsetXFraction >= 2f, "arrival not laid over the first key: $start")
        val mid = clip.transformAt(3000L)
        check(near(mid.scale, 0.75f) && mid.offsetXFraction == 0f, "the move under a finished arrival: $mid")
        check(clip.placementAt(1000L).offsetXFraction == 0f, "the arrival leaked into the placement the sheet edits")
        check(clip.opacityAt(2000L) == 0.8f, "opacity before its keys is not the first key")
        check(near(clip.opacityAt(4000L), 0.4f), "opacity half way between keys is ${clip.opacityAt(4000L)}")
        check(clip.fadesPicture, "a keyed opacity is not seen as fading")
        check(!overlay("p", 0L, 1000L).fadesPicture, "a plain overlay fades")
        check(overlay("p", 0L, 1000L).copy(arrival = ClipArrival.Fade).fadesPicture, "a fade in is not seen as fading")
        check(!overlay("p", 0L, 1000L).copy(arrival = ClipArrival.SlideLeft).fadesPicture, "a slide is seen as fading")
        check(overlay("p", 0L, 1000L).copy(opacity = 0.6f).fadesPicture, "a 60% overlay is not seen as fading")
        // Volume: the track over the level.
        val sound = Clip(id = "s", kind = ClipKind.Audio, label = "s", sourceInMs = 0L, sourceOutMs = 4000L, timelineStartMs = 0L,
            sourceDurationMs = 60_000L, volume = 1f, volumeKeys = listOf(ValueKey(1000L, 1f, KeyframeEasing.Linear), ValueKey(2000L, 0.2f, KeyframeEasing.Linear)))
        check(near(sound.volumeAt(1500L), 0.6f), "ducked level half way is ${sound.volumeAt(1500L)}")
        check(sound.volumeAt(3000L) == 0.2f, "level after the duck did not hold")
    }

    // --- Editing the tracks: the slider keys once there are keys. --------------
    run {
        val clip = overlay("o", 1000L, 4000L).copy(opacity = 1f)
        val slid = clip.withValueAt(ValueTrack.Opacity, 2000L, 0.5f, 0f..1f)
        check(slid.opacity == 0.5f && slid.opacityKeys.isEmpty(), "with no keys the slider did not set the one level")
        val keyed = slid.withValueKeyAdded(ValueTrack.Opacity, 2000L)
        check(keyed.opacityKeys == listOf(ValueKey(1000L, 0.5f)), "the first key did not pin the level: ${keyed.opacityKeys}")
        check(keyed.hasValueKeyAt(ValueTrack.Opacity, 2000L), "the key is not seen under the playhead")
        val second = keyed.withValueAt(ValueTrack.Opacity, 4000L, 0f, 0f..1f)
        check(second.opacityKeys.size == 2 && second.opacityKeys.last() == ValueKey(3000L, 0f), "the slider on a keyed clip did not key: ${second.opacityKeys}")
        check(near(second.opacityAt(3000L), 0.25f), "the keyed fade is not half way at 3 s: ${second.opacityAt(3000L)}")
        // Off the clip, the whole track moves.
        val off = second.withValueAt(ValueTrack.Opacity, 9000L, 0.2f, 0f..1f)
        check(off.opacityKeys.map { it.value } == listOf(0.7f, 0.2f), "off the clip the track did not shift by the change: ${off.opacityKeys}")
        // Removing the last key leaves the level where it was.
        val one = second.withValueKeyRemoved(ValueTrack.Opacity, 2000L)
        check(one.opacityKeys.size == 1, "removing a key removed ${2 - one.opacityKeys.size}")
        val none = one.withValueKeyRemoved(ValueTrack.Opacity, 4000L)
        check(none.opacityKeys.isEmpty() && none.opacity == 0f, "the last key did not leave its level behind: ${none.opacity}")
        val cleared = second.withValueKeysCleared(ValueTrack.Opacity)
        check(cleared.opacityKeys.isEmpty() && cleared.opacity == 0.5f, "clearing did not settle on the first frame's level: ${cleared.opacity}")
        // Through the placement path, as the Opacity sheet writes it.
        val state = TimelineState(clips = listOf(keyed), playheadMs = 4000L)
        val viaGeometry = state.withOverlayGeometry("o", opacity = 0.1f).clips.first()
        check(viaGeometry.opacityKeys.size == 2, "the sheet's slider did not key through withOverlayGeometry")
        val range = 0f..4f
        val loud = clip.withValueAt(ValueTrack.Volume, 2000L, 3f, range)
        check(loud.volume == 3f, "a sound's level above one was clamped")
    }

    // --- A cut and a trim carry the tracks and split the arrival from the leaving.
    run {
        val clip = overlay("o", 0L, 4000L).copy(
            arrival = ClipArrival.Fade, leaving = ClipLeaving.Fade,
            opacityKeys = listOf(ValueKey(1000L, 1f, KeyframeEasing.Linear), ValueKey(3000L, 0f, KeyframeEasing.Linear)),
            volumeKeys = listOf(ValueKey(500L, 1f), ValueKey(3500L, 0.2f))
        )
        val cut = TimelineState(clips = listOf(clip), playheadMs = 2000L).withSplitAtPlayhead("o")
        val head = cut.clips.first { it.id == "o" }
        val tail = cut.clips.first { it.id != "o" }
        check(head.arrival == ClipArrival.Fade && head.leaving == ClipLeaving.None, "the head kept the leaving")
        check(tail.arrival == ClipArrival.None && tail.leaving == ClipLeaving.Fade, "the tail kept the arrival")
        check(tail.opacityKeys.map { it.atMs } == listOf(-1000L, 1000L), "the tail's opacity keys did not move with the cut: ${tail.opacityKeys}")
        check(tail.volumeKeys.map { it.atMs } == listOf(-1500L, 1500L), "the tail's volume keys did not move: ${tail.volumeKeys}")
        // The same picture at the same moment on either side of the cut.
        for (t in listOf(500L, 1500L, 2500L, 3500L)) {
            val whole = clip.opacityKeys.valueAt(t, clip.opacity)
            val half = if (t < 2000L) head.opacityKeys.valueAt(t, head.opacity) else tail.opacityKeys.valueAt(t - 2000L, tail.opacity)
            check(near(whole, half), "opacity at $t differs across the cut: $whole vs $half")
        }
        val trimmed = TimelineState(clips = listOf(clip)).withClipTrimmed("o", 1000L, 0L).clips.first()
        check(trimmed.timelineStartMs == 1000L, "trim did not move the overlay's start")
        check(trimmed.opacityKeys.map { it.atMs } == listOf(0L, 2000L), "a head trim did not carry the opacity keys: ${trimmed.opacityKeys}")
        check(near(trimmed.opacityAt(2000L), clip.opacityAt(2000L)), "the fade moved with the trim")
    }

    // --- Speed: a clip at half speed animates on played time, like its keys. ---
    run {
        val slow = overlay("o", 0L, 2000L).copy(speedRamp = SpeedRamp.flat(0.5f), arrival = ClipArrival.Fade, arrivalMs = 1000L)
        check(slow.durationMs == 4000L, "test clip plays ${slow.durationMs}")
        check(slow.opacityAt(500L) < 1f && slow.opacityAt(1000L) == 1f, "the arrival did not run on played time")
    }

    println()
    if (problems.isEmpty()) println("PASS - value tracks, arrivals, leavings and loops behave, through cuts and trims")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
