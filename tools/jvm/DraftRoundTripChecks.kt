import com.squish.app.data.DraftClipCodec
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRatio
import com.squish.app.editor.CropRect
import com.squish.app.media.effects.Adjust
import com.squish.app.media.effects.ColorWheels
import com.squish.app.media.effects.Curve
import com.squish.app.media.effects.CurvePoint
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
import com.squish.app.media.effects.ToneCurve
import com.squish.app.media.effects.Wheel
import com.squish.app.media.video.FrameMotion
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.StabilizerMeasurement
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.LayerBlend
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskKey
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.ReversedSource
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.VoiceEffect
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.system.exitProcess

/*
 * A clip with everything set on it, written to a draft and read back.
 *
 * This is the check tools/jvm/DraftFieldChecks.kt cannot be: that one reads the
 * codec as *text* and compares two lists of names, so it sees a field nobody
 * persisted and it does not see a field whose **value** does not survive. A
 * colour wheel was clamped to half its range on reading for as long as anybody
 * had been grading with one, and the names check passed every one of those
 * days - "wheels" was written and "wheels" was read.
 *
 * It is also why DraftClipCodec was lifted out of ProjectAutosave and then
 * split from DraftCodec. The clip half names nothing but the timeline, the
 * colour pipeline and the vision types - none of which touch Android but for
 * Uri, which the harness stubs - so it can be *run* here. The other half names
 * EditorUiState, whose file reaches Compose, and cannot be.
 *
 * Two things hold it honest:
 *
 *   1. The fixture is checked against a default clip field by field, by
 *      reflection over Clip's own constructor. A field added to Clip and not
 *      set here fails *that* assertion first, with its name - so the round trip
 *      can never quietly stop covering something.
 *   2. The clip goes through JSON *text*, not just a JSONObject: encode,
 *      toString, parse, decode. That is what a draft on disk actually is, and
 *      it is the step that catches a value no JSON document can hold.
 *
 * The honest limit: org.json here is the Maven jar, not Android's own
 * implementation of the same API. The two agree on everything this exercises
 * (the opt* defaults, JSONObject.NULL, put-with-null removing a key), and a
 * difference between them is the one class of fault this cannot see.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }

/**
 * Fields of [Clip] that a draft deliberately does not carry, with the reason.
 *
 * `kind` is the argument `decodeClip` is handed - which list the clip was
 * written in says what kind it is - and is asserted separately below.
 *
 * `text` is only ever set on the ClipKind.Text clips `toTimeline()` builds out
 * of the text overlays for the strip to draw; the overlays themselves are
 * written by DraftCodec.encodeText, and no Text clip is ever handed to
 * encodeClip. Worth naming, because the names check reads every file as one
 * haystack and TextOverlayItem.text writes the same key - so "text" looks
 * written and read there while a Clip's is neither.
 */
private val NOT_PERSISTED = mapOf(
    "kind" to "the parameter decodeClip is given, from the list the clip is in",
    "text" to "only on the view-only ClipKind.Text clips toTimeline() builds"
)

