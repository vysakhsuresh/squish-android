package com.squish.app.util

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two threads at Android's background priority, for work nobody is waiting
 * on at that moment: the Squish originals made ahead, a waveform decoded
 * whole. On Dispatchers.Default they ran at the preview's own priority and
 * took the cores its decoders and audio sink wanted, on exactly the first
 * play after a song was added or the Music panel opened. At background
 * priority they still finish fast on an idle phone and give way to playback
 * on a busy one.
 */
object BackgroundWork {
    private val count = AtomicInteger()

    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "SquishBackground-${count.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
