import java.io.File
import kotlin.system.exitProcess

/*
 * Every field a draft writes is a field a draft reads.
 *
 * `ProjectAutosave` is the one file in the app where a mistake costs someone
 * work rather than a frame: write a field and forget to read it and the setting
 * is silently gone the next time the project is opened, with nothing on screen
 * to say so and no way to get it back. It is also too Android-coupled to run on
 * the JVM - Context, files, org.json - so the writer and the reader are read as
 * text instead and their key lists compared.
 *
 * Crude, and it catches the thing that actually happens. A new field is added to
 * the model, written in `toJson`, and the branch that reads it back is written
 * in the next sitting and never is.
 */
fun main() {
    val problems = mutableListOf<String>()
    val src = File("app/src/main/java/com/squish/app/data/ProjectAutosave.kt")
    if (!src.isFile) { println("FAIL - ProjectAutosave.kt is not there"); exitProcess(1) }
    val text = src.readText()

    val written = Regex("""put\("([A-Za-z0-9_]+)"""").findAll(text).map { it.groupValues[1] }.toSortedSet()
    val read = Regex("""(?:opt[A-Za-z]*|get[A-Za-z]*|has)\("([A-Za-z0-9_]+)"""")
        .findAll(text).map { it.groupValues[1] }.toSortedSet()

    /**
     * Written here and read through a name this check cannot see: `versionIn`
     * builds "snapshot" + "Fingerprint" at run time, so the literal never
     * appears beside an opt call.
     */
    val readByPrefix = setOf(
        "snapshotFingerprint", "snapshotSavedAtMillis",
        "pendingFingerprint", "pendingSavedAtMillis"
    )

    /**
     * Read and no longer written: fields of older drafts, kept so a project
     * saved by an earlier build still opens with everything it had. A name
     * leaves this list only when drafts that old are no longer supported.
     */
    val legacy = setOf(
        "brightness", "contrast", "saturation", // the one grade, before it was per clip
        "look",                                 // one look for the edit, before per-clip Filters
        "quality",                              // the export quality enum, before the three chips
        "speedPoints",                          // a flat speed, before the curve
        "voiceEffect"                           // one voice for the edit, before per-clip
    )

    (written - read - readByPrefix).forEach {
        problems += "\"$it\" is written into a draft and never read back - whatever it holds is lost on reopening"
    }
    (read - written - legacy).forEach {
        problems += "\"$it\" is read from a draft and never written - either it is a field of an older build, " +
            "in which case say so in this check's `legacy` list, or the writer has lost it"
    }
    // A list that has gone stale is a list that stops saying anything.
    (readByPrefix - written).forEach { problems += "\"$it\" is in readByPrefix and nothing writes it any more" }
    (legacy - read).forEach { problems += "\"$it\" is in the legacy list and nothing reads it any more" }
    if (written.size < 150) problems += "only ${written.size} fields written - has the parsing rotted?"

    println("draft keys: ${written.size} written, ${read.size} read")
    if (problems.isEmpty()) println("PASS - every field a draft writes is read back, and every field read is written or named as an old one")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
