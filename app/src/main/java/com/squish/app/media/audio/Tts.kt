package com.squish.app.media.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads a line aloud into a file, with the phone's own text-to-speech engine,
 * so a caption can be given a voice that lands as a sound clip where the
 * caption starts.
 *
 * On the device, as everything here is: the engine is whatever the phone has
 * installed, and a phone with none says so rather than sending the words away.
 */
object Tts {

    /**
     * Speaks [text] into [out] as a WAV. False when the phone has no engine,
     * the engine failed, or it never answered - the caller says so in a card.
     */
    suspend fun synthesize(context: Context, text: String, languageTag: String?, out: File): Boolean {
        val ok = withTimeoutOrNull(TIMEOUT_MS) {
            // A Looper-bound component: made and driven on the main thread,
            // where its callbacks arrive.
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    var engine: TextToSpeech? = null
                    var settled = false
                    fun finish(result: Boolean) {
                        if (settled) return
                        settled = true
                        runCatching { engine?.shutdown() }
                        if (continuation.isActive) continuation.resume(result)
                    }
                    val made = TextToSpeech(context.applicationContext) { status ->
                        val tts = engine
                        if (status != TextToSpeech.SUCCESS || tts == null) {
                            finish(false)
                            return@TextToSpeech
                        }
                        // The edit's caption language when the engine has it;
                        // otherwise the engine's own, which is the phone's.
                        languageTag?.let { Locale.forLanguageTag(it) }?.let { locale ->
                            val supported = tts.isLanguageAvailable(locale)
                            if (supported >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
                        }
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) = finish(out.length() > 0L)
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = finish(false)
                            override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
                        })
                        val queued = tts.synthesizeToFile(text, Bundle(), out, UTTERANCE)
                        if (queued != TextToSpeech.SUCCESS) finish(false)
                    }
                    engine = made
                    // With no engine installed the constructor answers ERROR
                    // before it returns, so the answer came with nothing to
                    // shut down and each attempt left a client holding the
                    // application context.
                    if (settled) runCatching { made.shutdown() }
                    continuation.invokeOnCancellation {
                        runCatching { engine?.stop() }
                        runCatching { engine?.shutdown() }
                    }
                }
            }
        }
        if (ok != true) out.delete()
        return ok == true
    }

    private const val UTTERANCE = "squish-speech"

    /** An engine that never answers - no file, no error - must not hold the editor. */
    private const val TIMEOUT_MS = 40_000L
}
