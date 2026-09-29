import com.squish.app.media.effects.Adjust
import com.squish.app.media.effects.AdjustField
import com.squish.app.media.effects.Grade
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
import com.squish.app.media.effects.Looks
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
    val spatial = setOf(AdjustField.Grain, AdjustField.Vignette, AdjustField.Sharpen)
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
