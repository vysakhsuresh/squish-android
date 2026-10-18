package com.squish.app.editor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The editor preview's claim on the phone's sound, as any player of media
 * makes one. The preview's players asked for nothing, so a song in another
 * app played on mixed under the edit, a call's ringtone rang over it, and
 * pulling the headphones out carried it on out of the speaker. Now playing
 * takes the focus, and [onLost] (the preview's pause) runs when another
 * player takes it, a call comes in, or the headphones come out.
 *
 * A request that is refused still plays: refusing play outright would leave
 * a Play button that does nothing for a reason nobody can see.
 */
internal class PlaybackFocus(context: Context, private val onLost: () -> Unit) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var held = false
    private var listening = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        // A notification's duck is the system's to do; a loss is ours to pause on.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onLost()
            }
        }, Handler(Looper.getMainLooper()))
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onLost()
        }
    }

    /** Called as playing starts. */
    fun take() {
        if (!held) {
            val r = runCatching { audio?.requestAudioFocus(request) }.getOrNull()
            held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!held) Log.i("SquishPreview", "audio focus not granted ($r); playing anyway")
        }
        if (!listening) {
            listening = runCatching {
                ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            }.isSuccess
        }
    }

    /** Called as playing stops, and when the preview goes. */
    fun drop() {
        if (held) {
            runCatching { audio?.abandonAudioFocusRequest(request) }
            held = false
        }
        if (listening) {
            runCatching { app.unregisterReceiver(noisy) }
            listening = false
        }
    }
}
