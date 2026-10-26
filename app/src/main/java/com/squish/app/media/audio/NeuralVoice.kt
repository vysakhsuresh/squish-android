package com.squish.app.media.audio

import android.content.Context
import com.squish.app.online.Online
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream

/**
 * Voices a person downloads once and that then speak on the phone
 * (EDGE_PLAN E5): AI4Bharat's Indic-TTS voices (MIT), each a FastPitch and a
 * HiFi-GAN turned into three TensorFlow Lite models by tools/voices/convert.py
 * and published on this app's own GitHub release. Fetched through the online
 * gate's one door, the size said first; nothing about the person is sent.
 */
object NeuralVoices {

    data class Language(
        val code: String,
        val label: String,
        /** The BCP-47 tag a caption language is matched on. */
        val tag: String,
        val approxMb: Int,
        val url: String
    ) {
        fun dir(context: Context) = File(File(context.filesDir, "voices"), code)
        fun installed(context: Context): Boolean = File(dir(context), "voice.json").isFile &&
            listOf("encoder.tflite", "decoder.tflite", "vocoder.tflite").all { File(dir(context), it).length() > 0L }
    }

    private const val RELEASE = "https://github.com/vysakhsuresh/squish-android/releases/download/voices-v1"

    val all = listOf(
        Language("en", "English (Indian)", "en", 0, "$RELEASE/voice-en.zip"),
        Language("hi", "Hindi", "hi", 0, "$RELEASE/voice-hi.zip"),
        Language("ml", "Malayalam", "ml", 0, "$RELEASE/voice-ml.zip")
    )

    fun byCode(code: String?): Language? = all.firstOrNull { it.code == code }

    /** The installed voice for [languageTag] ("ml-IN", "hi"), or null. */
    fun forLanguage(context: Context, languageTag: String?): Language? {
        val base = languageTag?.substringBefore('-')?.lowercase() ?: return null
        return all.firstOrNull { it.tag == base && it.installed(context) }
    }

    /**
     * Fetches [language]'s voice and unpacks it, only into its own folder and
     * only the files it should hold - a zip naming "../" anything is refused.
     */
    suspend fun download(context: Context, language: Language, onProgress: (Long, Long) -> Unit) {
        val zip = File(context.cacheDir, "voice-${language.code}.zip")
        Online.download(context, language.url, zip, onProgress)
        withContext(Dispatchers.IO) {
            val dir = language.dir(context)
            val part = File(dir.parentFile, "${language.code}.part").apply { deleteRecursively(); mkdirs() }
            val allowed = setOf("encoder.tflite", "decoder.tflite", "vocoder.tflite", "voice.json")
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in allowed) continue
                    File(part, name).outputStream().use { z.copyTo(it) }
                }
            }
            check(allowed.all { File(part, it).length() > 0L }) { "the voice download is missing a part" }
            dir.deleteRecursively()
            check(part.renameTo(dir)) { "could not put the voice in place" }
            zip.delete()
        }
        NeuralVoice.forget(language.code)
    }

    fun remove(context: Context, language: Language) {
        NeuralVoice.forget(language.code)
        language.dir(context).deleteRecursively()
    }
}

/** One downloaded voice, loaded: its three models and what voice.json says of them. */
class NeuralVoice private constructor(dir: File) : Closeable {

