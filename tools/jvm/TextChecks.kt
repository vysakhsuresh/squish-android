import com.squish.app.editor.CaptionStylePreset
import com.squish.app.editor.OverlayRules
import com.squish.app.editor.TextAnimation
import com.squish.app.editor.TextBubble
import com.squish.app.editor.TextExit
import com.squish.app.editor.TextGeometry
import com.squish.app.editor.TextLook
import com.squish.app.editor.TextLoop
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextPlacement
import com.squish.app.editor.TextStroke
import com.squish.app.editor.TextStyleNames
import com.squish.app.editor.TextStyleSpec
import com.squish.app.editor.TextTiming
import com.squish.app.editor.TitlePreset
import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.SpeechSegment
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.timeline.Transform
import kotlin.math.abs
import kotlin.system.exitProcess

// Text, executed: the looks as presets over the explicit fields, the arrival,
// leaving and loop of a line with their lengths, the reveal of letters and
// words, the box on the picture round the letters (which has to be exactly the
// letters, and to give the letters back when it is moved), and where the
// segmenter hears words begin. Each block is a claim from docs/ROADMAP.md
// batch B10.

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps

fun frame(
    motion: TextMotion = TextMotion.None, exit: TextExit = TextExit.None, loop: TextLoop = TextLoop.None,
    inMs: Long = 450, outMs: Long = 450, loopMs: Long = 1000, at: Long, total: Long = 3000, words: List<Long> = emptyList()
) = TextAnimation.frameAt(motion, exit, loop, inMs, outMs, loopMs, at, total, words)

