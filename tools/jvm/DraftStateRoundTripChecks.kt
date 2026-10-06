import com.squish.app.data.DraftCodec
import com.squish.app.data.ProjectSnapshot
import com.squish.app.editor.BeatProgress
import com.squish.app.editor.CanvasBackground
import com.squish.app.editor.CanvasFill
import com.squish.app.editor.CaptionSource
import com.squish.app.editor.CropAspect
import com.squish.app.editor.CropRect
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.EffectKind
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TimedEffect
import com.squish.app.media.ExportQuality
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.system.exitProcess

/*
 * The edit-wide half of a draft: what the whole project carries, as opposed to
 * what a clip or a line does.
 *
 * The third and last of the round trips (tools/jvm/DraftRoundTripChecks.kt for a
 * clip, DraftTextRoundTripChecks.kt for a line), and the one docs/ROADMAP.md §6
 * said would cost the most. It did, and most of the cost was not where it looked:
 *
 *  - ProjectSnapshot - the *declared contract* of what a draft holds - was
 *    declared inside ProjectAutosave, the one file in data/ that needs a
 *    Context, while every function that fills it had already moved out. It is
 *    data/ProjectSnapshot.kt now.
 *  - EditorModels.kt imports nothing from Compose, and reached it in exactly one
 *    place: EffectSpan carried an ImageVector and a Color. Two presentation
 *    values on a model, for a glyph and a tint that Glyphs.kt derives from the
 *    effect's kind anyway - and they put EditorUiState and every rule hanging
 *    off it out of reach of this harness. EffectSpan carries the kind now.
 *  - What is left is three stands-in, in tools/jvm/stub/EditorState.kt, whose
 *    header says exactly what they do and do not let a suite claim.
 *
 * The asymmetry worth knowing: `encode` takes the live EditorUiState and
 * `decode` answers a ProjectSnapshot, so this is not a round trip of one type
 * but a comparison of two - which is the honest shape of the thing, because the
 * snapshot is the contract and the state is everything the editor happens to be
 * holding. So the fields are matched by name through a map, and the map is what
 * is checked: every field of ProjectSnapshot is either read off the state here
 * or named as derived, so one added to the contract and to neither fails by
 * name.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }

/**
 * Fields of [ProjectSnapshot] that no field of the state answers, with the
 * reason. Both are worked out rather than carried.
 */
private val DERIVED = mapOf(
    // Stamped onto the document by ProjectAutosave.save *after* encode, so that
    // the signature it compares is of the edit alone - otherwise every tick
    // would look like a change. encode writes no such key, and decode's own
    // answer for a missing one is 0.
    "savedAtMillis" to "put on the document by save(), after encode, so the signature is of the edit alone",
    "clipCount" to "the size of the clips list"
)

/** Each field of [ProjectSnapshot], and what it is read off the state. */
private val FROM_STATE: Map<String, (EditorUiState) -> Any?> = mapOf(
    "sourceUri" to { s: EditorUiState -> s.sourceUri },
    "clips" to { s -> s.videoClips },
    "audioClips" to { s -> s.audioClips },
    "textOverlays" to { s -> s.textOverlays },
    "effects" to { s -> s.effects },
    "markers" to { s -> s.markers },
    "playheadMs" to { s -> s.playheadMs },
    "outputP" to { s -> s.outputP },
    "fitToSize" to { s -> s.fitToSize },
    "targetSizeMb" to { s -> s.targetSizeMb },
    "audioOnly" to { s -> s.audioOnly },
    "muteOriginal" to { s -> s.muteOriginal },
    "originalVolume" to { s -> s.originalVolume },
    "rotationDegrees" to { s -> s.rotationDegrees },
    "cropAspect" to { s -> s.cropAspect },
    "cropRect" to { s -> s.cropRect },
    "snapToMarkers" to { s -> s.snapToMarkers },
    "stabilizeStrength" to { s -> s.stabilizeStrength },
    "beats" to { s -> s.beats },
    "canvasBackground" to { s -> s.canvasBackground },
    "pixelsPerSecond" to { s -> s.pixelsPerSecond },
    // The one field whose name differs, and the reason it does: a draft calls
    // it the project's name, the editor calls it the project's name too, and
    // the state's field is projectName because `name` on a 90-field state said
    // nothing.
    "name" to { s -> s.projectName },
    "outputFps" to { s -> s.outputFps },
    "quality" to { s -> s.quality },
    "hevc" to { s -> s.hevc },
    "keepHdr" to { s -> s.keepHdr },
    "captionSource" to { s -> s.captionSource },
    "captionLanguage" to { s -> s.captionLanguage }
)

