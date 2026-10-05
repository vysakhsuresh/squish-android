import java.io.RandomAccessFile

/**
 * What ffprobe would say about an MP4's tracks, for a machine without it: per
 * track its handler, timescale, sample count, length and the spread of its
 * frame durations (stts), read from the boxes alone. The device checks in
 * CLAUDE.md ask for a file's frame rate and frame count; this answers them.
 *
 *   java -cp <out;kotlin-stdlib> Mp4ProbeKt file.mp4 [...]
 */
fun main(args: Array<String>) {
    for (path in args) {
        println("== $path")
        RandomAccessFile(path, "r").use { f -> boxes(f, 0L, f.length(), 0) }
    }
}

private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

private var handler = ""
private var timescale = 0L

/** The track's codec, from the first sample entry of stsd ("avc1", "mp4a", "hvc1"…). */
private var format = ""

/** The track's bytes, from stsz - what the track actually costs in the file. */
private var trackBytes = 0L

/** The line stts prepared, printed when the trak box closes. */
private var pending: (() -> Unit)? = null

private fun boxes(f: RandomAccessFile, start: Long, end: Long, depth: Int) {
    var at = start
    while (at + 8 <= end) {
        f.seek(at)
        var size = f.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
        var header = 8L
        if (size == 1L) { size = f.readLong(); header = 16L }
        if (size == 0L) size = end - at
        if (size < header) return
        val body = at + header
        when (type) {
            // A track is printed when its box closes, not when its stts is read:
            // stsz and stsd come after stts in stbl, so the bytes and the codec
            // are only known by then.
            "trak" -> {
                handler = ""; timescale = 0L; format = ""; trackBytes = 0L; pending = null
                boxes(f, body, at + size, depth + 1)
                pending?.invoke()
                pending = null
            }
            in containers -> boxes(f, body, at + size, depth + 1)
            "mdhd" -> {
                f.seek(body)
                val version = f.readUnsignedByte(); f.skipBytes(3)
                if (version == 1) { f.skipBytes(16); timescale = f.readInt().toLong() and 0xffffffffL }
                else { f.skipBytes(8); timescale = f.readInt().toLong() and 0xffffffffL }
            }
            "hdlr" -> { f.seek(body + 8); handler = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1) }
            // stsd's first entry names the codec; stsz adds up to what the
            // track costs. Together they answer "why is this file that big",
            // which a size on the export sheet is a promise about.
            "stsd" -> {
                f.seek(body + 4)
                val entries = f.readInt()
                format = if (entries <= 0) "" else {
                    f.skipBytes(4)
                    ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                }
            }
            "stsz" -> {
                f.seek(body + 4)
                val uniform = f.readInt().toLong() and 0xffffffffL
                val count = f.readInt().toLong() and 0xffffffffL
                trackBytes = if (uniform != 0L) uniform * count else {
                    var sum = 0L
                    repeat(count.toInt()) { sum += f.readInt().toLong() and 0xffffffffL }
                    sum
                }
            }
            "stts" -> {
                f.seek(body + 4)
                val entries = f.readInt()
                var samples = 0L
                var total = 0L
                var minD = Long.MAX_VALUE
                var maxD = 0L
                val spread = LinkedHashMap<Long, Long>()
                repeat(entries) {
                    val count = f.readInt().toLong() and 0xffffffffL
                    val delta = f.readInt().toLong() and 0xffffffffL
                    samples += count; total += count * delta
                    if (count > 0) { minD = minOf(minD, delta); maxD = maxOf(maxD, delta) }
                    spread[delta] = (spread[delta] ?: 0L) + count
                }
                pending = {
                    val seconds = if (timescale > 0) total.toDouble() / timescale else 0.0
                    val rate = if (seconds > 0) samples / seconds else 0.0
                    val kbps = if (seconds > 0) trackBytes * 8.0 / seconds / 1000.0 else 0.0
                    println(
                        "  track $handler ${format.ifEmpty { "?" }}: timescale $timescale, $samples samples, " +
                            "%.3f s, %.3f per second, %d bytes, %.0f kbps".format(seconds, rate, trackBytes, kbps)
                    )
                    if (handler == "vide" && timescale > 0) {
                        println("    frame durations: " + spread.entries.sortedByDescending { it.value }.take(6)
                            .joinToString(", ") { "%.2f ms x%d".format(it.key * 1000.0 / timescale, it.value) })
                        println("    shortest %.2f ms, longest %.2f ms".format(minD * 1000.0 / timescale, maxD * 1000.0 / timescale))
                    }
                }
            }
        }
        at += size
    }
}
