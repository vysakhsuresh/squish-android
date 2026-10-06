import com.squish.app.data.DraftTextCodec
import com.squish.app.data.TextStyleJson
import com.squish.app.editor.AnnotationShape
import com.squish.app.editor.TextAlign
import com.squish.app.editor.TextBackground
import com.squish.app.editor.TextBubble
import com.squish.app.editor.TextExit
import com.squish.app.editor.TextFont
import com.squish.app.editor.TextLoop
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextShadow
import com.squish.app.editor.TextStroke
import com.squish.app.editor.TextStyleSpec
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.system.exitProcess

/*
 * A line of words with everything set on it, written to a draft and read back.
 *
 * The sibling of tools/jvm/DraftRoundTripChecks.kt, over the other half of a
 * draft's value: the text track. A line carries a font, a colour, a size, a
 * stroke, a shadow, a bubble, a glow, an opacity, a turn, a flip, an arrival, a
 * leaving, a loop with three lengths, its words' own timings, a shape with an
 * aspect and a fill, a motion track and a row - thirty-six fields, every one of
 * which somebody set on purpose and none of which announces itself when it comes
 * back wrong.
 *
 * It exists because DraftTextCodec and TextOverlayItem were both lifted into
 * files that touch no Android (docs/ROADMAP.md §6). Before that, the only thing
 * that could be said about any of these fields was that a key with that name
 * appeared somewhere in the codec's source - and tools/jvm/DraftFieldChecks.kt
 * says in its own header how little that is worth: it read a Clip's `text` as
 * persisted because *this* model writes the same key.
 *
 * Held honest the same two ways as the clip's: the fixture is compared against a
 * plain line by reflection over TextOverlayItem's own constructor, so a field
 * added to the model and not to the fixture fails by name before the round trip
 * can pass it by default; and the line goes through JSON *text*, which is what a
 * draft on disk is. The same honest limit applies: org.json here is the Maven
 * jar, not Android's implementation of the same API.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }

/**
 * Nothing. Every field of a line is persisted, and the empty map is the
 * statement - an entry appearing here later is somebody deciding that a setting
 * of a line need not survive, which wants the reason beside it.
 */
private val NOT_PERSISTED = emptyMap<String, String>()

/** A line with every field moved off its default. */
private fun wildLine(): TextOverlayItem = TextOverlayItem(
    id = "wild-line",
    text = "Everything, all at once",
    startMs = 1_200L,
    endMs = 4_800L,
    colorArgb = 0xFFEE7733.toInt(),
    xFraction = 0.31f,
    yFraction = 0.19f,
    sizeSp = 44,
    font = TextFont.entries.last(),
    fontFile = "fonts/imported.ttf",
    bold = true,
    italic = true,
    underline = true,
    align = TextAlign.entries.last(),
    letterSpacing = 0.14f,
    lineSpacing = 1.35f,
    stroke = TextStroke(0xFF112233.toInt(), 0.09f),
    shadow = TextShadow(0xFF445566.toInt(), 0.72f, 0.26f, 0.17f, 112f),
    background = TextBackground(0xFF778899.toInt(), 0.41f, 0.22f, TextBubble.entries.last()),
    glow = true,
    opacity = 0.63f,
    rotationDegrees = -18.5f,
    flipped = true,
    motion = TextMotion.entries.last(),
    motionInMs = 360L,
    motionOut = TextExit.entries.last(),
    motionOutMs = 470L,
    loop = TextLoop.entries.last(),
    loopMs = 1_150L,
    wordStartsMs = listOf(0L, 420L, 910L, 1_500L),
    // A sticker *and* a shape is not a state the editor can make - a line is one
    // of the three kinds. It is set that way on purpose: the shape's three keys
    // are written only when there is a shape, and the sticker flag is the only
    // other way to tell the kinds apart, so one fixture has to carry both or one
    // of the four keys goes unexercised.
    sticker = true,
    shape = AnnotationShape.entries.last(),
    shapeAspect = 2.4f,
    shapeFilled = true,
    track = MotionTrack(
        listOf(
            TrackSample(0L, 0.4f, 0.7f, 1.1f, 0.9f),
            TrackSample(600L, 0.45f, 0.65f, 1.2f, 0.75f)
        )
    ),
    stripRow = 3
)

/** A line with nothing set on it. Also the fixture's own yardstick. */
private fun plainLine(): TextOverlayItem = TextOverlayItem(
    id = "plain",
    text = "",
    startMs = 0L,
    endMs = 0L,
    colorArgb = 0
)

