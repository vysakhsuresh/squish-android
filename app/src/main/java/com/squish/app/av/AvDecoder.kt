package com.squish.app.av

import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * One of Squish's FFmpeg decoders: packets in, frames out. Opened from a
 * parameter blob - the stream's own (AvFile.streamParams) or one written for
 * a Media3 format (AvParams). Times in and out are microseconds.
 *
 * Unlike the decoders the app used to borrow from nextlib, the end of the
 * stream is a real drain: [drain] tells FFmpeg there is nothing more and the
 * frames it holds for reordering and threading all come out. Used from one
 * thread at a time.
 */
class AvDecoder private constructor(private var handle: Long) : Closeable {

    /** What [receive] fills in: the frame last taken from the decoder. */
    class Frame {
        internal val meta = LongArray(13)
        val ptsUs: Long get() = meta[0]
        val width: Int get() = meta[1].toInt()
        val height: Int get() = meta[2].toInt()
        /** FFmpeg's AVColorSpace: 1 BT.709, 5/6 BT.601, 9/10 BT.2020. */
        val colorSpace: Int get() = meta[3].toInt()
        /** 2 for full range ("JPEG"), else limited. */
        val colorRange: Int get() = meta[4].toInt()
        /** FFmpeg's AVColorTransferCharacteristic: 16 PQ, 18 HLG. */
        val colorTrc: Int get() = meta[5].toInt()
        val samples: Int get() = meta[6].toInt()
        val sampleRate: Int get() = meta[7].toInt()
        /** Channels the PCM comes out with: the frame's own, or two past eight. */
        val outChannels: Int get() = meta[8].toInt()
        val key: Boolean get() = meta[9] != 0L
        val durationUs: Long get() = meta[10]
        val pixelFormat: Int get() = meta[11].toInt()
        /** An RGB or palette picture, which goes to a surface as limited-range YUV (squishav.c). */
        val rgb: Boolean get() = meta[12] != 0L
    }

    val name: String by lazy { AvNative.decName(nativeHandle) }

    internal val nativeHandle: Long get() = handle.also { check(it != 0L) { "closed" } }

    /**
     * Sends [size] bytes of [data] (a direct buffer) from [offset] as one
     * packet. [AvNative.OK]; [AvNative.AGAIN] when a frame must be taken first
     * (send the same packet again after); [AvNative.SKIPPED] for a packet the
     * decoder refused as broken; [AvNative.EOF] after a drain.
     */
    fun send(data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, key: Boolean): Int {
        val r = AvNative.decSend(nativeHandle, data, offset, size, ptsUs, key)
        if (r < 0) throw AvException("decoding with $name", r)
        return r
    }

    /** Nothing more is coming: every frame still held will come out of [receive], then EOF. */
    fun drain() {
        val r = AvNative.decSend(nativeHandle, null, 0, -1, 0L, false)
        if (r < 0 && r != AvNative.EOF) throw AvException("draining $name", r)
    }

    /** The next frame into [frame]: OK, AGAIN or EOF. */
    fun receive(frame: Frame): Int {
        val r = AvNative.decReceive(nativeHandle, frame.meta)
        if (r < 0) throw AvException("decoding with $name", r)
        return r
    }

    /** The frame last received, as a handle of its own (a reference, not a copy). */
    fun takeFrame(): Long = AvNative.decTakeFrame(nativeHandle)

    fun flush() = AvNative.decFlush(nativeHandle)

    /** Into an ARGB_8888 bitmap at the bitmap's size; [held] is a taken frame, or 0 for the last received. */
    fun toBitmap(bitmap: Bitmap, held: Long = 0L): Boolean = AvNative.decFrameToBitmap(nativeHandle, held, bitmap) >= 0

    /** The luma at [width]x[height], full range, into [into]. */
    fun toGray(into: ByteArray, width: Int, height: Int, held: Long = 0L): Boolean =
        AvNative.decFrameToGray(nativeHandle, held, into, width, height) >= 0

    /** Interleaved 16-bit PCM into [into] from [offset]; the bytes written, or a negative error. */
    fun toPcm16(into: ByteBuffer, offset: Int, capacity: Int, held: Long = 0L): Int =
        AvNative.decFrameToPcm16(nativeHandle, held, into, offset, capacity)

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) AvNative.decClose(h)
    }

    companion object {
        /** A decoder for [params]; null when the codec has no decoder here or refuses to open. */
        fun open(params: ByteArray, threads: Int = defaultThreads()): AvDecoder? {
            if (!AvNative.loaded) return null
            val h = AvNative.decOpen(params, threads)
            if (AvNative.failed(h)) {
                android.util.Log.w("SquishAv", "decoder did not open (ffmpeg error $h)")
                return null
            }
            return AvDecoder(h)
        }

        /** A decoder for [stream] of [file]. */
        /**
         * A decoder for one of [file]'s streams, for the readers that make
         * pictures of their own (Reverse, the filmstrip, thumbnails, the
         * analyses): an HLG or PQ stream comes out tone-mapped to BT.709 SDR
         * (ToneMap), which written as it was looked flat and grey. The players
         * and the export open theirs with [open] and hand HDR on as HDR.
         */
        fun forStream(file: AvFile, stream: Int, threads: Int = defaultThreads(), toSdr: Boolean = true): AvDecoder? {
            val decoder = open(file.streamParams(stream), threads) ?: return null
            val trc = file.info.streams.firstOrNull { it.index == stream }?.colorTrc ?: 2
            // runCatching: a library older than this function has no tone map, and pictures come as they did.
            if (toSdr) ToneMap.cached(trc)?.let { t -> runCatching { AvNative.decSetToneMap(decoder.nativeHandle, t) } }
            return decoder
        }

        /**
         * FFmpeg's frame threads hold about a frame each, which a drain gives
         * back; past six the gain on a phone is small and the memory is not.
         */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

        fun freeFrame(held: Long) {
            if (held != 0L) AvNative.frameFree(held)
        }
    }
}
