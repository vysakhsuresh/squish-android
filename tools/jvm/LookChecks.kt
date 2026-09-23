import com.squish.app.media.effects.Look
import com.squish.app.media.effects.Looks
import kotlin.math.abs
import kotlin.system.exitProcess

fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
fun lum(c: Int): Float {
    val r = ((c shr 16) and 0xFF) / 255f
    val g = ((c shr 8) and 0xFF) / 255f
    val b = (c and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
fun dist(a: IntArray, b: IntArray): Int =
    a.indices.sumOf { i ->
        val ca = a[i]; val cb = b[i]
        abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)) +
        abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
        abs((ca and 0xFF) - (cb and 0xFF))
    }

fun main() {
    val problems = mutableListOf<String>()
    val looks = Looks.catalog
    println("catalog: ${looks.size} looks across ${looks.map { it.family }.distinct().size} families")

    // 1. Ids unique.
    val dupes = looks.groupBy { it.id }.filter { it.value.size > 1 }.keys
    if (dupes.isNotEmpty()) problems += "duplicate ids: $dupes"

    // 2. Intensity 0 must be the untouched picture, or the slider lies at one end.
    for (look in looks) {
        val off = look.atIntensity(0f)
        if (!off.isIdentity) problems += "${look.id}: intensity 0 is not identity ($off)"
    }

    // 3. No look may crush every reference to the same value - that is a swatch
    //    with no information in it.
    val swatches = looks.associate { it.id to Looks.swatch(it) }
    for ((id, sw) in swatches) {
        if (id == "none") continue
        val flat = sw.distinct().size == 1
        if (flat) problems += "$id: every reference lands on the same colour ${hex(sw[0])}"
        val allWhite = sw.all { lum(it) > 0.97f }
        val allBlack = sw.all { lum(it) < 0.03f }
        if (allWhite) problems += "$id: swatch is blown out to white"
        if (allBlack) problems += "$id: swatch is crushed to black"
    }

    // 4. Adjacent looks in the row must be distinguishable. 18 is roughly the
    //    point where two chips stop looking like the same chip at a glance.
    val ids = looks.map { it.id }
    for (i in 1 until ids.size) {
        for (j in i + 1 until ids.size) {
            val d = dist(swatches[ids[i]]!!, swatches[ids[j]]!!)
            if (d < 18) problems += "${ids[i]} and ${ids[j]} are indistinguishable (distance $d)"
        }
    }

    // 5. Shader-only looks must actually report needing the shader, and the
    //    built-in ones must not - that routing decides whether the film moves
    //    reach the GPU at all.
    for (look in looks) {
        val filmMoves = look.fade > 0f || look.split > 0f || look.bloom > 0f ||
            look.vignette > 0f || look.grain > 0f
        if (filmMoves != look.needsShader) {
            problems += "${look.id}: film moves=$filmMoves but needsShader=${look.needsShader}"
        }
        val grade = Looks.grade(look.id, 1f, 0f, 0f, 0f)
        if (grade.needsShader != filmMoves) {
            problems += "${look.id}: grade routing disagrees with the look"
        }
    }

    // 6. Half intensity must sit between off and full, for every parameter.
    for (look in looks) {
        val half = look.atIntensity(0.5f)
        fun between(name: String, a: Float, m: Float, b: Float) {
            val lo = minOf(a, b); val hi = maxOf(a, b)
            if (m < lo - 1e-4f || m > hi + 1e-4f) problems += "${look.id}: $name $m outside [$lo,$hi]"
        }
        between("fade", 0f, half.fade, look.fade)
        between("grain", 0f, half.grain, look.grain)
        between("vignette", 0f, half.vignette, look.vignette)
        between("contrast", 0f, half.contrast, look.contrast)
        between("redScale", 1f, half.redScale, look.redScale)
    }

    // 7. Parameter sanity: nothing outside the range the shader assumes.
    for (look in looks) {
        if (look.fade !in 0f..1f) problems += "${look.id}: fade ${look.fade} out of 0..1"
        if (look.grain !in 0f..1f) problems += "${look.id}: grain ${look.grain} out of 0..1"
        if (look.vignette !in 0f..1f) problems += "${look.id}: vignette ${look.vignette} out of 0..1"
        if (look.split !in 0f..1f) problems += "${look.id}: split ${look.split} out of 0..1"
        if (look.bloom !in 0f..1f) problems += "${look.id}: bloom ${look.bloom} out of 0..1"
        if (look.contrast !in -1f..1f) problems += "${look.id}: contrast out of range"
        if (look.saturation !in -1f..1f) problems += "${look.id}: saturation out of range"
    }

    println()
    println("%-14s %-12s %-9s %s".format("id", "family", "shader", "swatch"))
    for (look in looks) {
        val sw = swatches[look.id]!!
        println("%-14s %-12s %-9s %s".format(
            look.id, look.family.name, if (look.needsShader) "shader" else "built-in",
            sw.joinToString(" ") { hex(it) }
        ))
    }

    println()
    if (problems.isEmpty()) {
        println("PASS — all ${looks.size} looks are distinguishable, dial correctly, and route correctly")
    } else {
        println("FAIL (${problems.size})")
        problems.take(30).forEach { println("  - $it") }
        exitProcess(1)
    }
}
