import com.squish.app.media.effects.SkinTone
import com.squish.app.media.effects.SurfaceBlur
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * Smooth skin: the numbers against the shader's own, and the blur executed.
 *
 * The file SkinTone.kt's header has named this suite since the day it was
 * written - "the numbers are the shader's own, parsed back out of
 * squish_look_es2.glsl by tools/jvm/SkinChecks.kt, so the two cannot drift" -
 * and it did not exist. A check named in a comment and never written is worse
 * than no comment: it says a drift is impossible when nothing is watching.
 *
 * Two halves:
 *
 *  1. The constants, parsed out of the shader. Eleven numbers describe where the
 *     skin is and how far the blur reaches, and they are written twice - once in
 *     GLSL and once in Kotlin - because one side runs on the GPU for a video and
 *     the other on the CPU for a photo overlay. A pair that parts means the same
 *     slider does two different things to a photo and the video beside it, which
 *     is the one screen where they meet.
 *  2. The blur itself, executed. It is a surface blur, not a blur: the whole
 *     feature is the weighting that keeps an eyelash and takes a pore, and
 *     nothing about reading the code says whether it does.
 */

private val problems = mutableListOf<String>()
private fun flag(msg: String) { problems += msg }
private fun check(ok: Boolean, msg: String) { if (!ok) flag(msg) }
private fun near(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) < eps

private const val SHADER = "app/src/main/assets/squish_look_es2.glsl"

private fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF) =
    (a shl 24) or (r shl 16) or (g shl 8) or b

private fun red(p: Int) = (p shr 16) and 0xFF
private fun green(p: Int) = (p shr 8) and 0xFF
private fun blue(p: Int) = p and 0xFF
private fun alpha(p: Int) = (p shr 24) and 0xFF

/** A mid skin tone, inside the locus by construction - asserted below, not assumed. */
private const val SKIN_R = 214
private const val SKIN_G = 163
private const val SKIN_B = 140

/** How spread out a patch's pixels are, as the mean distance from its mean colour. */
private fun spread(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var mr = 0f; var mg = 0f; var mb = 0f
    pixels.forEach { mr += red(it); mg += green(it); mb += blue(it) }
    mr /= pixels.size; mg /= pixels.size; mb /= pixels.size
    var total = 0f
    pixels.forEach {
        val dr = red(it) - mr; val dg = green(it) - mg; val db = blue(it) - mb
        total += sqrt(dr * dr + dg * dg + db * db)
    }
    return total / pixels.size
}

/** A patch of one colour with a little noise laid over it, the same every run. */
private fun noisy(w: Int, h: Int, r: Int, g: Int, b: Int, swing: Int): IntArray {
    val out = IntArray(w * h)
    var seed = 12345
    for (i in out.indices) {
        // A fixed sequence: a suite that is different every run cannot be
        // negative-tested, and the point here is the spread, not the pattern.
        seed = seed * 1103515245 + 12345
        val n = ((seed shr 16) and 0xFF) % (2 * swing + 1) - swing
        out[i] = argb(
            (r + n).coerceIn(0, 255),
            (g + n).coerceIn(0, 255),
            (b + n).coerceIn(0, 255)
        )
    }
    return out
}