/** A clip with every field moved off its default, and every nested model filled. */
private fun wildClip(): Clip = Clip(
    id = "wild-clip",
    kind = ClipKind.Video,
    uri = android.net.Uri.parse("content://media/external/video/media/4242"),
    label = "A shot with everything on it",
    sourceInMs = 1_250L,
    sourceOutMs = 9_750L,
    timelineStartMs = 3_000L,
    sourceDurationMs = 12_000L,
    volume = 0.42f,
    muted = true,
    fadeInMs = 320L,
    fadeOutMs = 480L,
    voice = VoiceEffect.entries.last(),
    // Sorted and in range: the codec sorts beats on the way in, and a fixture
    // that was not sorted would be testing the sort rather than the round trip.
    beats = listOf(100L, 900L, 1_700L),
    speedRamp = SpeedRamp(listOf(SpeedPoint(0L, 0.5f), SpeedPoint(2_000L, 2.5f))),
    text = null,
    transitionIn = Transition(type = TransitionType.entries.last(), durationMs = 640L),
    layer = 2,
    opacity = 0.77f,
    scale = 1.35f,
    offsetXFraction = -0.18f,
    offsetYFraction = 0.24f,
    rotation = 12.5f,
    keyframes = listOf(
        Keyframe(0L, Transform(1f, 0f, 0f, 0f), KeyframeEasing.entries.first()),
        Keyframe(1_500L, Transform(1.8f, 0.2f, -0.3f, 30f), KeyframeEasing.entries.last())
    ),
    chromaKey = ChromaKey(keyColorArgb = 0xFF00FF2A.toInt(), similarity = 0.31f, smoothness = 0.09f, spill = 0.14f),
    mask = Mask(
        shape = MaskShape.entries.last(),
        centerXFraction = 0.12f,
        centerYFraction = -0.33f,
        widthFraction = 0.44f,
        heightFraction = 0.52f,
        rotationDegrees = 22f,
        feather = 0.11f,
        cornerRadius = 0.07f,
        inverted = true,
        mode = MaskMode.entries.last(),
        strength = 0.81f,
        track = MotionTrack(listOf(TrackSample(0L, 0.4f, 0.6f, 1.2f, 0.9f), TrackSample(500L, 0.45f, 0.55f, 1.1f, 0.7f))),
        keys = listOf(
            MaskKey(0L, Mask(shape = MaskShape.entries.first(), widthFraction = 0.3f), KeyframeEasing.entries.first()),
            MaskKey(900L, Mask(shape = MaskShape.entries.last(), widthFraction = 0.5f), KeyframeEasing.entries.last())
        )
    ),
    background = BackgroundRemoval(maskFile = "segments/wild.png", fill = BackgroundFill.entries.last(), colorArgb = 0xFF2277AA.toInt()),
    stabilizer = listOf(
        Keyframe(0L, Transform(1.04f, 0.01f, -0.02f, 0.4f), KeyframeEasing.entries.last()),
        Keyframe(66L, Transform(1.05f, 0.02f, -0.01f, -0.3f), KeyframeEasing.entries.last())
    ),
    mirrored = true,
    quarterTurns = 3,
    reversedFrom = ReversedSource(
        uri = android.net.Uri.parse("content://media/external/video/media/99"),
        sourceInMs = 400L,
        sourceOutMs = 4_400L,
        durationMs = 20_000L,
        background = BackgroundRemoval(maskFile = "segments/was.png", fill = BackgroundFill.entries.last(), colorArgb = 0xFF445566.toInt())
    ),
    lookId = "kodachrome",
    lookIntensity = 0.65f,
    adjust = Adjust(
        brightness = 0.11f, contrast = -0.22f, saturation = 0.33f, exposure = -0.44f,
        temperature = 0.55f, tint = -0.15f, highlights = 0.26f, shadows = -0.37f,
        sharpen = 0.48f, vignette = 0.59f, hue = -0.61f, fade = 0.17f, grain = 0.28f, smooth = 0.39f,
        hsl = List(HueBand.entries.size) { i -> HslBand(hue = 0.1f * (i + 1), saturation = -0.05f * (i + 1), luminance = 0.02f * (i + 1)) },
        curve = ToneCurve(
            master = Curve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 0.95f))),
            red = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.9f))),
            green = Curve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f))),
            blue = Curve(listOf(CurvePoint(0f, 0.02f), CurvePoint(0.4f, 0.3f), CurvePoint(1f, 1f)))
        ),
        // Past 1 on purpose: a wheel's component reaches COMPONENT_REACH, and
        // reading it back clamped to 1 is the fault this whole suite exists for.
        wheels = ColorWheels(
            lift = Wheel(0.4f, -0.3f, 0.2f),
            gamma = Wheel(-1.6f, 1.4f, 0.1f),
            gain = Wheel(1.9f, -1.9f, 0.8f)
        ),
        lutFile = "luts/teal.cube",
        lutStrength = 0.72f
    ),
    crop = ClipCrop(
        rect = CropRect(0.1f, 0.15f, 0.85f, 0.9f),
        straightenDegrees = -6.5f,
        flipHorizontal = true,
        flipVertical = true,
        ratio = CropRatio.entries.last()
    ),
    reframe = MotionTrack(listOf(TrackSample(0L, 0.5f, 0.4f, 1f, 1f), TrackSample(1_000L, 0.6f, 0.45f, 1.05f, 0.8f))),
    // Four motions at least: a measurement of fewer is `isEmpty`, and the
    // codec writes nothing for one of those - so a three-frame fixture would
    // be testing that rule rather than the round trip.
    stabilizerMeasurement = StabilizerMeasurement(
        analysisWidth = 480,
        analysisHeight = 270,
        timesMs = listOf(0L, 33L, 66L, 100L),
        motions = listOf(
            FrameMotion(0.5f, -0.25f, 0.1f, 0.95f),
            FrameMotion(-0.75f, 0.4f, -0.2f, 0.8f),
            FrameMotion(1.25f, 0.05f, 0.3f, 0.6f),
            FrameMotion(-0.1f, -0.6f, 0.45f, 0.55f)
        )
    ),
    stabilizeStrength = 0.83f,
    opacityKeys = listOf(ValueKey(0L, 0.2f, KeyframeEasing.entries.first()), ValueKey(800L, 0.95f, KeyframeEasing.entries.last())),
    blend = LayerBlend.entries.last(),
    volumeKeys = listOf(ValueKey(0L, 1.4f, KeyframeEasing.entries.last()), ValueKey(600L, 0.3f, KeyframeEasing.entries.first())),
    lookKeys = listOf(ValueKey(0L, 0.1f, KeyframeEasing.entries.first()), ValueKey(400L, 0.9f, KeyframeEasing.entries.last())),
    arrival = ClipArrival.entries.last(),
    arrivalMs = 420L,
    leaving = ClipLeaving.entries.last(),
    leavingMs = 530L,
    loop = ClipLoop.entries.last(),
    loopMs = 1_240L,
    frameBlend = true,
    pitchFollowsSpeed = true
)

