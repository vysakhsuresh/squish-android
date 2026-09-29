package com.squish.app.editor

import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Transition
import kotlin.math.abs

/**
 * The arithmetic behind the sound tools, kept free of Android so it can be
 * compiled and executed on the JVM (tools/jvm/AudioRulesChecks.kt): what a fade
 * does to a level at a moment, how loud a sound may go and where the extra
 * comes from, where a clip's beats fall on the timeline, what looping a song to
 * the end of the picture makes, and what extracting a shot's sound detaches.
 *
 * The preview reads these per tick and the export per buffer, so one answer
 * here is what keeps the two the same.
 */
object AudioRules {

    /** The longest fade, either end: CapCut's ten seconds. */
    const val MAX_FADE_MS = 10_000L

    /**
     * How loud an added sound may be turned: four times its own level. The
     * players cannot turn a sound up past its own level, so the part above
     * that is done by a processor in front of them (see [gainSplit]).
     */
    const val MAX_SOUND_GAIN = 4f

    /** Two beats closer than this are one beat: a tap that lands on a found beat does not double it. */
    const val BEAT_TOLERANCE_MS = 60L

    /**
     * The level a sound plays at [playedMs] into a clip [lengthMs] long, given
     * its fades: 0 to 1. Linear, which is what the wedge on the strip draws;
     * where the two fades cross on a short clip the lower one wins, so the
     * sound is never louder than either fade alone allows.
     */
    fun fadeGain(playedMs: Long, lengthMs: Long, fadeInMs: Long, fadeOutMs: Long): Float {
        if (lengthMs <= 0L) return 1f
        val at = playedMs.coerceIn(0L, lengthMs)
        var gain = 1f
        if (fadeInMs > 0L && at < fadeInMs) gain = minOf(gain, at.toFloat() / fadeInMs)
        if (fadeOutMs > 0L) {
            val left = lengthMs - at
            if (left < fadeOutMs) gain = minOf(gain, left.toFloat() / fadeOutMs)
        }
        return gain.coerceIn(0f, 1f)
    }

    /**
     * Both fades as they may stand on a clip [lengthMs] long: each within
     * [MAX_FADE_MS], and together no longer than the clip - the one just set
     * ([changedIn] says which) keeps its value and the other gives way, so a
     * slider never fights the one beside it.
     */
    fun fades(lengthMs: Long, fadeInMs: Long, fadeOutMs: Long, changedIn: Boolean): Pair<Long, Long> {
        val cap = MAX_FADE_MS.coerceAtMost(lengthMs.coerceAtLeast(0L))
        var fadeIn = fadeInMs.coerceIn(0L, cap)
        var fadeOut = fadeOutMs.coerceIn(0L, cap)
        if (fadeIn + fadeOut > lengthMs) {
            if (changedIn) fadeOut = (lengthMs - fadeIn).coerceAtLeast(0L)
            else fadeIn = (lengthMs - fadeOut).coerceAtLeast(0L)
        }
        return fadeIn to fadeOut
    }

    /**
     * A sound's [volume] as the two numbers that make it: the player's own
     * level, which stops at 1, and the gain a processor in front of it applies
     * for the rest. Below full level the processor does nothing; above it the
     * player is at full and the processor carries the boost.
     */
    fun gainSplit(volume: Float): Pair<Float, Float> {
        val v = volume.coerceIn(0f, MAX_SOUND_GAIN)
        return v.coerceAtMost(1f) to v.coerceAtLeast(1f)
    }

    /**
     * Every [every]th of [beats], counted from [downbeatOffset]: the beats the
     * dots are drawn on and the cuts land on when the density is "every 2" or
     * "every bar".
     */
    fun chosenBeats(beats: List<Long>, every: Int, downbeatOffset: Int): List<Long> {
        if (every <= 1) return beats
        return beats.filterIndexed { i, _ -> (i - downbeatOffset).mod(every) == 0 }
    }

    /**
     * The beats of [clip] that fall inside the part of the file it plays, in
     * the file's time. Its window is closed at both ends, so a beat exactly on
     * a cut belongs to both halves of a split song - the cut was made on it.
     */
    fun beatsInWindow(clip: Clip): List<Long> =
        clip.beats.filter { it >= clip.sourceInMs && it <= clip.sourceOutMs }

