import java.io.File
import kotlin.system.exitProcess

/*
 * Every field a draft writes is a field a draft reads.
 *
 * A draft is the one place in the app where a mistake costs someone work rather
 * than a frame: write a field and forget to read it and the setting is silently
 * gone the next time the project is opened, with nothing on screen to say so
 * and no way to get it back. So the writer and the reader are read as text and
 * their key lists compared.
 *
 * Crude, and it catches the thing that actually happens. A new field is added to
 * the model, written in `toJson`, and the branch that reads it back is written
 * in the next sitting and never is.
 *
 * **All three files, and that is the whole lesson of this header.** It read
 * `ProjectAutosave.kt` alone, which was right while the codec lived there. The
 * codec then moved out in two cuts (`DraftCodec.kt`, then
 * `DraftClipCodec.kt`), and this check went on passing over the twenty-two
 * keys that were left - a tenth of what its own sentences claim - because
 * nothing about a shrinking scope looks like a failure. Only the "has the
 * parsing rotted?" guard at the bottom caught it. A check whose reach quietly
 * narrows is the same fault as a check nobody runs, arriving from a third
 * direction, and the guard is the only thing that sees it.
 */
fun main() {
    val problems = mutableListOf<String>()
    val paths = listOf(
        "app/src/main/java/com/squish/app/data/ProjectAutosave.kt",
        "app/src/main/java/com/squish/app/data/DraftCodec.kt",
        "app/src/main/java/com/squish/app/data/DraftClipCodec.kt",
        "app/src/main/java/com/squish/app/data/DraftTextCodec.kt"
    )
    val missing = paths.filterNot { File(it).isFile }
    if (missing.isNotEmpty()) { println("FAIL - ${missing.joinToString()} is not there"); exitProcess(1) }
    val text = paths.joinToString("\n") { File(it).readText() }

    // putFinite as well as put: a float goes through it so that a NaN leaves
    // its key out rather than throwing the autosave away (data/DraftNumbers.kt).
    val written = Regex("""put(?:Finite)?\("([A-Za-z0-9_]+)"""").findAll(text).map { it.groupValues[1] }.toSortedSet()
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

    // ---- The same question of the settings ---------------------------------
    //
    // A SharedPreferences key read with a default behind it and never written
    // is a setting that cannot be set, and one written and never read is a
    // setting that does not stick. "defaultTransitionMs" was the first kind:
    // every project made from a pile of clips had half-second joins whatever
    // the footage, and the code read as though you could choose.
    val prefs = File("app/src/main/java/com/squish/app/settings/Preferences.kt")
    if (!prefs.isFile) problems += "Preferences.kt is not there any more" else {
        val p = prefs.readText()
        val put = Regex("""put[A-Za-z]+\((KEY_[A-Z_]+)""").findAll(p).map { it.groupValues[1] }.toSortedSet()
        val got = Regex("""get[A-Za-z]+\((KEY_[A-Z_]+)""").findAll(p).map { it.groupValues[1] }.toSortedSet()
        (put - got).forEach { problems += "$it is saved and never read - the setting does not stick" }
        (got - put).forEach { problems += "$it is read and never saved - the setting cannot be set" }
        if (put.size < 8) problems += "only ${put.size} settings keys written - has the parsing rotted?"
        println("settings keys: ${put.size} written, ${got.size} read")
    }

    println("draft keys: ${written.size} written, ${read.size} read")
    if (problems.isEmpty()) println("PASS - every field a draft writes is read back, and every field read is written or named as an old one")
    else { println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1) }
}
