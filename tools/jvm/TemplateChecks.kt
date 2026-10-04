import java.io.File
import kotlin.system.exitProcess

// Every template against the looks, title presets and effects that exist.
//
// Read as text rather than compiled, because a template names a CropAspect and
// that enum lives in EditorModels.kt, which is half the editor and all of
// Android. The thing worth checking is not the types - the compiler has those -
// but the look *ids*, which are strings and which nothing else would catch: a
// look renamed leaves a template silently applying nothing at all.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun read(path: String): String {
    val file = File(path)
    if (!file.isFile) { problems += "$path is not there"; return "" }
    return file.readText()
}

fun main() {
    val src = "app/src/main/java/com/squish/app"
    val templates = read("$src/editor/Template.kt")
    val looks = read("$src/media/effects/Look.kt")
    // The TitlePreset block alone. Taken over the whole file, every name in it
    // matched something: CaptionStylePreset is right below TitlePreset and has
    // its own Classic, Bold, Soft and Mono, so thirteen templates naming
    // presets that do not exist passed this check and failed the compiler.
    val textStyle = read("$src/editor/TextStyle.kt")
        .substringAfter("enum class TitlePreset(")
        .substringBefore("\nenum class ")
    val effects = read("$src/editor/TimedEffect.kt")

    // Every id a Look is declared with, and the body of the enum of templates.
    // Declared both on one line and wrapped onto the next, so the family is
    // what anchors it rather than the opening bracket.
    val lookIds = Regex(""""([a-z0-9-]+)"\s*,\s*"[^"]*"\s*,\s*LookFamily""")
        .findAll(looks).map { it.groupValues[1] }.toSet()
    check(lookIds.size > 20, "only ${lookIds.size} looks were found - the pattern has probably stopped matching")

    // One entry per template: Name( "Label", "blurb", crop, "look", preset, "title", effects )
    val entries = Regex(
        """\n    ([A-Z][A-Za-z0-9]*)\(\s*\n\s*"([^"]*)",\s*"([^"]*)",\s*\n\s*([A-Za-z.]+|null),\s*(null|"[a-z0-9-]+"),\s*([A-Za-z.]+|null),"""
    ).findAll(templates).toList()
    check(entries.size >= 40, "only ${entries.size} templates were read - 40 was the point of the exercise")

    val names = mutableListOf<String>()
    val labels = mutableListOf<String>()
    entries.forEach { m ->
        val (name, label, blurb, crop, look, preset) = m.destructured
        names += name
        labels += label
        check(label.isNotBlank(), "$name has no label")
        check(blurb.isNotBlank(), "$name has no blurb")
        check(label.length <= 12, "$name's label \"$label\" is too long for a tile")
        if (look != "null") {
            val id = look.trim('"')
            check(id in lookIds, "$name uses the look \"$id\", which no Look declares")
        }
        if (crop != "null") {
            check(crop.startsWith("CropAspect."), "$name's crop reads \"$crop\"")
        }
        if (preset != "null") {
            val value = preset.removePrefix("TitlePreset.")
            check(preset.startsWith("TitlePreset."), "$name's title reads \"$preset\"")
            check(
                Regex("""\n    $value\(""").containsMatchIn(textStyle),
                "$name uses the title preset $value, which TitlePreset does not declare"
            )
        }
    }

    // Every effect any template names.
    val used = Regex("""EffectKind\.([A-Za-z0-9]+)""").findAll(templates).map { it.groupValues[1] }.toSet()
    check(used.isNotEmpty(), "no effects were read from the templates")
    used.forEach { kind ->
        check(
            Regex("""\n    $kind\(""").containsMatchIn(effects),
            "a template uses the effect $kind, which EffectKind does not declare"
        )
    }

    // Every template on exactly one shelf, and every shelf with something on
    // it. The compiler has the first half (the when is exhaustive); what it
    // cannot see is a shelf that ended up empty, or a chip for nothing.
    val shelves = templates.substringAfter("val Template.family")
    val families = Regex("""TemplateFamily\.([A-Za-z]+)\b""").findAll(shelves).map { it.groupValues[1] }.toList()
    val declared = Regex("""\n    ([A-Z][A-Za-z]*)\("([^"]*)"\)""")
        .findAll(templates.substringAfter("enum class TemplateFamily").substringBefore("val Template.family"))
        .map { it.groupValues[1] }.toList()
    check(declared.isNotEmpty(), "no template families were read")
    declared.forEach { shelf ->
        check(shelf in families, "the $shelf shelf has nothing on it, so its chip shows an empty page")
    }
    names.forEach { name ->
        check(
            Regex("""Template\.$name\b""").findAll(shelves).count() == 1,
            "$name is on ${Regex("""Template\.$name\b""").findAll(shelves).count()} shelves"
        )
    }

    check(names.distinct().size == names.size, "two templates share a name")
    check(labels.distinct().size == labels.size, "two templates share a label: ${labels.groupBy { it }.filter { it.value.size > 1 }.keys}")

    println("templates: ${entries.size} against ${lookIds.size} looks and ${used.size} effects")
    if (problems.isEmpty()) println("PASS - every template names a look, a title and effects that exist")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
