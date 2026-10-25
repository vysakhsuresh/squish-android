package com.squish.app.media.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Voice only and Remove voice rendered to a file (EDGE_PLAN G3): a window of
 * a clip's sound read at its own rate, its voice found by DTLN at 16 kHz and
 * brought back to that rate, and the two mixed at the sheet's level
 * ([VoiceSplit]). A mono 16-bit WAV under files/voicesplit, which the edit
 * lands as the clip's sound - as Extract audio and Reverse land theirs.
 */
object VoiceSplitter {

    private const val TAG = "SquishVoiceSplit"

    sealed interface Result {
        class Done(val file: File, val spanMs: Long) : Result
        object TooLong : Result
        object NoSound : Result
        object Failed : Result
    }

    suspend fun render(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        mode: VoiceSplit.Mode,
        level: Float,
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.Default) {
        val span = toMs - fromMs
        if (span > VoiceSplit.MAX_MS) return@withContext Result.TooLong
        if (span <= 0L) return@withContext Result.NoSound
        val pcm = PcmDecoder.decodeMono(context, uri, targetSampleRate = 48_000, maxDurationMs = span, fromMs = fromMs)
            ?: return@withContext Result.NoSound
        currentCoroutineContext().ensureActive()
        val network = DtlnTflite.open(context) ?: return@withContext Result.Failed
        try {
            val rate = pcm.sampleRate
            val original = pcm.samples
            onProgress(0.05f)
            val t0 = System.nanoTime()
            val down = VoiceSplit.resample(original, rate, VoiceSplit.MODEL_RATE)
            currentCoroutineContext().ensureActive()
            val t1 = System.nanoTime()
            val voice16 = VoiceSplit.voiceOf(down, network) { onProgress(0.1f + 0.8f * it) }
            currentCoroutineContext().ensureActive()
            val t2 = System.nanoTime()
            val up = VoiceSplit.resample(voice16, VoiceSplit.MODEL_RATE, rate)
            android.util.Log.i(TAG, "down ${(t1 - t0) / 1_000_000} ms, network ${(t2 - t1) / 1_000_000} ms, up ${(System.nanoTime() - t2) / 1_000_000} ms")
            val voice = FloatArray(original.size) { up.getOrElse(it) { 0f } }
            val mixed = VoiceSplit.mix(original, voice, mode, level)
            val dir = File(context.filesDir, "voicesplit").apply { mkdirs() }
            val out = File(dir, "${mode.name.lowercase()}_${System.currentTimeMillis()}.wav")
            writeWav(out, mixed, rate)
            onProgress(1f)
            Result.Done(out, original.size * 1000L / rate)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("SquishVoiceSplit", "splitting $uri", t)
            Result.Failed
        } finally {
            network.close()
        }
    }

    /** Mono 16-bit PCM, written to a side file and moved into place, so a half-written one is never read. */
    private fun writeWav(out: File, samples: FloatArray, rate: Int) {
        val tmp = File(out.parentFile, out.name + ".part")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { o ->
            val dataBytes = samples.size * 2
            fun intLe(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun shortLe(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.writeBytes("RIFF"); intLe(36 + dataBytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); intLe(16); shortLe(1); shortLe(1); intLe(rate); intLe(rate * 2); shortLe(2); shortLe(16)
            o.writeBytes("data"); intLe(dataBytes)
            for (s in samples) shortLe((s.coerceIn(-1f, 1f) * 32767f).roundToInt())
        }
        if (!tmp.renameTo(out)) { tmp.delete(); error("could not write $out") }
    }
}
