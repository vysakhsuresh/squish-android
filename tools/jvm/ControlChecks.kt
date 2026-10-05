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

    // ---- A knob that names a unit reads in that unit. ----------------------
    run {
        // "Beats per second" over a 0..1 fraction read "50%", which is neither
        // beats nor seconds. Any EffectKind whose parameter names a unit must
        // carry a readout; and the rate it reads must come from the same
        // function FxParams uses, or the number and the picture drift apart.
        val timed = read("$SRC/editor/TimedEffect.kt")
        val unitKnobs = Regex("""\w+\("[^"]+",\s*"([^"]*(?:per second|seconds|degrees|pixels)[^"]*)"([^)]*)\)""")
            .findAll(timed).toList()
        // A check that matches nothing passes for the wrong reason, so the two
        // that exist today are the floor.
        check(
            unitKnobs.size >= 2,
            "no effect knob names a unit any more - either they were renamed, in which case this check " +
                "needs the new words, or the regex has rotted and is passing on nothing"
        )
        unitKnobs.forEach { m ->
            check(
                m.groupValues[2].contains("{"),
                "the effect knob \"${m.groupValues[1]}\" names a unit but has no readout, so its " +
                    "0..1 fraction shows as a percent under a label that promises a rate"
            )
        }
        check(
            Regex("""fun punchPerSecond""").containsMatchIn(timed) &&
                Regex("""punchPerSecond\(amount\)""").containsMatchIn(timed) &&
                Regex("""heartbeatPerSecond\(amount\)""").containsMatchIn(timed),
            "FxParams no longer takes Punch's or Heartbeat's rate from the function the slider's readout " +
                "reads, so the number over the slider can drift from the one the shader uses"
        )
    }

    // ---- A long decode stops when nobody is waiting for it. -----------------
    run {
        val pcm = read("$SRC/media/audio/PcmDecoder.kt")
        check(
            pcm.contains("private suspend fun decodeFrames") && pcm.contains("ensureActive()"),
            "PcmDecoder's codec loop no longer checks for cancellation - a waveform read is up to an hour " +
                "of audio, and a sound deselected would leave it running to the end on the IO pool"
        )
        check(
            pcm.contains("catch (c: CancellationException)"),
            "PcmDecoder's blanket catch would swallow the cancellation and report the file as having no sound"
        )
    }

    // ---- Deleting a sound takes its analysis with it. -----------------------
    run {
        val audio = read("$SRC/editor/edits/AudioEdits.kt")
        val remove = audio.substringAfter("fun removeAudioClip").substringBefore("\n    fun ")
        check(
            remove.contains("syncJob?.cancel()") && remove.contains("syncClipId = null"),
            "removeAudioClip no longer stops the Auto-sync listening to the sound it deletes, so a " +
                "\"Matched\" status lands for a clip id undo can bring back"
        )
    }

    // ---- A pass over every clip says so. ------------------------------------
    run {
        // evenOutVolume measures every piece of footage that is heard, overlays
        // included; the button that starts it must be offered on that same
        // count, and must not promise shots.
        val sheet = read("$SRC/editor/AudioSheet.kt")
        check(
            sheet.contains("Even out volume across clips") &&
                sheet.contains("state.videoClips.count { it.isFootage } > 1"),
            "the Even out volume button no longer counts footage (it used to show for a photo and one " +
                "video, with nothing to compare) or still says \"shots\" for a pass that changes overlays too"
        )
        check(
            !sheet.contains("\"Apply to all shots\""),
            "the Voice sheet's Apply button says \"all shots\" again, while setVoiceForAll maps over " +
                "every video clip, overlay rows included"
        )
    }

    // ---- A claim about every shot is counted, not any-ed. -------------------
    run {
        val frame = read("$SRC/editor/FrameSheet.kt")
        check(
            frame.contains("state.videoClips.count { it.isMain && it.reframe != null }"),
            "the auto-reframe card is back to an any{} - one reframed shot out of ten would read " +
                "\"the crop follows the subject in each shot\""
        )
    }

    // ---- One slider, so the dot cannot come back. --------------------------
    run {
        // Material 3 draws a stop indicator on the inactive track - a filled dot
        // at the far end - which on the full-screen scrub bar reads as a marker
        // sitting on the video. SquishSlider is the only place that calls
        // Material's Slider, with the track drawn without it; a second call site
        // would be a second slider that has the dot and nobody would look.
        readAll("$SRC").forEach { (path, text) ->
            if (path.endsWith("/ui/components/SquishSlider.kt")) return@forEach
            check(
                !Regex("""(?m)^\s*(Slider|RangeSlider)\(""").containsMatchIn(text),
                "$path builds a Material slider of its own - every slider goes through SquishSlider, which " +
                    "is where the stop-indicator dot is turned off"
            )
        }
        val squish = read("$SRC/ui/components/SquishSlider.kt")
        check(
            squish.contains("drawStopIndicator = null"),
            "SquishSlider no longer turns the stop indicator off, so every slider has a dot at its end again"
        )
    }

    // ---- A cache can remember the answer "nothing" --------------------------
    run {
        // getOrPut reads a stored null as absent and calls its lambda again, so
        // a cache of a nullable thing cannot remember a miss. Three of those in
        // one day: the export's blended-still bitmaps, the preview's, and the
        // caption renderer's typefaces - where the missing font was looked for
        // on disk again for every caption of every frame. Nothing nullable goes
        // through getOrPut; containsKey does.
        val nullable = Regex("""getOrPut\([^)]*\)\s*\{[^}]*(?:getOrNull\(\)|takeIf|\?\.let\()""", RegexOption.DOT_MATCHES_ALL)
        readAll(SRC).forEach { (path, text) ->
            nullable.findAll(text).forEach {
                problems += "$path caches something that can be null through getOrPut, which reads a stored " +
                    "null as absent and recomputes it every time - use containsKey"
            }
        }
    }

    // ---- A cut is one shot becoming another --------------------------------
    run {
        val audio = read("$SRC/editor/edits/AudioEdits.kt")
        check(
            audio.contains("CutSounds.joinsOf(current.videoClips.filter { it.isMain }, current.pictureEndMs)"),
            "\"Sound on every cut\" measures its joins against the edit's length again - where a song runs " +
                "on past the last shot that makes the picture's end a join, and a sound is laid into black"
        )
    }

    // ---- Cuts and the playhead always snap ---------------------------------
    run {
        // The switch is "Snap to markers and beats". Cuts, 0:00 and the end of
        // the edit are none of those, and the strip says so twice. Both scrub
        // paths had the whole lookup behind the switch, so with it off a drag
        // held on nothing while a trim handle an inch away still held on the cut.
        val editor = read("$SRC/timeline/TimelineEditor.kt")
        check(
            !Regex("""if\s*\(!latestSnapScrub\)\s*null""").containsMatchIn(editor),
            "the strip's scrub gates its whole snap on the markers switch again - cuts and the ends go with it"
        )
        val model = read("$SRC/editor/EditorViewModel.kt")
        check(
            Regex("""val snapped = snapToAnything\(target, current\)""").containsMatchIn(model),
            "EditorViewModel.scrubTo gates its whole snap on snapToMarkers again"
        )
        check(
            Regex("""if \(current\.snapToMarkers\) current\.markers""").containsMatchIn(model),
            "snapToAnything no longer keeps the markers behind the switch, so turning it off changes nothing"
        )
    }

    // ---- A control that is not drawn does not take touches ------------------
    run {
        val handles = read("$SRC/editor/OverlayHandles.kt")
        check(
            handles.contains("latestShowBox && roomy"),
            "the overlay box's corner hit test no longer checks `roomy`, which the drawing does - with the " +
                "keyboard up the four buttons are hidden and their touch zones would stay live, so a tap on " +
                "bare picture past a corner deletes the line being typed"
        )
        check(
            handles.contains("canOpen"),
            "the box's double tap no longer asks whether there is anything to open - on a clip overlay the " +
                "handler returns without acting, and the quick repeat stands in for the tap that cycles a stack"
        )
    }

    // ---- A corner is a corner wherever the finger is ------------------------
    run {
        val crop = read("$SRC/editor/CustomCropOverlay.kt")
        check(
            !Regex("""Grip\.(Top|Bottom)(Left|Right)\s*(?:->|\bto\b)[^\n]*!inside""").containsMatchIn(crop) &&
                !Regex("""&&\s*!inside\s*->\s*Grip\.""").containsMatchIn(crop),
            "the hand-drawn crop's corners are gated on being outside the rectangle again - the brackets are " +
                "drawn inward and the window opens at the whole picture, so no touch is ever outside and a " +
                "corner cannot be grabbed at all"
        )
    }

    // ---- A square inside a scrolling sheet lets go of what it did not grab --
    run {
        val curve = read("$SRC/editor/CurveEditor.kt")
        check(
            !Regex("""(?m)^\s*detectDragGestures\(""").containsMatchIn(curve),
            "the Curves square is back on detectDragGestures, which takes and consumes every drag whether or " +
                "not a point was under the finger - the sheet behind it cannot then be scrolled"
        )
    }

    // ---- What a held thing is keyed on is what it was built from ------------
    //
    // The preview holds a blended still per base surface, built from the still
    // and the shot under it, closing over the still's blend mode, its opacity
    // track and where it sits. It was re-made only when the two *ids* changed -
    // and an edit makes a new Clip with the same id, so changing a still's
    // Blend mode or its Opacity left the surface drawing the one it had until
    // the shot under it changed. Keyed on the two clips by identity it is
    // exact, and a tick with no edit hands back the same objects.
    run {
        val engine = read("$SRC/editor/PreviewEngine.kt")
        check(
            engine.contains("s.blendStillClip !== still || s.blendUnderClip !== clip"),
            "the preview's blended still is no longer re-made when its clip changes - keyed on ids, a new " +
                "blend mode or opacity on the same still never reaches the surface"
        )
        check(
            !engine.contains("val key = still?.id to clip.id"),
            "the blended still is back on an id-only key, which cannot see a mode, an opacity or a move"
        )
    }

    // ---- Two handles on one strip do not cover each other -------------------
    //
    // This used to assert the shape of the arithmetic that was inline here -
    // "val share =" and "width = targetDp" - which held it to one particular
    // wrong answer: that split closes the gap only while the kept stretch is at
    // least a target wide, and below that the two overlapped again. The
    // geometry is `TrimRules.handleBoxes` now, where it can be executed, and
    // TrimRulesChecks sweeps every width of keep for an overlap. What is left
    // for a text check is that the strip still *asks* it rather than working
    // the widths out again on the spot.
    run {
        val strip = read("$SRC/tools/TrimStrip.kt")
        check(
            strip.contains("TrimRules.handleBoxes("),
            "Snip's trim handles are no longer laid out by TrimRules.handleBoxes, so nothing executed holds " +
                "them apart - below a 48 dp keep the end handle, drawn second, covers the start bar and takes its touches"
        )
        check(
            !strip.contains("val share = "),
            "the old inline split is back in TrimStrip: it meets in the middle of the *overlap*, which closes " +
                "the gap only while the keep is at least one target wide"
        )
    }

    // ---- A switch has to say which way it is. ------------------------------
    //
    // Role.Switch on a clickable only makes a screen reader say "switch". The
    // "on" or "off" after it comes from the node's ToggleableState, which only
    // Modifier.toggleable (or an explicit semantics block) sets - so every
    // switch in the app announced itself as a switch and never said which way
    // it was. The colour of the track was the only answer, which is no answer
    // at all to someone using TalkBack, and the privacy switch, Keep HDR, Mute
    // and "Ticks when snapping" are all behind this one control.
    run {
        val chips = read("$SRC/ui/components/Chips.kt")
        check(
            chips.contains("SquishToggleSwitch"),
            "SquishToggleSwitch has moved out of Chips.kt - this check has rotted"
        )
        check(
            Regex("\\.toggleable\\(\\s*value\\s*=").containsMatchIn(chips),
            "the app's switch is no longer a Modifier.toggleable, so nothing puts an on/off on its node and " +
                "a screen reader can only say that a switch is there"
        )
        // The same for the chip every option row in the app is built out of -
        // fifty-nine of them. The sweep behind the chosen chip was the only
        // thing that said which one it was, so a chip row read as a list of
        // identical buttons. Modifier.selectable is what puts the state on the
        // node; which role it carries is a separate question and this one
        // carries none on purpose, because the rows are variously tabs, values
        // and toggles.
        check(
            Regex("\\.selectable\\(\\s*selected\\s*=").containsMatchIn(chips),
            "SelectableChip is no longer a Modifier.selectable, so nothing says which chip in a row is the " +
                "chosen one except its colour"
        )

        // And nowhere may claim to be a switch while being a plain click. Said
        // over the whole tree rather than the one file, because the next switch
        // someone writes is the one that will do it.
        readAll(SRC).forEach { (path, text) ->
            Regex("\\.clickable\\([^)]*Role\\.Switch").findAll(text).forEach { m ->
                problems += "$path declares Role.Switch on a clickable (${m.value.trim()}) - it says " +
                    "\"switch\" and never says on or off; Modifier.toggleable is what carries the state"
            }
            Regex("\\.clickable\\([^)]*Role\\.Checkbox").findAll(text).forEach { m ->
                problems += "$path declares Role.Checkbox on a clickable (${m.value.trim()}) - same fault: " +
                    "the ticked state comes from toggleable, not from the role"
            }
        }
    }

    // ---- The level goes after the voice, on both sides. -------------------
    //
    // ProcessorChecks runs the two orders against Media3 and measures how far
    // apart they come out - 18.5% of RMS for Megaphone at half level. Which
    // side is right is a convention, and the convention is the fader after the
    // insert, as every mixing desk has it: turning a clip down makes it
    // quieter and does not make it cleaner. Held as text because neither chain
    // can be built off a phone.
    run {
        val export = read("$SRC/media/VideoProcessor.kt")
        val voiceAt = export.indexOf("processors.add(VoiceProcessor")
        val levelAt = export.indexOf("processors.add(GainProcessor")
        check(voiceAt >= 0, "the export's chain no longer adds a VoiceProcessor - this check has rotted")
        check(
            levelAt > voiceAt,
            "the export applies a clip's level before its voice. The saturating voices are tanh, which is " +
                "not linear, so the file then disagrees with the preview about the timbre and not only the " +
                "loudness - Megaphone at half level came out clean in the file and a loud-hailer on screen"
        )
        check(
            export.contains("AudioMixing.processor(1f)"),
            "the export's mixer carries a gain again: its matrix runs before the voice, so the gain in it is " +
                "the level arriving on the wrong side"
        )
        val preview = read("$SRC/editor/PreviewEngine.kt")
        val pVoice = preview.indexOf("VoiceProcessor { chain.voice")
        val pGain = preview.indexOf("GainProcessor { chain.gain")
        check(pVoice >= 0 && pGain >= 0, "the preview's sound chain has been rewritten - this check has rotted")
        check(
            pGain > pVoice,
            "the preview puts a sound's boost before its voice, where its player's own volume applies the rest " +
                "of the level after it: the two halves of one split level would land on opposite sides of a tanh"
        )
    }

    // ---- One blur, in four copies that have to be the same four lines. -----
    //
    // The nine taps of a Defocus join and of the library's own Blur live in
    // four files - the effects pass, the transition pass, an overlay's closing
    // premultiply pass, and CanvasFx's AGSL copy for the preview's Compose
    // layers - because each runs in a different place and none of them can call
    // the others. `check_shaders.py` compares uniform *names*; nothing compared
    // the loop. One of the four read its taps without clamping, so what a tap
    // at the frame's edge returned was the sampler's wrap mode rather than this
    // shader's decision.
    run {
        // The clamp is written at the tap in the three GLSL copies and inside
        // the AGSL copy's own `texel`, which is where that one turns y over -
        // so each is checked where it keeps it rather than where the others do.
        val blurs = listOf(
            Triple("app/src/main/assets/squish_fx_es2.glsl", "texture2D", "texture2D\\(uTexSampler, clamp\\(uv \\+ "),
            Triple("app/src/main/assets/squish_transition_es2.glsl", "texture2D", "texture2D\\(uTexSampler, clamp\\(uv \\+ "),
            Triple("app/src/main/assets/squish_premultiply_es2.glsl", "texture2D", "texture2D\\(uTexSampler, clamp\\(uv \\+ "),
            Triple("$SRC/editor/CanvasFx.kt", "texel", "float3 texel\\(float2 uv\\) \\{\\s*\\n\\s*float2 flipped = float2\\(clamp\\(uv\\.x")
        )
        for ((path, sampler, clamped) in blurs) {
            val text = read(path)
            if (text.isEmpty()) continue
            // Nine taps: a -1..1 pair of loops, not some other count.
            check(
                Regex("for \\(int i = -1; i <= 1; i\\+\\+\\)").containsMatchIn(text) &&
                    Regex("for \\(int j = -1; j <= 1; j\\+\\+\\)").containsMatchIn(text),
                "$path no longer takes its blur as a -1..1 box of nine taps"
            )
            check(
                Regex("sum / 9\\.0").containsMatchIn(text),
                "$path divides its blur by something other than nine"
            )
            // Every tap clamped into the frame, in all four.
            check(
                Regex(clamped).containsMatchIn(text),
                "$path takes a blur tap without clamping it into the frame, so what a tap at the edge " +
                    "returns is the sampler's wrap mode and not this shader's decision - the other three clamp"
            )
            // And it is the same helper the loop reads through, so the clamp
            // the line above found is on the path the taps take.
            check(
                Regex("sum \\+= $sampler\\(").containsMatchIn(text),
                "$path's blur loop no longer reads through $sampler, so the clamp checked above may not be on its path"
            )
            // And the early-out at the same threshold, or a join that softens
            // in one pass does nothing in another.
            check(
                Regex("uBlur <= 0\\.0001").containsMatchIn(text),
                "$path leaves its blur early at some other threshold than 0.0001"
            )
        }
    }

    // ---- A pending row is deleted on *both* ways out. ----------------------
    //
    // A gallery copy starts as a MediaStore row with IS_PENDING set, and a row
    // nothing ever writes to is invisible to the gallery, holds the name, and
    // is only cleared when Android expires a pending item a week later. Both
    // files that insert one tidied up when the copy *threw*; one of them
    // returned straight past the tidying when the provider handed back no
    // stream at all - `openOutputStream(target)?.use { … } ?: return`, which is
    // the shape this looks for.
    run {
        for (name in listOf("media/GallerySaver.kt", "media/gif/GifMaker.kt")) {
            val text = read("$SRC/$name")
            if (text.isEmpty()) continue
            check(
                text.contains("IS_PENDING"),
                "$name no longer inserts a pending row - this check has rotted"
            )
            // A stream that comes back null must not take a return with it: the
            // row has to be deleted first. Matched across the line break,
            // since the `?:` is usually on the next line.
            check(
                // Lazily, and across braces: the `use` block has a nested
                // `use` in it, so a pattern that stopped at the first `}`
                // matched nothing and said so by passing.
                !Regex("openOutputStream\\([^)]*\\)\\?\\.use \\{.*?\\}\\s*\\?:\\s*return", RegexOption.DOT_MATCHES_ALL)
                    .containsMatchIn(text),
                "$name returns straight out when openOutputStream gives nothing, leaving the pending row it " +
                    "had just inserted behind - delete it the way the catch does"
            )
        }
    }

    // ---- The curve editor reads the curve in the curve's own order. --------
    //
    // It holds an index into the list of points and clamps a dragged point
    // between its neighbours *in that list*. Read off `points`, which is
    // whatever order a draft happened to store, a point's floor could be above
    // its ceiling - and `coerceIn` on an inverted range throws. `Curve.ordered`
    // is sorted and has two points on one x reduced to one, which is what both
    // the index and the clamp assume.
    run {
        val editor = read("$SRC/editor/CurveEditor.kt")
        check(editor.contains(".ordered"), "CurveEditor no longer reads Curve.ordered - this check has rotted")
        check(
            !Regex("of\\(channel\\)\\.points").containsMatchIn(editor),
            "CurveEditor reads a curve's raw points again: its index and its neighbour clamp both assume " +
                "sorted, deduped points, and coerceIn on an inverted range throws"
        )
        check(
            !Regex("drawn\\.points").containsMatchIn(editor),
            "CurveEditor draws a curve's raw points again, so an out-of-order draft is drawn as a zigzag"
        )
    }

    // ---- Float output off, or the sound processors are not in the chain. ---
    //
    // DefaultAudioSink puts the processors it was given into its pipeline on
    // the *int* branch only: with float output on it adds a to-float processor
    // instead and the chain is not there at all. So the voice, the fold-down
    // and the boost would stop working, with nothing thrown and nothing logged.
    // The sink the preview builds therefore drops the enableFloatOutput
    // parameter it is handed rather than passing it on - which reads like an
    // oversight and is the opposite.
    run {
        val engine = read("$SRC/editor/PreviewEngine.kt")
        check(
            engine.contains("DefaultAudioSink.Builder(context)"),
            "the preview no longer builds its own audio sink - this check has rotted"
        )
        check(
            !Regex("setEnableFloatOutput").containsMatchIn(engine),
            "the preview's sink enables float output, which takes the voice, the fold-down and the boost " +
                "out of DefaultAudioSink's pipeline altogether - silently"
        )
    }

    // ---- A slider reaches everything its value may be. ---------------------
    //
    // The Size slider ran 12..120 while a line's size may be 8..200 - which is
    // what a pinch on the picture is held to. A line pinched past 120 showed
    // its real size in the readout with the thumb pinned at the end, and the
    // first touch anywhere on the track shrank it to 120. The control
    // disagreed with the label beside it, and the control was the destructive
    // one. The shape panel's Size had the same gap, 22..198 of a possible
    // 8..200.
    //
    // So a Size slider's range is built from the model's own constants. Spelt
    // as text because a Compose slider cannot be executed here.
    run {
        for ((name, file) in listOf("the text sheet" to "editor/TextSheet.kt", "the shapes panel" to "editor/ShapesPanel.kt")) {
            val text = read("$SRC/$file")
            if (text.isEmpty()) continue
            val size = text.substringAfter("\"Size\"", "").take(400)
            check(size.isNotEmpty(), "$file no longer has a Size slider - this check has rotted")
            check(
                size.contains("TextStyleSpec.MIN_SIZE_SP") && size.contains("TextStyleSpec.MAX_SIZE_SP"),
                "$name's Size slider sets its range from numbers of its own rather than from " +
                    "TextStyleSpec's limits, so a line or a shape pinched past the slider's end is shrunk " +
                    "by the first touch on the track"
            )
        }
    }

    // ---- A storage row counts what Clear can reach. ------------------------
    //
    // `sizeOf` measures with walkTopDown, so a row counts the whole tree. The
    // sweep took a flag and was called with it off for two of the kinds, so a
    // file in a subdirectory was counted and unreachable: Clear took the number
    // to nothing on screen and left the bytes on the disk. Twice - the stills'
    // backdrops first, then every downloaded stock clip under
    // files/imports/stock. The flag is gone; the sweep walks the tree the row
    // measures.
    run {
        val cleaner = read("$SRC/settings/StorageCleaner.kt")
        check(cleaner.contains("private fun sweep("), "StorageCleaner has no sweep any more - this check has rotted")
        check(
            !Regex("recurse").containsMatchIn(cleaner),
            "StorageCleaner's sweep takes a recurse flag again: every row measures its whole tree with " +
                "walkTopDown, so a sweep that stops at the top counts files it cannot delete"
        )
        check(
            Regex("dir\\.walkTopDown\\(\\)").containsMatchIn(cleaner),
            "StorageCleaner's sweep no longer walks the tree"
        )
        // And one list of folders, read by the measuring and the sweeping
        // both, so a folder cannot be counted by a row and left out of its
        // Clear. `music/online` had been left out of both, so a downloaded
        // track could not be got rid of from inside the app at all.
        check(
            cleaner.contains("private fun renderDirs("),
            "StorageCleaner no longer keeps one list of the folders a row covers, so the measuring and " +
                "the sweeping can name different ones again"
        )
        check(
            cleaner.contains("OnlineMusic.DIR"),
            "downloaded music is not in StorageCleaner's folders, so nothing counts it and nothing clears it"
        )
        check(
            Regex("renderDirs\\(context\\)").findAll(cleaner).count() >= 2,
            "the folder list is read in only one place - the point of it is that both read it"
        )
    }

    // ---- A sticker is a sticker because it says so. ------------------------
    //
    // TextOverlayItem carries a `sticker` flag, and clearCaptions and the SRT
    // export both read it. Translate captions guessed instead - "more than two
    // code points" - so a short real caption was skipped *and* left out of the
    // tally it reports: "Hi", "No", "OK", and every one- or two-character line
    // in Chinese or Japanese, which is a whole sentence. The sheet said three
    // of three were translated with one still in the old language.
    run {
        val text = read("$SRC/editor/edits/TextEdits.kt")
        val translate = text.substringAfter("fun translateCaptions(", "").substringBefore("viewModelScope").take(600)
        check(translate.isNotEmpty(), "translateCaptions has moved - this check has rotted")
        check(
            translate.contains("sticker"),
            "Translate captions does not read the sticker flag, so it is guessing which lines are stickers"
        )
        check(
            !translate.contains("codePointCount"),
            "Translate captions is counting code points again: a short caption is a caption, and the flag " +
                "that says what a sticker is is right there on the item"
        )
    }

    // ---- The edit's length is the edit's, not the file it was opened on. ---
    //
    // trimmedDurationMs is the header, the export sheet, the file's length and
    // the recovery card, and the drafts list writes it into the sidecar. It
    // fell back to the lead source file's own trim window whenever there was no
    // *picture* - so an edit whose shots had all been deleted, which is a
    // supported state and the one a sound-only edit is in, reported the length
    // of the file it came from. The fallback is for a project with nothing laid
    // down at all.
    run {
        val models = read("$SRC/editor/EditorModels.kt")
        val getter = models.substringAfter("val trimmedDurationMs: Long", "").take(800)
        check(getter.isNotEmpty(), "trimmedDurationMs has moved - this check has rotted")
        check(
            !getter.contains("if (videoClips.isEmpty())"),
            "the edit's length falls back to the source file's window whenever there is no picture, so an " +
                "edit of sounds alone reports the length of the clip it was opened on"
        )
        check(
            getter.contains("audioClips.maxOfOrNull"),
            "the edit's length no longer looks at the sounds before falling back"
        )
    }

    // ---- A bin's expiry is not its listing. --------------------------------
    //
    // A listing has no way to release a read grant. ProjectAutosave learned
    // that and split its expiry out into expireOldTrash; ToolAutosave kept
    // deleting expired entries inside trashed(), so a binned Trim or Squeeze
    // left to age out held its picker grant until the app was uninstalled -
    // and the phone caps how many of those an app may keep. Both bins are
    // expired by the caller now, and both hand back what they released.
    run {
        for (name in listOf("data/ProjectAutosave.kt", "data/ToolAutosave.kt")) {
            val text = read("$SRC/$name")
            if (text.isEmpty()) continue
            check(
                text.contains("fun expireOldTrash("),
                "$name has no expireOldTrash, so whatever expires inside its listing releases nothing"
            )
            val listing = text.substringAfter("fun trashed(", "").take(1200)
            check(listing.isNotEmpty(), "$name has no trashed() any more - this check has rotted")
            check(
                !listing.contains("deleteRecursively"),
                "$name deletes inside its listing again: a listing cannot release the grants of what it removes"
            )
        }
        val home = read("$SRC/home/HomeViewModel.kt")
        check(
            home.contains("toolAutosave.expireOldTrash()"),
            "the dashboard expires the projects' bin and not the quick tools', so one of the two still leaks"
        )
    }

    println("controls: the conventions that, broken, make a control lie")
    if (problems.isEmpty()) println("PASS - the playhead is fixed, the strip follows the finger, and every list has a branch for every entry")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
