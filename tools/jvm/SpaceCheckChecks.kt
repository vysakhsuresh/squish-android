import com.squish.app.media.SpaceCheck
import kotlin.system.exitProcess

/**
 * Whether there is room for an export.
 *
 * The one that mattered: the test read `free in 1 until needed`, so a volume
 * reporting exactly 0 bytes free - which is what StatFs reports to an app on an
 * ext4 or f2fs volume full down to its root reserve, and so the single most
 * likely reading on a full phone - passed the check. The render started and
 * died in the muxer instead of saying there was no room. The 1 was there to let
 * [SpaceCheck.isShort]'s -1 "could not measure" through, and took 0 with it.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    println("export space: a full volume, an unreadable one, and the headroom")

    val needed = SpaceCheck.needed(500L * 1_000_000)

    // The reading that was let through.
    check(SpaceCheck.isShort(0L, needed), "a volume with nothing free passed the check")
    check(SpaceCheck.isShort(1L, needed), "a volume with one byte free passed the check")
    check(SpaceCheck.isShort(needed - 1, needed), "a volume one byte short passed the check")
    check(!SpaceCheck.isShort(needed, needed), "a volume with exactly enough was refused")
    check(!SpaceCheck.isShort(needed + 1, needed), "a volume with room to spare was refused")

    // The sentinel, which is what the lower bound was for: an unknown is let
    // through, because refusing every export on a phone whose StatFs throws
    // would be worse than a late failure on the few with no room.
    check(!SpaceCheck.isShort(-1L, needed), "an unmeasurable volume was refused")
    // Any negative reading is an unknown, not a very full volume.
    check(!SpaceCheck.isShort(-1_000L, needed), "a negative reading was taken as a measurement")

    // Nothing is ever short of nothing.
    check(!SpaceCheck.isShort(0L, 0L), "a volume with nothing free was short of nothing")

    // The headroom: two copies of the estimate, since the render is written to
    // the app's folder and then copied whole into the gallery.
    check(SpaceCheck.needed(1_000L * 1_000_000) == 2_200L * 1_000_000, "a gigabyte export wants ${SpaceCheck.needed(1_000L * 1_000_000)}")
    check(SpaceCheck.needed(1_000L * 1_000_000) > 2 * 1_000L * 1_000_000, "the headroom does not cover both copies")
    // And a floor, so a tiny export is not started on a volume with kilobytes.
    check(SpaceCheck.needed(0L) == SpaceCheck.MIN_SPACE_BYTES, "an export of nothing wants ${SpaceCheck.needed(0L)}")
    check(SpaceCheck.needed(1_000L) == SpaceCheck.MIN_SPACE_BYTES, "a kilobyte export wants ${SpaceCheck.needed(1_000L)}, under the floor")
    check(SpaceCheck.isShort(SpaceCheck.MIN_SPACE_BYTES - 1, SpaceCheck.needed(1_000L)), "a nearly full volume took a tiny export")
    // The floor is where the headroom overtakes it and no lower.
    val atFloor = (SpaceCheck.MIN_SPACE_BYTES / SpaceCheck.SPACE_HEADROOM).toLong()
    check(SpaceCheck.needed(atFloor + 1_000_000) > SpaceCheck.MIN_SPACE_BYTES, "the headroom never overtakes the floor")
    // Monotonic: a bigger export never wants less room.
    var last = 0L
    (0..200).forEach { i ->
        val want = SpaceCheck.needed(i * 50L * 1_000_000)
        check(want >= last, "an export of ${i * 50} MB wants $want, less than the one below it")
        last = want
    }
    // A 4K hour, as a size that must not go round the long way.
    check(SpaceCheck.needed(40_000L * 1_000_000) == 88_000L * 1_000_000, "a 40 GB export wants ${SpaceCheck.needed(40_000L * 1_000_000)}")

    println()
    if (problems.isEmpty()) println("PASS - a measured nothing is refused, an unmeasurable volume is not, and the headroom covers both copies")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
