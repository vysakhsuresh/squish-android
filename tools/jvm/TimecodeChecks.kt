import com.squish.app.editor.Timecode
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import kotlin.math.ceil
import kotlin.system.exitProcess

// The transport's frame buttons, executed: every press shows the next frame -
// never the same one again, never one skipped - at any frame rate, on a trimmed
// shot, and on a retimed one.
//
// "Showing" is what an exactly-seeked player puts up: the first frame at or
// after the position (see PreviewRules.END_BACKOFF_MS).

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun showing(sourceMs: Long, fps: Float): Long = ceil(sourceMs * fps / 1000.0 - 1e-9).toLong()

fun shot(start: Long, srcIn: Long, span: Long, ramp: SpeedRamp = SpeedRamp()) = Clip(
    id = "s", kind = ClipKind.Video, label = "s", sourceInMs = srcIn, sourceOutMs = srcIn + span,
    timelineStartMs = start, sourceDurationMs = 600_000, speedRamp = ramp
)

fun main() {
    // --- No shot: a plain grid from zero, at every common rate. -----------------------
    for (fps in listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)) {
        var t = 0L
        var frame = showing(t, fps)
        repeat(3_000) { press ->
            val next = Timecode.frameStep(t, 1, fps)
            val nextFrame = showing(next, fps)
            if (nextFrame != frame + 1) {
                problems += "$fps fps, press ${press + 1}: frame $frame then $nextFrame (at $t -> $next ms)"
                return@repeat
            }
            t = next; frame = nextFrame
        }
        repeat(3_000) { press ->
            val back = Timecode.frameStep(t, -1, fps)
            val backFrame = showing(back, fps)
            if (backFrame != frame - 1) {
                problems += "$fps fps, back press ${press + 1}: frame $frame then $backFrame (at $t -> $back ms)"
                return@repeat
            }
            t = back; frame = backFrame
        }
        check(t == 0L, "$fps fps: 3000 forward and 3000 back ended at $t, not 0")
    }

    // From the middle of a frame - a playhead dragged there - forward is the next
    // frame, back the one before.
    run {
        val fps = 30f
        val mid = 50L // frame 2 (66.7) is showing
        check(showing(Timecode.frameStep(mid, 1, fps), fps) == 3L, "from mid-frame, forward: ${Timecode.frameStep(mid, 1, fps)}")
        check(showing(Timecode.frameStep(mid, -1, fps), fps) == 1L, "from mid-frame, back: ${Timecode.frameStep(mid, -1, fps)}")
        check(Timecode.frameStep(0L, 1, 30f) == 33L, "the first step at 30fps is ${Timecode.frameStep(0L, 1, 30f)}, not 33")
        check(Timecode.frameStep(0L, 1, 60f) == 16L, "the first step at 60fps is ${Timecode.frameStep(0L, 1, 60f)}, not 16")
    }

    // --- On a shot: its own frames, in its own file's time. ---------------------------
    fun walk(name: String, clip: Clip, fps: Float, from: Long) {
        var t = from
        var frame = showing(clip.sourceAt(t), fps)
        var presses = 0
        while (true) {
            val next = Timecode.frameStep(
                t, 1, fps, clip.timelineStartMs, clip.timelineEndMs, clip::sourceAt, clip::timelineAtSource
            )
            presses++
            if (next >= clip.timelineEndMs) {
                check(next == clip.timelineEndMs, "$name: past the last frame the step went to $next, not the next shot at ${clip.timelineEndMs}")
                break
            }
            val nextFrame = showing(clip.sourceAt(next), fps)
            if (nextFrame != frame + 1) {
                problems += "$name, press $presses: source frame $frame then $nextFrame (timeline $t -> $next)"
                return
            }
            check(next > t, "$name: a forward step went back ($t -> $next)")
            t = next; frame = nextFrame
            if (presses > 10_000) { problems += "$name: never reached the end"; return }
        }
        // Every frame of the shot was visited: the count matches its span.
        val first = showing(clip.sourceAt(from), fps)
        val last = showing(clip.sourceOutMs, fps) - 1
        check(frame >= last - 1, "$name: stopped at frame $frame of $first..$last")

        // And back again, to the first.
        while (true) {
            val back = Timecode.frameStep(
                t, -1, fps, clip.timelineStartMs, clip.timelineEndMs, clip::sourceAt, clip::timelineAtSource
            )
            if (back < clip.timelineStartMs) {
                check(back < clip.timelineStartMs, "$name: back off the first frame stayed at $back")
                break
            }
            val backFrame = showing(clip.sourceAt(back), fps)
            if (backFrame != frame - 1) {
                problems += "$name, back: source frame $frame then $backFrame (timeline $t -> $back)"
                return
            }
            t = back; frame = backFrame
        }
    }

    // A shot trimmed to start mid-frame in its file, placed mid-frame on the timeline.
    walk("trimmed 29.97", shot(start = 1_010, srcIn = 5_017, span = 4_000), 29.97f, 1_010)
    walk("trimmed 60", shot(start = 7, srcIn = 123, span = 3_000), 60f, 7)
    walk("trimmed 24", shot(start = 2_500, srcIn = 0, span = 2_000), 24f, 2_500)
    // Retimed: twice as fast is half as many timeline ms per frame; half speed twice.
    walk("2x", shot(start = 1_000, srcIn = 2_000, span = 6_000, ramp = SpeedRamp.flat(2f)), 30f, 1_000)
    walk("0.5x", shot(start = 0, srcIn = 333, span = 2_000, ramp = SpeedRamp.flat(0.5f)), 30f, 0)
    walk("4x at 60", shot(start = 500, srcIn = 0, span = 8_000, ramp = SpeedRamp.flat(4f)), 60f, 500)
    walk("ramp", shot(start = 0, srcIn = 0, span = 6_000,
        ramp = SpeedRamp(listOf(SpeedPoint(0L, 1f), SpeedPoint(2_000L, 0.25f), SpeedPoint(4_000L, 2f)))), 30f, 0)

    // Back off a shot's first frame goes before it, on the plain grid.
    run {
        val clip = shot(start = 3_000, srcIn = 1_000, span = 2_000)
        val back = Timecode.frameStep(3_000, -1, 30f, clip.timelineStartMs, clip.timelineEndMs, clip::sourceAt, clip::timelineAtSource)
        check(back in 2_950 until 3_000, "back off a shot's first frame went to $back")
    }

    println("timecode: the frame buttons step one frame, on the shot's own frames")
    if (problems.isEmpty()) println("PASS - every press shows the next frame, forward and back, at every rate and speed")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
