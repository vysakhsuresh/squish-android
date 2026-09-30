import com.squish.app.media.gif.GifEncoder
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

    if (problems.isEmpty()) println("GifChecks: all checks passed (${data.size} bytes)") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
