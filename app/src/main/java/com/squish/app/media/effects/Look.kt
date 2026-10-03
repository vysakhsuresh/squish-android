package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LookFamily(val label: String) {
    Essentials("Essentials"),
    Film("Film"),
    Mood("Mood"),
    Cinema("Cinema"),
    Retro("Retro"),
    Social("Social")
}

/**
 * A graded look, expressed as the three moves the GPU can already make: per-channel
 * gain, contrast, and saturation.
 *
 * Deliberately not a shader. Media3 gives these three as built-in, hardware-backed
 * effects, and between them they cover the grades people actually reach for - a
 * channel gain is a color cast, contrast plus saturation is the difference between
 * Vivid and Faded. Writing custom GLSL would buy film-grain and vignette and cost a
 * shader pipeline that cannot be verified without a device. Real 3D LUTs are the
 * right next step; this is the honest version of that idea which ships working.
 *
 * Every parameter is normalized to -1 through 1, or a multiplier around 1, so a look can be
 * dialled continuously between "off" and "full" ([atIntensity]).
 */
data class Look(
    val id: String,
    val label: String,
    val family: LookFamily,
    val redScale: Float = 1f,
    val greenScale: Float = 1f,
    val blueScale: Float = 1f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,

    // ---- The film moves --------------------------------------------------------
    // Everything above runs on Media3's built-in colour effects. Everything below
    // needs a shader, so a look that uses none of them stays on the cheaper path.

    /** Lifted blacks. The single move that most says film rather than phone. */
    val fade: Float = 0f,
    /** Shadows pulled toward this colour, highlights toward [highlightTint]. */
    val shadowTint: Int = NEUTRAL_TINT,
    val highlightTint: Int = NEUTRAL_TINT,
    val split: Float = 0f,
    /** Highlights that spill into what surrounds them, the way a bright lens does. */
    val bloom: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f
) {
    /** The look dialled back toward no-op. t=0 is the untouched picture, t=1 full. */
    fun atIntensity(t: Float): Look {
        val k = t.coerceIn(0f, 1f)
        return copy(
            redScale = 1f + (redScale - 1f) * k,
            greenScale = 1f + (greenScale - 1f) * k,
            blueScale = 1f + (blueScale - 1f) * k,
            contrast = contrast * k,
            saturation = saturation * k,
            fade = fade * k,
            // The tints themselves do not move; their strength does. Interpolating
            // a colour toward neutral and *also* fading its strength would take the
            // cast out twice as fast as the slider says.
            split = split * k,
            bloom = bloom * k,
            vignette = vignette * k,
            grain = grain * k
        )
    }

    val isIdentity: Boolean
        get() = abs(redScale - 1f) < 1e-4f && abs(greenScale - 1f) < 1e-4f &&
            abs(blueScale - 1f) < 1e-4f && abs(contrast) < 1e-4f && abs(saturation) < 1e-4f &&
            abs(fade) < 1e-4f && abs(split) < 1e-4f && abs(bloom) < 1e-4f &&
            abs(vignette) < 1e-4f && abs(grain) < 1e-4f

    /** True when this look needs more than the built-in colour effects can do. */
    val needsShader: Boolean
        get() = abs(fade) > 1e-4f || abs(split) > 1e-4f || abs(bloom) > 1e-4f ||
            abs(vignette) > 1e-4f || abs(grain) > 1e-4f
}

/** Mid-grey: a tint that changes nothing, whatever strength it is given. */
const val NEUTRAL_TINT: Int = 0xFF808080.toInt()

/**
 * The eight bands of the colour wheel the HSL sliders work on, each centred on
 * [degrees] of hue. A pixel belongs to the bands nearest its own hue, by how
 * near ([HslBand.weight]), so a change to Orange shades off into Red and Yellow
 * rather than stopping at a hard line through the skin tones.
 */
enum class HueBand(val label: String, val degrees: Float, val swatch: Int) {
    Red("Red", 0f, 0xFFE0453A.toInt()),
    Orange("Orange", 30f, 0xFFF0873A.toInt()),
    Yellow("Yellow", 60f, 0xFFF0D23A.toInt()),
    Green("Green", 120f, 0xFF4CC24C.toInt()),
    Cyan("Cyan", 180f, 0xFF3ACFD6.toInt()),
    Blue("Blue", 240f, 0xFF3A6BE0.toInt()),
    Purple("Purple", 270f, 0xFF8A4CE0.toInt()),
    Magenta("Magenta", 300f, 0xFFDA48C2.toInt())
}

