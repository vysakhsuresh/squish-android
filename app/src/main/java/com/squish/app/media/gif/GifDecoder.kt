package com.squish.app.media.gif

/**
 * An animated GIF read into whole frames, for a GIF placed as a sticker
 * (EDGE_PLAN F4, INSHOT_PARITY P6): LZW, local and global palettes,
 * transparency, interlacing, the three disposal methods and each frame's
 * delay. Free of Android so tools/jvm/GifDecodeChecks.kt executes it -
 * against GifEncoder's own files and against files made by hand for the
 * disposals the encoder never writes.
 *
 * Frames come out composed, as a viewer shows them - each frame drawn over
 * what the last left, after that one's disposal - and scaled down to
 * [maxSide], because a sticker is held in memory frame by frame and a 1080p
 * GIF of a hundred frames would be a third of a gigabyte. A file this cannot
 * read is a [Failure] with a sentence, never a crash.
 */
object GifDecoder {

    class Failure(message: String) : Exception(message)

    /** The frames, each [width] x [height] ARGB, and how long each is shown. */
    class Animation(val width: Int, val height: Int, val frames: List<IntArray>, val delaysMs: IntArray) {
        val durationMs: Int get() = delaysMs.sum()
    }

    /** Larger than this either way is refused rather than read: a GIF this size is not a sticker. */
    const val MAX_CANVAS = 4096

    /** At most this many frames are kept; a longer GIF keeps every second one, its delays added. */
    const val MAX_FRAMES = 120

    /** What a browser shows a frame for when the file says 0 or 10 ms: anything shorter plays as this. */
    const val SHORT_DELAY_MS = 100

    fun decode(bytes: ByteArray, maxSide: Int = 320): Animation {
        val r = Reader(bytes)
        val sig = String(ByteArray(6) { r.u8().toByte() }, Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") throw Failure("This is not a GIF file.")
        val width = r.u16()
        val height = r.u16()
        if (width <= 0 || height <= 0) throw Failure("This GIF has no size.")
        if (width > MAX_CANVAS || height > MAX_CANVAS) throw Failure("This GIF is ${width}x$height - too large for a sticker.")
        val packed = r.u8()
        r.u8() // background colour index: a viewer shows the page through, and so does a sticker
        r.u8() // pixel aspect
        val global = if (packed and 0x80 != 0) palette(r, 2 shl (packed and 7)) else null

        val canvas = IntArray(width * height)
        var saved: IntArray? = null
        val out = ArrayList<IntArray>()
        val delays = ArrayList<Int>()
        var delay = 0
        var transparent = -1
        var disposal = 0
        // The rectangle and disposal of the frame drawn last, applied before the next.
        var lastRect: IntArray? = null
        var lastDisposal = 0

        val (ow, oh) = scaled(width, height, maxSide)
        loop@ while (true) {
            if (!r.has(1)) break
            when (r.u8()) {
                0x21 -> {
                    val label = r.u8()
                    if (label == 0xF9) {
                        val size = r.u8()
                        if (size < 4) throw Failure("This GIF's frame settings are broken.")
                        val flags = r.u8()
                        delay = r.u16() * 10
                        val t = r.u8()
                        transparent = if (flags and 1 != 0) t else -1
                        disposal = (flags shr 2) and 7
                        r.skip(size - 4)
                        r.skipBlocks()
                    } else r.skipBlocks()
                }
                0x2C -> {
                    // Before this frame: what the last one asked to leave.
                    lastRect?.let { rect ->
                        when (lastDisposal) {
                            2 -> clear(canvas, width, rect)
                            3 -> saved?.copyInto(canvas)
                        }
                    }
                    val fx = r.u16(); val fy = r.u16(); val fw = r.u16(); val fh = r.u16()
                    val fp = r.u8()
                    val local = if (fp and 0x80 != 0) palette(r, 2 shl (fp and 7)) else null
                    val interlaced = fp and 0x40 != 0
                    val colours = local ?: global ?: throw Failure("This GIF has a frame with no colours.")
                    if (fw <= 0 || fh <= 0) { r.u8(); r.skipBlocks(); continue@loop }
                    saved = if (disposal == 3) canvas.copyOf() else null
                    val indices = lzw(r, fw * fh)
                    draw(canvas, width, height, indices, fx, fy, fw, fh, colours, transparent, interlaced)
                    out += downscale(canvas, width, height, ow, oh)
                    delays += if (delay <= 10) SHORT_DELAY_MS else delay
                    lastRect = intArrayOf(fx, fy, fw, fh)
                    lastDisposal = disposal
                    // A graphic control applies to the one frame after it.
                    delay = 0; transparent = -1; disposal = 0
                }
                0x3B -> break@loop
                0x00 -> continue@loop
                else -> throw Failure("This GIF is damaged.")
            }
        }
        if (out.isEmpty()) throw Failure("This GIF has no pictures in it.")
        return thinned(Animation(ow, oh, out, delays.toIntArray()))
    }

    /** The frame showing [elapsedMs] into [animation], going round again at its end. */
    fun frameAt(animation: Animation, elapsedMs: Long): Int {
        val total = animation.durationMs.coerceAtLeast(1)
        var t = ((elapsedMs % total) + total) % total
        for (i in animation.delaysMs.indices) {
            t -= animation.delaysMs[i]
            if (t < 0) return i
        }
        return animation.frames.size - 1
    }

    /** Every second frame of a long GIF, each kept frame shown for both of theirs. */
    private fun thinned(a: Animation): Animation {
        if (a.frames.size <= MAX_FRAMES) return a
        val frames = ArrayList<IntArray>()
        val delays = ArrayList<Int>()
        var i = 0
        while (i < a.frames.size) {
            frames += a.frames[i]
            delays += a.delaysMs[i] + if (i + 1 < a.frames.size) a.delaysMs[i + 1] else 0
            i += 2
        }
        return thinned(Animation(a.width, a.height, frames, delays.toIntArray()))
    }

    fun scaled(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxSide) return w to h
        val k = maxSide.toFloat() / long
        return maxOf(1, Math.round(w * k)) to maxOf(1, Math.round(h * k))
    }