    private val meta = JSONObject(File(dir, "voice.json").readText())
    val symbols: List<String> = meta.getJSONArray("symbols").let { a -> List(a.length()) { a.getString(it) } }
    /** Speaker names to ids ("female", "male"). */
    val speakers: Map<String, Int> = meta.optJSONObject("speakers")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }.orEmpty()
    private val maxChars = meta.getInt("maxChars")
    private val maxFrames = meta.getInt("maxFrames")
    private val vocoderFrames = meta.getInt("vocoderFrames")
    private val hop = meta.getInt("hop")
    val sampleRate: Int = meta.getInt("sampleRate")
    private val channels = meta.getInt("channels")
    private val lowercase = meta.optBoolean("lowercase", true)
    /** Coqui's own character-to-id table, written by the converter; the list read as Coqui reads it otherwise. */
    private val idOf: Map<String, Int> = meta.optJSONObject("ids")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: TtsRules.idsOf(symbols)

    private fun open(f: File): Interpreter {
        val buffer = FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        return Interpreter(buffer, Interpreter.Options().setNumThreads(THREADS))
    }

    private val encoder = open(File(dir, "encoder.tflite"))
    private val decoder = open(File(dir, "decoder.tflite"))
    private val vocoder = open(File(dir, "vocoder.tflite"))

    /**
     * [text] spoken by [speaker] (an id from [speakers]) at [lengthScale]
     * (above 1 slower), as mono floats at [sampleRate]: sentence by sentence,
     * with a breath between them.
     */
    suspend fun speak(text: String, speaker: Int, lengthScale: Float = 1f): FloatArray = withContext(Dispatchers.Default) {
        val pieces = ArrayList<FloatArray>()
        val gap = FloatArray((sampleRate * GAP_S).toInt())
        for (sentence in TtsRules.sentences(text, maxChars)) {
            currentCoroutineContext().ensureActive()
            val ids = TtsRules.ids(sentence, idOf, lowercase)
            if (ids.isEmpty()) continue
            pieces += voice(ids, speaker, lengthScale)
            pieces += gap
        }
        val total = pieces.sumOf { it.size }
        FloatArray(total).also { out -> var at = 0; pieces.forEach { p -> p.copyInto(out, at); at += p.size } }
    }

    private fun voice(ids: IntArray, speaker: Int, lengthScale: Float): FloatArray {
        val t = ids.size
        // The encoder: ids, mask and speaker in; features [1,T,C] and log durations [1,T] out.
        val idsIn = intBuffer(maxChars).apply { asIntBuffer().put(ids) }
        val idsValues = IntArray(maxChars).also { ids.copyInto(it) }
        val maskIn = floatBuffer(maxChars).apply { asFloatBuffer().put(FloatArray(t) { 1f }) }
        val speakerIn = intBuffer(1).apply { asIntBuffer().put(speaker) }
        val speakerValues = intArrayOf(speaker)
        val featsOut = floatBuffer(maxChars * channels)
        val durOut = floatBuffer(maxChars)
        run(encoder, listOf(Input(idsIn, 2, true, idsValues), Input(maskIn, 2, false), Input(speakerIn, 1, true, speakerValues)), listOf(featsOut to 3, durOut to 2))
        val feats = FloatArray(t * channels).also { featsOut.rewind(); featsOut.asFloatBuffer().get(it) }
        val logDur = FloatArray(t).also { durOut.rewind(); durOut.asFloatBuffer().get(it) }
        var durations = TtsRules.durations(logDur, lengthScale)
        // A sentence held longer than one decoder call is sped up to fit rather than cut.
        val n0 = durations.sum()
        if (n0 > maxFrames) durations = TtsRules.durations(logDur, lengthScale * maxFrames.toFloat() / n0 * 0.98f)
        val expanded = TtsRules.expand(feats, t, channels, durations)
        val n = minOf(durations.sum(), maxFrames)

        // The decoder: features [1,N,C], mask, speaker in; mel [1,N,80] out.
        val fIn = floatBuffer(maxFrames * channels).apply { asFloatBuffer().put(expanded, 0, n * channels) }
        val yMask = floatBuffer(maxFrames).apply { asFloatBuffer().put(FloatArray(n) { 1f }) }
        speakerIn.rewind()
        val melOut = floatBuffer(maxFrames * MELS)
        run(decoder, listOf(Input(fIn, 3, false), Input(yMask, 2, false), Input(speakerIn, 1, true, speakerValues)), listOf(melOut to 3))
        val mel = FloatArray(n * MELS).also { melOut.rewind(); melOut.asFloatBuffer().get(it) }

        // The vocoder, chunk by chunk ([1,80,M] in, frames by band), faded into each other.
        val starts = TtsRules.chunkStarts(n, vocoderFrames, OVERLAP)
        val chunks = starts.map { s ->
            val chunk = FloatArray(MELS * vocoderFrames)
            for (f in 0 until vocoderFrames) {
                val frame = s + f
                if (frame >= n) break
                for (b in 0 until MELS) chunk[b * vocoderFrames + f] = mel[frame * MELS + b]
            }
            val inBuf = floatBuffer(chunk.size).apply { asFloatBuffer().put(chunk) }
            val outBuf = floatBuffer(vocoderFrames * hop)
            vocoder.run(inBuf, outBuf)
            FloatArray(vocoderFrames * hop).also { outBuf.rewind(); outBuf.asFloatBuffer().get(it) }
        }
        return TtsRules.stitch(chunks, starts.map { it * hop }, OVERLAP * hop, n * hop)
    }

    /** An input: its buffer, rank and kind - and, for an integer, its values, so it can be handed over as the 32- or 64-bit integers the model was written with. */
    private class Input(val buffer: ByteBuffer, val rank: Int, val integer: Boolean, val values: IntArray? = null)

    /**
     * Runs [interpreter], each input put where a tensor of its rank and kind
     * is, each output read from the one of its rank: a converter is free to
     * number them in any order, and DtlnTflite learned not to assume one.
     */
    private fun run(interpreter: Interpreter, inputs: List<Input>, outputs: List<Pair<ByteBuffer, Int>>) {
        val ins = arrayOfNulls<Any>(interpreter.inputTensorCount)
        val taken = BooleanArray(inputs.size)
        for (i in ins.indices) {
            val tensor = interpreter.getInputTensor(i)
            val integer = tensor.dataType() != org.tensorflow.lite.DataType.FLOAT32
            val k = inputs.indices.firstOrNull { !taken[it] && inputs[it].rank == tensor.shape().size && inputs[it].integer == integer }
                ?: error("voice model input $i ${tensor.shape().toList()} has no match")
            taken[k] = true
            val given = inputs[k]
            ins[i] = if (given.integer && tensor.dataType() == org.tensorflow.lite.DataType.INT64 && given.values != null) {
                ByteBuffer.allocateDirect(given.values.size * 8).order(ByteOrder.nativeOrder()).apply { asLongBuffer().put(LongArray(given.values.size) { given.values[it].toLong() }) }
            } else {
                given.buffer.rewind()
                given.buffer
            }
        }
        val outs = HashMap<Int, Any>()
        val used = BooleanArray(outputs.size)
        for (o in 0 until interpreter.outputTensorCount) {
            val rank = interpreter.getOutputTensor(o).shape().size
            val k = outputs.indices.firstOrNull { !used[it] && outputs[it].second == rank } ?: continue
            used[k] = true
            outputs[k].first.rewind()
            outs[o] = outputs[k].first
        }
        interpreter.runForMultipleInputsOutputs(ins, outs)
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { vocoder.close() }
    }

    companion object {
        private const val MELS = 80
        private const val OVERLAP = 16
        private const val GAP_S = 0.18
        private const val THREADS = 4

        private val loaded = HashMap<String, NeuralVoice>()

        /** The voice in [language]'s folder, opened once and kept. */
        @Synchronized
        fun of(context: Context, language: NeuralVoices.Language): NeuralVoice? {
            loaded[language.code]?.let { return it }
            if (!language.installed(context)) return null
            return runCatching { NeuralVoice(language.dir(context)) }.getOrNull()?.also { loaded[language.code] = it }
        }

        @Synchronized
        fun forget(code: String) {
            loaded.remove(code)?.close()
        }

        private fun floatBuffer(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        private fun intBuffer(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

        /** [samples] at [rate] as a 16-bit mono WAV in [out]. */
        fun writeWav(samples: FloatArray, rate: Int, out: File) {
            DataOutputStream(out.outputStream().buffered()).use { o ->
                fun le32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
                fun le16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
                val data = samples.size * 2
                o.writeBytes("RIFF"); le32(36 + data); o.writeBytes("WAVE")
                o.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
                o.writeBytes("data"); le32(data)
                for (s in samples) le16((s.coerceIn(-1f, 1f) * 32767f).toInt())
            }
        }
    }
}
