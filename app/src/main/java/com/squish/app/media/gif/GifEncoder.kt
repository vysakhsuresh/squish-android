package com.squish.app.media.gif

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A looping animated GIF, frame by frame, from ARGB pixels.
 *
 * One fixed palette for every frame - 6 reds x 7 greens x 6 blues, 252 colours,
 * with green given the extra step because the eye sees it best - and a 4x4
 * ordered dither, so gradients band far less than a nearest colour would and
 * the file compresses well (an ordered dither repeats; error diffusion is noise
 * to LZW). Kept free of Android so it is executed on the JVM
 * (tools/jvm/GifChecks.kt).
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    private var started = false

    /** One frame of [argb] (width x height, row by row), shown for [delayMs]. */
    fun addFrame(argb: IntArray, delayMs: Int) {
        require(argb.size == width * height) { "frame is ${argb.size} pixels, not ${width * height}" }
        if (!started) writeHeader()
        started = true
        // Graphic control: the frame's delay, in hundredths.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        writeShort((delayMs / 10).coerceIn(2, 65_535))
        out.write(byteArrayOf(0, 0))
        // Image descriptor: the whole canvas, the global palette.
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0)
        lzw(indices(argb))
    }

    fun finish() {
        if (!started) writeHeader()
        out.write(0x3B)
        out.flush()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width); writeShort(height)
        // A global palette of 256 entries (2^(7+1)).
        out.write(0xF7)
        out.write(0); out.write(0)
        for (i in 0 until 256) {
            if (i < PALETTE_SIZE) {
                val r = i / (G * B); val g = (i / B) % G; val b = i % B
                out.write(r * 255 / (R - 1)); out.write(g * 255 / (G - 1)); out.write(b * 255 / (B - 1))
            } else {
                out.write(0); out.write(0); out.write(0)
            }
        }
        // Loop for ever (NETSCAPE2.0).
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun indices(argb: IntArray): ByteArray {
        val px = ByteArray(argb.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val bias = BAYER[(y and 3) * 4 + (x and 3)] / 16f - 0.5f + 1f / 32f
                val r = level((c shr 16) and 0xFF, R, bias)
                val g = level((c shr 8) and 0xFF, G, bias)
                val b = level(c and 0xFF, B, bias)
                px[y * width + x] = (r * G * B + g * B + b).toByte()
            }
        }
        return px
    }

    private fun level(v: Int, steps: Int, bias: Float): Int =
        (v / 255f * (steps - 1) + bias).let { Math.round(it) }.coerceIn(0, steps - 1)

    /** The frame's pixels, LZW-compressed in 8-bit code space and written in sub-blocks. */
    private fun lzw(pixels: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val blocks = SubBlocks(out)
        val clear = 1 shl minCode
        val end = clear + 1
        var codeSize = minCode + 1
        var next = end + 1
        val table = HashMap<Int, Int>(8192)
        blocks.bits(clear, codeSize)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) {
                prefix = found
                continue
            }
            blocks.bits(prefix, codeSize)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                blocks.bits(clear, codeSize)
                table.clear()
                codeSize = minCode + 1
                next = end + 1
            }
            prefix = k
        }
        blocks.bits(prefix, codeSize)
        blocks.bits(end, codeSize)
        blocks.close()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** Codes packed least-significant bit first into blocks of up to 255 bytes. */
    private class SubBlocks(private val out: OutputStream) {
        private val block = ByteArrayOutputStream(255)
        private var acc = 0
        private var count = 0

        fun bits(code: Int, size: Int) {
            acc = acc or (code shl count)
            count += size
            while (count >= 8) {
                byte(acc and 0xFF)
                acc = acc ushr 8
                count -= 8
            }
        }

        private fun byte(b: Int) {
            block.write(b)
            if (block.size() == 255) flushBlock()
        }

        private fun flushBlock() {
            if (block.size() == 0) return
            out.write(block.size())
            block.writeTo(out)
            block.reset()
        }

        fun close() {
            if (count > 0) byte(acc and 0xFF)
            acc = 0; count = 0
            flushBlock()
            out.write(0)
        }
    }

    companion object {
        const val R = 6
        const val G = 7
        const val B = 6
        const val PALETTE_SIZE = R * G * B

        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    }
}
