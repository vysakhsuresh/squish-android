import android.net.Uri
import com.squish.app.media.video.BlendFrameRules
import com.squish.app.media.video.BlendFrameRules.Step
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.LayerBlend
import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F6: a blended video overlay decoded by hand, frame by frame, into
 * the blend shader. BlendFrameRules decides which frame is under a moment and
 * whether to hold, decode on or seek; here a file of frames is simulated and
 * walked the way an export and a preview walk it.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** A decoder over frames every [frameMs] from 0 to [lengthMs], counting what it does. */
private class FakeDecoder(val frameMs: Long, val lengthMs: Long, val keyEveryMs: Long = 1_000L) {
    var held: Long? = null
    var decoded = 0
    var seeks = 0
    private var next = 0L

    fun frameFor(wanted: Long): Long {
        when (BlendFrameRules.step(held, wanted, frameMs)) {
            Step.Hold -> return held!!
            Step.Seek -> {
                seeks++
                next = ((wanted / keyEveryMs) * keyEveryMs / frameMs) * frameMs
                held = null
            }
            Step.Decode -> {}
        }
        while (true) {
            if (next > lengthMs) return held ?: lengthMs
            val pts = next
            next += frameMs
            decoded++
            held = pts
            if (BlendFrameRules.takes(pts, wanted, frameMs)) return pts
        }
    }
}

/** The frame a player has on screen at [wanted]: the last at or before it. */
private fun onScreen(wanted: Long, frameMs: Long): Long = (wanted / frameMs) * frameMs

private fun overlay(start: Long, srcIn: Long, span: Long, ramp: SpeedRamp = SpeedRamp()) = Clip(
    id = "o", kind = ClipKind.Video, uri = Uri.parse("content://o"), label = "o", sourceInMs = srcIn,
    sourceOutMs = srcIn + span, timelineStartMs = start, sourceDurationMs = 60_000, speedRamp = ramp, layer = 1,
    blend = LayerBlend.Screen
)

fun main() {
    check(BlendFrameRules.frameMs(30f) == 33L && BlendFrameRules.frameMs(0f) == BlendFrameRules.DEFAULT_FRAME_MS &&
        BlendFrameRules.frameMs(Float.NaN) == BlendFrameRules.DEFAULT_FRAME_MS, "frame lengths")

    // An export at the footage's rate: one decode a frame, one seek, every frame the right one.
    run {
        val d = FakeDecoder(frameMs = 40, lengthMs = 10_000)
        var wrong = 0
        for (i in 0 until 200) { val w = i * 40L; if (d.frameFor(w) != onScreen(w, 40)) wrong++ }
        check(wrong == 0, "25 fps over 25 fps: $wrong frames not the one on screen")
        check(d.seeks == 1 && d.decoded == 200, "25 fps over 25 fps: ${d.seeks} seeks, ${d.decoded} decodes for 200 frames")
    }
    // Twice the footage's rate: every frame held for two, nothing decoded twice.
    run {
        val d = FakeDecoder(frameMs = 40, lengthMs = 10_000)
        var wrong = 0
        for (i in 0 until 400) { val w = i * 20L; val f = d.frameFor(w); if (f > w || w - f >= 40) wrong++ }
        check(wrong == 0, "50 fps over 25 fps: $wrong frames not the one on screen")
        check(d.seeks == 1 && d.decoded <= 201, "50 fps over 25 fps: ${d.seeks} seeks, ${d.decoded} decodes")
    }
    // 30 fps asked of 25 fps footage, where the frame after the moment is often
    // the nearer one: the one on screen, never the next one along.
    run {
        val d = FakeDecoder(frameMs = 40, lengthMs = 10_000)
        var wrong = 0
        for (i in 0 until 300) { val w = i * 1000L / 30; if (d.frameFor(w) != onScreen(w, 40)) wrong++ }
        check(wrong == 0, "30 fps over 25 fps: $wrong frames not the one on screen")
    }
    // Half the rate: every other frame skipped by decoding, never by seeking.
    run {
        val d = FakeDecoder(frameMs = 20, lengthMs = 10_000)
        for (i in 0 until 200) d.frameFor(i * 40L)
        check(d.seeks == 1, "25 fps over 50 fps: ${d.seeks} seeks where decoding on would do")
    }
    // Jitter: a moment a few milliseconds behind the last holds, it does not seek.
    run {
        val d = FakeDecoder(frameMs = 33, lengthMs = 10_000)
        d.frameFor(1_000)
        val before = d.seeks
        val f = d.frameFor(995)
        check(d.seeks == before && f == d.held, "a 5 ms step back sought again")
    }
    // A scrub back seeks; a jump far ahead seeks; a short step ahead decodes.
    run {
        val d = FakeDecoder(frameMs = 33, lengthMs = 60_000)
        d.frameFor(5_000)
        check(BlendFrameRules.step(d.held, 2_000, 33) == Step.Seek, "a scrub back did not seek")
        check(BlendFrameRules.step(d.held, 20_000, 33) == Step.Seek, "a jump 15 s ahead decoded through")
        check(BlendFrameRules.step(d.held, 5_300, 33) == Step.Decode, "300 ms ahead sought rather than decoded")
        val at = d.frameFor(2_000)
        check(at == onScreen(2_000, 33), "after a scrub back the frame is $at, not ${onScreen(2_000, 33)}")
    }
    // The frame on screen past the end of the file is the last one, not nothing.
    run {
        val d = FakeDecoder(frameMs = 40, lengthMs = 1_000)
        d.frameFor(900)
        check(d.frameFor(1_400) == 1_000L, "past the end the frame was not the last")
    }

    // The overlay's own moment: through its trim and speed, null off its stretch.
    val plain = overlay(start = 2_000, srcIn = 500, span = 4_000)
    check(BlendFrameRules.sourceMsAt(plain, 1_999) == null && BlendFrameRules.sourceMsAt(plain, 6_000) == null,
        "an overlay read outside its stretch")
    check(BlendFrameRules.sourceMsAt(plain, 2_000) == 500L && BlendFrameRules.sourceMsAt(plain, 3_000) == 1_500L,
        "a trimmed overlay's moments: ${BlendFrameRules.sourceMsAt(plain, 3_000)}")
    val fast = overlay(start = 0, srcIn = 0, span = 8_000, ramp = SpeedRamp.flat(2f))
    check(BlendFrameRules.sourceMsAt(fast, 1_000) == 2_000L, "a 2x overlay at 1 s read ${BlendFrameRules.sourceMsAt(fast, 1_000)}, not 2000")

    // Only a video overlay with a mode is blended as a video.
    check(BlendFrameRules.blendsAsVideo(plain, isStill = false), "a blended video overlay not taken")
    check(!BlendFrameRules.blendsAsVideo(plain, isStill = true), "a still taken as a video")
    check(!BlendFrameRules.blendsAsVideo(plain.copy(blend = LayerBlend.Normal), isStill = false), "a plain overlay taken")
    check(!BlendFrameRules.blendsAsVideo(plain.copy(layer = 0), isStill = false), "a main-track shot taken")

    if (problems.isEmpty()) println("BlendFrameChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