/** One band's sliders: its hue nudged, its saturation and its brightness, each -1..1. */
data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    val isIdentity: Boolean get() = abs(hue) < EPS && abs(saturation) < EPS && abs(luminance) < EPS

    companion object {
        /** How far a band's hue slider turns the colour at full: a whole band either way. */
        const val HUE_SWING_DEGREES = 30f

        /**
         * How much of a pixel at [pixelDegrees] of hue a band centred on
         * [bandDegrees] owns: all of it on the band, nothing a band and a half
         * away, and a straight ramp between - the same ramp the shader uses.
         */
        fun weight(pixelDegrees: Float, bandDegrees: Float): Float {
            var d = abs(pixelDegrees - bandDegrees) % 360f
            if (d > 180f) d = 360f - d
            return (1f - d / REACH_DEGREES).coerceIn(0f, 1f)
        }

        private const val REACH_DEGREES = 45f
    }
}

private const val EPS = 1e-4f

/**
 * The manual sliders on one clip. Every value is 0 for "leave it alone" and runs
 * -1..1, or 0..1 where only one direction means anything (a negative grain is no
 * grain). A clip's look grades first and these refine on top (see [Looks.grade]).
 *
 * Brightness is an offset, not a gain: as a gain, -100% was black and +100% was
 * twice the picture, and neither is what the word means on any other slider.
 */
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    /** In stops, scaled: 1 is [EXPOSURE_STOPS] stops up. */
    val exposure: Float = 0f,
    /** Warm (positive) to cool. */
    val temperature: Float = 0f,
    /** Magenta (positive) to green. */
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
    /** A turn of the whole colour wheel, [HUE_TURN_DEGREES] at full either way. */
    val hue: Float = 0f,
    val fade: Float = 0f,
    val grain: Float = 0f,
    /** One entry per [HueBand], in its order. */
    val hsl: List<HslBand> = NO_HSL,
    /** The Curves tool: a master curve and one per channel. */
    val curve: ToneCurve = ToneCurve.NONE
) {
    val isIdentity: Boolean
        get() = abs(brightness) < EPS && abs(contrast) < EPS && abs(saturation) < EPS && abs(exposure) < EPS &&
            abs(temperature) < EPS && abs(tint) < EPS && abs(highlights) < EPS && abs(shadows) < EPS &&
            abs(sharpen) < EPS && abs(vignette) < EPS && abs(hue) < EPS && abs(fade) < EPS && abs(grain) < EPS &&
            hsl.all { it.isIdentity } && curve.isIdentity

    /** This with one band's sliders replaced. */
    fun withBand(band: HueBand, value: HslBand): Adjust {
        val bands = MutableList(HueBand.entries.size) { hsl.getOrElse(it) { HslBand() } }
        bands[band.ordinal] = value
        return copy(hsl = bands)
    }

    fun band(band: HueBand): HslBand = hsl.getOrElse(band.ordinal) { HslBand() }

    companion object {
        val NO_HSL: List<HslBand> = List(HueBand.entries.size) { HslBand() }
        val NONE = Adjust()

        /** Exposure at full: a stop and a half, which is as far as a slider on a phone is worth. */
        const val EXPOSURE_STOPS = 1.5f

        /** Hue at full: half the wheel, so the two ends of the slider meet. */
        const val HUE_TURN_DEGREES = 180f

        /**
         * The Brightness slider of a draft saved when it was a gain - every
         * channel times (1 + value) - as the offset it is now, chosen so a
         * mid-grey lands where it did: the picture's midtones, which are what
         * the slider was set by eye against, come back the same, and only
         * the far ends of the range drift a little. Without this a draft
         * opened after the change showed a different picture from the one
         * that had been exported, with nothing touched.
         */
        fun brightnessFromLegacyGain(gain: Float): Float =
            (gain * 0.5f / Looks.BRIGHTNESS_REACH).coerceIn(-1f, 1f)
    }
}

/**
 * The Adjust sheet's sliders, in the order CapCut lists them: the name, the
 * range, and how to read one and write one on an [Adjust]. Each has its own
 * reset - the slider back to nothing - which is why they are named here rather
 * than laid out by hand thirteen times.
 */
enum class AdjustField(val label: String, val min: Float, val max: Float) {
    Brightness("Brightness", -1f, 1f),
    Contrast("Contrast", -1f, 1f),
    Saturation("Saturation", -1f, 1f),
    Exposure("Exposure", -1f, 1f),
    Temperature("Temperature", -1f, 1f),
    Tint("Tint", -1f, 1f),
    Highlights("Highlights", -1f, 1f),
    Shadows("Shadows", -1f, 1f),
    Sharpen("Sharpen", 0f, 1f),
    Vignette("Vignette", 0f, 1f),
    Hue("Hue", -1f, 1f),
    Fade("Fade", 0f, 1f),
    Grain("Grain", 0f, 1f);

