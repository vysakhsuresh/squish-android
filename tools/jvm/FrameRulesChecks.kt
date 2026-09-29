import android.net.Uri
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRect
import com.squish.app.editor.CropRules
import com.squish.app.editor.FrameRules
import com.squish.app.editor.OutputSize
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

        // A shot sliding in follows its subject in, as the file's window does
        // (ExportPlan.motionAt User carries the arrival): not the resting spot.
        val sliding = a.copy(arrival = com.squish.app.timeline.ClipArrival.SlideLeft, arrivalMs = 1_000L)
        val early = FrameRules.reframeFocus(listOf(sliding), 100L)!!.first
        val rest = FrameRules.reframeFocus(listOf(sliding), 2_500L)!!.first
        check(near(rest, 0.2f), "at rest the sliding shot's focus is $rest")
        check(!near(early, 0.2f), "mid-arrival the focus stayed on the resting subject: $early")
        val expected = FrameRules.subjectOnCanvas(0.2f, 0.5f, null, null, 0, sliding.placedAt(100L), 0f)
        check(expected != null && near(early, expected.first), "mid-arrival the focus $early is not the animated subject's ${expected?.first}")
    }

    // --- A shot's subject carried to the canvas: through its crop, the edit's
    //     turn, the fit and its placement - the walk both the preview and the
    //     file make, so the reframe window lands on the face in both. ------------
    run {
        val wide = 16f / 9f
        // Nothing done to the shot: the point is where it was.
        val same = FrameRules.subjectOnCanvas(0.3f, 0.7f, null, wide, 0, Transform.Identity, wide)
        check(near(same.first, 0.3f) && near(same.second, 0.7f), "an untouched shot moved its subject: $same")
        // Nothing measured reads as the canvas's own shape.
        val guessed = FrameRules.subjectOnCanvas(0.3f, 0.7f, null, null, 0, Transform.Identity, wide)
        check(near(guessed.first, 0.3f) && near(guessed.second, 0.7f), "an unmeasured shot moved its subject: $guessed")

        // The clip's own window: the left half kept, fitted to the canvas.
        // The window is 8:9; on a 16:9 canvas it is fitted to the height, in the middle.
        val half = ClipCrop(rect = CropRect.of(0f, 0f, 0.5f, 1f))
        val inHalf = FrameRules.subjectOnCanvas(0.25f, 0.5f, half, wide, 0, Transform.Identity, wide)
        check(near(inHalf.first, 0.5f) && near(inHalf.second, 0.5f), "the middle of a kept half is not the middle of the canvas: $inHalf")
        val leftEdge = FrameRules.subjectOnCanvas(0f, 0f, half, wide, 0, Transform.Identity, wide)
        check(near(leftEdge.first, 0.25f) && near(leftEdge.second, 0f), "the corner of a kept half is not at the fitted window's corner: $leftEdge")
        // A point the window cuts away lands outside the canvas.
        val cutAway = FrameRules.subjectOnCanvas(0.9f, 0.5f, half, wide, 0, Transform.Identity, wide)
        check(cutAway.first > 1f, "a subject cropped out still landed on the canvas: $cutAway")

        // A quarter turn: the right edge goes to the top, as Media3 turns it.
        val turned = FrameRules.subjectOnCanvas(1f, 0.5f, null, wide, 90, Transform.Identity, 9f / 16f)
        check(near(turned.first, 0.5f) && near(turned.second, 0f), "a quarter turn did not put the right edge at the top: $turned")
        val turnedCorner = FrameRules.subjectOnCanvas(1f, 0f, null, wide, 90, Transform.Identity, 9f / 16f)
        check(near(turnedCorner.first, 0f) && near(turnedCorner.second, 0f), "a quarter turn did not put the top right at the top left: $turnedCorner")

        // A padded canvas: the shot sits in the middle band; its corner is the band's corner.
        val band = FrameRules.fittedFrame(9f / 16f, wide)
        val padded = FrameRules.subjectOnCanvas(0f, 0f, null, wide, 0, Transform.Identity, 9f / 16f)
        check(near(padded.first, 0f) && near(padded.second, band.top), "on a padded canvas the corner is not the band's: $padded vs $band")

        // Placement: scaled about the middle, moved by fractions of half the canvas, turned clockwise.
        val bigger = FrameRules.subjectOnCanvas(0.75f, 0.5f, null, wide, 0, Transform(scale = 2f), wide)
        check(near(bigger.first, 1f) && near(bigger.second, 0.5f), "2x about the middle did not double the distance: $bigger")
        val moved = FrameRules.subjectOnCanvas(0.5f, 0.5f, null, wide, 0, Transform(offsetXFraction = 0.5f, offsetYFraction = -1f), wide)
        check(near(moved.first, 0.75f) && near(moved.second, 0f), "an offset of half the half-canvas did not land at 0.75: $moved")
        val clockwise = FrameRules.subjectOnCanvas(0.5f, 0f, null, wide, 0, Transform(rotationDegrees = 90f), wide)
        // The top of the picture, turned clockwise, is on the right - the same distance in square units.
        check(near(clockwise.second, 0.5f) && near(clockwise.first, 0.5f + 0.5f / wide), "a clockwise quarter turn did not put the top on the right: $clockwise")

        // All of it at once, against the same walk done by hand.
        val crop = ClipCrop(rect = CropRect.of(0.2f, 0.1f, 0.8f, 0.9f), straightenDegrees = 7f, flipHorizontal = true)
        val place = Transform(scale = 1.3f, offsetXFraction = 0.2f, offsetYFraction = -0.1f, rotationDegrees = 15f)
        val all = FrameRules.subjectOnCanvas(0.4f, 0.6f, crop, wide, 0, place, wide)
        val (wx, wy) = CropRules.windowPoint(0.4f, 0.6f, crop, wide)
        val wa = crop.rect.aspect(wide)
        val fit = FrameRules.fittedFrame(wide, wa)
        val fx = fit.left + wx * fit.width
        val fy = fit.top + wy * fit.height
        val r = Math.toRadians(15.0)
        val qx = (fx - 0.5f) * wide * 1.3f
        val qy = (fy - 0.5f) * 1.3f
        val ex = (qx * cos(r) - qy * sin(r)).toFloat() / wide + 0.5f + 0.1f
        val ey = (qx * sin(r) + qy * cos(r)).toFloat() + 0.5f - 0.05f
        check(near(all.first, ex) && near(all.second, ey), "the whole walk disagrees with itself: $all vs ($ex,$ey)")
    }

    // --- The focus reads the shot's crop and placement at the moment. ------------
    run {
        val wide = 16f / 9f
        val shot = Clip(
            id = "s", kind = ClipKind.Video, uri = Uri.parse("s"), label = "S",
            sourceInMs = 0L, sourceOutMs = 4_000L, timelineStartMs = 0L, sourceDurationMs = 4_000L,
            reframe = MotionTrack(listOf(TrackSample(0L, 0.25f, 0.5f), TrackSample(4_000L, 0.25f, 0.5f))),
            crop = ClipCrop(rect = CropRect.of(0f, 0f, 0.5f, 1f)),
            offsetXFraction = 0.5f
        )
        val focus = FrameRules.reframeFocus(listOf(shot), 1_000L, 0, wide) { wide }!!
        // The middle of the kept half sits in the middle of the canvas, then moves right by a quarter.
        check(near(focus.first, 0.75f) && near(focus.second, 0.5f), "the focus did not follow the crop and the placement: $focus")
        val raw = FrameRules.reframeFocus(listOf(shot.copy(crop = null, offsetXFraction = 0f)), 1_000L, 0, wide) { wide }!!
        check(near(raw.first, 0.25f), "an untouched shot's focus moved: $raw")
    }

    // --- The blurred backdrop: one still per shot on a coarse grid, shown from
    //     the shot's start to the next shot's, across a gap, from the very
    //     start and on to the end. ----------------------------------------------
    run {
        fun shot(id: String, start: Long, length: Long, sourceIn: Long = 0L) = Clip(
            id = id, kind = ClipKind.Video, uri = Uri.parse(id), label = id,
            sourceInMs = sourceIn, sourceOutMs = sourceIn + length, timelineStartMs = start, sourceDurationMs = 60_000L
        )
        val a = shot("a", 1_000L, 4_000L, sourceIn = 10_000L)
        val b = shot("b", 7_000L, 2_000L)
        val overlay = shot("o", 0L, 3_000L).copy(layer = 1)
        val clips = listOf(a, b, overlay)
        val stretches = FrameRules.backdropStretches(clips, 12_000L)
        check(stretches.size == 2, "an overlay got a backdrop stretch: $stretches")
        check(stretches[0].clip.id == "a" && stretches[0].startMs == 0L && stretches[0].endMs == 7_000L, "A's stretch is not 0..7000: ${stretches[0]}")
        check(stretches[1].clip.id == "b" && stretches[1].startMs == 7_000L && stretches[1].endMs == 12_000L, "B's stretch does not run to the end: ${stretches[1]}")
        check(FrameRules.backdropShot(clips, 500L, 12_000L)?.id == "a", "before the first shot the backdrop is not the first shot's")
        check(FrameRules.backdropShot(clips, 5_500L, 12_000L)?.id == "a", "over the gap the backdrop is not the shot before it")
        check(FrameRules.backdropShot(clips, 8_000L, 12_000L)?.id == "b", "inside B the backdrop is not B's")
        check(FrameRules.backdropShot(clips, 11_000L, 12_000L)?.id == "b", "past the picture the backdrop is not the last shot's")
        check(FrameRules.backdropShot(listOf(overlay), 1_000L, 3_000L) == null, "an overlay alone gave a backdrop")
        // The middle of A is 12 s into its file; the grid keeps it there through a small trim.
        check(FrameRules.backdropMomentMs(a) == 12_000L, "A's still is not from its middle: ${FrameRules.backdropMomentMs(a)}")
        val trimmed = a.copy(sourceOutMs = a.sourceOutMs - 300L)
        check(FrameRules.backdropMomentMs(trimmed) == FrameRules.backdropMomentMs(a), "a small trim asked for a new still")
        val tail = a.copy(sourceOutMs = a.sourceOutMs + 2_000L)
        check(FrameRules.backdropMomentMs(tail) == 13_000L, "a long trim did not move the still: ${FrameRules.backdropMomentMs(tail)}")
        check(FrameRules.backdropMomentMs(tail) % FrameRules.BACKDROP_GRID_MS == 0L, "the still is off the grid")
    }

    println("frame rules: padded canvas, fitted picture, subject on the canvas, reframe per shot, backdrop stretches")
    if (problems.isEmpty()) println("PASS - the padded frame is sized and laid out as the export writes it, and each shot follows its own subject")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
