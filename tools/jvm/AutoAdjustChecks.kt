import com.squish.app.media.effects.AutoAdjust
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun argb(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()

fun main() {
    // A dark, blue-cast, flat frame: a gradient from 0.08 to 0.35, blue lifted.
    val rnd = java.util.Random(5)
    val dull = IntArray(4_000) { i ->
        val t = 0.08f + 0.27f * (i % 100) / 99f
        val hue = rnd.nextFloat() * 0.06f
        argb(t * 0.85f + hue, t, t * 1.25f)
    }
    val a = AutoAdjust.of(dull)
    val after = AutoAdjust.stats(dull, a)
    check(abs(after.luma - AutoAdjust.TARGET_LUMA) < 0.04f, "the frame was not brought to mid-grey: ${after.luma} with $a")
    check(abs(after.r - after.b) < 0.04f, "the blue cast was not taken out: r ${after.r} b ${after.b} with $a")
    check(after.spread > AutoAdjust.stats(dull, com.squish.app.media.effects.Adjust.NONE).spread, "the flat frame was not given more range")

    // A well-exposed neutral frame is left close to alone.
    val good = IntArray(4_000) { i -> val t = 0.05f + 0.9f * (i % 100) / 99f; argb(t, t, t) }
    val g = AutoAdjust.of(good)
    check(abs(g.temperature) < 0.08f && abs(g.tint) < 0.08f && abs(g.exposure) < 0.15f, "a good frame was changed a lot: $g")

    // Every value stays on its slider.
    listOf(a, g).forEach { adj ->
        check(adj.exposure in -1f..1f && adj.temperature in -1f..1f && adj.tint in -1f..1f &&
            adj.contrast in -1f..1f && adj.saturation in -1f..1f, "a value left its slider: $adj")
    }
    // The sliders Auto does not own are kept.
    val kept = AutoAdjust.of(dull, com.squish.app.media.effects.Adjust(sharpen = 0.4f, vignette = 0.3f))
    check(kept.sharpen == 0.4f && kept.vignette == 0.3f, "Auto threw away sharpen or vignette")

    if (problems.isEmpty()) println("AutoAdjustChecks: all checks passed (${"%.2f".format(after.luma)} luma)") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
