import com.squish.app.editor.OutputSize
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import kotlin.system.exitProcess

/**
 * The export sheet's arithmetic, executed: the frame rate a choice gives, what
 * a quality step and HEVC do to the bitrate, which sizes the encoder ceiling
 * greys, where a remembered default lands, where a fitted export is sized,
 * and how a missed target is chased on the next run.
 */
private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

private const val MB = 1_000_000L

fun main() {
    // ---- Frame rate --------------------------------------------------------------
    check("no choice keeps the footage's rate", ExportSettings.effectiveFps(ExportSettings.SOURCE_FPS, 29.97f) == 29.97f)
    check("a choice is the rate", ExportSettings.effectiveFps(24, 60f) == 24f)
    check("30 on 60 fps footage drops frames, honestly", !ExportSettings.exceedsSource(30, 60f))
    check("60 on 30 fps footage cannot add frames", ExportSettings.exceedsSource(60, 30f))
    check("30 on 29.97 footage is the same rate", !ExportSettings.exceedsSource(30, 29.97f))
    check("no choice never exceeds", !ExportSettings.exceedsSource(ExportSettings.SOURCE_FPS, 24f))
    check("unknown footage rate exceeds nothing", !ExportSettings.exceedsSource(60, 0f))
    check("the offered rates are the standard five", ExportSettings.FPS_CHOICES == listOf(24, 25, 30, 50, 60))

    // ---- Quality and codec -------------------------------------------------------
    val base = 10_000_000
    check("Recommended is the rate itself", ExportSettings.scaledBitrate(base, ExportQuality.Recommended, hevc = false) == base)
    check("Lower is below Recommended", ExportSettings.scaledBitrate(base, ExportQuality.Lower, false) < base)
    check("Higher is above Recommended", ExportSettings.scaledBitrate(base, ExportQuality.Higher, false) > base)
    val hevc = ExportSettings.scaledBitrate(base, ExportQuality.Recommended, hevc = true)
    check("HEVC spends about two thirds", hevc in (base * 0.6).toInt()..(base * 0.7).toInt())
    check("the two scale together", ExportSettings.scaledBitrate(base, ExportQuality.Higher, true) < ExportSettings.scaledBitrate(base, ExportQuality.Higher, false))
    check("never below the floor", ExportSettings.scaledBitrate(300_000, ExportQuality.Lower, true) >= ExportPresets.MIN_VIDEO_BPS)
    check("never above the ceiling", ExportSettings.scaledBitrate(80_000_000, ExportQuality.Higher, false) <= ExportPresets.MAX_VIDEO_BPS)
    check("a quality name reads back", ExportQuality.fromName("Higher") == ExportQuality.Higher)
    check("an unknown quality name is Recommended", ExportQuality.fromName("Ultra") == ExportQuality.Recommended)
    check("no quality name is Recommended", ExportQuality.fromName(null) == ExportQuality.Recommended)

    // ---- The encoder's ceiling ---------------------------------------------------
    check("4K is greyed on a 1080p encoder", ExportSettings.aboveCeiling(2160, 1080))
    check("1440p is greyed on a 1080p encoder", ExportSettings.aboveCeiling(1440, 1080))
    check("1080p is not greyed on a 1080p encoder", !ExportSettings.aboveCeiling(1080, 1080))
    check("nothing is greyed while the ceiling is unknown", !ExportSettings.aboveCeiling(2160, 0))
    check("Original is never greyed", !ExportSettings.aboveCeiling(OutputSize.ORIGINAL, 720))
    check("a hand-typed size is capped by the encoder", ExportSettings.customCeiling(1080) == 1080)
    check("an unknown ceiling leaves the sheet's own cap", ExportSettings.customCeiling(0) == OutputSize.MAX_P)
    check("an encoder past 4K still caps at the sheet's 4K", ExportSettings.customCeiling(4320) == OutputSize.MAX_P)

    // ---- Remembered defaults -----------------------------------------------------
    check("a remembered 1080p lands on 4K footage", ExportSettings.defaultOutputP(1080, 2160) == 1080)
    check("a remembered 4K does not upscale 720p footage", ExportSettings.defaultOutputP(2160, 720) == OutputSize.ORIGINAL)
    check("a remembered size equal to the footage lands", ExportSettings.defaultOutputP(1080, 1080) == 1080)
    check("Original stays Original", ExportSettings.defaultOutputP(OutputSize.ORIGINAL, 1080) == OutputSize.ORIGINAL)
    check("an unmeasured source takes the remembered size", ExportSettings.defaultOutputP(720, 0) == 720)

    // The frame rate lands by the same rule as the size.
    check("a remembered 60 lands on 60 fps footage", ExportSettings.defaultOutputFps(60, 59.94f) == 60)
    check("a remembered 60 falls back to the footage's own 30", ExportSettings.defaultOutputFps(60, 30f) == ExportSettings.SOURCE_FPS)
    check("a remembered 24 lands on 30 fps footage", ExportSettings.defaultOutputFps(24, 30f) == 24)
    check("Auto stays Auto", ExportSettings.defaultOutputFps(ExportSettings.SOURCE_FPS, 30f) == ExportSettings.SOURCE_FPS)
    check("an unmeasured rate takes the remembered one", ExportSettings.defaultOutputFps(60, 0f) == 60)

    // ---- Fit to a size: the resolution is solved too -----------------------------
    // A minute of 4K at 16 MB: two megabits a second cannot cover eight million
    // pixels; it steps down to a size each pixel gets enough at.
    val fit4k = ExportPresets.fitOutputP(16 * MB, 60_000L, 30f, 3840, 2160, includeAudio = true)
    check("16 MB over a minute of 4K is not written at 4K (got $fit4k)", fit4k != OutputSize.ORIGINAL && fit4k < 2160)
    check("...and lands on a named size", fit4k in OutputSize.PRESETS)
    // The same budget over ten seconds is plenty for the frame as it is.
    check("16 MB over ten seconds of 1080p keeps the frame", ExportPresets.fitOutputP(16 * MB, 10_000L, 30f, 1920, 1080, true) == OutputSize.ORIGINAL)
    // A bigger budget lands bigger, never smaller.
    val small = ExportPresets.fitOutputP(16 * MB, 120_000L, 30f, 3840, 2160, true)
    val big = ExportPresets.fitOutputP(100 * MB, 120_000L, 30f, 3840, 2160, true)
    check("a bigger budget is never a smaller size ($small vs $big)", sizeRank(big) >= sizeRank(small))
    // Never above the frame: a 720p clip is not upscaled to fit.
    check("a 720p clip fits at its own size or below", ExportPresets.fitOutputP(100 * MB, 10_000L, 30f, 1280, 720, true) == OutputSize.ORIGINAL)
    val tiny = ExportPresets.fitOutputP(16 * MB, 3_600_000L, 30f, 1280, 720, true)
    check("an hour at 16 MB steps down but lands on a size ($tiny)", tiny in OutputSize.PRESETS && tiny < 720)
    check("an unmeasured frame is left alone", ExportPresets.fitOutputP(16 * MB, 10_000L, 30f, 0, 0, true) == OutputSize.ORIGINAL)
    // The solve is monotonic in length: longer never gets a bigger frame.
    var last = Int.MAX_VALUE
    for (seconds in listOf(5, 15, 30, 60, 120, 300, 900)) {
        val p = ExportPresets.fitOutputP(25 * MB, seconds * 1000L, 30f, 3840, 2160, true)
        val rank = sizeRank(p)
        check("$seconds s at 25 MB is not bigger than a shorter edit", rank <= last)
        last = rank
    }
    // At 60 fps the same bits are spread over twice the frames, so the size is never bigger.
    check(
        "60 fps never fits bigger than 30 fps",
        sizeRank(ExportPresets.fitOutputP(25 * MB, 60_000L, 60f, 3840, 2160, true)) <=
            sizeRank(ExportPresets.fitOutputP(25 * MB, 60_000L, 30f, 3840, 2160, true))
    )

    // ---- A chosen rate is paid for -----------------------------------------------
    // 30 chosen on 60 fps footage at Original size: half the frames, about half
    // the bits. It used to keep the source's whole bitrate at its own size.
    val at60 = ExportPresets.bitrateForFrame(ExportPresets.Resolution(1920, 1080), 1920, 1080, 60f, 20_000_000L, sourceFps = 60f)
    val at30 = ExportPresets.bitrateForFrame(ExportPresets.Resolution(1920, 1080), 1920, 1080, 30f, 20_000_000L, sourceFps = 60f)
    check("Original at the source's rate is the source's bitrate", at60 == 20_000_000)
    check("30 on 60 fps footage at Original is half the bits ($at30)", at30 == 10_000_000)
    check("60 asked of 30 fps footage adds nothing", ExportPresets.bitrateForFrame(ExportPresets.Resolution(1920, 1080), 1920, 1080, 60f, 20_000_000L, sourceFps = 30f) == 20_000_000)
    check("an unknown source rate is left alone", ExportPresets.bitrateForFrame(ExportPresets.Resolution(1920, 1080), 1920, 1080, 30f, 20_000_000L, sourceFps = 0f) == 20_000_000)
    val down60 = ExportPresets.bitrateForFrame(ExportPresets.Resolution(1280, 720), 1920, 1080, 60f, 20_000_000L, sourceFps = 60f)
    val down30 = ExportPresets.bitrateForFrame(ExportPresets.Resolution(1280, 720), 1920, 1080, 30f, 20_000_000L, sourceFps = 60f)
    check("a smaller size at fewer frames is fewer bits still ($down30 < $down60)", down30 < down60)
    check("the old callers, with no source rate, are unchanged", ExportPresets.bitrateForFrame(ExportPresets.Resolution(1920, 1080), 1920, 1080, 30f, 20_000_000L) == 20_000_000)

    // ---- The fit steps down when the budget is pulled down -----------------------
    // The rate a missed run is chased at is what the size is solved from: the
    // next run at a fifth of the budget lands on a smaller frame, not on the
    // same frame starved.
    val budget = ExportPresets.bitrateForTargetSize(16 * MB, 60_000L, includeAudio = true)
    val plain = ExportPresets.fitOutputPForBitrate(budget, 30f, 3840, 2160)
    val chased = ExportPresets.fitOutputPForBitrate((budget * 0.2f).toInt(), 30f, 3840, 2160)
    check("the plain budget solves as fitOutputP does", plain == ExportPresets.fitOutputP(16 * MB, 60_000L, 30f, 3840, 2160, true))
    check("a fifth of the budget lands smaller ($chased < $plain)", sizeRank(chased) < sizeRank(plain))
    check("...on a named size", chased in OutputSize.PRESETS)

    // ---- Verifying the file afterwards -------------------------------------------
    check("under the target is not a miss", !ExportSettings.overshoots(15_900_000L, 16 * MB))
    check("within two per cent is not a miss", !ExportSettings.overshoots(16_200_000L, 16 * MB))
    check("four per cent over is a miss", ExportSettings.overshoots(16_700_000L, 16 * MB))
    check("no target is never a miss", !ExportSettings.overshoots(50 * MB, 0L))
    val retry = ExportSettings.retryScale(1f, 18 * MB, 16 * MB)
    check("a miss pulls the rate down by the miss and a margin", retry < 16f / 18f && retry > 0.8f)
    check("a second miss pulls further", ExportSettings.retryScale(retry, 17 * MB, 16 * MB) < retry)
    check("a wild miss is never starved past the floor", ExportSettings.retryScale(0.3f, 200 * MB, 16 * MB) >= 0.2f)
    check("the scale never rises above the first run", ExportSettings.retryScale(1f, 8 * MB, 16 * MB) <= 1f)
    check("no file size leaves the scale alone", ExportSettings.retryScale(0.9f, 0L, 16 * MB) == 0.9f)

    if (failures.isEmpty()) {
        println("PASS - the export sheet's arithmetic holds: rates, quality, the ceiling, defaults, the fit and the retry")
    } else {
        println("FAIL (${failures.size})")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

/** Original ranks above every named size: it is the frame itself, the biggest a fit ever keeps. */
private fun sizeRank(p: Int): Int = if (p == OutputSize.ORIGINAL) Int.MAX_VALUE else p
