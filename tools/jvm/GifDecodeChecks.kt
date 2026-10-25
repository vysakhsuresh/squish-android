import com.squish.app.media.gif.GifDecoder
import com.squish.app.media.gif.GifEncoder
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F4 / INSHOT_PARITY P6: GIF stickers. The decoder against the app's
 * own encoder (frames and delays back as they went in, a picture busy enough
 * to fill the code table and clear it), against files written here by hand for
 * the disposals and the transparency the encoder never writes, against broken
 * files (a sentence, never a crash), and the frame a moment falls on, going
 * round at the end.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** A colour the encoder's palette holds exactly, so the dither cannot move it. */
private fun exact(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r * 255 / 5) shl 16) or ((g * 255 / 6) shl 8) or (b * 255 / 5)

private fun encoded(w: Int, h: Int, frames: List<IntArray>, delays: List<Int>): ByteArray {
    val out = ByteArrayOutputStream()
    val e = GifEncoder(out, w, h)
    frames.zip(delays).forEach { (f, d) -> e.addFrame(f, d) }
    e.finish()
    return out.toByteArray()
}

/** A frame for a hand-written GIF: its rectangle, what it leaves behind, its transparent index. */
private class Raw(val x: Int, val y: Int, val w: Int, val h: Int, val px: IntArray, val disposal: Int = 0, val transparent: Int = -1, val delayCs: Int = 10)

/**
 * A GIF of 4 colours (black, red, green, blue) written byte by byte. The LZW
 * stream is literals only, with a clear code before the table can grow a bit,
 * which every decoder must read and which needs no encoder to trust.
 */
private fun handmade(w: Int, h: Int, frames: List<Raw>): ByteArray {
    val o = ByteArrayOutputStream()
    fun s(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    o.write("GIF89a".toByteArray()); s(w); s(h); o.write(0x81); o.write(0); o.write(0)
    listOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF).forEach { c -> o.write(c shr 16); o.write((c shr 8) and 0xFF); o.write(c and 0xFF) }
    for (f in frames) {
        o.write(0x21); o.write(0xF9); o.write(4)
        o.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0)); s(f.delayCs); o.write(maxOf(f.transparent, 0)); o.write(0)
        o.write(0x2C); s(f.x); s(f.y); s(f.w); s(f.h); o.write(0)
        o.write(2)
        // Codes are 3 bits: clear 4, end 5; two literals then a clear keeps the table from growing.
        val codes = ArrayList<Int>()
        codes += 4
        f.px.forEachIndexed { i, p -> codes += p; if (i % 2 == 1) codes += 4 }
        codes += 5
        val bits = ByteArrayOutputStream(); var acc = 0; var n = 0
        for (c in codes) { acc = acc or (c shl n); n += 3; while (n >= 8) { bits.write(acc and 0xFF); acc = acc ushr 8; n -= 8 } }
        if (n > 0) bits.write(acc and 0xFF)
        val data = bits.toByteArray()
        var at = 0
        while (at < data.size) { val k = minOf(255, data.size - at); o.write(k); o.write(data, at, k); at += k }
        o.write(0)
    }
    o.write(0x3B)
    return o.toByteArray()
}

private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLUE = 0xFF0000FF.toInt()

/** A section run so that a decoder that throws fails the check by name, rather than ending the run. */
private fun section(name: String, body: () -> Unit) {
    try { body() } catch (e: Throwable) { check(false, "$name: the decoder threw $e") }
}