    private fun palette(r: Reader, n: Int): IntArray = IntArray(n) {
        val red = r.u8(); val green = r.u8(); val blue = r.u8()
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun clear(canvas: IntArray, width: Int, rect: IntArray) {
        val (x, y, w, h) = rect.toList()
        val height = canvas.size / width
        for (j in y until minOf(y + h, height)) for (i in x until minOf(x + w, width)) canvas[j * width + i] = 0
    }

    private fun draw(
        canvas: IntArray, width: Int, height: Int, indices: ByteArray,
        fx: Int, fy: Int, fw: Int, fh: Int, colours: IntArray, transparent: Int, interlaced: Boolean
    ) {
        for (row in 0 until fh) {
            val y = fy + if (interlaced) interlacedRow(row, fh) else row
            if (y >= height) continue
            for (col in 0 until fw) {
                val x = fx + col
                if (x >= width) continue
                val index = indices[row * fw + col].toInt() and 0xFF
                if (index == transparent || index >= colours.size) continue
                canvas[y * width + x] = colours[index]
            }
        }
    }

    /** Which canvas row the [row]th stored row of an interlaced frame [h] high is: every 8th from 0, every 8th from 4, every 4th from 2, every 2nd from 1. */
    fun interlacedRow(row: Int, h: Int): Int {
        val p1 = (h + 7) / 8
        val p2 = (h + 3) / 8
        val p3 = (h + 1) / 4
        return when {
            row < p1 -> row * 8
            row < p1 + p2 -> (row - p1) * 8 + 4
            row < p1 + p2 + p3 -> (row - p1 - p2) * 4 + 2
            else -> (row - p1 - p2 - p3) * 2 + 1
        }
    }

    /** One frame's pixel indices, [count] of them; a short stream leaves the rest at 0, as viewers do. */
    private fun lzw(r: Reader, count: Int): ByteArray {
        val minCode = r.u8()
        if (minCode < 2 || minCode > 11) throw Failure("This GIF's picture data is broken.")
        val data = r.blocks()
        val out = ByteArray(count)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); first[i] = i.toByte(); prefix[i] = -1 }
        val stack = ByteArray(4097)
        var codeSize = minCode + 1
        var next = eoi + 1
        var prev = -1
        var written = 0
        var bitPos = 0L
        val totalBits = data.size * 8L
        while (written < count) {
            if (bitPos + codeSize > totalBits) break
            var code = 0
            for (b in 0 until codeSize) {
                val p = bitPos + b
                if ((data[(p ushr 3).toInt()].toInt() shr (p and 7).toInt()) and 1 != 0) code = code or (1 shl b)
            }
            bitPos += codeSize
            if (code == clear) { codeSize = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            if (prev == -1) {
                if (code >= clear) throw Failure("This GIF's picture data is broken.")
                out[written++] = code.toByte()
                prev = code
                continue
            }
            if (code > next) throw Failure("This GIF's picture data is broken.")
            // The code not yet in the table is the last string and its own first letter (KwKwK).
            val inTable = code < next
            var c = if (inTable) code else prev
            var sp = 0
            if (!inTable) stack[sp++] = first[prev]
            while (c >= clear) { stack[sp++] = suffix[c]; c = prefix[c] }
            stack[sp++] = c.toByte()
            while (sp > 0 && written < count) out[written++] = stack[--sp]
            if (next < 4096) {
                prefix[next] = prev
                suffix[next] = if (inTable) first[code] else first[prev]
                first[next] = first[prev]
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = code
        }
        return out
    }

    /** Area-averaged down to [ow] x [oh], the colour weighted by its alpha so a transparent edge does not turn dark. */
    private fun downscale(src: IntArray, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        if (ow == w && oh == h) return src.copyOf()
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val y0 = y * h / oh; val y1 = maxOf(y0 + 1, (y + 1) * h / oh)
            for (x in 0 until ow) {
                val x0 = x * w / ow; val x1 = maxOf(x0 + 1, (x + 1) * w / ow)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (j in y0 until y1) for (i in x0 until x1) {
                    val c = src[j * w + i]
                    val ca = (c ushr 24) and 0xFF
                    a += ca; r += ((c shr 16) and 0xFF) * ca; g += ((c shr 8) and 0xFF) * ca; b += (c and 0xFF) * ca
                    n++
                }
                out[y * ow + x] = if (a == 0L) 0 else
                    ((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            }
        }
        return out
    }

    private class Reader(private val b: ByteArray) {
        var pos = 0
        fun has(n: Int) = pos + n <= b.size
        fun u8(): Int {
            if (pos >= b.size) throw Failure("This GIF stops part way through.")
            return b[pos++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun skip(n: Int) { if (!has(n)) throw Failure("This GIF stops part way through."); pos += n }
        fun skipBlocks() { while (true) { val n = u8(); if (n == 0) return; skip(n) } }
        fun blocks(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = u8()
                if (n == 0) return out.toByteArray()
                if (!has(n)) throw Failure("This GIF stops part way through.")
                out.write(b, pos, n)
                pos += n
            }
        }
    }
}
