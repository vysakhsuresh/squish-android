import com.squish.app.media.ProxyRules
import com.squish.app.media.ProxyRules.Footage
import com.squish.app.media.ProxyRules.Quality
import kotlin.system.exitProcess

/* EDGE_PLAN C5: which footage gets a light preview copy, at each Preview quality. */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val min = 60_000L
    fun f(w: Int, h: Int, fps: Float = 30f, codec: String = "h264", bits: Int = 8, rate: Long = 17_000_000, ms: Long = min) =
        Footage(w, h, fps, ms, codec, bits, rate)

    // As it was: 4K yes, 1080p30 H.264 no, a portrait 4K yes (the long edge, not the width).
    check(ProxyRules.worth(f(3840, 2160)), "4K was not proxied")
    check(ProxyRules.worth(f(2160, 3840)), "portrait 4K was not proxied")
    check(!ProxyRules.worth(f(1920, 1080)), "1080p30 H.264 was proxied")
    // The new cases.
    check(ProxyRules.worth(f(1920, 1080, fps = 60f)), "1080p60 was not proxied")
    check(!ProxyRules.worth(f(1920, 1080, fps = 50f)), "1080p50 was proxied (the line is above 50)")
    check(ProxyRules.worth(f(1920, 1080, bits = 10)), "10-bit 1080p was not proxied")
    check(ProxyRules.worth(f(1080, 1920, codec = "hevc")), "HEVC 1080p portrait was not proxied")
    check(!ProxyRules.worth(f(1280, 720, codec = "hevc", fps = 60f)), "720p60 HEVC was proxied - small enough to play")
    check(ProxyRules.worth(f(1280, 720, rate = 60_000_000)), "a 60 Mbps 720p file was not proxied")
    // Unknowns are not reasons.
    check(!ProxyRules.worth(Footage(1920, 1080, 30f, min)), "a 1080p file with nothing known was proxied")
    // Length.
    check(!ProxyRules.worth(f(3840, 2160, ms = 16 * min)), "a 16-minute 4K file was proxied")
    check(!ProxyRules.worth(f(3840, 2160, ms = 0)), "a file of no length was proxied")
    // The settings.
    check(ProxyRules.worth(f(1280, 720), Quality.Smooth) && !ProxyRules.worth(f(854, 480), Quality.Smooth), "Smooth's line is not 720p")
    check(!ProxyRules.worth(f(3840, 2160, fps = 60f), Quality.Full), "Full proxied")
    check(ProxyRules.reason(f(1920, 1080, fps = 59.94f)) == "1080p at 59 fps", "reason: ${ProxyRules.reason(f(1920, 1080, fps = 59.94f))}")

    if (problems.isEmpty()) println("ProxyRulesChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
