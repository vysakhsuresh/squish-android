import com.squish.app.editor.SlotRules
import com.squish.app.editor.SlotTemplate
import com.squish.app.media.audio.MusicSynth
import com.squish.app.timeline.TransitionType
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN H1: slot templates - the table checked against the originals and
 * the templates it names, and the slot arithmetic executed.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val templates = Regex("^    ([A-Z][A-Za-z0-9]*)\\(", RegexOption.MULTILINE)
        .findAll(File("app/src/main/java/com/squish/app/editor/Template.kt").readText()).map { it.groupValues[1] }.toSet()
    check(templates.size >= 40, "read only ${templates.size} templates out of Template.kt - this check has rotted")

    check(SlotTemplate.entries.size >= 20, "${SlotTemplate.entries.size} slot templates, the plan asks for 20")
    for (t in SlotTemplate.entries) {
        check(t.base in templates, "${t.label} names a template \"${t.base}\" that Template.kt does not have")
        val song = MusicSynth.byId(t.songId)
        check(song != null, "${t.label} names an original \"${t.songId}\" that MusicSynth does not have")
        if (song != null) {
            val beats = song.bars * 4
            check(t.slots.sum() <= beats, "${t.label}'s slots run ${t.slots.sum()} beats over a ${beats}-beat song")
        }
        check(t.slots.all { it >= 1 } && t.slots.size >= 4, "${t.label}: slots ${t.slots}")
        check(t.joins.isNotEmpty(), "${t.label} has no joins")
    }
    check(SlotTemplate.entries.map { it.label }.distinct().size == SlotTemplate.entries.size, "two slot templates share a name")
    check(SlotTemplate.entries.map { it.songId }.distinct().size >= 15, "fewer than 15 songs across 20 slot templates")

    // The joins go round, and the first slot has none.
    val qc = SlotTemplate.QuickCuts
    check(qc.joinInto(0) == TransitionType.None && qc.joinInto(1) == qc.joins[0] && qc.joinInto(1 + qc.joins.size) == qc.joins[0], "joins do not go round")

    // --- Filling. -----------------------------------------------------------
    val beatMs = 500.0 // 120 bpm
    // Long shots: every slot its full beats, the ends on the grid.
    val long = SlotRules.fill(listOf(4, 2, 2, 8), beatMs, listOf(60_000L, 60_000L, 60_000L, 60_000L))
    check(long.map { it.endMs } == listOf(2_000L, 3_000L, 4_000L, 8_000L), "ends ${long.map { it.endMs }}")
    check(long.map { it.shot } == listOf(0, 1, 2, 3), "shots ${long.map { it.shot }}")
    // Fewer shots than slots: round again.
    val few = SlotRules.fill(listOf(2, 2, 2, 2, 2), beatMs, listOf(10_000L, 10_000L))
    check(few.map { it.shot } == listOf(0, 1, 0, 1, 0), "two shots over five slots went ${few.map { it.shot }}")
    // A shot shorter than its slot: whole beats it holds, the cut still on a beat.
    val short = SlotRules.fill(listOf(8, 4), beatMs, listOf(1_900L, 60_000L))
    check(short[0].beats == 3 && short[0].endMs == 1_500L && short[1].endMs == 3_500L, "a 1.9 s shot in an 8-beat slot: $short")
    // Too short for even a beat: one beat all the same.
    check(SlotRules.fill(listOf(4), beatMs, listOf(200L))[0].beats == 1, "a 0.2 s shot got no beat")
    // A photo takes the slot whole.
    check(SlotRules.fill(listOf(8), beatMs, listOf(null))[0].beats == 8, "a photo did not take its slot")
    // No drift at an awkward tempo over many slots: the last end is its beat count times the beat.
    val odd = 60_000.0 / 97
    val many = SlotRules.fill(List(200) { 1 }, odd, List(200) { 60_000L })
    check(abs(many.last().endMs - 200 * odd) <= 1.0, "200 one-beat slots at 97 bpm drifted to ${many.last().endMs} from ${200 * odd}")

    // Laid in long enough that the beat trim only shortens, and never past the next beat.
    for (i in long.indices) {
        val laid = SlotRules.laidLengthMs(long, i, beatMs, joinIn = true)
        val from = if (i == 0) 0L else long[i - 1].endMs
        val join = if (i > 0) SlotRules.joinMs(beatMs) else 0L
        // Its end on the track, with the join overlapping it: from - join + laid.
        val end = from - join + laid
        check(end > long[i].endMs && end < long[i].endMs + beatMs, "slot $i laid to end at $end, its beat ${long[i].endMs}")
    }
    check(SlotRules.joinMs(60_000.0 / 60) == SlotRules.MAX_JOIN_MS && SlotRules.joinMs(60_000.0 / 150) == 200L, "join lengths")

    if (problems.isEmpty()) println("SlotTemplateChecks: all checks passed (${SlotTemplate.entries.size} slot templates)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
