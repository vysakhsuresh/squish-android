package com.squish.app.av

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * The JNI surface of libsquishav.so - Squish's own build of FFmpeg, with
 * every demuxer and decoder it has, plus dav1d, libjxl and LibRaw
 * (app/src/main/cpp, built by tools/native/build.sh). Handles are native
 * pointers as longs; each is used from one thread at a time, which the
 * Kotlin wrappers (AvFile, AvDecoder, AvRaw) arrange.
 *
 * Everything here is raw: the wrappers are the API.
 */
internal object AvNative {

    const val OK = 0
    const val AGAIN = 1
    const val EOF = 2
    const val SKIPPED = 3

    /** Whether a handle from fileOpen or decOpen is a failure (AvHandles says why it is not "<= 0"). */
    fun failed(handle: Long): Boolean = AvHandles.failed(handle)

    /** Whether the library loaded on this phone. False on an ABI it was not built for, or a missing file. */
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("squishav") }
            .onFailure { android.util.Log.w("SquishAv", "libsquishav did not load", it) }
            .isSuccess
    }

    @JvmStatic external fun version(): String
    @JvmStatic external fun counts(): IntArray
    @JvmStatic external fun hasDecoder(codec: String): Boolean

    @JvmStatic external fun fileOpen(fd: Int, extension: String?): Long
    @JvmStatic external fun fileClose(file: Long)
    @JvmStatic external fun fileInfo(file: Long): ByteArray?
    @JvmStatic external fun fileBestStream(file: Long, type: Int): Int
    @JvmStatic external fun fileStreamParams(file: Long, stream: Int): ByteArray?
    @JvmStatic external fun fileReadPacket(file: Long, meta: LongArray): Int
    @JvmStatic external fun filePacketData(file: Long, into: ByteArray, offset: Int): Int
    @JvmStatic external fun fileSeek(file: Long, timeUs: Long): Int
    @JvmStatic external fun fileIndexPosition(file: Long, stream: Int, timeUs: Long): Long
    @JvmStatic external fun fileDecodeNext(file: Long, decoder: Long, stream: Int, meta: LongArray): Int
    @JvmStatic external fun fileSeekDecoder(file: Long, decoder: Long, timeUs: Long): Int

    @JvmStatic external fun decOpen(params: ByteArray, threads: Int): Long
    @JvmStatic external fun decClose(decoder: Long)
    @JvmStatic external fun decFlush(decoder: Long)
    @JvmStatic external fun decName(decoder: Long): String
    @JvmStatic external fun decSend(decoder: Long, data: ByteBuffer?, offset: Int, size: Int, ptsUs: Long, key: Boolean): Int
    @JvmStatic external fun decReceive(decoder: Long, meta: LongArray): Int
    @JvmStatic external fun decTakeFrame(decoder: Long): Long
    @JvmStatic external fun frameFree(frame: Long)
    @JvmStatic external fun decFrameToYuv(
        decoder: Long, frame: Long,
        y: ByteBuffer, yRow: Int, u: ByteBuffer, uRow: Int, uPixel: Int, v: ByteBuffer, vRow: Int, vPixel: Int
    ): Int
    @JvmStatic external fun decFrameToRgbaBuffer(decoder: Long, frame: Long, into: ByteBuffer, rowStride: Int, width: Int, height: Int): Int
    @JvmStatic external fun decFrameToBitmap(decoder: Long, frame: Long, bitmap: Bitmap): Int
    @JvmStatic external fun decSetToneMap(decoder: Long, tables: FloatArray?): Int
    @JvmStatic external fun decFrameToGray(decoder: Long, frame: Long, into: ByteArray, width: Int, height: Int): Int
    @JvmStatic external fun decFrameToPcm16(decoder: Long, frame: Long, into: ByteBuffer, offset: Int, capacity: Int): Int

    @JvmStatic external fun rawOpen(fd: Int): Long
    @JvmStatic external fun rawInfo(raw: Long): IntArray
    @JvmStatic external fun rawThumbJpeg(raw: Long): ByteArray?
    @JvmStatic external fun rawDevelopedSize(raw: Long, half: Boolean): IntArray
    @JvmStatic external fun rawDevelop(raw: Long, bitmap: Bitmap, half: Boolean): Int
    @JvmStatic external fun rawClose(raw: Long)
}
