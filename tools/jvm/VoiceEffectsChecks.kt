import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.squish.app.media.audio.VoiceProcessor
import com.squish.app.timeline.VoiceEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/**
 * Every voice effect run through VoiceProcessor - Media3's own processor base
 * class, from the Gradle cache - on a second of a voice-like signal: the
 * output is the input's length, never silent, never clipped flat, and the
 * processed effects change the sound while the pitch-only ones leave it to
 * Sonic. Cave's repeat lands half a second after a click.
 */
fun run(effect: VoiceEffect, input: ShortArray, channels: Int, rate: Int): ShortArray {
    val p = VoiceProcessor { effect }
    p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
    p.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val out = ArrayList<Short>()
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

fun main() {
    val rate = 44_100
    val channels = 2
    // A voice-ish signal: a 160 Hz buzz with harmonics, swelling like syllables.
    val frames = rate
    val voice = ShortArray(frames * channels) { i ->
        val t = (i / channels).toDouble() / rate
        val env = 0.5 + 0.5 * sin(2 * PI * 3 * t)
        val v = (sin(2 * PI * 160 * t) + 0.5 * sin(2 * PI * 320 * t) + 0.25 * sin(2 * PI * 1200 * t)) * env * 0.4
        (v * 32767).toInt().toShort()
    }
    for (effect in VoiceEffect.entries) {
        val out = try { run(effect, voice, channels, rate) } catch (e: Exception) { problems += "$effect threw $e"; continue }
        check(out.size == voice.size, "$effect changed the length: ${out.size} vs ${voice.size}")
        val rms = kotlin.math.sqrt(out.sumOf { it.toDouble() * it } / out.size) / 32768
        check(rms > 0.02, "$effect is nearly silent (rms $rms)")
        val pinned = out.count { abs(it.toInt()) >= 32767 }
        check(pinned < out.size / 50, "$effect clips $pinned samples")
        val processed = effect != VoiceEffect.None && effect.pitch == 1f
        val differs = out.indices.count { out[it] != voice[it] }
        if (processed) check(differs > out.size / 2, "$effect barely changes the sound ($differs samples)")
        else if (effect != VoiceEffect.Alien) check(differs == 0, "$effect is a pitch for Sonic but the processor changed it")
    }
    // Cave: a click comes back about half a second later.
    val click = ShortArray(rate * channels) { if (it / channels in 0 until 40) 20_000 else 0 }
    val cave = run(VoiceEffect.Cave, click, channels, rate)
    val echoAt = (rate * 0.5).toInt()
    val tail = (echoAt - 200 until echoAt + 400).maxOf { abs(cave[it * channels].toInt()) }
    check(tail > 3_000, "Cave's repeat is missing at half a second (peak $tail)")
    // Echo, then a switch to Cave over silence with no seek between: nothing of
    // Echo's tail may come out (the two share one delay line).
    run {
        var current = VoiceEffect.Echo
        val p = VoiceProcessor { current }
        p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        fun feed(samples: ShortArray): ShortArray {
            val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
            samples.forEach { buf.putShort(it) }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            return ShortArray(o.remaining() / 2) { o.short }
        }
        feed(voice.copyOfRange(0, 8_192))
        current = VoiceEffect.Cave
        val after = feed(ShortArray(8_192))
        check(after.all { it.toInt() == 0 }, "Echo's tail came out after a switch to Cave (peak ${after.maxOf { abs(it.toInt()) }})")
    }
    if (problems.isEmpty()) println("VoiceEffectsChecks: all checks passed (${VoiceEffect.entries.size} voices)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
