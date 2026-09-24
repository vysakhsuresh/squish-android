package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LookFamily(val label: String) {
    Essentials("Essentials"),
    Film("Film"),
    Mood("Mood"),
    Cinema("Cinema"),
    Retro("Retro")
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
    val saturation: Float,
    val fade: Float = 0f,
    val shadowTint: Int = NEUTRAL_TINT,
    val highlightTint: Int = NEUTRAL_TINT,
    val split: Float = 0f,
    val bloom: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f
) {
    val hasChannelGain: Boolean
        get() = abs(redScale - 1f) > 1e-4f || abs(greenScale - 1f) > 1e-4f || abs(blueScale - 1f) > 1e-4f
    val hasContrast: Boolean get() = abs(contrast) > 1e-4f
    val hasSaturation: Boolean get() = abs(saturation) > 1e-4f

    /**
     * Whether this grade needs the shader.
     *
     * Grain and vignette are spatial and bloom reads neighbouring pixels, so none of
     * the three can be expressed as a per-pixel colour transform - which is all the
     * built-in effects do. A grade without them stays on the built-ins, because
     * hardware-backed passes beat anything written by hand.
     */
    val needsShader: Boolean
        get() = abs(fade) > 1e-4f || abs(split) > 1e-4f || abs(bloom) > 1e-4f ||
            abs(vignette) > 1e-4f || abs(grain) > 1e-4f

    val isIdentity: Boolean get() = !hasChannelGain && !hasContrast && !hasSaturation && !needsShader

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

        // The same split-tone and fade the shader does, in the same order. Spatial
        // moves - vignette, grain, bloom - have no meaning for one colour and are
        // simply absent here; a swatch shows the grade, not the texture.
        if (abs(split) > 1e-4f) {
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
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
}

object Looks {

    val None = Look("none", "Original", LookFamily.Essentials)

    // Tints for the split-tone looks, named rather than inlined so the same teal
    // is the same teal in three places.
    private const val TEAL = 0xFF4E7F86.toInt()
    private const val AMBER = 0xFFA8794E.toInt()
    private const val ROSE = 0xFFA36775.toInt()
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
        )
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
            saturation = (look.saturation + saturation).coerceIn(-1f, 1f),
            fade = look.fade,
            shadowTint = look.shadowTint,
            highlightTint = look.highlightTint,
            split = look.split,
            bloom = look.bloom,
            vignette = look.vignette,
            grain = look.grain
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
