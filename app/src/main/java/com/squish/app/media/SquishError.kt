@file:OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.Quality
import java.io.File

/**
 * Every failure the user can actually hit, named in their language.
 *
 * The rule here: an editor that says "Export failed" has told you nothing, and a
 * stack trace has told you too much. Each case carries three things - what went
 * wrong, why, and the one action that fixes it - because a failure the user can
 * resolve themselves is not really a failure.
 *
 * Nothing swallows the original throwable; it stays on [cause] for logs while the
 * UI shows the sentences.
 */
sealed class SquishError(
    val title: String,
    val detail: String,
    val fix: String,
    val cause: Throwable? = null
) {

    class FileUnreadable(cause: Throwable? = null) : SquishError(
        title = "Can't open that file",
        detail = "The file moved, was deleted, or the app lost permission to read it since you picked it.",
        fix = "Pick the clip again from the gallery.",
        cause = cause
    )

    class UnsupportedCodec(val codecHint: String?, cause: Throwable? = null) : SquishError(
        title = "This phone can't decode that clip",
        detail = buildString {
            append("The video uses a format")
            codecHint?.let { append(" ($it)") }
            append(" your device has no hardware decoder for. ")
            append("It is the phone, not the file - the same clip opens fine on hardware that supports it.")
        },
        fix = "Convert the clip to H.264 on another device, or try a different file.",
        cause = cause
    )

    class EncoderUnavailable(cause: Throwable? = null) : SquishError(
        title = "No encoder free right now",
        detail = "Android gives out a limited number of hardware encoders. Another app - a camera, a screen recorder, a call - is holding the one Squish needs.",
        fix = "Close other camera or video apps and export again.",
        cause = cause
    )

    class ResolutionTooHigh(cause: Throwable? = null) : SquishError(
        title = "Resolution beyond this encoder",
        detail = "Your device's encoder refused the output size. This usually means an 8K or unusually shaped frame.",
        fix = "Drop the quality preset one step and export again.",
        cause = cause
    )

    class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : SquishError(
        title = "Not enough free space",
        detail = "This export needs about ${formatBytes(neededBytes)} and there is ${formatBytes(freeBytes)} left on the device.",
        fix = "Free up some space, or switch to a smaller quality preset."
    )

    class SourceTooLarge(val pixels: Long) : SquishError(
        title = "That clip is very large",
        detail = "At ${pixels / 1_000_000} megapixels per frame this will decode slowly and may run out of memory mid-export.",
        fix = "Export at High rather than Original, or trim it shorter first."
    )

    class NothingToExport : SquishError(
        title = "Nothing on the timeline",
        detail = "Every clip has been trimmed to zero or deleted, so there are no frames to write.",
        fix = "Add a clip, or widen a trim handle."
    )

    class NoAudioTrack : SquishError(
        title = "This clip has no sound",
        detail = "You asked for an audio-only export, but the source file carries no audio track.",
        fix = "Turn off audio-only, or add a separate audio track first."
    )

    class CaptionsUnreadable : SquishError(
        title = "No captions in that file",
        detail = "The file opened, but nothing in it looked like subtitle timings.",
        fix = "Check it is a .srt file — SubRip, with lines like 00:00:01,000 --> 00:00:04,000."
    )

    class OutOfMemory(cause: Throwable? = null) : SquishError(
        title = "Ran out of memory",
        detail = "Decoding and encoding at this resolution needed more memory than Android would give the app.",
        fix = "Close background apps, lower the quality preset, or export in shorter pieces.",
        cause = cause
    )

    class MixedSources(cause: Throwable? = null) : SquishError(
        title = "These clips don't fit together",
        detail = "The pipeline rejected the composition before it started. That almost always means the clips disagree about something structural — most often frame size or orientation, with a portrait clip and a landscape one in the same merge.",
        fix = "Remove one clip at a time to find the odd one out, or export at a fixed quality rather than Original so everything is scaled to one size.",
        cause = cause
    )

    class FrameProcessingFailed(cause: Throwable? = null) : SquishError(
        title = "The GPU gave up mid-render",
        detail = "A frame went into the effect chain and did not come out. This is the graphics driver refusing something — a shader, an unusual frame size, or simply too much at once.",
        fix = "Turn off the film looks and any mask or green screen, then export again. If that works, add them back one at a time.",
        cause = cause
    )

    class AudioProcessingFailed(cause: Throwable? = null) : SquishError(
        title = "Something went wrong with the sound",
        detail = "The audio pipeline stopped. With several clips joined together this usually means one of them has no audio track, or a sample rate the others do not share.",
        fix = "Mute the clip audio and export again to confirm it is the sound, then replace or remove the odd track.",
        cause = cause
    )

    class MuxingFailed(cause: Throwable? = null) : SquishError(
        title = "Couldn't write the finished file",
        detail = "Everything encoded, and then writing it into an MP4 failed. The frames were fine; the container was not.",
        fix = "Check there is free space, then export again. A shorter export will also tell you whether it is a size limit.",
        cause = cause
    )

    class Unknown(cause: Throwable?) : SquishError(
        title = "Export stopped unexpectedly",
        detail = cause?.message?.takeIf { it.isNotBlank() }
            ?: "The media pipeline stopped without explaining why.",
        fix = "Try once more. If it happens again, change the quality preset - that swaps the encoder path entirely.",
        cause = cause
    )

    companion object {

        /**
         * Checks that can be made *before* burning two minutes of encoding on an
         * export that was never going to finish. Cheap, and it turns a late,
         * confusing failure into an immediate, specific one.
         */
        fun preflight(context: Context, state: EditorUiState, estimatedBytes: Long): SquishError? {
            if (state.sourceUri == null) return FileUnreadable()
            if (state.trimmedDurationMs <= 0L) return NothingToExport()
            if (state.audioOnly && !state.sourceHasAudio && !state.hasSeparateAudio) return NoAudioTrack()

            val sources = (state.videoClips.mapNotNull { it.uri } + state.sourceUri).distinct()
            if (sources.any { !canRead(context, it) }) return FileUnreadable()

            val needed = (estimatedBytes * SPACE_HEADROOM).toLong().coerceAtLeast(MIN_SPACE_BYTES)
            val free = freeBytes(context.filesDir)
            if (free in 1 until needed) return NotEnoughSpace(needed, free)

            val pixels = state.sourceWidth.toLong() * state.sourceHeight.toLong()
            if (pixels > HUGE_FRAME_PIXELS && state.quality == Quality.Original) {
                return SourceTooLarge(pixels)
            }
            return null
        }

        /**
         * Media3 reports failures as numeric codes grouped by stage. The exact
         * constants are matched where the distinction changes the advice, and the
         * thousands band carries the rest - a band never gets renumbered, so this
         * stays correct across library upgrades.
         */
        fun from(throwable: Throwable?): SquishError = when (throwable) {
            null -> Unknown(null)
            is OutOfMemoryError -> OutOfMemory(throwable)
            is ExportException -> fromExport(throwable)
            else -> when {
                throwable.cause is ExportException -> fromExport(throwable.cause as ExportException)
                throwable is java.io.FileNotFoundException -> FileUnreadable(throwable)
                throwable is SecurityException -> FileUnreadable(throwable)
                else -> Unknown(throwable)
            }
        }

        private fun fromExport(e: ExportException): SquishError = when {
            e.cause is OutOfMemoryError -> OutOfMemory(e)
            else -> when (e.errorCode) {
                CODE_IO_FILE_NOT_FOUND, CODE_IO_NO_PERMISSION -> FileUnreadable(e)
                CODE_DECODING_FORMAT_UNSUPPORTED -> UnsupportedCodec(codecHintOf(e), e)
                CODE_ENCODING_FORMAT_UNSUPPORTED -> ResolutionTooHigh(e)
                CODE_ENCODER_INIT_FAILED -> EncoderUnavailable(e)
                // A composition the pipeline refused to start. The commonest
                // reason by far is inputs that disagree with each other.
                CODE_FAILED_RUNTIME_CHECK -> MixedSources(e)
                in BAND_IO -> FileUnreadable(e)
                in BAND_DECODING -> UnsupportedCodec(codecHintOf(e), e)
                in BAND_ENCODING -> EncoderUnavailable(e)
                in BAND_VIDEO_FRAME -> FrameProcessingFailed(e)
                in BAND_AUDIO -> AudioProcessingFailed(e)
                in BAND_MUXING -> MuxingFailed(e)
                else -> Unknown(e)
            }
        }

        /** Pulls a codec name out of the message when Media3 put one there. */
        private fun codecHintOf(e: ExportException): String? {
            val text = (e.message ?: "") + " " + (e.cause?.message ?: "")
            return CODEC_NAMES.firstOrNull { text.contains(it, ignoreCase = true) }
        }

        private fun canRead(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)

        private fun freeBytes(dir: File): Long = runCatching {
            StatFs(dir.absolutePath).availableBytes
        }.getOrDefault(-1L)

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            else -> "%.0f KB".format(bytes / 1_000.0)
        }

        // Media3 ExportException codes, by name, pinned as ints so a library rename
        // can never silently change which message the user reads.
        private const val CODE_IO_FILE_NOT_FOUND = 2005
        private const val CODE_IO_NO_PERMISSION = 2006
        private const val CODE_DECODING_FORMAT_UNSUPPORTED = 3003
        private const val CODE_ENCODER_INIT_FAILED = 4001
        private const val CODE_ENCODING_FORMAT_UNSUPPORTED = 4003
        private const val CODE_FAILED_RUNTIME_CHECK = 1001

        // Four of these bands used to be missing, and everything in them landed on
        // "Export stopped unexpectedly. Try once more." - advice that was no help
        // at all for a merge, which fails in exactly these bands and fails the
        // same way on every retry.
        private val BAND_IO = 2000..2999
        private val BAND_DECODING = 3000..3999
        private val BAND_ENCODING = 4000..4999
        private val BAND_VIDEO_FRAME = 5000..5999
        private val BAND_AUDIO = 6000..6999
        private val BAND_MUXING = 7000..7999

        private val CODEC_NAMES = listOf("HEVC", "H.265", "VP9", "AV1", "Dolby Vision", "ProRes", "MPEG-4")

        private const val SPACE_HEADROOM = 1.6
        private const val MIN_SPACE_BYTES = 40L * 1_000_000
        private const val HUGE_FRAME_PIXELS = 8_500_000L // beyond 4K DCI
    }
}
