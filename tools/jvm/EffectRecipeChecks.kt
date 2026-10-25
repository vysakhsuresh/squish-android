import com.squish.app.editor.EffectCategory
import com.squish.app.editor.EffectKind
import com.squish.app.editor.FxParams
import com.squish.app.editor.TimedEffect
import com.squish.app.editor.category
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/**
 * Every effect in the library, as the shader will be handed it: nothing outside
 * its stretch, something inside it at any knob setting, never a zoom below 1
 * (which would pull the frame's edge into view), never NaN, and every control
 * inside the range the shader treats as sane.
 *
 * EDGE_PLAN F2 took the library from 21 effects to 60 and more in categories,
 * with new stages in the shader (lens, light, tone, particles, more glitch):
 * their controls are held to their ranges here too, and each category has to
 * have something in it.
 */
fun main() {
    for (kind in EffectKind.entries) {
        for (amount in listOf(0f, 0.5f, 1f)) for (strength in listOf(0.3f, 1f)) {
            val e = TimedEffect("e", kind, 1_000, 5_000, strength, amount)
            check(FxParams.at(listOf(e), 500).isIdentity && FxParams.at(listOf(e), 5_000).isIdentity, "$kind acts outside its stretch")
            val inside = (1_100L until 4_900L step 20).map { FxParams.at(listOf(e), it) }
            check(inside.any { !it.isIdentity }, "$kind at amount $amount, strength $strength does nothing")
            for (p in inside) {
                val all = listOf(p.offsetX, p.offsetY, p.zoom, p.split, p.glitch, p.flash, p.mono, p.invert, p.scan, p.noise, p.blur, p.hue) +
                    p.lensA.toList() + p.lensB.toList() + p.light.toList() + p.tone.toList() + p.particles.toList() + p.glitch2.toList()
                check(all.all { it.isFinite() }, "$kind gives NaN: $p")
                check(p.zoom >= 1f, "$kind zooms out past the frame: ${p.zoom}")
                check(p.flash in 0f..1f && p.mono in 0f..1f && p.invert in 0f..1f, "$kind gives an out-of-range mix: $p")
                check(abs(p.offsetX) < 0.12f && abs(p.offsetY) < 0.12f, "$kind moves too far: ${p.offsetX}, ${p.offsetY}")
                check(p.split < 0.05f && p.blur < 0.05f && p.noise < 0.9f, "$kind is too strong: $p")
                // The new stages: mixes 0..1, the counted ones whole numbers in range.
                val (fisheye, kaleido, ripple, tiles) = p.lensA.toList()
                val (mirror, _, zoomBlur, pixel) = p.lensB.toList()
                check(fisheye in 0f..1f && ripple in 0f..1f && zoomBlur in 0f..1f, "$kind warps past its range: $p")
                check(kaleido == 0f || kaleido in 3f..12f, "$kind folds into ${kaleido} pieces")
                check(tiles == 0f || tiles in 2f..4f, "$kind tiles ${tiles} times")
                check(mirror in listOf(0f, 1f, 2f), "$kind mirrors as ${mirror}")
                check(pixel == 0f || pixel in 0.004f..0.06f, "$kind pixelates at ${pixel}")
                check(p.light.toList().all { it in 0f..1f }, "$kind lights past its range: ${p.light.toList()}")
                val (vignette, tint, tintKind, posterize) = p.tone.toList()
                check(vignette in 0f..1f && tint in 0f..1f && tintKind in 0f..3f && (posterize == 0f || posterize in 2f..12f), "$kind tones past its range: ${p.tone.toList()}")
                val (particle, density, amountP, rec) = p.particles.toList()
                check(particle in 0f..6f && density in 0f..1f && amountP in 0f..1f && rec in 0f..1f, "$kind particles past their range: ${p.particles.toList()}")
                check(p.glitch2.toList().all { it in 0f..1f }, "$kind glitches past its range: ${p.glitch2.toList()}")
            }
        }
    }
    // A knob does what its name says, in the direction it says it: more Levels
    // is more levels, more Pieces more pieces. Posterize's ran backwards when
    // first written - the knob called Levels took them away.
    fun at(kind: EffectKind, amount: Float) = FxParams.at(listOf(TimedEffect("e", kind, 0, 4_000, 1f, amount)), 2_000)
    check(at(EffectKind.Posterize, 1f).tone.w > at(EffectKind.Posterize, 0f).tone.w, "Posterize's Levels knob takes levels away")
    check(at(EffectKind.Kaleidoscope, 1f).lensA.y > at(EffectKind.Kaleidoscope, 0f).lensA.y, "Kaleidoscope's Pieces knob takes pieces away")
    check(at(EffectKind.Pixelate, 1f).lensB.w > at(EffectKind.Pixelate, 0f).lensB.w, "Pixelate's Size knob makes the pixels smaller")
    check(at(EffectKind.EightBit, 1f).lensB.w > at(EffectKind.EightBit, 0f).lensB.w, "8-bit's Pixels knob makes the pixels smaller")
    check(at(EffectKind.Fisheye, 1f).lensA.x > at(EffectKind.Fisheye, 0f).lensA.x, "Fisheye's Bulge knob flattens it")
    check(at(EffectKind.Vignette, 1f).tone.x > at(EffectKind.Vignette, 0f).tone.x, "Vignette's Darkness knob lightens it")
    check(at(EffectKind.Snow, 1f).particles.y > at(EffectKind.Snow, 0f).particles.y, "Snow's Density knob thins it")

    // Sixty and more, in every category, each name its own.
    check(EffectKind.entries.size >= 60, "only ${EffectKind.entries.size} effects")
    for (c in EffectCategory.entries) check(EffectKind.entries.count { it.category == c } >= 4, "${c.label} has ${EffectKind.entries.count { it.category == c }} effects")
    check(EffectKind.entries.map { it.label }.toSet().size == EffectKind.entries.size, "two effects share a name")
    if (problems.isEmpty()) println("EffectRecipeChecks: all checks passed (${EffectKind.entries.size} effects in ${EffectCategory.entries.size} categories)")
    else { problems.distinct().take(30).forEach { println("FAIL: $it") }; exitProcess(1) }
}
