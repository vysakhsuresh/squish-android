package com.squish.app.media.audio

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Waveforms kept on disk under cache/waveforms, so a sound is decoded for
 * its picture once rather than on every editor open (PcmDecoder.decodePeaks).
 *
 * Keyed by the file's address, its size, its modification time where a
 * plain file has one, and the two numbers the peaks were taken at - so a
 * file replaced at the same address, or a peak size changed in code, is
 * decoded afresh. A few hundred kilobytes for an hour of sound, a few for a
 * song; the oldest go past [KEEP] files, and the storage card's cache clear
 * takes them all, as it takes everything under cache/.
 */
internal object WaveformCache {

    private const val VERSION = 1
    private const val KEEP = 300

    private fun dir(context: Context): File = File(context.cacheDir, "waveforms").apply { mkdirs() }

    /** The key for [uri] with these peaks, or null when the file cannot even be measured. */
    fun key(context: Context, uri: Uri, bucketMs: Int, maxDurationMs: Long): String? = runCatching {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        val modified = if (uri.scheme == "file") uri.path?.let { File(it).lastModified() } ?: 0L else 0L
        val text = "$VERSION|$uri|$size|$modified|$bucketMs|$maxDurationMs"
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }.getOrNull()

    fun read(context: Context, key: String): Waveform? = runCatching {
        val file = File(dir(context), "$key.wf")
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            val durationMs = input.readLong()
            val count = input.readInt()
            if (count < 0 || count > 10_000_000) return null
            val peaks = FloatArray(count) { input.readFloat() }
            file.setLastModified(System.currentTimeMillis())
            Waveform(peaks, durationMs)
        }
    }.getOrNull()

    fun write(context: Context, key: String, wave: Waveform) {
        runCatching {
            val target = File(dir(context), "$key.wf")
            val part = File(target.absolutePath + ".part")
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeLong(wave.durationMs)
                out.writeInt(wave.peaks.size)
                for (v in wave.peaks) out.writeFloat(v)
            }
            if (!part.renameTo(target)) part.delete()
            prune(context)
        }
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".wf") } ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }
}
