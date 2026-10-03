import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.MusicSynth
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * The beats of every Squish original, as the editor gives them
 * (MusicSynth.beatMap), against where the track's kicks really are; and, for
 * the record, what the beat detector hears in each - which is why the editor
 * does not listen to these: 7 of 22 on their tempo when this was written.
 */
fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "squish-tempo").apply { mkdirs() }
    val problems = mutableListOf<String>()
    var heardRight = 0
    for (s in MusicSynth.styles) {
        val map = MusicSynth.beatMap(s)
        val beatMs = 60_000.0 / s.bpm
        if (map.bpm != s.bpm.toFloat()) problems += "${s.id}: beatMap says ${map.bpm}, written ${s.bpm}"
        if (map.beatsMs.size != s.bars * 4) problems += "${s.id}: ${map.beatsMs.size} beats for ${s.bars} bars"
        if (map.beatsMs.firstOrNull() != 0L) problems += "${s.id}: the first beat is not at 0"
        // Every beat inside the track, and the last one within a beat of its end.
        val lengthMs = s.seconds * 1000L
        if (map.beatsMs.any { it < 0 || it > lengthMs + beatMs }) problems += "${s.id}: a beat falls outside the track"
        // Evenly spaced to within a millisecond of rounding.
        map.beatsMs.zipWithNext().forEach { (a, b) -> if (abs((b - a) - beatMs) > 1.0) problems += "${s.id}: beats ${b - a} ms apart, not $beatMs" }

        val f = File(dir, "${s.id}.wav")
        MusicSynth.render(s, f)
        val b = f.readBytes()
        val step = (MusicSynth.SAMPLE_RATE / 11_025).coerceAtLeast(1)
        val frames = (b.size - 44) / 4
        val mono = FloatArray(frames / step) { i ->
            val at = 44 + i * step * 4
            val l = ((b[at + 1].toInt() shl 8) or (b[at].toInt() and 0xff)).toShort()
            val r = ((b[at + 3].toInt() shl 8) or (b[at + 2].toInt() and 0xff)).toShort()
            (l + r) / 65536f
        }
        val heard = BeatDetector.detect(mono, MusicSynth.SAMPLE_RATE / step).bpm
        if (abs(heard - s.bpm) / s.bpm < 0.06f) heardRight++
        println("%-18s written %5.1f  heard %6.1f".format(s.id, s.bpm.toFloat(), heard))
    }
    println("the detector heard $heardRight of ${MusicSynth.styles.size} on their written tempo (not used for these)")
    if (problems.isEmpty()) println("SynthTempoChecks: all checks passed")
    else { problems.take(20).forEach { println("FAIL: $it") }; exitProcess(1) }
}
