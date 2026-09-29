@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.UnstableApi
import com.squish.app.media.RampSpeedProvider
import com.squish.app.timeline.SpeedSegment
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A clip's speed curve on its sound with the pitch held.
 *
 * Media3's own SpeedChangingAudioProcessor drives Sonic with the pitch set to
 * the speed, so the file's sound went up an octave at double speed like a tape
 * - while the preview, playing through ExoPlayer at a pitch of one, kept every
 * voice where it was. This is the same staircase (RampSpeedProvider's segments,
 * the ones the strip and the picture use) walked the same way, each tread fed
 * to Sonic at its own rate with the pitch left alone, so the file keeps pitch
 * exactly as the preview does. Clip.pitchFollowsSpeed chooses Media3's
 * processor instead, for both.
 *
 * The walk is Media3's: input is fed up to the next tread's boundary, Sonic is
 * told the stream ends there and drained, then set to the next rate and flushed
 * for the next tread. At a rate of one Sonic is bypassed, as Media3 does, since
 * it is not built at a rate of one.
 */
class KeepPitchSpeedProcessor(private val segments: List<SpeedSegment>) : AudioProcessor {

    private val sonic = SonicAudioProcessor()
    private val provider = RampSpeedProvider(segments)

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET

    /** Frames taken from the input so far, since the last flush. */
    private var framesRead = 0L
    private var segmentIndex = 0
    private var endOfStreamToSonic = false
    private var inputEnded = false

    /** Output at a rate of one, where Sonic is not in the path. */
    private var passBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var passOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private val currentSpeed: Float get() = segments.getOrNull(segmentIndex)?.speed ?: 1f
    private val usingSonic: Boolean get() = abs(currentSpeed - 1f) >= CLOSE_TO_ONE

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        SpeedProviderUtil.getDurationAfterSpeedProviderApplied(provider, durationUs)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        sonic.setPitch(1f)
        sonic.setSpeed(segments.firstOrNull()?.speed ?: 1f)
        sonic.configure(inputAudioFormat)
        // Sonic keeps the rate and the channels; only the length changes.
        outputFormat = inputAudioFormat
        return outputFormat
    }

    override fun isActive(): Boolean =
        inputFormat.sampleRate != AudioProcessor.AudioFormat.NOT_SET.sampleRate &&
            segments.any { abs(it.speed - 1f) >= CLOSE_TO_ONE }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputFormat.bytesPerFrame
        if (bytesPerFrame <= 0) return
        val limit = inputBuffer.limit()
        // Onto the next tread once this one's frames are all in and drained.
        while (segmentIndex < segments.lastIndex && framesRead >= boundaryFrame(segmentIndex)) {
            advanceSegment()
        }
        val toBoundary = if (segmentIndex < segments.lastIndex) {
            ((boundaryFrame(segmentIndex) - framesRead) * bytesPerFrame).coerceAtLeast(0L)
        } else -1L
        if (toBoundary >= 0L) {
            inputBuffer.limit(minOf(limit.toLong(), inputBuffer.position() + toBoundary).toInt())
        }
        val start = inputBuffer.position()
        if (usingSonic) {
            sonic.queueInput(inputBuffer)
            if (toBoundary >= 0L && (inputBuffer.position() - start).toLong() == toBoundary && !endOfStreamToSonic) {
                // This tread's last sample is in: let Sonic finish it before
                // the rate changes under it.
                sonic.queueEndOfStream()
                endOfStreamToSonic = true
            }
        } else {
            val remaining = inputBuffer.remaining()
            if (remaining > 0) {
                if (passBuffer.capacity() < remaining) {
                    passBuffer = ByteBuffer.allocateDirect(remaining).order(java.nio.ByteOrder.nativeOrder())
                } else {
                    passBuffer.clear()
                }
                passBuffer.put(inputBuffer)
                passBuffer.flip()
                passOutput = passBuffer
            }
        }
        framesRead += (inputBuffer.position() - start) / bytesPerFrame
        inputBuffer.limit(limit)
    }

    override fun queueEndOfStream() {
        inputEnded = true
        if (usingSonic && !endOfStreamToSonic) {
            sonic.queueEndOfStream()
            endOfStreamToSonic = true
        }
    }

    override fun getOutput(): ByteBuffer {
        if (usingSonic) return sonic.output
        val out = passOutput
        passOutput = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && (if (usingSonic) sonic.isEnded else !passOutput.hasRemaining())

    override fun flush() {
        framesRead = 0L
        segmentIndex = 0
        endOfStreamToSonic = false
        inputEnded = false
        passOutput = AudioProcessor.EMPTY_BUFFER
        sonic.setSpeed(currentSpeed)
        // The no-argument flush is the one an implementor must define; Media3's
        // default of it throws, so Sonic is flushed through the other.
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    override fun reset() {
        flush()
        sonic.reset()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        passBuffer = AudioProcessor.EMPTY_BUFFER
    }

    /** The input frame at which tread [index] ends. */
    private fun boundaryFrame(index: Int): Long =
        (segments[index].endMs * inputFormat.sampleRate / 1000.0).roundToLong()

    private fun advanceSegment() {
        segmentIndex++
        endOfStreamToSonic = false
        // A new rate takes effect on Sonic's next flush; what it had queued was
        // finished and drained at the boundary.
        sonic.setSpeed(currentSpeed)
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private companion object {
        /** Sonic's own threshold for a rate that is one. */
        const val CLOSE_TO_ONE = 0.01f
    }
}
