import kotlin.system.exitProcess
val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun main() {
    val short = com.squish.app.online.OnlineTranslate.chunks("hello world")
    check(short == listOf("hello world" to false), "a short line was split: $short")
    val hindi = "नमस्ते दुनिया ".repeat(40)
    val pieces = com.squish.app.online.OnlineTranslate.chunks(hindi)
    check(pieces.all { it.first.toByteArray().size <= com.squish.app.online.OnlineTranslate.MAX_BYTES }, "a piece went over the limit")
    check(pieces.joinToString(" ") { it.first } == hindi.trim(), "words were lost or cut")
    val emoji = "😀".repeat(200)
    val ep = com.squish.app.online.OnlineTranslate.chunks(emoji)
    check(ep.joinToString("") { it.first } == emoji, "an emoji run did not join back whole")
    check(ep.all { p -> p.first.isEmpty() || !Character.isLowSurrogate(p.first[0]) }, "an emoji was cut in half")
    check(ep.drop(1).all { it.second }, "pieces of one word were not marked as continuing it")
    // The pick, on the service's real answers (fetched 30 Sep 2026).
    val T = com.squish.app.online.OnlineTranslate
    fun c(t: String, q: Int, m: Double) = com.squish.app.online.OnlineTranslate.Candidate(t, q, m)
    val bigNews = T.pick("BIG NEWS", "¡Uups!", listOf(c("¡Uups!", 0, 0.98), c("¡GRANDES NOTICIAS!&#10;", 74, 0.97), c("¡MUY BUENAS NOTICIAS! ", 74, 0.97)))
    check(bigNews == "¡GRANDES NOTICIAS!", "BIG NEWS picked $bigNews")
    val big = T.pick("Big news", "Excelentes noticias.", listOf(c("Excelentes noticias.", 0, 0.98), c("¡Grandes noticias!", 74, 0.98)))
    check(big == "¡Grandes noticias!", "Big news picked $big")
    val thanks = T.pick("Thanks for watching", "Gracias por ver el vídeo ", listOf(c("Gracias por ver el vídeo ", 74, 1.0), c("Gracias por tu atención.", 0, 0.99)))
    check(thanks == "Gracias por ver el vídeo", "Thanks for watching picked $thanks")
    // Nothing reviewed, or nothing close: the service's own answer, tidied.
    check(T.pick("hello there", "hola&#39;s ", listOf(c("x", 74, 0.5))) == "hola's", "a far match was taken over the top answer")
    check(T.pick("hello", null, emptyList()) == null && T.pick("hello", "   ", emptyList()) == null, "an empty answer was not refused")
    check(T.pick("OK GO", "vamos", emptyList()) == "VAMOS", "a line in capitals lost them")
    check(T.pick("I", "yo", emptyList()) == "yo", "one capital letter read as shouting")
    check(T.pick("a", "uno&#10;dos", emptyList()) == "uno dos", "a line break in an answer split the line")
    if (problems.isEmpty()) println("TranslateChunkChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