@Suppress("UNCHECKED_CAST")
private fun reader(name: String): KProperty1<TextOverlayItem, Any?>? =
    TextOverlayItem::class.memberProperties.firstOrNull { it.name == name } as? KProperty1<TextOverlayItem, Any?>

/** Where two values differ, named as deep as the models go - see the clip suite. */
@Suppress("UNCHECKED_CAST")
private fun describe(path: String, before: Any?, after: Any?, depth: Int = 0): String {
    if (before == after) return ""
    val leaf = "$path: wrote <$before>, read back <$after>"
    if (depth >= 4 || before == null || after == null) return leaf
    if (before::class != after::class) return leaf
    if (before is List<*> && after is List<*>) {
        if (before.size != after.size) return "$path: wrote ${before.size} of them, read back ${after.size}"
        return before.indices.mapNotNull { i ->
            describe("$path[$i]", before[i], after[i], depth + 1).takeIf { it.isNotEmpty() }
        }.firstOrNull() ?: leaf
    }
    val params = runCatching { before::class.primaryConstructor?.parameters }.getOrNull().orEmpty()
    if (params.isEmpty()) return leaf
    val props = before::class.memberProperties.associateBy { it.name }
    return params.mapNotNull { p ->
        val prop = props[p.name] as? KProperty1<Any, Any?> ?: return@mapNotNull null
        describe("$path.${p.name}", runCatching { prop.get(before) }.getOrNull(),
            runCatching { prop.get(after) }.getOrNull(), depth + 1).takeIf { it.isNotEmpty() }
    }.firstOrNull() ?: leaf
}

