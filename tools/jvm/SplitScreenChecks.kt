import com.squish.app.editor.MaskOutline
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SplitSide
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withSplitScreen
import com.squish.app.timeline.withGridTile
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

    gridChecks()
    if (problems.isEmpty()) println("SplitScreenChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}

fun gridChecks() {
    val shot = Clip(id = "s", kind = ClipKind.Video, uri = null, label = "s", sourceInMs = 0, sourceOutMs = 5_000, timelineStartMs = 0, sourceDurationMs = 5_000)
    val br = com.squish.app.timeline.GridTile.BottomRight
    val t = TimelineState(clips = listOf(shot)).withGridTile("s", br.placement()).clips.first()
    // Placement: scaled about the middle, moved by fractions of half the canvas (ExportPlan.placementMatrix).
    // A 0.5 picture centred at +0.5 half-widths spans 0.5..1.0 of the frame - the bottom-right quarter.
    val left = 0.5f + t.offsetXFraction / 2f - t.scale / 2f
    val right = 0.5f + t.offsetXFraction / 2f + t.scale / 2f
    check(abs(left - 0.5f) < 1e-4f && abs(right - 1f) < 1e-4f, "the tile did not span the right quarter: $left..$right")

    // Where a placed picture lands on the canvas, in canvas fractions.
    fun bounds(at: com.squish.app.timeline.GridPlacement, ca: Float, pa: Float): FloatArray {
        val pw = if (pa >= ca) 1f else pa / ca
        val ph = if (pa >= ca) ca / pa else 1f
        val cx = 0.5f + at.offsetX / 2f
        val cy = 0.5f + at.offsetY / 2f
        return floatArrayOf(cx - at.scale * pw / 2f, cy - at.scale * ph / 2f, cx + at.scale * pw / 2f, cy + at.scale * ph / 2f)
    }
    fun near(a: Float, b: Float) = abs(a - b) < 1e-4f

    // Seen on the phone: a 1:1 frame cut from the middle of a 9:16 canvas, a 4:3 shot on it.
    // The top-left tile must sit whole in the frame's top-left quarter, centred there.
    val ca = 9f / 16f
    val fh = ca  // a square's height, in fractions of a 9:16 canvas's
    val ft = (1f - fh) / 2f
    for (tile in com.squish.app.timeline.GridTile.entries) {
        val at = tile.placement(ca, 4f / 3f, 0f, ft, 1f, fh)
        val b = bounds(at, ca, 4f / 3f)
        val qx0 = if (tile.x < 0) 0f else 0.5f
        val qy0 = ft + if (tile.y < 0) 0f else fh / 2f
        check(b[0] >= qx0 - 1e-4f && b[2] <= qx0 + 0.5f + 1e-4f && b[1] >= qy0 - 1e-4f && b[3] <= qy0 + fh / 2f + 1e-4f,
            "$tile on a square frame of a 9:16 canvas left its quarter: ${b.toList()}")
        check(near((b[0] + b[2]) / 2f, qx0 + 0.25f) && near((b[1] + b[3]) / 2f, qy0 + fh / 4f), "$tile is not centred in its quarter: ${b.toList()}")
        // Fitted, not merely inside: it fills the quarter one way.
        check(near(b[2] - b[0], 0.5f) || near(b[3] - b[1], fh / 2f), "$tile does not fill its quarter either way: ${b.toList()}")
    }
    // The canvas's own shape, uncut: tall footage on a tall canvas is the old 0.5 and ±0.5.
    val same = com.squish.app.timeline.GridTile.TopLeft.placement(ca, ca)
    check(near(same.scale, 0.5f) && near(same.offsetX, -0.5f) && near(same.offsetY, -0.5f), "an uncut frame moved the tile: $same")
    // A 9:16 shot on a square frame cut from its own 9:16 canvas stands inside its quarter, height-bound.
    val tall = com.squish.app.timeline.GridTile.BottomRight.placement(ca, ca, 0f, ft, 1f, fh)
    val tb = bounds(tall, ca, ca)
    check(near(tb[3] - tb[1], fh / 2f) && tb[2] - tb[0] < 0.5f, "a tall shot was not fitted by its height: ${tb.toList()}")
    // A hand-drawn window off centre: the quarter is the window's, wherever it is.
    val off = com.squish.app.timeline.GridTile.TopLeft.placement(1f, 1f, 0.2f, 0.1f, 0.6f, 0.6f)
    val ob = bounds(off, 1f, 1f)
    check(near(ob[0], 0.2f) && near(ob[1], 0.1f) && near(ob[2], 0.5f) && near(ob[3], 0.4f), "an off-centre window's quarter was missed: ${ob.toList()}")
    // Nonsense in, the plain tile out.
    val bad = br.placement(Float.NaN, 0f, 0f, 0f, 0f, -1f)
    check(near(bad.scale, 0.5f) && near(bad.offsetX, 0.5f) && near(bad.offsetY, 0.5f), "bad geometry gave $bad")
    check(br.placement().matches(0.5f, 0.5f, 0.5f) && !br.placement().matches(0.5f, 0.5f, 0.4f), "matches() read a placement wrong")
}
