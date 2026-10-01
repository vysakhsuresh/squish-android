import com.squish.app.editor.EffectKind
import com.squish.app.editor.FxParams
import com.squish.app.editor.TimedEffect
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/**
 * Every effect in the library, as the shader will be handed it: nothing outside
 * its stretch, something inside it at any knob setting, never a zoom below 1
 * (which would pull the frame's edge into view), never NaN, and every control
 * inside the range the shader treats as sane.
 */
fun main() {
    for (kind in EffectKind.entries) {
        for (amount in listOf(0f, 0.5f, 1f)) for (strength in listOf(0.3f, 1f)) {
            val e = TimedEffect("e", kind, 1_000, 5_000, strength, amount)
            check(FxParams.at(listOf(e), 500).isIdentity && FxParams.at(listOf(e), 5_000).isIdentity, "$kind acts outside its stretch")
            val inside = (1_100L until 4_900L step 20).map { FxParams.at(listOf(e), it) }
            check(inside.any { !it.isIdentity }, "$kind at amount $amount, strength $strength does nothing")
            for (p in inside) {
                val all = listOf(p.offsetX, p.offsetY, p.zoom, p.split, p.glitch, p.flash, p.mono, p.invert, p.scan, p.noise, p.blur, p.hue)
                check(all.all { it.isFinite() }, "$kind gives NaN: $p")
                check(p.zoom >= 1f, "$kind zooms out past the frame: ${p.zoom}")
                check(p.flash in 0f..1f && p.mono in 0f..1f && p.invert in 0f..1f, "$kind gives an out-of-range mix: $p")
                check(abs(p.offsetX) < 0.12f && abs(p.offsetY) < 0.12f, "$kind moves too far: ${p.offsetX}, ${p.offsetY}")
                check(p.split < 0.05f && p.blur < 0.05f && p.noise < 0.9f, "$kind is too strong: $p")
            }
        }
    }
    if (problems.isEmpty()) println("EffectRecipeChecks: all checks passed (${EffectKind.entries.size} effects)")
    else { problems.distinct().take(30).forEach { println("FAIL: $it") }; exitProcess(1) }
}
