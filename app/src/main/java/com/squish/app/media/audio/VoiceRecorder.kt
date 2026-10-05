package com.squish.app.media.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

/**
 * The mic to a WAV file, for a voiceover taken over the timeline.
 *
 * Pure Android framework (AudioRecord), 16-bit mono at 44.1 kHz - the one
 * rate every phone's mic path takes - with the platform's noise suppressor
 * and gain control on the capture where the phone has them, so a take in a
 * room reads as a voice rather than a room. Written as it is heard, straight
 * into the file with its header patched on stop, so a take the app is killed
 * under is still a readable file up to the last buffer.
 *
 * The caller holds the RECORD_AUDIO permission; without it [start] fails
 * rather than throws.
 *
 * [start] and [stop] take turns: the editor stops a take from the thread that
 * is clearing it while the coroutine that opened the mic may still be on its
 * IO thread, and a stop that ran between the two halves of a start left the
 * mic open with nobody holding it.
 */
class VoiceRecorder {

    /** A finished take: the file, and how long it runs. */
    class Take(val file: File, val durationMs: Long)

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var out: RandomAccessFile? = null
    private var file: File? = null
    @Volatile private var running = false
    @Volatile private var framesWritten = 0L

    /** The loudest sample of the last buffer, 0..1, for a meter. */
    @Volatile var level: Float = 0f
        private set

    /** How much has been heard so far: the take's own clock, which runs whether or not the picture does. */
    val recordedMs: Long get() = framesWritten * 1000L / SAMPLE_RATE

    private var suppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    /**
     * Starts listening into a new file under [dir]. False when the mic could
     * not be opened.
     *
     * Declared as needing the permission rather than checking for it here: the
     * editor asks before the count-in and this says so to a caller and to lint,
     * which otherwise reports the AudioRecord below as a call that may be
     * refused. Refused anyway - revoked between the ask and the take - it
     * throws a SecurityException, which the catch takes with everything else
     * and turns into the false this promises.
     */
    @Synchronized
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(dir: File): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                // Well ahead of what one read takes: a stall on the UI thread
                // must not cost a buffer of someone's sentence.
                maxOf(minBuffer * 4, SAMPLE_RATE * 2)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "no AudioRecord", t)
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        // The platform's own cleaners, on the capture session: they cost
        // nothing when the phone lacks them (create returns null).
        runCatching { if (NoiseSuppressor.isAvailable()) suppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }
        runCatching { if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true } }

        dir.mkdirs()
        val target = File(dir, "take-${UUID.randomUUID()}.wav")
        val raf = try {
            RandomAccessFile(target, "rw").also { writeHeader(it, 0) }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot write the take", t)
            releaseEffects()
            recorder.release()
            return false
        }

        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording failed", t)
            runCatching { raf.close() }
            target.delete()
            releaseEffects()
            recorder.release()
            return false
        }
        record = recorder
        out = raf
        file = target
        framesWritten = 0L
        level = 0f
        running = true
        thread = Thread({ pump(recorder, raf) }, "squish-voice").also { it.start() }
        return true
    }

    private fun pump(recorder: AudioRecord, raf: RandomAccessFile) {
        val buffer = ByteArray(SAMPLE_RATE / 10 * 2)
        val shorts = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val n = recorder.read(buffer, 0, buffer.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            var peak = 0
            shorts.clear()
            val count = n / 2
            for (i in 0 until count) peak = maxOf(peak, abs(shorts.getShort(i * 2).toInt()))
            level = (peak / 32768f).coerceIn(0f, 1f)
            try {
                raf.write(buffer, 0, n)
            } catch (t: Throwable) {
                Log.w(TAG, "write failed mid-take", t)
                break
            }
            framesWritten += count
        }
    }

    /**
     * Stops listening and finishes the file. Null when nothing was recorded -
     * the mic never delivered a buffer - and the file is removed. A second
     * stop finds nothing running and returns null: the take went to the first.
     */
    @Synchronized
    fun stop(): Take? {
        if (!running && record == null) return null
        running = false
        val recorder = record
        record = null
        runCatching { recorder?.stop() }
        thread?.join(2_000)
        thread = null
        releaseEffects()
        runCatching { recorder?.release() }
        val raf = out
        out = null
        val target = file
        file = null
        val frames = framesWritten
        if (raf != null) {
            runCatching {
                raf.seek(0)
                writeHeader(raf, frames * 2)
            }
            runCatching { raf.close() }
        }
        if (target == null) return null
        if (frames < SAMPLE_RATE / 20) {
            target.delete()
            return null
        }
        return Take(target, frames * 1000L / SAMPLE_RATE)
    }

    private fun releaseEffects() {
        runCatching { suppressor?.release() }
        runCatching { gainControl?.release() }
        suppressor = null
        gainControl = null
    }

    private fun writeHeader(raf: RandomAccessFile, dataBytes: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        raf.write(header.array())
    }

    companion object {
        private const val TAG = "SquishVoice"
        const val SAMPLE_RATE = 44_100

        /** Where takes live: their own folder, which is how the strip knows a take from a song. */
        fun dir(context: Context): File = File(context.filesDir, "voice")
    }
}
