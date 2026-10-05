import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.squish.app.editor.AudioRules
import com.squish.app.media.audio.FadeProcessor
import com.squish.app.media.audio.GainCurveProcessor
import com.squish.app.media.audio.GainProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * The three level processors, run against Media3's own BaseAudioProcessor.
 *
 * `AudioRulesChecks` executes what a fade's level *should* be at a moment.
 * These are the things that apply it to samples in the exported file and in
 * the preview's sink, and nothing had ever run them: a fade that is right in
 * the arithmetic and wrong in the buffer loop is silent until someone listens
 * to a file, which on this project means weeks.
 *
 * The signal is full-scale, so every assertion below is about the gain and not
 * about the content.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val RATE = 48_000
private const val CHANNELS = 2

/** Feeds [input] through [p] in 4096-sample bites and returns everything it gave back. */
private fun run(p: AudioProcessor, input: ShortArray, channels: Int = CHANNELS, rate: Int = RATE): ShortArray {
    p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
    p.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val out = ArrayList<Short>(input.size)
    var at = 0
    while (at < input.size) {
        val n = minOf(4096, input.size - at)
        val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until n) buf.putShort(input[at + i])
        buf.flip()
        p.queueInput(buf)
        val o = p.output
        while (o.remaining() >= 2) out.add(o.short)
        at += n
    }
    return out.toShortArray()
}

/** A full-scale square: every sample at the top of the range, so the output is the gain. */
private fun flat(seconds: Double, channels: Int = CHANNELS): ShortArray =
    ShortArray((seconds * RATE).toInt() * channels) { 16_000 }

/** The level the output shows at [ms], as a fraction of full scale. */
private fun levelAt(out: ShortArray, ms: Long, channels: Int = CHANNELS): Float {
    val frame = (ms * RATE / 1000L).toInt()
    val at = frame * channels
    if (at !in out.indices) return -1f
    return out[at] / 16_000f
}

fun main() {
    // ---- A fade in and a fade out, in the buffer ----------------------------
    run {
        val lengthMs = 4_000L
        val input = flat(4.0)
        val out = run(FadeProcessor(fadeInMs = 1_000L, fadeOutMs = 1_000L, lengthMs = lengthMs), input)
        check(out.size == input.size, "the fade changed the stream's length: ${out.size} from ${input.size}")

        // The shape, at the moments the sheet's sliders are about.
        listOf(0L to 0f, 500L to 0.5f, 1_000L to 1f, 2_000L to 1f, 3_500L to 0.5f).forEach { (ms, want) ->
            val got = levelAt(out, ms)
            check(abs(got - want) < 0.03f, "at ${ms}ms the fade is $got, want about $want")
        }
        // And it agrees with the arithmetic the preview uses, everywhere.
        for (ms in 0L until lengthMs step 37L) {
            val want = AudioRules.fadeGain(ms, lengthMs, 1_000L, 1_000L)
            val got = levelAt(out, ms)
            check(abs(got - want) < 0.03f, "at ${ms}ms the buffer is $got and AudioRules says $want")
        }
        // Never louder than it came in, and never negative.
        check(out.all { it in 0..16_000 }, "the fade took a sample outside the level it was given")
    }

    // ---- A stream that starts part-way through its clip ---------------------
    run {
        // A sound dragged back past zero: the first second of its fade in has
        // already happened, so it opens at half level and reaches full at 500 ms.
        val out = run(FadeProcessor(1_000L, 0L, 4_000L, startMs = 500L), flat(2.0))
        check(abs(levelAt(out, 0L) - 0.5f) < 0.03f, "a stream starting 500ms in opens at ${levelAt(out, 0L)}, want 0.5")
        check(abs(levelAt(out, 500L) - 1f) < 0.03f, "it reaches ${levelAt(out, 500L)} at 500ms, want 1")
    }

    // ---- Nothing to do, nothing done ---------------------------------------
    run {
        val p = FadeProcessor(0L, 0L, 4_000L)
        p.configure(AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        check(!p.isActive, "a clip with no fades still runs the fade processor over every sample")
    }

    // ---- A boost, and where it stops ---------------------------------------
    run {
        val input = flat(0.2)
        check(run(GainProcessor { 1f }, input).toList() == input.toList(), "gain 1 changed the samples")
        val twice = run(GainProcessor { 2f }, input)
        check(twice.all { it.toInt() == 32_000 }, "gain 2 gave ${twice.firstOrNull()}, want 32000")
        // Past the top of the range it clips rather than wrapping, which is the
        // difference between a loud sound and a burst of noise.
        val loud = run(GainProcessor { 4f }, input)
        check(loud.all { it == Short.MAX_VALUE }, "gain 4 did not clip to the sample range: ${loud.firstOrNull()}")
    }

    // ---- A level that moves over the clip ----------------------------------
    run {
        // Up from nothing to full across two seconds, read in played time.
        val out = run(GainCurveProcessor(gainAt = { ms -> (ms / 2_000f).coerceIn(0f, 1f) }), flat(2.0))
        listOf(0L to 0f, 500L to 0.25f, 1_000L to 0.5f, 1_900L to 0.95f).forEach { (ms, want) ->
            val got = levelAt(out, ms)
            check(abs(got - want) < 0.03f, "the volume curve is $got at ${ms}ms, want about $want")
        }
        // It only reads the curve every millisecond or so, which must not show.
        for (ms in 0L until 2_000L step 13L) {
            val got = levelAt(out, ms)
            check(abs(got - (ms / 2_000f)) < 0.01f, "the curve's step shows at ${ms}ms: $got")
        }
        // Above one, as the mixer allows, and clipped the same way.
        val hot = run(GainCurveProcessor(gainAt = { 3f }), flat(0.1))
        check(hot.all { it == Short.MAX_VALUE }, "a level above one did not clip: ${hot.firstOrNull()}")
    }

    // ---- Mono, and an odd channel count ------------------------------------
    run {
        val out = run(FadeProcessor(1_000L, 0L, 2_000L), flat(2.0, channels = 1), channels = 1)
        check(abs(levelAt(out, 500L, 1) - 0.5f) < 0.03f, "a mono fade is ${levelAt(out, 500L, 1)} at 500ms")
    }

    println("processors: the fades and the levels, applied to samples through Media3's own base class")
    if (problems.isEmpty()) println("PASS - the buffer loops agree with AudioRules, keep the stream's length, and clip rather than wrap")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
