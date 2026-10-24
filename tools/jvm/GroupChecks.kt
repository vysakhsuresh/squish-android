import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Groups
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipsDuplicated
import kotlin.system.exitProcess

/*
 * EDGE_PLAN D4: clips selected across rows move, shift, delete, duplicate and
 * copy together keeping their relative timing, and Group keeps them together:
 * selecting any one of a group selects the group. The groups are one list of
 * id sets on the edit (Groups); the selection the editor makes from a tap is
 * Groups.selectionFor.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun video(id: String, start: Long, length: Long, layer: Int = 0) =
    Clip(id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = length, timelineStartMs = start, sourceDurationMs = 60_000, layer = layer)
private fun sound(id: String, start: Long, length: Long) =
    Clip(id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = length, timelineStartMs = start, sourceDurationMs = 60_000)

fun main() {
    // Grouping: the selection becomes one group; a member already in another group leaves it.
    var groups = Groups.grouped(emptyList(), setOf("a", "b"))
    check(groups == listOf(setOf("a", "b")), "grouping a and b gave $groups")
    groups = Groups.grouped(groups, setOf("b", "c"))
    check(groups == listOf(setOf("b", "c")), "b grouped again with c left $groups")
    check(Groups.grouped(groups, setOf("x")) == groups, "a group of one was made")

    // Selecting one of a group selects all of it, the tapped one leading.
    val (lead, rest) = Groups.selectionFor(groups, "c")
    check(lead == "c" && rest == setOf("b"), "a tap on c selected $lead + $rest")
    check(Groups.selectionFor(groups, "z") == ("z" to emptySet<String>()), "a clip in no group selected more than itself")

    // Ungrouping what is selected; and a group whose members are gone goes with them.
    check(Groups.ungrouped(groups, setOf("c")).isEmpty(), "ungrouping c left ${Groups.ungrouped(groups, setOf("c"))}")
    check(Groups.pruned(listOf(setOf("a", "b", "c")), setOf("a", "c")) == listOf(setOf("a", "c")), "a deleted member stayed in its group")
    check(Groups.pruned(listOf(setOf("a", "b")), setOf("a")).isEmpty(), "a group of one survived a delete")
    // Duplicated, the copies are a group of their own.
    check(Groups.copied(listOf(setOf("a", "b")), mapOf("a" to "a2", "b" to "b2")) == listOf(setOf("a", "b"), setOf("a2", "b2")), "the copies of a group were not grouped")

    // Duplicating a set across rows: the copies keep their spacing, one span later.
    val state = TimelineState(clips = listOf(
        video("m1", 0, 4_000), video("m2", 4_000, 3_000), video("m3", 7_000, 2_000),
        video("pip", 1_000, 2_000, layer = 1), sound("s", 2_500, 3_000)
    ), selectedClipId = "pip", selectedIds = setOf("s", "m2"))
    val ids = setOf("pip", "s", "m2")
    val dup = state.withClipsDuplicated(ids) { "$it+" }
    check(dup != null, "duplicating a set across rows was refused")
    if (dup != null) {
        val pip = dup.clips.first { it.id == "pip+" }
        val s = dup.clips.first { it.id == "s+" }
        // The set ran 1.0 s to 7.0 s; its copy runs 7.0 s to 13.0 s, the pip and the sound 1.5 s apart as before.
        check(s.timelineStartMs - pip.timelineStartMs == 1_500L, "the copies are ${s.timelineStartMs - pip.timelineStartMs} ms apart, not 1500")
        check(pip.timelineStartMs == 7_000L, "the copied pip starts at ${pip.timelineStartMs}, not after the set at 7000")
        // The main-track shot is copied in after the last selected shot, and the track makes room.
        val order = dup.baseVideoClips.map { it.id }
        check(order == listOf("m1", "m2", "m2+", "m3"), "the main track is $order")
        check(dup.clips.first { it.id == "m3" }.timelineStartMs == 10_000L, "the shot after the copy did not move along by its length")
        check(dup.allSelectedIds == setOf("pip+", "s+", "m2+") && dup.selectedClipId == "pip+", "the copies are not what is selected: ${dup.allSelectedIds}")
        check(dup.clips.size == state.clips.size + 3, "${dup.clips.size - state.clips.size} copies made of three")
    }
    // With no free overlay row where the copy would go, it is refused whole, not half done.
    val full = TimelineState(clips = (1..com.squish.app.timeline.MAX_LAYER).map { video("o$it", 2_000, 2_000, layer = it) } + video("m", 0, 10_000), selectedClipId = "o1")
    check(full.withClipsDuplicated(setOf("o1")) { "$it+" } == null || full.withClipsDuplicated(setOf("o1")) { "$it+" }!!.clips.any { it.id == "o1+" }, "a refused duplicate came back half done")

    if (problems.isEmpty()) println("GroupChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
