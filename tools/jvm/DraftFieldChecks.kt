import java.io.File
import kotlin.system.exitProcess

/*
 * Every field of every model a draft holds is written and read back.
 *
 * This is the app's third guarantee - "no edit is ever lost" - checked
 * mechanically rather than by reading. A field added to Clip, TextOverlayItem,
 * TimedEffect, Adjust, Mask or ChromaKey and not added to the codec is silent:
 * the editor works, the preview works, the export works, and the setting is
 * gone the next time the project opens. There is no failure, no message and
 * nothing in the picture to see, so the only way to catch it is to compare the
 * two lists - which is what this does.
 *
 * It reads the source as text, because ProjectAutosave takes a Context and a
 * suite cannot build one. That makes it a names check rather than a round trip,
 * and the honest limits are: it cannot see that the *value* survives (a field
 * written as a Double and read as an Int would pass), and it cannot see a key
 * written in one place and read in another that never meet - every file goes
 * into one haystack, so TextOverlayItem.text makes a Clip's own `text` look
 * both written and read when neither is true (see NOT_PERSISTED below).
 * RENAMED has the same shape of hole: it is keyed by field name across every
 * model, and `rotationDegrees` is a different key in three of them, so one
 * entry cannot say what any of them is. Those pairings do hold - they were
 * read - but they pass here by coincidence rather than by assertion.
 *
 * The clip half of the codec now *is* run, against real values, in
 * tools/jvm/DraftRoundTripChecks.kt, which closes both holes for a Clip by
 * running the thing - and which is what lifting DraftClipCodec out of the
 * Context-bound class was for. This one still carries the half that cannot be:
 * the edit-wide settings and the lines of words, whose models are declared
 * beside EditorUiState. docs/ROADMAP.md §6 has what it would take, measured.
 *
 * What it does catch is the thing that has actually happened: a field nobody
 * remembered to persist.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }

private const val SRC = "app/src/main/java/com/squish/app"

/**
 * The codec, its clip half, and the file-handling class beside them.
 *
 * All three, because the sidecar's own keys are written in ProjectAutosave
 * while every model's are in the codec - and because ProjectSnapshot is
 * declared in ProjectAutosave while the functions that fill it are not. Each
 * time the codec was split, this suite's sanity guard caught it before any of
 * its real assertions did ("only 45 written keys found - the put( pattern has
 * rotted"), which is what that guard is for.
 */
private const val CODEC = "$SRC/data/DraftCodec.kt"
private const val CLIPS = "$SRC/data/DraftClipCodec.kt"
private const val TEXT = "$SRC/data/DraftTextCodec.kt"
private const val FILES = "$SRC/data/ProjectAutosave.kt"

/**
 * A field whose JSON key is not its own name. Each of these is a deliberate
 * rename, and listing them is the point: a field that is *not* here and not in
 * the codec is one nobody persisted.
 */
private val RENAMED = mapOf(
    // A clip's curve is nested under one object, as is its transition.
    "speedRamp" to "speed",
    "transitionIn" to "transitionType",
    // Adjust keeps the cube by name; the pixels live under files/luts.
    "lutFile" to "lut",
    // The word timings an auto-caption lands with.
    "wordStartsMs" to "wordStarts",
    // A clip's crop: the window's four edges under one object, and three
    // shorter names beside them.
    "rect" to "left",
    "straightenDegrees" to "straighten",
    "flipHorizontal" to "flipH",
    "flipVertical" to "flipV",
    // The canvas behind a padded frame: a fill, a colour and a picture under
    // three keys rather than one object.
    "canvasBackground" to "canvasFill",
    // "quality" is the export's, and the key says so.
    "quality" to "exportQuality"
)

/**
 * Fields that are deliberately not in a draft, with the reason. A field here is
 * one somebody decided about; a field missing from both lists is one nobody
 * did.
 */
private val NOT_PERSISTED = mapOf(
    // Which list a clip is in says what kind it is.
    "kind" to "implied by the list the clip is written in (clips / audioClips)",
    // Only set on the ClipKind.Text clips toTimeline() builds out of the text
    // overlays for the strip to draw; the overlays are written by encodeText
    // and no Text clip is ever handed to encodeClip. Listed because this check
    // read it as persisted - TextOverlayItem.text writes the same key into the
    // same haystack - which is exactly the blind spot the round trip covers.
    "text" to "a view-only field of the Text clips toTimeline() builds; the overlay behind it is written",
    // The looks of a line are their own document (TextStyleJson), which has its
    // own symmetry: one function writes it and one reads it.
    "stroke" to "written by TextStyleJson.write and read by TextStyleJson.read",
    "shadow" to "the same"
)

