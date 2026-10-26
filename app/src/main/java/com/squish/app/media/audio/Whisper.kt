package com.squish.app.media.audio

import android.content.Context
import com.squish.app.online.Online
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp's JNI entry points (tools/native/whisper/squishwhisper.cpp).
 * Loaded on first use, not at start: most sessions never caption, and a phone
 * whose build lacks the library (it is fetched into jniLibs from the whisper
 * workflow) simply has no Whisper - [loaded] says so.
 */
object WhisperNative {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("squishwhisper") }.isSuccess }

    @JvmStatic external fun open(modelPath: String): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun transcribe(handle: Long, pcm16k: FloatArray, language: String, threads: Int): String?

    /** The same as raw bytes (WhisperText.parseBytes says why); not in a library built before 26 Oct. */
    @JvmStatic external fun transcribeBytes(handle: Long, pcm16k: FloatArray, language: String, threads: Int): ByteArray?

    // The library also has transcribePrompted (a starting line in the
    // language's script). It is not called: a Malayalam prompt did not move
    // the small model off Devanagari on the moto and made it slower, so the
    // script is put right afterwards instead (WhisperText.inScriptOf).

    /** Whether the library has [transcribeBytes]; found out on the first call. */
    @Volatile private var hasBytes = true

    /**
     * The words, read through the byte surface where the library has it (the
     * first, string one's NewStringUTF aborts on a character cut in two), and
     * carried into the script of the language asked for - or, under "auto",
     * of the language heard.
     */
    fun words(handle: Long, pcm16k: FloatArray, language: String, threads: Int, offsetMs: Long): WhisperText.Result? {
        val result = run {
            if (hasBytes) {
                try {
                    return@run WhisperText.parseBytes(transcribeBytes(handle, pcm16k, language, threads), offsetMs)
                } catch (_: UnsatisfiedLinkError) {
                    hasBytes = false
                }
            }
            WhisperText.parse(transcribe(handle, pcm16k, language, threads), offsetMs)
        } ?: return null
        return result.inScript(if (language == "auto") result.language else language)
    }
}

/**
 * The speech models a person can download once (EDGE_PLAN E1): whisper.cpp's
 * quantised multilingual ggml files, MIT like Whisper's own weights, fetched
 * only - nothing about the person or their video is sent. Kept under
 * files/models; the size is said before anything is fetched.
 */
object WhisperModels {

    data class Model(val id: String, val label: String, val approxMb: Int, val url: String, val note: String) {
        fun file(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, "$id.bin")
    }

    val BASE = Model(
        "ggml-base-q5_1", "Standard", 57,
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
        "Good for clear English and the major languages; quick."
    )
    val SMALL = Model(
        "ggml-small-q5_1", "Better", 181,
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        "Noticeably better for Hindi, Malayalam and other Indian languages; slower."
    )
    val all = listOf(BASE, SMALL)

    /** The best model already on the phone, or null. */
    fun installed(context: Context): Model? = all.reversed().firstOrNull { it.file(context).length() > 1_000_000L }

    /** Fetches [model] through the Online gate's one door; throws while online features are off. */
    suspend fun download(context: Context, model: Model, onProgress: (Long, Long) -> Unit): File =
        Online.download(context, model.url, model.file(context), onProgress)
}

/**
 * Speech to timed words with whisper.cpp, on the device (EDGE_PLAN E1). The
 * system recognizer (Transcriber) stays the fast path where it exists; this
 * is what every other phone uses, and the one that gives each word its time.
 */
object WhisperTranscriber {

    private val lock = Mutex()
    private var handle = 0L
    private var openFor: String? = null

    fun isReady(context: Context): Boolean = WhisperNative.loaded && WhisperModels.installed(context) != null

    /**
     * The words of [segment] of [pcm], their times on the file's own clock.
     * [languageTag] is a BCP-47 tag or null for automatic. Null when there is
     * no model, no library, or whisper.cpp failed.
     */
    suspend fun transcribe(context: Context, pcm: MonoPcm, segment: SpeechSegment, languageTag: String?): WhisperText.Result? =
        withContext(Dispatchers.Default) {
            if (!WhisperNative.loaded) return@withContext null
            val model = WhisperModels.installed(context) ?: return@withContext null
            val slice = resampled16k(pcm, segment.startMs, segment.endMs) ?: return@withContext null
            lock.withLock {
                if (handle == 0L || openFor != model.id) {
                    if (handle != 0L) WhisperNative.close(handle)
                    handle = WhisperNative.open(model.file(context).absolutePath)
                    openFor = if (handle != 0L) model.id else null
                }
                if (handle == 0L) return@withLock null
                val lang = languageTag?.substringBefore('-')?.lowercase()?.takeIf { it.length in 2..3 } ?: "auto"
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                WhisperNative.words(handle, slice, lang, threads, offsetMs = segment.startMs)
            }
        }

    /** Lets the model go, for when the captions are done: it is tens of megabytes held. */
    suspend fun release() = lock.withLock {
        if (handle != 0L) WhisperNative.close(handle)
        handle = 0L
        openFor = null
    }

    /** [pcm] from [fromMs] to [toMs], linearly resampled to the 16 kHz Whisper wants. */
    private fun resampled16k(pcm: MonoPcm, fromMs: Long, toMs: Long): FloatArray? {
        val rate = pcm.sampleRate
        if (rate <= 0) return null
        val start = (fromMs * rate / 1000).toInt().coerceIn(0, pcm.samples.size)
        val end = (toMs * rate / 1000).toInt().coerceIn(start, pcm.samples.size)
        if (end - start < rate / 10) return null
        val outCount = ((end - start).toLong() * 16_000 / rate).toInt()
        val out = FloatArray(outCount)
        val step = rate / 16_000.0
        for (i in 0 until outCount) {
            val pos = start + i * step
            val a = pos.toInt().coerceAtMost(end - 1)
            val b = (a + 1).coerceAtMost(end - 1)
            val f = (pos - a).toFloat()
            out[i] = pcm.samples[a] * (1 - f) + pcm.samples[b] * f
        }
        return out
    }
}
