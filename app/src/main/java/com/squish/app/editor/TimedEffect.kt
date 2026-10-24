package com.squish.app.editor

import com.squish.app.timeline.Clip
import com.squish.app.timeline.KEY_TOLERANCE_MS
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.easingAt
import com.squish.app.timeline.shiftedBy
import com.squish.app.timeline.upserted
import com.squish.app.timeline.valueAt
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Punch's hits a second at knob [a]. The slider's readout and [FxParams] share it. */
fun punchPerSecond(a: Float): Float = 0.5f + 3f * a.coerceIn(0f, 1f)

/** Heartbeat's beats a second at knob [a]. Shared the same way. */
fun heartbeatPerSecond(a: Float): Float = 0.5f + 2f * a.coerceIn(0f, 1f)

private val perSecond: (Float) -> String = { v -> PolishRules.number(v, 1) + "/s" }

/**
 * The effects in the library, each a stretch of the video treated a particular
 * way. [parameter] names the one knob each has besides Strength - how fast a
 * shake shakes, how often a punch lands - or null for the effects that are one
 * number all the way down.
 *
 * [parameterReadout] is how that knob reads over the slider, for the knobs that
 * name a unit. The knob is a 0..1 fraction underneath, so the two named "Beats
 * per second" read "50%" without it - which is not a number of beats and not a
 * number of seconds. The vaguer knobs (Speed, Reach, Grain) are a fraction of
 * the usual and a percent is what they mean.
 */
enum class EffectKind(
    val label: String,
    val parameter: String? = null,
    val parameterReadout: ((Float) -> String)? = null
) {
    Shake("Shake", "Speed"),
    Punch("Zoom punch", "Beats per second", { a -> perSecond(punchPerSecond(a)) }),
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
    Heartbeat("Heartbeat", "Beats per second", { a -> perSecond(heartbeatPerSecond(a)) }),
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
    val amount: Float = DEFAULT_AMOUNT,
    /**
     * Strength keyed over the effect (EDGE_PLAN D3), on its own clock: [ValueKey.atMs]
     * from [startMs], so the keys travel with the effect when it is carried. Empty
     * is the one [intensity] throughout.
     */
    val intensityKeys: List<ValueKey> = emptyList(),
    /** The knob keyed the same way; empty is the one [amount]. */
    val amountKeys: List<ValueKey> = emptyList()
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
    fun shiftedInto(clip: Clip): TimedEffect {
        val start = clip.sourceAtExtended(startMs)
        // The keys too, through the same curve: on a 2x clip a key two seconds
        // into the effect is four seconds into the file.
        fun into(keys: List<ValueKey>) = keys.map { it.copy(atMs = clip.sourceAtExtended(startMs + it.atMs) - start) }
        return copy(startMs = start, endMs = clip.sourceAtExtended(endMs), intensityKeys = into(intensityKeys), amountKeys = into(amountKeys))
    }
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
                // Keyed or not (EDGE_PLAN D3): the one number, or the keys at this moment.
                val a = e.knobAt(EffectKnob.Strength, timeMs).coerceIn(0f, 1f)
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
                val amount = e.knobAt(EffectKnob.Amount, timeMs).coerceIn(0f, 1f)
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
                        val phase = (t * punchPerSecond(amount)) % 1f
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
                        val phase = (t * heartbeatPerSecond(amount)) % 1f
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
                    // k, not a: from a, it opened on a jump straight to its widest.
                    EffectKind.ZoomOut -> p.copy(zoom = p.zoom * (1f + 0.3f * twice * k * (1f - (t / span).coerceIn(0f, 1f))))
                }
            }
            return p
        }

        /** A smooth, non-repeating shake from two sines at unrelated rates. */
        private fun wobble(t: Float, rate: Float): Float =
            (sin(t * rate) * 0.6 + sin(t * rate * 2.31 + 1.3) * 0.4).toFloat()
    }
}

/** The two numbers a placed effect has: Strength, and its own knob (EffectKind.parameter). */
enum class EffectKnob { Strength, Amount }

private fun TimedEffect.keysOf(knob: EffectKnob): List<ValueKey> = if (knob == EffectKnob.Strength) intensityKeys else amountKeys
private fun TimedEffect.staticOf(knob: EffectKnob): Float = if (knob == EffectKnob.Strength) intensity else amount
private fun TimedEffect.withKeys(knob: EffectKnob, keys: List<ValueKey>): TimedEffect =
    if (knob == EffectKnob.Strength) copy(intensityKeys = keys) else copy(amountKeys = keys)
private fun TimedEffect.withStatic(knob: EffectKnob, value: Float): TimedEffect =
    if (knob == EffectKnob.Strength) copy(intensity = value) else copy(amount = value)

