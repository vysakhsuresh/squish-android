import com.squish.app.timeline.ChromaKey
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

const val SHADER = "app/src/main/assets/squish_chroma_key_es2.glsl"

fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** A colour's point in the UV plane, by the Kotlin the app uses for the key. */
fun uvOf(colour: Int): FloatArray = ChromaKey(keyColorArgb = colour).keyUV()

fun distance(a: FloatArray, b: FloatArray): Float =
    sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2))

/** The shader's own mask for a colour, as squish_chroma_key_es2.glsl computes it. */
fun mask(key: ChromaKey, colour: Int): Float {
    val base = distance(uvOf(colour), key.keyUV()) - key.similarity
    return (base / key.safeSmoothness).coerceIn(0f, 1f).toDouble().pow(1.5).toFloat()
}

fun main() {
    // ---- The shader and the Kotlin agree about rgbToUV ---------------------
    // check_shaders.py compares uniform names and arity, not the numbers inside
    // the shader, so a coefficient edited on one side only would pass it - and
    // the two would key differently in the preview and in the file, which looks
    // like a tuning problem for as long as anyone is willing to believe it.
    val glsl = File(SHADER)
    check(glsl.isFile, "$SHADER is not there - did it move?")
    if (glsl.isFile) {
        val body = glsl.readText().substringAfter("vec2 rgbToUV").substringBefore("}")
        val numbers = Regex("-?\\d+\\.\\d+").findAll(body).map { it.value.toFloat() }.toList()
        check(numbers.size == 8, "rgbToUV in the shader read ${numbers.size} numbers, not 8 - the checker needs rewriting for its new shape")
        if (numbers.size == 8) {
            // Derived from the Kotlin rather than written out again here: the
            // basis colours give each coefficient, and black gives the offset.
            val black = uvOf(argb(0, 0, 0))
            val red = uvOf(argb(255, 0, 0))
            val green = uvOf(argb(0, 255, 0))
            val blue = uvOf(argb(0, 0, 255))
            val kotlin = floatArrayOf(
                red[0] - black[0], green[0] - black[0], blue[0] - black[0], black[0],
                red[1] - black[1], green[1] - black[1], blue[1] - black[1], black[1]
            )
            kotlin.forEachIndexed { i, k ->
                check(abs(k - numbers[i]) < 1e-4f, "rgbToUV disagrees at ${i}: the shader has ${numbers[i]}, the Kotlin ${"%.4f".format(k)}")
            }
        }
    }

    // ---- Why luma is thrown away ------------------------------------------
    // Every neutral - a white shirt, a grey wall, black hair - is one point in
    // UV. That is the premise of keying in chroma at all, and it is what lets a
    // single similarity hold across an unevenly lit screen.
    val neutralPoint = uvOf(argb(0, 0, 0))
    for (v in listOf(0, 32, 64, 128, 200, 255)) {
        val d = distance(uvOf(argb(v, v, v)), neutralPoint)
        check(d < 1e-4f, "grey $v is $d from black in UV - the neutral axis is not a point")
    }
    check(abs(neutralPoint[0] - 0.5f) < 1e-4f && abs(neutralPoint[1] - 0.5f) < 1e-4f, "the neutral axis is not at (0.5, 0.5)")

    // ---- The numbers the defaults were tuned against -----------------------
    // ChromaKey's own comment states these; nothing executed them until now.
    val greenKey = ChromaKey()
    val neutralDistance = distance(neutralPoint, greenKey.keyUV())
    check(abs(neutralDistance - 0.33f) < 0.02f, "a neutral is $neutralDistance from digital green, not about 0.33")
    // The blue key is for a subject wearing green; it needs the same room.
    val blueKey = ChromaKey(keyColorArgb = ChromaKey.STANDARD_BLUE)
    check(abs(distance(neutralPoint, blueKey.keyUV()) - 0.33f) < 0.02f, "a neutral is the wrong distance from digital blue")

    // A white shirt survives the whole feather band, not just the hard edge.
    for (key in listOf(greenKey, blueKey)) {
        check(mask(key, argb(255, 255, 255)) == 1f, "a white shirt is touched by the default key")
        check(mask(key, argb(20, 20, 20)) == 1f, "black hair is touched by the default key")
        check(mask(key, key.keyColorArgb) == 0f, "the key colour itself is not cut")
    }

    // The screen in shadow. Dimming moves a colour toward neutral in a straight
    // line, so the distance from the key is (1 - brightness) x the key's own
    // distance from neutral: the comment's "still within 0.20 in deep shadow"
    // holds down to about 40% brightness, and the default cuts it to about 27%.
    for (lit in listOf(1.0f, 0.8f, 0.6f, 0.4f)) {
        val r = ((0x00 * lit).toInt()); val g = ((0xB1 * lit).toInt()); val b = ((0x40 * lit).toInt())
        val d = distance(uvOf(argb(r, g, b)), greenKey.keyUV())
        check(d <= 0.20f, "a screen at ${(lit * 100).toInt()}% light is $d from the key, past the 0.20 the default was tuned for")
        check(mask(greenKey, argb(r, g, b)) == 0f, "a screen at ${(lit * 100).toInt()}% light is not cut")
    }
    // Darker than that it starts to survive - stated so nobody reads the above
    // as a promise that any shadow keys.
    val veryDark = argb(0, (0xB1 * 0.2f).toInt(), (0x40 * 0.2f).toInt())
    check(mask(greenKey, veryDark) > 0f, "a screen at 20% light is still cut - the shadow limit has moved")

    // ---- The range the slider offers --------------------------------------
    check(greenKey.similarity in ChromaKey.SIMILARITY_RANGE, "the default similarity is outside the range the slider offers")
    // The bottom of the range leaves the lit screen keyed; the top eats neutrals,
    // which is what the comment means by "nothing useful past 0.45".
    check(mask(greenKey.copy(similarity = ChromaKey.SIMILARITY_RANGE.start), greenKey.keyColorArgb) == 0f, "the lowest similarity does not cut the key colour")
    check(mask(greenKey.copy(similarity = ChromaKey.SIMILARITY_RANGE.endInclusive), argb(255, 255, 255)) == 0f, "the highest similarity does not reach a neutral - the range could go further")
    check(neutralDistance > greenKey.similarity + greenKey.smoothness, "the default key reaches a neutral through its feather")

    // ---- The guards on the divisors ---------------------------------------
    check(ChromaKey(smoothness = 0f).safeSmoothness > 0f && ChromaKey(spill = 0f).safeSpill > 0f, "a divisor can still be zero")
    check(mask(ChromaKey(smoothness = 0f), argb(255, 255, 255)) == 1f, "a zero feather stops being a hard edge")

    if (problems.isEmpty()) println("ChromaKeyChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
