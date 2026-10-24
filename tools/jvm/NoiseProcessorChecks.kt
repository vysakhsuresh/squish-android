import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.squish.app.media.audio.NoiseProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G2: Reduce noise inside Media3's audio chain (NoiseProcessor),
 * against Media3's own BaseAudioProcessor. NoiseReduceChecks executes the
 * arithmetic; this executes the buffer handling round it: a clip keeps its
 * length to the sample through to the end of its stream, off is the sound
 * byte for byte, and switching it on and off while playing neither skips nor
 * repeats a moment.
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
        buf.flip()
        p.queueInput(buf)
        drain()
        at += n
    }
    p.queueEndOfStream()
    drain()
    check(p.isEnded, "the processor did not end after its stream")
    return out.toShortArray()
}

/** A 440 Hz tone, the same on both channels, with a little noise. */
private fun signal(seconds: Double): ShortArray {
    val frames = (seconds * RATE).toInt()
    val r = Random(5)
    return ShortArray(frames * CHANNELS) { i ->
        val f = i / CHANNELS
        (9_000 * sin(2 * PI * 440 * f / RATE) + 600 * (r.nextFloat() * 2 - 1)).toInt().toShort()
    }
}

fun main() {
    val input = signal(3.0)

    val off = run(NoiseProcessor { 0f }, input)
    check(off.contentEquals(input), "off changed the sound")

    val on = run(NoiseProcessor { 1f }, input)
    check(on.size == input.size, "${input.size} samples in, ${on.size} out with it on")
    // In step: the tone lines up with the input, not a frame late.
    var aligned = 0.0; var late = 0.0
    val lag = 2048 * CHANNELS
    for (i in RATE until input.size - lag) { aligned += on[i].toDouble() * input[i]; late += on[i + lag].toDouble() * input[i] }
    check(aligned > 0 && aligned > 2 * abs(late), "the output is not in step with the input")

    // On for the middle second only, turned while playing: the same length,
    // and the tone carries straight through both switches (no gap, no repeat
    // - a skipped or doubled stretch puts the 440 Hz out of phase).
    var calls = 0
    val toggled = run(NoiseProcessor { calls++; if (calls in 16..31) 1f else 0f }, input)
    check(toggled.size == input.size, "${input.size} samples in, ${toggled.size} out across two switches")
    // Before it was turned on, and once it is off again, every sample is the
    // input's own at its own place: a skip or a repeat would move them all.
    val onAt = 15 * 3_000
    val offBy = 32 * 3_000 + 4 * 2048 * CHANNELS
    val common = minOf(input.size, toggled.size)
    check((0 until minOf(onAt, common)).all { toggled[it] == input[it] }, "the sound changed before it was turned on")
    val moved = (offBy until common).count { toggled[it] != input[it] }
    check(moved == 0, "$moved samples after it was turned off are not the input's - skipped or repeated")
    val worst = (onAt until minOf(offBy, common)).maxOfOrNull { abs(toggled[it] - input[it]) } ?: 0

    if (problems.isEmpty()) println("NoiseProcessorChecks: all checks passed (largest change across the switches $worst)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
