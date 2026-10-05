import com.squish.app.media.gif.GifEncoder
import com.squish.app.media.gif.GifSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val w = 160
    val h = 90
    // Three frames: a gradient, a noisy picture (many codes, table resets), and flat colour.
    val gradient = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 128 }
    val rnd = java.util.Random(3)
    val noise = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
    val flat = IntArray(w * h) { 0xFF2080E0.toInt() }
    val bytes = ByteArrayOutputStream()
    GifEncoder(bytes, w, h).apply {
        addFrame(gradient, 100)
        addFrame(noise, 100)
        addFrame(flat, 100)
        finish()
    }
    val data = bytes.toByteArray()
    check(String(data, 0, 6) == "GIF89a", "not a GIF89a file")

    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(data))
    val frames = reader.getNumImages(true)
    check(frames == 3, "decoded $frames frames, not 3")

    fun maxError(expected: IntArray, index: Int): Int {
        val img = reader.read(index)
        var worst = 0
        for (y in 0 until h) for (x in 0 until w) {
            val a = expected[y * w + x]
            val b = img.getRGB(x, y)
            for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        }
        return worst
    }
    // A 6-7-6 palette steps by ~43-51; a dithered pixel is at most a step away.
    check(maxError(gradient, 0) <= 52, "the gradient decoded too far off: ${maxError(gradient, 0)}")
    check(maxError(noise, 1) <= 52, "the noisy frame decoded too far off (LZW or table reset broken): ${maxError(noise, 1)}")
    check(maxError(flat, 2) <= 52, "the flat frame decoded too far off")

    sizeChecks()

    if (problems.isEmpty()) println("GifChecks: all checks passed (${data.size} bytes)") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}

/**
 * The size the GIF is written at, from the shape of the footage alone - no
 * pixels. It used to be read off a full-resolution decode of frame zero, 33 MB
 * for a 4K edit, purely for these two numbers.
 */
fun sizeChecks() {
    // 4K, down to 480 wide, the aspect kept.
    check(GifSize.of(3840, 2160) == 480 to 270, "4K gave ${GifSize.of(3840, 2160)}")
    // Portrait footage: the long side is the height, and the width is still
    // what is capped - a GIF of a portrait clip is 480 across, 854 tall.
    check(GifSize.of(1080, 1920) == 480 to 852, "portrait gave ${GifSize.of(1080, 1920)}")
    // Footage narrower than the cap is not blown up.
    check(GifSize.of(320, 240) == 320 to 240, "a small clip gave ${GifSize.of(320, 240)}")
    check(GifSize.of(480, 480) == 480 to 480, "a square at the cap gave ${GifSize.of(480, 480)}")
    // Both sides even, always: the encoder's buffer is indexed by width.
    listOf(
        3840 to 2160, 1920 to 1080, 1080 to 1920, 1440 to 1080, 641 to 481, 481 to 641,
        320 to 241, 101 to 7, 7 to 101, 2 to 2, 1 to 1, 10_000 to 3
    ).forEach { (sw, sh) ->
        val size = GifSize.of(sw, sh)
        check(size != null, "${sw}x$sh gave nothing")
        if (size != null) {
            val (w, h) = size
            check(w % 2 == 0 && h % 2 == 0, "${sw}x$sh gave an odd side: ${w}x$h")
            check(w >= 2 && h >= 2, "${sw}x$sh gave a side under 2: ${w}x$h")
            check(w <= GifSize.MAX_WIDTH, "${sw}x$sh is ${w} across, past the cap")
            check(w <= maxOf(sw, 2), "${sw}x$sh was widened to $w")
            // The aspect is kept to within the rounding to even.
            val wanted = w.toDouble() * sh / sw
            check(kotlin.math.abs(h - wanted) <= 2.0, "${sw}x$sh gave $h tall where $wanted was wanted")
        }
    }
    // An unreadable shape is nothing, not a zero-sized encoder.
    check(GifSize.of(0, 1080) == null, "a width of nothing gave a size")
    check(GifSize.of(1920, 0) == null, "a height of nothing gave a size")
    check(GifSize.of(-1, -1) == null, "a negative shape gave a size")
}
