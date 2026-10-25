package com.squish.app.media

/**
 * Which card an export failure shows, from Media3's error code alone - pure,
 * so tools/jvm/ExportErrorKindsChecks.kt holds it.
 *
 * Seen on the moto on 25 October: a two-shot edit of one H.264 file with a
 * Dissolve between them failed when the phone's hardware decoder would not
 * start a second 1080p stream (c2.qti.avc.decoder, "failed to set format on
 * port:IN", Media3 code 3001), and the card said "This phone can't decode
 * that clip ... It is the phone, not the file" - about a file it had
 * exported a minute before. A decoder that will not *start* (3001) is busy,
 * not unable; only a format no decoder takes (3003) is "can't decode".
 */
object ExportErrorKinds {

    enum class Kind { OutOfMemory, FileUnreadable, UnsupportedCodec, DecoderBusy, ResolutionTooHigh, EncoderBusy, MixedSources, FileUnreadableBand, FrameProcessing, AudioProcessing, Muxing, Unknown }

    const val FAILED_RUNTIME_CHECK = 1001
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_NO_PERMISSION = 2006
    const val DECODER_INIT_FAILED = 3001
    const val DECODING_FAILED = 3002
    const val DECODING_FORMAT_UNSUPPORTED = 3003
    const val ENCODER_INIT_FAILED = 4001
    const val ENCODING_FORMAT_UNSUPPORTED = 4003

    fun of(code: Int, outOfMemory: Boolean = false): Kind = when {
        outOfMemory -> Kind.OutOfMemory
        code == IO_FILE_NOT_FOUND || code == IO_NO_PERMISSION -> Kind.FileUnreadable
        code == DECODER_INIT_FAILED -> Kind.DecoderBusy
        code == DECODING_FORMAT_UNSUPPORTED -> Kind.UnsupportedCodec
        code == ENCODING_FORMAT_UNSUPPORTED -> Kind.ResolutionTooHigh
        code == ENCODER_INIT_FAILED -> Kind.EncoderBusy
        // A composition the pipeline refused to start. The commonest reason by far is inputs that disagree with each other.
        code == FAILED_RUNTIME_CHECK -> Kind.MixedSources
        code in 2000..2999 -> Kind.FileUnreadableBand
        code in 3000..3999 -> Kind.UnsupportedCodec
        code in 4000..4999 -> Kind.EncoderBusy
        code in 5000..5999 -> Kind.FrameProcessing
        code in 6000..6999 -> Kind.AudioProcessing
        code in 7000..7999 -> Kind.Muxing
        else -> Kind.Unknown
    }
}