fun main() {
    val fields = TextOverlayItem::class.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
    if (fields.size < 30) {
        flag("only ${fields.size} constructor parameters found on TextOverlayItem - reflection has rotted " +
            "and every answer below is meaningless")
    }

    val wild = wildLine()
    val plain = plainLine()

    // ---- 1. The fixture itself. -------------------------------------------
    fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name)
        if (read == null) { flag("TextOverlayItem.$name has no readable property - reflection has rotted"); return@forEach }
        if (read.get(wild) == read.get(plain)) {
            flag("the fixture leaves TextOverlayItem.$name at the plain line's value (${read.get(plain)}) - set it in " +
                "wildLine() to something else, or name it in NOT_PERSISTED with the reason. Until then the round trip " +
                "below passes this field whether the codec carries it or not.")
        }
    }
    NOT_PERSISTED.keys.filterNot { it in fields }.forEach {
        flag("\"$it\" is excused from this round trip and is no longer a field of TextOverlayItem")
    }

    // ---- 2. The round trip, through text. ---------------------------------
    val written = DraftTextCodec.encodeText(wild).toString()
    val back = DraftTextCodec.decodeText(org.json.JSONObject(written))
    if (back == null) {
        flag("decodeText returned null for a line encodeText had just written")
        report()
        return
    }
    fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        if (read.get(wild) != read.get(back)) {
            flag("a draft does not carry it - " + describe("TextOverlayItem.$name", read.get(wild), read.get(back)))
        }
    }

    // ---- 3. A second trip is a fixed point. -------------------------------
    val again = DraftTextCodec.decodeText(org.json.JSONObject(DraftTextCodec.encodeText(back).toString()))
    if (again == null) flag("the second encode and decode returned null")
    else fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        if (read.get(back) != read.get(again)) {
            flag("the draft on disk walks every time the project is opened and saved - " +
                describe("TextOverlayItem.$name", read.get(back), read.get(again)))
        }
    }

    // ---- 4. A bare line reads back bare. ----------------------------------
    val bareBack = DraftTextCodec.decodeText(org.json.JSONObject(DraftTextCodec.encodeText(plain).toString()))
    if (bareBack == null) flag("decodeText returned null for a line with nothing set on it")
    else fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        if (read.get(plain) != read.get(bareBack)) {
            flag("a line nothing was set on comes back carrying something - " +
                describe("TextOverlayItem.$name", read.get(plain), read.get(bareBack)))
        }
    }

    // ---- 5. A line with no id is not a line. ------------------------------
    //
    // The one case decodeText answers with null, and it must: a draft whose
    // array holds a half-written object would otherwise put an anonymous line
    // on the strip that no edit can reach, since every command finds a line by
    // its id.
    if (DraftTextCodec.decodeText(org.json.JSONObject("""{"text":"orphan"}""")) != null) {
        flag("a line with no id was read back as a line - nothing can select or delete it")
    }

    // ---- 6. A style saved on its own reads back as the line's. -------------
    //
    // TextStyleJson has two callers - a line in a draft, and a saved style in
    // SharedPreferences ("Save this style") - and its whole point is that they
    // are one document. If they ever part, a style saved off a line comes back
    // as a different look than the line it was taken from, which is the sort of
    // thing nobody reports as a bug because it reads as having mis-remembered.
    run {
        val saved = TextStyleJson.read(org.json.JSONObject(TextStyleJson.encode(wild.style).toString()))
        if (saved != wild.style) {
            flag("a style saved on its own is not the line's style - " + describe("style", wild.style, saved))
        }
        // And the legacy branch: a style document written before a line's
        // decorations were its own fields has no "glow", and is read through the
        // look that named them. It must still answer something, not throw.
        val old = runCatching { TextStyleJson.read(org.json.JSONObject("""{"font":"Sans","look":"Outline"}""")) }
        if (old.isFailure) flag("a style from before the decorations were fields now throws: ${old.exceptionOrNull()}")
        else if (old.getOrNull() == TextStyleSpec()) {
            flag("a style written as a named look reads back as the plain default - the look is being dropped")
        }
    }

    // ---- 7. And the codec may not reach put() with a float. ----------------
    //
    // As in the clip's codec: JSON has no NaN, put throws on one, and encode is
    // called outside the try that guards the write, so one non-finite number
    // took the whole autosave with it. A line's own hazard is live - a turn and
    // a letter spacing are dragged, and an anchor is a fraction of a measured
    // width. See data/DraftNumbers.kt.
    listOf("app/src/main/java/com/squish/app/data/DraftTextCodec.kt").forEach { path ->
        val text = java.io.File(path).takeIf { it.isFile }?.readText()
        if (text == null) { flag("$path is not there - this check has rotted"); return@forEach }
        if (!text.contains("putFinite(")) flag("$path no longer uses putFinite at all")
        Regex("""(?m)^.*put\([^\n]*\.toDouble\(\)\).*$""").findAll(text).forEach { m ->
            flag("DraftTextCodec.kt puts a number straight into the document: ${m.value.trim()} - " +
                "use putFinite, or a NaN there throws the whole autosave away (data/DraftNumbers.kt)")
        }
        Regex("""optDouble\([^,)]*\)""").findAll(text).forEach { m ->
            flag("DraftTextCodec.kt reads ${m.value} with no default, which is NaN for a key that is not " +
                "there - give it the field's own default explicitly")
        }
    }
    run {
        val poisoned = plain.copy(
            xFraction = Float.NaN,
            rotationDegrees = Float.POSITIVE_INFINITY,
            letterSpacing = Float.NaN,
            opacity = Float.NEGATIVE_INFINITY,
            shape = AnnotationShape.entries.last(),
            shapeAspect = Float.NaN,
            track = MotionTrack(listOf(TrackSample(0L, Float.NaN, 0.5f, 1f, 1f)))
        )
        val threw = runCatching { DraftTextCodec.encodeText(poisoned).toString() }.exceptionOrNull()
        if (threw != null) {
            flag("a line holding a NaN cannot be written at all (${threw.javaClass.simpleName}: ${threw.message}) - " +
                "an autosave throwing is a draft lost")
        } else {
            val read = DraftTextCodec.decodeText(org.json.JSONObject(DraftTextCodec.encodeText(poisoned).toString()))
            if (read == null) flag("a line holding a NaN was written and then read back as nothing")
            else {
                if (read.xFraction != 0.5f) flag("a NaN anchor read back as ${read.xFraction}, not the decoder's own 0.5")
                if (read.rotationDegrees != 0f) flag("an infinite turn read back as ${read.rotationDegrees}, not 0")
                if (read.letterSpacing != 0f) flag("a NaN letter spacing read back as ${read.letterSpacing}, not 0")
                if (read.opacity != 1f) flag("an infinite opacity read back as ${read.opacity}, not 1")
                if (read.shapeAspect != 1f) flag("a NaN shape aspect read back as ${read.shapeAspect}, not 1")
                if (read.track?.samples?.size != 1) {
                    flag("a NaN in a track sample lost the sample: ${read.track?.samples?.size} left of 1")
                }
            }
        }
    }

    report()
}

private fun report() {
    println("draft text round trip: ${TextOverlayItem::class.primaryConstructor?.parameters?.size} fields of a line, through JSON text")
    if (problems.isEmpty()) {
        println("PASS - a line with everything on it survives a draft, twice over, and a saved style is the line's")
    } else {
        println("FAIL (${problems.size})")
        problems.take(25).forEach { println("  - $it") }
        exitProcess(1)
    }
}
