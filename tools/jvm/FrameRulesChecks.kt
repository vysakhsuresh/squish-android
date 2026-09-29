import android.net.Uri
import com.squish.app.editor.FrameRules
import com.squish.app.editor.OutputSize
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import kotlin.math.abs
import kotlin.system.exitProcess

// The edit's frame (B12), executed: the canvas a padded frame is written at,
// where the picture sits on it, and which shot's track auto-reframe reads at
// a moment - each shot's own, in its own file time.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

fun main() {
    // --- The padded canvas: the short edge kept, at the chosen shape, even. ------
    run {
        val portrait = FrameRules.paddedCanvas(OutputSize.ORIGINAL, 1920, 1080, 9f / 16f)
        check(portrait.width == 1080 && portrait.height == 1920, "a landscape 1080p shot on 9:16 is not 1080x1920: $portrait")
        val square = FrameRules.paddedCanvas(720, 1920, 1080, 1f)
        check(square.width == 720 && square.height == 720, "720p square is not 720x720: $square")
        val wide = FrameRules.paddedCanvas(OutputSize.ORIGINAL, 1080, 1920, 16f / 9f)
        check(wide.width == 1920 && wide.height == 1080, "a portrait shot on 16:9 is not 1920x1080: $wide")
        val scope = FrameRules.paddedCanvas(1080, 1920, 1080, 2.35f)
        check(scope.height == 1080 && scope.width % 2 == 0 && abs(scope.width - 1080 * 2.35f) <= 2f, "2.35:1 at 1080 is $scope")
        val four = FrameRules.paddedCanvas(OutputSize.ORIGINAL, 1920, 1080, 4f / 5f)
        check(four.width % 2 == 0 && four.height % 2 == 0 && four.width == 1080, "4:5 is $four")
        check(FrameRules.paddedCanvas(OutputSize.ORIGINAL, 0, 0, 1f).width == 0, "an unmeasured source made a canvas")
    }

    // --- Where the picture sits: fitted whole, centred. ---------------------------
    run {
        val onPortrait = FrameRules.fittedFrame(9f / 16f, 16f / 9f)
        check(near(onPortrait.left, 0f) && near(onPortrait.right, 1f), "a landscape picture on 9:16 is not full width: $onPortrait")
        check(near(onPortrait.height, (9f / 16f) / (16f / 9f)) && near(onPortrait.top + onPortrait.bottom, 1f), "it is not centred at its height: $onPortrait")
        val onLandscape = FrameRules.fittedFrame(16f / 9f, 9f / 16f)
        check(near(onLandscape.top, 0f) && near(onLandscape.bottom, 1f) && near(onLandscape.width, (9f / 16f) / (16f / 9f)), "a portrait picture on 16:9: $onLandscape")
        val same = FrameRules.fittedFrame(1f, 1f)
        check(near(same.width, 1f) && near(same.height, 1f), "the same shape is not the whole canvas")
        check(FrameRules.fittedFrame(0f, 1f).width == 1f, "a bad canvas did not fall back to everything")
    }

    // --- Auto-reframe reads the shot under the playhead, in its own file time. -----
    run {
        fun track(vararg at: Pair<Long, Float>) = MotionTrack(at.map { (ms, x) -> TrackSample(ms, x, 0.5f) })
        val a = Clip(
            id = "a", kind = ClipKind.Video, uri = Uri.parse("a"), label = "A",
            sourceInMs = 1_000L, sourceOutMs = 4_000L, timelineStartMs = 0L, sourceDurationMs = 10_000L,
            reframe = track(1_000L to 0.2f, 4_000L to 0.2f)
        )
        val b = Clip(
            id = "b", kind = ClipKind.Video, uri = Uri.parse("b"), label = "B",
            sourceInMs = 0L, sourceOutMs = 2_000L, timelineStartMs = 3_000L, sourceDurationMs = 2_000L,
            reframe = track(0L to 0.8f, 2_000L to 0.9f)
        )
        val plain = Clip(
            id = "c", kind = ClipKind.Video, uri = Uri.parse("c"), label = "C",
            sourceInMs = 0L, sourceOutMs = 1_000L, timelineStartMs = 6_000L, sourceDurationMs = 1_000L
        )
        val clips = listOf(a, b, plain)
        check(near(FrameRules.reframeFocus(clips, 1_000L)!!.first, 0.2f), "inside A the focus is not A's")
        // Inside B at 4 s: 1 s into B's file, where the track is between 0.8 and 0.9.
        val inB = FrameRules.reframeFocus(clips, 4_000L)!!.first
        check(inB > 0.8f && inB < 0.9f, "inside B the focus is $inB, not B's own")
        check(FrameRules.reframeFocus(clips, 6_500L) == null, "a shot with no track gave a focus")
        check(FrameRules.reframeFocus(clips, 5_500L) == null, "a gap gave a focus")
        // The very end reads the last shot; an overlay is not a shot.
        val overlay = a.copy(id = "o", layer = 1, timelineStartMs = 5_000L)
        check(FrameRules.reframeFocus(listOf(a, b, overlay), 5_000L)!!.first > 0.8f, "the end of the picture did not read the last shot")
        check(FrameRules.reframeFocus(listOf(overlay), 5_500L) == null, "an overlay's track was read as the picture's")
        check(FrameRules.reframeFocus(emptyList(), 0L) == null, "no shots gave a focus")
    }

    println("frame rules: padded canvas, fitted picture, reframe per shot")
    if (problems.isEmpty()) println("PASS - the padded frame is sized and laid out as the export writes it, and each shot follows its own subject")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
