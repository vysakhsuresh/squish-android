import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.squish.app.media.audio.FinishProcessor
import com.squish.app.media.audio.FinishSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G4: a clip's finishing inside Media3's audio chain (FinishProcessor),
 * against Media3's own BaseAudioProcessor. AudioFinishChecks executes the
 * arithmetic; this the buffer handling: off is the sound byte for byte, on keeps
 * its length to the sample, stays in step, and a loud tone through a big boost
 * comes out under the ceiling rather than wrapped.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val RATE = 48_000
private const val CHANNELS = 2

private fun run(p: AudioProcessor, input: ShortArray, chunk: Int = 3_000): ShortArray {
    p.configure(AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT))
    p.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val out = ArrayList<Short>(input.size)
    fun drain() { val o = p.output.order(ByteOrder.nativeOrder()); while (o.remaining() >= 2) out.add(o.short) }
    var at = 0
    while (at < input.size) {
        val n = minOf(chunk, input.size - at)
        val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until n) buf.putShort(input[at + i])
        buf.flip(); p.queueInput(buf); drain(); at += n
    }
    p.queueEndOfStream(); drain()
    return out.toShortArray()
}

private fun tone(hz: Double, amp: Int, seconds: Double) = ShortArray((seconds * RATE).toInt() * CHANNELS) { i ->
    (amp * sin(2 * PI * hz * (i / CHANNELS) / RATE)).toInt().toShort()
}

fun main() {
    val input = tone(440.0, 9_000, 2.0)
    check(run(FinishProcessor { FinishSettings.OFF }, input).contentEquals(input), "off changed the sound")

    val voice = FinishSettings(com.squish.app.media.audio.AudioFinish.Preset.Voice.gains.toList(), 0.5f)
    val on = run(FinishProcessor { voice }, input)
    check(on.size == input.size, "${input.size} samples in, ${on.size} out with finishing on")
    // In step: a tone that starts after half a second of silence starts in the
    // output where it does in the input (an equaliser shifts a tone's phase, which
    // is not a delay, so the onset is what is compared, not the waveform).
    val burst = ShortArray(input.size) { i -> if (i / CHANNELS < RATE / 2) 0 else input[i] }
    val burstOut = run(FinishProcessor { voice }, burst)
    fun onset(x: ShortArray) = (0 until x.size / CHANNELS).first { abs(x[it * CHANNELS].toInt()) > 500 }
    val lagFrames = onset(burstOut) - onset(burst)
    check(abs(lagFrames) <= RATE / 1000, "the finished sound starts $lagFrames frames off the input's")

    // A near-full-scale bass tone through a bass boost: limited, not wrapped round.
    val loud = tone(60.0, 30_000, 1.0)
    val boosted = run(FinishProcessor { FinishSettings(listOf(12f, 6f, 0f, 0f, 0f), 0f) }, loud)
    val peak = boosted.drop(RATE / 10).maxOf { abs(it.toInt()) }
    check(peak <= (com.squish.app.media.audio.AudioFinish.CEILING * 32767).toInt() + 2, "a boosted tone peaks at $peak, past the ceiling")
    // A wrap shows as a sample flipping sign between neighbours at full size.
    val wraps = (1 until boosted.size / CHANNELS).count { f -> val a = boosted[f * CHANNELS].toInt(); val b = boosted[(f - 1) * CHANNELS].toInt(); abs(a - b) > 40_000 }
    check(wraps == 0, "a boosted tone wrapped round $wraps times")

    // A level carried into the float path (what "Bring to -14 LUFS" sets): a tone at
    // about half scale taken to 4x comes out held under the ceiling, its level
    // brought down by the limiter rather than its tops cut flat at 16 bits. Before
    // the level lived here a 4x GainProcessor clipped it ahead of the limiter, and a
    // clipped tone is mostly samples sitting at full scale.
    val half = tone(440.0, 16_000, 1.0)
    val lifted = run(FinishProcessor { FinishSettings(level = 4f) }, half)
    val liftedPeak = lifted.drop(RATE / 10).maxOf { abs(it.toInt()) }
    val ceiling = (com.squish.app.media.audio.AudioFinish.CEILING * 32767).toInt()
    check(liftedPeak <= ceiling + 2, "a 4x level peaks at $liftedPeak, past the ceiling $ceiling")
    val flatTops = lifted.drop(RATE / 10).count { abs(it.toInt()) >= ceiling - 2 }
    check(flatTops < lifted.size / 20, "a 4x level sits flat at the ceiling for $flatTops samples - clipped, not limited")
    fun rms(x: List<Short>) = kotlin.math.sqrt(x.map { it.toDouble() * it }.average())
    val gainDb = 20 * kotlin.math.log10(rms(lifted.drop(RATE / 10)) / rms(half.drop(RATE / 10)))
    check(gainDb > 4.5, "a 4x level on a half-scale tone came out only $gainDb dB louder")
    check(!FinishSettings(level = 4f).isOff, "a level alone reads as finishing off")
    check(FinishSettings(level = 1f).isOff, "unity level reads as finishing on")

    if (problems.isEmpty()) println("FinishProcessorChecks: all checks passed (boosted peak $peak)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
