@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Whether this phone can actually decode a file's picture and sound.
 *
 * A file opening is not the same as a file playing. An MKV of a film rip -
 * HEVC 10-bit video, DTS or TrueHD sound - reads its length and track list
 * perfectly well, and then no decoder on the phone will take its frames. The
 * preview sat black with the clock running and nothing said why, and the export
 * only failed once it was started.
 *
 * So each file's tracks are read the way the player reads them, and every
 * decoder on the device is asked about that exact format - profile and level
 * included, which is what separates the HEVC this phone plays from the 10-bit
 * HEVC it cannot. Checked once per file in the background and remembered, so
 * the export's preflight can read the answer without waiting on it.
 */
object MediaCompat {

    /**
     * What cannot be decoded, in words, or null for each part that is fine; and
     * whether the file has any sound at all, for what a file with none needs no
     * room for (EditorUiState.hasAnyAudio). [sampleRateHz] is the highest rate
     * of a sound track this phone decodes, or zero when there is none or the
     * file does not say - for the rate a layered export mixes at
     * (ExportPlan.mixerSampleRate).
     */
    data class Report(val videoProblem: String?, val audioProblem: String?, val hasAudio: Boolean = true, val sampleRateHz: Int = 0)

    private val reports = ConcurrentHashMap<String, Report>()

    /** The answer for [uri] if it has been worked out, without waiting for it. */
    fun cached(uri: Uri): Report? = reports[uri.toString()]

    /**
     * Reads [uri]'s tracks and checks them against this phone's decoders.
     *
     * Null when the file could not be read at all - that is a different failure,
     * already reported by the code that opens it. A part counts as a problem only
     * when none of its tracks will decode: an MKV with a DTS track and an AAC one
     * plays the AAC, exactly as the player itself would choose.
     */
    suspend fun check(context: Context, uri: Uri): Report? {
        cached(uri)?.let { return it }
        val groups = withContext(Dispatchers.IO) {
            runCatching {
                // An instance per file since Media3 1.8, closed when done with, and
                // in its own module (media3-inspector) rather than in ExoPlayer.
                MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
                    retriever.retrieveTrackGroups().get(TIMEOUT_S, TimeUnit.SECONDS)
                }
            }.getOrNull()
        } ?: return null

        val video = mutableListOf<Format>()
        val audio = mutableListOf<Format>()
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (t in 0 until group.length) {
                val format = group.getFormat(t)
                val mime = format.sampleMimeType ?: continue
                when {
                    MimeTypes.isVideo(mime) -> video += format
                    MimeTypes.isAudio(mime) -> audio += format
                }
            }
        }
        val report = Report(
            videoProblem = if (video.isNotEmpty() && video.none { decodable(context, it) }) describe(video.first()) else null,
            audioProblem = if (audio.isNotEmpty() && audio.none { decodable(context, it) }) describe(audio.first()) else null,
            hasAudio = audio.isNotEmpty(),
            sampleRateHz = audio.filter { decodable(context, it) }.maxOfOrNull { it.sampleRate }?.coerceAtLeast(0) ?: 0
        )
        reports[uri.toString()] = report
        return report
    }

    private fun decodable(context: Context, format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        // Already samples; the sink plays them without a decoder.
        if (mime == MimeTypes.AUDIO_RAW) return true
        val decoders = runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false) }.getOrDefault(emptyList())
        return decoders.any { runCatching { it.isFormatSupported(context, format) }.getOrDefault(false) }
    }

    /** The format as someone holding the file would know it - the name on a release, not a MIME type. */
    private fun describe(format: Format): String {
        val codecs = format.codecs.orEmpty()
        return when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 ->
                if (codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2")) "HEVC 10-bit (H.265 Main 10)"
                else "HEVC (H.265)"
            MimeTypes.VIDEO_H264 ->
                if (codecs.startsWith("avc1.6E", ignoreCase = true)) "H.264 10-bit (High 10)" else "H.264"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_VP8 -> "VP8"
            MimeTypes.VIDEO_MP4V -> "MPEG-4 Part 2 (DivX / Xvid)"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_VC1 -> "VC-1"
            // What the Matroska reader reports for an old Video for Windows codec it
            // cannot name - Microsoft MPEG-4, DivX 3 and their like.
            MimeTypes.VIDEO_UNKNOWN -> "an old Windows video codec (such as MS-MPEG4 or DivX 3)"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus (E-AC-3)"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            else -> format.sampleMimeType ?: "an unknown format"
        }
    }

    private const val TIMEOUT_S = 10L
}