    /**
     * The beat grid on the timeline: every sound's chosen beats, carried to
     * where they are heard through its position and its speed curve, as one
     * sorted list without doubles. With no sound carrying beats the grid is
     * [fallback] - a grid found on the camera's own audio, or one saved by a
     * draft from before beats lived on the clip.
     *
     * Mapped every time it is read rather than kept, so a song dragged two
     * seconds along takes its beats with it. That used to be a documented
     * trade against decoding the audio again on every drag; the beats are on
     * the clip now, and moving them is arithmetic.
     */
    fun beatsOnTimeline(clips: List<Clip>, every: Int, downbeatOffset: Int, fallback: List<Long>): List<Long> {
        val out = ArrayList<Long>()
        clips.forEach { clip ->
            if (clip.kind != ClipKind.Audio || clip.beats.isEmpty()) return@forEach
            chosenBeats(beatsInWindow(clip), every, downbeatOffset).forEach { at ->
                out.add(clip.timelineAtSource(at))
            }
        }
        if (out.isEmpty()) return chosenBeats(fallback, every, downbeatOffset)
        return out.distinct().sorted()
    }

    /**
     * [beats] with one more at [atMs], kept sorted; a tap within
     * [toleranceMs] of a beat already there changes nothing, so tapping along
     * to a found grid does not stack a second dot on every beat.
     */
    fun withBeat(beats: List<Long>, atMs: Long, toleranceMs: Long = BEAT_TOLERANCE_MS): List<Long> {
        if (beats.any { abs(it - atMs) <= toleranceMs }) return beats
        return (beats + atMs).sorted()
    }

    /** A song looped to the end: the clip as it stands afterwards, and the copies after it. */
    data class Loop(val first: Clip, val copies: List<Clip>)

    /**
     * A sound repeated, butted end to end, until the picture ends at [endMs] -
     * the last copy cut to end with it. Nothing is added when less than [minMs]
     * of room is left, or the clip plays nothing. Each copy is the same slice
     * of the file at the same speed and level; the fades stay on the ends of
     * the whole run - the fade out moves from the clip to the last copy - so
     * the loop plays as one long song rather than dipping at every join.
     */
    fun loopToFit(clip: Clip, endMs: Long, minMs: Long, newId: () -> String): Loop {
        val played = clip.durationMs
        if (played <= 0L) return Loop(clip, emptyList())
        val copies = ArrayList<Clip>()
        var cursor = clip.timelineEndMs
        while (endMs - cursor >= minMs) {
            val room = endMs - cursor
            val copy = clip.copy(
                id = newId(),
                timelineStartMs = cursor,
                transitionIn = Transition(),
                keyframes = emptyList(),
                fadeInMs = 0L,
                fadeOutMs = 0L
            )
            if (played <= room) {
                copies.add(copy)
                cursor += played
                continue
            }
            // The last one, cut in the file's own time so a retimed song is cut
            // where its played length reaches the end.
            val sourceOffset = clip.speedRamp.sourceOffsetAt(room, clip.sourceSpanMs)
            if (sourceOffset >= minMs) {
                copies.add(
                    copy.copy(
                        sourceOutMs = clip.sourceInMs + sourceOffset,
                        speedRamp = clip.speedRamp.sliced(0L, sourceOffset)
                    )
                )
            }
            break
        }
        if (copies.isEmpty()) return Loop(clip, emptyList())
        val last = copies.last()
        val (_, fadeOut) = fades(last.durationMs, 0L, clip.fadeOutMs, changedIn = false)
        copies[copies.lastIndex] = last.copy(fadeOutMs = fadeOut)
        return Loop(clip.copy(fadeOutMs = 0L), copies)
    }

    /** A shot's sound detached: the sound clip made, and the shot as it is left. */
    data class Extraction(val sound: Clip, val muted: Clip)

    /**
     * The sound of [clip] as a clip of its own on the sound rows, playing the
     * same part of the same file at the same time and speed, with the shot's
     * level, voice and fades - and the shot itself silenced, so nothing is
     * heard twice. What "Extract audio" on a shot or an overlay does; from
     * there the sound can be slid, trimmed or cut apart from the picture.
     */
    fun extracted(clip: Clip, id: String): Extraction {
        val sound = Clip(
            id = id,
            kind = ClipKind.Audio,
            uri = clip.uri,
            label = "${clip.label} sound",
            sourceInMs = clip.sourceInMs,
            sourceOutMs = clip.sourceOutMs,
            timelineStartMs = clip.timelineStartMs,
            sourceDurationMs = clip.sourceDurationMs,
            // A shot muted by its own slider gives a sound heard at full: the
            // point of extracting is to hear it on its own.
            volume = clip.volume.coerceIn(0f, 1f).takeIf { it > 0f } ?: 1f,
            fadeInMs = clip.fadeInMs,
            fadeOutMs = clip.fadeOutMs,
            voice = clip.voice,
            speedRamp = clip.speedRamp
        )
        return Extraction(sound, clip.copy(volume = 0f))
    }
}