/** A clip with nothing set on it. Also the fixture's own yardstick. */
private fun plainClip(): Clip = Clip(
    id = "plain",
    kind = ClipKind.Video,
    label = "",
    sourceInMs = 0L,
    sourceOutMs = 0L,
    timelineStartMs = 0L
)

@Suppress("UNCHECKED_CAST")
private fun reader(name: String): KProperty1<Clip, Any?>? =
    Clip::class.memberProperties.firstOrNull { it.name == name } as? KProperty1<Clip, Any?>

/**
 * Where two values differ, named as deep as the models go.
 *
 * Without this a mismatch anywhere inside `adjust` printed the whole grade
 * twice - thirteen sliders, eight bands, four curves and three wheels, in two
 * paragraphs - and the one number that moved was somewhere in the middle of
 * it. Walking the constructors says `adjust.wheels.gamma.r`, which is the
 * difference between a failure somebody reads and a failure somebody skips.
 */
@Suppress("UNCHECKED_CAST")
private fun describe(path: String, before: Any?, after: Any?, depth: Int = 0): String {
    if (before == after) return ""
    val leaf = "$path: wrote <$before>, read back <$after>"
    if (depth >= 4 || before == null || after == null) return leaf
    if (before::class != after::class) return leaf
    // A list differs element by element; one that differs in length is a leaf.
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
        val a = runCatching { prop.get(before) }.getOrNull()
        val b = runCatching { prop.get(after) }.getOrNull()
        describe("$path.${p.name}", a, b, depth + 1).takeIf { it.isNotEmpty() }
    }.firstOrNull() ?: leaf
}

