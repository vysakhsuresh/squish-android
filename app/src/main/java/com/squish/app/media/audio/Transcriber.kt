package com.squish.app.media.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Turns a stretch of speech into words, on the device.
 *
 * **On-device only, deliberately.** `SpeechRecognizer.createSpeechRecognizer` is
 * free to send audio to a server, and this app's whole premise is that nothing
 * leaves the phone. So only `createOnDeviceSpeechRecognizer` is ever used, and when
 * a device has no on-device model installed the answer is "no transcription here",
 * not "we sent it away". That costs coverage and keeps the promise.
 *
 * Audio is fed one segment at a time rather than as a whole file, which is the only
 * shape that works: the recognizer is built for a single utterance and stops at the
 * first long pause. Since [SpeechSegmenter] has already found the utterances, one
 * call per segment fits it exactly - and the timings come from the segmenter, which
 * is more reliable than anything the recognizer reports.
 */
object Transcriber {

    /** The on-device recognizer landed in Android 13. Below that, there is none. */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
            .getOrDefault(false)
    }

    /**
     * Transcribes one segment. Returns null when nothing came back - a silent
     * stretch, an unsupported language, a device with no model - which the caller
     * turns into an empty caption card rather than an error.
     */
    suspend fun transcribe(
        context: Context,
        pcm: MonoPcm,
        segment: SpeechSegment,
        languageTag: String
    ): String? {
        if (!isAvailable(context)) return null

        val slice = sliceOf(pcm, segment) ?: return null

        // The recognizer is a Looper-bound component: it must be created and driven
        // from the main thread, and its callbacks arrive there.
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }.getOrNull()

                if (recognizer == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
                if (pipe == null) {
                    recognizer.destroy()
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val (readSide, writeSide) = pipe

                var settled = false
                fun finish(text: String?) {
                    if (settled) return
                    settled = true
                    runCatching { recognizer.destroy() }
                    runCatching { readSide.close() }
                    if (continuation.isActive) continuation.resume(text)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val best = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        finish(best?.takeIf { it.isNotEmpty() })
                    }

                    override fun onError(error: Int) = finish(null)

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                // The extras are written as their documented string names rather than
                // the RecognizerIntent constants. They mean exactly the same thing to
                // the recognizer, and a literal cannot fail to resolve against a
                // different platform version - which matters for the one part of this
                // app that is reaching into a rarely-used corner of the framework.
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra("android.speech.extra.AUDIO_SOURCE", readSide)
                    putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1)
                    putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT)
                    putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLE_RATE", pcm.sampleRate)
                }

                continuation.invokeOnCancellation { finish(null) }

                // Written on a worker: the pipe blocks once its buffer fills, and
                // blocking the main thread here would deadlock against the recognizer
                // that is meant to be draining it.
                Thread {
                    runCatching {
                        DataOutputStream(
                            ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                        ).use { out -> out.write(slice) }
                    }
                }.start()

                runCatching { recognizer.startListening(intent) }
                    .onFailure { finish(null) }
            }
        }
    }

    /**
     * The segment as little-endian 16-bit PCM, which is what the recognizer expects.
     * Returns null if the segment falls outside the decoded audio.
     */
    private fun sliceOf(pcm: MonoPcm, segment: SpeechSegment): ByteArray? {
        val rate = pcm.sampleRate
        if (rate <= 0) return null
        val from = (segment.startMs * rate / 1000L).toInt().coerceIn(0, pcm.samples.size)
        val to = (segment.endMs * rate / 1000L).toInt().coerceIn(from, pcm.samples.size)
        if (to - from < rate / 20) return null // under 50 ms is not a word

        val out = ByteArray((to - from) * 2)
        var j = 0
        for (i in from until to) {
            val v = (pcm.samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
