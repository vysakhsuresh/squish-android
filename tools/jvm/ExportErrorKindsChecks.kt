import com.squish.app.media.ExportErrorKinds
import com.squish.app.media.ExportErrorKinds.Kind
import kotlin.system.exitProcess

/*
 * Which card an export failure shows. The one that sent this here: a hardware
 * decoder that would not start a second stream (3001) was told to the user as
 * "this phone can't decode that clip", about a file that had just exported.
 * Busy is not unable.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    check(ExportErrorKinds.of(3001) == Kind.DecoderBusy, "a decoder that would not start reads as ${ExportErrorKinds.of(3001)}, not busy")
    check(ExportErrorKinds.of(3003) == Kind.UnsupportedCodec, "a format no decoder takes reads as ${ExportErrorKinds.of(3003)}")
    check(ExportErrorKinds.of(3002) == Kind.UnsupportedCodec, "a decode that failed part way reads as ${ExportErrorKinds.of(3002)}")
    check(ExportErrorKinds.of(4001) == Kind.EncoderBusy, "an encoder that would not start reads as ${ExportErrorKinds.of(4001)}")
    check(ExportErrorKinds.of(4003) == Kind.ResolutionTooHigh, "a size the encoder refuses reads as ${ExportErrorKinds.of(4003)}")
    check(ExportErrorKinds.of(2005) == Kind.FileUnreadable && ExportErrorKinds.of(2006) == Kind.FileUnreadable, "a missing file reads wrong")
    check(ExportErrorKinds.of(1001) == Kind.MixedSources, "a refused composition reads as ${ExportErrorKinds.of(1001)}")
    check(ExportErrorKinds.of(3001, outOfMemory = true) == Kind.OutOfMemory, "running out of memory is not read first")
    check(ExportErrorKinds.of(5001) == Kind.FrameProcessing && ExportErrorKinds.of(7001) == Kind.Muxing, "the processing bands read wrong")
    check(ExportErrorKinds.of(9999) == Kind.Unknown, "an unknown code is given a meaning")

    if (problems.isEmpty()) println("ExportErrorKindsChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
