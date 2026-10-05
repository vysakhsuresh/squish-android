import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.SpeechSegmenter
import kotlin.system.exitProcess

/*
 * The speech finder, executed on synthetic audio.
 *
 * The fault this was written for: the dynamic-range gate measured "loud" as
 * the 95th percentile of every 20 ms frame of the whole decoded file, which
 * answers "is more than a twentieth of this loud" rather than "is there a loud
 * part". Every caller decodes the whole file and applies the clip's window
 * afterwards - auto-captions up to thirty minutes of the source, Remove
 * silences and Duck under speech from the file's start to the shot's out-point
 * - so a 30 s talking head trimmed out of a fifteen-minute recording is 3% of
 * what the segmenter sees. Under about 5% the 95th percentile was room tone,
 * the gate fired, and a clip that is nothing but speech reported no speech at
 * all: "no speech found" on the caption panel, "no talking found in this shot"
 * from Remove silences, and no dips from Duck under speech.
 *
 * So the sweep below is the check: the same words, at the same level, in takes
 * of growing length, must keep being found.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

const val RATE = 16_000

/**
 * A take [lengthMs] long of room tone at [tone], with [words] one-second words
 * at [level] starting a second in, a second apart. Deterministic: the "speech"
 * is a 200 Hz tone shaped by a slow envelope, the room tone a fixed pseudo-
 * random hiss, so a run is a run.
 */
fun take(lengthMs: Int, words: Int, level: Float = 0.4f, tone: Float = 0.004f): MonoPcm {
    // Long: 16000 * 300000 overflows an Int, and a five-minute take came out
    // silently as a 31 s one - the very case this suite exists to cover.
    val n = (RATE.toLong() * lengthMs / 1000L).toInt()
    val out = FloatArray(n)
    var seed = 12345L
    fun noise(): Float {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 40).toInt() / 8_388_608f - 1f) * tone
    }
    for (i in 0 until n) out[i] = noise()
    for (w in 0 until words) {
        val from = RATE * (1 + 2 * w)
        val to = from + RATE
        if (to > n) break
        for (i in from until to) {
            // A word is not a square wave: it rises and falls, so the frames at
            // its edges sit between the floor and the level.
            val through = (i - from).toFloat() / RATE
            val envelope = kotlin.math.sin(through * Math.PI).toFloat()
            out[i] += (kotlin.math.sin(2.0 * Math.PI * 200.0 * i / RATE).toFloat()) * level * envelope
        }
    }
    return MonoPcm(out, RATE)
}

fun main() {
    // --- The words are found whatever share of the take they are. -------------
    //
    // Five one-second words, the same five every time, in takes from 12 s to
    // 300 s. The share falls from 42% to 1.7%; the answer must not.
    run {
        val lengths = listOf(12_000, 20_000, 30_000, 45_000, 60_000, 120_000, 300_000)
        lengths.forEach { ms ->
            val found = SpeechSegmenter.segment(take(ms, words = 5))
            check(
                found.size == 5,
                "five words in a ${ms / 1000} s take (${"%.1f".format(500f / (ms / 1000f))}% speech) " +
                    "were heard as ${found.size} segments"
            )
            // And each one lands on its word: word w runs from 1 + 2w seconds.
            found.sortedBy { it.startMs }.forEachIndexed { w, seg ->
                val wordStart = 1_000L + 2_000L * w
                check(
                    seg.startMs in (wordStart - 400)..(wordStart + 400),
                    "in a ${ms / 1000} s take, word $w was put at ${seg.startMs} rather than near $wordStart"
                )
            }
        }
    }

    // --- The level of the talking does not decide it either. ------------------
    run {
        listOf(0.05f, 0.1f, 0.4f, 0.9f).forEach { level ->
            val found = SpeechSegmenter.segment(take(120_000, words = 5, level = level))
            check(found.size == 5, "five words at level $level in a 120 s take gave ${found.size} segments")
        }
    }

    // --- And silence is still silence. ---------------------------------------
    run {
        // Room tone alone: no separation, nothing to find. This is what the gate
        // is for, and it must still fire.
        check(SpeechSegmenter.segment(take(30_000, words = 0)).isEmpty(), "room tone was carved into captions")
        // Flat digital silence.
        check(SpeechSegmenter.segment(MonoPcm(FloatArray(RATE * 10), RATE)).isEmpty(), "silence was carved into captions")
        // Solid noise at speech level, no quiet parts at all.
        val loud = take(30_000, words = 0, tone = 0.4f)
        check(SpeechSegmenter.segment(loud).isEmpty(), "solid noise was carved into captions")
        // One click: a single loud frame cannot open a segment, and must not
        // raise the loud level either - OPEN_FRAMES consecutive frames are what
        // it takes, which is why the gate measures the OPEN_FRAMES-th loudest.
        val clicked = take(30_000, words = 0)
        for (i in RATE * 5 until RATE * 5 + 160) clicked.samples[i] = 0.9f   // 10 ms
        check(SpeechSegmenter.segment(clicked).isEmpty(), "a 10 ms click became a caption")
        // Too short to measure.
        check(SpeechSegmenter.segment(MonoPcm(FloatArray(100) { 0.5f }, RATE)).isEmpty(), "a sliver gave segments")
        check(SpeechSegmenter.segment(MonoPcm(FloatArray(0), RATE)).isEmpty(), "no samples gave segments")
        check(SpeechSegmenter.segment(MonoPcm(FloatArray(RATE), 0)).isEmpty(), "a rate of nothing gave segments")
    }

    // --- A speech-dense take behaves exactly as it did. -----------------------
    //
    // The threshold still comes from the 95th percentile wherever that stands
    // clear of the floor on its own, so nothing that worked before moved. Ten
    // words in a 21 s take is 48% speech - well inside the old gate - and the
    // segments must still be the words.
    run {
        val found = SpeechSegmenter.segment(take(21_000, words = 10))
        check(found.size == 10, "ten words in a dense take gave ${found.size} segments")
        check(found.all { it.durationMs in 900..1_600 }, "a word's segment is ${found.map { it.durationMs }}")
    }

    // --- Two words a breath apart are one caption. ---------------------------
    run {
        // Words half a second apart on purpose: MERGE_GAP_MS is 220 ms and the
        // pad is 110 either side, so a 500 ms gap stays two segments while a
        // 200 ms one is joined.
        val n = RATE * 8
        val out = FloatArray(n) { 0.004f * if (it % 7 == 0) 1f else -1f }
        fun word(fromMs: Int, lengthMs: Int) {
            for (i in RATE * fromMs / 1000 until RATE * (fromMs + lengthMs) / 1000) {
                out[i] += kotlin.math.sin(2.0 * Math.PI * 200.0 * i / RATE).toFloat() * 0.4f
            }
        }
        word(1_000, 600); word(1_800, 600)       // 200 ms apart: one caption
        word(5_000, 600)                          // 2.6 s later: its own
        val found = SpeechSegmenter.segment(MonoPcm(out, RATE))
        check(found.size == 2, "a breath split a sentence: ${found.map { "${it.startMs}-${it.endMs}" }}")
    }

    if (problems.isEmpty()) {
        println("SegmenterChecks: speech is found whatever share of the file it is, and silence is still silence")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}
