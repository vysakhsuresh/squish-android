package com.squish.app.editor

import com.squish.app.timeline.Clip
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The effects in the library, each a stretch of the video treated a particular
 * way. [parameter] names the one knob each has besides Strength - how fast a
 * shake shakes, how often a punch lands - or null for the effects that are one
 * number all the way down.
 */
enum class EffectKind(val label: String, val parameter: String? = null) {
    Shake("Shake", "Speed"),
    Punch("Zoom punch", "Beats per second"),
    ZoomIn("Slow zoom", "Reach"),
    Glitch("Glitch", "Bursts"),
    Flash("Flash", "Fade"),
    Vhs("VHS", "Lines"),
    Mono("B&W"),
    Invert("Invert"),
    Blur("Blur", "Radius"),
    Rainbow("Rainbow", "Speed"),
    // Recipes over the same shader controls as the ten above: nothing new on
    // the GPU, so the preview and the file agree by construction.
    RgbSplit("RGB split", "Width"),
    Strobe("Strobe", "Speed"),
    Earthquake("Earthquake", "Speed"),
    Heartbeat("Heartbeat", "Beats per second"),
    Static("TV static", "Grain"),
    OldFilm("Old film", "Grain"),
    Dream("Dream", "Glow"),
    NegativePulse("Negative pulse", "Speed"),
    Trippy("Trippy", "Speed"),
    Sway("Sway", "Speed"),
    ZoomOut("Slow zoom out", "Reach")
}

/**
 * One effect over one stretch of the timeline, at a strength from 0 to 1.
 * Stored in timeline time, like captions. [amount] is the effect's own knob
 * ([EffectKind.parameter]), 0 to 1, half way being how it always behaved.
 */
data class TimedEffect(
    val id: String,
    val kind: EffectKind,
    val startMs: Long,
    val endMs: Long,
    val intensity: Float = 0.7f,
    val amount: Float = DEFAULT_AMOUNT
) {
    companion object {
        const val DEFAULT_AMOUNT = 0.5f
    }
    /**
     * The same effect in a clip's own source clock, for the preview player that
     * plays that clip.
     *
     * Through the clip's speed curve, not by a constant offset: on a clip at
     * double speed an effect over two seconds of timeline covers four seconds
     * of the file, and shifting its ends by the trim alone showed it for one.
     */
    fun shiftedInto(clip: Clip): TimedEffect =
        copy(startMs = clip.sourceAtExtended(startMs), endMs = clip.sourceAtExtended(endMs))
}

/**
 * The source moment on screen at [timelineMs], extended past the clip's ends at
 * normal speed.
 *
 * [Clip.sourceAt] pins anything outside the clip to its first or last frame,
 * which is right for choosing a frame and wrong for an interval: a caption that
 * starts before the clip and ends after it would be squeezed onto the clip's
 * edges, and its end would land on the last frame exactly, where "until the end"
 * means "gone one frame early". Outside the clip the file simply carries on.
 */
fun Clip.sourceAtExtended(timelineMs: Long): Long = when {
    timelineMs < timelineStartMs -> sourceInMs - (timelineStartMs - timelineMs)
    timelineMs > timelineEndMs -> sourceOutMs + (timelineMs - timelineEndMs)
    else -> sourceAt(timelineMs)
}

/**
 * The effects cut back to a picture [endMs] long.
 *
 * An effect over time the video no longer has does nothing - there are no
 * frames there to treat - but it still drew a bar running off the end of the
 * strip and could not be dragged back in. So one that overhangs the end stops
 * at it, and one that starts past the end is dropped. Undo brings either back
 * along with the length that was cut.
 *
 * An empty picture ([endMs] of zero or less) changes nothing: that is a timeline
 * part-way through being rebuilt, not one that is short.
 */
/**
 * How far an effect may be placed, moved or stretched: the picture's end, the
 * same end every edit fits the effects to ([fittedTo]). Placed by the edit's
 * whole length instead, an effect over a song's tail past the last shot was
 * allowed, then dropped by the next unrelated edit inside that edit's undo
 * step. With no picture at all nothing is fitted, and the edit's length is
 * the room.
 */
fun effectRoomMs(pictureEndMs: Long, editEndMs: Long): Long =
    if (pictureEndMs > 0L) pictureEndMs else editEndMs

