package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LookFamily(val label: String) {
    Essentials("Essentials"),
    Film("Film"),
    Mood("Mood")
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
 * Every parameter is normalized to -1..1 or a multiplier around 1, so a look can be
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
    val saturation: Float = 0f
) {
    /** The look dialled back toward no-op. t=0 is the untouched picture, t=1 full. */
    fun atIntensity(t: Float): Look {
        val k = t.coerceIn(0f, 1f)
        return copy(
            redScale = 1f + (redScale - 1f) * k,
            greenScale = 1f + (greenScale - 1f) * k,
            blueScale = 1f + (blueScale - 1f) * k,
            contrast = contrast * k,
            saturation = saturation * k
        )
    }

    val isIdentity: Boolean
        get() = abs(redScale - 1f) < 1e-4f && abs(greenScale - 1f) < 1e-4f &&
            abs(blueScale - 1f) < 1e-4f && abs(contrast) < 1e-4f && abs(saturation) < 1e-4f
}

/**
 * What actually reaches the GPU: one look and the manual color sliders folded into
 * a single set of moves.
 *
 * Folding matters. Applying a look and then three more adjustments would stack six
 * shader passes on every frame; combined, it is three passes no matter how much
 * grading is going on.
 */
data class Grade(
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val contrast: Float,
    val saturation: Float
) {
    val hasChannelGain: Boolean
        get() = abs(redScale - 1f) > 1e-4f || abs(greenScale - 1f) > 1e-4f || abs(blueScale - 1f) > 1e-4f
    val hasContrast: Boolean get() = abs(contrast) > 1e-4f
    val hasSaturation: Boolean get() = abs(saturation) > 1e-4f
    val isIdentity: Boolean get() = !hasChannelGain && !hasContrast && !hasSaturation

    /**
     * The same maths the shaders do, on one color.
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

        if (hasContrast) {
            // Media3's Contrast: a factor either side of mid-gray, steepening as the
            // value approaches 1 and flattening to gray as it approaches -1.
            val f = (1f + contrast) / (1.0001f - contrast)
            r = f * (r - 0.5f) + 0.5f
            g = f * (g - 0.5f) + 0.5f
            b = f * (b - 0.5f) + 0.5f
        }

        if (hasSaturation) {
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val s = (1f + saturation).coerceAtLeast(0f)
            r = lum + (r - lum) * s
            g = lum + (g - lum) * s
            b = lum + (b - lum) * s
        }

        fun byte(v: Float) = (min(1f, max(0f, v)) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}

object Looks {

    val None = Look("none", "Original", LookFamily.Essentials)

    val catalog: List<Look> = listOf(
        None,
        // Essentials - the everyday moves
        Look("vivid",   "Vivid",   LookFamily.Essentials, contrast = 0.14f, saturation = 0.2f),
        Look("punch",   "Punch",   LookFamily.Essentials, contrast = 0.26f, saturation = 0.1f),
        Look("soft",    "Soft",    LookFamily.Essentials, contrast = -0.18f, saturation = -0.1f),
        Look("clean",   "Clean",   LookFamily.Essentials, redScale = 1.01f, blueScale = 1.03f, contrast = 0.08f),

        // Film - stocks and darkroom habits
        Look("noir",    "Noir",    LookFamily.Film, contrast = 0.22f, saturation = -1f),
        Look("silver",  "Silver",  LookFamily.Film, redScale = 1.03f, blueScale = 1.05f, contrast = 0.06f, saturation = -1f),
        Look("sepia",   "Sepia",   LookFamily.Film, redScale = 1.22f, greenScale = 1.04f, blueScale = 0.8f, contrast = 0.05f, saturation = -0.85f),
        Look("faded",   "Faded",   LookFamily.Film, redScale = 1.05f, blueScale = 1.04f, contrast = -0.22f, saturation = -0.25f),
        Look("kodak",   "Kodak",   LookFamily.Film, redScale = 1.08f, greenScale = 1.0f, blueScale = 0.95f, contrast = 0.12f, saturation = 0.14f),
        Look("bleach",  "Bleach",  LookFamily.Film, contrast = 0.3f, saturation = -0.5f),

        // Mood - a room, a time of day, a genre
        Look("golden",  "Golden",  LookFamily.Mood, redScale = 1.14f, greenScale = 1.04f, blueScale = 0.88f, contrast = 0.06f, saturation = 0.08f),
        Look("arctic",  "Arctic",  LookFamily.Mood, redScale = 0.93f, greenScale = 1.0f, blueScale = 1.12f, contrast = 0.1f, saturation = 0.06f),
        Look("moonlit", "Moonlit", LookFamily.Mood, redScale = 0.88f, greenScale = 0.96f, blueScale = 1.16f, contrast = 0.08f, saturation = -0.15f),
        Look("ember",   "Ember",   LookFamily.Mood, redScale = 1.18f, greenScale = 0.97f, blueScale = 0.88f, contrast = 0.12f, saturation = 0.1f),
        Look("neon",    "Neon",    LookFamily.Mood, redScale = 1.0f, greenScale = 0.95f, blueScale = 1.12f, contrast = 0.16f, saturation = 0.2f),
        Look("mint",    "Mint",    LookFamily.Mood, redScale = 0.95f, greenScale = 1.05f, blueScale = 1.0f, contrast = 0.06f, saturation = 0.1f)
    )

    fun byId(id: String?): Look = catalog.firstOrNull { it.id == id } ?: None

    /**
     * A look and the manual sliders, folded together.
     *
     * The look grades first and the sliders refine on top, which is the order a
     * colourist works in - so a warm look plus a saturation nudge behaves the way
     * you would expect rather than fighting itself.
     */
    fun grade(
        lookId: String?,
        intensity: Float,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Grade {
        val look = byId(lookId).atIntensity(intensity)
        val gain = (1f + brightness).coerceIn(0f, 2f)
        return Grade(
            redScale = look.redScale * gain,
            greenScale = look.greenScale * gain,
            blueScale = look.blueScale * gain,
            contrast = (look.contrast + contrast).coerceIn(-1f, 1f),
            saturation = (look.saturation + saturation).coerceIn(-1f, 1f)
        )
    }

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
        val g = grade(look.id, intensity, 0f, 0f, 0f)
        return IntArray(REFERENCE.size) { g.applyTo(REFERENCE[it]) }
    }
}
