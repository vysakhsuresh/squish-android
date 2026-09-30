import com.squish.app.editor.MaskOutline
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SplitSide
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withSplitScreen
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val w = 1080f
    val h = 1920f
    // Each half's mask edge, in the frame's own pixels, bounds exactly that half.
    for (side in SplitSide.entries) {
        val m = side.mask
        val pts = MaskOutline.outline(m, m.centerXFraction to m.centerYFraction, w, h).flatten()
        val xs = pts.map { it.first }.filter { it in 0f..w }
        val ys = pts.map { it.second }.filter { it in 0f..h }
        val slack = 4f
        when (side) {
            SplitSide.Left -> check(abs(xs.maxOrNull()!! - w / 2) < slack, "Left's edge was not at the middle: ${xs.maxOrNull()}")
            SplitSide.Right -> check(abs(xs.minOrNull()!! - w / 2) < slack, "Right's edge was not at the middle: ${xs.minOrNull()}")
            SplitSide.Top -> check(abs(ys.maxOrNull()!! - h / 2) < slack || abs(ys.minOrNull()!! - h / 2) < slack, "Top's edge was not at the middle")
            SplitSide.Bottom -> check(abs(ys.minOrNull()!! - h / 2) < slack || abs(ys.maxOrNull()!! - h / 2) < slack, "Bottom's edge was not at the middle")
        }
    }
    // Top and Bottom are opposite halves.
    val top = MaskOutline.outline(SplitSide.Top.mask, 0f to SplitSide.Top.mask.centerYFraction, w, h).flatten().map { it.second }.average()
    val bottom = MaskOutline.outline(SplitSide.Bottom.mask, 0f to SplitSide.Bottom.mask.centerYFraction, w, h).flatten().map { it.second }.average()
    check(abs(top - bottom) > h / 4, "Top and Bottom masked the same half")

    // Only an overlay is made a half; it goes full frame and still.
    val shot = Clip(id = "s", kind = ClipKind.Video, uri = null, label = "s", sourceInMs = 0, sourceOutMs = 5_000, timelineStartMs = 0, sourceDurationMs = 5_000)
    val pip = shot.copy(id = "p", layer = 1, scale = 0.4f, offsetXFraction = 0.5f)
    val split = TimelineState(clips = listOf(shot, pip)).withSplitScreen("p", SplitSide.Right)
    val p = split.clips.first { it.id == "p" }
    check(p.scale == 1f && p.offsetXFraction == 0f && p.mask == SplitSide.Right.mask, "the overlay was not made the right half")
    check(TimelineState(clips = listOf(shot)).withSplitScreen("s", SplitSide.Left).clips.first().mask == null, "a main-track shot was masked")

    if (problems.isEmpty()) println("SplitScreenChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
