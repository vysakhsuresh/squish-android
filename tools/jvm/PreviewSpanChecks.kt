import com.squish.app.ui.components.PreviewSpan
import kotlin.system.exitProcess

/*
 * The length a ClipPreview measures itself against.
 *
 * Everything the preview draws is a fraction of it - the bar's width, the
 * playhead, the timecode, the kept stretch, the waveform - and when the
 * caller's probe came back with nothing it was zero, so the picture played
 * while the bar stayed at the start, the timecode read 0:00 throughout and
 * there was no bar to drag. The player knows the answer for a single file once
 * it has prepared it, and this is where that is taken.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** What ExoPlayer reports before it knows: C.TIME_UNSET. */
private const val TIME_UNSET = Long.MIN_VALUE + 1

fun main() {
    // --- A probed length is the length, and the player cannot move it. -------
    //
    // The probe is what the render will use, so where it has an answer that
    // answer wins - even if the player disagrees, which it will for a file with
    // a longer sound track than picture.
    check(PreviewSpan.scrubTotalMs(12_000L, 1, 0L) == 12_000L, "a probed length was not used")
    check(PreviewSpan.scrubTotalMs(12_000L, 1, 99_000L) == 12_000L, "the player overrode a probed length")
    check(PreviewSpan.scrubTotalMs(12_000L, 1, TIME_UNSET) == 12_000L, "an unknown player duration spoiled a probed length")
    check(PreviewSpan.scrubTotalMs(12_000L, 3, 4_000L) == 12_000L, "a playlist's probed length was not used")

    // --- A failed probe on one file falls back to the player. ----------------
    check(PreviewSpan.scrubTotalMs(0L, 1, 8_500L) == 8_500L, "the player's duration was not taken when the probe failed")
    // And not before it knows. TIME_UNSET is Long.MIN_VALUE + 1, so a test for
    // "any number" would have taken a vast negative length and every fraction
    // drawn from it would have been a negative one.
    check(PreviewSpan.scrubTotalMs(0L, 1, TIME_UNSET) == 0L, "an unset duration was taken as a length")
    check(PreviewSpan.scrubTotalMs(0L, 1, 0L) == 0L, "a duration of nothing was taken as a length")
    check(PreviewSpan.scrubTotalMs(0L, 1, -1L) == 0L, "a negative duration was taken as a length")

    // --- A playlist is not rescued, and says so. ----------------------------
    //
    // The player reports the item playing now, not the sum, and there is no way
    // to add up what was never measured. A merge preview whose sources all
    // probed zero is a caller that has not probed.
    check(PreviewSpan.scrubTotalMs(0L, 2, 8_500L) == 0L, "a two-file playlist took one file's duration as the whole")
    check(PreviewSpan.scrubTotalMs(0L, 5, 8_500L) == 0L, "a five-file playlist took one file's duration as the whole")
    // No sources at all is no length, whatever the player is holding.
    check(PreviewSpan.scrubTotalMs(0L, 0, 8_500L) == 0L, "an empty preview was given a length")

    // --- It is never negative, whatever it is handed. ------------------------
    //
    // A negative total divides the bar backwards: the playhead goes the wrong
    // way, which is the shape of fault that reaches a user as "the bar is
    // broken" rather than as a crash.
    val probes = listOf(-5_000L, -1L, 0L, 1L, 12_000L, Long.MAX_VALUE)
    val durations = listOf(TIME_UNSET, Long.MIN_VALUE, -1L, 0L, 1L, 8_500L, Long.MAX_VALUE)
    for (probed in probes) for (reported in durations) for (sources in 0..4) {
        val total = PreviewSpan.scrubTotalMs(probed, sources, reported)
        check(total >= 0L, "probed=$probed sources=$sources reported=$reported gave $total")
        // And it is one of the two numbers it was given, never arithmetic on
        // them: a preview must not invent a length.
        check(
            total == 0L || total == probed || total == reported,
            "probed=$probed sources=$sources reported=$reported gave $total, which is neither"
        )
    }

    // --- The waveform strip's four states -----------------------------------
    //
    // Unreadable is the one that did not exist. A null wave meant both "not
    // started" and "came back with nothing", so a file with no sound track, a
    // codec the decoder would not open and a long file the heap refused all
    // showed the waiting glyph for ever - nothing told anyone it had stopped
    // trying. PcmDecoder.decodeMono returns null for all three.
    run {
        // Spelled out: an enum class is not a value, so there is no aliasing it.
        check(PreviewSpan.waveState(tried = false, peaks = null) == PreviewSpan.Wave.Waiting, "nothing tried yet is not Waiting")
        check(PreviewSpan.waveState(tried = false, peaks = FloatArray(0)) == PreviewSpan.Wave.Waiting, "an empty wave before the attempt is not Waiting")
        check(PreviewSpan.waveState(tried = true, peaks = null) == PreviewSpan.Wave.Unreadable, "a decode that came back with nothing is not Unreadable")
        check(PreviewSpan.waveState(tried = true, peaks = FloatArray(0)) == PreviewSpan.Wave.Unreadable, "a decode that came back empty is not Unreadable")
        check(PreviewSpan.waveState(tried = true, peaks = FloatArray(64)) == PreviewSpan.Wave.Silent, "a decoded silence is not Silent")
        check(PreviewSpan.waveState(tried = false, peaks = FloatArray(64)) == PreviewSpan.Wave.Silent, "a silence read before the flag landed is not Silent")
        check(PreviewSpan.waveState(tried = true, peaks = floatArrayOf(0f, 0f, 0.01f)) == PreviewSpan.Wave.Drawn, "one bar above the floor is not Drawn")
        check(PreviewSpan.waveState(tried = true, peaks = floatArrayOf(0.4f, 0.9f)) == PreviewSpan.Wave.Drawn, "a wave is not Drawn")
        // Silence is tested before the flag, so the sentence a user reads does
        // not depend on which of the two states lands first.
        check(
            PreviewSpan.waveState(true, FloatArray(8)) == PreviewSpan.waveState(false, FloatArray(8)),
            "a silent wave said different things before and after the attempt"
        )
        // Waiting is the only state that is about to change, so nothing else
        // may be it: a strip that says Waiting and never moves is the fault.
        check(
            PreviewSpan.waveState(true, null) != PreviewSpan.Wave.Waiting && PreviewSpan.waveState(true, FloatArray(0)) != PreviewSpan.Wave.Waiting,
            "a finished attempt still read as Waiting"
        )
    }

    println("preview span: ${probes.size * durations.size * 5} probe-and-player pairs, 4 wave states")
    if (problems.isEmpty()) println("PASS - a failed probe falls back to the player on one file and to nothing on a playlist")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
