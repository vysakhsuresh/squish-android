import com.squish.app.media.effects.PassStage
import kotlin.random.Random
import kotlin.system.exitProcess

/*
 * EDGE_PLAN C3: a preview pass at its identity hands its input on instead of
 * drawing a copy (PassStage, IdlePassEffect). That is Media3's frame
 * hand-over done by hand - credits, hand-backs, releases, flushes - and a
 * mistake in it does not draw a wrong picture: it stalls the preview, or
 * frees a texture twice. So the real PassStage is run here inside a model of
 * Media3's chain, as read in the 1.11.1 classes:
 *
 *  - ChainingGlShaderProgramListener / FrameConsumptionManager between each
 *    pair: an output is sent on if the consumer has a credit and queued if
 *    not; a credit sends the oldest queued frame; a hand-back releases the
 *    producer's output; a flush clears the queue, zeroes the credits and
 *    flushes the producer - so a flush runs from the last stage to the first.
 *    Every call across a pair is a task on one thread, run in order.
 *  - BaseGlShaderProgram as each wrapped pass: room for one frame, an input
 *    handed back as soon as it is drawn, its own output freed (and a credit
 *    given) when released, and on a flush its pool emptied, onFlush, a credit.
 *  - A source with a pool of its own, and a display that holds frames for a
 *    while and gives them back in order, as the final program does.
 */

private val failures = mutableListOf<String>()
private var current = ""
private fun check(what: String, ok: Boolean) { if (!ok && failures.size < 30) failures.add("$current: $what") }

private class Tex(val owner: String, val n: Int) { override fun toString() = "$owner#$n" }

private class Model(seed: Int, stages: Int, val idleChance: Double, sourceRoom: Int, sinkRoom: Int) {
    val rnd = Random(seed)
    val tasks = ArrayDeque<() -> Unit>()

    interface Node { fun queue(t: Tex, pts: Long); fun release(t: Tex); fun flush() }

    inner class Link(val producer: () -> Node, val consumer: () -> Node, val room: Int) {
        var credits = 0
        val pending = ArrayDeque<Pair<Tex, Long>>()
        fun onReady() {
            val p = pending.removeFirstOrNull()
            if (p != null) tasks.add { consumer().queue(p.first, p.second) } else credits++
            check("a stage was given more credits than it has room for ($credits > $room)", credits <= room)
        }
        fun onProcessed(t: Tex) { tasks.add { producer().release(t) } }
        fun onFlush() { pending.clear(); credits = 0; tasks.add { producer().flush() } }
        fun onOutput(t: Tex, pts: Long) {
            if (credits > 0) { credits--; tasks.add { consumer().queue(t, pts) } } else pending.add(t to pts)
        }
    }

    val epoch get() = flushes
    var flushes = 0

    inner class Source(val room: Int) : Node {
        var free = room
        val out = HashSet<Tex>()
        var made = 0
        var nextPts = 0L
        lateinit var down: Link
        fun pump() {
            while (free > 0 && made < TOTAL) {
                free--
                val t = Tex("src", made++)
                out += t
                down.onOutput(t, nextPts)
                nextPts += 33_333
            }
        }
        override fun queue(t: Tex, pts: Long) = error("the source takes no frames")
        override fun release(t: Tex) {
            check("the source was given back $t, which it does not have out", t in out)
            if (out.remove(t)) free++
        }
        override fun flush() { out.clear(); free = room }
    }

    /** BaseGlShaderProgram with a pool of one. */
    inner class Base(val name: String) {
        var free = 1
        val out = HashSet<Tex>()
        var made = 0
        lateinit var up: Link
        lateinit var down: Link
        fun queue(t: Tex, pts: Long) {
            check("$name was handed $t with no room", free > 0)
            free--
            val o = Tex(name, made++)
            out += o
            up.onProcessed(t)
            down.onOutput(o, pts)
        }
        fun release(t: Tex) {
            check("$name was given back $t, which it does not have out", t in out)
            if (out.remove(t)) { free++; up.onReady() }
        }
        fun flush() { out.clear(); free = 1; up.onFlush(); up.onReady() }
    }

    inner class Wrapped(name: String) : Node {
        val inner = Base(name)
        lateinit var up: Link
        lateinit var down: Link
        val stage = PassStage<Tex>(
            idle = { rnd.nextDouble() < idleChance },
            innerQueue = { t, p -> inner.queue(t, p) },
            innerRelease = { inner.release(it) },
            innerFlush = { inner.flush() },
            handOn = { t, p -> down.onOutput(t, p) },
            handBack = { up.onProcessed(it) },
            credit = { up.onReady() }
        )
        override fun queue(t: Tex, pts: Long) = stage.queue(t, pts)
        override fun release(t: Tex) = stage.release(t)
        override fun flush() = stage.flush()
    }

    inner class Sink(val room: Int) : Node {
        val held = ArrayDeque<Tex>()
        val seen = HashSet<Tex>()
        var lastPts = -1L
        var shown = 0
        lateinit var up: Link
        override fun queue(t: Tex, pts: Long) {
            check("the display was handed $t twice", seen.add(t))
            check("the display was handed $t with no room", held.size < room)
            check("frames reached the display out of order ($pts after $lastPts)", pts > lastPts)
            lastPts = pts
            held.add(t)
            shown++
        }
        fun show() {
            val t = held.removeFirstOrNull() ?: return
            up.onProcessed(t)
            up.onReady()
        }
        override fun release(t: Tex) = error("nothing follows the display")
        override fun flush() {
            held.clear(); up.onFlush(); repeat(room) { up.onReady() }
        }
    }

