import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.WaveformBuilder
import kotlin.system.exitProcess

/*
 * The energy envelope auto-sync correlates, and the clock its buckets sit on.
 *
 * Two recordings of the same moment are lined up by cross-correlating these,
 * and `PcmDecoder.decodeMono` decimates by an integer stride - so a 48 kHz
 * camera track comes back at 8,000 Hz and a 44.1 kHz recording at 8,820. If a
 * bucket is a whole number of *samples* rather than a length of *time*, the two
 * envelopes run at different speeds and no single lag lines them up: the error
 * accumulates across a forty-five second window and a true three-second offset
 * is reported as about three and a bit.
 *
 * So: a bucket begins where the clock says, at any rate.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** Silence of [ms] at [rate], with a click one sample wide at each of [clicksAtMs]. */
private fun signal(rate: Int, ms: Int, vararg clicksAtMs: Int): MonoPcm {
    val samples = FloatArray(rate.toLong().times(ms).div(1000).toInt())
    clicksAtMs.forEach { at ->
        val i = (at.toLong() * rate / 1000).toInt()
        if (i in samples.indices) samples[i] = 1f
    }
    return MonoPcm(samples, rate)
}

fun main() {
    val bucketMs = 10

    // ---- A click lands in the bucket its moment belongs to, at any rate -----
    //
    // 8,000 is a whole number of samples per bucket and 8,820 is not, which is
    // the pair auto-sync actually gets from a 48 kHz and a 44.1 kHz source.
    listOf(8_000, 8_820, 11_025, 16_000).forEach { rate ->
        listOf(1_000, 10_000, 30_000, 44_000).forEach { atMs ->
            val env = WaveformBuilder.envelope(signal(rate, 45_000, atMs), bucketMs)
            val loudest = env.indices.maxByOrNull { env[it] } ?: -1
            check(
                loudest == atMs / bucketMs,
                "at ${rate}Hz a click at ${atMs}ms is in bucket $loudest, and ${atMs}ms is bucket ${atMs / bucketMs}"
            )
        }
    }

    // ---- Two rates agree about where everything is --------------------------
    //
    // The same clicks decoded at two rates must give the same bucket for each,
    // which is the whole of what auto-sync leans on.
    run {
        val clicks = intArrayOf(500, 7_300, 21_040, 38_990)
        val a = WaveformBuilder.envelope(signal(8_000, 45_000, *clicks), bucketMs)
        val b = WaveformBuilder.envelope(signal(8_820, 45_000, *clicks), bucketMs)
        clicks.forEach { at ->
            val bucket = at / bucketMs
            val inA = a.indices.filter { a[it] > 0f }
            val inB = b.indices.filter { b[it] > 0f }
            check(bucket in inA, "at 8000Hz the click at ${at}ms is not in bucket $bucket: $inA")
            check(bucket in inB, "at 8820Hz the click at ${at}ms is not in bucket $bucket: $inB")
        }
    }

    // ---- The shape of the thing ---------------------------------------------
    run {
        val env = WaveformBuilder.envelope(signal(8_000, 1_000, 500), bucketMs)
        check(env.size == 100, "a second at 10ms buckets is ${env.size} buckets, want 100")
        // Mean-removed, so the correlation is about the shape and not the level.
        check(kotlin.math.abs(env.sum()) < 1e-3f, "the envelope is not mean-removed: sum ${env.sum()}")
        check(WaveformBuilder.envelope(MonoPcm(FloatArray(0), 8_000), bucketMs).isEmpty(), "an empty signal gave buckets")
        check(WaveformBuilder.envelope(signal(8_000, 1_000), 0).isEmpty(), "a bucket of no length gave buckets")
        // A bucket longer than the signal is no buckets, not one short one.
        check(WaveformBuilder.envelope(signal(8_000, 5), 10).isEmpty(), "a signal shorter than a bucket gave buckets")
    }

    println("envelope: ${WaveformBuilder.envelope(signal(8_820, 45_000, 30_000), 10).size} buckets of a 45 s take at 8,820 Hz")
    if (problems.isEmpty()) println("PASS - a bucket begins where the clock says, at every rate, so two takes line up")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
