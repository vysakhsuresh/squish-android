package com.squish.app.media.audio

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Squish Originals: music the app composes itself, on the phone.
 *
 * Every track here is written by this code - drums, bass, chords and melody
 * synthesised from nothing - so there is no licence to clear, nothing to
 * download, and nothing to upload. A track is rendered the first time it is
 * used and kept as a WAV from then on, so it costs no space until it is wanted.
 */
object MusicSynth {

    const val SAMPLE_RATE = 32_000

    /** Instruments a style can use. */
    internal enum class Lead { Pluck, Bell, Square, None }

    /**
     * One track's recipe: tempo, key, chords, and which parts play. The notes
     * themselves come from the chords, so every recipe stays in key.
     */
    class Style(
        val id: String,
        val title: String,
        val mood: String,
        val bpm: Int,
        /** Chord roots as MIDI notes, one per bar, and whether each is minor. */
        val chords: List<Pair<Int, Boolean>>,
        val bars: Int,
        val kickPattern: String,
        val snarePattern: String,
        val hatPattern: String,
        val swing: Float = 0f,
        val pad: Boolean = true,
        val bassEighths: Boolean = false,
        private val lead: Int = 0,
        val crackle: Boolean = false,
        val echo: Float = 0.25f
    ) {
        internal val leadKind: Lead get() = Lead.entries[lead]
        val seconds: Int get() = (bars * 4 * 60f / bpm).roundToInt()
    }

    private const val PLUCK = 0
    private const val BELL = 1
    private const val SQUARE = 2
    private const val NONE = 3

