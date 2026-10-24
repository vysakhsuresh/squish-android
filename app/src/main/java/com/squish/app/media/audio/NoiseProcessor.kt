@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reduce noise (NoiseReducer, EDGE_PLAN G2) in Media3's audio chain, on 16-bit
 * PCM, any channel count. The same class in a preview player's sink and in the
 * export's processors, so what is heard is what is written.
 *
 * [strengthNow] is read on every buffer: a preview player keeps one of these
 * for every clip it plays and the clip's setting is written into it. At 0 the
 * sound passes through as it came, byte for byte. Turned on, the reducer
 * starts from nothing heard; turned off, what it holds back is let out before
 * the sound passes through again, so the stream never skips or repeats.
 */
class NoiseProcessor(private val strengthNow: () -> Float) : BaseAudioProcessor() {

    private var reducer: NoiseReducer? = null
    private var channels = 2
    private var running = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        reducer = NoiseReducer(inputAudioFormat.sampleRate, channels)
        running = false
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val r = reducer ?: return passThrough(inputBuffer, ByteArray(0))
        val strength = strengthNow().coerceIn(0f, 1f)
        if (!running && strength <= 0f) return passThrough(inputBuffer, ByteArray(0))
        if (!running) {
            r.reset()
            running = true
        }
        if (strength <= 0f) {
            // Off: the held tail first, then the sound as it came.
            val tail = toBytes(r.drain())
            running = false
            return passThrough(inputBuffer, tail)
        }
        r.strength = strength
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val samples = FloatArray(remaining / 2)
        for (i in samples.indices) samples[i] = input.short / 32768f
        write(toBytes(r.process(samples)))
    }

    override fun onQueueEndOfStream() {
        val r = reducer ?: return
        if (!running) return
        running = false
        val tail = r.drain()
        if (tail.isNotEmpty()) write(toBytes(tail))
    }

    override fun onFlush() {
        // After a seek the sound before it is not the noise after it.
        reducer?.reset()
        running = false
    }

    override fun onReset() {
        reducer = null
        running = false
    }

    private fun passThrough(inputBuffer: ByteBuffer, before: ByteArray) {
        val out = replaceOutputBuffer(before.size + inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        out.put(before)
        out.put(inputBuffer)
        out.flip()
    }

    private fun write(bytes: ByteArray) {
        val out = replaceOutputBuffer(bytes.size).order(ByteOrder.nativeOrder())
        out.put(bytes)
        out.flip()
    }

    private fun toBytes(samples: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }
}
