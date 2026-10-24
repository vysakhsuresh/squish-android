import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * SSIM between frames, for docs/BENCHMARKS.md (EDGE_PLAN A1): how close an
 * exported frame is to its source, 1.0 being the same picture.
 *
 *   java -cp "<dir>;<kotlin-stdlib>" FrameCompareKt <a.jpg> <b.jpg>
 *   java -cp ... FrameCompareKt <dirA> <dirB>     # pairs by sorted file name
 *   java -cp ... FrameCompareKt --check           # the self-check, run by the runners
 *
 * Luma SSIM (Wang et al. 2004) over 8x8 windows stepped by 4, the constants of
 * the paper for 8-bit (K1 0.01, K2 0.03). Two frames of different sizes are
 * compared at the smaller size, each scaled with area averaging - frames.ps1
 * writes both at one width, so that is a guard, not the usual path.
 */
fun luma(img: BufferedImage, w: Int, h: Int): DoubleArray {
    val src = if (img.width == w && img.height == h) img else {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
        g.dispose()
        scaled
    }
    val out = DoubleArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val c = src.getRGB(x, y)
        out[y * w + x] = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
    }
    return out
}

fun ssim(a: DoubleArray, b: DoubleArray, w: Int, h: Int, window: Int = 8, step: Int = 4): Double {
    val c1 = (0.01 * 255) * (0.01 * 255)
    val c2 = (0.03 * 255) * (0.03 * 255)
    var sum = 0.0
    var count = 0
    var y = 0
    while (y + window <= h) {
        var x = 0
        while (x + window <= w) {
            var ma = 0.0; var mb = 0.0
            for (j in 0 until window) for (i in 0 until window) {
                val k = (y + j) * w + x + i
                ma += a[k]; mb += b[k]
            }
            val n = (window * window).toDouble()
            ma /= n; mb /= n
            var va = 0.0; var vb = 0.0; var cov = 0.0
            for (j in 0 until window) for (i in 0 until window) {
                val k = (y + j) * w + x + i
                val da = a[k] - ma; val db = b[k] - mb
                va += da * da; vb += db * db; cov += da * db
            }
            va /= n - 1; vb /= n - 1; cov /= n - 1
            sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
            count++
            x += step
        }
        y += step
    }
    return if (count == 0) Double.NaN else sum / count
}

fun compareFiles(fa: File, fb: File): Double {
    val ia = ImageIO.read(fa) ?: error("cannot read $fa")
    val ib = ImageIO.read(fb) ?: error("cannot read $fb")
    val w = minOf(ia.width, ib.width)
    val h = minOf(ia.height, ib.height)
    return ssim(luma(ia, w, h), luma(ib, w, h), w, h)
}

/** The self-check: SSIM must say what it is supposed to about frames whose difference is known. */
fun selfCheck(): Boolean {
    val problems = mutableListOf<String>()
    val w = 96; val h = 64
    val rnd = java.util.Random(5)
    // A picture with detail: gradients and blocks.
    val base = DoubleArray(w * h) { i -> val x = i % w; val y = i / w; ((x * 3 + y * 2) % 200 + ((x / 8 + y / 8) % 2) * 40).toDouble() }
    val same = ssim(base, base.copyOf(), w, h)
    if (abs(same - 1.0) > 1e-9) problems += "a frame against itself scored $same, not 1"
    val noisy = DoubleArray(w * h) { (base[it] + rnd.nextGaussian() * 12).coerceIn(0.0, 255.0) }
    val sNoise = ssim(base, noisy, w, h)
    if (sNoise >= 0.95 || sNoise <= 0.2) problems += "noise of sd 12 scored $sNoise - expected well under 1 and well over 0"
    val noisier = DoubleArray(w * h) { (base[it] + rnd.nextGaussian() * 40).coerceIn(0.0, 255.0) }
    val sNoisier = ssim(base, noisier, w, h)
    if (sNoisier >= sNoise) problems += "more noise scored higher ($sNoisier >= $sNoise)"
    val shifted = DoubleArray(w * h) { i -> val x = i % w; val y = i / w; base[y * w + (x + 3).coerceAtMost(w - 1)] }
    val sShift = ssim(base, shifted, w, h)
    if (sShift >= 0.95) problems += "a 3-pixel shift scored $sShift - SSIM should see it"
    // Symmetric.
    if (abs(ssim(base, noisy, w, h) - ssim(noisy, base, w, h)) > 1e-9) problems += "SSIM is not symmetric"
    // Through real image files: a PNG written and read back is the same frame.
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (i in base.indices) { val v = base[i].toInt().coerceIn(0, 255); img.setRGB(i % w, i / w, (v shl 16) or (v shl 8) or v) }
    val f = File.createTempFile("framecompare", ".png"); ImageIO.write(img, "png", f)
    val sFile = compareFiles(f, f)
    f.delete()
    if (abs(sFile - 1.0) > 1e-9) problems += "a PNG against itself through the file path scored $sFile"
    println("FrameCompare self-check: same 1.000, noise12 %.3f, noise40 %.3f, shift3 %.3f".format(sNoise, sNoisier, sShift))
    problems.forEach { println("FAIL: $it") }
    return problems.isEmpty()
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--check") {
        if (selfCheck()) println("FrameCompareChecks: all checks passed") else exitProcess(1)
        return
    }
    val a = File(args[0]); val b = File(args[1])
    if (a.isDirectory && b.isDirectory) {
        val fa = a.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        val fb = b.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        val n = minOf(fa.size, fb.size)
        if (n == 0) { println("no frames to compare"); exitProcess(1) }
        val scores = (0 until n).map { i -> compareFiles(fa[i], fb[i]).also { println("%-28s %-28s %.4f".format(fa[i].name, fb[i].name, it)) } }
        println("mean SSIM %.4f over %d frames (min %.4f)".format(scores.average(), n, scores.min()))
    } else {
        println("SSIM %.4f".format(compareFiles(a, b)))
    }
}
