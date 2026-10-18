package com.squish.app.av

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.Closeable

/** One stream of a file as FFmpeg reads it. */
data class AvStream(
    val index: Int,
    val type: String,
    /** FFmpeg's codec name: "h264", "prores", "wmv3", "dca"... */
    val codec: String,
    val codecLong: String,
    val profile: String,
    /** Whether this build has a decoder for it. */
    val decodable: Boolean,
    val tag: Long,
    val bitRate: Long,
    val durationUs: Long,
    val startUs: Long,
    val frames: Long,
    val attachedPicture: Boolean,
    val isDefault: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val sarNum: Int = 0,
    val sarDen: Int = 0,
    val fpsNum: Int = 0,
    val fpsDen: Int = 0,
    /** Clockwise quarter turns that stand the picture up, in degrees. */
    val rotation: Int = 0,
    val pixFmt: String = "",
    val bits: Int = 0,
    val alpha: Boolean = false,
    val colorRange: Int = 0,
    val colorPrimaries: Int = 2,
    val colorTrc: Int = 2,
    val colorSpace: Int = 2,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val sampleFmt: String = "",
    val language: String = "",
    val title: String = ""
) {
    val isVideo: Boolean get() = type == "video" && !attachedPicture
    val isAudio: Boolean get() = type == "audio"

    val fps: Float get() = if (fpsNum > 0 && fpsDen > 0) fpsNum.toFloat() / fpsDen else 0f

    /** The picture's width as shown: stored width times its pixel shape. */
    val displayWidth: Int
        get() = if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) Math.round(width * sarNum.toDouble() / sarDen).toInt() else width

    /** FFmpeg's colour enums as AVCOL_*: 18 is ARIB B67 (HLG), 16 SMPTE ST 2084 (PQ). */
    val hdr: Boolean get() = colorTrc == 16 || colorTrc == 18
}

/** A file as FFmpeg reads it: its container and its streams. */
data class AvInfo(
    val format: String,
    val formatLong: String,
    val durationUs: Long,
    val startUs: Long,
    val bitRate: Long,
    val size: Long,
    val seekable: Boolean,
    val streams: List<AvStream>,
    val title: String,
    val creationTime: String
) {
    val video: AvStream? get() = streams.firstOrNull { it.isVideo && it.decodable && it.isDefault } ?: streams.firstOrNull { it.isVideo && it.decodable }
    val audio: AvStream? get() = streams.firstOrNull { it.isAudio && it.decodable && it.isDefault } ?: streams.firstOrNull { it.isAudio && it.decodable }
    val durationMs: Long get() = if (durationUs > 0) durationUs / 1000 else 0L

    companion object {
        fun parse(bytes: ByteArray): AvInfo {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val list = o.getJSONArray("streams")
            val streams = (0 until list.length()).map { i ->
                val s = list.getJSONObject(i)
                AvStream(
                    index = s.getInt("index"),
                    type = s.getString("type"),
                    codec = s.getString("codec"),
                    codecLong = s.optString("codecLong"),
                    profile = s.optString("profile"),
                    decodable = s.optBoolean("decoder"),
                    tag = s.optLong("tag"),
                    bitRate = s.optLong("bitRate"),
                    durationUs = s.optLong("durationUs", -1L),
                    startUs = s.optLong("startUs", 0L),
                    frames = s.optLong("frames"),
                    attachedPicture = s.optBoolean("attachedPic"),
                    isDefault = s.optBoolean("isDefault"),
                    width = s.optInt("width"),
                    height = s.optInt("height"),
                    sarNum = s.optInt("sarNum"),
                    sarDen = s.optInt("sarDen"),
                    fpsNum = s.optInt("fpsNum"),
                    fpsDen = s.optInt("fpsDen"),
                    rotation = s.optInt("rotation"),
                    pixFmt = s.optString("pixFmt"),
                    bits = s.optInt("bits"),
                    alpha = s.optBoolean("alpha"),
                    colorRange = s.optInt("colorRange"),
                    colorPrimaries = s.optInt("colorPrimaries", 2),
                    colorTrc = s.optInt("colorTrc", 2),
                    colorSpace = s.optInt("colorSpace", 2),
                    sampleRate = s.optInt("sampleRate"),
                    channels = s.optInt("channels"),
                    sampleFmt = s.optString("sampleFmt"),
                    language = s.optString("language"),
                    title = s.optString("title")
                )
            }
            return AvInfo(
                format = o.optString("format"),
                formatLong = o.optString("formatLong"),
                durationUs = o.optLong("durationUs", -1L),
                startUs = o.optLong("startUs", 0L),
                bitRate = o.optLong("bitRate"),
                size = o.optLong("size", -1L),
                seekable = o.optBoolean("seekable"),
                streams = streams,
                title = o.optString("title"),
                creationTime = o.optString("creationTime")
            )
        }
    }
}