fun main() {
    // --- A look is a preset over the decorations, and reads back as itself. ---------
    run {
        val base = TextStyleSpec(colorArgb = 0xFFFFD166.toInt(), sizeSp = 40)
        for (look in TextLook.entries) {
            val styled = look.applied(base)
            check(TextLook.of(styled) == look, "$look does not read back as itself")
            check(styled.colorArgb == base.colorArgb && styled.sizeSp == base.sizeSp, "$look changed the colour or size")
        }
        check(TextLook.Outline.applied(base).stroke.isOn && !TextLook.Outline.applied(base).shadow.isOn, "Outline is not a stroke alone")
        check(TextLook.Box.applied(base).background.bubble == TextBubble.Box, "Box is not a box")
        check(TextLook.Plain.applied(TextLook.Neon.applied(base)) == base, "Plain does not clear a look")
        // Tuned past any look, no chip is lit: the sliders own it now.
        val tuned = TextLook.Outline.applied(base).copy(stroke = TextStroke(0xFFFF0000.toInt(), 0.3f))
        check(TextLook.of(tuned) == null, "a tuned edge still reads as Outline")
        check(TextStyleSpec().stroke == TextStroke.NONE, "a bare spec has an edge")
        // What a new line starts as, and what Reset puts back: the outline, not the bare spec.
        check(TextLook.of(TextStyleSpec.NEW_LINE) == TextLook.Outline, "a new line does not start outlined")
        for (preset in TitlePreset.entries) check(TextLook.of(preset.style) == preset.look, "${preset.label}'s style is not its look")
        check(CaptionStylePreset.entries.map { it.style }.distinct().size == CaptionStylePreset.entries.size, "two caption presets are the same style")
        // A saved style's name is the first free number, never one that is taken.
        check(TextStyleNames.next(emptyList()) == "Style 1", "the first saved style")
        check(TextStyleNames.next(listOf("Style 1", "Style 2", "Style 3")) == "Style 4", "the fourth saved style")
        check(TextStyleNames.next(listOf("Style 1", "Style 3")) == "Style 2", "a removed style's number is not reused: ${TextStyleNames.next(listOf("Style 1", "Style 3"))}")
        check(TextStyleNames.next(listOf("Style 2", "Style 3", "Mine")) == "Style 1", "a name of the person's own gets in the way")
    }

    // --- Word timings follow the line's start: a head trim, and a cut between words. ----
    run {
        val starts = listOf(0L, 300L, 600L, 900L)
        // The head dragged 500 ms later: the two words already said show at once, the rest keep their beat.
        check(TextTiming.shifted(starts, 500) == listOf(0L, 0L, 100L, 400L), "a head trim did not move the words: ${TextTiming.shifted(starts, 500)}")
        check(TextTiming.shifted(starts, 0) === starts, "an untouched head copied the words")
        // A tail trim moves nothing.
        check(TextTiming.shifted(starts, -200) == listOf(200L, 500L, 800L, 1100L), "the head dragged earlier did not move the words later")
        // Cut at 500 ms: "one two" stay with the first half, "three four" go with the second, from its own start.
        val halves = TextTiming.split("one two three four", starts, 500)
        check(halves != null, "a cut between words did not split them")
        check(halves?.firstText == "one two" && halves.firstStarts == listOf(0L, 300L), "the first half: ${halves?.firstText} ${halves?.firstStarts}")
        check(halves?.secondText == "three four" && halves.secondStarts == listOf(100L, 400L), "the second half: ${halves?.secondText} ${halves?.secondStarts}")
        // A cut on a word's start puts that word in the second half.
        check(TextTiming.split("one two three four", starts, 600)?.secondText == "three four", "a cut on a word's beat lost it")
        // Nothing to split: the words and the timings disagree, one word, or every word on one side.
        check(TextTiming.split("one two three", starts, 500) == null, "words and timings that disagree were split")
        check(TextTiming.split("one", listOf(0L), 500) == null, "one word was split")
        check(TextTiming.split("one two", listOf(0L, 100L), 500) == null, "a cut after every word split them")
        check(TextTiming.split("one two", emptyList(), 500) == null, "an untimed line was split")
        // The Words arrival on each half lands its words where the speech is.
        val second = frame(motion = TextMotion.Words, at = 99, total = 1000, words = halves!!.secondStarts)
        check(near(second.reveal, 0f), "the second half showed a word before its beat")
        check(near(frame(motion = TextMotion.Words, at = 100, total = 1000, words = halves.secondStarts).reveal, 0.5f), "the second half's first word is late")
    }

    // --- Still is still; an arrival and a leaving each take their time and never overlap. ---
    run {
        for (at in listOf(0L, 100L, 1500L, 2999L)) check(frame(at = at) == com.squish.app.editor.TextFrame(), "a still line moved at $at")
        check(near(frame(motion = TextMotion.Fade, at = 0).alpha, 0f), "Fade in is not invisible at its first frame")
        check(near(frame(motion = TextMotion.Fade, at = 450).alpha, 1f), "Fade in is not done at its length")
        check(near(frame(motion = TextMotion.Fade, inMs = 1000, at = 450).alpha, 1f, 0.2f) && frame(motion = TextMotion.Fade, inMs = 1000, at = 450).alpha < 1f,
            "a longer arrival is not still arriving at 450 ms")
        check(near(frame(exit = TextExit.Fade, at = 2550).alpha, 1f), "Fade out starts early")
        check(frame(exit = TextExit.Fade, at = 2999).alpha < 0.05f, "Fade out has not gone by the last frame")
        check(frame(exit = TextExit.Shrink, at = 2999).scale < 0.35f, "Shrink does not shrink")
        check(frame(exit = TextExit.Drop, at = 2999).rise < -0.1f, "Drop does not fall")
        check(frame(exit = TextExit.Slide, at = 2999).rise < 0f, "Slide out does not sink")
        // A short line: each half of it at most, so the middle frame is whole.
        val short = frame(motion = TextMotion.Fade, exit = TextExit.Fade, inMs = 2000, outMs = 2000, at = 200, total = 400)
        check(near(short.alpha, 1f), "a 400 ms line with 2 s in and out is never whole (alpha ${short.alpha})")
        check(near(frame(motion = TextMotion.Pop, at = 450).scale, 1f) && frame(motion = TextMotion.Pop, at = 300).scale > 1f,
            "Pop does not overshoot and settle")
        check(frame(motion = TextMotion.Slide, at = 0).rise > 0f && near(frame(motion = TextMotion.Slide, at = 450).rise, 0f), "Slide does not rise into place")
        check(frame(motion = TextMotion.Bounce, at = 450).rise == 0f, "Bounce does not come to rest")
    }

    // --- Letters and words are revealed over the arrival, whole words only. -----------
    run {
        check(near(frame(motion = TextMotion.Typewriter, at = 0).reveal, 0f), "the typewriter starts with letters showing")
        check(near(frame(motion = TextMotion.Typewriter, at = 225).reveal, 0.5f), "the typewriter is not half way at half its length")
        check(near(frame(motion = TextMotion.Typewriter, inMs = 900, at = 900).reveal, 1f), "the typewriter is not done at its length")
        check(frame(motion = TextMotion.Typewriter, at = 0).alpha == 1f, "the typewriter fades")
        check(TextAnimation.shownLetters("hello", 0.5f) == "hel", "half of hello is not hel")
        check(TextAnimation.shownLetters("hello", 0f) == "" && TextAnimation.shownLetters("hello", 1f) == "hello", "the ends of a reveal")
        // Words on their beats: the count that have started.
        val beats = listOf(0L, 300L, 600L)
        check(near(frame(motion = TextMotion.Words, at = 0, words = beats).reveal, 1f / 3f), "the first word is not up at once")
        check(near(frame(motion = TextMotion.Words, at = 299, words = beats).reveal, 1f / 3f), "the second word came early")
        check(near(frame(motion = TextMotion.Words, at = 300, words = beats).reveal, 2f / 3f), "the second word came late")
        check(near(frame(motion = TextMotion.Words, at = 600, words = beats).reveal, 1f), "the last word never came")
        // Without beats, evenly over the arrival.
        check(near(frame(motion = TextMotion.Words, inMs = 900, at = 450).reveal, 0.5f), "untimed words are not even")
        check(TextAnimation.shownWords("one two three", 2f / 3f) == "one two", "two thirds of three words")
        check(TextAnimation.shownWords("one two three", 0.4f) == "one two", "a share is rounded up to whole words")
        check(TextAnimation.shownWords("one two three", 1f) == "one two three", "all the words")
        check(TextAnimation.shownWords("one two three", 0f) == "", "no words")
    }

    // --- The loop: periodic about rest, never to black. -------------------------------
    run {
        check(near(frame(loop = TextLoop.Pulse, at = 250).scale, 1.06f) && near(frame(loop = TextLoop.Pulse, at = 750).scale, 0.94f), "Pulse does not breathe")
        check(near(frame(loop = TextLoop.Pulse, at = 1000).scale, 1f), "Pulse is not at rest after a period")
        check(near(frame(loop = TextLoop.Wobble, at = 250).tilt, 4f) && near(frame(loop = TextLoop.Wobble, at = 750).tilt, -4f), "Wobble does not rock")
        check(near(frame(loop = TextLoop.Wobble, loopMs = 500, at = 125).tilt, 4f), "a faster loop does not keep its period")
        check(frame(loop = TextLoop.Bob, at = 250).rise > 0f && frame(loop = TextLoop.Bob, at = 750).rise < 0f, "Bob does not float")
        for (at in 0L..2000L step 50) check(frame(loop = TextLoop.Flicker, at = at).alpha >= 0.7f, "Flicker went dark at $at")
        // The loop rides on the arrival.
        val both = frame(motion = TextMotion.Fade, loop = TextLoop.Pulse, at = 250)
        check(both.alpha < 1f && near(both.scale, 1.06f), "the loop does not ride on the arrival")
    }

    // --- The box is the letters, and the letters come back from the box. ---------------
    run {
        val fw = 1080f; val fh = 1920f
        val gw = 300f; val gh = 60f
        val t = TextGeometry.transformOf(0.5f, 0.5f, 0f, gw, gh, fw, fh)
        val box = OverlayRules.box(t, TextGeometry.aspect(gw, gh), fw, fh)
        check(near(box.cx, 540f) && near(box.cy, 960f), "the box is not where the line is (${box.cx}, ${box.cy})")
        check(near(box.halfW, 150f) && near(box.halfH, 30f), "the box is not the letters (${box.halfW * 2} x ${box.halfH * 2})")
        // A small sticker's box would be under the overlay's floor of 0.1: its own limits keep it.
        val tiny = TextGeometry.transformOf(0.5f, 0.5f, 0f, 80f, 80f, fw, fh)
        check(tiny.scale < 0.1f, "a small sticker's box is not under the overlay floor, so this claim is moot")
        val kept = TextGeometry.limited(tiny, tiny, 64)
        check(near(kept.scale, tiny.scale, 0.0001f), "a small sticker grew when it was touched")
        val before = TextPlacement(0.5f, 0.5f, 28, 0f)
        check(TextGeometry.placed(before, t, t) == before, "an untouched box changed the line")
        // Dragged 108 px right and 192 down: a tenth of the way across and down.
        val dragged = OverlayRules.dragged(t, 108f, 192f, fw, fh)
        val moved = TextGeometry.placed(before, t, dragged)
        check(near(moved.xFraction, 0.6f) && near(moved.yFraction, 0.6f), "a drag did not move the line by the finger's distance")
        check(moved.sizeSp == 28, "a drag changed the size")
        // Pinched to twice the size: twice the letters, measured from the start.
        val pinched = OverlayRules.pinched(t, 2f, 0f)
        check(TextGeometry.placed(before, t, pinched).sizeSp == 56, "a pinch to 2x is not 56")
        check(TextGeometry.placed(before, t, OverlayRules.pinched(t, 1.5f, 0f)).sizeSp == 42, "a pinch to 1.5x is not 42")
        // In steps: the placement halfway through, then the rest, ends where one pinch does.
        val half = TextGeometry.placed(before, t, OverlayRules.pinched(t, 1.4f, 0f))
        check(half.sizeSp == 39 && TextGeometry.placed(before, t, OverlayRules.pinched(t, 2f, 0f)).sizeSp == 56, "a pinch in steps drifts")
        // Kept in range: the size floor and ceiling, wherever the fingers go.
        val huge = TextGeometry.limited(OverlayRules.pinched(t, 100f, 0f), t, 28)
        check(TextGeometry.placed(before, t, huge).sizeSp == TextStyleSpec.MAX_SIZE_SP, "the ceiling did not hold")
        val small = TextGeometry.limited(OverlayRules.pinched(t, 0.01f, 0f), t, 28)
        check(TextGeometry.placed(before, t, small).sizeSp == TextStyleSpec.MIN_SIZE_SP, "the floor did not hold")
        val far = TextGeometry.limited(OverlayRules.dragged(t, 5000f, 0f, fw, fh), t, 28)
        check(near(far.offsetXFraction, TextGeometry.OFFSET_MAX), "a line can be lost off the edge")
        // Turned: the angle comes back normalised, and the readout says the size and turn.
        val turned = OverlayRules.pinched(t, 1f, 190f)
        check(near(TextGeometry.placed(before, t, turned).rotationDegrees, -170f), "a turn past 180 is not brought round")
        check(TextGeometry.readout(t, t, 28, moving = false) == "Size 28 · 0°", "the readout: ${TextGeometry.readout(t, t, 28, false)}")
        check(TextGeometry.readout(pinched, t, 28, moving = false) == "Size 56 · 0°", "the readout after a pinch")
        check(TextGeometry.readout(dragged, t, 28, moving = true) == "Across 60% · Down 60%", "the readout while moving: ${TextGeometry.readout(dragged, t, 28, true)}")
        // A wide line in a wide frame and a tall one in a tall frame both come back exactly.
        for ((w, h, g) in listOf(Triple(1920f, 1080f, 800f to 90f), Triple(1080f, 1920f, 200f to 900f), Triple(720f, 720f, 700f to 50f))) {
            val tt = TextGeometry.transformOf(0.3f, 0.7f, 15f, g.first, g.second, w, h)
            val b = OverlayRules.box(tt, TextGeometry.aspect(g.first, g.second), w, h)
            check(near(b.halfW * 2, g.first, 0.1f) && near(b.halfH * 2, g.second, 0.1f), "the box is not the letters in a ${w}x$h frame")
            check(near(b.cx, 0.3f * w, 0.1f) && near(b.cy, 0.7f * h, 0.1f) && near(b.degrees, 15f), "the box is not where the line is in a ${w}x$h frame")
        }
    }

    // --- Where words begin: the dips between bursts of sound. ----------------------------
    run {
        val rate = 16_000
        val ms = { n: Int -> rate * n / 1000 }
        // Three words of 200 ms with 150 ms of near-silence between: 0-200, 350-550, 700-900.
        val samples = FloatArray(ms(900)) { i ->
            val t = i * 1000 / rate
            val loud = t < 200 || t in 350 until 550 || t >= 700
            val noise = ((i * 7919) % 200 - 100) / 100f
            if (loud) noise * 0.5f else noise * 0.002f
        }
        val pcm = MonoPcm(samples, rate)
        val segment = SpeechSegment(0, 900)
        val starts = SpeechSegmenter.wordStarts(pcm, segment, 3)
        check(starts.size == 3 && starts[0] == 0L, "three words did not get three starts from 0: $starts")
        check(starts[1] in 220L..380L, "the second word does not begin in the first gap: $starts")
        check(starts[2] in 570L..730L, "the third word does not begin in the second gap: $starts")
        check(starts == starts.sorted(), "the words are out of order: $starts")
        check(SpeechSegmenter.wordStarts(pcm, segment, 1) == listOf(0L), "one word has more than one start")
        // More words than dips: spaced evenly rather than invented.
        check(SpeechSegmenter.wordStarts(pcm, segment, 5) == SpeechSegmenter.evenWordStarts(5, 900), "five words over two dips were not spaced evenly")
        check(SpeechSegmenter.evenWordStarts(5, 900) == listOf(0L, 180L, 360L, 540L, 720L), "even spacing")
        // The same words later in the file come back relative to the segment.
        val later = MonoPcm(FloatArray(ms(500)) { ((it * 7919) % 200 - 100) / 100f * 0.002f } + samples, rate)
        val shifted = SpeechSegmenter.wordStarts(later, SpeechSegment(500, 1400), 3)
        check(shifted == starts, "a segment later in the file gives different starts: $shifted vs $starts")
        // Too short to tell: even.
        val brief = MonoPcm(FloatArray(ms(100)) { 0.3f }, rate)
        check(SpeechSegmenter.wordStarts(brief, SpeechSegment(0, 100), 3) == SpeechSegmenter.evenWordStarts(3, 100), "a brief segment guessed")
    }

    println("text: looks, style names, arrivals and leavings, reveals, the loop, the box round the letters, word starts, words through a trim and a cut")
    if (problems.isEmpty()) println("PASS - a line looks, moves and is grabbed as B10 decided")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
