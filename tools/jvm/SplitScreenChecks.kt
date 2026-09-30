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
    // Where the kept part of a split overlay lands on the frame, in frame
    // fractions: the picture fitted, placed (ExportPlan.placementMatrix: scaled
    // about the middle, moved by fractions of half the frame), then cut by its
    // mask in its own -1..1 coordinates, then by the frame's edges.
    fun kept(side: SplitSide, fa: Float, pa: Float): FloatArray {
        val at = side.layout(fa, pa)
        val pw = if (pa >= fa) 1f else pa / fa
        val ph = if (pa >= fa) fa / pa else 1f
        val left = 0.5f + at.offsetX / 2f - at.scale * pw / 2f
        val top = 0.5f + at.offsetY / 2f - at.scale * ph / 2f
        val m = at.mask
        val u0 = 0.5f + m.centerXFraction / 2f - m.widthFraction / 2f
        val u1 = 0.5f + m.centerXFraction / 2f + m.widthFraction / 2f
        val v0 = 0.5f + m.centerYFraction / 2f - m.heightFraction / 2f
        val v1 = 0.5f + m.centerYFraction / 2f + m.heightFraction / 2f
        // The mask and the picture's own edges, then the frame's.
        val x0 = maxOf(left + maxOf(u0, 0f) * at.scale * pw, 0f)
        val x1 = minOf(left + minOf(u1, 1f) * at.scale * pw, 1f)
        val y0 = maxOf(top + maxOf(v0, 0f) * at.scale * ph, 0f)
        val y1 = minOf(top + minOf(v1, 1f) * at.scale * ph, 1f)
        return floatArrayOf(x0, y0, x1, y1)
    }
    fun near(a: Float, b: Float) = abs(a - b) < 2e-3f

    // Seen on the phone: a portrait clip over a square frame. Also the frame's
    // own shape, landscape over portrait, and the reverse.
    val shapes = listOf(1f to 9f / 16f, 9f / 16f to 9f / 16f, 16f / 9f to 16f / 9f, 9f / 16f to 16f / 9f, 16f / 9f to 9f / 16f, 1f to 4f / 3f)
    for ((fa, pa) in shapes) for (side in SplitSide.entries) {
        val k = kept(side, fa, pa)
        val want = when (side) {
            SplitSide.Left -> floatArrayOf(0f, 0f, 0.5f, 1f)
            SplitSide.Right -> floatArrayOf(0.5f, 0f, 1f, 1f)
            SplitSide.Top -> floatArrayOf(0f, 0f, 1f, 0.5f)
            SplitSide.Bottom -> floatArrayOf(0f, 0.5f, 1f, 1f)
        }
        check((0..3).all { near(k[it], want[it]) }, "$side of a $pa picture on a $fa frame kept ${k.toList()}, not its half ${want.toList()}")
    }
    // A picture the frame's shape keeps its middle, not the edge it used to keep.
    val mid = SplitSide.Left.layout(16f / 9f, 16f / 9f)
    check(near(mid.scale, 1f) && near(mid.mask.centerXFraction, 0f) && near(mid.mask.widthFraction, 0.5f), "a frame-shaped picture was laid out as $mid")

    // Only an overlay is made a half; it goes still and upright, and matches() reads it back.
    val shot = Clip(id = "s", kind = ClipKind.Video, uri = null, label = "s", sourceInMs = 0, sourceOutMs = 5_000, timelineStartMs = 0, sourceDurationMs = 5_000)
    val pip = shot.copy(id = "p", layer = 1, scale = 0.4f, offsetXFraction = 0.5f, quarterTurns = 1, mirrored = true)
    val at = SplitSide.Right.layout(1f, 9f / 16f)
    val split = TimelineState(clips = listOf(shot, pip)).withSplitScreen("p", at)
    val p = split.clips.first { it.id == "p" }
    check(at.matches(p) && p.quarterTurns == 0 && !p.mirrored, "the overlay was not made the right half: $p")
    check(!SplitSide.Left.layout(1f, 9f / 16f).matches(p), "Left read as the Right half")
    check(TimelineState(clips = listOf(shot)).withSplitScreen("s", at).clips.first().mask == null, "a main-track shot was masked")
    // Nonsense in, the frame-shaped layout out.
    val bad = SplitSide.Top.layout(Float.NaN, -1f)
    check(bad.scale.isFinite() && near(bad.offsetY, -0.5f), "bad shapes gave $bad")

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
