import com.squish.app.media.effects.Look
import com.squish.app.media.effects.LookPreview
import com.squish.app.media.effects.Looks
import kotlin.math.abs

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

private const val W = 24
private const val H = 16

/** A picture with shadows, midtones, highlights and colour in it. */
private fun testFrame(): IntArray {
    val px = IntArray(W * H)
    var i = 0
    for (y in 0 until H) {
        for (x in 0 until W) {
            val r = (x * 255 / (W - 1))
            val g = (y * 255 / (H - 1))
            val b = ((x + y) * 255 / (W + H - 2))
            px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return px
}

private fun rgb(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

private fun graded(look: Look): IntArray {
    val px = testFrame()
    LookPreview.apply(px, W, H, look)
    return px
}

/** The shader's own maths, transcribed independently, for one pixel. */
private fun shaderReference(r0: Float, g0: Float, b0: Float, look: Look): Triple<Float, Float, Float> {
    var r = r0 * look.redScale
    var g = g0 * look.greenScale
    var b = b0 * look.blueScale

    val f = (1f + look.contrast) / (1.0001f - look.contrast)
    r = f * (r - 0.5f) + 0.5f
    g = f * (g - 0.5f) + 0.5f
    b = f * (b - 0.5f) + 0.5f

    val lum = r * 0.2126f + g * 0.7152f + b * 0.0722f
    val sat = maxOf(0f, 1f + look.saturation)
    r = lum + (r - lum) * sat
    g = lum + (g - lum) * sat
    b = lum + (b - lum) * sat

    if (look.split > 0.001f) {
        val l = (r * 0.2126f + g * 0.7152f + b * 0.0722f).coerceIn(0f, 1f)
        fun channel(shadow: Int, high: Int): Float {
            val s = shadow / 255f
            val h = high / 255f
            return (s + (h - s) * l - 0.5f) * look.split * 0.55f
        }
        r += channel((look.shadowTint shr 16) and 0xFF, (look.highlightTint shr 16) and 0xFF)
        g += channel((look.shadowTint shr 8) and 0xFF, (look.highlightTint shr 8) and 0xFF)
        b += channel(look.shadowTint and 0xFF, look.highlightTint and 0xFF)
    }

    r = r * (1f - look.fade * 0.55f) + look.fade * 0.16f
    g = g * (1f - look.fade * 0.55f) + look.fade * 0.16f
    b = b * (1f - look.fade * 0.55f) + look.fade * 0.16f

    return Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

fun main() {
    val source = testFrame()

    // An untouched look leaves the picture untouched. If this fails, "None" would
    // render differently from the actual preview and the whole row would be suspect.
    check("the empty look changes nothing", !LookPreview.differs(graded(Looks.None), source, threshold = 1))

    // Dialled to zero, every look is the empty look. This is the property the
    // strength slider rests on.
    for (look in Looks.catalog) {
        check(
            "${look.label} at zero strength changes nothing",
            !LookPreview.differs(graded(look.atIntensity(0f)), source, threshold = 1)
        )
    }

    // Every look in the catalogue actually does something, and does something
    // different from every other look. A preview row where two thumbnails match
    // is a row that cannot be chosen from.
    val byLook = Looks.catalog.filter { it.id != Looks.None.id }.associateWith { graded(it) }
    for ((look, pixels) in byLook) {
        check("${look.label} visibly changes the picture", LookPreview.differs(pixels, source))
    }
    val names = byLook.keys.toList()
    for (i in names.indices) {
        for (j in i + 1 until names.size) {
            check(
                "${names[i].label} and ${names[j].label} are told apart",
                LookPreview.differs(byLook[names[i]]!!, byLook[names[j]]!!)
            )
        }
    }

    // The pipeline matches the shader, pixel for pixel, for every look that does
    // not use the position-dependent moves. This is the check that matters: a
    // preview that disagrees with the render is worse than no preview.
    val probes = listOf(
        Triple(0.05f, 0.05f, 0.05f),
        Triple(0.2f, 0.45f, 0.7f),
        Triple(0.5f, 0.5f, 0.5f),
        Triple(0.9f, 0.75f, 0.4f),
        Triple(1f, 1f, 1f)
    )
    for (look in Looks.catalog) {
        if (look.bloom > 0.001f || look.vignette > 0.001f || look.grain > 0.001f) continue
        for ((r0, g0, b0) in probes) {
            val one = IntArray(1) {
                (0xFF shl 24) or
                    ((r0 * 255).toInt() shl 16) or ((g0 * 255).toInt() shl 8) or (b0 * 255).toInt()
            }
            LookPreview.apply(one, 1, 1, look)
            val (ar, ag, ab) = rgb(one[0])
            val (er, eg, eb) = shaderReference((r0 * 255).toInt() / 255f, (g0 * 255).toInt() / 255f, (b0 * 255).toInt() / 255f, look)
            val tolerance = 1
            check(
                "${look.label} matches the shader at ($r0,$g0,$b0): got $ar,$ag,$ab want ${(er * 255 + 0.5f).toInt()},${(eg * 255 + 0.5f).toInt()},${(eb * 255 + 0.5f).toInt()}",
                abs(ar - (er * 255 + 0.5f).toInt()) <= tolerance &&
                    abs(ag - (eg * 255 + 0.5f).toInt()) <= tolerance &&
                    abs(ab - (eb * 255 + 0.5f).toInt()) <= tolerance
            )
        }
    }

    // Nothing ever leaves the range a pixel can hold, whatever the look.
    for (look in Looks.catalog) {
        for (p in graded(look)) {
            val (r, g, b) = rgb(p)
            if (r !in 0..255 || g !in 0..255 || b !in 0..255) {
                failures.add("${look.label} produced an out-of-range pixel")
                break
            }
        }
        check("${look.label} keeps the alpha", graded(look).all { (it ushr 24) == 0xFF })
    }

    // Saturation at its floor is greyscale, which is the one grade with an answer
    // that can be stated exactly.
    val mono = Look(id = "mono", label = "Mono", family = Looks.None.family, saturation = -1f)
    for (p in graded(mono)) {
        val (r, g, b) = rgb(p)
        if (abs(r - g) > 1 || abs(g - b) > 1) {
            failures.add("full desaturation left colour behind ($r,$g,$b)")
            break
        }
    }

    // A vignette darkens the corners and leaves the middle alone.
    val vignette = Look(id = "v", label = "V", family = Looks.None.family, vignette = 0.8f)
    val flat = IntArray(W * H) { (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200 }
    LookPreview.apply(flat, W, H, vignette)
    val centre = rgb(flat[(H / 2) * W + W / 2]).first
    val corner = rgb(flat[0]).first
    check("a vignette darkens the corner", corner < centre - 10)
    check("a vignette spares the middle", centre >= 190)

    // A one-pixel picture, an empty array, a zero size: none of them may throw.
    LookPreview.apply(IntArray(0), 0, 0, Looks.catalog.last())
    LookPreview.apply(IntArray(1), 1, 1, Looks.catalog.last())
    LookPreview.apply(IntArray(4), 2, 2, Looks.catalog.last())

    if (failures.isEmpty()) {
        println("PASS - every look previews on a real picture, differs from every other, and matches the shader")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