    fun of(adjust: Adjust): Float = when (this) {
        Brightness -> adjust.brightness
        Contrast -> adjust.contrast
        Saturation -> adjust.saturation
        Exposure -> adjust.exposure
        Temperature -> adjust.temperature
        Tint -> adjust.tint
        Highlights -> adjust.highlights
        Shadows -> adjust.shadows
        Sharpen -> adjust.sharpen
        Vignette -> adjust.vignette
        Hue -> adjust.hue
        Fade -> adjust.fade
        Grain -> adjust.grain
    }

    fun set(adjust: Adjust, value: Float): Adjust {
        val v = value.coerceIn(min, max)
        return when (this) {
            Brightness -> adjust.copy(brightness = v)
            Contrast -> adjust.copy(contrast = v)
            Saturation -> adjust.copy(saturation = v)
            Exposure -> adjust.copy(exposure = v)
            Temperature -> adjust.copy(temperature = v)
            Tint -> adjust.copy(tint = v)
            Highlights -> adjust.copy(highlights = v)
            Shadows -> adjust.copy(shadows = v)
            Sharpen -> adjust.copy(sharpen = v)
            Vignette -> adjust.copy(vignette = v)
            Hue -> adjust.copy(hue = v)
            Fade -> adjust.copy(fade = v)
            Grain -> adjust.copy(grain = v)
        }
    }
}

/**
 * What actually reaches the GPU: one look and the manual sliders folded into
 * a single set of moves.
 *
 * Folding matters. Applying a look and then three more adjustments would stack six
 * shader passes on every frame; combined, it is one pass no matter how much
 * grading is going on. The look's channel gains, the exposure, the temperature
 * and the tint all end in the same three numbers; the look's contrast and the
 * slider's are one contrast; and so on down the list.
 */
