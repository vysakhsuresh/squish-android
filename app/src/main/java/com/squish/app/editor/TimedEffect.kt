package com.squish.app.editor

import com.squish.app.timeline.Clip
import kotlin.math.PI
import kotlin.math.exp
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
    Rainbow("Rainbow", "Speed")
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
                val twice = amount * 2f
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
                    EffectKind.Rainbow -> p.copy(hue = p.hue + (t * amount % 1f) * 2f * PI.toFloat() * k)
                }
            }
            return p
        }

        /** A smooth, non-repeating shake from two sines at unrelated rates. */
        private fun wobble(t: Float, rate: Float): Float =
            (sin(t * rate) * 0.6 + sin(t * rate * 2.31 + 1.3) * 0.4).toFloat()
    }
}