fun main() {
    // ---- 1. The eleven numbers, against the shader. -----------------------
    val shader = File(SHADER)
    if (!shader.isFile) flag("$SHADER is not there - did it move?") else {
        val text = shader.readText()

        /** The arguments of the one `smoothstep(a, b, x)` in the shader whose x is [of]. */
        fun steps(of: String): List<Pair<Float, Float>> =
            Regex("""smoothstep\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*$of\s*\)""")
                .findAll(text)
                .map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
                .toList()

        // skinWeight's three bands, each read as a pair of smoothsteps on cb, cr
        // and y. Two per variable: the rising edge and the falling one.
        val cb = steps("cb")
        val cr = steps("cr")
        val y = steps("y")
        check(cb.size == 2, "the shader has ${cb.size} smoothsteps on cb, not the two the skin locus needs")
        check(cr.size == 2, "the shader has ${cr.size} smoothsteps on cr, not two")
        check(y.size >= 2, "the shader has ${y.size} smoothsteps on y, fewer than the two the skin locus needs")
        if (cb.size == 2) {
            check(near(cb[0].first, SkinTone.CB_LOW) && near(cb[0].second, SkinTone.CB_LOW_END),
                "the shader's blue-difference floor is ${cb[0]}, SkinTone's is ${SkinTone.CB_LOW}..${SkinTone.CB_LOW_END}")
            check(near(cb[1].first, SkinTone.CB_HIGH) && near(cb[1].second, SkinTone.CB_HIGH_END),
                "the shader's blue-difference ceiling is ${cb[1]}, SkinTone's is ${SkinTone.CB_HIGH}..${SkinTone.CB_HIGH_END}")
        }
        if (cr.size == 2) {
            check(near(cr[0].first, SkinTone.CR_LOW) && near(cr[0].second, SkinTone.CR_LOW_END),
                "the shader's red-difference floor is ${cr[0]}, SkinTone's is ${SkinTone.CR_LOW}..${SkinTone.CR_LOW_END}")
            check(near(cr[1].first, SkinTone.CR_HIGH) && near(cr[1].second, SkinTone.CR_HIGH_END),
                "the shader's red-difference ceiling is ${cr[1]}, SkinTone's is ${SkinTone.CR_HIGH}..${SkinTone.CR_HIGH_END}")
        }
        if (y.size >= 2) {
            check(y.any { near(it.first, SkinTone.DARK) && near(it.second, SkinTone.DARK_END) },
                "no smoothstep on y matches SkinTone's dark end ${SkinTone.DARK}..${SkinTone.DARK_END}; the shader has $y")
            check(y.any { near(it.first, SkinTone.BRIGHT) && near(it.second, SkinTone.BRIGHT_END) },
                "no smoothstep on y matches SkinTone's bright end ${SkinTone.BRIGHT}..${SkinTone.BRIGHT_END}; the shader has $y")
        }

        // The blur's own three: how different a tap may be before it stops
        // counting, and the two that set the reach.
        val keep = Regex("""smoothstep\(0\.0,\s*([\d.]+),\s*length\(s - centre\)\)""").find(text)
        if (keep == null) flag("the shader's surfaceBlur no longer weighs a tap by its distance from the middle")
        else check(near(keep.groupValues[1].toFloat(), SkinTone.DETAIL_KEEP),
            "the shader keeps detail to ${keep.groupValues[1]}, SkinTone to ${SkinTone.DETAIL_KEEP} - " +
                "the two sides would blur a face by different amounts")

        val reach = Regex("""max\(uTexel\.x,\s*uTexel\.y\)\s*\*\s*\(([\d.]+)\s*\+\s*([\d.]+)\s*\*\s*uSmooth\)""").find(text)
        if (reach == null) flag("the shader no longer works its blur radius out of uTexel and uSmooth")
        else {
            check(near(reach.groupValues[1].toFloat(), SkinTone.MIN_RADIUS),
                "the shader's smallest radius is ${reach.groupValues[1]}, SkinTone's is ${SkinTone.MIN_RADIUS}")
            check(near(reach.groupValues[2].toFloat(), SkinTone.RADIUS_REACH),
                "the shader's radius reach is ${reach.groupValues[2]}, SkinTone's is ${SkinTone.RADIUS_REACH}")
        }

        // And the ring itself: twelve taps, the second six at 0.55, one step
        // apart. Written as literals on both sides, so compared as literals.
        check(Regex("""for \(int i = 0; i < ${SurfaceBlur.TAPS}; i\+\+\)""").containsMatchIn(text),
            "the shader's surfaceBlur no longer takes ${SurfaceBlur.TAPS} taps")
        check(text.contains("float(i) * ${SurfaceBlur.STEP_RADIANS}"),
            "the shader's taps are no longer ${SurfaceBlur.STEP_RADIANS} radians apart")
        check(Regex("""\(i < ${SurfaceBlur.OUTER_TAPS}\) \? r : r \* ${SurfaceBlur.INNER_SCALE}""").containsMatchIn(text),
            "the shader's two rings are no longer ${SurfaceBlur.OUTER_TAPS} out and the rest at ${SurfaceBlur.INNER_SCALE}")

        // The order matters and is the whole reason the CPU path can be exact:
        // the shader smooths *before* the colour chain, and Grade.graded is the
        // colour chain. If the smooth ever moves after the gain, the preview's
        // pass would have to move with it or stop agreeing.
        val smoothAt = text.indexOf("uSmooth > 0.001")
        val gainAt = text.indexOf("c *= uGain;")
        check(smoothAt in 1 until gainAt,
            "the shader no longer smooths before `c *= uGain` - TimelinePreview runs SurfaceBlur before " +
                "Grade.applyTo precisely because the shader's order is smooth-then-colour, so this moving " +
                "means the preview and the file have parted on every photo overlay")
    }

    // ---- 2. The reach, in texture coordinates. ----------------------------
    //
    // A circle in texture space is an ellipse in pixels, and the radius is set
    // by the *shorter* side: the longer side has the smaller texel and `max`
    // picks the other one. Taking the radius in pixels instead would soften a
    // landscape photo more across than down while the file did the opposite.
    run {
        val wide = SurfaceBlur.radiusUv(1920, 1080, 1f)
        check(near(wide, (1f / 1080) * (SkinTone.MIN_RADIUS + SkinTone.RADIUS_REACH)),
            "a 1920x1080 picture's reach at full is $wide, not the shorter side's texel times the reach")
        val tall = SurfaceBlur.radiusUv(1080, 1920, 1f)
        check(near(wide, tall), "a picture and the same picture turned have different reaches: $wide and $tall")
        check(SurfaceBlur.radiusUv(1000, 1000, 1f) > SurfaceBlur.radiusUv(1000, 1000, 0.25f),
            "the reach does not grow with the slider")
        check(near(SurfaceBlur.radiusUv(1000, 1000, 0f), 0.001f * SkinTone.MIN_RADIUS),
            "at nothing on the slider the reach is not the smallest radius")
        check(SurfaceBlur.radiusUv(0, 0, 1f) == 0f, "a picture with no size answers a reach rather than zero")
    }

    // ---- 3. The locus: what is skin and what is not. -----------------------
    run {
        val skin = SkinTone.weight(SKIN_R / 255f, SKIN_G / 255f, SKIN_B / 255f)
        check(skin > 0.5f, "the fixture's skin tone weighs $skin - it is not inside the locus, so every " +
            "assertion below about skin is about nothing")
        check(SkinTone.weight(0.2f, 0.4f, 0.9f) < 0.05f, "a strong blue weighs as skin")
        check(SkinTone.weight(0.05f, 0.04f, 0.03f) < 0.05f, "near-black weighs as skin - there is no " +
            "chrominance down there worth trusting")
        check(SkinTone.weight(0.99f, 0.99f, 0.99f) < 0.05f, "a blown-out white weighs as skin")
        // The point of measuring chrominance rather than brightness: a dark
        // skin tone and a pale one are both skin. The same hue, two brightnesses.
        val pale = SkinTone.weight(0.94f, 0.80f, 0.72f)
        val deep = SkinTone.weight(0.42f, 0.28f, 0.22f)
        check(pale > 0.3f, "a pale skin tone weighs only $pale")
        check(deep > 0.3f, "a deep skin tone weighs only $deep - the locus has stopped being about " +
            "chrominance, which is the one thing that makes this work across skin tones")
    }

    // ---- 4. The blur, executed. --------------------------------------------
    val w = 64
    val h = 64
    run {
        // Nothing on the slider changes nothing.
        val flat = noisy(w, h, SKIN_R, SKIN_G, SKIN_B, 10)
        val untouched = flat.copyOf()
        SurfaceBlur.smoothSkin(flat, w, h, 0f)
        check(flat.contentEquals(untouched), "the slider at nothing still changed the picture")

        // Noise on skin comes down.
        val noisySkin = noisy(w, h, SKIN_R, SKIN_G, SKIN_B, 10)
        val before = spread(noisySkin)
        SurfaceBlur.smoothSkin(noisySkin, w, h, 1f)
        val after = spread(noisySkin)
        check(after < before * 0.8f,
            "noise on skin only came down from $before to $after - the blur is not reaching, which at a " +
                "radius of a pixel or two is what a nearest-neighbour sample does")

        // The same noise on something that is not skin is left alone.
        val noisyBlue = noisy(w, h, 60, 90, 220, 10)
        val blueBefore = spread(noisyBlue)
        SurfaceBlur.smoothSkin(noisyBlue, w, h, 1f)
        check(near(spread(noisyBlue), blueBefore, 0.01f),
            "a blue patch was smoothed too ($blueBefore to ${spread(noisyBlue)}) - the hold on skin has gone, " +
                "and Smooth skin would soften a whole picture")

        // Half the slider does less than all of it.
        val half = noisy(w, h, SKIN_R, SKIN_G, SKIN_B, 10)
        SurfaceBlur.smoothSkin(half, w, h, 0.5f)
        val full = noisy(w, h, SKIN_R, SKIN_G, SKIN_B, 10)
        SurfaceBlur.smoothSkin(full, w, h, 1f)
        check(spread(half) > spread(full), "half the slider smoothed as much as all of it: ${spread(half)} and ${spread(full)}")
        check(spread(half) < before, "half the slider did nothing at all")
    }

    // ---- 5. The weighting, which is the whole feature. ---------------------
    //
    // A plain blur takes the eyes and the lashes with the pores. This one weighs
    // a tap down by how unlike the middle it is, so an edge inside skin survives.
    run {
        // Skin on the left, a dark lash line on the right, no noise at all.
        val edge = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            edge[y * w + x] = if (x < w / 2) argb(SKIN_R, SKIN_G, SKIN_B) else argb(30, 20, 18)
        }
        val before = edge.copyOf()
        SurfaceBlur.smoothSkin(edge, w, h, 1f)
        // The pixel two in from the edge, on the skin side, must still be skin:
        // the dark taps across the line weigh almost nothing.
        val at = (h / 2) * w + (w / 2 - 2)
        val moved = abs(red(edge[at]) - red(before[at])) +
            abs(green(edge[at]) - green(before[at])) +
            abs(blue(edge[at]) - blue(before[at]))
        check(moved <= 6, "a skin pixel two from a lash line moved by $moved - the blur is pulling the dark " +
            "side across, which is a smudged eye rather than a smoothed cheek")
        // A patch of one colour has nothing to average: every tap equals the
        // middle, so the picture must come back exactly as it went in.
        val plain = IntArray(w * h) { argb(SKIN_R, SKIN_G, SKIN_B) }
        val plainBefore = plain.copyOf()
        SurfaceBlur.smoothSkin(plain, w, h, 1f)
        check(plain.contentEquals(plainBefore), "an evenly coloured patch of skin was changed by the blur")
    }

    // ---- 6. What a photo overlay is drawn this way for. --------------------
    run {
        val withAlpha = IntArray(w * h) { i ->
            argb(SKIN_R, SKIN_G, SKIN_B, a = if (i % 3 == 0) 0 else if (i % 3 == 1) 128 else 255)
        }
        val before = withAlpha.copyOf()
        SurfaceBlur.smoothSkin(withAlpha, w, h, 1f)
        val kept = withAlpha.indices.all { alpha(withAlpha[it]) == alpha(before[it]) }
        check(kept, "the blur changed a pixel's alpha - a photo overlay is drawn pixel by pixel precisely so " +
            "that its transparency survives")
    }

    // ---- 7. It does not walk off the picture. ------------------------------
    run {
        // The taps at a corner are mostly outside, and the sampler clamps. A
        // one-pixel and a one-row picture are the degenerate cases a photo
        // overlay can genuinely be mid-gesture, while a width is still being
        // measured.
        listOf(1 to 1, 1 to 64, 64 to 1, 3 to 3).forEach { (pw, ph) ->
            val tiny = IntArray(pw * ph) { argb(SKIN_R, SKIN_G, SKIN_B) }
            val threw = runCatching { SurfaceBlur.smoothSkin(tiny, pw, ph, 1f) }.exceptionOrNull()
            check(threw == null, "a ${pw}x$ph picture threw ${threw?.javaClass?.simpleName}")
        }
        // And a picture whose array is short of its own size is refused rather
        // than read past: the sizes come from a bitmap and an array made apart.
        val short = IntArray(10)
        val threw = runCatching { SurfaceBlur.smoothSkin(short, 64, 64, 1f) }.exceptionOrNull()
        check(threw == null, "a short array threw ${threw?.javaClass?.simpleName} rather than being left alone")
    }

    // ---- 8. The preview actually calls it. ---------------------------------
    //
    // The arithmetic being right is worth nothing if nothing runs it, and this
    // whole fix exists because a slider was offered and never read.
    run {
        val preview = File("app/src/main/java/com/squish/app/editor/TimelinePreview.kt")
        if (!preview.isFile) flag("TimelinePreview.kt is not there - this check has rotted") else {
            val text = preview.readText()
            val callAt = text.indexOf("SurfaceBlur.smoothSkin(")
            val gradeAt = text.indexOf("grade.applyTo(pixels")
            check(callAt >= 0, "the preview's photo-overlay grade does not call SurfaceBlur.smoothSkin, so " +
                "Smooth skin is a slider that does nothing on screen and something in the file")
            check(gradeAt >= 0, "the preview no longer grades a photo overlay through Grade.applyTo")
            check(callAt in 0 until gradeAt,
                "the preview smooths *after* grading, where the shader smooths before the colour chain - " +
                    "the picture on screen and the picture in the file will differ by a shade on any graded face")
        }
    }

    println("skin: ${SurfaceBlur.TAPS} taps, the locus and the reach against the shader's own numbers")
    if (problems.isEmpty()) {
        println("PASS - Smooth skin holds to skin, keeps an edge, keeps alpha, and the two sides read one set of numbers")
    } else {
        println("FAIL (${problems.size})")
        problems.forEach { println("  - $it") }
        exitProcess(1)
    }
}