fun List<TimedEffect>.fittedTo(endMs: Long): List<TimedEffect> {
    if (endMs <= 0L || none { it.endMs > endMs }) return this
    return mapNotNull { e ->
        when {
            e.startMs >= endMs -> null
            e.endMs > endMs -> e.copy(endMs = endMs)
            else -> e
        }
    }
}

/**
 * Everything the effects shader needs for one frame, worked out on the CPU from
 * whichever effects cover that moment. Effects that overlap add together.
 */
data class FxParams(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoom: Float = 1f,
    val split: Float = 0f,
    val glitch: Float = 0f,
    val flash: Float = 0f,
    val mono: Float = 0f,
    val invert: Float = 0f,
    val scan: Float = 0f,
    val noise: Float = 0f,
    val blur: Float = 0f,
    val hue: Float = 0f,
    val timeSec: Float = 0f
) {
    val isIdentity: Boolean
        get() = offsetX == 0f && offsetY == 0f && zoom == 1f && split == 0f && glitch == 0f &&
            flash == 0f && mono == 0f && invert == 0f && scan == 0f && noise == 0f &&
            blur == 0f && hue == 0f

    companion object {
        fun at(effects: List<TimedEffect>, timeMs: Long): FxParams {
            var p = FxParams(timeSec = (timeMs % 100_000L) / 1000f)
            for (e in effects) {
                if (timeMs < e.startMs || timeMs >= e.endMs) continue
                val a = e.intensity.coerceIn(0f, 1f)
                val t = (timeMs - e.startMs) / 1000f
                val span = ((e.endMs - e.startMs) / 1000f).coerceAtLeast(0.001f)
                // Every effect eases in and out over a tenth of a second, so none
                // of them switches on and off with a hard cut.
                val edge = minOf(t, span - t).coerceAtLeast(0f)
                val ramp = (edge / 0.1f).coerceIn(0f, 1f)
                val k = a * ramp
                // The effect's own knob, as a factor of how it always behaved:
                // half way is one, so a draft from before it had the knob plays
                // exactly as it did.
                val amount = e.amount.coerceIn(0f, 1f)
                // A quarter at the left end, one in the middle, one and three quarters at
                // the right - never nothing. It was amount * 2, so Slow zoom and Blur with
                // the knob at the left did nothing at all and Strength did nothing either.
                val twice = 0.25f + 1.5f * amount
                p = when (e.kind) {
                    EffectKind.Shake -> p.copy(
                        offsetX = p.offsetX + wobble(t, 17.3f * (0.5f + amount)) * 0.028f * k,
                        offsetY = p.offsetY + wobble(t, (23.9f + 1.7f) * (0.5f + amount)) * 0.028f * k,
                        zoom = p.zoom * (1f + 0.06f * k)
                    )
                    EffectKind.Punch -> {
                        // Hits a second, each a sharp push in that settles.
                        val perSecond = 0.5f + 3f * amount
                        val phase = (t * perSecond) % 1f
                        p.copy(zoom = p.zoom * (1f + 0.22f * k * exp(-phase * 7f)))
                    }
                    EffectKind.ZoomIn -> p.copy(zoom = p.zoom * (1f + 0.3f * twice * a * (t / span).coerceIn(0f, 1f)))
                    EffectKind.Glitch -> {
                        // Bursts rather than a steady wobble: glitches read as glitches
                        // because they come and go. The knob is how often they come.
                        val burst = if (sin(t * 11.0 + sin(t * 3.7) * 4.0) > 0.6 - 0.8 * amount) 1f else 0.25f
                        p.copy(glitch = p.glitch + k * burst, split = p.split + 0.012f * k * burst)
                    }
                    // Full white at the start of its stretch, fading out - a camera flash.
                    EffectKind.Flash -> p.copy(flash = maxOf(p.flash, a * exp(-t * (1f + 6f * (1f - amount)))))
                    EffectKind.Vhs -> p.copy(scan = p.scan + k * twice, noise = p.noise + 0.12f * k, split = p.split + 0.004f * k)
                    EffectKind.Mono -> p.copy(mono = maxOf(p.mono, k))
                    EffectKind.Invert -> p.copy(invert = maxOf(p.invert, k))
                    EffectKind.Blur -> p.copy(blur = p.blur + 0.012f * k * twice)
                    // The knob is how fast the hue turns: a quarter of the usual
                    // rate at the left end, four times at the right, and one in
                    // the middle. It used to be the rate itself, so the left end
                    // was not "slow" but off, with Strength then doing nothing.
                    EffectKind.Rainbow -> {
                        val rate = 2.0.pow(((amount - 0.5f) * 4f).toDouble()).toFloat()
                        p.copy(hue = p.hue + (t * rate % 1f) * 2f * PI.toFloat() * k)
                    }
                    EffectKind.RgbSplit -> p.copy(split = p.split + 0.014f * k * twice)
                    EffectKind.Strobe -> {
                        val rate = 3f + 12f * amount
                        p.copy(flash = maxOf(p.flash, if ((t * rate) % 1f < 0.5f) 0.85f * k else 0f))
                    }
                    EffectKind.Earthquake -> p.copy(
                        offsetX = p.offsetX + wobble(t, 31f * (0.5f + amount)) * 0.06f * k,
                        offsetY = p.offsetY + wobble(t, 43f * (0.5f + amount)) * 0.05f * k,
                        zoom = p.zoom * (1f + 0.14f * k)
                    )
                    EffectKind.Heartbeat -> {
                        val perSecond = 0.5f + 2f * amount
                        val phase = (t * perSecond) % 1f
                        val beat = exp(-((phase - 0.08f) * (phase - 0.08f)) / 0.0025f) +
                            0.7f * exp(-((phase - 0.3f) * (phase - 0.3f)) / 0.0025f)
                        p.copy(zoom = p.zoom * (1f + 0.12f * k * beat))
                    }
                    EffectKind.Static -> p.copy(
                        noise = p.noise + 0.35f * k * twice,
                        scan = p.scan + 0.6f * k,
                        mono = maxOf(p.mono, 0.5f * k)
                    )
                    EffectKind.OldFilm -> {
                        // Grain, a little flicker and the odd jump of the frame in the gate.
                        val flicker = if (sin(t * 23.0 + sin(t * 5.1) * 3.0) > 0.75) 1f else 0f
                        val jump = if (sin(t * 2.3 + sin(t * 0.7) * 5.0) > 0.92) 0.01f else 0f
                        p.copy(
                            mono = maxOf(p.mono, 0.85f * k),
                            noise = p.noise + 0.09f * k * twice,
                            flash = maxOf(p.flash, 0.07f * k * flicker),
                            offsetY = p.offsetY + jump * k,
                            zoom = p.zoom * (1f + 0.03f * k)
                        )
                    }
                    EffectKind.Dream -> p.copy(
                        blur = p.blur + 0.004f * k * twice,
                        flash = maxOf(p.flash, 0.14f * k * twice.coerceAtMost(1.5f)),
                        hue = p.hue + 0.25f * k * sin(t * 0.8).toFloat()
                    )
                    EffectKind.NegativePulse -> {
                        val perSecond = 0.5f + 3f * amount
                        val phase = (t * perSecond) % 1f
                        p.copy(invert = maxOf(p.invert, k * exp(-phase * 6f)))
                    }
                    EffectKind.Trippy -> {
                        val rate = 2.0.pow(((amount - 0.5f) * 4f).toDouble()).toFloat()
                        p.copy(
                            hue = p.hue + (t * rate * 1.5f % 1f) * 2f * PI.toFloat() * k,
                            split = p.split + 0.01f * k * (0.5f + 0.5f * sin(t * rate * 6.0).toFloat()),
                            zoom = p.zoom * (1f + 0.05f * k * (0.5f + 0.5f * sin(t * rate * 3.0).toFloat()))
                        )
                    }
                    EffectKind.Sway -> p.copy(
                        offsetX = p.offsetX + sin(t * (0.6f + 2.4f * amount) * 2.0 * PI).toFloat() * 0.03f * k,
                        zoom = p.zoom * (1f + 0.07f * k)
                    )
                    EffectKind.ZoomOut -> p.copy(zoom = p.zoom * (1f + 0.3f * twice * a * (1f - (t / span).coerceIn(0f, 1f))))
                }
            }
            return p
        }

        /** A smooth, non-repeating shake from two sines at unrelated rates. */
        private fun wobble(t: Float, rate: Float): Float =
            (sin(t * rate) * 0.6 + sin(t * rate * 2.31 + 1.3) * 0.4).toFloat()
    }
}