    val source = Source(sourceRoom)
    val wrapped = List(stages) { Wrapped("pass$it") }
    val sink = Sink(sinkRoom)

    init {
        val nodes: List<Node> = listOf(source) + wrapped + sink
        for (i in 0 until nodes.size - 1) {
            val p = nodes[i]; val c = nodes[i + 1]
            val room = if (c is Sink) sinkRoom else 1
            val link = Link({ p }, { c }, room)
            when (p) { is Source -> p.down = link; is Wrapped -> { p.down = link; p.inner.down = link } }
            when (c) { is Wrapped -> { c.up = link; c.inner.up = link }; is Sink -> c.up = link }
        }
        // setInputListener: each consumer gives its credits.
        wrapped.forEach { it.inner.up.onReady() }
        repeat(sinkRoom) { sink.up.onReady() }
    }

    /** Runs to the end, flushing [flushAt] times along the way. Returns whether it drained. */
    fun run(flushAt: Set<Int>): Boolean {
        var steps = 0
        var idleSteps = 0
        while (steps < 200_000) {
            steps++
            // VideoFrameProcessingTaskExecutor.flush drops the queued tasks first,
            // then the flush runs from the display back to the source.
            // and new input queues behind it on the same thread.
            if (steps in flushAt) { tasks.clear(); sink.flush(); while (tasks.isNotEmpty()) tasks.removeFirst()(); flushes++ }
            val r = rnd.nextDouble()
            when {
                tasks.isNotEmpty() && r < 0.75 -> { tasks.removeFirst()(); idleSteps = 0 }
                sink.held.isNotEmpty() && r < 0.9 -> { sink.show(); idleSteps = 0 }
                else -> {
                    val before = source.made
                    source.pump()
                    if (tasks.isEmpty() && sink.held.isEmpty() && source.made == before) idleSteps++ else idleSteps = 0
                }
            }
            if (idleSteps > 50) return source.made == TOTAL
        }
        return false
    }

    companion object { const val TOTAL = 400 }
}

fun main() {
    var runs = 0
    for (stages in listOf(1, 2, 7)) for (chance in listOf(0.0, 0.5, 0.9, 1.0)) for (srcRoom in listOf(1, 2)) for (sinkRoom in listOf(1, 3)) for (seed in 0 until 6) {
        val label = "$stages stages, idle ${chance}, source room $srcRoom, display room $sinkRoom, seed $seed"
        current = label
        // Without a flush: every frame the source made reaches the display once, in order.
        run {
            val m = Model(seed, stages, chance, srcRoom, sinkRoom)
            val drained = m.run(emptySet())
            runs++
            check("the chain stalled at ${m.sink.shown} of ${Model.TOTAL} frames", drained && m.sink.shown == Model.TOTAL)
            check("the source's textures were not all given back (${m.source.out.size} out)", m.source.out.isEmpty() && m.source.free == srcRoom)
            m.wrapped.forEach { w ->
                check("${w.inner.name} kept a texture out", w.inner.out.isEmpty() && w.inner.free == 1)
                check("${w.inner.name} was sent ${w.stage.outOfTurn} frame(s) it gave no credit for", w.stage.outOfTurn == 0)
            }
        }
        // With flushes - a seek, as a scrub makes dozens of: it still runs to the end
        // and nothing is given back that is not out (the checks inside say so).
        run {
            val m = Model(seed + 100, stages, chance, srcRoom, sinkRoom)
            val drained = m.run(setOf(300, 1_100, 1_101, 2_500))
            runs++
            check("flushed: the chain stalled after a flush at ${m.sink.shown} frames", drained)
            m.wrapped.forEach { w -> check("flushed: ${w.inner.name} was sent ${w.stage.outOfTurn} frame(s) out of turn", w.stage.outOfTurn == 0) }
        }
    }
    // A frame sent while a forwarded one is still out - which no stage given
    // its credits right can be sent - is drawn, not forwarded over the first.
    run {
        current = "out of turn"
        val drawn = mutableListOf<String>(); val on = mutableListOf<String>()
        val s = PassStage<String>(
            idle = { true }, innerQueue = { t, _ -> drawn += t }, innerRelease = {}, innerFlush = {},
            handOn = { t, _ -> on += t }, handBack = {}, credit = {}
        )
        s.queue("a", 0); s.queue("b", 1)
        check("a second frame forwarded over one still out: on=$on drawn=$drawn", on == listOf("a") && drawn == listOf("b") && s.outOfTurn == 1)
    }
    // And the point of it: an idle pass draws nothing.
    run {
        val m = Model(1, 7, 1.0, 1, 3)
        m.run(emptySet())
        check("idle passes drew ${m.wrapped.sumOf { it.inner.made }} frames", m.wrapped.all { it.inner.made == 0 })
        val busy = Model(1, 7, 0.0, 1, 3)
        busy.run(emptySet())
        check("busy passes did not draw every frame", busy.wrapped.all { it.inner.made == Model.TOTAL })
    }

    println("passstage: a preview pass at its identity hands frames on without drawing ($runs runs of the chain)")
    if (failures.isEmpty()) println("PASS - every frame reaches the display once and in order, every texture comes back once, through flushes")
    else { println("FAIL (${failures.size})"); failures.forEach { println("  - $it") }; exitProcess(1) }
}
