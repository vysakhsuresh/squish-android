package com.squish.app.av

import android.os.SystemClock

/**
 * Timings for the moments a person waits on - a project opening, its first
 * frame, a waveform, a download - logged under "SquishPerf" so a change that
 * makes one slower is seen on the phone rather than felt:
 *
 *   adb logcat -s SquishPerf
 *
 * [start] names a span; [end] logs how long it took. Cheap enough to leave in.
 */
object Perf {
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start(name: String) {
        starts[name] = SystemClock.elapsedRealtime()
    }

    /** Logs [name]'s time since its start, once; nothing if it was never started. */
    fun end(name: String, detail: String = "") {
        val at = starts.remove(name) ?: return
        android.util.Log.i("SquishPerf", "$name ${SystemClock.elapsedRealtime() - at} ms $detail".trimEnd())
    }

    /** Logs a time measured elsewhere. */
    fun took(name: String, ms: Long, detail: String = "") {
        android.util.Log.i("SquishPerf", "$name $ms ms $detail".trimEnd())
    }
}
