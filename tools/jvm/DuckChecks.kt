import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.DuckRules
import com.squish.app.timeline.valueAt
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps

fun main() {
    // A 30 s song at 80% from 0, and a voiceover saying something at 5-8 s and 20-22 s.
    val song = Clip(kind = ClipKind.Audio, uri = null, label = "song", sourceInMs = 0, sourceOutMs = 30_000,
        timelineStartMs = 0, sourceDurationMs = 60_000, volume = 0.8f)
    val speech = listOf(5_000L..8_000L, 20_000L..22_000L)
    val keys = DuckRules.keys(song, speech)
    val ducked = song.copy(volumeKeys = keys)
    fun at(ms: Long) = ducked.volumeAt(ms)

    check(near(at(1_000), 0.8f), "the song was not at its level before the first line: ${at(1_000)}")
    check(near(at(6_000), 0.2f), "the song was not down under the first line: ${at(6_000)}")
    check(near(at(21_000), 0.2f), "the song was not down under the second line: ${at(21_000)}")
    check(near(at(14_000), 0.8f), "the song did not come back between the lines: ${at(14_000)}")
    check(near(at(29_000), 0.8f), "the song did not come back at the end: ${at(29_000)}")
    // Eased, not stepped: half way down during the fade.
    val mid = at(5_000 - DuckRules.FADE_MS / 2)
    check(mid > 0.3f && mid < 0.7f, "the dip was a step, not a fade: $mid")
    check(keys.zipWithNext().all { (a, b) -> a.atMs < b.atMs }, "keys were not in order")

    // Two lines close together keep the song down between them.
    val close = DuckRules.keys(song, listOf(5_000L..6_000L, 6_500L..7_500L))
    check(near(song.copy(volumeKeys = close).volumeAt(6_250), 0.2f), "the song pumped up between two close lines")

    // No speech under the song: nothing changes.
    check(DuckRules.keys(song, listOf(40_000L..45_000L)).isEmpty(), "speech past the song's end still made keys")
    check(DuckRules.keys(song, emptyList()).isEmpty(), "no speech made keys")

    // A song starting at 10 s: its keys are in its own time.
    val later = song.copy(timelineStartMs = 10_000)
    val laterKeys = DuckRules.keys(later, listOf(15_000L..17_000L))
    check(near(later.copy(volumeKeys = laterKeys).volumeAt(6_000), 0.2f), "a later song ducked at the wrong moment")

    // Speech mapped from a clip's file through its trim: a shot trimmed to 2-12 s of its file,
    // placed at 4 s, speaking at 3-5 s of its file, speaks at 5-7 s of the timeline.
    val shot = Clip(kind = ClipKind.Video, uri = null, label = "shot", sourceInMs = 2_000, sourceOutMs = 12_000,
        timelineStartMs = 4_000, sourceDurationMs = 20_000)
    val onLine = DuckRules.onTimeline(shot, listOf(3_000L..5_000L, 15_000L..16_000L))
    check(onLine == listOf(5_000L..7_000L), "speech was not carried through the trim: $onLine")

    // A song ducked at 0.8 is never taken above its own level.
    check(keys.all { it.value <= 0.8f + 1e-4f }, "a key went above the song's level")

    if (problems.isEmpty()) {
        println("DuckChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
