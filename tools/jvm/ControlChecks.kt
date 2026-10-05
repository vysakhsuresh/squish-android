import java.io.File
import kotlin.system.exitProcess

/*
 * The checks that would have caught the faults a user found, and the ones they
 * are cousins of.
 *
 * A user dragged the strip right and the playhead walked left. Nothing in this
 * repo could have caught that, because a gesture's *direction* is not
 * arithmetic anyone had written down - and when a mistake escapes, the question
 * here is not only what was wrong but what would have caught it.
 *
 * So these read the source as text and assert the handful of conventions that,
 * broken, produce exactly that class of fault: a control that moves the thing
 * you are looking at the opposite way to your finger, a label that names a
 * value nothing writes, a list with an entry no branch handles. None of it is
 * deep; all of it is the kind of thing that ships.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val SRC = "app/src/main/java/com/squish/app"

private fun read(path: String): String {
    val file = File(path)
    if (!file.isFile) { problems += "$path is not there any more - this check has rotted"; return "" }
    return file.readText()
}

/** Every file under [dir] whose name ends .kt, read. */
private fun readAll(dir: String): List<Pair<String, String>> =
    File(dir).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.path.replace('\\', '/') to it.readText() }.toList()

fun main() {
    // ---- The playhead cannot move, so it cannot move the wrong way. --------
    run {
        val editor = read("$SRC/timeline/TimelineEditor.kt")
        check(
            Regex("""leadPx\s*=\s*viewportPx\s*/\s*2f""").containsMatchIn(editor),
            "the strip's window no longer centres the playhead (leadPx is not half the viewport) - " +
                "a line that walks is a line a drag can move against the finger"
        )
        // And the drag is content-follows-finger: a positive delta (the finger
        // going right) takes the time *back*.
        check(
            Regex("""val to = \(from - w\.msForPx\(deltaPx\)\)""").containsMatchIn(editor),
            "the strip's scrub no longer subtracts the drag delta - the strip must follow the finger"
        )
    }

    // ---- "Up / down" means the same thing on every sheet. -------------------
    run {
        // A mask's own y runs up; every other vertical placement value runs
        // down. A slider labelled "Up / down" must read and write the down
        // convention, so two identically labelled sliders cannot go opposite
        // ways - which is what they did.
        readAll("$SRC/editor").forEach { (path, text) ->
            Regex("""LabeledSlider\(\s*"Up / down",\s*([^,]+),""").findAll(text).forEach { m ->
                val value = m.groupValues[1].trim()
                if (value.contains("centerYFraction")) {
                    check(
                        value.startsWith("-"),
                        "$path shows a mask's centerYFraction in an \"Up / down\" slider without negating it: " +
                            "`$value` - a mask's y runs up and every other sheet's runs down"
                    )
                }
            }
        }
    }

    // ---- Every synthesised sound is actually synthesised. -------------------
    run {
        val synth = read("$SRC/media/audio/MusicSynth.kt")
        val declared = Regex("""Effect\("([a-z0-9-]+)",""").findAll(synth).map { it.groupValues[1] }.toList()
        check(declared.size >= 20, "only ${declared.size} sound effects were read - the pattern has stopped matching")
        val dispatch = synth.substringAfter("fun renderEffect").substringBefore("\n    /**")
        declared.forEach { id ->
            check(
                dispatch.contains("\"$id\" ->"),
                "the effect \"$id\" has no branch in renderEffect, so it would come out as the fallback ding"
            )
        }
        check(declared.distinct().size == declared.size, "two sound effects share an id")

        // The three the cut sounds go round in turn are real effects.
        val audio = read("$SRC/editor/edits/AudioEdits.kt")
        Regex("""val CUT_SOUND_IDS = listOf\(([^)]*)\)""").find(audio)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
            ?.forEach { id ->
                check(id in declared, "the cut sounds name \"$id\", which the synth does not have")
            }
            ?: problems.add("CUT_SOUND_IDS could not be read from AudioEdits")
    }

    // ---- Every Adjust slider is readable and writable. ----------------------
    run {
        val look = read("$SRC/media/effects/Look.kt")
        val block = look.substringAfter("enum class AdjustField(").substringBefore("\n}")
        val fields = Regex("""\n    ([A-Z][A-Za-z]*)\("""").findAll(block).map { it.groupValues[1] }.toList()
        check(fields.size >= 13, "only ${fields.size} Adjust fields were read")
        val of = block.substringAfter("fun of(adjust: Adjust)").substringBefore("fun set(")
        val set = block.substringAfter("fun set(adjust: Adjust")
        fields.forEach { f ->
            check(of.contains("$f ->"), "AdjustField.$f has no branch in of(), so its slider would always read zero")
            check(set.contains("$f ->"), "AdjustField.$f has no branch in set(), so its slider would write nothing")
        }
    }

    // ---- Every shape on the picker can be drawn. ---------------------------
    run {
        val ann = read("$SRC/editor/Annotation.kt")
        val shapes = Regex("""\n    ([A-Z][A-Za-z]*)\("[^"]*", [0-9.]+f\)""").findAll(ann)
            .map { it.groupValues[1] }.filter { it != "None" }.toList()
        check(shapes.size >= 8, "only ${shapes.size} shapes were read from Annotation.kt")
        val polys = ann.substringAfter("fun polys(")
        shapes.forEach { s ->
            check(polys.contains("AnnotationShape.$s ->"), "the shape $s has no branch in ShapeGeometry.polys")
        }
    }

    // ---- A Reset writes its own field's neutral. ----------------------------
    run {
        // Placement's Reset wrote the sticker's default size for every text
        // item, including a shape, whose own default is almost twice it.
        val sheet = read("$SRC/editor/ToolSheet.kt")
        val placement = sheet.substringAfter("Tool.Placement ->").substringBefore("Tool.Transition ->")
        check(
            placement.contains("isShape") && placement.contains("ShapeGeometry.DEFAULT_SIZE_SP"),
            "Placement's Reset no longer uses a shape's own default size - it would shrink a shape to a sticker's"
        )
        // Adjust's Reset must keep the imported LUT, which belongs to Filters.
        val clipEdits = read("$SRC/editor/edits/ClipEdits.kt")
        val resetAdjust = clipEdits.substringAfter("fun resetAdjust(").substringBefore("\n    }")
        check(
            resetAdjust.contains("lutFile"),
            "resetAdjust no longer keeps the imported LUT, which is the Filters tab's and not Adjust's"
        )
    }

    // ---- A keyed shape is read at the playhead, not off the frozen base. ----
    run {
        val screen = read("$SRC/editor/EditorScreen.kt")
        val move = screen.substringAfter("onMaskMove =").substringBefore("onMaskMoveEnd")
        check(
            move.contains(".at(") && move.contains("sourceAt("),
            "the mask outline's drag no longer reads the shape at the playhead - on a keyed mask the base " +
                "centre is never written, so every event would add its delta to the same frozen number"
        )
    }

    // ---- A sync result says which sound it is about. ------------------------
    run {
        val audioSheet = read("$SRC/editor/AudioSheet.kt")
        check(
            audioSheet.contains("syncClipId"),
            "the Sync sheet no longer checks which clip its status is about - every sound would report the last one's"
        )
    }

    println("controls: the conventions that, broken, make a control lie")
    if (problems.isEmpty()) println("PASS - the playhead is fixed, the strip follows the finger, and every list has a branch for every entry")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
