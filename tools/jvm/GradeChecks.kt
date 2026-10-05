import com.squish.app.media.effects.Adjust
import com.squish.app.media.effects.AdjustField
import com.squish.app.media.effects.Grade
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
import com.squish.app.media.effects.Looks
import com.squish.app.media.effects.SkinTone
import kotlin.math.abs
import kotlin.system.exitProcess

// The colour sliders (B12), executed: each is nothing at zero and something
// away from it, brightness is an offset and not a gain, the look and the
// sliders fold into one grade, the hue wheel meets itself, and an HSL band
// reaches its own colours and no others. Grade.applyTo is the CPU copy of
// the shader's maths, so what is checked here is what a swatch shows and, as
// far as the arithmetic goes, what the GPU does.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun rgb(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

fun main() {
    val skin = px(0xB8, 0x85, 0x6A)
    val grey = px(0x80, 0x80, 0x80)

    // --- Nothing moved is nothing done. ------------------------------------------
    check(Adjust.NONE.isIdentity, "an untouched Adjust is not identity")
    check(Looks.grade(null, 1f).isIdentity, "no look and no sliders is not an identity grade")
    check(Looks.grade(null, 1f).applyTo(skin) == skin, "an identity grade changed a pixel")
    for (field in AdjustField.entries) {
        check(field.set(Adjust(), 0f).isIdentity, "${field.label} at zero is not identity")
        check(near(field.of(field.set(Adjust(), 0.4f)), 0.4f), "${field.label} does not read back what was set")
        check(field.of(field.set(Adjust(), 5f)) <= field.max && field.of(field.set(Adjust(), -5f)) >= field.min, "${field.label} is not held to its range")
    }

    // --- Every slider away from zero does something to the grade, and the
    //     per-pixel ones to a pixel. Grain, vignette and sharpening are spatial
    //     and leave a lone pixel alone by design.
    // Smooth skin is spatial too: it is a blur of the neighbours, so it cannot
    // move a lone pixel any more than grain or a vignette can.
    val spatial = setOf(AdjustField.Grain, AdjustField.Vignette, AdjustField.Sharpen, AdjustField.Smooth)
    // A shadow, a skin midtone and a highlight: Shadows reaches only the first
    // and Highlights only the last, so each slider is asked about all three.
    val probes = listOf(px(0x30, 0x28, 0x24), skin, px(0xE6, 0xDC, 0xC8))
    for (field in AdjustField.entries) {
        val grade = Looks.grade(null, 1f, field.set(Adjust(), 0.6f))
        check(!grade.isIdentity, "${field.label} at 0.6 is an identity grade")
        val changed = probes.any { grade.applyTo(it) != it }
        if (field in spatial) check(!changed, "${field.label} changed a lone pixel, which it cannot on the GPU")
        else check(changed, "${field.label} at 0.6 left every probe untouched")
    }

    // --- Brightness is an offset: black comes up, which a gain never managed. ---
    run {
        val up = Looks.grade(null, 1f, Adjust(brightness = 1f)).applyTo(px(0, 0, 0))
        check(rgb(up).first > 60, "brightness +1 left black black: ${rgb(up)}")
        val down = Looks.grade(null, 1f, Adjust(brightness = -1f)).applyTo(px(255, 255, 255))
        check(rgb(down).first < 200, "brightness -1 left white white: ${rgb(down)}")
        val g = Looks.grade(null, 1f, Adjust(brightness = 1f))
        check(near(g.redScale, 1f) && near(g.brightness, Looks.BRIGHTNESS_REACH), "brightness folded into the gain: $g")
    }

    // --- Exposure, temperature and tint fold into the channel gains. --------------
    run {
        val e = Looks.grade(null, 1f, Adjust(exposure = 1f))
        val stops = Math.pow(2.0, Adjust.EXPOSURE_STOPS.toDouble()).toFloat()
        check(near(e.redScale, stops) && near(e.greenScale, stops) && near(e.blueScale, stops), "exposure +1 is not ${stops}x: $e")
        val warm = Looks.grade(null, 1f, Adjust(temperature = 1f))
        check(warm.redScale > 1f && warm.blueScale < 1f && near(warm.greenScale, 1f), "temperature +1 is not warm: $warm")
        val cool = Looks.grade(null, 1f, Adjust(temperature = -1f))
        check(cool.redScale < 1f && cool.blueScale > 1f, "temperature -1 is not cool: $cool")
        val magenta = Looks.grade(null, 1f, Adjust(tint = 1f))
        check(magenta.greenScale < 1f && magenta.redScale > 1f && magenta.blueScale > 1f, "tint +1 is not magenta: $magenta")
        // A look's own gains multiply with them rather than being replaced.
        val warmLook = Looks.grade("warm", 1f, Adjust(exposure = 1f))
        check(near(warmLook.redScale, 1.07f * stops, 1e-2f), "a look's gain did not fold with exposure: $warmLook")
    }

    // --- The look and the sliders fold: contrast adds, fade adds and stops at one. ---
    run {
        val g = Looks.grade("vivid", 1f, Adjust(contrast = 0.1f, saturation = -0.1f))
        check(near(g.contrast, 0.24f) && near(g.saturation, 0.1f), "vivid plus sliders did not add: $g")
        val faded = Looks.grade("faded", 1f, Adjust(fade = 1f))
        check(near(faded.fade, 1f), "fade ran past one: ${faded.fade}")
        val half = Looks.grade("vivid", 0.5f, Adjust(contrast = 0.1f))
        check(near(half.contrast, 0.17f), "the look at half strength did not fold at half: $half")
        val grainy = Looks.grade("super8", 1f, Adjust(grain = 1f))
        check(near(grainy.grain, 1f) && near(grainy.vignette, 0.34f), "grain and vignette did not fold: $grainy")
    }

    // --- Highlights lift the bright end, shadows the dark end, each leaving the other alone. ---
    run {
        val bright = px(230, 230, 230)
        val dark = px(30, 30, 30)
        val hi = Looks.grade(null, 1f, Adjust(highlights = -1f))
        check(rgb(hi.applyTo(bright)).first < 230, "highlights -1 left a bright pixel alone")
        check(rgb(hi.applyTo(dark)).first == 30, "highlights -1 touched a dark pixel: ${rgb(hi.applyTo(dark))}")
        val sh = Looks.grade(null, 1f, Adjust(shadows = 1f))
        check(rgb(sh.applyTo(dark)).first > 30, "shadows +1 left a dark pixel alone")
        check(rgb(sh.applyTo(bright)).first == 230, "shadows +1 touched a bright pixel: ${rgb(sh.applyTo(bright))}")
    }

    // --- Hue: a turn of the wheel, whose two ends meet. -----------------------------
    run {
        val red = px(200, 40, 40)
        val quarter = Looks.grade(null, 1f, Adjust(hue = 0.5f)).applyTo(red)
        val (r, g, b) = rgb(quarter)
        val h = Grade.rgbToHsv(r / 255f, g / 255f, b / 255f)[0]
        check(near(h, 90f, 2f), "hue +0.5 turned red to $h degrees, not 90")
        val left = Looks.grade(null, 1f, Adjust(hue = -1f)).applyTo(red)
        val right = Looks.grade(null, 1f, Adjust(hue = 1f)).applyTo(red)
        val (lr, lg, lb) = rgb(left)
        val (rr, rg, rb) = rgb(right)
        check(abs(lr - rr) <= 1 && abs(lg - rg) <= 1 && abs(lb - rb) <= 1, "the two ends of the hue slider differ: $lr,$lg,$lb vs $rr,$rg,$rb")
        check(Looks.grade(null, 1f, Adjust(hue = 0.7f)).applyTo(grey) == grey, "a hue turn touched grey")
        // HSV round-trips.
        for ((r0, g0, b0) in listOf(Triple(0.2f, 0.45f, 0.7f), Triple(0.9f, 0.1f, 0.3f), Triple(0.5f, 0.5f, 0.5f), Triple(0f, 0f, 0f))) {
            val hsv = Grade.rgbToHsv(r0, g0, b0)
            val back = Grade.hsvToRgb(hsv[0], hsv[1], hsv[2])
            check(near(back[0], r0) && near(back[1], g0) && near(back[2], b0), "HSV did not round-trip ($r0,$g0,$b0) -> ${back.toList()}")
        }
    }

    // --- HSL bands: a band reaches its own colours and shades into its neighbours. ---
    run {
        check(near(HslBand.weight(0f, 0f), 1f), "a pixel on the band is not wholly the band's")
        check(near(HslBand.weight(45f, 0f), 0f) && near(HslBand.weight(60f, 0f), 0f), "a band reaches past a band and a half")
        check(near(HslBand.weight(30f, 0f), 1f / 3f), "a neighbouring band's share is not a third: ${HslBand.weight(30f, 0f)}")
        check(near(HslBand.weight(350f, 0f), 1f - 10f / 45f), "the wheel does not wrap at red: ${HslBand.weight(350f, 0f)}")
        val redOff = Adjust().withBand(HueBand.Red, HslBand(saturation = -1f))
        check(!redOff.isIdentity && redOff.band(HueBand.Blue).isIdentity, "withBand touched another band")
        val g = Looks.grade(null, 1f, redOff)
        val red = px(200, 30, 30)
        val (r, gg, b) = rgb(g.applyTo(red))
        check(abs(r - gg) <= 2 && abs(gg - b) <= 2, "red desaturated is not grey: $r,$gg,$b")
        val blue = px(30, 30, 200)
        check(g.applyTo(blue) == blue, "the red band touched blue: ${rgb(g.applyTo(blue))}")
        check(g.applyTo(grey) == grey, "the red band touched grey")
        val brighter = Looks.grade(null, 1f, Adjust().withBand(HueBand.Blue, HslBand(luminance = 1f)))
        check(rgb(brighter.applyTo(blue)).third > 200, "blue's luminance up did not brighten blue")
        val turned = Looks.grade(null, 1f, Adjust().withBand(HueBand.Blue, HslBand(hue = 1f)))
        val (tr, tg, tb) = rgb(turned.applyTo(blue))
        val h = Grade.rgbToHsv(tr / 255f, tg / 255f, tb / 255f)[0]
        check(near(h, 240f + HslBand.HUE_SWING_DEGREES, 3f), "blue's hue +1 turned it to $h, not ${240f + HslBand.HUE_SWING_DEGREES}")
    }

    // --- A draft's old brightness gain comes back as the offset that keeps mid-grey where it was. ---
    run {
        for (gain in listOf(-0.6f, -0.2f, 0f, 0.3f, 0.5f)) {
            val offset = Adjust.brightnessFromLegacyGain(gain)
            val then = (0.5f * (1f + gain)).coerceIn(0f, 1f)
            val now = 0.5f + offset * Looks.BRIGHTNESS_REACH
            check(near(then, now, 1e-3f), "a legacy gain of $gain became $offset, which puts mid-grey at $now not $then")
        }
        check(near(Adjust.brightnessFromLegacyGain(0f), 0f), "no gain became an offset")
        check(Adjust.brightnessFromLegacyGain(1f) <= 1f && Adjust.brightnessFromLegacyGain(-1f) >= -1f, "a legacy gain ran off the slider")
    }

    // --- A whole picture graded: every pixel as applyTo has it, the vignette
    //     darkening the corners and not the middle, and the alpha kept. --------------
    run {
        val w = 8
        val h = 6
        val pixels = IntArray(w * h) { (0x80 shl 24) or 0xB8856A }
        Looks.grade("warm", 1f).applyTo(pixels, w, h)
        val one = Looks.grade("warm", 1f).applyTo(px(0xB8, 0x85, 0x6A))
        check(pixels.all { (it and 0xFFFFFF) == (one and 0xFFFFFF) }, "a picture graded differs from its pixels graded one by one")
        check(pixels.all { (it ushr 24) == 0x80 }, "grading lost the picture's alpha")
        val shaded = IntArray(w * h) { px(0xB8, 0x85, 0x6A) }
        Looks.grade(null, 1f, Adjust(vignette = 1f)).applyTo(shaded, w, h)
        val middle = rgb(shaded[(h / 2) * w + w / 2]).first
        val corner = rgb(shaded[0]).first
        check(middle == 0xB8, "the vignette darkened the middle: $middle")
        check(corner < middle, "the vignette left the corner as bright as the middle: $corner")
        val untouched = IntArray(4) { skin }
        Looks.grade(null, 1f).applyTo(untouched, 2, 2)
        check(untouched.all { it == skin }, "an identity grade changed a picture")
    }

    // --- The Curves tool, where it sits in the order. ----------------------------
    run {
        val none = com.squish.app.media.effects.ToneCurve.NONE
        check(Looks.grade(null, 1f, Adjust(curve = none)).isIdentity, "an identity curve is not an identity grade")
        check(Looks.grade(null, 1f, Adjust(curve = none)).applyTo(skin) == skin, "an identity curve changed a pixel")

        // A black point lifted lifts black and leaves white where it is.
        val lift = com.squish.app.media.effects.ToneCurve(
            master = com.squish.app.media.effects.Curve(
                listOf(com.squish.app.media.effects.CurvePoint(0f, 0.2f), com.squish.app.media.effects.CurvePoint(1f, 1f))
            )
        )
        val lifted = Looks.grade(null, 1f, Adjust(curve = lift))
        check(!lifted.isIdentity, "a curve with a lifted black read as identity")
        check(rgb(lifted.applyTo(px(0, 0, 0))).first in 48..54, "black was not lifted to about 0.2: ${rgb(lifted.applyTo(px(0, 0, 0)))}")
        check(rgb(lifted.applyTo(px(255, 255, 255))).first == 255, "white moved when only black was lifted")
        // A swatch can show it: a curve is a per-pixel move, not a spatial one.
        check(!lifted.needsShader, "a curve was counted as needing the shader")

        // One channel alone tints, which is what a per-channel curve is for.
        val warmer = Looks.grade(null, 1f, Adjust(curve = com.squish.app.media.effects.ToneCurve(
            red = com.squish.app.media.effects.Curve(
                listOf(com.squish.app.media.effects.CurvePoint(0f, 0f), com.squish.app.media.effects.CurvePoint(0.5f, 0.7f), com.squish.app.media.effects.CurvePoint(1f, 1f))
            )
        )))
        val (wr, wg, wb) = rgb(warmer.applyTo(grey))
        check(wr > 128 && wg == 128 && wb == 128, "a red curve did not move red alone: ${Triple(wr, wg, wb)}")

        // Before saturation, not after: with the curve crushing everything to
        // grey, a saturation lift afterwards has nothing left to lift.
        val flatten = com.squish.app.media.effects.ToneCurve(
            master = com.squish.app.media.effects.Curve(
                listOf(com.squish.app.media.effects.CurvePoint(0f, 0.5f), com.squish.app.media.effects.CurvePoint(1f, 0.5f))
            )
        )
        val (fr, fg, fb) = rgb(Looks.grade(null, 1f, Adjust(curve = flatten, saturation = 1f)).applyTo(skin))
        check(fr == fg && fg == fb, "the curve ran after saturation, so a flattened pixel came out coloured: ${Triple(fr, fg, fb)}")
    }

    // --- An imported LUT, last of the colour. ------------------------------------
    run {
        val identity = com.squish.app.media.effects.Lut3D.identity(17)
        check(Looks.grade(null, 1f).copy(lut = identity).applyTo(skin) == skin, "an identity LUT changed a pixel")
        check(!Looks.grade(null, 1f).copy(lut = identity).isIdentity, "a LUT was not counted as doing something")
        check(Looks.grade(null, 1f).copy(lut = identity, lutStrength = 0f).isIdentity, "a LUT at no strength still counted")

        // A cube that swaps red and blue, so the answer is known everywhere.
        val n = 9
        val swap = com.squish.app.media.effects.Lut3D(
            n,
            FloatArray(n * n * n * 3).also { d ->
                for (z in 0 until n) for (y in 0 until n) for (x in 0 until n) {
                    val i = ((z * n + y) * n + x) * 3
                    d[i] = com.squish.app.media.effects.Lut3D.byte(z.toFloat() / (n - 1))
                    d[i + 1] = com.squish.app.media.effects.Lut3D.byte(y.toFloat() / (n - 1))
                    d[i + 2] = com.squish.app.media.effects.Lut3D.byte(x.toFloat() / (n - 1))
                }
            }
        )
        val full = Looks.grade(null, 1f).copy(lut = swap)
        val (r, g, b) = rgb(full.applyTo(px(255, 0, 0)))
        check(r < 6 && g < 6 && b > 249, "the swap LUT turned red into ${Triple(r, g, b)}")
        // Half strength is half way there, which is what the slider promises.
        val half = Looks.grade(null, 1f).copy(lut = swap, lutStrength = 0.5f)
        val (hr, _, hb) = rgb(half.applyTo(px(255, 0, 0)))
        check(hr in 120..136 && hb in 120..136, "the LUT at half strength gave ${Triple(hr, 0, hb)}")

        // After the sliders: a LUT that forces everything to black leaves
        // nothing for an earlier brightness lift to show.
        val black = com.squish.app.media.effects.Lut3D(2, FloatArray(2 * 2 * 2 * 3))
        val lifted = Looks.grade(null, 1f, Adjust(brightness = 1f)).copy(lut = black)
        check(rgb(lifted.applyTo(skin)) == Triple(0, 0, 0), "the LUT ran before the sliders: ${rgb(lifted.applyTo(skin))}")
    }

    // --- The CPU copy and the shader agree about their shared constants. ---------
    // applyTo is the CPU copy of squish_look_es2.glsl, and check_shaders.py
    // compares uniform names, not the numbers inside either file. A constant
    // changed on one side only would grade a photo overlay differently from the
    // video beside it - the one place in the app where the two paths show the
    // same picture - and nothing would say so.
    val assets = java.io.File("app/src/main/assets")
    val shader = java.io.File(assets, "squish_look_es2.glsl")
    check(shader.isFile, "squish_look_es2.glsl is not there - did it move?")

    // Every shader that weighs a pixel's brightness carries its own copy of the
    // Rec. 709 weights - the look shader and the effects shader as a named LUMA,
    // the chroma key's spill suppression spelled into a dot(). Rather than
    // listing the ones that happen to exist today, every vec3 of three weights
    // that sums to 1 with green the largest is taken to be a luma vector and
    // has to be the one the Kotlin uses.
    var lumaSites = 0
    /** The skin locus's own Rec. 601 weights, which are allowed and have to be there. */
    var skinSites = 0
    assets.listFiles { f -> f.extension == "glsl" }?.sortedBy { it.name }?.forEach { f ->
        Regex("vec3\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\)").findAll(f.readText()).forEach { m ->
            val w = (1..3).map { m.groupValues[it].toFloat() }
            val weights = near(w.sum(), 1f, 1e-3f) && w[1] > w[0] && w[1] > w[2]
            if (weights) {
                lumaSites++
                // One exception, and it is written down on both sides: the skin
                // locus Smooth skin uses is defined in Rec. 601 YCbCr, so the
                // shader's skinWeight carries those weights and SkinTone carries
                // the same three numbers. Everything else is Rec. 709.
                val is601 = near(w[0], SkinTone.LUMA_R) && near(w[1], SkinTone.LUMA_G) && near(w[2], SkinTone.LUMA_B)
                if (is601) skinSites++
                else check(near(w[0], Grade.LUMA_R) && near(w[1], Grade.LUMA_G) && near(w[2], Grade.LUMA_B),
                    "${f.name} weighs brightness as $w, the Kotlin as ${listOf(Grade.LUMA_R, Grade.LUMA_G, Grade.LUMA_B)}")
            }
        }
    }
    check(lumaSites >= 3, "only $lumaSites luma vectors found in the shaders - the pattern that finds them has stopped matching")
    check(skinSites == 1, "$skinSites Rec. 601 luma vectors in the shaders - the skin locus is the only thing allowed one")

    if (shader.isFile) {
        val text = shader.readText()
        check(Regex("const\\s+vec3\\s+LUMA\\s*=").containsMatchIn(text), "the look shader has no `const vec3 LUMA` any more")
        // The curve's table is read with a half-texel offset, and those two
        // numbers are the size of the table: get them wrong and every level is
        // read half a step along, which looks like a slightly wrong curve
        // rather than a broken one - the worst kind.
        val size = com.squish.app.media.effects.ToneCurve.LUT_SIZE
        val scale = Regex("const\\s+float\\s+LUT_SCALE\\s*=\\s*([\\d.]+)\\s*/\\s*([\\d.]+)").find(text)
        val offset = Regex("const\\s+float\\s+LUT_OFFSET\\s*=\\s*([\\d.]+)\\s*/\\s*([\\d.]+)").find(text)
        check(scale != null && offset != null, "the look shader has no LUT_SCALE/LUT_OFFSET any more")
        scale?.let {
            check(near(it.groupValues[1].toFloat(), (size - 1).toFloat()) && near(it.groupValues[2].toFloat(), size.toFloat()),
                "LUT_SCALE is ${it.groupValues[1]}/${it.groupValues[2]}, not ${size - 1}/$size")
        }
        offset?.let {
            check(near(it.groupValues[1].toFloat(), 0.5f) && near(it.groupValues[2].toFloat(), size.toFloat()),
                "LUT_OFFSET is ${it.groupValues[1]}/${it.groupValues[2]}, not 0.5/$size")
        }
        check(Regex("uniform\\s+sampler2D\\s+uCurve").containsMatchIn(text), "the look shader no longer samples a curve")
        check(Regex("uniform\\s+sampler2D\\s+uLut").containsMatchIn(text), "the look shader no longer samples a LUT")
        check(Regex("vec3\\s+lutLookup").containsMatchIn(text), "the look shader has no lutLookup")
        // The atlas is size*size across and size tall, and the lookup divides by
        // exactly that. A mismatch reads as a look that is subtly wrong at one
        // blue level, which is the hardest kind of fault to see on purpose.
        check(Regex("\\(n \\* n\\)").containsMatchIn(text), "the LUT lookup no longer divides by the atlas width")

        for ((name, kotlinValue) in listOf("HSL_LUMA_REACH" to Grade.HSL_LUMA_REACH, "TONE_REACH" to Grade.TONE_REACH)) {
            val m = Regex("const\\s+float\\s+$name\\s*=\\s*([-\\d.]+)").find(text)
            // TONE_REACH may be spelled into the shader's arithmetic rather than
            // named; only a named one is compared, and its absence is said here
            // rather than passing quietly.
            if (m == null) check(name == "TONE_REACH", "the shader has no `const float $name` any more")
            else check(near(m.groupValues[1].toFloat(), kotlinValue),
                "$name disagrees: the shader has ${m.groupValues[1]}, the Kotlin $kotlinValue")
        }
    }

    // --- The two places the CPU copy had stopped being the shader. ----------
    //
    // Both are about *when* the value is held to 0..1. The shader carries the
    // colour as an unbounded float all the way to its last line and clamps
    // twice on the way: once before the HSV conversion, once at the end. The
    // CPU copy clamped in neither of those places and in one the shader does
    // not.
    run {
        // 1. The hue wheel and the HSL bands read a clamped colour, as
        //    `rgb2hsv(clamp(c, 0.0, 1.0))` does. So once a channel is past
        //    white, how far past makes no difference. Unclamped it did: the
        //    value became the HSV `v`, every component came out scaled by it,
        //    and the middle channel of a rotated hue landed somewhere else.
        val turned = Grade(
            redScale = 2f, greenScale = 1f, blueScale = 1f,
            contrast = 0f, saturation = 0f, hueDegrees = 30f
        )
        val bright = turned.applyTo(0xFFFF6464.toInt())   // red 255, past white at 2x
        val brighter = turned.applyTo(0xFFC86464.toInt()) // red 200, also past white
        check(
            bright == brighter,
            "two reds that both clip at 2x graded differently through the hue wheel: " +
                "${rgb(bright)} and ${rgb(brighter)}"
        )
        // And the middle channel is the clamped answer, not the scaled one: a
        // 30 degree turn of a clipped red leaves green below white.
        val (_, greenOf, _) = rgb(bright)
        check(greenOf < 255, "a 30 degree hue turn of a clipped red took green to white: $greenOf")
        // The shader has to still be clamping there, or this is now the wrong
        // answer and nothing else would say so.
        if (shader.isFile) check(
            Regex("rgb2hsv\\s*\\(\\s*clamp\\s*\\(").containsMatchIn(shader.readText()),
            "the look shader no longer clamps before rgb2hsv, so the CPU copy's clamp is now the odd one out"
        )

        // 2. The vignette multiplies the float the shader is still carrying,
        //    before the clamp - not a byte. Applied after the clamp it took a
        //    bright area darker than the file does, because the clamp to 1.0
        //    had thrown away the headroom the falloff was about to bring back
        //    down. A picture lifted well past white with a vignette over it
        //    must come out white in the middle *and* at a radius where the
        //    falloff is small.
        val lifted = Grade(
            redScale = 1f, greenScale = 1f, blueScale = 1f,
            contrast = 0f, saturation = 0f, brightness = 0.8f, vignette = 0.6f
        )
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        lifted.applyTo(pixels, w, h)
        val middle = rgb(pixels[(h / 2) * w + w / 2])
        check(middle.first == 255, "white under a vignette is ${middle.first} in the middle of the frame")
        // Out where the falloff has begun but is still under the headroom the
        // brightness lift gave - 0.82 against a value of 1.8 - the picture is
        // white, as the shader's clamp-at-the-end makes it. Clamped first it
        // was not: 255 * 0.82 came out at 209, a grey band in a white wall.
        // (Nearer the middle the falloff is exactly 1 and the two agree, which
        // is why the fixture reaches this far out.)
        val edge = rgb(pixels[(h / 2) * w + 61])
        check(
            edge.first == 255,
            "a vignette ate into the headroom a brightness lift gave: ${edge.first} where the file has 255"
        )
        // And the corner is genuinely darkened, so the fixture is not simply
        // a vignette that does nothing.
        val corner = rgb(pixels[(h - 1) * w + (w - 1)])
        check(corner.first < 200, "the corner of a vignetted frame is ${corner.first} - the fixture is wrong")
        // One pixel of a picture and one colour through the chain land on the
        // same place when there is no vignette, which is what lets a swatch
        // stand for a frame.
        val plain = Grade(
            redScale = 1.07f, greenScale = 1f, blueScale = 0.95f,
            contrast = 0.1f, saturation = 0.06f, hueDegrees = 12f
        )
        val one = IntArray(4) { skin }
        plain.applyTo(one, 2, 2)
        check(
            one.all { it and 0xFFFFFF == plain.applyTo(skin) and 0xFFFFFF },
            "a picture and a swatch disagree with no vignette on: ${rgb(one[0])} and ${rgb(plain.applyTo(skin))}"
        )
    }

    // --- The swatch maths still holds for every catalogue look with the sliders on. ---
    for (look in Looks.catalog) {
        val g = Looks.grade(look.id, 1f, Adjust(exposure = 0.3f, highlights = 0.5f, hue = 0.2f))
        val (r, gg, b) = rgb(g.applyTo(skin))
        check(r in 0..255 && gg in 0..255 && b in 0..255, "${look.id} with sliders produced an out-of-range pixel")
    }

    println("grade: ${AdjustField.entries.size} sliders, ${HueBand.entries.size} bands, ${Looks.catalog.size} looks")
    if (problems.isEmpty()) println("PASS - every slider does its one thing, folds with the look, and the wheel meets itself")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