/**
 * A model whose fields are driven by an enum rather than by literal keys. The
 * enum is the symmetry - one loop writes `put(field.name, …)` and one reads
 * `json.has(field.name)` - so a field added to the enum is persisted by
 * construction and a names check has nothing to say about it.
 */
private val ENUM_DRIVEN = mapOf(
    "Adjust" to ("AdjustField" to listOf("AdjustField.entries.forEach", "field.name"))
)

private fun read(path: String): String {
    val f = File(path)
    if (!f.isFile) { flag("$path is not there - this check has rotted"); return "" }
    return f.readText()
}

/** The `val` properties of [name]'s primary constructor. */
private fun fieldsOf(path: String, name: String): List<String> {
    val text = read(path)
    val start = text.indexOf("data class $name(")
    if (start < 0) { flag("$name is not in $path any more"); return emptyList() }
    // To the matching close of the constructor's parenthesis.
    var depth = 0
    var i = text.indexOf('(', start)
    val from = i
    while (i < text.length) {
        if (text[i] == '(') depth++
        if (text[i] == ')') { depth--; if (depth == 0) break }
        i++
    }
    val body = text.substring(from, minOf(i + 1, text.length))
    return Regex("""\bval\s+([a-zA-Z][A-Za-z0-9]*)\s*:""").findAll(body).map { it.groupValues[1] }.toList()
}

