import com.squish.app.editor.TypedEcho
import kotlin.system.exitProcess

/*
 * A field with its own copy of the words, and the edit's copy coming back
 * behind the keys (27 Oct, the moto: " friends" typed into an "Edit all" row
 * came out "dsGood morning, frie"). A model of the round trip: each key is
 * handed to the edit, and the edit's words reach the field `lag` keys later.
 * Whenever TypedEcho says follow, the field takes the edit's words with the
 * cursor at the end - or at 0, as the rows did - and the next key goes there.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun typeWith(start: String, keys: String, lag: Int, msPerKey: Long, cursorAtStartOnFollow: Boolean): String {
    val echo = TypedEcho()
    var field = start
    var cursor = start.length
    val handed = ArrayList<String>()
    var now = 0L
    var edit = start
    fun arrive(t: String) {
        edit = t
        if (echo.follows(edit, field, now)) { field = edit; cursor = if (cursorAtStartOnFollow) 0 else edit.length }
    }
    for (k in keys) {
        now += msPerKey
        field = field.substring(0, cursor) + k + field.substring(cursor); cursor++
        echo.sent(field, now); handed += field
        if (handed.size > lag) arrive(handed[handed.size - 1 - lag])
    }
    // The rest arrive once the keys stop.
    for (i in (handed.size - lag).coerceAtLeast(0) until handed.size) { now += 16; arrive(handed[i]) }
    return field
}

fun main() {
    for (lag in 0..4) {
        val got = typeWith("Good morning,", " friends", lag, msPerKey = 40, cursorAtStartOnFollow = true)
        check(got == "Good morning, friends", "typed with the edit $lag keys behind: \"$got\"")
    }
    // Slow typing: each echo back a frame after its key, long before the next.
    check(typeWith("Hi", " there", 0, msPerKey = 2_000, cursorAtStartOnFollow = true) == "Hi there", "slow typing lost letters")

    // A change from elsewhere is followed: an undo back to the words before typing.
    val echo = TypedEcho()
    echo.sent("Good morning, f", 100); echo.sent("Good morning, fr", 140)
    check(!echo.follows("Good morning, f", "Good morning, fr", 150), "the field's own echo was followed")
    check(echo.follows("Bonjour,", "Good morning, fr", 160), "a translation landing while typing was not followed")
    check(echo.follows("Good morning,", "Good morning, fr", 3_000), "an undo after typing was not followed")
    check(!TypedEcho().follows("same", "same", 0), "the same words were 'followed'")

    // An undo back to a value the field itself sent a moment ago (review, 27 Oct):
    // "a", "ab", Backspace ("a" again), "ac" - each echoed in turn - then Undo to "a".
    run {
        val e = TypedEcho()
        var t = 0L
        for (s in listOf("a", "ab", "a", "ac")) e.sent(s, t++)
        for (s in listOf("a", "ab", "a", "ac")) check(!e.follows(s, "ac", t++), "its own echo \"$s\" was followed")
        check(e.follows("a", "ac", t++), "an undo to \"a\", once every echo was home, was taken for an echo")
    }
    // The same keys with the echoes lagging two behind: the repeated "a" must not
    // swallow the "ab" still on its way.
    run {
        val e = TypedEcho()
        e.sent("a", 0); e.sent("ab", 1)
        check(!e.follows("a", "ab", 2), "the first echo was followed")
        e.sent("a", 3)
        check(!e.follows("ab", "a", 4), "an echo still on its way was followed after a repeated value")
        check(!e.follows("a", "a", 5), "the repeated value's echo was followed")
    }

    if (problems.isEmpty()) println("TypedEchoChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
