import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The busiest methods on one thread of an ART method trace (`am profile start
 * --sampling`), for a machine with no profiler: inclusive and self wall time,
 * from the trace's own enter/exit records. Found the editor's main-thread
 * work during playback (CLAUDE.md, "On the desktop").
 *
 *   java -cp <out;kotlin-stdlib> TraceTopKt sq.trace [thread=main] [count=40] [filter]
 */
fun main(args: Array<String>) {
    val bytes = File(args[0]).readBytes()
    val threadName = args.getOrNull(1) ?: "main"
    val count = args.getOrNull(2)?.toIntOrNull() ?: 40
    val filter = args.getOrNull(3)

    // The text header runs to "*end\n"; the binary records follow it.
    val endMark = "*end\n".toByteArray()
    var headerEnd = -1
    for (i in 0..bytes.size - endMark.size) {
        if ((0 until endMark.size).all { bytes[i + it] == endMark[it] }) { headerEnd = i + endMark.size; break }
    }
    val header = String(bytes, 0, headerEnd, Charsets.UTF_8)
    val threads = HashMap<Int, String>()
    val methods = HashMap<Long, String>()
    var section = ""
    for (line in header.lines()) {
        if (line.startsWith("*")) { section = line; continue }
        if (line.isBlank()) continue
        when (section) {
            "*threads" -> line.split('\t').let { if (it.size >= 2) threads[it[0].toInt()] = it[1] }
            "*methods" -> line.split('\t').let { p ->
                if (p.size >= 3) methods[java.lang.Long.decode(p[0])] = p[1].replace("com.squish.app.", "~") + "." + p[2]
            }
        }
    }
    val tid = threads.entries.firstOrNull { it.value == threadName }?.key ?: error("no thread $threadName: ${threads.values}")

    val b = ByteBuffer.wrap(bytes, headerEnd, bytes.size - headerEnd).order(ByteOrder.LITTLE_ENDIAN)
    b.int // magic
    val version = b.short.toInt()
    val offset = b.short.toInt()
    b.long // start time
    val recordSize = if (version >= 3) b.short.toInt() else if (version == 1) 10 else 14
    b.position(headerEnd + offset)

    data class Frame(val method: Long, val start: Long, var child: Long = 0)
    val stack = ArrayList<Frame>()
    val inclusive = HashMap<Long, Long>()
    val self = HashMap<Long, Long>()
    var total = 0L
    var first = -1L
    var last = 0L
    while (b.remaining() >= recordSize) {
        val at = b.position()
        val thread = b.short.toInt() and 0xffff
        val value = b.int.toLong() and 0xffffffffL
        b.int // thread cpu
        val wall = b.int.toLong() and 0xffffffffL
        b.position(at + recordSize)
        if (thread != tid) continue
        if (first < 0) first = wall
        last = wall
        val method = value and 0xfffffffcL
        val action = (value and 3L).toInt()
        if (action == 0) {
            stack.add(Frame(method, wall))
        } else {
            // Exit or unroll: pop to the matching frame.
            val i = stack.indexOfLast { it.method == method }
            if (i < 0) continue
            while (stack.size > i) {
                val f = stack.removeAt(stack.size - 1)
                val dur = (wall - f.start).coerceAtLeast(0)
                if (stack.none { it.method == f.method }) inclusive[f.method] = (inclusive[f.method] ?: 0) + dur
                self[f.method] = (self[f.method] ?: 0) + (dur - f.child).coerceAtLeast(0)
                stack.lastOrNull()?.let { it.child += dur }
                if (stack.isEmpty()) total += dur
            }
        }
    }
    val span = (last - first).coerceAtLeast(1)
    fun name(m: Long) = methods[m] ?: "0x" + m.toString(16)
    fun show(title: String, map: Map<Long, Long>) {
        println("== $title (thread $threadName, ${span / 1000} ms traced)")
        map.entries.filter { filter == null || name(it.key).contains(filter) }
            .sortedByDescending { it.value }.take(count).forEach {
                println("%8.1f ms  %5.1f%%  %s".format(it.value / 1000.0, it.value * 100.0 / span, name(it.key)))
            }
    }
    show("inclusive", inclusive)
    show("self", self)
}
