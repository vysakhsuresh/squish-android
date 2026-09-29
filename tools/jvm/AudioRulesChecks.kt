import com.squish.app.editor.AudioRules
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.VoiceEffect
import kotlin.math.abs
import kotlin.system.exitProcess

// The sound tools' arithmetic, executed: fades, the gain split, beats on the
// clip, looping a song to the end, and extracting a shot's sound. Each block is
// a claim from docs/ROADMAP.md batch B9.

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float) = abs(a - b) < 1e-4f

fun sound(
    srcIn: Long = 0, srcOut: Long = 10_000, start: Long = 0, ramp: SpeedRamp = SpeedRamp(),
    beats: List<Long> = emptyList(), fadeIn: Long = 0, fadeOut: Long = 0, volume: Float = 1f
) = Clip(
    kind = ClipKind.Audio, label = "song", uri = null,
    sourceInMs = srcIn, sourceOutMs = srcOut, timelineStartMs = start, sourceDurationMs = 60_000,
    speedRamp = ramp, beats = beats, fadeInMs = fadeIn, fadeOutMs = fadeOut, volume = volume
)

fun main() {
    val minMs = 200L

    // --- A fade is a level at a moment. ---------------------------------------
    run {
        check(near(AudioRules.fadeGain(0, 10_000, 1_000, 2_000), 0f), "a fade in does not start silent")
        check(near(AudioRules.fadeGain(500, 10_000, 1_000, 2_000), 0.5f), "halfway through a fade in is not half")
        check(near(AudioRules.fadeGain(5_000, 10_000, 1_000, 2_000), 1f), "the middle of a faded clip is not full")
        check(near(AudioRules.fadeGain(9_000, 10_000, 1_000, 2_000), 0.5f), "halfway through a fade out is not half")
        check(near(AudioRules.fadeGain(10_000, 10_000, 1_000, 2_000), 0f), "a fade out does not end silent")
        check(near(AudioRules.fadeGain(3_000, 10_000, 0, 0), 1f), "no fades is not full level")
        check(near(AudioRules.fadeGain(0, 10_000, 0, 2_000), 1f), "a clip with only a fade out does not start full")
        check(near(AudioRules.fadeGain(-50, 10_000, 1_000, 0), 0f), "before the clip is not silent")
        check(near(AudioRules.fadeGain(12_000, 10_000, 0, 1_000), 0f), "after the clip is not silent")
        // Crossing fades on a short clip: the lower one wins, never louder than either.
        check(near(AudioRules.fadeGain(500, 1_000, 1_000, 1_000), 0.5f), "crossing fades peak above either")
        check(near(AudioRules.fadeGain(250, 1_000, 1_000, 1_000), 0.25f), "crossing fades: the fade in is not followed")
        // A clip of no length plays at full rather than dividing by nothing.
        check(near(AudioRules.fadeGain(0, 0, 500, 500), 1f), "a zero-length clip faded to nothing")
    }

    // --- The two fades share the clip; the one just set wins. -------------------
    run {
        check(AudioRules.fades(5_000, 3_000, 3_000, changedIn = true) == (3_000L to 2_000L), "setting the fade in did not push the fade out down")
        check(AudioRules.fades(5_000, 3_000, 4_000, changedIn = false) == (1_000L to 4_000L), "setting the fade out did not push the fade in down")
        check(AudioRules.fades(60_000, 15_000, 0, changedIn = true) == (10_000L to 0L), "a fade longer than ten seconds was allowed")
        check(AudioRules.fades(400, 300, 300, changedIn = true) == (300L to 100L), "fades on a short clip overlap")
        check(AudioRules.fades(400, 900, 0, changedIn = true) == (400L to 0L), "a fade longer than the clip was allowed")
        check(AudioRules.fades(5_000, -100, 0, changedIn = true) == (0L to 0L), "a negative fade was kept")
    }

    // --- A clip that got shorter keeps fades that fit it. ------------------------
    run {
        // Eight seconds each way on a thirty-second song, trimmed to a six-second sting.
        check(AudioRules.fittedFades(6_000, 8_000, 8_000) == (3_000L to 3_000L), "equal fades on a short clip did not share it")
        check(AudioRules.fittedFades(6_000, 1_000, 8_000) == (1_000L to 5_000L), "the shorter fade gave way instead of the longer")
        check(AudioRules.fittedFades(6_000, 8_000, 1_000) == (5_000L to 1_000L), "the shorter fade in gave way instead of the longer out")
        check(AudioRules.fittedFades(10_000, 1_000, 2_000) == (1_000L to 2_000L), "fades that fit were changed")
        check(AudioRules.fittedFades(0, 1_000, 2_000) == (0L to 0L), "a clip of no length kept fades")
        val sting = sound(srcIn = 0, srcOut = 6_000, fadeIn = 8_000, fadeOut = 8_000)
        val fitted = AudioRules.withFittedFades(sting)
        check(fitted.fadeInMs == 3_000L && fitted.fadeOutMs == 3_000L, "the clip's fades were not fitted")
        check(near(AudioRules.fadeGain(3_000, 6_000, fitted.fadeInMs, fitted.fadeOutMs), 1f), "a fitted sting never reaches full level")
        val fine = sound(fadeIn = 1_000, fadeOut = 1_000)
        check(AudioRules.withFittedFades(fine) === fine, "a clip whose fades fit was copied")
        // A speed-up halves the played length; the fades follow it.
        val fast = sound(srcIn = 0, srcOut = 10_000, fadeIn = 4_000, fadeOut = 4_000, ramp = SpeedRamp.flat(2f))
        check(AudioRules.withFittedFades(fast).let { it.fadeInMs + it.fadeOutMs <= it.durationMs }, "fades outlive a retimed clip")
    }

    // --- Gain to 400%: the player to its ceiling, the processor for the rest. ---
    run {
        check(AudioRules.gainSplit(0.4f) == (0.4f to 1f), "below full level the processor is not idle")
        check(AudioRules.gainSplit(1f) == (1f to 1f), "full level is not the player alone")
        check(AudioRules.gainSplit(2.5f) == (1f to 2.5f), "above full level the player is not at its ceiling")
        check(AudioRules.gainSplit(9f) == (1f to AudioRules.MAX_SOUND_GAIN), "the gain has no ceiling")
        check(AudioRules.gainSplit(-1f) == (0f to 1f), "a negative level was not silence")
    }

    // --- Beats travel with the clip and through its speed curve. ---------------
    run {
        val beats = listOf(0L, 500L, 1_000L, 1_500L, 2_000L)
        // Playing 500-1500 of the file from 2 s: only those beats, moved to where they are heard.
        val c = sound(srcIn = 500, srcOut = 1_500, start = 2_000, beats = beats)
        check(AudioRules.beatsOnTimeline(listOf(c), 1, 0, emptyList()) == listOf(2_000L, 2_500L, 3_000L),
            "beats did not follow the clip: ${AudioRules.beatsOnTimeline(listOf(c), 1, 0, emptyList())}")
        // Dragged two seconds on: the dots go with it, no analysis needed.
        val moved = c.copy(timelineStartMs = 4_000)
        check(AudioRules.beatsOnTimeline(listOf(moved), 1, 0, emptyList()) == listOf(4_000L, 4_500L, 5_000L), "beats stayed behind when the clip moved")
        // At double speed the beats come twice as fast.
        val fast = sound(srcIn = 0, srcOut = 2_000, start = 0, ramp = SpeedRamp.flat(2f), beats = beats)
        check(AudioRules.beatsOnTimeline(listOf(fast), 1, 0, emptyList()) == listOf(0L, 250L, 500L, 750L, 1_000L),
            "a retimed song's beats are not through its curve: ${AudioRules.beatsOnTimeline(listOf(fast), 1, 0, emptyList())}")
        // A song cut in two: the beat on the cut belongs to both halves and is listed once.
        val whole = sound(srcIn = 0, srcOut = 2_000, start = 1_000, beats = beats)
        val (head, tail) = whole.splitAt(2_000)!!
        val grid = AudioRules.beatsOnTimeline(listOf(head, tail), 1, 0, emptyList())
        check(grid == listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L), "a split song's grid is $grid")
        check(head.beats == beats && tail.beats == beats, "a split lost the beats")
        // Every bar, from the downbeat.
        check(AudioRules.chosenBeats(beats, 2, 1) == listOf(500L, 1_500L), "every 2 from beat 2 gave ${AudioRules.chosenBeats(beats, 2, 1)}")
        check(AudioRules.chosenBeats(beats, 4, 0) == listOf(0L, 2_000L), "every bar gave ${AudioRules.chosenBeats(beats, 4, 0)}")
        check(AudioRules.beatsOnTimeline(listOf(whole), 2, 0, emptyList()) == listOf(1_000L, 2_000L, 3_000L), "the density is not applied on the timeline")
        // The bar is counted over the file, not the window: a head trimmed by
        // one beat keeps the bar where the detector put it (downbeat = beat 2,
        // at 500 ms), and both halves of a cut song agree on it.
        val eight = (0L until 8).map { it * 500L }
        val trimmed = sound(srcIn = 500, srcOut = 4_000, start = 0, beats = eight)
        val bars = AudioRules.chosenInWindow(trimmed, 4, 1)
        check(bars == listOf(500L, 2_500L), "a trimmed head moved the bar: $bars")
        check(AudioRules.beatsOnTimeline(listOf(trimmed), 4, 1, emptyList()) == listOf(0L, 2_000L), "the timeline bars are not on the downbeat")
        val (h, t) = sound(srcIn = 0, srcOut = 4_000, start = 0, beats = eight).splitAt(1_800)!!
        check(AudioRules.beatsOnTimeline(listOf(h, t), 4, 1, emptyList()) == listOf(500L, 2_500L), "a cut song's halves disagree on the bar")
        // Nothing carrying beats: the camera-audio grid, at the same density.
        check(AudioRules.beatsOnTimeline(listOf(sound()), 2, 0, listOf(10L, 20L, 30L)) == listOf(10L, 30L), "the fallback grid is not used")
        // Tapping a beat in by ear: sorted in, and not doubled onto a found one.
        val tapped = AudioRules.withBeat(beats, 1_240L)
        check(tapped == listOf(0L, 500L, 1_000L, 1_240L, 1_500L, 2_000L), "a tapped beat was not filed in order: $tapped")
        check(AudioRules.withBeat(beats, 1_030L) == beats, "a tap on a found beat doubled it")
    }

    // --- Loop to fit: butted copies to the end, the last cut to it. -----------
    run {
        var n = 0
        val song = sound(srcIn = 0, srcOut = 10_000, start = 0, fadeIn = 1_000, fadeOut = 2_000)
        val loop = AudioRules.loopToFit(song, 35_000, minMs) { "copy${n++}" }
        check(loop.copies.map { it.timelineStartMs } == listOf(10_000L, 20_000L, 30_000L), "copies at ${loop.copies.map { it.timelineStartMs }}")
        check(loop.copies.last().timelineEndMs == 35_000L, "the last copy does not end with the picture: ${loop.copies.last().timelineEndMs}")
        check(loop.copies.last().sourceOutMs == 5_000L, "the last copy is not cut in the file: ${loop.copies.last().sourceOutMs}")
        check(loop.copies.dropLast(1).all { it.durationMs == 10_000L }, "a middle copy is not whole")
        check(loop.first.fadeOutMs == 0L && loop.copies.last().fadeOutMs == 2_000L, "the fade out did not move to the end of the run")
        check(loop.first.fadeInMs == 1_000L && loop.copies.all { it.fadeInMs == 0L }, "a copy fades in mid-run")
        check(loop.copies.map { it.id }.distinct().size == 3, "copies share an id")
        // No room: nothing added, the clip untouched.
        val none = AudioRules.loopToFit(song, 10_100, minMs) { "x" }
        check(none.copies.isEmpty() && none.first == song, "a sliver of room made a copy")
        // A retimed song: copies are its played length, the last cut where the played length reaches the end.
        val fast = sound(srcIn = 0, srcOut = 10_000, start = 0, ramp = SpeedRamp.flat(2f))
        val fl = AudioRules.loopToFit(fast, 12_000, minMs) { "f${n++}" }
        check(fl.copies.map { it.timelineStartMs } == listOf(5_000L, 10_000L), "retimed copies at ${fl.copies.map { it.timelineStartMs }}")
        check(fl.copies.last().durationMs == 2_000L, "the retimed last copy plays ${fl.copies.last().durationMs}")
        check(fl.copies.last().sourceOutMs == 4_000L, "the retimed last copy is cut at ${fl.copies.last().sourceOutMs} in the file")
    }

    // --- Extract audio: the same sound on its own row, the shot silenced. -------
    run {
        val shot = Clip(
            kind = ClipKind.Video, label = "A", uri = null, sourceInMs = 2_000, sourceOutMs = 8_000,
            timelineStartMs = 3_000, sourceDurationMs = 30_000, volume = 0.6f, voice = VoiceEffect.Robot,
            speedRamp = SpeedRamp.flat(0.5f), fadeOutMs = 500, layer = 0
        )
        val e = AudioRules.extracted(shot, "s1")
        check(e.sound.kind == ClipKind.Audio && e.sound.id == "s1", "the extraction is not a sound")
        check(e.sound.sourceInMs == 2_000L && e.sound.sourceOutMs == 8_000L && e.sound.timelineStartMs == 3_000L, "the sound is not where the shot is")
        check(e.sound.durationMs == shot.durationMs, "the sound does not play as long as the shot")
        check(e.sound.speedRamp == shot.speedRamp, "the sound lost the shot's speed")
        check(e.sound.voice == VoiceEffect.Robot && e.sound.fadeOutMs == 500L, "the sound lost the shot's voice or fade")
        check(near(e.sound.volume, 0.6f), "the sound is not at the shot's level")
        // By the switch, with its level and keys kept: a level of nothing on a
        // keyed shot was one key, and the shot played on.
        check(e.muted.muted && !e.muted.isHeard && e.muted.id == shot.id, "the shot was not silenced")
        check(e.muted.volume == shot.volume && e.muted.volumeKeys == shot.volumeKeys, "silencing the shot rewrote its level")
        check(e.sound.layer == 0 && e.sound.keyframes.isEmpty(), "the sound carried picture fields")
        // A muted shot's sound comes out audible.
        check(AudioRules.extracted(shot.copy(volume = 0f), "s2").sound.volume == 1f, "a muted shot gave a silent sound")
        // At the level it was heard: a shot at 60% under a camera level of 50%.
        check(near(AudioRules.extracted(shot, "s3", heardAt = 0.3f).sound.volume, 0.3f), "the sound is not at the level the shot was heard at")
        check(AudioRules.extracted(shot, "s4", heardAt = 0f).sound.volume == 1f, "a shot under a camera mute gave a silent sound")
    }

    // --- A split keeps each fade on its own end. -----------------------------
    run {
        val song = sound(srcIn = 0, srcOut = 10_000, start = 0, fadeIn = 1_000, fadeOut = 1_000)
        val (head, tail) = song.splitAt(4_000)!!
        check(head.fadeInMs == 1_000L && head.fadeOutMs == 0L, "the head kept a fade out at the cut")
        check(tail.fadeInMs == 0L && tail.fadeOutMs == 1_000L, "the tail lost its fade out or gained a fade in")
    }

    if (problems.isEmpty()) {
        println("PASS - fades, gain, beats on the clip, loop to fit and extract audio all hold")
    } else {
        println("FAIL (${problems.size})"); problems.forEach { println("  - $it") }; exitProcess(1)
    }
}
