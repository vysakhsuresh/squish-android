@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.squish.app.editor.EditorUiState
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

    class FileUnreadable(cause: Throwable? = null, val name: String? = null) : SquishError(
        title = if (name != null) "Can't open “$name”" else "Can't open that file",
        detail = "The file moved, was deleted, or the app lost permission to read it since you picked it.",
        fix = if (name != null) "Delete that clip from the timeline and add it again from the gallery."
        else "Pick the clip again from the gallery.",
        cause = cause
    )

    /**
     * A sound on the timeline that can no longer be read. Named, because "that
     * file" sent people looking at their video clips while the song was the one
     * whose permission had lapsed.
     */
    class SoundUnreadable(val name: String) : SquishError(
        title = "Can't open the sound “$name”",
        detail = "The file moved, was deleted, or the app lost permission to read it since you added it. " +
            "Nothing was rendered.",
        fix = "Delete that sound from the timeline and add it again, or remove it to export without it."
    )

    class SoundUnsupported(val name: String, val codecHint: String) : SquishError(
        title = "This phone can't play “$name”",
        detail = "The sound is $codecHint, which no decoder on this phone can read, so it can't be mixed into the video.",
        fix = "Convert it to AAC or MP3 on a computer and add it again, or remove it to export without it."
    )

    class StillsPreparing(val count: Int) : SquishError(
        title = "Still preparing ${if (count == 1) "a photo" else "$count photos"}",
        detail = "Photos and blanks are made into clips before they reach the timeline. Exporting now would " +
            "leave out what is still being made.",
        fix = "Wait for “Preparing” to finish, then export."
    )

    /**
     * The failure a photo or a silent clip at the front of a roll used to cause:
     * Media3 meeting a clip with sound after a sequence began without any. Every
     * sequence now carries a sound track from its start, so this should not
     * happen - if it does, the words at least point at what is involved, where
     * "these clips don't fit together" sent people hunting for a wrong frame size.
     */
    class SilentClipInMix(cause: Throwable? = null) : SquishError(
        title = "A clip without sound stopped the export",
        detail = "One of the clips - a photo, a blank or a silent recording - has no sound track, and the " +
            "export could not line it up with the clips that do.",
        fix = "Turn the clips' own sound off under Sound and export again; added music and voiceovers are kept.",
        cause = cause
    )

    /** The compositor refused the layers: transitions, picture-in-picture, or a gap between shots. */
    class LayersFailed(cause: Throwable? = null) : SquishError(
        title = "The layers couldn't be put together",
        detail = "The export stopped while stacking the picture - a transition, an overlay, or the blank " +
            "between two shots. The clips themselves read fine.",
        fix = "Export once without the most recent transition or overlay to find the one it refuses, then " +
            "change or remove it.",
        cause = cause
    )

    /** An HDR clip layered with SDR ones, which the compositor cannot blend. */
    class MixedColourRanges(cause: Throwable? = null) : SquishError(
        title = "HDR and ordinary clips can't be layered",
        detail = "One of the clips is HDR - newer phones record it by default - and a transition or overlay " +
            "needs it drawn together with clips that are not.",
        fix = "Take the transition or overlay off that clip, or convert the HDR clip to SDR first.",
        cause = cause
    )

    class UnsupportedCodec(val codecHint: String?, cause: Throwable? = null) : SquishError(
        title = "This phone can't decode that clip",
        detail = buildString {
            append("The picture is in a format")
            codecHint?.let { append(" - $it -") }
            append(" that no decoder on this phone can read, so it can't be shown or exported. ")
            append("It is the phone, not the file - the same clip opens fine on hardware that supports it.")
        },
        fix = "Convert the clip to H.264 on a computer (HandBrake does it free), or try a different file.",
        cause = cause
    )

    class UnsupportedAudio(val codecHint: String) : SquishError(
        title = "This phone can't play this clip's sound",
        detail = "The sound is $codecHint, which no decoder on this phone can read. The picture edits normally; " +
            "the original sound is left out of the export instead of the export failing.",
        fix = "Add music or a voiceover, or convert the sound to AAC on a computer to keep it."
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
        fix = "Export at 1080p rather than Original, or trim it shorter first."
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
            // A photo still being made into a clip is not on the timeline yet, so
            // an export now would silently leave it out and it would turn up in
            // the edit a moment after the file was written without it.
            if (state.preparingStills > 0) return StillsPreparing(state.preparingStills)

            // Each clip by name, so the one to replace can be found. The source is
            // checked last: with clips on the timeline it may not be in the edit.
            for (clip in state.videoClips) {
                val uri = clip.uri ?: state.sourceUri
                if (!canRead(context, uri)) return FileUnreadable(name = clip.label.takeIf { it.isNotBlank() })
            }
            if (state.videoClips.isEmpty() && !canRead(context, state.sourceUri)) return FileUnreadable()
            // Sounds are read too. They used to be left out, so a song whose grant
            // had lapsed failed minutes into the encode as "Can't open that file".
            if (!state.audioOnly || state.hasSeparateAudio) {
                for (clip in state.audioClips) {
                    val uri = clip.uri ?: continue
                    if (!canRead(context, uri)) return SoundUnreadable(soundName(clip.label))
                    MediaCompat.cached(uri)?.audioProblem?.let { return SoundUnsupported(soundName(clip.label), it) }
                }
            }

            val sources = (state.videoClips.mapNotNull { it.uri } + state.sourceUri).distinct()

            // Answered in the background when each file was opened; only read here.
            // A picture no decoder takes cannot export at all. Sound that none takes
            // is dropped by the export instead - unless sound is all that was asked for.
            val reports = sources.mapNotNull { MediaCompat.cached(it) }
            if (!state.audioOnly) {
                reports.firstNotNullOfOrNull { it.videoProblem }?.let { return UnsupportedCodec(it) }
            } else if (!state.hasSeparateAudio) {
                reports.firstNotNullOfOrNull { it.audioProblem }?.let { return UnsupportedAudio(it) }
            }

            // Measured where the file is written, and for both copies of it: the
            // render lands in the app's exports folder and is then copied whole
            // into the gallery, so for a moment the phone holds it twice.
            val needed = (estimatedBytes * SPACE_HEADROOM).toLong().coerceAtLeast(MIN_SPACE_BYTES)
            val free = freeBytes(exportsDir(context))
            if (free in 1 until needed) return NotEnoughSpace(needed, free)

            val pixels = state.outputResolution.pixels
            if (pixels > HUGE_FRAME_PIXELS && !state.fitToSize) {
                return SourceTooLarge(pixels)
            }
            return null
        }

        /**
         * The edit as it should be exported, given what this phone can decode: a
         * source whose sound no decoder takes has its original sound left out, so
         * the export completes silent rather than failing in the audio pipeline.
         */
        fun exportable(state: EditorUiState): EditorUiState {
            if (state.audioOnly || state.muteOriginal) return state
            val sources = (state.videoClips.mapNotNull { it.uri } + listOfNotNull(state.sourceUri)).distinct()
            val soundless = sources.any { MediaCompat.cached(it)?.audioProblem != null }
            return if (soundless) state.copy(muteOriginal = true) else state
        }

        /**
         * Media3 reports failures as numeric codes grouped by stage. The exact
         * constants are matched where the distinction changes the advice, and the
         * thousands band carries the rest - a band never gets renumbered, so this
         * stays correct across library upgrades.
         */
        fun from(throwable: Throwable?): SquishError {
            if (throwable == null) return Unknown(null)
            // A few failures are only told apart by what Media3 wrote in the
            // message, and they are wrapped - an IllegalStateException inside a
            // playback error inside an ExportException - so the whole chain is read.
            fromMessages(throwable)?.let { return it }
            return when (throwable) {
                is OutOfMemoryError -> OutOfMemory(throwable)
                is ExportException -> fromExport(throwable)
                else -> when {
                    throwable.cause is ExportException -> fromExport(throwable.cause as ExportException)
                    throwable is java.io.FileNotFoundException -> FileUnreadable(throwable)
                    throwable is SecurityException -> FileUnreadable(throwable)
                    else -> Unknown(throwable)
                }
            }
        }

        /**
         * The cases a code alone would misname. "The preceding MediaItem does not
         * contain any track" came through as a runtime-check failure, which read
         * "These clips don't fit together" - about a photo followed by a video, two
         * clips that fit together perfectly well.
         */
        private fun fromMessages(throwable: Throwable): SquishError? {
            var t: Throwable? = throwable
            var depth = 0
            while (t != null && depth < 8) {
                val message = t.message.orEmpty()
                when {
                    message.contains("does not contain any", ignoreCase = true) ||
                        message.contains("ForceAudioTrack", ignoreCase = true) ||
                        message.contains("ForceVideoTrack", ignoreCase = true) -> return SilentClipInMix(throwable)
                    message.contains("ColorInfo", ignoreCase = true) ||
                        message.contains("HDR input is not supported", ignoreCase = true) ->
                        return MixedColourRanges(throwable)
                    message.contains("Gaps", ignoreCase = true) &&
                        message.contains("not supported", ignoreCase = true) -> return LayersFailed(throwable)
                }
                t = t.cause
                depth++
            }
            return null
        }

        /**
         * What cannot be known without opening each sound: whether this phone
         * decodes it. Opening a file was already being done in the background
         * when a clip was picked, but a sound added before that finished, or a
         * draft restored from disk, has no answer yet - and a DTS track found
         * that way fails the encode minutes in, as a generic sound error.
         */
        suspend fun checkSounds(context: Context, state: EditorUiState): SquishError? {
            if (state.audioOnly && !state.hasSeparateAudio) return null
            for (clip in state.audioClips) {
                val uri = clip.uri ?: continue
                val report = MediaCompat.check(context, uri) ?: return SoundUnreadable(soundName(clip.label))
                report.audioProblem?.let { return SoundUnsupported(soundName(clip.label), it) }
            }
            return null
        }

        /** Where exports are written; the space check has to measure that volume. */
        fun exportsDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")

        private fun soundName(label: String): String = label.takeIf { it.isNotBlank() } ?: "sound"

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
            // The folder is made on the first export; its volume is what counts.
            var at: File? = dir
            while (at != null && !at.exists()) at = at.parentFile
            StatFs((at ?: dir).absolutePath).availableBytes
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

        /**
         * Two copies of the estimate - the render and the gallery copy made from
         * it - with a tenth over each for an encoder that overshoots its bitrate.
         * It was 1.6, for one copy, while every export was written twice.
         */
        private const val SPACE_HEADROOM = 2.2
        private const val MIN_SPACE_BYTES = 40L * 1_000_000
        private const val HUGE_FRAME_PIXELS = 8_500_000L // beyond 4K DCI
    }
}
