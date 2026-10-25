package com.squish.app.debug

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Taps and swipes from adb, for a phone that refuses `adb shell input`
 * (debug builds only). MIUI will not let the shell inject touches without a
 * Mi account and a SIM ("Injecting to another application requires
 * INJECT_EVENTS"); an app may inject into its own windows - sheets and
 * dialogs included - without that permission, so the app does it for itself:
 *
 *   adb shell am broadcast -a com.squish.app.debug.TAP --ei x 540 --ei y 1200
 *   adb shell am broadcast -a com.squish.app.debug.SWIPE --ei x1 900 --ei y1 1200 --ei x2 200 --ei y2 1200 --ei ms 400
 *
 * Screen pixels, as `input tap` takes them. Attached by MainActivity through
 * reflection, so a release build, which has no such class, has none of this.
 */
object DebugTouch {
    private const val TAP = "com.squish.app.debug.TAP"
    private const val SWIPE = "com.squish.app.debug.SWIPE"
    private val worker = Executors.newSingleThreadExecutor()
    private var receiver: BroadcastReceiver? = null

    @JvmStatic
    fun attach(activity: Activity) {
        if (receiver != null) return
        val context = activity.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    TAP -> {
                        val x = intent.getIntExtra("x", -1).toFloat(); val y = intent.getIntExtra("y", -1).toFloat()
                        worker.execute { gesture(listOf(x to y), 60L) }
                    }
                    SWIPE -> {
                        val x1 = intent.getIntExtra("x1", 0).toFloat(); val y1 = intent.getIntExtra("y1", 0).toFloat()
                        val x2 = intent.getIntExtra("x2", 0).toFloat(); val y2 = intent.getIntExtra("y2", 0).toFloat()
                        val ms = intent.getIntExtra("ms", 300).toLong().coerceAtLeast(16L)
                        val steps = (ms / 16).toInt().coerceAtLeast(2)
                        val path = (0..steps).map { i -> val t = i.toFloat() / steps; (x1 + (x2 - x1) * t) to (y1 + (y2 - y1) * t) }
                        worker.execute { gesture(path, ms) }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter().apply { addAction(TAP); addAction(SWIPE) }, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        Log.i("SquishDebugTouch", "listening for $TAP and $SWIPE")
    }

    /** A finger down at the first point, along the rest over [ms], and up at the last. */
    private fun gesture(points: List<Pair<Float, Float>>, ms: Long) {
        val inst = Instrumentation()
        val down = SystemClock.uptimeMillis()
        val step = if (points.size > 1) ms / (points.size - 1) else ms
        try {
            inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, points.first().first, points.first().second, 0))
            for (i in 1 until points.size) {
                SystemClock.sleep(step)
                val (x, y) = points[i]
                inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0))
            }
            if (points.size == 1) SystemClock.sleep(ms)
            val (x, y) = points.last()
            inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        } catch (t: Throwable) {
            Log.w("SquishDebugTouch", "could not touch", t)
        }
    }
}
