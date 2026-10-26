package com.squish.app.util

/**
 * TEMPORARY (EDGE_PLAN C3 diagnosis): switches read once per process from
 * `debug.squish.<name>` system properties, so one release build can be
 * measured with a part of the editor turned off, and counts of how often each
 * composable runs, logged each second under "SquishCounts" while
 * `debug.squish.counts` is 1. To be removed with the fix.
 */
object PerfFlags {
    private val cache = HashMap<String, Boolean>()
    fun on(name: String): Boolean = synchronized(cache) {
        cache.getOrPut(name) {
            runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.squish.$name") == "1"
            }.getOrDefault(false)
        }
    }

    private val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val counting by lazy {
        on("counts").also { yes ->
            if (yes) Thread({
                while (true) {
                    Thread.sleep(1000)
                    val line = counts.entries.filter { it.value.get() > 0 }.sortedBy { it.key }
                        .joinToString(" ") { "${it.key}=${it.value.getAndSet(0)}" }
                    if (line.isNotEmpty()) android.util.Log.i("SquishCounts", line)
                }
            }, "squish-counts").apply { isDaemon = true }.start()
        }
    }

    fun hit(name: String) {
        if (!counting) return
        counts.getOrPut(name) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }
}