data class Grade(
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val contrast: Float,
    val saturation: Float,
    val fade: Float = 0f,
    val shadowTint: Int = NEUTRAL_TINT,
    val highlightTint: Int = NEUTRAL_TINT,
    val split: Float = 0f,
    val bloom: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    /** Added to every channel after the gain: the Brightness slider. */
    val brightness: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpen: Float = 0f,
    /** Degrees round the wheel. */
    val hueDegrees: Float = 0f,
    val hsl: List<HslBand> = Adjust.NO_HSL,
    /** The Curves tool. Folded into one table per channel; see [curveLut]. */
    val curve: ToneCurve = ToneCurve.NONE
) {
    val hasCurve: Boolean get() = !curve.isIdentity

    /**
     * The curve's table, built once per grade rather than per pixel: [applyTo]
     * runs over every pixel of a photo overlay, and the shader uploads this as
     * its texture. Both read the same numbers, which is the whole point of
     * there being a table at all.
     */
    val curveLut: FloatArray by lazy(LazyThreadSafetyMode.NONE) { curve.lut() }

    val hasChannelGain: Boolean
        get() = abs(redScale - 1f) > 1e-4f || abs(greenScale - 1f) > 1e-4f || abs(blueScale - 1f) > 1e-4f
    val hasContrast: Boolean get() = abs(contrast) > 1e-4f
    val hasSaturation: Boolean get() = abs(saturation) > 1e-4f
    val hasHsl: Boolean get() = hsl.any { !it.isIdentity }
    val hasHue: Boolean get() = abs(hueDegrees) > 1e-3f
    val hasTone: Boolean get() = abs(highlights) > 1e-4f || abs(shadows) > 1e-4f
    val hasBrightness: Boolean get() = abs(brightness) > 1e-4f

    /**
     * Whether this grade needs more than a per-pixel colour transform: grain
     * and a vignette are spatial, and bloom and sharpening read neighbouring
     * pixels. Every grade goes through the one shader now (ColorGrade), so this
     * only says which moves a swatch cannot show.
     */
    val needsShader: Boolean
        get() = abs(fade) > 1e-4f || abs(split) > 1e-4f || abs(bloom) > 1e-4f ||
            abs(vignette) > 1e-4f || abs(grain) > 1e-4f || abs(sharpen) > 1e-4f

    val isIdentity: Boolean
        get() = !hasChannelGain && !hasContrast && !hasSaturation && !needsShader &&
            !hasBrightness && !hasTone && !hasHue && !hasHsl && !hasCurve

    /**
     * The same maths the shader does, on one color, in the same order.
     *
     * This exists so a filter chip can show what the look does to a real frame
     * without decoding one - and because both paths reading from a single
     * description is the only way the swatch can be trusted to match the export.
     */
    fun applyTo(argb: Int): Int {
        var r = ((argb shr 16) and 0xFF) / 255f
        var g = ((argb shr 8) and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f

        r *= redScale; g *= greenScale; b *= blueScale
        r += brightness; g += brightness; b += brightness

        if (hasContrast) {
            // Media3's Contrast: a factor either side of mid-gray, steepening as the
            // value approaches 1 and flattening to gray as it approaches -1.
            val f = (1f + contrast) / (1.0001f - contrast)
            r = f * (r - 0.5f) + 0.5f
            g = f * (g - 0.5f) + 0.5f
            b = f * (b - 0.5f) + 0.5f
        }

        if (hasTone) {
            // Lifted or crushed by how bright the pixel already is, so the two
            // sliders reach different parts of the picture rather than the whole
            // of it: the sky for one, the shadow under the chin for the other.
            val l = luma(r, g, b).coerceIn(0f, 1f)
            val lift = highlights * TONE_REACH * smoothstep(0.45f, 1f, l) +
                shadows * TONE_REACH * (1f - smoothstep(0f, 0.55f, l))
            r += lift; g += lift; b += lift
        }

        if (hasCurve) {
            // After the tonal sliders and before saturation, which is where a
            // curve sits in every tool that has one: it is a tonal move, and
            // putting it after saturation would undo the saturation's own lift.
            // Sampled from the table rather than evaluated, so this lands on
            // exactly what the shader's texture lookup lands on.
            r = ToneCurve.sample(curveLut, 0, r)
            g = ToneCurve.sample(curveLut, 1, g)
            b = ToneCurve.sample(curveLut, 2, b)
        }

        if (hasSaturation) {
            val lum = luma(r, g, b)
            val s = (1f + saturation).coerceAtLeast(0f)
            r = lum + (r - lum) * s
            g = lum + (g - lum) * s
            b = lum + (b - lum) * s
        }

        if (hasHue || hasHsl) {
            val hsv = rgbToHsv(r, g, b)
            var h = hsv[0]
            var s = hsv[1]
            var v = hsv[2]
            h = (h + hueDegrees) % 360f
            if (h < 0f) h += 360f
            if (hasHsl) {
                // Greys have no hue to speak of, so a band leaves them alone.
                val owned = smoothstep(0.05f, 0.3f, s)
                var turn = 0f
                var moreSat = 0f
                var brighter = 0f
                HueBand.entries.forEachIndexed { i, band ->
                    val w = HslBand.weight(h, band.degrees) * owned
                    if (w <= 0f) return@forEachIndexed
                    val e = hsl.getOrElse(i) { HslBand() }
                    turn += w * e.hue * HslBand.HUE_SWING_DEGREES
                    moreSat += w * e.saturation
                    brighter += w * e.luminance
                }
                h = (h + turn) % 360f
                if (h < 0f) h += 360f
                s = (s * (1f + moreSat)).coerceIn(0f, 1f)
                v = (v * (1f + brighter * HSL_LUMA_REACH)).coerceIn(0f, 1f)
            }
            val rgb = hsvToRgb(h, s, v)
            r = rgb[0]; g = rgb[1]; b = rgb[2]
        }

        // The same split-tone and fade the shader does, in the same order. Spatial
        // moves - vignette, grain, bloom, sharpening - have no meaning for one
        // colour and are simply absent here; a swatch shows the grade, not the texture.
        if (abs(split) > 1e-4f) {
            val l = luma(r, g, b).coerceIn(0f, 1f)
            r += (channel(shadowTint, 16, highlightTint, l) - 0.5f) * split * 0.55f
            g += (channel(shadowTint, 8, highlightTint, l) - 0.5f) * split * 0.55f
            b += (channel(shadowTint, 0, highlightTint, l) - 0.5f) * split * 0.55f
        }

        if (abs(fade) > 1e-4f) {
            r = r * (1f - fade * 0.55f) + fade * 0.16f
            g = g * (1f - fade * 0.55f) + fade * 0.16f
            b = b * (1f - fade * 0.55f) + fade * 0.16f
        }

        fun byte(v: Float) = (min(1f, max(0f, v)) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /**
     * A whole picture graded in place - [applyTo] on every pixel, and the
     * vignette, which needs a place in the frame: the shader's falloff from
     * the middle to the corners, in the same numbers. For a photo on an
     * overlay row, which the preview draws itself rather than through a
     * player's shader. Grain, bloom and sharpening are left out, as the look
     * chips leave them out (LookPreview): grain frozen on a still reads as
     * dirt, and the other two are a texture the file has and the preview
     * does without.
     */
    fun applyTo(pixels: IntArray, width: Int, height: Int, fromRow: Int = 0, toRow: Int = height) {
        if (width <= 0 || height <= 0 || isIdentity) return
        val aspect = width.toFloat() / height
        val halfDiagonal = kotlin.math.sqrt((aspect * 0.5f) * (aspect * 0.5f) + 0.25f)
        val vignetted = abs(vignette) > 1e-3f
        val first = fromRow.coerceIn(0, height)
        val last = toRow.coerceIn(first, height)
        var i = first * width
        for (y in first until last) {
            for (x in 0 until width) {
                val argb = pixels[i]
                var graded = applyTo(argb)
                if (vignetted) {
                    val px = ((x + 0.5f) / width - 0.5f) * aspect
                    val py = (y + 0.5f) / height - 0.5f
                    val d = kotlin.math.sqrt(px * px + py * py) / halfDiagonal
                    val falloff = 1f - vignette * smoothstep(0.42f, 1.06f, d)
                    val r = (((graded shr 16) and 0xFF) * falloff + 0.5f).toInt().coerceIn(0, 255)
                    val g = (((graded shr 8) and 0xFF) * falloff + 0.5f).toInt().coerceIn(0, 255)
                    val b = ((graded and 0xFF) * falloff + 0.5f).toInt().coerceIn(0, 255)
                    graded = (r shl 16) or (g shl 8) or b
                }
                // The picture's own alpha stays: a transparent logo is still transparent.
                pixels[i] = (argb and 0xFF000000.toInt()) or (graded and 0xFFFFFF)
                i++
            }
        }
    }

    /** One channel of the split-tone colour at luminance [l], as 0..1. */
    private fun channel(shadow: Int, shift: Int, highlight: Int, l: Float): Float {
        val lo = ((shadow shr shift) and 0xFF) / 255f
        val hi = ((highlight shr shift) and 0xFF) / 255f
        return lo + (hi - lo) * l
    }

    /** The three channels of a tint, as the shader wants them. */
    fun tintToFloats(argb: Int): FloatArray = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f
    )

    /** One band's three sliders as the shader wants them. */
    fun bandToFloats(index: Int): FloatArray {
        val band = hsl.getOrElse(index) { HslBand() }
        return floatArrayOf(band.hue, band.saturation, band.luminance)
    }

    companion object {
        /**
         * The weights a pixel's brightness is measured with - Rec. 709, the
         * shader's `LUMA`. Written out at four sites here before they had a
         * name, which is four places for them to drift from the shader's one;
         * GradeChecks now reads both and compares.
         */
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f

        /** A pixel's brightness, as both the shader and the CPU copy measure it. */
        fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

        /** How far Highlights or Shadows at full moves the pixels it reaches. */
        const val TONE_REACH = 0.3f

        /** How much a band's Luminance at full brightens or darkens its colours. */
        const val HSL_LUMA_REACH = 0.5f

        /** The shader's smoothstep, which is not Kotlin's and has no standard version. */
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x < edge0) 0f else 1f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Hue in degrees (0..360), saturation and value (0..1). The shader's rgb2hsv, in the same terms. */
        fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val v = max
            val s = if (max > 1e-6f) delta / max else 0f
            var h = when {
                delta < 1e-6f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            if (h < 0f) h += 360f
            return floatArrayOf(h, s, v)
        }

        fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
            val c = v * s
            val hh = ((h % 360f) + 360f) % 360f / 60f
            val x = c * (1f - abs(hh % 2f - 1f))
            val (r1, g1, b1) = when {
                hh < 1f -> Triple(c, x, 0f)
                hh < 2f -> Triple(x, c, 0f)
                hh < 3f -> Triple(0f, c, x)
                hh < 4f -> Triple(0f, x, c)
                hh < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = v - c
            return floatArrayOf(r1 + m, g1 + m, b1 + m)
        }
    }
}

object Looks {

    val None = Look("none", "Original", LookFamily.Essentials)

    // Tints for the split-tone looks, named rather than inlined so the same teal
    // is the same teal in three places.
    private const val TEAL = 0xFF4E7F86.toInt()
    private const val AMBER = 0xFFA8794E.toInt()
    private const val ROSE = 0xFFA36775.toInt()
    private const val MAGENTA = 0xFF9A5A93.toInt()
    private const val FOREST = 0xFF5E7A5A.toInt()
    private const val STEEL = 0xFF5F6E8C.toInt()
    private const val CREAM = 0xFF9C9079.toInt()
    private const val INK = 0xFF56607F.toInt()

    val catalog: List<Look> = listOf(
        None,

        // ---- Essentials: the everyday moves, all on the cheap built-in path -----
        Look("vivid", "Vivid", LookFamily.Essentials, contrast = 0.14f, saturation = 0.2f),
        Look("punch", "Punch", LookFamily.Essentials, contrast = 0.26f, saturation = 0.1f),
        Look("soft", "Soft", LookFamily.Essentials, contrast = -0.18f, saturation = -0.1f),
        Look("clean", "Clean", LookFamily.Essentials, redScale = 1.01f, blueScale = 1.03f, contrast = 0.08f),
        Look("warm", "Warm", LookFamily.Essentials, redScale = 1.07f, blueScale = 0.95f, saturation = 0.06f),
        Look("cool", "Cool", LookFamily.Essentials, redScale = 0.96f, blueScale = 1.08f, saturation = 0.04f),

        // ---- Film: stocks and darkroom habits ----------------------------------
        Look("noir", "Noir", LookFamily.Film, contrast = 0.22f, saturation = -1f),
        Look(
            "silver", "Silver", LookFamily.Film,
            redScale = 1.03f, blueScale = 1.05f, contrast = 0.06f, saturation = -1f,
            grain = 0.3f, vignette = 0.16f
        ),
        Look(
            "sepia", "Sepia", LookFamily.Film,
            redScale = 1.22f, greenScale = 1.04f, blueScale = 0.8f,
            contrast = 0.05f, saturation = -0.85f, fade = 0.2f, grain = 0.22f
        ),
        Look(
            "faded", "Faded", LookFamily.Film,
            redScale = 1.05f, blueScale = 1.04f, contrast = -0.22f, saturation = -0.25f,
            fade = 0.42f
        ),
        Look("kodak", "Kodak", LookFamily.Film, redScale = 1.08f, blueScale = 0.95f, contrast = 0.12f, saturation = 0.14f),
        Look("bleach", "Bleach", LookFamily.Film, contrast = 0.3f, saturation = -0.5f),
        Look(
            "portra", "Portra", LookFamily.Film,
            redScale = 1.05f, greenScale = 1.0f, blueScale = 0.97f, contrast = -0.06f, saturation = -0.04f,
            fade = 0.24f, shadowTint = CREAM, highlightTint = CREAM, split = 0.3f, grain = 0.18f
        ),
        Look(
            "super8", "Super 8", LookFamily.Film,
            redScale = 1.12f, greenScale = 1.0f, blueScale = 0.86f, contrast = 0.1f, saturation = -0.12f,
            fade = 0.3f, grain = 0.55f, vignette = 0.34f
        ),
        Look(
            "tungsten", "Tungsten", LookFamily.Film,
            redScale = 0.94f, blueScale = 1.14f, contrast = 0.08f,
            shadowTint = INK, highlightTint = AMBER, split = 0.28f, grain = 0.2f
        ),

        // ---- Mood: a room, a time of day ---------------------------------------
        Look("golden", "Golden", LookFamily.Mood, redScale = 1.14f, greenScale = 1.04f, blueScale = 0.88f, contrast = 0.06f, saturation = 0.08f),
        Look("arctic", "Arctic", LookFamily.Mood, redScale = 0.93f, blueScale = 1.12f, contrast = 0.1f, saturation = 0.06f),
        Look("moonlit", "Moonlit", LookFamily.Mood, redScale = 0.88f, greenScale = 0.96f, blueScale = 1.16f, contrast = 0.08f, saturation = -0.15f),
        Look("ember", "Ember", LookFamily.Mood, redScale = 1.18f, greenScale = 0.97f, blueScale = 0.88f, contrast = 0.12f, saturation = 0.1f),
        Look("neon", "Neon", LookFamily.Mood, greenScale = 0.95f, blueScale = 1.12f, contrast = 0.16f, saturation = 0.2f),
        Look("mint", "Mint", LookFamily.Mood, redScale = 0.95f, greenScale = 1.05f, contrast = 0.06f, saturation = 0.1f),
        Look(
            "dusk", "Dusk", LookFamily.Mood,
            redScale = 1.04f, blueScale = 1.06f, contrast = 0.06f, saturation = -0.06f,
            shadowTint = INK, highlightTint = ROSE, split = 0.38f, fade = 0.2f, vignette = 0.2f
        ),
        Look(
            "midnight", "Midnight", LookFamily.Mood,
            redScale = 0.86f, greenScale = 0.93f, blueScale = 1.18f, contrast = 0.14f, saturation = -0.2f,
            vignette = 0.36f, bloom = 0.22f
        ),
        Look(
            "bonfire", "Bonfire", LookFamily.Mood,
            redScale = 1.2f, greenScale = 0.98f, blueScale = 0.82f, contrast = 0.14f, saturation = 0.12f,
            bloom = 0.3f, vignette = 0.24f
        ),

        // ---- Cinema: the graded-feature looks -----------------------------------
        Look(
            "blockbuster", "Blockbuster", LookFamily.Cinema,
            contrast = 0.16f, saturation = 0.06f,
            shadowTint = TEAL, highlightTint = AMBER, split = 0.55f, vignette = 0.2f
        ),
        Look(
            "thriller", "Thriller", LookFamily.Cinema,
            redScale = 0.96f, blueScale = 1.06f, contrast = 0.2f, saturation = -0.22f,
            shadowTint = STEEL, highlightTint = STEEL, split = 0.34f, vignette = 0.32f, grain = 0.2f
        ),
        Look(
            "romance", "Romance", LookFamily.Cinema,
            redScale = 1.06f, blueScale = 0.99f, contrast = -0.08f, saturation = 0.04f,
            shadowTint = ROSE, highlightTint = CREAM, split = 0.3f, bloom = 0.34f, fade = 0.18f
        ),
        Look(
            "western", "Western", LookFamily.Cinema,
            redScale = 1.12f, greenScale = 1.02f, blueScale = 0.85f, contrast = 0.14f, saturation = -0.08f,
            shadowTint = AMBER, highlightTint = CREAM, split = 0.3f, vignette = 0.3f, grain = 0.24f
        ),
        Look(
            "documentary", "Doc", LookFamily.Cinema,
            contrast = 0.1f, saturation = -0.06f, fade = 0.14f, grain = 0.14f
        ),
        Look(
            "dream", "Dream", LookFamily.Cinema,
            redScale = 1.03f, blueScale = 1.05f, contrast = -0.14f, saturation = 0.08f,
            bloom = 0.55f, fade = 0.3f
        ),

        // ---- Retro: the ones that look like a format, not a grade ---------------
        Look(
            "vhs", "VHS", LookFamily.Retro,
            redScale = 1.06f, greenScale = 0.98f, blueScale = 1.08f, contrast = -0.1f, saturation = 0.18f,
            fade = 0.34f, grain = 0.45f, vignette = 0.26f, bloom = 0.24f
        ),
        Look(
            "polaroid", "Polaroid", LookFamily.Retro,
            redScale = 1.08f, greenScale = 1.02f, blueScale = 0.92f, contrast = -0.12f, saturation = -0.1f,
            fade = 0.46f, shadowTint = CREAM, highlightTint = CREAM, split = 0.26f, vignette = 0.2f
        ),
        Look(
            "disposable", "Disposable", LookFamily.Retro,
            redScale = 1.06f, blueScale = 0.96f, contrast = 0.2f, saturation = 0.14f,
            grain = 0.4f, vignette = 0.4f, bloom = 0.3f
        ),
        Look(
            "crt", "CRT", LookFamily.Retro,
            greenScale = 1.04f, blueScale = 1.1f, contrast = 0.18f, saturation = 0.24f,
            bloom = 0.42f, vignette = 0.3f, grain = 0.2f
        ),
        Look(
            "y2k", "Y2K", LookFamily.Retro,
            redScale = 1.04f, greenScale = 1.0f, blueScale = 1.1f, contrast = 0.12f, saturation = 0.3f,
            shadowTint = INK, highlightTint = ROSE, split = 0.34f, bloom = 0.3f
        ),

        // ---- Cinema, more: the other graded looks people ask for by name --------
        Look(
            "cyberpunk", "Cyberpunk", LookFamily.Cinema,
            redScale = 1.04f, greenScale = 0.9f, blueScale = 1.14f, contrast = 0.22f, saturation = 0.2f,
            shadowTint = TEAL, highlightTint = MAGENTA, split = 0.6f, bloom = 0.3f, vignette = 0.24f
        ),
        Look(
            "forest", "Forest", LookFamily.Cinema,
            redScale = 0.95f, greenScale = 1.06f, blueScale = 0.94f, contrast = 0.12f, saturation = -0.08f,
            shadowTint = FOREST, highlightTint = CREAM, split = 0.36f, vignette = 0.22f
        ),
        Look(
            "desert", "Desert", LookFamily.Cinema,
            redScale = 1.16f, greenScale = 1.05f, blueScale = 0.8f, contrast = 0.18f, saturation = -0.16f,
            fade = 0.12f, highlightTint = AMBER, shadowTint = AMBER, split = 0.32f
        ),

        // ---- Social: the looks a feed is made of --------------------------------
        Look("bright", "Bright", LookFamily.Social, redScale = 1.05f, greenScale = 1.05f, blueScale = 1.07f, contrast = 0.12f, saturation = 0.12f),
        Look(
            "insta", "Insta", LookFamily.Social,
            redScale = 1.08f, greenScale = 1.02f, blueScale = 0.96f, contrast = 0.08f, saturation = 0.04f,
            fade = 0.22f, vignette = 0.16f
        ),
        Look(
            "glow", "Glow", LookFamily.Social,
            redScale = 1.07f, greenScale = 1.02f, blueScale = 0.98f, contrast = -0.1f, saturation = 0.06f,
            bloom = 0.45f, fade = 0.1f
        ),
        Look("food", "Food", LookFamily.Social, redScale = 1.1f, greenScale = 1.03f, blueScale = 0.9f, contrast = 0.16f, saturation = 0.24f),
        Look(
            "portrait", "Portrait", LookFamily.Social,
            redScale = 1.06f, greenScale = 1.01f, blueScale = 0.97f, contrast = -0.05f, saturation = -0.06f,
            shadowTint = ROSE, highlightTint = CREAM, split = 0.22f, bloom = 0.18f
        ),
        Look(
            "beach", "Beach", LookFamily.Social,
            redScale = 0.98f, greenScale = 1.05f, blueScale = 1.12f, contrast = 0.1f, saturation = 0.22f,
            highlightTint = AMBER, shadowTint = TEAL, split = 0.3f
        ),
        Look(
            "sunset", "Sunset", LookFamily.Social,
            redScale = 1.18f, greenScale = 0.98f, blueScale = 0.92f, contrast = 0.1f, saturation = 0.16f,
            shadowTint = MAGENTA, highlightTint = AMBER, split = 0.42f, bloom = 0.2f
        ),
        Look(
            "matte", "Matte", LookFamily.Social,
            contrast = -0.16f, saturation = -0.18f, fade = 0.36f, shadowTint = INK, highlightTint = CREAM, split = 0.2f
        ),
        Look(
            "cherry", "Cherry", LookFamily.Social,
            redScale = 1.12f, greenScale = 0.95f, blueScale = 1.02f, contrast = 0.14f, saturation = 0.18f,
            highlightTint = ROSE, split = 0.3f
        ),
        Look(
            "city", "City", LookFamily.Social,
            redScale = 0.97f, blueScale = 1.06f, contrast = 0.24f, saturation = -0.2f,
            shadowTint = STEEL, highlightTint = CREAM, split = 0.26f, grain = 0.12f, vignette = 0.2f
        ),
        Look("pastel", "Pastel", LookFamily.Social, redScale = 1.04f, greenScale = 1.02f, blueScale = 1.06f, contrast = -0.22f, saturation = -0.12f, fade = 0.3f)
    )

    fun byId(id: String?): Look = catalog.firstOrNull { it.id == id } ?: None

    /**
     * A look and the manual sliders, folded together.
     *
     * The look grades first and the sliders refine on top, which is the order a
     * colourist works in - so a warm look plus a saturation nudge behaves the way
     * you would expect rather than fighting itself. Exposure, temperature and
     * tint are all channel gains, so they fold into the look's; the rest add to
     * the look's own value of the same move and are held to the shader's range.
     */
    fun grade(lookId: String?, intensity: Float, adjust: Adjust = Adjust.NONE): Grade {
        val look = byId(lookId).atIntensity(intensity)
        val exposure = Math.pow(2.0, (adjust.exposure * Adjust.EXPOSURE_STOPS).toDouble()).toFloat()
        val warm = adjust.temperature * TEMPERATURE_REACH
        val tint = adjust.tint * TINT_REACH
        return Grade(
            redScale = look.redScale * exposure * (1f + warm) * (1f + tint / 2f),
            greenScale = look.greenScale * exposure * (1f - tint),
            blueScale = look.blueScale * exposure * (1f - warm) * (1f + tint / 2f),
            contrast = (look.contrast + adjust.contrast).coerceIn(-1f, 1f),
            saturation = (look.saturation + adjust.saturation).coerceIn(-1f, 1f),
            fade = (look.fade + adjust.fade).coerceIn(0f, 1f),
            shadowTint = look.shadowTint,
            highlightTint = look.highlightTint,
            split = look.split,
            bloom = look.bloom,
            vignette = (look.vignette + adjust.vignette).coerceIn(0f, 1f),
            grain = (look.grain + adjust.grain).coerceIn(0f, 1f),
            brightness = adjust.brightness * BRIGHTNESS_REACH,
            highlights = adjust.highlights,
            shadows = adjust.shadows,
            sharpen = adjust.sharpen,
            hueDegrees = adjust.hue * Adjust.HUE_TURN_DEGREES,
            hsl = adjust.hsl,
            // The curve is the user's own and a look never carries one, so it
            // passes through rather than folding with anything.
            curve = adjust.curve
        )
    }

    /** How far Brightness at full lifts every channel: a third of the way to white. */
    const val BRIGHTNESS_REACH = 0.35f

    /** How much Temperature at full pushes red up and blue down, and Tint green against the other two. */
    const val TEMPERATURE_REACH = 0.18f
    const val TINT_REACH = 0.14f

    /**
     * Reference colors for the filter chips: a shadow, a skin midtone and a warm
     * highlight. Three points are enough to show a cast, a crush and a lift - which
     * is what separates one look from another at chip size.
     *
     * The highlight is deliberately not near-white. A bright reference clips to flat
     * white under any contrast boost, so Vivid, Punch, Sepia and Neon all rendered
     * an identical white band and the row stopped telling you anything. At these
     * values only Noir and Bleach clip, which is honest - crushing is what those two
     * are for.
     */
    private val REFERENCE = intArrayOf(
        0xFF3A4465.toInt(),
        0xFFB8856A.toInt(),
        0xFFCCC0B0.toInt()
    )

    /** What this look does to the reference ramp, for drawing a chip. */
    fun swatch(look: Look, intensity: Float = 1f): IntArray {
        val g = grade(look.id, intensity)
        return IntArray(REFERENCE.size) { g.applyTo(REFERENCE[it]) }
    }
}