    // Patterns are 16 steps a bar: x is a hit, - is a rest.
    val styles = listOf(
        Style(
            "lofi-sunset", "Lo-fi Sunset", "Chill · 78 BPM", 78,
            listOf(57 to true, 62 to true, 55 to false, 60 to false), 16,
            kickPattern = "x-----x---x-----", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            swing = 0.18f, lead = PLUCK, crackle = true, echo = 0.3f
        ),
        Style(
            "good-vibes", "Good Vibes", "Happy · 112 BPM", 112,
            listOf(60 to false, 55 to false, 57 to true, 53 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            lead = PLUCK, bassEighths = true
        ),
        Style(
            "epic-rise", "Epic Rise", "Cinematic · 90 BPM", 90,
            listOf(50 to true, 58 to false, 53 to false, 60 to false), 16,
            kickPattern = "x-------x-------", snarePattern = "--------x-------", hatPattern = "----------------",
            lead = BELL, echo = 0.4f
        ),
        Style(
            "night-drive", "Night Drive", "Synthwave · 100 BPM", 100,
            listOf(57 to true, 53 to false, 60 to false, 55 to false), 20,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = SQUARE, bassEighths = true, echo = 0.35f
        ),
        Style(
            "chill-house", "Chill House", "Dance · 120 BPM", 120,
            listOf(53 to true, 56 to false, 51 to false, 58 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = BELL, bassEighths = true
        ),
        Style(
            "street-beat", "Street Beat", "Hip-hop · 90 BPM", 90,
            listOf(52 to true, 52 to true, 57 to true, 55 to false), 16,
            kickPattern = "x-----x-x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-xx",
            swing = 0.12f, pad = false, lead = BELL
        ),
        Style(
            "sunny-day", "Sunny Day", "Upbeat · 116 BPM", 116,
            listOf(55 to false, 62 to false, 64 to true, 60 to false), 24,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            pad = false, lead = PLUCK, bassEighths = true
        ),
        Style(
            "calm-keys", "Calm Keys", "Gentle · 70 BPM", 70,
            listOf(60 to false, 57 to true, 53 to false, 55 to false), 12,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = BELL, echo = 0.45f
        )
    )

    fun byId(id: String): Style? = styles.firstOrNull { it.id == id }

    /** Renders [style] to a stereo 16-bit WAV at [out]. */
    fun render(style: Style, out: File) {
        val frames = style.seconds * SAMPLE_RATE
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val rng = Random(style.id.hashCode())

        val beat = 60.0 / style.bpm
        val step = beat / 4.0

        for (bar in 0 until style.bars) {
            val (root, minor) = style.chords[bar % style.chords.size]
            val third = if (minor) 3 else 4
            val chord = intArrayOf(root, root + third, root + 7, root + (if (minor) 10 else 11))
            val barStart = bar * 4 * beat
            // A lighter first bar and a fuller middle: the track builds and breathes.
            val intro = bar < 2
            val outro = bar >= style.bars - 1

            for (s in 0 until 16) {
                val swingDelay = if (s % 2 == 1) style.swing * step else 0.0
                val t = barStart + s * step + swingDelay
                if (!intro && style.kickPattern[s] == 'x') kick(left, right, t)
                if (!intro && style.snarePattern[s] == 'x') snare(left, right, t, rng)
                if (style.hatPattern[s] == 'x' && !outro) hat(left, right, t, rng, if (s % 4 == 0) 0.9f else 0.55f)
            }

            // Bass: the root, on the beat or in eighths.
            val bassNote = root - 24
            val bassSteps = if (style.bassEighths) (0 until 8).map { it * 2 } else listOf(0, 8)
            for (s in bassSteps) {
                bass(left, right, barStart + s * step, midiHz(bassNote), step * (if (style.bassEighths) 1.8 else 7.5))
            }

            if (style.pad) pad(left, right, barStart, 4 * beat, chord.map { midiHz(it) })

            // Lead: an arpeggio up the chord an octave higher, varied every other bar.
            if (style.leadKind != Lead.None && !intro) {
                val order = if (bar % 2 == 0) intArrayOf(0, 1, 2, 3, 2, 1, 2, 3) else intArrayOf(3, 2, 1, 2, 0, 2, 1, 3)
                for (i in 0 until 8) {
                    val note = chord[order[i]] + 12
                    val t = barStart + i * 2 * step
                    when (style.leadKind) {
                        Lead.Pluck -> pluck(left, right, t, midiHz(note), rng, pan = if (i % 2 == 0) -0.3f else 0.3f)
                        Lead.Bell -> if (i % 2 == 0) bell(left, right, t, midiHz(note + 12))
                        Lead.Square -> square(left, right, t, midiHz(note), step * 1.6)
                        Lead.None -> Unit
                    }
                }
            }
        }

        if (style.echo > 0f) echo(left, right, (beat * 0.75 * SAMPLE_RATE).toInt(), style.echo)
        if (style.crackle) crackle(left, right, rng)
        fadeEdges(left, right)
        write(out, left, right)
    }

    // ---- Instruments ----------------------------------------------------------------

    private fun midiHz(note: Int): Double = 440.0 * 2.0.pow((note - 69) / 12.0)

    private fun add(l: FloatArray, r: FloatArray, i: Int, v: Float, pan: Float = 0f) {
        if (i !in l.indices) return
        l[i] += v * (1f - pan.coerceAtLeast(0f))
        r[i] += v * (1f + pan.coerceAtMost(0f))
    }

    private fun kick(l: FloatArray, r: FloatArray, t: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        var phase = 0.0
        for (n in 0 until (0.35 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val f = 48 + 110 * exp(-x * 28)
            phase += 2 * PI * f / SAMPLE_RATE
            add(l, r, start + n, (sin(phase) * exp(-x * 9) * 0.9).toFloat())
        }
    }

    private fun snare(l: FloatArray, r: FloatArray, t: Double, rng: Random) {
        val start = (t * SAMPLE_RATE).toInt()
        var prev = 0f
        for (n in 0 until (0.22 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            val tone = sin(2 * PI * 185 * x) * exp(-x * 30)
            add(l, r, start + n, ((hp * 0.35 * exp(-x * 18)) + tone * 0.25).toFloat())
        }
    }

    private fun hat(l: FloatArray, r: FloatArray, t: Double, rng: Random, level: Float) {
        val start = (t * SAMPLE_RATE).toInt()
        var prev = 0f
        for (n in 0 until (0.05 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            add(l, r, start + n, (hp * 0.12 * level * exp(-x * 70)).toFloat(), pan = 0.25f)
        }
    }

    private fun bass(l: FloatArray, r: FloatArray, t: Double, hz: Double, length: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        var lp = 0.0
        for (n in 0 until count) {
            val x = n.toDouble() / SAMPLE_RATE
            val saw = 2 * ((x * hz) % 1.0) - 1
            lp += (saw - lp) * 0.08
            val env = (minOf(1.0, x * 200) * exp(-x * 2.2)).coerceAtLeast(0.0) *
                (1 - (n.toDouble() / count).pow(8))
            add(l, r, start + n, (tanh(lp * 1.6) * env * 0.45).toFloat())
        }
    }

    private fun pad(l: FloatArray, r: FloatArray, t: Double, length: Double, notes: List<Double>) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        for ((k, hz) in notes.withIndex()) {
            var lp = 0.0
            val detune = 1.0 + (k - 1.5) * 0.003
            for (n in 0 until count) {
                val x = n.toDouble() / SAMPLE_RATE
                val saw = 2 * ((x * hz * detune) % 1.0) - 1
                lp += (saw - lp) * 0.02
                val env = minOf(1.0, x / 0.4) * minOf(1.0, (length - x) / 0.3).coerceAtLeast(0.0)
                add(l, r, start + n, (lp * env * 0.07).toFloat(), pan = if (k % 2 == 0) -0.4f else 0.4f)
            }
        }
    }

    /** Karplus-Strong: a burst of noise in a short damped loop - a plucked string. */
    private fun pluck(l: FloatArray, r: FloatArray, t: Double, hz: Double, rng: Random, pan: Float) {
        val start = (t * SAMPLE_RATE).toInt()
        val period = (SAMPLE_RATE / hz).toInt().coerceAtLeast(2)
        val buf = FloatArray(period) { rng.nextFloat() * 2 - 1 }
        var idx = 0
        for (n in 0 until (0.9 * SAMPLE_RATE).toInt()) {
            val next = (idx + 1) % period
            val v = buf[idx]
            buf[idx] = (v + buf[next]) * 0.4965f
            idx = next
            add(l, r, start + n, v * 0.22f, pan)
        }
    }

    private fun bell(l: FloatArray, r: FloatArray, t: Double, hz: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        for (n in 0 until (1.6 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val v = sin(2 * PI * hz * x) * exp(-x * 2.4) + 0.35 * sin(2 * PI * hz * 2.76 * x) * exp(-x * 5.0)
            add(l, r, start + n, (v * 0.13 * minOf(1.0, x * 400)).toFloat(), pan = 0.2f)
        }
    }

    private fun square(l: FloatArray, r: FloatArray, t: Double, hz: Double, length: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        var lp = 0.0
        for (n in 0 until count) {
            val x = n.toDouble() / SAMPLE_RATE
            val sq = if ((x * hz) % 1.0 < 0.5) 1.0 else -1.0
            lp += (sq - lp) * 0.12
            val env = minOf(1.0, x * 300) * exp(-x * 3.0)
            add(l, r, start + n, (lp * env * 0.08).toFloat(), pan = -0.2f)
        }
    }

    // ---- Mix ----------------------------------------------------------------------

    /** A ping-pong echo, so the lead has somewhere to live. */
    private fun echo(l: FloatArray, r: FloatArray, delay: Int, amount: Float) {
        if (delay <= 0) return
        for (i in delay until l.size) {
            l[i] += r[i - delay] * amount * 0.6f
            r[i] += l[i - delay] * amount * 0.6f
        }
    }

    private fun crackle(l: FloatArray, r: FloatArray, rng: Random) {
        for (i in l.indices) {
            val hiss = (rng.nextFloat() * 2 - 1) * 0.006f
            val pop = if (rng.nextInt(6000) == 0) (rng.nextFloat() - 0.5f) * 0.25f else 0f
            l[i] += hiss + pop
            r[i] += hiss + pop
        }
    }

    private fun fadeEdges(l: FloatArray, r: FloatArray) {
        val fade = SAMPLE_RATE * 2
        for (i in 0 until minOf(fade, l.size)) {
            val g = i.toFloat() / fade
            val j = l.size - 1 - i
            l[j] *= g
            r[j] *= g
        }
    }

    private fun write(out: File, l: FloatArray, r: FloatArray) {
        // Normalised to a safe peak, then a gentle limiter, so every track sits at
        // the same loudness under a video.
        var peak = 1e-6f
        for (i in l.indices) peak = maxOf(peak, abs(l[i]), abs(r[i]))
        val gain = 0.85f / peak
        val tmp = File(out.parentFile, out.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { o ->
            val dataBytes = l.size * 4
            o.writeBytes("RIFF"); o.writeIntLe(36 + dataBytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.writeIntLe(16); o.writeShortLe(1); o.writeShortLe(2)
            o.writeIntLe(SAMPLE_RATE); o.writeIntLe(SAMPLE_RATE * 4); o.writeShortLe(4); o.writeShortLe(16)
            o.writeBytes("data"); o.writeIntLe(dataBytes)
            for (i in l.indices) {
                o.writeShortLe((tanh(l[i] * gain) * 32000).toInt())
                o.writeShortLe((tanh(r[i] * gain) * 32000).toInt())
            }
        }
        tmp.renameTo(out)
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
