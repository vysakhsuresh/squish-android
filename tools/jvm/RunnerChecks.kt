import java.io.File
import kotlin.system.exitProcess

/*
 * The two runners have to list the same suites.
 *
 * `run.sh` is the sandbox's runner and `run_desktop.sh` the desktop's, and they
 * keep their own copies of every suite's file list because the sandbox has
 * kotlinc and the desktop drives the compiler out of the Gradle cache. Four
 * suites - animoptions, effectrecipes, musicsynth, synthtempo - had been added
 * to one and not the other, so the desktop, the only machine here that compiles
 * anything, had never run them. One of them (musicsynth) had stopped compiling
 * months before and said nothing, because the file that ran it was the file
 * nobody ran.
 *
 * A suite that nothing runs is worse than no suite: it reads as cover.
 */
fun main() {
    val problems = mutableListOf<String>()

    fun suites(path: String): Set<String> {
        val f = File(path)
        if (!f.isFile) { problems += "$path is gone"; return emptySet() }
        return Regex("""(?m)^run\s+([a-z0-9]+)""").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    }

    val sandbox = suites("tools/jvm/run.sh")
    val desktop = suites("tools/jvm/run_desktop.sh")

    (sandbox - desktop).sorted().forEach {
        problems += "\"$it\" is in run.sh and not in run_desktop.sh - the desktop never runs it"
    }
    (desktop - sandbox).sorted().forEach {
        problems += "\"$it\" is in run_desktop.sh and not in run.sh - the sandbox never runs it"
    }
    if (sandbox.size < 60) problems += "only ${sandbox.size} suites in run.sh - has the parsing rotted?"

    // Every suite named must end in a checks file that is actually there.
    listOf("tools/jvm/run.sh", "tools/jvm/run_desktop.sh").forEach { path ->
        Regex("""(?m)^run\s+[a-z0-9]+\s+(.*)$""").findAll(File(path).readText()).forEach { m ->
            Regex("""tools/jvm/[A-Za-z0-9/]+\.kt""").findAll(m.groupValues[1]).forEach { f ->
                if (!File(f.value).isFile) problems += "$path names ${f.value}, which is not there"
            }
        }
    }

    println("runners: run.sh and run_desktop.sh list ${sandbox.size} and ${desktop.size} suites")
    if (problems.isEmpty()) println("PASS - every suite is run on both machines, and every file named exists")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
