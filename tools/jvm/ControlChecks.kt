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

    // ---- "Has the gallery row gone?" is not "is it in the Bin?" ------------
    //
    // Since Android 11 the gallery's Bin is a MediaStore flag, and a query with
    // no arguments hides every row that carries it. So moving an export to the
    // Bin read as deleted for good: the library row was forgotten and
    // history.json rewritten, and restoring the video brought the file back
    // with no way to get the row back - the one thing that function promises
    // not to do to a file that still exists.
    run {
        val history = read("$SRC/data/HistoryRepository.kt")
        val probe = history.substringAfter("suspend fun forgetDeleted()", "").take(1400)
        check(probe.isNotEmpty(), "forgetDeleted has moved - this check has rotted")
        check(
            probe.contains("QUERY_ARG_MATCH_TRASHED"),
            "the \"has it gone?\" probe does not count trashed rows, so an export moved to the gallery's Bin " +
                "is forgotten for good and restoring it cannot bring the row back"
        )
    }

    // ---- Nothing in the app can send a body. -------------------------------
    //
    // The promise on Settings' privacy card, and the one thing in this app that
    // would be worth a user's anger: footage, photos and projects never leave
    // the phone. `Online` is the only thing that opens a connection, both of
    // its calls take a URL and give back bytes, and `open` sets a timeout, a
    // redirect policy and a User-Agent and nothing else - no request method, no
    // doOutput - so every request is a GET. This holds that shape, because a
    // promise nothing checks is a promise waiting to be broken by a helpful
    // refactor.
    run {
        val online = read("$SRC/online/Online.kt")
        check(online.contains("HttpURLConnection"), "Online.kt no longer opens a connection - this check has rotted")
        check(
            !Regex("doOutput").containsMatchIn(online),
            "Online.kt sets doOutput, which is how a request gets a body - nothing here may upload"
        )
        check(
            !Regex("setRequestMethod|requestMethod\\s*=").containsMatchIn(online),
            "Online.kt sets a request method: every request here is a GET, and a POST is an upload"
        )
        // And nothing else in the app opens one behind its back. The list is
        // every way out of the process there is, not only the one in use:
        // checked once against the whole tree, and there is none of it, so
        // naming them all costs nothing and closes the door.
        readAll(SRC).forEach { (path, text) ->
            if (path.endsWith("online/Online.kt")) return@forEach
            Regex(
                "openConnection\\(\\)|HttpURLConnection|URLConnection|OkHttpClient|Retrofit|" +
                    "io\\.ktor|java\\.net\\.Socket|DatagramSocket|SSLSocket|WebView|RequestQueue"
            ).findAll(text).forEach { m ->
                problems += "$path reaches the network directly (${m.value}) - every request goes through " +
                    "Online.get or Online.download, which refuse while the Settings switch is off"
            }
        }
    }

    // ---- Nothing in tools/ or media/ is offered and never called. ----------
    //
    // ThumbnailCache.evict was written to forget a thumbnail "for a file that
    // was deleted or replaced" and had no callers at all, so deleting an
    // export left its picture in cache/thumbs until somebody cleared the whole
    // cache from Settings. Dead code shaped like an API is the same fault as a
    // dead `else` shaped like a fallback: it says a path exists that nothing
    // takes, and nobody can test what it would have done.
    run {
        val callers = readAll(SRC).filterNot { (path, _) -> path.endsWith("media/ThumbnailCache.kt") }
        check(
            callers.any { (_, text) -> text.contains("ThumbnailCache.evict(") },
            "ThumbnailCache.evict has no callers again - either something should be calling it when a " +
                "file goes, or it should not exist"
        )
    }

    // ---- A thing you choose says it is chosen, and says what it is. --------
    //
    // Every colour swatch in the app was a bare circle whose only content was
    // its own fill and whose only state was a border colour: no name, no
    // selected semantics, and under the 44 dp the rest of the app uses. With a
    // screen reader on, a colour row was nine identical nameless targets and
    // nothing said which one was applied - and ColourRow is the only colour
    // control there is, so there was no non-visual route to a caption's, an
    // outline's, a shadow's, a bubble's, a shape's, the canvas background's or
    // a cut-out's colour at all. The eyedropper beside them has always carried
    // "Pick a colour from the picture", which is how you can tell this was a
    // gap and not the house style.
    //
    // Six picker grids outside Chips.kt had the same shape: a plain clickable
    // marked chosen by a border, so a reader read the applied filter exactly as
    // it read the other twenty-nine. And the three grading wheels were a Canvas
    // in a Box with two pointer inputs - a Box whose only child is a Canvas has
    // no node to focus, so the disc was skipped entirely and its value could
    // neither be read nor changed without a drag.
    run {
        // One swatch, used by every palette.
        val text = read("$SRC/editor/TextSheet.kt")
        check(
            Regex("""internal fun ColourSwatch\([\s\S]{0,800}?\.selectable\(selected = selected, role = Role\.RadioButton[\s\S]{0,400}?contentDescription = ColourName\.of\(colour\)""")
                .containsMatchIn(text),
            "the colour swatch is not named and selectable - a circle whose only content is its fill " +
                "announces nothing, and a border colour is not a state"
        )
        // And no palette draws its own any more.
        listOf("editor/FrameSheet.kt", "editor/BackgroundPanel.kt").forEach { name ->
            val panel = read("$SRC/$name")
            check(
                panel.contains("ColourSwatch("),
                "$name draws its own colour circles instead of the named, selectable ColourSwatch"
            )
        }
        // ColourNameChecks keeps a copy of each palette, because the suites
        // cannot compile a Compose file. These hold the copies honest: a tenth
        // colour fails here and points at the suite, rather than quietly
        // arriving uncovered - and what the suite asserts is that a palette's
        // names are all *different*, which a new colour can break.
        listOf(
            Triple("editor/TextSheet.kt", "CAPTION_COLOURS", 9),
            Triple("editor/FrameSheet.kt", "CANVAS_COLOURS", 9),
            Triple("editor/BackgroundPanel.kt", "BACKDROPS", 7)
        ).forEach { (name, palette, count) ->
            val body = Regex("""private val $palette = listOf\(([\s\S]*?)\)\s*\n""")
                .find(read("$SRC/$name"))?.groupValues?.get(1)
            val n = body?.let { Regex("""0x[0-9A-Fa-f]{8}""").findAll(it).count() }
            check(
                n == count,
                "$palette has ${n ?: "no readable list of"} colours, not $count - add it to " +
                    "tools/jvm/ColourNameChecks.kt's copy and check the names are still all different"
            )
        }
        // The six grids that mark a choice with a border.
        listOf(
            "editor/LooksSheet.kt" to "the Filters tiles",
            "editor/TransitionSheet.kt" to "the transition tiles",
            "editor/AudioSheet.kt" to "the voice tiles and the track rows"
        ).forEach { (name, what) ->
            check(
                read("$SRC/$name").contains(".selectable(selected = "),
                "$what carry no selected state, so nothing says which one is on ($name)"
            )
        }
        val styleTiles = Regex("""\.selectable\(selected = selected, role = Role\.RadioButton""").findAll(text).count()
        check(styleTiles >= 3, "TextSheet has $styleTiles selectable tiles - the style presets and the lines list need theirs")
        // A choice that cannot be taken says it cannot, rather than reading as
        // merely not chosen and swallowing the tap.
        val chips = read("$SRC/ui/components/Chips.kt")
        check(
            chips.contains(".selectable(selected = selected, enabled = enabled, onClick = onClick)"),
            "SelectableChip has no enabled again - a chip drawn dead at alpha 0.35 then keeps a live " +
                "selectable, announces \"not selected\" rather than \"disabled\", and eats the tap"
        )
        check(
            read("$SRC/ui/components/OutputSizePicker.kt").contains("enabled = !beyond,"),
            "the size picker draws a size dead without saying so to a reader"
        )
        // And the one control that permanently forgets something is named.
        check(
            text.contains("contentDescription = \"Forget the style \$name\""),
            "the x that forgets a saved style is unnamed again - a screen reader announces it as " +
                "\"multiplication sign\", right after the chip it destroys, and there is no undo"
        )
        // The "More…" pad behind the swatches. Two raw pointer inputs over a
        // Canvas, and a Box whose only child is a Canvas has no node to focus -
        // so that route to a colour was not reachable by swipe at all, and the
        // only readable thing left was the hex readout, which is output rather
        // than a control.
        check(
            text.contains("contentDescription = \"Shade and brightness\"") &&
                text.contains("contentDescription = \"Colour\""),
            "the colour picker's square or its hue strip has no name - neither can be focused at all " +
                "without one, so \"More…\" is not a route to a colour"
        )
        check(
            Regex("""customActions = actions\(\s*\n\s*"Stronger"""").containsMatchIn(text) &&
                Regex("""customActions = actions\(\s*\n\s*"Next colour"""").containsMatchIn(text),
            "the colour picker has no actions - a drag on an unlabelled surface is then the only way to " +
                "reach a colour that is not one of the nine swatches"
        )
        // The wheel.
        val wheel = read("$SRC/editor/WheelPad.kt")
        check(
            wheel.contains("stateDescription = spokenTint(wheel)") && wheel.contains("customActions = actions"),
            "the grading wheel has no spoken state or no actions - it is a Canvas in a Box, which a " +
                "screen reader cannot focus at all, and a drag is the only way to change it"
        )
    }

    // ---- A picked text file is read in the encoding it is in. --------------
    //
    // bufferedReader() is UTF-8 and decodeToString() is UTF-8, and both
    // *replace* what they cannot read. A subtitle file in a legacy single-byte
    // encoding - Notepad's "ANSI", and most subtitle archives - has ASCII
    // timing lines, so the cues parsed, the import reported success, and every
    // accented or non-Latin letter in the words had quietly become U+FFFD. The
    // app's own round trip was safe because it writes UTF-8; only files from
    // elsewhere were corrupted, and nothing warned. PickedText.decode is the
    // one reader now.
    run {
        readAll(SRC).forEach { (path, text) ->
            if (path.endsWith("data/PickedText.kt")) return@forEach
            // An HTTP response body is UTF-8 by spec and is not a picked file.
            if (path.endsWith("online/Online.kt")) return@forEach
            Regex("""openInputStream\([^)]*\)[\s\S]{0,200}?(bufferedReader\(\)|decodeToString\(\))""")
                .findAll(text).forEach { m ->
                    problems += "$path reads a picked file as UTF-8 with replacement (${m.groupValues[1]}) - " +
                        "a file in a legacy encoding comes back peppered with U+FFFD and nothing says so. " +
                        "PickedText.decode tries the BOM, then UTF-8 strictly, then the reader's own language."
                }
        }
    }

    // ---- A name the user gave is never reduced to ASCII. -------------------
    //
    // An imported font's and an imported LUT's stored name was the picked
    // file's display name with everything outside [A-Za-z0-9 _-] deleted,
    // falling back to "font" / "Look" when nothing was left - and that stem is
    // what the chip shows. So a font named in Cyrillic, Greek, Arabic,
    // Devanagari or CJK lost its whole name: the first landed as "font", the
    // second as "font 2", with nothing to tell them apart, and the draft
    // recorded them under those names. Android's filesystem takes UTF-8 names,
    // so nothing downstream wanted the stripping. Only what a file name cannot
    // hold comes out now.
    // The three exempt files build a cache or download filename out of a
    // machine id (OnlineMusic's track id, OnlineStock's video id) or a Google
    // Fonts family name, which is ASCII by definition - and in each the label
    // someone reads comes from the record, not from the filename. Named here
    // with the reason rather than quietly passing.
    run {
        val asciiNameExempt = listOf("online/OnlineFonts.kt", "online/OnlineMusic.kt", "online/OnlineStock.kt")
        readAll(SRC).forEach { (path, text) ->
            if (asciiNameExempt.any { path.endsWith(it) }) return@forEach
            Regex("""\[\^A-Za-z0-9[^]]*\]""").findAll(text).forEach { m ->
                problems += "$path reduces a name to ASCII (${m.value}) - a name in Cyrillic, Greek, " +
                    "Arabic, Devanagari or CJK is deleted outright by that, and the filesystem never " +
                    "wanted it. Take out only what a file name cannot hold."
            }
        }
    }

    // ---- Time runs left to right, whatever the phone's language is. --------
    //
    // Both strips compute a left-origin pixel from a moment and place it with
    // `offset`, which is the layout-direction-aware modifier: placeRelative
    // mirrors x to parentWidth - childWidth - x. The manifest declares
    // supportsRtl and nothing provided a direction, so on an Arabic, Hebrew,
    // Persian or Urdu phone every clip, handle, diamond and line would have
    // been drawn mirrored against a playhead and a drag that were not - the
    // same class as a control that moves against the finger. Held for the
    // subtree rather than per modifier, so the Rows inside keep their order.
    run {
        listOf("timeline/TimelineEditor.kt", "tools/TrimStrip.kt").forEach { name ->
            check(
                read("$SRC/$name").contains("CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)"),
                "$name does not hold its layout direction - its x is a left-origin pixel and `offset` " +
                    "mirrors in RTL, so the strip would be drawn against its own playhead and drags"
            )
        }
    }

    // ---- A formatted number is never cut up by an ASCII literal. -----------
    //
    // `"...".format(...)` is java.lang.String.format against the default
    // locale, so %d emits the phone's own digits. Five call sites took ".000"
    // off the end of a timecode, which matched nothing on a phone set to
    // Arabic, Persian, Bengali, Nepali or Burmese: every label grew from four
    // characters to nine, and the strip's ruler - a tick label a second, laid
    // out with no width given - ran into itself. The fix is to leave the part
    // out of the format string, which Timecode.format(ms, withMillis) does.
    // The sibling call sites that use substringBefore('.') are safe, because
    // the literal '.' is copied through the format string itself.
    run {
        readAll(SRC).forEach { (path, text) ->
            Regex("""removeSuffix\("\.0+"\)|removePrefix\("0""").findAll(text).forEach { m ->
                problems += "$path cuts a formatted number up with an ASCII literal (${m.value}) - " +
                    "format() with no locale emits the phone's own digits, so the literal matches nothing " +
                    "and the whole string is kept. Leave the part out of the format string instead."
            }
        }
    }

    // ---- "Opened just to look" outlives the process. -----------------------
    //
    // A video opened by another app and never edited is binned when the editor
    // closes - but the rule was held in an in-memory field set on the fresh
    // path alone, and its one durable record is the .start file that the first
    // save deletes, inside loadFresh. So a look closed by the process going
    // rather than by a back press - which is what swiping the app off recents
    // does - stayed on the dashboard for good as a project nobody made, holding
    // a picker grant nothing could release (a draft names the file, so
    // ProjectRules.releasable refuses). Reopening it and pressing back did not
    // take it off either, because on that path the field was never set.
    run {
        val autosave = read("$SRC/data/ProjectAutosave.kt")
        check(
            autosave.contains("fun openedJustToLook(slot: String): Boolean"),
            "ProjectAutosave no longer records \"opened from outside\" past the first save"
        )
        check(
            Regex("""put\("openedFromOutside", true\)""").containsMatchIn(autosave),
            "the sidecar no longer carries openedFromOutside, so the one record of a look is the .start " +
                "file that save() deletes"
        )
        val editor = read("$SRC/editor/EditorViewModel.kt")
        check(
            Regex("""openedJustToLook = withContext\(Dispatchers\.IO\) \{ autosave\.openedJustToLook\(""")
                .containsMatchIn(editor),
            "a reopened project does not read back whether it was opened just to look, so a look that " +
                "survived a process death can never be taken off the grid"
        )
    }

    // ---- A pick waits for the edit to be open. -----------------------------
    //
    // Killed behind the photo picker, the app comes back and
    // ActivityResultRegistry dispatches the pending result while the launcher's
    // effect commits - before open() has read a byte of the draft. Only Replace
    // and Relink waited; the other four ran against the default empty state, so
    // the add landed at playhead 0 on an empty timeline, applyDraft then
    // replaced the clip lists wholesale and the pick vanished, nothing of it
    // reached disk, and the undo step *survived* - so one tap on Undo restored
    // the empty snapshot and blanked the whole edit, which the next autosave
    // tick wrote over the draft.
    run {
        val screen = read("$SRC/editor/EditorScreen.kt")
        listOf(
            "viewModel.audio.addAudioTrack(" to "addAudioWhenOpened",
            "viewModel.layers.addOverlayClips(" to "addOverlaysWhenOpened",
            "viewModel.clips.setCanvasImage(" to "setCanvasImageWhenOpened"
        ).forEach { (direct, waiting) ->
            check(
                !screen.contains(direct),
                "EditorScreen calls $direct straight from a picker - it has to be $waiting, or a pick " +
                    "delivered before the draft is read lands on an empty edit"
            )
        }
        // insertSourcesAtPlayhead has one direct caller left: the stock-footage
        // sheet, which is a Compose sheet inside the open editor and cannot be
        // reached before open().
        check(
            Regex("""viewModel\.clips\.insertSourcesAtPlayhead\(""").findAll(screen).count() == 1,
            "EditorScreen has more than one direct insertSourcesAtPlayhead - a picker's must be " +
                "insertWhenOpened; only the stock sheet, which cannot run before the edit is open, is direct"
        )
        check(
            screen.contains("viewModel.insertWhenOpened(uris)"),
            "the media picker no longer waits for the edit to be open"
        )
    }

    // ---- A blank new line is only discarded once the edit is there. --------
    //
    // newLineId and the open tool come back from saved state after a process
    // kill and the selection does not, so the guard did not hold on the first
    // composition: the effect fired against the default empty state, where
    // discardIfBlank finds no such line and does nothing, and cleared newLineId
    // - so when the draft was applied the line came back with nothing left
    // pointing at it, and the project carried a caption reading "Your text" for
    // good, with no undo step for it either.
    run {
        val screen = read("$SRC/editor/EditorScreen.kt")
        check(
            Regex("""LaunchedEffect\(openTool, state\.selectedClipId, state\.isLoadingSource\) \{\s*\n\s*if \(state\.isLoadingSource\) return@LaunchedEffect""")
                .containsMatchIn(screen),
            "the blank-line discard runs before the draft has been read - it clears newLineId against an " +
                "empty edit, and the line comes back with nothing left to take it off"
        )
    }

    // ---- A result that lands in the background goes beneath a drag. --------
    //
    // recordLate exists for it ("a slider being dragged when Stabilize finished
    // became two steps, one either side of it"), and every lander in the editor
    // uses it - Stabilize, Auto-sync, Find the beat, Background, Auto-reframe,
    // Freeze, Reverse, Auto adjust, Duck under speech, Even out volume, Remove
    // silences, Translate captions. Read aloud was built after recordBeneathOpen
    // existed and never picked it up, so a sound landing mid-drag split one
    // finger movement into two undo steps with "Read aloud" wedged between
    // them, and the drag could not be taken back without losing the sound.
    //
    // A `record(` inside a coroutine launched from one of the edit areas is the
    // shape of that mistake; the check reads each launch and asks.
    //
    // Nothing is exempt any more. The two that were - "To main track" and the
    // subtitle import - reached the model through `mutateTimeline`, whose pure
    // equivalent was a private copy in AudioEdits and another in ClipEdits; it
    // is `EditArea.withTimeline` now, one copy, and both landings go through
    // recordLate.
    val recordLateExempt = emptyList<Pair<String, String>>()
    run {
        readAll("$SRC/editor/edits").forEach { (path, text) ->
            // Each `viewModelScope.launch {` body, up to the next declaration
            // at the same indent. Crude, and it only has to be good enough to
            // see a bare `record(` where `recordLate(` belongs.
            Regex("""viewModelScope\.launch \{""").findAll(text).forEach { m ->
                val body = text.substring(m.range.first, minOf(text.length, m.range.first + 4000))
                val ends = body.indexOf("\n    }")
                val inside = if (ends > 0) body.substring(0, ends) else body
                inside.lines().forEach { line ->
                    val code = line.substringBefore("//")
                    if (!Regex("""(^|[^a-zA-Z.])record\(""").containsMatchIn(code)) return@forEach
                    if (recordLateExempt.any { (label, _) -> code.contains("\"$label\"") }) return@forEach
                    problems += "$path files a background result with record( rather than recordLate( " +
                        "(\"${code.trim()}\") - record closes whatever gesture is open, so a slider under " +
                        "the finger when the result lands becomes two undo steps with the result between " +
                        "them, and the drag cannot be taken back without losing the result"
                }
            }
        }
    }

    // And one copy of the pure timeline edit, so no landing has an excuse.
    run {
        check(
            read("$SRC/editor/edits/EditArea.kt").contains("protected fun EditSnapshot.withTimeline("),
            "EditArea has no withTimeline - without it a landing that reaches the timeline model has no " +
                "pure edit to hand recordLate, which is why two of them stayed on record for a day"
        )
        readAll("$SRC/editor/edits").forEach { (path, text) ->
            if (path.endsWith("EditArea.kt")) return@forEach
            if (text.contains("fun EditSnapshot.withTimeline(")) {
                problems += "$path keeps its own copy of withTimeline - there is one in EditArea"
            }
        }
    }

    // ---- A one-shot command carries no gesture id. -------------------------
    //
    // A gesture id folds a per-frame stream into one step. moveEffect is called
    // once, on the drop, and carried one anyway - and nothing closed it, since
    // endGesture is wired to the trim handles and not to the lift-and-drop
    // path. So two deliberate carries of the same effect inside the coalescing
    // window became one step and one Undo sent it back past both. UndoStack's
    // own header says the rule: "a discrete action now carries no gesture id
    // and is never merged with anything."
    run {
        val clips = read("$SRC/editor/edits/ClipEdits.kt")
        check(
            Regex("""fun moveEffect\([^)]*\) = record\("Move effect"\) \{""").containsMatchIn(clips),
            "moveEffect carries a gesture id again - the strip calls it once, on the drop, and nothing " +
                "closes the step, so two carries inside 700 ms merge into one"
        )
    }

    // ---- A whole sound is never decoded to draw a few dozen bars. ----------
    //
    // PcmDecoder.decodePeaks exists for this: one float per 50 ms, read
    // straight off the decoder, so an hour of audio costs a few hundred
    // kilobytes. Three places still went through decodeMono, which holds the
    // whole decimated file - a ten-minute sound is 4.8 million floats, and the
    // doubling plus the final copy is about 38 MB live at the peak - and which
    // gives up and returns null on a tight heap *by design*, so the wave
    // silently never appeared. decodeMono's remaining callers all want the
    // samples themselves (beat detection, auto-sync, the speech segmenter, the
    // silence probe), and none of them builds a waveform.
    // The check is structural rather than a grep for callers, which is stronger:
    // there is no longer any way to make a Waveform out of decoded samples.
    // WaveformBuilder's only MonoPcm-taking function is `envelope`, which is
    // auto-sync's cross-correlation signal and does want the samples.
    run {
        val wave = read("$SRC/media/audio/Waveform.kt")
        val fromSamples = Regex("""fun (\w+)\([^)]*pcm: MonoPcm""").findAll(wave).map { it.groupValues[1] }.toList()
        check(
            fromSamples == listOf("envelope"),
            "WaveformBuilder can build from decoded samples again ($fromSamples) - a waveform is one float " +
                "per 50 ms off the decoder (PcmDecoder.decodePeaks), and decoding the whole sound first held " +
                "millions of floats for a few dozen bars and gave up on a long file by design, so the wave " +
                "silently never appeared. `envelope` is the one exception: auto-sync correlates samples."
        )
    }

    // ---- A .part survives nothing, a cancellation included. ----------------
    //
    // ProxyEngine deleted its half-written copy on both of its own exits and
    // not on a cancellation - and a cancellation is the ordinary case, because
    // the only thing that cancels it is leaving the editor, which is what a
    // user does while the notice over the strip still says "Building a light
    // preview copy · 37%". About a hundred megabytes for a fifteen-minute clip
    // abandoned there, counted in Settings' storage figure, and one more orphan
    // for every different clip abandoned. ReverseRenderer has always wrapped
    // its body in runCatching for exactly this.
    run {
        // The shape is: catch everything round the await, delete the .part,
        // *then* rethrow. So the delete has to come before the rethrow, and
        // there has to be a rethrow - without one the delete is on the happy
        // path only, which is what it was.
        //
        // Named shapes rather than a general grep, because "the delete is on
        // the path a throw takes" is not something text can be asked in
        // general: each of these files has a pre-emptive delete before the
        // await as well, and any rule that only counts deletes is satisfied by
        // that one. So each names where its failure path begins and where it
        // rethrows, and the delete has to sit between the two.
        listOf(
            Triple("media/ProxyEngine.kt", "val outcome = runCatching {", "outcome.exceptionOrNull()"),
            Triple("media/ReverseRenderer.kt", "if (result.isFailure)", "result.exceptionOrNull()?.let"),
            // The download already had the shape, and is here so the check
            // covers three rather than the two that were wrong: an interrupted
            // download would otherwise leave a *partial* file that the caller's
            // `length() == 0L` test reads as complete, which is the same
            // mistake as a truncated JPEG that `exists()` is happy with.
            Triple("online/Online.kt", "catch (t: Throwable) {", "throw t")
        ).forEach { (name, from, to) ->
            val text = read("$SRC/$name")
            val begins = text.indexOf(from)
            val rethrows = text.indexOf(to, maxOf(begins, 0))
            val deleted = Regex("""part(?:ial)?\.delete\(\)""").findAll(text)
                .map { it.range.first }.lastOrNull { it > begins && it < rethrows } ?: -1
            check(
                begins >= 0 && rethrows > begins && deleted in (begins + 1) until rethrows,
                "$name writes a .part and does not delete it on the way out of a cancellation - catch " +
                    "round the await, delete, then rethrow. Leaving the editor is the ordinary case, not " +
                    "a corner: it is what a user does while the notice still shows a percentage."
            )
        }
    }

    // ---- The ceiling asks the encoders Media3 will actually choose from. ----
    //
    // EncoderSelector.DEFAULT returns only the hardware encoders when any
    // exist. Asking the unfiltered list was the failure EncoderCeiling exists
    // to prevent: on a phone whose hardware AVC encoder stops at 1920x1088
    // while AOSP's size-flexible software one advertises 4080x4080, the
    // software encoder won by area, so the sheet left 4K tappable with no note,
    // budgeted a 4K bitrate, and recorded 3840x2160 in the library - while the
    // render wrote 1920x1088.
    run {
        readAll(SRC).forEach { (path, text) ->
            if (!text.contains("getSupportedEncoders(")) return@forEach
            // A bare "is there one at all" question needs no filter.
            if (!Regex("""getSupportedEncoders\([^)]*\)\s*\n?\s*\.?\s*(isNotEmpty|isEmpty)""").containsMatchIn(text) ||
                Regex("""getSupportedEncoders""").findAll(text).count() > 1
            ) {
                if (!text.contains("isHardwareAccelerated")) {
                    problems += "$path picks an encoder out of getSupportedEncoders without preferring the " +
                        "hardware ones - Media3's EncoderSelector.DEFAULT returns only those when any exist, " +
                        "so an answer taken from the whole list is not the encoder the render will use"
                }
            }
        }
    }

    // ---- One rate is printed one way, wherever it is printed. --------------
    //
    // PolishRules.rateLabel's own doc says it is "a rate as the Speed sheet
    // prints it everywhere", and the strip's badge on a retimed clip used
    // PolishRules.number instead - two decimals where the sheet gives one above
    // 1x. The slider rounds to two decimals below 10x, so two-decimal rates are
    // the ordinary case: a shot dragged to 1.25x wore "1.25x" on the strip and
    // said "1.3x" the moment the sheet was opened. They agree below 1x, which
    // is what made it read as working. (The locale fix that put `number` on
    // that line picked the lower-level formatter; both are locale-safe, and
    // rateLabel is the shared one.)
    run {
        val editor = read("$SRC/timeline/TimelineEditor.kt")
        check(
            Regex("""isRamped\) "ramp"\s*\n\s*else com\.squish\.app\.editor\.PolishRules\.rateLabel\(""")
                .containsMatchIn(editor),
            "the strip's speed badge does not print its rate with PolishRules.rateLabel - the Speed sheet " +
                "does, everywhere, and `number` gives a second decimal the sheet does not"
        )
    }

    // ---- A picture is written through a .part and renamed. -----------------
    //
    // Every picture writer in the app does this and one did not. Written
    // straight to its final name, a compress that died part way - the process
    // going, a full disk - leaves a file that `exists()` is happy with, and a
    // truncated JPEG is *not* self-healing: BitmapFactory.decodeFile hands back
    // a partial bitmap rather than null (AOSP accepts kIncompleteInput), so the
    // half-drawn thumbnail was served for ever, with nothing but Settings'
    // "Clear preview thumbnails" to get rid of it. StillClips.blank records the
    // same lesson in so many words, having been broken that way for one frame
    // shape until somebody cleared the stills by hand.
    run {
        readAll(SRC).forEach { (path, text) ->
            Regex("""(?m)^.*\.compress\(.*$""").findAll(text).forEach { m ->
                val line = m.value
                if (!line.contains("partial")) {
                    problems += "$path compresses a bitmap straight to its destination ($line) - " +
                        "it has to go to a .part and be renamed, or a write that dies part way leaves a " +
                        "truncated picture that exists() accepts and decodeFile happily returns half of"
                }
            }
        }
    }

    // ---- A queue of decodes is dropped with the cache it fills. ------------
    run {
        val filmstrip = read("$SRC/media/video/FilmstripLoader.kt")
        check(
            Regex("""fun evictAll\(\)[\s\S]{0,400}?pending\.clear\(\)""").containsMatchIn(filmstrip),
            "FilmstripLoader.evictAll no longer empties the queue - the worker lives on a process-lifetime " +
                "scope, so it carries on decoding the closed project's tiles back into the cache it was " +
                "just told to empty, against the dashboard the user has navigated to"
        )
        check(
            Regex("""fun evictAll\(\)[\s\S]{0,400}?stopping\?\.cancel\(\)""").containsMatchIn(filmstrip),
            "FilmstripLoader.evictAll no longer stops its worker"
        )
    }

    // ---- One sample-size arithmetic, and it is the executed one. -----------
    //
    // Three loops solved their own, and two of them solved it wrongly - one
    // against the short side where the kept size is bounded on the long side
    // too (a panorama decoded whole, 57 MB for a picture kept at 5.9), and one
    // joined with && so the doubling stopped the moment either side would fall
    // under the bound. Both are PictureSample's now, which is executed in
    // tools/jvm/PictureSampleChecks.kt over sixteen shapes.
    run {
        readAll(SRC).forEach { (path, text) ->
            if (path.endsWith("media/PictureSample.kt")) return@forEach
            if (Regex("""sample \*= 2""").containsMatchIn(text)) {
                problems += "$path solves its own sample size - PictureSample.forFit and forLongSide " +
                    "are the two there are, and they are the ones with a suite"
            }
        }
    }

    // ---- A picture is decoded the way up it is meant to be seen. -----------
    //
    // BitmapFactory does not apply the camera's orientation tag and ImageDecoder
    // does, and phone cameras write the tag rather than rotating the pixels, so
    // for a portrait photo straight off a camera the two decoders disagree about
    // which way up it is. previewBitmap went through BitmapFactory while every
    // sibling in the same file went through ImageDecoder - and it is the helper
    // ThumbnailExtractor.cover falls back to for a photo picked as a document
    // URI, so a portrait photo came back lying on its side on the project's
    // cover card, in the library, and written to cache/thumbs at that angle,
    // where it outlived the next restart. The sibling that reads only the
    // picture's *size* (uprightSize) has applied the tag by hand since it was
    // written, for exactly this reason.
    //
    // inSampleSize is the marker of a BitmapFactory decode that wants pixels
    // rather than bounds; a bounds-only read (inJustDecodeBounds) is fine,
    // since it is reading the header and nothing else.
    run {
        readAll(SRC).forEach { (path, text) ->
            if (text.contains("inSampleSize")) {
                problems += "$path decodes a picture's pixels through BitmapFactory (inSampleSize) - " +
                    "that decoder ignores the camera's orientation tag, so a portrait photo comes back " +
                    "on its side. ImageDecoder applies it."
            }
        }
    }

    // ---- A listen that lands after its clip has gone must land on nothing. -
    //
    // AudioEdits runs four background listens, and each writes to the edit or
    // to a panel when it finishes. The clip it is about can leave the edit
    // while it runs - deleted, undone away, or taken by a Select more delete -
    // and removeAudioClip's cancel covers only the first of those three.
    // "Find the beat" was the worst of it: with nothing left matching the
    // target's uri, every remaining sound took the `else clip.copy(beats =
    // emptyList())` branch, so the grid the user already had on another song
    // went off the strip, an undo step was filed for it, and beats.clipId was
    // left naming a clip that is not there. Auto-sync wrote "Matched" for a
    // gone clip, which is the fault its own cancel was added to stop. So every
    // landing looks the clip up again, in the state it lands in.
    run {
        val audio = read("$SRC/editor/edits/AudioEdits.kt")
        check(
            Regex("""fun gone\(sounds: List<Clip>\) = target != null && sounds\.none \{ it\.id == target\.id \}""")
                .containsMatchIn(audio),
            "detectBeats no longer asks whether the sound it listened to is still in the edit"
        )
        check(
            audio.contains("if (gone(snapshot.audioClips)) snapshot else snapshot.copy("),
            "the \"Find the beat\" step no longer refuses to land on an edit its sound has left - " +
                "nothing matches the target's uri then, so every other sound's grid is wiped"
        )
        check(
            audio.contains("gone(state.audioClips)"),
            "the beat card is told a grid was found for a sound that has gone"
        )
        check(
            Regex("""val result = AudioSyncAnalyzer\.detectOffset[\s\S]{0,900}?audioClips\.none \{ it\.id == clipId \}""")
                .containsMatchIn(audio),
            "Auto-sync writes its status without checking the sound is still there"
        )
        check(
            audio.contains("if (beatTargetId == clipId && _state.value.beats.running)"),
            "removing a sound no longer stops a beat listen on it - six minutes of decode for an answer " +
                "nobody can use, and the removal path is the one case a cancel can catch early"
        )
    }

    // ---- A MediaCodec that decodes audio must ask the decoder what a frame is.
    //
    // PcmDecoder read KEY_SAMPLE_RATE and KEY_CHANNEL_COUNT off the
    // *container's* track format, handed the rate to its caller before
    // codec.start(), and had no INFO_OUTPUT_FORMAT_CHANGED branch at all - that
    // constant is -2, so it fell through the `outIndex >= 0` test and was
    // dropped. On HE-AAC (SBR doubles the output rate) and HE-AACv2
    // (parametric stereo decodes a mono-signalled stream to two channels) the
    // two formats disagree, and then every length this layer reports is wrong
    // by that factor: the waveform lane drawn against a file twice or four
    // times its length, the beat detector handed audio that slow, the auto-sync
    // offset scaled. ReverseRenderer had always re-read all three, so the two
    // readers of one file disagreed about what a frame is.
    run {
        readAll(SRC).forEach { (path, text) ->
            if (!text.contains("dequeueOutputBuffer")) return@forEach
            // Video readers hand frames to a Surface and have no PCM layout to
            // get wrong; the audio ones are the ones that must ask.
            if (!Regex("KEY_SAMPLE_RATE|KEY_CHANNEL_COUNT").containsMatchIn(text)) return@forEach
            if (!text.contains("INFO_OUTPUT_FORMAT_CHANGED")) {
                problems += "$path decodes audio with MediaCodec and never reads codec.outputFormat - " +
                    "the container's sample rate and channel count are not the decoder's on HE-AAC, " +
                    "and INFO_OUTPUT_FORMAT_CHANGED is -2, so it falls through `outIndex >= 0` unnoticed"
            }
            if (!text.contains("codec.outputFormat") && !text.contains(".outputFormat")) {
                problems += "$path has the INFO_OUTPUT_FORMAT_CHANGED branch but never reads outputFormat"
            }
        }
    }

    println("controls: the conventions that, broken, make a control lie")
    if (problems.isEmpty()) println("PASS - the playhead is fixed, the strip follows the finger, and every list has a branch for every entry")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
