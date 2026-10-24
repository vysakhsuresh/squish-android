package com.squish.app.media

/**
 * A/V sync read off a file (EDGE_PLAN B3). The sync clips made by
 * tools/native/make_bench_media.sh flash white and beep together on every
 * second; after an export the two should still land together, at the start
 * of the file and at the end of an hour of it. This finds the flashes in a
 * row of frame brightnesses and the beeps in a row of samples, pairs each
 * flash with its beep, and says how far apart they are and whether that
 * grows - which is drift, the thing a long VFR clip is suspected of.
 *
 * Pure, so tools/jvm/SyncAnalysisChecks.kt runs it on signals whose answer
 * is known; the debug SyncProbeActivity feeds it a real file.
 */
object SyncAnalysis {

    /**
     * Times at which the picture goes bright: a frame whose mean luma crosses
     * the half-way line between the file's median and its brightest frame,
     * from below. [timesMs] and [luma] are per frame, in order.
     */
    fun flashes(timesMs: LongArray, luma: FloatArray): List<Long> {
        if (luma.size < 2 || luma.size != timesMs.size) return emptyList()
        val sorted = luma.sortedArray()
        val median = sorted[sorted.size / 2]
        val top = sorted.last()
        if (top - median < 0.15f) return emptyList()
        val line = median + (top - median) / 2
        val out = ArrayList<Long>()
        for (i in 1 until luma.size) if (luma[i] >= line && luma[i - 1] < line) out += timesMs[i]
        if (luma[0] >= line) out.add(0, timesMs[0])
        return out
    }

    /**
     * Times at which a sound starts: the first 5 ms window above a tenth of the
     * loudest window's level after at least 200 ms below it.
     */
    fun beeps(samples: FloatArray, sampleRate: Int): List<Long> {
        if (sampleRate <= 0 || samples.isEmpty()) return emptyList()
        val window = (sampleRate / 200).coerceAtLeast(1)
        val count = samples.size / window
        val rms = FloatArray(count) { w ->
            var s = 0.0
            for (k in w * window until (w + 1) * window) s += samples[k] * samples[k]
            kotlin.math.sqrt(s / window).toFloat()
        }
        val loudest = rms.maxOrNull() ?: return emptyList()
        if (loudest <= 1e-4f) return emptyList()
        val line = loudest / 10
        val quietNeeded = 200 / 5
        val out = ArrayList<Long>()
        var quiet = quietNeeded
        for (w in 0 until count) {
            if (rms[w] >= line) {
                if (quiet >= quietNeeded) out += w * 5L
                quiet = 0
            } else quiet++
        }
        return out
    }

    /** One flash and the beep it was paired with: [offsetMs] is picture minus sound, positive when the picture is late. */
    data class Pair(val flashMs: Long, val beepMs: Long) { val offsetMs: Long get() = flashMs - beepMs }

    /** Each flash paired with the nearest beep within [maxApartMs]; unpaired flashes are dropped. */
    fun pair(flashes: List<Long>, beeps: List<Long>, maxApartMs: Long = 400): List<Pair> =
        flashes.mapNotNull { f ->
            beeps.minByOrNull { kotlin.math.abs(it - f) }?.takeIf { kotlin.math.abs(it - f) <= maxApartMs }?.let { Pair(f, it) }
        }

    data class Report(val pairs: Int, val medianMs: Long, val worstMs: Long, val firstMs: Long, val lastMs: Long) {
        /** How far the offset moved from the first tenth of the file to the last. */
        val driftMs: Long get() = lastMs - firstMs
        override fun toString() =
            "$pairs flashes paired; offset median $medianMs ms, worst $worstMs ms; first tenth $firstMs ms, last tenth $lastMs ms, drift $driftMs ms"
    }

    fun report(pairs: List<Pair>): Report? {
        if (pairs.isEmpty()) return null
        val offsets = pairs.map { it.offsetMs }
        val tenth = (pairs.size / 10).coerceAtLeast(1)
        fun avg(xs: List<Long>) = Math.round(xs.average())
        return Report(
            pairs = pairs.size,
            medianMs = offsets.sorted()[offsets.size / 2],
            worstMs = offsets.maxByOrNull { kotlin.math.abs(it) } ?: 0,
            firstMs = avg(offsets.take(tenth)),
            lastMs = avg(offsets.takeLast(tenth))
        )
    }
}
