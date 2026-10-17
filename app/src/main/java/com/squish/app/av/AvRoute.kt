package com.squish.app.av

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Per file, whose reader opens it (AvRouteRules says why there is a choice
 * and what the rule is), remembered for the life of the process so a player
 * built for the tenth time does not probe the file a tenth.
 */
object AvRoute {

    /** What was decided for a file, with FFmpeg's view of it when it was opened. */
    data class Decision(val ffmpeg: Boolean, val info: AvInfo?)

    private val decisions = ConcurrentHashMap<String, Decision>()

    private val MEDIA3 = Decision(ffmpeg = false, info = null)

    /** The decision if it has been made, without making it. */
    fun cached(uri: Uri): Decision? = decisions[uri.toString()]

    /**
     * Whether [uri] is read by Squish's FFmpeg (true) or by Media3 (false).
     * Blocking: it may open the file. Called from a player's loading thread
     * (AvExtractorsFactory) and from the import probe.
     */
    fun ffmpeg(context: Context, uri: Uri): Boolean = decide(context, uri).ffmpeg

    fun decide(context: Context, uri: Uri): Decision {
        decisions[uri.toString()]?.let { return it }
        val made = make(context, uri)
        decisions[uri.toString()] = made
        return made
    }

    /** For a file the app wrote itself, or before the library is known to load: Media3, without looking. */
    private fun ownFile(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return path.startsWith(context.filesDir.absolutePath) || path.startsWith(context.cacheDir.absolutePath)
    }

    private fun make(context: Context, uri: Uri): Decision {
        if (!AvNative.loaded || ownFile(context, uri)) return MEDIA3
        val info = AvFile.probe(context, uri) ?: return MEDIA3
        val streams = info.streams
            .filter { (it.isVideo || it.isAudio) && it.decodable }
            .map { AvRouteRules.Stream(it.isVideo, it.codec, it.tag) }
        // Nothing FFmpeg can decode either: leave it to Media3, whose failure
        // the app already knows how to put into words.
        if (streams.isEmpty()) return MEDIA3
        val packet = if (info.format == "mpegts") tsPacketSize(context, uri) else 188
        val ffmpeg = !AvRouteRules.media3Reads(info.format, streams, packet)
        if (ffmpeg) {
            android.util.Log.i(
                "SquishAv",
                "reading $uri with FFmpeg: ${info.format}, " + info.streams.joinToString { "${it.type}:${it.codec}" }
            )
        }
        return Decision(ffmpeg, info)
    }

    private fun tsPacketSize(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(1024)
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
            AvRouteRules.tsPacketSize(head.copyOf(n))
        } ?: 188
    }.getOrDefault(188)

    /** Forgets [uri] - a file relinked or replaced at the same address. */
    fun forget(uri: Uri) {
        decisions.remove(uri.toString())
    }
}
