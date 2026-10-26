package com.squish.app.media.audio

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The arithmetic between the three models of an on-device voice (EDGE_PLAN
 * E5): AI4Bharat's FastPitch encoder, its decoder and a HiFi-GAN, each a
 * TensorFlow Lite model (tools/voices/convert.py). The encoder says how long
 * each character is held; the characters' features are repeated that many
 * frames for the decoder; the decoder's mel frames are voiced by the HiFi-GAN
 * in fixed chunks that overlap and are faded into each other.
 *
 * The layouts are the converter's: time first, features after ([T][C] and
 * [N][C], row by row), so a frame is a contiguous run.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/TtsRulesChecks.kt).
 */
object TtsRules {

    /**
     * The character ids of [text] in [symbols] (the model's own list, its pad
     * first), after the cleaning the model was trained with - lower case where
     * the script has case, runs of space made one, and anything the list does
     * not hold dropped rather than guessed at. A blank line is no ids.
     */
    fun ids(text: String, symbols: List<String>, lowercase: Boolean = true): IntArray =
        ids(text, idsOf(symbols), lowercase)

    /**
     * A symbol list as Coqui's tokenizer reads it: `{char: idx for idx, char in
     * enumerate(vocab)}`, so where a symbol is listed twice - the space, the
     * comma and the question mark are both punctuation and characters in
     * AI4Bharat's lists - the later place is its id.
     */
    fun idsOf(symbols: List<String>): Map<String, Int> {
        val index = HashMap<String, Int>(symbols.size * 2)
        symbols.forEachIndexed { i, s -> if (s.isNotEmpty()) index[s] = i }
        return index
    }

    /**
     * Coqui's `multilingual_cleaners` with no language, which the voices were
     * trained through: Unicode NFC, lower case, ";" and ":" to ",", "-" to a
     * space, the brackets and double quotes dropped, runs of space made one.
     */
    fun clean(text: String, lowercase: Boolean = true): String {
        var t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        if (lowercase) t = t.lowercase()
        t = t.replace(";", ",").replace("-", " ").replace(":", ",")
        t = t.replace(Regex("[<>()\\[\\]\"]+"), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** [text]'s ids by [index] (from [idsOf], or the voice's own table). */
    fun ids(text: String, index: Map<String, Int>, lowercase: Boolean = true): IntArray {
        val clean = clean(text, lowercase)
        if (clean.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>(clean.length)
        // By code point, so a character outside the Basic plane is one symbol.
        // Indic vowel signs and viramas are code points of their own in the
        // models' lists, so a cluster is spelled as the model was taught it.
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val s = String(Character.toChars(cp))
            index[s]?.let { out += it }
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }

    /**
     * [text] in pieces the encoder takes in one call ([maxChars] characters):
     * by sentence - the full stop, the question and exclamation marks, Hindi's
     * danda and double danda, a line break - and a sentence still too long at
     * its last comma or space before the limit, never in the middle of a word
     * unless one word is longer than the whole limit.
     */
    fun sentences(text: String, maxChars: Int): List<String> {
        require(maxChars > 1)
        val out = ArrayList<String>()
        val ends = setOf('.', '!', '?', '।', '॥', '\n')
        val buf = StringBuilder()
        fun flush() {
            var s = buf.toString().trim()
            buf.setLength(0)
            while (s.length > maxChars) {
                val window = s.substring(0, maxChars)
                val cut = maxOf(window.lastIndexOf(','), window.lastIndexOf(' ')).takeIf { it > 0 } ?: maxChars
                out += s.substring(0, if (cut < maxChars && s[cut] == ',') cut + 1 else cut).trim()
                s = s.substring(if (cut < s.length && (s[cut] == ' ' || s[cut] == ',')) cut + 1 else cut).trim()
            }
            if (s.isNotEmpty()) out += s
        }
        for (ch in text) {
            buf.append(ch)
            if (ch in ends) flush()
        }
        flush()
        return out.filter { it.any(Char::isLetterOrDigit) }
    }

    /**
     * Frames each character is held for, from the encoder's log durations, as
     * FastPitch's own inference rounds them: e^d - 1, scaled by [lengthScale]
     * (above 1 is slower), at least one frame, rounded.
     */
    fun durations(logDurations: FloatArray, lengthScale: Float = 1f): IntArray =
        IntArray(logDurations.size) { i ->
            val d = (exp(logDurations[i].toDouble()) - 1.0) * lengthScale
            maxOf(1.0, d).roundToInt()
        }

    /**
     * The encoder's [t] x [c] features, each row repeated [durations] times: the
     * decoder's input, [n] x [c] where n is the sum of the durations.
     */
    fun expand(features: FloatArray, t: Int, c: Int, durations: IntArray): FloatArray {
        require(features.size >= t * c && durations.size >= t) { "features or durations short" }
        val n = (0 until t).sumOf { durations[it].coerceAtLeast(0) }
        val out = FloatArray(n * c)
        var at = 0
        for (i in 0 until t) {
            repeat(durations[i].coerceAtLeast(0)) {
                System.arraycopy(features, i * c, out, at * c, c)
                at++
            }
        }
        return out
    }

    /**
     * Where the vocoder's chunks start: [frames] mel frames, chunks of [size]
     * with [overlap] frames shared between neighbours.
     */
    fun chunkStarts(frames: Int, size: Int, overlap: Int): List<Int> {
        require(size > 2 * overlap && overlap >= 0) { "chunk too small for its overlap" }
        if (frames <= 0) return emptyList()
        val step = size - overlap
        val out = ArrayList<Int>()
        var s = 0
        while (true) {
            out += s
            if (s + size >= frames) break
            s += step
        }
        return out
    }

    /**
     * The chunks' sound laid end to end over [totalSamples]: each chunk's
     * [overlapSamples] head faded in under the last one's tail fading out, so
     * no seam clicks. [chunks] are each chunk's samples, at their starts in
     * [startsSamples].
     */
    fun stitch(chunks: List<FloatArray>, startsSamples: List<Int>, overlapSamples: Int, totalSamples: Int): FloatArray {
        val out = FloatArray(totalSamples)
        val weight = FloatArray(totalSamples)
        chunks.forEachIndexed { k, chunk ->
            val start = startsSamples[k]
            for (j in chunk.indices) {
                val at = start + j
                if (at !in 0 until totalSamples) continue
                val rampIn = if (k > 0 && j < overlapSamples) (j + 0.5f) / overlapSamples else 1f
                val rampOut = if (k < chunks.size - 1 && j >= chunk.size - overlapSamples) (chunk.size - j - 0.5f) / overlapSamples else 1f
                val w = minOf(rampIn, rampOut).coerceIn(0f, 1f)
                out[at] += chunk[j] * w
                weight[at] += w
            }
        }
        for (i in out.indices) if (weight[i] > 1e-6f) out[i] /= weight[i]
        return out
    }
}
