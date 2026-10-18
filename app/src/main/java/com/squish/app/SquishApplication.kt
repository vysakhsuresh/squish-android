package com.squish.app

import android.app.Application

class SquishApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Off the main thread and at low priority, the two costs that used to
        // land on someone's first tap: loading Squish's FFmpeg (a 17 MB
        // library, mapped and linked the first time anything read a file
        // through it) and, after an update, remaking every sound effect on
        // disk before the one asked for (MusicLibrary.warmUp).
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            com.squish.app.av.AvNative.loaded
            runCatching { com.squish.app.media.audio.MusicLibrary.warmUp(this) }
        }, "squish-warmup").start()
    }
}