private val SOUND_ID = "beat-sound"

/** An edit with every persisted field moved off its default. */
private fun wildState(): EditorUiState = EditorUiState(
    projectId = "p-wild",
    sourceUri = android.net.Uri.parse("content://media/external/video/media/7"),
    projectName = "A whole project",
    durationMs = 30_000L,
    playheadMs = 7_400L,
    markers = listOf(500L, 2_500L, 9_000L),
    snapToMarkers = false,
    outputP = 720,
    fitToSize = true,
    targetSizeMb = 24,
    audioOnly = true,
    outputFps = 24,
    quality = ExportQuality.entries.last(),
    hevc = true,
    keepHdr = true,
    muteOriginal = true,
    originalVolume = 0.37f,
    rotationDegrees = 270,
    cropAspect = CropAspect.entries.last(),
    // Not the whole frame: a crop that came back full used not to be written at
    // all, and the panel still read Custom.
    cropRect = CropRect(0.08f, 0.12f, 0.9f, 0.94f),
    canvasBackground = CanvasBackground(CanvasFill.Image, 0xFF334455.toInt(), "stills/backdrop.png"),
    textOverlays = listOf(
        TextOverlayItem(id = "line-1", text = "A line", startMs = 0L, endMs = 1_500L, colorArgb = 0xFFFFFFFF.toInt())
    ),
    captionSource = CaptionSource.entries.last(),
    captionLanguage = "ml-IN",
    effects = listOf(TimedEffect(id = "fx-1", kind = EffectKind.entries.last(), startMs = 200L, endMs = 1_800L)),
    stabilizeStrength = 0.72f,
    // Only the fields a draft carries are moved: `running`, `failed`,
    // `listeningTo` and `failedOutsideWindow` are how a listen is *going*, and
    // the encoder deliberately writes none of them - their defaults are the
    // only values a draft can produce, which is why comparing the whole object
    // still says something. `finished` is true here on purpose and not to match
    // the decoder: a grid read off disk *is* a finished listen, and if it came
    // back false the Beats panel would say a listen was still pending on a
    // project just opened. And clipId has to name a sound that is actually in
    // the edit - decode drops one that does not, so a grid cannot be pinned to
    // a clip that has been deleted (asserted on its own below).
    beats = BeatProgress(
        finished = true,
        bpm = 128.5f,
        confidence = 0.81f,
        beatsMs = listOf(0L, 468L, 937L),
        downbeatOffset = 2,
        clipLabel = "The song",
        clipId = SOUND_ID,
        every = 4
    ),
    videoClips = listOf(
        Clip(id = "shot-1", kind = ClipKind.Video, label = "Shot", sourceInMs = 0L, sourceOutMs = 4_000L, timelineStartMs = 0L)
    ),
    audioClips = listOf(
        Clip(id = SOUND_ID, kind = ClipKind.Audio, label = "The song", sourceInMs = 0L, sourceOutMs = 30_000L, timelineStartMs = 0L)
    ),
    pixelsPerSecond = 73.5f
)

/** An edit with nothing set on it but the one thing a draft cannot be read without. */
private fun plainState(): EditorUiState = EditorUiState(
    projectId = "p-plain",
    sourceUri = android.net.Uri.parse("content://media/external/video/media/1")
)

