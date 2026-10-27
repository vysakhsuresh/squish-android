package com.squish.app

import android.app.Application

class SquishApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Debug builds name every disk or network touch on the main thread in
        // logcat ("StrictMode", with its stack), and do nothing else: EDGE_PLAN
        // C7 asks that no tap waits on work, and this is how a tap that reads a
        // file before it answers shows itself. Never in a release build.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
        }
        // Off the main thread and at low priority, the two costs that used to
        // land on someone's first tap: loading Squish's FFmpeg (a 17 MB
        // library, mapped and linked the first time anything read a file
        // through it) and, after an update, remaking every sound effect on
        // disk before the one asked for (MusicLibrary.warmUp).
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // How many overlay rows take footage, from the decoder's own figure (EDGE_PLAN C4).
            com.squish.app.timeline.FootageRows.value =
                com.squish.app.media.DecoderBudget.footageRows(com.squish.app.media.DecoderCapacity.blocksPerSecond)
            com.squish.app.av.AvNative.loaded
            runCatching { com.squish.app.media.audio.MusicLibrary.warmUp(this) }
        }, "squish-warmup").start()
    }
}