fun main() {
    val fields = Clip::class.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
    if (fields.size < 40) {
        flag("only ${fields.size} constructor parameters found on Clip - reflection has rotted and every answer below is meaningless")
    }

    val wild = wildClip()
    val plain = plainClip()

    // ---- 1. The fixture itself. -------------------------------------------
    //
    // Every field of Clip is either moved off its default here or named in
    // NOT_PERSISTED with a reason. A field added to Clip and left out of both
    // fails here, by name, before the round trip has a chance to pass it by
    // default - which is the only way a check like this does not rot.
    fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name)
        if (read == null) { flag("Clip.$name has no readable property - reflection has rotted"); return@forEach }
        if (read.get(wild) == read.get(plain)) {
            flag("the fixture leaves Clip.$name at the plain clip's value (${read.get(plain)}) - set it in wildClip() " +
                "to something else, or name it in NOT_PERSISTED with the reason. Until then the round trip below " +
                "passes this field whether the codec carries it or not.")
        }
    }
    // And the other way, or the exemption list becomes a record of fields that
    // no longer exist - which reads as a decision somebody made about the model
    // as it is now.
    NOT_PERSISTED.keys.filterNot { it in fields }.forEach {
        flag("\"$it\" is excused from this round trip and is no longer a field of Clip")
    }

    // ---- 2. The round trip, through text. ---------------------------------
    val written = DraftClipCodec.encodeClip(wild).toString()
    val back = DraftClipCodec.decodeClip(org.json.JSONObject(written), ClipKind.Video)
    if (back == null) {
        flag("decodeClip returned null for a clip encodeClip had just written")
        report()
        return
    }

    fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        val before = read.get(wild)
        val after = read.get(back)
        if (before != after) {
            flag("a draft does not carry it - " + describe("Clip.$name", before, after))
        }
    }

    // The kind is the parameter, not a key: the list a clip was written in is
    // what says what it is.
    if (DraftClipCodec.decodeClip(org.json.JSONObject(written), ClipKind.Audio)?.kind != ClipKind.Audio) {
        flag("decodeClip does not take its kind from its argument")
    }

    // ---- 3. A second trip is a fixed point. -------------------------------
    //
    // Not the same assertion: a value that is clamped or defaulted on reading
    // settles after one trip, so wrote-then-read can differ while read-then-read
    // agrees. This says the draft on disk is stable - that opening and saving a
    // project ten times does not walk a number anywhere.
    val again = DraftClipCodec.decodeClip(org.json.JSONObject(DraftClipCodec.encodeClip(back).toString()), ClipKind.Video)
    if (again == null) flag("the second encode and decode returned null")
    else fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        if (read.get(back) != read.get(again)) {
            flag("the draft on disk walks every time the project is opened and saved - " +
                describe("Clip.$name", read.get(back), read.get(again)))
        }
    }

    // ---- 4. A bare clip reads back bare. ----------------------------------
    //
    // The other end of the same guarantee: a clip with nothing set on it must
    // not come back carrying something. A default written as a key and read
    // with a different default is how a mask or a key colour appears on a shot
    // nobody touched.
    val bareBack = DraftClipCodec.decodeClip(org.json.JSONObject(DraftClipCodec.encodeClip(plain).toString()), ClipKind.Video)
    if (bareBack == null) flag("decodeClip returned null for a clip with nothing set on it")
    else fields.forEach { name ->
        if (name in NOT_PERSISTED) return@forEach
        val read = reader(name) ?: return@forEach
        if (read.get(plain) != read.get(bareBack)) {
            flag("a clip nothing was set on comes back carrying something - " + describe("Clip.$name", read.get(plain), read.get(bareBack)))
        }
    }

    // ---- 5. The values a JSON document cannot hold. -----------------------
    //
    // JSON has no NaN and no infinity, and put() throws on one rather than
    // writing something nothing could read back. That throw comes out of
    // encode(), which ProjectAutosave.save calls *before* the try that guards
    // the write - so one non-finite number anywhere in an edit took the whole
    // save with it, and every save after it, with nothing on screen to say so.
    // Gesture arithmetic divides by a measured width and a width is zero for
    // one frame on every layout, so this is not hypothetical: see
    // data/DraftNumbers.kt, and CropRect.of, which answers it for a crop.
    run {
        val poisoned = plain.copy(
            volume = Float.NaN,
            scale = Float.NaN,
            opacity = Float.POSITIVE_INFINITY,
            rotation = Float.NEGATIVE_INFINITY,
            offsetXFraction = Float.NaN,
            mask = Mask(widthFraction = Float.NaN, feather = Float.NaN),
            keyframes = listOf(Keyframe(0L, Transform(Float.NaN, 0f, Float.NaN, 0f))),
            adjust = Adjust(
                contrast = Float.NaN,
                wheels = ColorWheels(lift = Wheel(Float.NaN, 0.5f, -0.5f)),
                curve = ToneCurve(master = Curve(listOf(CurvePoint(0f, Float.NaN), CurvePoint(1f, 1f))))
            ),
            stabilizerMeasurement = StabilizerMeasurement(
                320, 180, listOf(0L, 33L, 66L, 100L),
                List(4) { FrameMotion(if (it == 1) Float.NaN else 0.2f, 0.1f, 0f, 1f) }
            )
        )
        val threw = runCatching { DraftClipCodec.encodeClip(poisoned).toString() }.exceptionOrNull()
        if (threw != null) {
            flag("a clip holding a NaN cannot be written at all (${threw.javaClass.simpleName}: ${threw.message}) - " +
                "an autosave throwing is a draft lost, so a non-finite number has to be answered before it is put")
        } else {
            val read = DraftClipCodec.decodeClip(
                org.json.JSONObject(DraftClipCodec.encodeClip(poisoned).toString()), ClipKind.Video
            )
            if (read == null) flag("a clip holding a NaN was written and then read back as nothing")
            else {
                // Left out of the document, so each reads back as the default
                // the decoder itself declares - nothing is invented twice.
                if (read.scale != 1f) flag("a NaN scale read back as ${read.scale}, not the decoder's own 1")
                if (read.volume != 1f) flag("a NaN volume read back as ${read.volume}, not 1")
                if (read.opacity != 1f) flag("an infinite opacity read back as ${read.opacity}, not 1")
                if (read.rotation != 0f) flag("an infinite rotation read back as ${read.rotation}, not 0")
                if (read.offsetXFraction != 0f) flag("a NaN offset read back as ${read.offsetXFraction}, not 0")
                if (read.adjust.contrast != 0f) flag("a NaN contrast read back as ${read.adjust.contrast}, not 0")
                // And in an array a zero is written rather than the place
                // dropped, because the measurement is four parallel columns:
                // one short column and every frame after it reads another
                // frame's motion.
                val m = read.stabilizerMeasurement
                if (m == null || m.motions.size != 4) {
                    flag("a NaN in one of the stabilizer's four columns shortened it to ${m?.motions?.size} motions - " +
                        "the columns are read in step, so every frame after it would take another frame's motion")
                } else if (m.motions[1].dx != 0f) {
                    flag("a NaN motion read back as ${m.motions[1].dx}, not 0")
                }
                if (read.adjust.wheels.lift.r != 0f) flag("a NaN wheel component read back as ${read.adjust.wheels.lift.r}")
                if (read.adjust.wheels.lift.g != 0.5f) {
                    flag("the wheel's other two components did not survive beside a NaN: ${read.adjust.wheels.lift}")
                }
            }
        }
    }

    // ---- 6. And the codec may not reach put() with a float again. ---------
    //
    // The fix above is only a fix while every float goes through putFinite, and
    // a new field is written by copying the line above it. Checked as text,
    // because that is the only way to see a `put(..., x.toDouble())` that
    // somebody will add next week.
    listOf("app/src/main/java/com/squish/app/data/DraftCodec.kt",
           "app/src/main/java/com/squish/app/data/DraftClipCodec.kt").forEach { path ->
        val text = java.io.File(path).takeIf { it.isFile }?.readText()
        if (text == null) { flag("$path is not there - this check has rotted"); return@forEach }
        if (!text.contains("putFinite(")) flag("$path no longer uses putFinite at all")
        Regex("""(?m)^.*put\([^\n]*\.toDouble\(\)\).*$""").findAll(text).forEach { m ->
            flag("${path.substringAfterLast('/')} puts a number straight into the document: ${m.value.trim()} - " +
                "use putFinite, or a NaN there throws the whole autosave away (data/DraftNumbers.kt)")
        }
        // And the other end of the same mistake, which the round trip above
        // found by reading NaN back out of a key that was not there:
        // `optDouble(name)` with no second argument answers **NaN**, not zero.
        // So every field read that way came back NaN from a draft written
        // before the field existed - and now also from one where putFinite
        // dropped a non-finite value - and a NaN placement is an invisible
        // clip rather than a clip at nothing.
        Regex("""optDouble\([^,)]*\)""").findAll(text).forEach { m ->
            flag("${path.substringAfterLast('/')} reads ${m.value} with no default, which is NaN for a key " +
                "that is not there - give it the field's own default explicitly")
        }
    }

    report()
}

private fun report() {
    println("draft round trip: ${Clip::class.primaryConstructor?.parameters?.size} clip fields, through JSON text")
    if (problems.isEmpty()) {
        println("PASS - a clip with everything on it survives a draft, twice over")
    } else {
        println("FAIL (${problems.size})")
        problems.take(25).forEach { println("  - $it") }
        exitProcess(1)
    }
}
