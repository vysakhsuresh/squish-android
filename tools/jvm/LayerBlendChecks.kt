import com.squish.app.media.ExportPlan
import com.squish.app.timeline.LayerBlend
import com.squish.app.timeline.Transform
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

// The separable blend functions of the W3C compositing spec, executed - and the
// shader checked for a branch per mode, since a forgotten one would silently
// fall through to Normal in the file while the preview blended properly.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-4f) = abs(a - b) <= slack

private const val SHADER = "app/src/main/assets/squish_layer_blend_es2.glsl"

fun main() {
    // --- Normal is the layer, whatever is under it. -------------------------
    for (b in listOf(0f, 0.3f, 1f)) for (s in listOf(0f, 0.4f, 1f)) {
        check(near(LayerBlend.Normal.blend(b, s), s), "Normal at ($b, $s) gave ${LayerBlend.Normal.blend(b, s)}")
    }

    // --- Every mode stays in range, everywhere. -----------------------------
    LayerBlend.entries.forEach { mode ->
        for (i in 0..32) for (j in 0..32) {
            val v = mode.blend(i / 32f, j / 32f)
            check(v in 0f..1f, "${mode.label} left 0..1 at (${i / 32f}, ${j / 32f}): $v")
        }
    }

    // --- The values the spec names. -----------------------------------------
    check(near(LayerBlend.Multiply.blend(0.5f, 0.5f), 0.25f), "Multiply of two halves is not a quarter")
    check(near(LayerBlend.Screen.blend(0.5f, 0.5f), 0.75f), "Screen of two halves is not three quarters")
    check(near(LayerBlend.Darken.blend(0.3f, 0.7f), 0.3f), "Darken did not take the darker")
    check(near(LayerBlend.Lighten.blend(0.3f, 0.7f), 0.7f), "Lighten did not take the lighter")
    check(near(LayerBlend.Difference.blend(0.3f, 0.7f), 0.4f), "Difference is not the distance")
    check(near(LayerBlend.Add.blend(0.7f, 0.7f), 1f), "Add did not clamp at white")
    check(near(LayerBlend.Add.blend(0.2f, 0.3f), 0.5f), "Add is not a sum below white")

    // --- The identities that give these modes their names. ------------------
    for (i in 0..32) {
        val b = i / 32f
        // Multiply by white leaves it; by black makes black.
        check(near(LayerBlend.Multiply.blend(b, 1f), b), "Multiply by white changed $b")
        check(near(LayerBlend.Multiply.blend(b, 0f), 0f), "Multiply by black is not black")
        // Screen by black leaves it; by white makes white.
        check(near(LayerBlend.Screen.blend(b, 0f), b), "Screen by black changed $b")
        check(near(LayerBlend.Screen.blend(b, 1f), 1f), "Screen by white is not white")
        // A mid-grey layer leaves the picture alone under Soft light and Overlay,
        // which is what makes them the two "contrast" modes rather than tints.
        check(near(LayerBlend.SoftLight.blend(b, 0.5f), b, 1e-3f), "Soft light with mid grey moved $b")
        check(near(LayerBlend.Overlay.blend(b, 0.5f), b, 1e-3f), "Overlay with mid grey moved $b")
        // Difference with black leaves it, with white inverts it.
        check(near(LayerBlend.Difference.blend(b, 0f), b), "Difference with black changed $b")
        check(near(LayerBlend.Difference.blend(b, 1f), 1f - b), "Difference with white did not invert $b")
    }

    // --- Overlay is Hard light with the two swapped, the whole way along. ---
    for (i in 0..32) for (j in 0..32) {
        val b = i / 32f
        val s = j / 32f
        check(near(LayerBlend.Overlay.blend(b, s), LayerBlend.HardLight.blend(s, b)),
            "Overlay and the swapped Hard light part at ($b, $s)")
    }

    // --- Each one really is a different mode. -------------------------------
    val probe = listOf(0.2f to 0.3f, 0.5f to 0.8f, 0.9f to 0.1f, 0.35f to 0.65f)
    val fingerprints = LayerBlend.entries.associateWith { m -> probe.map { (b, s) -> m.blend(b, s) } }
    LayerBlend.entries.forEach { a ->
        LayerBlend.entries.filter { it != a }.forEach { c ->
            check(fingerprints[a] != fingerprints[c], "${a.label} and ${c.label} do the same thing")
        }
    }

    // --- The shader has a branch for every one of them. ---------------------
    // A mode added here and forgotten there falls through to Normal in the file
    // while the preview blends it, which is the preview/file split this app does
    // not ship.
    val glsl = File(SHADER)
    check(glsl.isFile, "$SHADER is not there")
    if (glsl.isFile) {
        val text = glsl.readText()
        LayerBlend.entries.filter { it != LayerBlend.Normal }.forEach { mode ->
            check(text.contains("MODE_${mode.name.uppercase()}"),
                "the blend shader has no branch named MODE_${mode.name.uppercase()} for ${mode.label}")
        }
        // The ordinals are what the uniform carries, so the shader's constants
        // have to be the enum's own numbering.
        LayerBlend.entries.forEach { mode ->
            val m = Regex("const\\s+float\\s+MODE_${mode.name.uppercase()}\\s*=\\s*([\\d.]+)").find(text)
            if (mode != LayerBlend.Normal) {
                check(m != null && near(m.groupValues[1].toFloat(), mode.ordinal.toFloat()),
                    "${mode.label} is ${mode.ordinal} here and ${m?.groupValues?.get(1)} in the shader")
            }
        }
    }

    // --- Where the shader looks the layer up. -------------------------------
    // The inverse of the placement the overlay box on screen already agrees
    // with (OverlayChecks): a point put through the placement and back through
    // this comes out where it started. That round trip is what ties the blended
    // still in the file to the one the preview draws.
    run {
        val frames = listOf(1920f to 1080f, 1080f to 1920f, 1080f to 1080f)
        val aspects = listOf<Float?>(16f / 9f, 9f / 16f, 1f, 4f / 3f, null)
        val placements = listOf(
            Transform(), Transform(0.7f, -0.3f, 0.2f, 30f),
            Transform(1.4f, 0.1f, -0.6f, -135f), Transform(0.25f, 0.9f, 0.9f, 90f)
        )
        for ((w, h) in frames) for (a in aspects) for (t in placements) {
            val m = ExportPlan.placementMatrix(t, w / h)
            val inv = ExportPlan.layerLookup(t, a, w, h)
            val fw = if (a == null) 1f else if (a > w / h) 1f else (h * a) / w
            val fh = if (a == null) 1f else if (a > w / h) (w / a) / h else 1f
            // A grid of points of the layer, in its own 0..1 space.
            for (i in 0..4) for (j in 0..4) {
                val lu = i / 4f
                val lv = j / 4f
                // layer uv -> layer ndc -> frame ndc -> frame uv, the forward way.
                val lx = (lu * 2f - 1f) * fw
                val ly = (1f - lv * 2f) * fh
                val nx = m[0] * lx + m[1] * ly + m[4]
                val ny = m[2] * lx + m[3] * ly + m[5]
                val u = (nx + 1f) / 2f
                val v = (1f - ny) / 2f
                // and back through the lookup, read as GLSL reads a column-major mat3.
                val bu = inv[0] * u + inv[3] * v + inv[6]
                val bv = inv[1] * u + inv[4] * v + inv[7]
                check(near(bu, lu, 2e-3f) && near(bv, lv, 2e-3f),
                    "frame ${w}x$h aspect $a $t: ($lu, $lv) came back as ($bu, $bv)")
            }
            // A point well outside the layer reads as outside, which is what
            // leaves the picture alone there.
            val farU = inv[0] * 1.9f + inv[3] * 1.9f + inv[6]
            val farV = inv[1] * 1.9f + inv[4] * 1.9f + inv[7]
            check(farU !in 0f..1f || farV !in 0f..1f, "a point far outside the frame read as inside the layer")
        }
    }

    println("layer blend: ${LayerBlend.entries.size} modes")
    if (problems.isEmpty()) println("PASS - the spec's formulas, in range, each its own, and a branch for each in the shader")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
