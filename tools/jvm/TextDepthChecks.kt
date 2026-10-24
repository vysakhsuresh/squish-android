import com.squish.app.editor.TextFill
import com.squish.app.editor.TextDepth
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextStroke
import com.squish.app.editor.TextStyleSpec
import com.squish.app.editor.TitlePreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * EDGE_PLAN E3: text with depth - a gradient fill, a glow of its own colour
 * and strength, a second outline, a bubble's padding, a line bent into an
 * arc, a colour per word, and thirty and more title templates. The geometry
 * the renderer draws with is here, executed; the fields travel with the style
 * (Apply to all, saved styles) and the line.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, eps: Float = 0.5f) = abs(a - b) <= eps

fun main() {
    // A gradient across a 200x100 box: left to right at 0°, top to bottom at 90°,
    // through the middle, reaching the box's edges.
    val across = TextDepth.gradientEnds(200f, 100f, 0f)
    check(near(across[0], 0f) && near(across[2], 200f) && near(across[1], 50f) && near(across[3], 50f), "0° ran ${across.toList()}")
    val down = TextDepth.gradientEnds(200f, 100f, 90f)
    check(near(down[0], 100f) && near(down[1], 0f) && near(down[3], 100f), "90° ran ${down.toList()}")
    val diag = TextDepth.gradientEnds(200f, 100f, 45f)
    val mx = (diag[0] + diag[2]) / 2; val my = (diag[1] + diag[3]) / 2
    check(near(mx, 100f) && near(my, 50f), "45° is not centred: ${diag.toList()}")
    // The corners are on the two ends' perpendiculars: the first and last colours reach them.
    val dx = diag[2] - diag[0]; val dy = diag[3] - diag[1]
    fun along(x: Float, y: Float) = ((x - diag[0]) * dx + (y - diag[1]) * dy) / (dx * dx + dy * dy)
    check(near(along(0f, 0f), 0f, 0.01f) && near(along(200f, 100f), 1f, 0.01f), "45° does not reach the corners: ${along(0f, 0f)}, ${along(200f, 100f)}")

    // An arc: half a bend over a 300 px line spans 90°, so its radius is 300 / (π/2).
    val r = TextDepth.arcRadius(300f, 0.5f)
    check(near(r, (300 / (PI / 2)).toFloat(), 0.1f), "half a bend over 300 px has radius $r")
    check(TextDepth.arcRadius(300f, 0f).isInfinite(), "no bend has a finite radius")
    check(near(TextDepth.arcRadius(300f, -0.5f), r, 0.1f), "a smile and a frown of the same bend differ")
    // Its rise: r(1 - cos(θ/2)).
    val rise = TextDepth.arcRise(300f, 0.5f)
    check(near(rise, (r * (1 - cos(PI / 4))).toFloat(), 0.1f), "half a bend rises $rise")
    check(TextDepth.arcRise(300f, 0f) == 0f, "a straight line rises")
    // The chord it spans: 2r sin(θ/2), narrower than the line laid flat.
    val chord = TextDepth.arcChord(300f, 0.5f)
    check(near(chord, (2 * r * sin(PI / 4)).toFloat(), 0.1f) && chord < 300f, "the arc's chord is $chord")
    // A full bend is a half circle, and no more.
    check(near(TextDepth.arcRadius(300f, 1f), (300 / PI).toFloat(), 0.1f), "a full bend is not a half circle")
    check(near(TextDepth.arcRadius(300f, 3f), (300 / PI).toFloat(), 0.1f), "a bend past full was not held to it")

    // The new settings travel with the style, and a line keeps its word colours of its own.
    val spec = TextStyleSpec(
        fill = TextFill(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 30f),
        glowColorArgb = 0xFF00FF00.toInt(), glowStrength = 0.4f,
        outline2 = TextStroke(0xFFFFFFFF.toInt(), 0.1f), padding = 1.6f, arc = -0.3f
    )
    val line = TextOverlayItem(id = "l", text = "a b c", startMs = 0, endMs = 1_000, colorArgb = -1, wordColors = mapOf(1 to 0xFFFF00FF.toInt()))
    val styled = line.withStyle(spec)
    check(styled.style == spec, "the style did not travel whole: ${styled.style}")
    check(styled.wordColors == line.wordColors, "a style applied took the line's word colours")
    check(TextFill().isGradient.not() && spec.fill.isGradient, "a fill's gradient is misread")

    // Thirty and more title templates, each distinct, and some using what is new.
    val titles = TitlePreset.entries
    check(titles.size >= 30, "only ${titles.size} title templates")
    check(titles.map { it.label }.toSet().size == titles.size, "two title templates share a name")
    check(titles.count { it.style.fill.isGradient || it.style.arc != 0f || it.style.outline2.isOn } >= 6, "too few templates use the new looks")

    if (problems.isEmpty()) println("TextDepthChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