/** Where on its own clock a moment of the timeline falls, held to its ends. */
private fun TimedEffect.local(timelineMs: Long): Long = (timelineMs - startMs).coerceIn(0L, (endMs - startMs).coerceAtLeast(0L))

/** [knob] at a moment of the timeline (or of the file's clock, for an effect [TimedEffect.shiftedInto] one). */
fun TimedEffect.knobAt(knob: EffectKnob, timelineMs: Long): Float = keysOf(knob).valueAt(local(timelineMs), staticOf(knob))

/**
 * [knob] set to [value] at [playheadMs], as a clip's keyed numbers are set
 * (Clip.withValueAt): the one number with no keys, a key at the playhead
 * inside the effect, or every key moved by the change when the playhead is
 * outside it.
 */
fun TimedEffect.withKnobAt(knob: EffectKnob, playheadMs: Long, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f): TimedEffect {
    val wanted = value.coerceIn(range.start, range.endInclusive)
    val keys = keysOf(knob)
    if (keys.isEmpty()) return withStatic(knob, wanted)
    val now = knobAt(knob, playheadMs)
    if (wanted == now) return this
    val at = playheadMs - startMs
    return if (at in 0L..(endMs - startMs)) {
        withKeys(knob, keys.upserted(ValueKey(at, wanted, keys.easingAt(at)), KEY_TOLERANCE_MS))
    } else {
        val delta = wanted - now
        withKeys(knob, keys.map { it.copy(value = (it.value + delta).coerceIn(range.start, range.endInclusive)) })
    }
}

/** A key at the playhead holding what [knob] is there: the keyframe button. */
fun TimedEffect.withKnobKeyAdded(knob: EffectKnob, playheadMs: Long): TimedEffect {
    val at = local(playheadMs)
    val keys = keysOf(knob)
    return withKeys(knob, keys.upserted(ValueKey(at, knobAt(knob, playheadMs), keys.easingAt(at)), KEY_TOLERANCE_MS))
}

/** The key under the playhead off [knob]; the last one off leaves the value it had there. */
fun TimedEffect.withKnobKeyRemoved(knob: EffectKnob, playheadMs: Long): TimedEffect {
    val at = playheadMs - startMs
    val keys = keysOf(knob)
    if (keys.none { kotlin.math.abs(it.atMs - at) <= KEY_TOLERANCE_MS }) return this
    val kept = keys.filterNot { kotlin.math.abs(it.atMs - at) <= KEY_TOLERANCE_MS }
    val settled = knobAt(knob, playheadMs)
    return if (kept.isEmpty()) withKeys(knob, kept).withStatic(knob, settled) else withKeys(knob, kept)
}

/** Whether a key of [knob] is under the playhead: what lights the keyframe button. */
fun TimedEffect.hasKnobKeyAt(knob: EffectKnob, playheadMs: Long): Boolean =
    keysOf(knob).any { kotlin.math.abs(it.atMs - (playheadMs - startMs)) <= KEY_TOLERANCE_MS }

/** The key of [knob] under the playhead, or null: its curve is set from the card. */
fun TimedEffect.knobKeyAt(knob: EffectKnob, playheadMs: Long): ValueKey? =
    keysOf(knob).filter { kotlin.math.abs(it.atMs - (playheadMs - startMs)) <= KEY_TOLERANCE_MS }.minByOrNull { kotlin.math.abs(it.atMs - (playheadMs - startMs)) }

/** The easing of [knob]'s key under the playhead set, a preset dropping any curve; unchanged with no key there. */
fun TimedEffect.withKnobKeyEasing(knob: EffectKnob, playheadMs: Long, easing: com.squish.app.timeline.KeyframeEasing, curve: com.squish.app.timeline.CubicBezier? = null): TimedEffect {
    val key = knobKeyAt(knob, playheadMs) ?: return this
    return withKeys(knob, keysOf(knob).map { if (it === key) it.copy(easing = easing, curve = if (easing == com.squish.app.timeline.KeyframeEasing.Custom) (curve ?: it.curve ?: com.squish.app.timeline.CubicBezier.DEFAULT_CUSTOM) else null) else it })
}

/** Carried along the timeline: its keys are on its own clock and go with it. */
fun TimedEffect.movedBy(deltaMs: Long): TimedEffect = copy(startMs = startMs + deltaMs, endMs = endMs + deltaMs)

/**
 * Its ends moved to [start] and [end]. A head trim moves its clock, so the
 * keys are moved back by as much and stay on the moments they were set at;
 * none is dropped, as a clip's are not (shiftedBy).
 */
fun TimedEffect.trimmedTo(start: Long, end: Long): TimedEffect {
    val shift = startMs - start
    return copy(
        startMs = start, endMs = end,
        intensityKeys = intensityKeys.shiftedBy(shift),
        amountKeys = amountKeys.shiftedBy(shift)
    )
}
