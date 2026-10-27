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
    private val sent = LinkedHashMap<String, Long>()

    /** The field handed [text] to the edit at [nowMs]. */
    fun sent(text: String, nowMs: Long) {
        sent.remove(text)
        sent[text] = nowMs
    }

    /** Whether a field showing [fieldText] should take [editText], the edit's words at [nowMs]. */
    fun follows(editText: String, fieldText: String, nowMs: Long): Boolean {
        sent.entries.removeAll { nowMs - it.value > windowMs }
        return editText != fieldText && editText !in sent
    }
}
