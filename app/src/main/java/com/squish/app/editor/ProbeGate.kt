package com.squish.app.editor

/**
 * Which questions to put to a slow oracle, and which of its answers to keep,
 * when the question can change while an answer is on its way.
 *
 * The export sheet asks the phone's encoder what it will write for the size
 * chosen (EncoderCeiling), which opens the codec list and takes a moment. Tap
 * 1080p and then 4K before the first answer lands, and the answers can come
 * back in either order: the 1080p answer landing last used to be kept as the
 * answer for 4K, so the sheet promised a size and weight the encoder would not
 * write - the very thing the ceiling exists to stop. Nothing asked again,
 * because "an answer exists" was the whole test.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/ProbeGateChecks.kt).
 *
 * @param T the question - a frame size.
 * @param answered whether the question already has an answer kept.
 */
class ProbeGate<T : Any>(private val answered: (T) -> Boolean) {

    /** The question last sent, until its answer comes back; there is never a reason to send it twice. */
    var pending: T? = null
        private set

    /** Whether [question] should be sent now: not answered already, and not already on its way. */
    fun ask(question: T): Boolean {
        if (answered(question) || pending == question) return false
        pending = question
        return true
    }

    /**
     * An answer to [question] has come back with [current] the question now
     * being asked on screen. True when the answer is for the current question
     * and should be kept; an answer to a question no longer asked is dropped,
     * because keeping it would answer the current question wrongly, and the
     * current question was sent its own probe when it was asked.
     */
    fun keep(question: T, current: T): Boolean {
        if (pending == question) pending = null
        return question == current
    }
}