/**
 * A container opened by Squish's FFmpeg over a file descriptor. Not
 * thread-safe: one thread reads it at a time. Close it when done; the
 * descriptor goes with it.
 */
class AvFile private constructor(private var handle: Long, val info: AvInfo) : Closeable {

    private val meta = LongArray(7)

    /** The last packet read: stream, times in µs on the container's clock, flags, size, byte position. */
    val packetStream: Int get() = meta[0].toInt()
    val packetPtsUs: Long get() = meta[1]
    val packetDtsUs: Long get() = meta[2]
    val packetDurationUs: Long get() = meta[3]
    val packetKey: Boolean get() = meta[4] and 1L != 0L
    val packetDiscard: Boolean get() = meta[4] and 2L != 0L
    val packetSize: Int get() = meta[5].toInt()
    val packetPosition: Long get() = meta[6]

    internal val nativeHandle: Long get() = handle.also { check(it != 0L) { "closed" } }

    /** Reads the next packet; false at the end of the file. Throws on a read error. */
    fun readPacket(): Boolean {
        val r = AvNative.fileReadPacket(nativeHandle, meta)
        if (r == AvNative.EOF) return false
        if (r < 0) throw AvException("reading a packet", r)
        return true
    }

    /** Copies the last packet's bytes into [into] at [offset]. */
    fun packetData(into: ByteArray, offset: Int = 0): Int {
        val r = AvNative.filePacketData(nativeHandle, into, offset)
        if (r < 0) throw AvException("copying a packet", r)
        return r
    }

    /** To the key frame at or before [timeUs] on the container's clock. */
    fun seek(timeUs: Long): Boolean = AvNative.fileSeek(nativeHandle, timeUs) >= 0

    /** The byte offset the index has for [stream] at [timeUs], or -1. */
    fun indexPosition(stream: Int, timeUs: Long): Long = AvNative.fileIndexPosition(nativeHandle, stream, timeUs)

    /** The parameter blob a decoder for [stream] is opened from (AvDecoder). */
    fun streamParams(stream: Int): ByteArray =
        AvNative.fileStreamParams(nativeHandle, stream) ?: throw AvException("no parameters for stream $stream", -1)

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) AvNative.fileClose(h)
    }

    companion object {
        /**
         * Opens [uri], or null when nothing FFmpeg has reads it (or the
         * library is not on this phone). [name] is the file's display name if
         * the caller has it; only its extension is used, for the few formats
         * with no signature (Targa, PCX...).
         */
        fun open(context: Context, uri: Uri, name: String? = null): AvFile? {
            if (!AvNative.loaded) return null
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            val fd = pfd.detachFd()
            val extension = (name ?: displayName(context, uri))?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            val handle = AvNative.fileOpen(fd, extension)
            if (AvNative.failed(handle)) return null
            val info = runCatching { AvNative.fileInfo(handle)?.let { AvInfo.parse(it) } }.getOrNull()
            if (info == null) {
                AvNative.fileClose(handle)
                return null
            }
            return AvFile(handle, info)
        }

        /** The info alone, the file closed again. */
        fun probe(context: Context, uri: Uri, name: String? = null): AvInfo? = open(context, uri, name)?.use { it.info }

        fun displayName(context: Context, uri: Uri): String? = when (uri.scheme) {
            "file" -> uri.lastPathSegment
            else -> runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
        }
    }
}

/** A failure from Squish's FFmpeg, with FFmpeg's own error number. */
class AvException(message: String, val code: Int) : java.io.IOException("$message (ffmpeg error $code)")