fun main() {
    val codec = read(CODEC)
    val files = read(FILES)
    val clips = read(CLIPS)
    val lines = read(TEXT)
    val style = read("$SRC/editor/TextStyle.kt")
    val haystack = codec + "\n" + clips + "\n" + lines + "\n" + files + "\n" + style

    // putFinite as well as put: every float goes through it now, so that a NaN
    // leaves its key out rather than taking the whole autosave down with it
    // (data/DraftNumbers.kt). Reading only `put(` here found 78 fields missing.
    val written = Regex("""put(?:Finite)?\(\s*"([A-Za-z][A-Za-z0-9]*)"""").findAll(haystack)
        .map { it.groupValues[1] }.toSet()
    val readBack = Regex("""(?:opt[A-Za-z]*|has|getJSONObject|getJSONArray|getString|getLong|getInt|getDouble|getBoolean)\(\s*"([A-Za-z][A-Za-z0-9]*)"""")
        .findAll(haystack).map { it.groupValues[1] }.toSet()

    // Sanity: the two sets must be big and must overlap, or the patterns have
    // rotted and every answer below is meaningless.
    if (written.size < 60) flag("only ${written.size} written keys found - the put( pattern has rotted")
    if (readBack.size < 60) flag("only ${readBack.size} read keys found - the opt( pattern has rotted")

    val models = listOf(
        // First, and the one that matters most: ProjectSnapshot *is* the
        // declared contract of what a draft holds. A setting of the edit that
        // reaches EditorUiState and not this list is one nobody decided to
        // keep - which is how "Listen to" and the caption language went back
        // to their defaults on every reopen, with the panel reading a state
        // that had just been rebuilt and nothing to see.
        "data/ProjectAutosave.kt" to "ProjectSnapshot",
        "timeline/TimelineModels.kt" to "Clip",
        "editor/TextOverlay.kt" to "TextOverlayItem",
        "editor/TimedEffect.kt" to "TimedEffect",
        "media/effects/Look.kt" to "Adjust",
        "timeline/Mask.kt" to "Mask",
        "timeline/ChromaKey.kt" to "ChromaKey",
        "editor/CropRect.kt" to "CropRect",
        "editor/ClipCrop.kt" to "ClipCrop"
    )

    models.forEach { (path, model) ->
        val fields = fieldsOf("$SRC/$path", model)
        if (fields.isEmpty()) return@forEach
        val enumDriven = ENUM_DRIVEN[model]
        if (enumDriven != null) {
            // The loop that makes the symmetry has to still be there.
            enumDriven.second.forEach { marker ->
                if (marker !in codec && marker !in clips) {
                    flag("$model is driven by ${enumDriven.first} and the codec no longer has \"$marker\" - " +
                        "its fields are only persisted while that loop writes and reads them both")
                }
            }
        }
        // An enum-driven model's own fields are covered by its loop; only the
        // extras beside them are checked by name. Taken from the enum itself,
        // so a field added to both the class and the enum needs no entry here.
        val byEnum = if (enumDriven == null) emptySet() else
            Regex("""(?m)^\s{4}([A-Z][A-Za-z]*)\("""")
                .findAll(read("$SRC/media/effects/Look.kt").substringAfter("enum class ${enumDriven.first}"))
                .map { it.groupValues[1].lowercase() }.toSet()

        fields.forEach { field ->
            if (field in NOT_PERSISTED) return@forEach
            if (field.lowercase() in byEnum) return@forEach
            val key = RENAMED[field] ?: field
            val w = key in written
            val r = key in readBack
            if (!w) {
                flag("$model.$field is never written to a draft (key \"$key\") - the setting is gone the " +
                    "next time the project opens, with no failure and nothing to see")
            }
            if (!r) {
                flag("$model.$field is written and never read back (key \"$key\") - a draft carries it and " +
                    "the editor ignores it")
            }
        }
    }

    // ---- A new setting has to be decided about, one way or the other. ------
    //
    // ProjectSnapshot is the contract, and the gap between it and
    // EditorUiState is where a setting goes missing: captionSource and
    // captionLanguage sat in the live state and in no draft, so "Listen to" and
    // the caption language went back to their defaults on every reopen with
    // nothing to see. Most of the gap is transient and belongs there - progress
    // counters, a failure, the clipboard, what is selected, what was probed off
    // the file - and no check can tell a setting from a status by looking.
    //
    // So this is a tripwire rather than a judgement: the names in the gap are
    // listed, and a field that joins EditorUiState and neither this list nor
    // ProjectSnapshot fails here. Whoever added it then decides which side it
    // is on, which is the one thing nobody did for those two.
    run {
        val snapshot = fieldsOf(FILES, "ProjectSnapshot").toSet()
        val live = fieldsOf("$SRC/editor/EditorModels.kt", "EditorUiState")
        // Deliberately not in a draft. A view setting, a status, a progress
        // count, a session clipboard, or something probed off the file again on
        // every open.
        val transient = setOf(
            "attributeClipboard", "audioWaveforms", "backgroundProgress", "bestBitsProgress",
            "captions", "durationMs", "encoderAnswer", "encoderCeilingP", "estimatedOutputBytes",
            "exportProgress", "failure", "fitNonce", "fitOvershoot", "fitScale", "fps",
            "hevcAvailable", "isExporting", "isLoadingSource", "isPlaying", "missingMedia",
            "originalSizeBytes", "preparingStills", "projectId", "projectName", "proxyPercent",
            "proxyStatuses", "proxyUris", "recording", "redoLabel", "reframeProgress",
            "replacing", "reversing", "safeArea", "scrubNonce", "selectedClipId",
            "selectedClipIds", "selectingMore", "sourceHasAudio", "sourceHeight", "sourceName",
            "sourceVideoBps", "sourceWidth", "speakingId", "stabilize", "startedAtMillis",
            "styleClipboard", "syncClipId", "syncConfidence", "syncStatus", "tracking",
            "transportRequest", "trimEndMs", "trimStartMs", "undoLabel", "videoClips"
        )
        val undecided = live.filterNot { it in snapshot || it in transient }
        undecided.forEach { field ->
            flag("EditorUiState.$field is in neither ProjectSnapshot nor the transient list - decide " +
                "which it is. If it is a setting of the edit it belongs in the draft (and the codec); " +
                "if it is a status or a view preference, add it to the list in this suite with the " +
                "reason, the way safeArea's own comment gives one.")
        }
        // And the other way: a name that has left EditorUiState should leave
        // the list, or the list becomes a record of fields that no longer exist.
        transient.filterNot { it in live }.forEach {
            flag("\"$it\" is in this suite's transient list and no longer a field of EditorUiState")
        }
    }

    // The codec says which versions it reads; a field added without a bump is
    // read by an older build as its new meaning. Not a thing a names check can
    // judge, but the constants must at least still be there to be reasoned about.
    listOf("FORMAT_VERSION", "OLDEST_READABLE_VERSION").forEach {
        if (!Regex("""const val $it = \d+""").containsMatchIn(codec)) {
            flag("the codec no longer declares $it")
        }
    }

    println("draft fields: ${written.size} keys written, ${readBack.size} read")
    if (problems.isEmpty()) {
        println("PASS - every field of every model a draft holds is written and read back")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}
