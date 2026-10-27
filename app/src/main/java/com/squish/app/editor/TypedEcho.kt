package com.squish.app.editor

/**
 * When a text field that keeps its own copy of the words should take the
 * words the edit holds. Kept free of the framework so
 * tools/jvm/TypedEchoChecks.kt runs it.
 *
 * The edit's copy comes back a recomposition or more behind the keys, so during
 * quick typing it is an *older* value than the field's. Following it wrote the
 * field back over the letters just typed, with the cursor at 0: on the moto
 * "Handmade with love" came out "Handde wthlov" (the Edit sheet, fixed then),
 * and on 27 Oct " friends" typed into an "Edit all" row came out
 * "dsGood morning, frie" - the rows had kept the old rule. A value this field
 * sent in the last [windowMs] is its own echo and is ignored; anything else
 * (an undo, a restyle, a translation) is followed.
 */
class TypedEcho(private val windowMs: Long = 1_500L) {
    /**
     * What was sent and when, oldest first, repeats kept: echoes come back in
     * the order they went, so each one consumes itself and everything before it.
     * A value whose echo has been and gone is then a change again - an undo
     * back to "a" after typing "a", "ab", Backspace, "c" was taken for an echo
     * while any "a" sent in the last moment was remembered (review, 27 Oct).
     */
    private val sent = ArrayDeque<Pair<String, Long>>()

    /** The field handed [text] to the edit at [nowMs]. */
    fun sent(text: String, nowMs: Long) {
        sent.addLast(text to nowMs)
    }

    /** Whether a field showing [fieldText] should take [editText], the edit's words at [nowMs]. */
    fun follows(editText: String, fieldText: String, nowMs: Long): Boolean {
        while (sent.isNotEmpty() && nowMs - sent.first().second > windowMs) sent.removeFirst()
        val echo = sent.indexOfFirst { it.first == editText }
        if (echo >= 0) {
            repeat(echo + 1) { sent.removeFirst() }
            return false
        }
        return editText != fieldText
    }
}
