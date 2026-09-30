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
    if (problems.isEmpty()) println("TranslateChunkChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
