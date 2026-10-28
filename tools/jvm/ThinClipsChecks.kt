import com.squish.app.timeline.ThinClips
import kotlin.system.exitProcess

// A clip too narrow to show a frame is drawn as a bar in its lane's one canvas
// rather than as a ClipView of its own (28 Oct): scrubbing 200 half-second shots
// zoomed out, about 60 on screen, the app spent 15-18 ms a frame building the
// ClipViews of shots scrolling in. What stays a ClipView, and which bar a tap
// lands on, are decided here.

private val failures = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) failures += msg }

fun main() {
    // Narrow and plain: a bar.
    check(ThinClips.drawnThin(widthDp = 6f, selected = false, lifted = false, trimmed = false, missing = false), "a 6 dp clip is not drawn as a bar")
    // Wide enough to show a frame: its own view.
    check(!ThinClips.drawnThin(widthDp = ThinClips.MAX_DP + 1f, selected = false, lifted = false, trimmed = false, missing = false), "a clip wider than the bar limit is drawn as a bar")
    // Anything with handles, a lift, a trim or a placeholder keeps its view, however narrow.
    check(!ThinClips.drawnThin(6f, selected = true, lifted = false, trimmed = false, missing = false), "a selected clip lost its handles to a bar")
    check(!ThinClips.drawnThin(6f, selected = false, lifted = true, trimmed = false, missing = false), "a carried clip is drawn as a bar")
    check(!ThinClips.drawnThin(6f, selected = false, lifted = false, trimmed = true, missing = false), "a clip being trimmed is drawn as a bar")
    check(!ThinClips.drawnThin(6f, selected = false, lifted = false, trimmed = false, missing = true), "a missing clip lost its placeholder to a bar")

    // Taps: three 500 ms bars end to end.
    val bars = listOf(ThinClips.Bar("a", 0, 500), ThinClips.Bar("b", 500, 1000), ThinClips.Bar("c", 1000, 1500))
    check(ThinClips.at(bars, 250, slopMs = 100) == "a", "a tap in the middle of a bar did not pick it")
    check(ThinClips.at(bars, 760, slopMs = 100) == "b", "a tap in the second bar picked ${ThinClips.at(bars, 760, 100)}")
    // On a join, the bar the tap is inside, not the one before it.
    check(ThinClips.at(bars, 1000, slopMs = 100) == "c", "a tap on a join picked ${ThinClips.at(bars, 1000, 100)}")
    // Just past the last bar, within a finger's slop: still it.
    check(ThinClips.at(bars, 1560, slopMs = 100) == "c", "a tap just past the last bar missed it")
    // Far from every bar: bare track.
    check(ThinClips.at(bars, 3000, slopMs = 100) == null, "a tap on bare track picked a bar")
    // Between two bars with a gap, the nearer one.
    val gapped = listOf(ThinClips.Bar("x", 0, 100), ThinClips.Bar("y", 300, 400))
    check(ThinClips.at(gapped, 140, slopMs = 100) == "x", "a tap nearer the first bar picked ${ThinClips.at(gapped, 140, 100)}")
    check(ThinClips.at(gapped, 270, slopMs = 100) == "y", "a tap nearer the second bar picked ${ThinClips.at(gapped, 270, 100)}")
    check(ThinClips.at(emptyList(), 0, slopMs = 100) == null, "no bars, yet a tap picked one")

    if (failures.isEmpty()) println("ThinClipsChecks: all checks passed")
    else { failures.forEach { println("FAIL: $it") }; exitProcess(1) }
}