/** Where two values differ, named as deep as the models go - see the clip suite. */
@Suppress("UNCHECKED_CAST")
private fun describe(path: String, before: Any?, after: Any?, depth: Int = 0): String {
    if (before == after) return ""
    val leaf = "$path: the edit had <$before>, the draft gave back <$after>"
    if (depth >= 4 || before == null || after == null) return leaf
    if (before::class != after::class) return leaf
    if (before is List<*> && after is List<*>) {
        if (before.size != after.size) return "$path: the edit had ${before.size}, the draft gave back ${after.size}"
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

@Suppress("UNCHECKED_CAST")
private fun snapshotReader(name: String): KProperty1<ProjectSnapshot, Any?>? =
    ProjectSnapshot::class.memberProperties.firstOrNull { it.name == name } as? KProperty1<ProjectSnapshot, Any?>

private fun trip(state: EditorUiState): ProjectSnapshot? =
    DraftCodec.decode(org.json.JSONObject(DraftCodec.encode(state).toString()))

fun main() {
    val fields = ProjectSnapshot::class.primaryConstructor?.parameters?.mapNotNull { it.name }.orEmpty()
    if (fields.size < 25) {
        flag("only ${fields.size} constructor parameters found on ProjectSnapshot - reflection has rotted " +
            "and every answer below is meaningless")
    }

    // ---- 1. The contract is accounted for. --------------------------------
    //
    // Every field of ProjectSnapshot is read off the state here or named as
    // derived. A field added to the contract and to neither fails by name, and
    // that is the whole guard against this suite falling behind the model.
    fields.forEach { name ->
        if (name !in FROM_STATE && name !in DERIVED) {
            flag("ProjectSnapshot.$name is in neither this suite's FROM_STATE nor its DERIVED list - say which " +
                "field of EditorUiState it comes back as, or why it is worked out rather than carried. Until " +
                "then nothing checks that it survives a draft.")
        }
    }
    (FROM_STATE.keys + DERIVED.keys).filterNot { it in fields }.forEach {
        flag("\"$it\" is listed in this suite and is no longer a field of ProjectSnapshot")
    }

    val wild = wildState()
    val plain = plainState()

    // ---- 2. The fixture itself. -------------------------------------------
    FROM_STATE.forEach { (name, read) ->
        if (read(wild) == read(plain)) {
            flag("the fixture leaves the field behind ProjectSnapshot.$name at the plain edit's value " +
                "(${read(plain)}) - set it in wildState() to something else. Until then the comparison below " +
                "passes this field whether the codec carries it or not.")
        }
    }

    // ---- 3. The edit, written to a draft and read back. -------------------
    val back = trip(wild)
    if (back == null) {
        flag("decode answered nothing for a draft encode had just written")
        report()
        return
    }
    FROM_STATE.forEach { (name, read) ->
        val got = snapshotReader(name)?.get(back)
        if (read(wild) != got) {
            flag("a draft does not carry it - " + describe("ProjectSnapshot.$name", read(wild), got))
        }
    }
    // The two that are worked out: one must be the clips' own count, and the
    // other must be absent rather than invented, because save() stamps it.
    if (back.clipCount != wild.videoClips.size) {
        flag("the draft's clipCount is ${back.clipCount} for ${wild.videoClips.size} clips")
    }
    if (back.savedAtMillis != 0L) {
        flag("encode wrote a savedAtMillis of ${back.savedAtMillis} - it is save()'s to stamp, and writing it " +
            "here would put the clock in the signature, so every autosave tick would read as a change")
    }

    // ---- 4. A bare edit reads back bare. ----------------------------------
    val bare = trip(plain)
    if (bare == null) flag("decode answered nothing for an edit with nothing set on it")
    else FROM_STATE.forEach { (name, read) ->
        val got = snapshotReader(name)?.get(bare)
        if (read(plain) != got) {
            flag("an edit nothing was set on comes back carrying something - " +
                describe("ProjectSnapshot.$name", read(plain), got))
        }
    }

    // ---- 5. The three documents decode refuses. ---------------------------
    //
    // Each for its own reason, and each worth one assertion: refusing the wrong
    // one loses somebody's project, and refusing none of them opens an editor on
    // damage. Written out separately because a document can fail more than one
    // guard at once - a first attempt here left "clips" out and so never
    // reached the sourceUri guard it meant to test, and passed while that guard
    // was deleted.
    run {
        val whole = DraftCodec.encode(wild).toString()
        fun without(key: String) = org.json.JSONObject(whole).apply { remove(key) }
        fun with(key: String, value: Any) = org.json.JSONObject(whole).apply { put(key, value) }

        if (DraftCodec.decode(without("sourceUri")) != null) {
            flag("a draft with no sourceUri was read back as an edit - half the editor reads it without asking")
        }
        if (DraftCodec.decode(without("clips")) != null) {
            flag("a draft with no clips *list* was read back as an edit - a missing list is damage, and the " +
                "backup is the better answer (an empty list is not: the last shot can be deleted)")
        }
        if (DraftCodec.decode(with("version", DraftCodec.OLDEST_READABLE_VERSION - 1)) != null) {
            flag("a draft older than OLDEST_READABLE_VERSION was read back as an edit")
        }
        if (DraftCodec.decode(with("version", DraftCodec.FORMAT_VERSION + 1)) != null) {
            flag("a draft from a newer build than this one was read back as an edit")
        }
        // And the one that must *not* be refused: an edit whose shots have all
        // been deleted is a real edit - only sounds and words left - and
        // refusing it brought a deleted shot back from the backup, or lost the
        // project once both were empty.
        val emptied = DraftCodec.decode(with("clips", org.json.JSONArray()))
        if (emptied == null) flag("an edit with every shot deleted was refused - that loses the project")
        else if (emptied.audioClips.size != wild.audioClips.size) {
            flag("an edit with every shot deleted lost its sounds too")
        }
        // The floor as a literal, not as DraftCodec's own constant.
        //
        // Reading the constant here is what the first version of this did, and
        // it could not catch the thing it was for: raising the floor to 14
        // narrowed the loop below with it, so nothing was refused and the suite
        // passed while every draft written before this build had been orphaned.
        // A check that reads the number it is checking is no check.
        //
        // 9 because that is what has been shipped, and the fields added since
        // all have defaults, so nine reads. Raising it is a decision to throw
        // away the drafts on somebody's phone, and it should cost whoever makes
        // it the trouble of changing this line and reading this paragraph.
        if (DraftCodec.OLDEST_READABLE_VERSION != 9) {
            flag("OLDEST_READABLE_VERSION is ${DraftCodec.OLDEST_READABLE_VERSION}, not 9 - every draft stamped " +
                "below that is now refused, and refused silently: the dashboard simply does not list it. If that " +
                "is meant, change the 9 in this suite and say in the commit which drafts are being given up.")
        }
        if (DraftCodec.FORMAT_VERSION < 14) {
            flag("FORMAT_VERSION went backwards to ${DraftCodec.FORMAT_VERSION} - a build that stamps a lower " +
                "number writes drafts an older build will read as its own")
        }
        // And every version in between has to read.
        (9..DraftCodec.FORMAT_VERSION).forEach { v ->
            if (DraftCodec.decode(with("version", v)) == null) {
                flag("a draft stamped version $v is refused, though 9..${DraftCodec.FORMAT_VERSION} must all read")
            }
        }
    }

    // ---- 6. A beat grid cannot be pinned to a clip that is gone. ----------
    //
    // Deliberate, and the kind of rule that only a run shows: the grid names
    // the sound it was found on, and a draft whose sound has since been deleted
    // must come back with the grid unpinned rather than pointing at nothing.
    run {
        val orphaned = wild.copy(audioClips = emptyList())
        val got = trip(orphaned)
        if (got == null) flag("an edit whose beat grid names a deleted sound read back as nothing")
        else if (got.beats.clipId != null) {
            flag("a beat grid naming a sound that is no longer in the edit came back pinned to it " +
                "(clipId=${got.beats.clipId})")
        }
    }

    // ---- 7. The encoder may read nothing but plain fields. ----------------
    //
    // This is what makes tools/jvm/stub/EditorState.kt a stand-in rather than a
    // fiction. The three names it stands for are reached only by computed
    // properties of the state - sourceIsHdr, the codec answer - and the stub
    // answers "not known" for all of them. That is harmless for exactly as long
    // as the codec reads no computed property, so it is asserted rather than
    // assumed.
    run {
        val source = java.io.File("app/src/main/java/com/squish/app/data/DraftCodec.kt")
        if (!source.isFile) flag("DraftCodec.kt is not there - this check has rotted") else {
            val params = EditorUiState::class.primaryConstructor?.parameters?.mapNotNull { it.name }?.toSet().orEmpty()
            if (params.size < 60) flag("only ${params.size} parameters on EditorUiState - reflection has rotted")
            val read = Regex("""\bstate\.([a-zA-Z][A-Za-z0-9]*)""").findAll(source.readText())
                .map { it.groupValues[1] }.toSortedSet()
            if (read.size < 20) flag("only ${read.size} state fields found in the codec - the pattern has rotted")
            read.filterNot { it in params }.forEach {
                flag("the codec reads state.$it, which is not a constructor parameter of EditorUiState but a " +
                    "computed property - and a computed property may go through MediaCompat, which this harness " +
                    "only stands in for (tools/jvm/stub/EditorState.kt). Either read a plain field, or the stub " +
                    "has to become real.")
            }
        }
    }

    report()
}

private fun report() {
    println("draft state round trip: ${ProjectSnapshot::class.primaryConstructor?.parameters?.size} fields of an edit, through JSON text")
    if (problems.isEmpty()) {
        println("PASS - an edit with everything set on it survives a draft, and a bare one comes back bare")
    } else {
        println("FAIL (${problems.size})")
        problems.take(25).forEach { println("  - $it") }
        exitProcess(1)
    }
}