fun main() {
    // --- Round trip through the app's own encoder. ---
    section("the round trip") {
        val w = 16; val h = 12
        val frames = (0 until 3).map { k -> IntArray(w * h) { i -> exact((i + k) % 6, (i / w + k) % 7, (i * 3 + k) % 6) } }
        val a = GifDecoder.decode(encoded(w, h, frames, listOf(40, 120, 70)))
        check(a.width == w && a.height == h, "a ${w}x$h GIF read back as ${a.width}x${a.height}")
        check(a.frames.size == 3, "three frames read back as ${a.frames.size}")
        check(a.delaysMs.toList() == listOf(40, 120, 70), "the delays read back as ${a.delaysMs.toList()}")
        for (k in frames.indices) {
            val bad = frames[k].indices.count { frames[k][it] != a.frames.getOrNull(k)?.getOrNull(it) }
            check(bad == 0, "frame $k read back with $bad of ${w * h} pixels wrong")
        }
        // Busy enough to fill the 4096-entry table, clear it and fill it again.
        val bw = 160; val bh = 120
        var seed = 12345
        val busy = IntArray(bw * bh) { seed = seed * 1103515245 + 12345; val v = (seed ushr 16) and 0x7FFF; exact(v % 6, (v / 6) % 7, (v / 42) % 6) }
        val b = GifDecoder.decode(encoded(bw, bh, listOf(busy), listOf(100)), maxSide = 1000)
        val bad = busy.indices.count { busy[it] != b.frames[0][it] }
        check(bad == 0, "a busy frame read back with $bad of ${bw * bh} pixels wrong - the code size or the table reset is off")
    }

    // --- Disposal to background: the first frame's rectangle is cleared before the second. ---
    section("disposal to background") {
        val a = GifDecoder.decode(handmade(4, 4, listOf(
            Raw(0, 0, 4, 4, IntArray(16) { 1 }, disposal = 2),
            Raw(1, 1, 2, 2, IntArray(4) { 2 })
        )))
        check(a.frames[0].all { it == RED }, "the first frame is not all red")
        val f = a.frames[1]
        check(f[5] == GREEN && f[6] == GREEN && f[9] == GREEN && f[10] == GREEN, "the second frame's square is not green")
        check(f[0] == 0 && f[15] == 0 && f[3] == 0, "disposal to background left the red behind: ${f[0].toUInt().toString(16)}")
    }
    // --- Disposal to previous: the canvas as it was before the frame comes back. ---
    section("disposal to previous") {
        val a = GifDecoder.decode(handmade(4, 4, listOf(
            Raw(0, 0, 4, 4, IntArray(16) { 1 }),
            Raw(0, 0, 2, 2, IntArray(4) { 3 }, disposal = 3),
            Raw(3, 3, 1, 1, IntArray(1) { 2 })
        )))
        check(a.frames[1][0] == BLUE && a.frames[1][5] == BLUE && a.frames[1][2] == RED, "the blue corner was not drawn over the red")
        check(a.frames[2][0] == RED && a.frames[2][5] == RED, "disposal to previous left the blue corner behind")
        check(a.frames[2][15] == GREEN, "the third frame's pixel is not green")
    }
    // --- No disposal and a transparent index: what is under the hole shows. ---
    section("transparency") {
        val a = GifDecoder.decode(handmade(4, 1, listOf(
            Raw(0, 0, 4, 1, intArrayOf(1, 1, 1, 1)),
            Raw(0, 0, 4, 1, intArrayOf(3, 0, 3, 0), transparent = 0)
        )))
        check(a.frames[1].toList() == listOf(BLUE, RED, BLUE, RED), "a transparent index painted over the frame under it: ${a.frames[1].map { it.toUInt().toString(16) }}")
    }
    // --- Interlaced rows land where they belong. ---
    check((0 until 8).map { GifDecoder.interlacedRow(it, 8) } == listOf(0, 4, 2, 6, 1, 3, 5, 7), "interlaced rows: ${(0 until 8).map { GifDecoder.interlacedRow(it, 8) }}")
    check((0 until 5).map { GifDecoder.interlacedRow(it, 5) }.sorted() == listOf(0, 1, 2, 3, 4), "an interlaced 5-row frame does not fill each row once")

    // --- Broken files are a sentence, never a crash. ---
    val good = encoded(8, 8, listOf(IntArray(64) { exact(5, 0, 0) }, IntArray(64) { exact(0, 6, 0) }), listOf(100, 100))
    val broken = listOf(
        ByteArray(0), "hello".toByteArray(), "GIF89a".toByteArray(), good.copyOf(good.size / 2), good.copyOf(30),
        ByteArray(400) { (it * 37).toByte() }, "GIF89a".toByteArray() + ByteArray(7) + byteArrayOf(0x2C)
    )
    broken.forEachIndexed { i, bytes ->
        try {
            val a = GifDecoder.decode(bytes)
            // A file cut after its first frame may show that frame; it may not invent more.
            check(a.frames.isNotEmpty(), "broken file $i decoded to nothing without saying so")
        } catch (e: GifDecoder.Failure) {
            check(!e.message.isNullOrBlank() && e.message!!.endsWith("."), "broken file $i failed without a sentence: ${e.message}")
        } catch (e: Throwable) {
            check(false, "broken file $i crashed the decoder: $e")
        }
    }

    // --- The frame a moment falls on, round and round. ---
    section("frames by time") {
        val a = GifDecoder.Animation(1, 1, List(3) { IntArray(1) }, intArrayOf(100, 200, 300))
        val got = listOf(0L, 99L, 100L, 299L, 300L, 599L, 600L, 650L, -1L).map { GifDecoder.frameAt(a, it) }
        check(got == listOf(0, 0, 1, 1, 2, 2, 0, 0, 2), "frames by time: $got")
        // A long GIF is thinned, and plays for as long as it did.
        val long = (0 until 300).map { IntArray(4) { 0xFF000000.toInt() or it } }
        val bytes = encoded(2, 2, long, List(300) { 50 })
        val t = GifDecoder.decode(bytes)
        check(t.frames.size <= GifDecoder.MAX_FRAMES, "a 300-frame GIF kept ${t.frames.size} frames")
        check(t.durationMs == 300 * 50, "a thinned GIF plays for ${t.durationMs} ms, not ${300 * 50}")
        // A short delay plays as a browser plays it.
        val quick = GifDecoder.decode(handmade(1, 1, listOf(Raw(0, 0, 1, 1, intArrayOf(1), delayCs = 0))))
        check(quick.delaysMs[0] == GifDecoder.SHORT_DELAY_MS, "a 0 ms frame plays for ${quick.delaysMs[0]} ms")
    }
    // --- Scaled down, a soft edge does not darken. ---
    section("scaling down") {
        val w = 640; val h = 2
        val half = IntArray(w * h) { if ((it % w) % 2 == 0) RED else 0 }
        val bytes = handmade(w, h, listOf(Raw(0, 0, w, h, IntArray(w * h) { if ((it % w) % 2 == 0) 1 else 0 }, transparent = 0)))
        val a = GifDecoder.decode(bytes, maxSide = 320)
        check(a.width == 320 && a.height == 1, "640x2 at a 320 cap came out ${a.width}x${a.height}")
        val c = a.frames[0][10]
        check(((c shr 16) and 0xFF) == 255 && ((c ushr 24) and 0xFF) in 120..135, "half-covered red scaled down to ${c.toUInt().toString(16)}, not half-transparent red")
        check(half.isNotEmpty())
    }

    if (problems.isEmpty()) println("GifDecodeChecks: all checks passed")
    else { problems.take(30).forEach { println("FAIL: $it") }; println("(${problems.size} in all)"); exitProcess(1) }
}
