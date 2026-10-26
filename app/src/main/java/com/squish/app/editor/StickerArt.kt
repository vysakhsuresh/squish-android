package com.squish.app.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The designed sticker pack (EDGE_PLAN F4): callouts, the follow-and-like
 * buttons, arrows, frames, highlights and price tags, drawn in code.
 *
 * Nothing here touches Android, as with Annotation.kt's shapes: a design is
 * described once as parts in the box it fills, and CaptionRenderer paints the
 * parts - so the preview and the file draw one description, and
 * tools/jvm/StickerArtChecks.kt can hold every design to its box. Vector and
 * made here, so no licence question: the few icons (a bell, a thumb) are the
 * Material icons the app already ships, Apache-2.0.
 *
 * A design is a sticker - left out of subtitles, the transcript and Read
 * aloud - whose words, where it has any, are the line's own text, typed on the
 * keyboard like a title's; [ACCENT] parts take the sticker's colour, so a
 * price tag can be any colour and still be a price tag.
 */

/** The colour that means "the sticker's own colour". Not a colour anything else uses: fully transparent black-ish. */
const val ACCENT = 0x00000001

/** An icon the renderer draws from the Material set it already has. */
enum class ArtIcon { Play, ThumbUp, Bell, Share, Heart, TouchApp, Cart, Fire }

/** A rectangle in the box, in the box's pixels. */
data class ArtRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * One part of a design: [polys] filled or stroked in [colour] - or, with
 * [icon], that icon drawn into [rect]. [evenOdd] cuts the inner runs out of
 * the outer, which is how a frame has a hole in it. [strokeShare] is a stroke's
 * width as a share of the box's short edge; zero fills.
 */
data class ArtPart(
    val polys: List<ShapePoly> = emptyList(),
    val colour: Int,
    val strokeShare: Float = 0f,
    val evenOdd: Boolean = false,
    val icon: ArtIcon? = null,
    val rect: ArtRect? = null,
    /** How much of the part shows, 0..1: an animated design fades a ring or a tick (EDGE_PLAN F4). */
    val alpha: Float = 1f
)

/** Where a design's words go, and in what. */
data class ArtWords(val rect: ArtRect, val colour: Int, val bold: Boolean = true)

/**
 * The designs. [words] is what a new one says (empty: it has no words);
 * [aspect] its width over its height; [colour] the sticker colour it lands in,
 * which its [ACCENT] parts take.
 */
enum class StickerDesign(
    val label: String,
    val shelf: Shelf,
    val aspect: Float,
    val words: String = "",
    val colour: Int = 0xFFFFD43B.toInt(),
    /** One time round an animated design, or 0 for a still one (EDGE_PLAN F4, the animated pack). */
    val loopMs: Long = 0L
) {
    None("None", Shelf.Callouts, 1f),

    Speech("Speech", Shelf.Callouts, 1.5f, "Hi!", 0xFFFFFFFF.toInt()),
    Thought("Thought", Shelf.Callouts, 1.5f, "Hmm…", 0xFFFFFFFF.toInt()),
    Burst("Burst", Shelf.Callouts, 1.25f, "NEW!", 0xFFFFD43B.toInt()),
    Shout("Shout", Shelf.Callouts, 1.6f, "WOW!", 0xFFFF3B5C.toInt()),
    Pill("Label", Shelf.Callouts, 2.6f, "TIP", 0xFF3D7BFF.toInt()),
    Ribbon("Ribbon", Shelf.Callouts, 3f, "SALE", 0xFFE53935.toInt()),

    Subscribe("Subscribe", Shelf.Social, 3.4f, "SUBSCRIBE", 0xFFE53935.toInt()),
    Like("Like", Shelf.Social, 1f, "", 0xFF2F80ED.toInt()),
    Bell("Bell", Shelf.Social, 1f, "", 0xFFFFC53D.toInt()),
    Follow("Follow", Shelf.Social, 2.8f, "+ FOLLOW", 0xFFFF2D7A.toInt()),
    Share("Share", Shelf.Social, 1f, "", 0xFF2EBD6B.toInt()),
    Hearts("Heart count", Shelf.Social, 2.3f, "1.2K", 0xFFFFFFFF.toInt()),

    CurvedArrow("Curved arrow", Shelf.Arrows, 1.6f, "", 0xFFFFD43B.toInt()),
    WavyArrow("Wavy arrow", Shelf.Arrows, 2.6f, "", 0xFFFFFFFF.toInt()),
    LookHere("Look here", Shelf.Arrows, 2.6f, "LOOK", 0xFFFF7A1A.toInt()),
    TapHere("Tap here", Shelf.Arrows, 1f, "", 0xFFFFFFFF.toInt()),

    RoundFrame("Frame", Shelf.Frames, 1.78f, "", 0xFFFFFFFF.toInt()),
    Polaroid("Polaroid", Shelf.Frames, 0.84f, "", 0xFFFFFFFF.toInt()),
    Film("Film strip", Shelf.Frames, 1.78f, "", 0xFF111111.toInt()),
    Viewfinder("Viewfinder", Shelf.Frames, 1.78f, "", 0xFFFFFFFF.toInt()),

    Marker("Marker", Shelf.Highlights, 3f, "", 0xFFFFE14D.toInt()),
    Scribble("Circle it", Shelf.Highlights, 1.4f, "", 0xFFFF3B30.toInt()),
    Swoosh("Underline", Shelf.Highlights, 4f, "", 0xFFFF3B30.toInt()),

    PriceTag("Price tag", Shelf.Prices, 2.1f, "$9.99", 0xFFFFD43B.toInt()),
    SaleBadge("Sale badge", Shelf.Prices, 1f, "-50%", 0xFFE53935.toInt()),
    Deal("Deal burst", Shelf.Prices, 1.2f, "ONLY $5", 0xFFFFD43B.toInt()),

    // The animated pack: drawn from the same description at each moment, round and round.
    Sparkles("Sparkles", Shelf.Animated, 1.2f, colour = 0xFFFFE066.toInt(), loopMs = 1_200L),
    Heartbeat("Heartbeat", Shelf.Animated, 1f, colour = 0xFFFF2D55.toInt(), loopMs = 1_000L),
    Flame("Flame", Shelf.Animated, 1f, colour = 0xFFFF7A1A.toInt(), loopMs = 600L),
    BounceArrow("Bounce arrow", Shelf.Animated, 0.7f, colour = 0xFFFFFFFF.toInt(), loopMs = 900L),
    SpinStar("Spinning star", Shelf.Animated, 1f, colour = 0xFFFFD43B.toInt(), loopMs = 2_000L),
    Pulse("Pulse", Shelf.Animated, 1f, colour = 0xFF5CE1E6.toInt(), loopMs = 1_400L),
    Typing("Typing", Shelf.Animated, 2.2f, colour = 0xFFFFFFFF.toInt(), loopMs = 1_200L),
    Tick("Tick", Shelf.Animated, 1f, colour = 0xFF2EBD6B.toInt(), loopMs = 1_600L),
    Confetti("Confetti", Shelf.Animated, 1f, colour = 0xFFFF2D7A.toInt(), loopMs = 2_000L),
    TapPulse("Tap pulse", Shelf.Animated, 1f, colour = 0xFFFFFFFF.toInt(), loopMs = 1_200L),
    Spinner("Spinner", Shelf.Animated, 1f, colour = 0xFFFFFFFF.toInt(), loopMs = 1_000L),
    SpeedLines("Speed lines", Shelf.Animated, 1.6f, colour = 0xFFFFFFFF.toInt(), loopMs = 500L);

    enum class Shelf(val label: String) {
        Callouts("Callouts"), Social("Social"), Arrows("Arrows"), Frames("Frames"), Highlights("Highlights"), Prices("Prices"), Animated("Animated")
    }

    val isDesign: Boolean get() = this != None
    val hasWords: Boolean get() = words.isNotEmpty()
    val animated: Boolean get() = loopMs > 0L

    companion object {
        val offered: List<StickerDesign> get() = entries.filter { it.isDesign }
        fun named(name: String?): StickerDesign = entries.firstOrNull { it.name == name } ?: None
    }
}

/** Every design laid out in a box [w] by [h] pixels. */
object StickerArt {
    private const val INK = 0xFF141414.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * [design] laid out in [w] by [h], [atMs] into it: the same at every moment
     * for a still design, and for an animated one the moment of its loop.
     */
    fun parts(design: StickerDesign, w: Float, h: Float, atMs: Long = 0L): List<ArtPart> {
        val s = min(w, h)
        if (design.animated) return animatedParts(design, w, h, phaseOf(design, atMs))
        return when (design) {
            StickerDesign.None -> emptyList()

            StickerDesign.Speech -> listOf(
                ArtPart(listOf(roundRect(0f, 0f, w, h * 0.8f, h * 0.25f), poly(p(w * 0.18f, h * 0.7f), p(w * 0.36f, h * 0.7f), p(w * 0.12f, h))), ACCENT),
                ArtPart(listOf(roundRect(0f, 0f, w, h * 0.8f, h * 0.25f)), INK, strokeShare = 0.035f)
            )
            StickerDesign.Thought -> listOf(
                ArtPart(
                    listOf(
                        ellipse(w * 0.5f, h * 0.4f, w * 0.5f, h * 0.4f),
                        ellipse(w * 0.2f, h * 0.84f, s * 0.08f, s * 0.08f),
                        ellipse(w * 0.1f, h * 0.95f, s * 0.045f, s * 0.045f)
                    ),
                    ACCENT
                )
            )
            StickerDesign.Burst, StickerDesign.Deal -> listOf(
                ArtPart(listOf(starburst(w / 2f, h / 2f, w / 2f, h / 2f, 14, 0.78f)), ACCENT),
                ArtPart(listOf(starburst(w / 2f, h / 2f, w / 2f, h / 2f, 14, 0.78f)), INK, strokeShare = 0.03f)
            )
            StickerDesign.Shout -> listOf(ArtPart(listOf(jagged(w, h)), ACCENT))
            StickerDesign.Pill, StickerDesign.Follow -> listOf(ArtPart(listOf(roundRect(0f, 0f, w, h, h / 2f)), ACCENT))
            StickerDesign.Ribbon -> listOf(
                ArtPart(listOf(poly(p(0f, 0f), p(w, 0f), p(w * 0.92f, h / 2f), p(w, h), p(0f, h), p(w * 0.08f, h / 2f))), ACCENT)
            )

            StickerDesign.Subscribe -> listOf(
                ArtPart(listOf(roundRect(0f, 0f, w, h, h * 0.22f)), ACCENT),
                ArtPart(icon = ArtIcon.Play, colour = WHITE, rect = ArtRect(h * 0.12f, h * 0.12f, h * 0.88f, h * 0.88f))
            )
            StickerDesign.Like, StickerDesign.Bell, StickerDesign.Share -> listOf(
                ArtPart(listOf(ellipse(w / 2f, h / 2f, w / 2f, h / 2f)), ACCENT),
                ArtPart(
                    icon = when (design) { StickerDesign.Like -> ArtIcon.ThumbUp; StickerDesign.Bell -> ArtIcon.Bell; else -> ArtIcon.Share },
                    colour = if (design == StickerDesign.Bell) INK else WHITE,
                    rect = ArtRect(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f)
                )
            )
            StickerDesign.Hearts -> listOf(
                ArtPart(listOf(roundRect(0f, 0f, w, h, h / 2f)), ACCENT),
                ArtPart(icon = ArtIcon.Heart, colour = 0xFFFF2D55.toInt(), rect = ArtRect(h * 0.18f, h * 0.18f, h * 0.82f, h * 0.82f))
            )

            StickerDesign.CurvedArrow -> {
                // A quarter-turn sweep ending in a head pointing right and down.
                val shaft = (0..24).map { i ->
                    val t = i / 24f
                    val a = PI * (1.0 - 0.5 * t)
                    p(w * 0.5f + w * 0.42f * cos(a).toFloat(), h * 0.92f - h * 0.62f * sin(a).toFloat())
                }
                val end = shaft.last()
                listOf(
                    ArtPart(listOf(ShapePoly(shaft, closed = false)), ACCENT, strokeShare = 0.1f),
                    ArtPart(listOf(poly(p(end.x - s * 0.02f, end.y - s * 0.2f), p(end.x + s * 0.18f, end.y), p(end.x - s * 0.02f, end.y + s * 0.2f))), ACCENT)
                )
            }
            StickerDesign.WavyArrow -> {
                val head = min(w * 0.22f, h)
                val shaft = (0..40).map { i ->
                    val t = i / 40f
                    p(t * (w - head * 0.8f), h / 2f + h * 0.28f * sin(t * 3.0 * PI).toFloat())
                }
                listOf(
                    ArtPart(listOf(ShapePoly(shaft, closed = false)), ACCENT, strokeShare = 0.14f),
                    ArtPart(listOf(poly(p(w - head, h * 0.12f), p(w, h / 2f), p(w - head, h * 0.88f))), ACCENT)
                )
            }
            StickerDesign.LookHere -> listOf(
                ArtPart(listOf(poly(p(0f, 0f), p(w - h * 0.5f, 0f), p(w, h / 2f), p(w - h * 0.5f, h), p(0f, h))), ACCENT)
            )
            StickerDesign.TapHere -> listOf(
                ArtPart(listOf(ellipse(w / 2f, h / 2f, w * 0.46f, h * 0.46f)), ACCENT, strokeShare = 0.06f),
                ArtPart(icon = ArtIcon.TouchApp, colour = ACCENT, rect = ArtRect(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f))
            )

            StickerDesign.RoundFrame -> listOf(
                ArtPart(listOf(roundRect(0f, 0f, w, h, s * 0.12f), roundRect(s * 0.06f, s * 0.06f, w - s * 0.06f, h - s * 0.06f, s * 0.08f)), ACCENT, evenOdd = true)
            )
            StickerDesign.Polaroid -> listOf(
                ArtPart(listOf(rect(0f, 0f, w, h), rect(w * 0.07f, w * 0.07f, w * 0.93f, h - h * 0.2f)), ACCENT, evenOdd = true)
            )
            StickerDesign.Film -> {
                val band = h * 0.14f
                val holes = (0 until 12).flatMap { i ->
                    val x = w * (i + 0.25f) / 12f
                    listOf(rect(x, band * 0.3f, x + w * 0.5f / 12f, band * 0.7f), rect(x, h - band * 0.7f, x + w * 0.5f / 12f, h - band * 0.3f))
                }
                listOf(ArtPart(listOf(rect(0f, 0f, w, h), rect(0f, band, w, h - band)) + holes, ACCENT, evenOdd = true))
            }
            StickerDesign.Viewfinder -> {
                val l = s * 0.22f
                listOf(
                    ArtPart(
                        listOf(
                            open(p(0f, l), p(0f, 0f), p(l, 0f)), open(p(w - l, 0f), p(w, 0f), p(w, l)),
                            open(p(w, h - l), p(w, h), p(w - l, h)), open(p(l, h), p(0f, h), p(0f, h - l))
                        ),
                        ACCENT, strokeShare = 0.05f
                    )
                )
            }

            // Translucent, as a highlighter is: the colour's own alpha is kept, and this one asks for half.
            StickerDesign.Marker -> listOf(
                ArtPart(listOf(poly(p(w * 0.03f, h * 0.1f), p(w, 0f), p(w * 0.97f, h * 0.9f), p(0f, h))), (ACCENT_HALF))
            )
            StickerDesign.Scribble -> listOf(
                ArtPart(listOf(tilted(w, h, 0.0), tilted(w, h, 0.12)), ACCENT, strokeShare = 0.05f)
            )
            StickerDesign.Swoosh -> listOf(
                ArtPart(
                    listOf(ShapePoly((0..30).map { i -> val t = i / 30f; p(t * w, h * (0.7f - 0.4f * t + 0.3f * t * t)) }, closed = false)),
                    ACCENT, strokeShare = 0.28f
                )
            )

            StickerDesign.PriceTag -> listOf(
                ArtPart(
                    listOf(
                        poly(p(h * 0.45f, 0f), p(w, 0f), p(w, h), p(h * 0.45f, h), p(0f, h / 2f)),
                        ellipse(h * 0.4f, h / 2f, h * 0.09f, h * 0.09f)
                    ),
                    ACCENT, evenOdd = true
                )
            )
            StickerDesign.SaleBadge -> listOf(ArtPart(listOf(starburst(w / 2f, h / 2f, w / 2f, h / 2f, 24, 0.9f)), ACCENT))
            // The animated pack is drawn by animatedParts, above.
            else -> emptyList()
        }
    }

    /** How far round its loop an animated design is, [atMs] into it: 0 until 1. */
    fun phaseOf(design: StickerDesign, atMs: Long): Float {
        if (!design.animated) return 0f
        val loop = design.loopMs
        return (((atMs % loop) + loop) % loop).toFloat() / loop
    }

    /**
     * Which picture of its loop an animated design shows [atMs] in, at thirty a
     * second: what the preview's and the export's caches key on, so a still
     * design is painted once and an animated one once a frame.
     */
    fun frameOf(design: StickerDesign, atMs: Long): Int =
        if (!design.animated) 0 else ((((atMs % design.loopMs) + design.loopMs) % design.loopMs) * FRAMES_A_SECOND / 1000L).toInt()

    private const val FRAMES_A_SECOND = 30L

    private fun animatedParts(design: StickerDesign, w: Float, h: Float, t: Float): List<ArtPart> {
        val s = min(w, h)
        val tau = 2.0 * PI
        fun wave(x: Float) = sin(tau * x).toFloat()
        return when (design) {
            StickerDesign.Sparkles -> {
                // Four four-pointed stars, each twinkling a quarter of a turn after the last.
                val spots = listOf(Triple(0.28f, 0.32f, 0.26f), Triple(0.72f, 0.24f, 0.18f), Triple(0.62f, 0.7f, 0.3f), Triple(0.2f, 0.76f, 0.14f))
                spots.mapIndexed { i, (x, y, size) ->
                    val grow = 0.25f + 0.75f * (0.5f + 0.5f * wave(t + i * 0.25f))
                    val r = s * size * 0.5f * grow
                    ArtPart(listOf(starburst(w * x, h * y, r, r, 4, 0.28f)), ACCENT)
                }
            }
            StickerDesign.Heartbeat -> {
                // Lub-dub: two quick swells a beat, then rest.
                fun bump(at: Float, width: Float) = kotlin.math.exp(-((t - at) / width) * ((t - at) / width))
                val beat = 0.78f + 0.16f * bump(0.12f, 0.06f) + 0.1f * bump(0.32f, 0.06f)
                val side = s * beat
                ArtPart(icon = ArtIcon.Heart, colour = ACCENT, rect = centred(w, h, side, side)).let(::listOf)
            }
            StickerDesign.Flame -> {
                // Flickers: taller and narrower in turn, always standing on the same floor.
                val tall = 0.86f + 0.12f * (0.5f + 0.5f * wave(t)) * (0.7f + 0.3f * wave(t * 3f).coerceAtLeast(0f))
                val wide = 0.94f - 0.06f * (0.5f + 0.5f * wave(t + 0.5f))
                val fw = s * wide
                val fh = s * tall
                listOf(ArtPart(icon = ArtIcon.Fire, colour = ACCENT, rect = ArtRect((w - fw) / 2f, h - fh, (w + fw) / 2f, h)))
            }
            StickerDesign.BounceArrow -> {
                // Pointing down at something, bouncing, as "look below" does.
                val travel = h * 0.18f
                val dy = travel * kotlin.math.abs(wave(t * 0.5f))
                val top = dy
                val headTop = top + (h - travel) * 0.45f
                val bottom = top + (h - travel)
                listOf(
                    ArtPart(listOf(rect(w * 0.36f, top, w * 0.64f, headTop + 1f), poly(p(0f, headTop), p(w, headTop), p(w / 2f, bottom))), ACCENT)
                )
            }
            StickerDesign.SpinStar -> {
                val turn = tau * t
                val pts = (0 until 10).map { i ->
                    val a = -PI / 2 + PI * i / 5 + turn
                    val r = if (i % 2 == 0) s * 0.48f else s * 0.2f
                    p(w / 2f + r * cos(a).toFloat(), h / 2f + r * sin(a).toFloat())
                }
                listOf(ArtPart(listOf(ShapePoly(pts, closed = true)), ACCENT))
            }
            StickerDesign.Pulse -> {
                // Three rings leaving the middle in turn, fading as they go.
                (0 until 3).map { k ->
                    val age = (t + k / 3f) % 1f
                    val r = s * (0.08f + 0.36f * age)
                    ArtPart(listOf(ellipse(w / 2f, h / 2f, r, r)), ACCENT, strokeShare = 0.05f, alpha = (1f - age).coerceIn(0f, 1f))
                } + ArtPart(listOf(ellipse(w / 2f, h / 2f, s * 0.08f, s * 0.08f)), ACCENT)
            }
            StickerDesign.Typing -> {
                // A bubble and three dots rising in turn, as a chat app shows someone typing.
                val dots = (0 until 3).map { k ->
                    val lift = (0.5f + 0.5f * wave(t - k * 0.15f)).coerceIn(0f, 1f)
                    val r = h * 0.11f
                    ArtPart(listOf(ellipse(w * (0.3f + 0.2f * k), h * 0.5f - h * 0.12f * lift, r, r)), 0xFF141414.toInt())
                }
                listOf(ArtPart(listOf(roundRect(0f, 0f, w, h, h / 2f)), ACCENT)) + dots
            }
            StickerDesign.Tick -> {
                // A ring, then a tick drawn into it, held, and gone again for the next time round.
                val draw = (t / 0.45f).coerceIn(0f, 1f)
                val fade = if (t > 0.85f) (1f - (t - 0.85f) / 0.15f).coerceIn(0f, 1f) else 1f
                val a = p(w * 0.28f, h * 0.52f)
                val b = p(w * 0.44f, h * 0.68f)
                val c = p(w * 0.74f, h * 0.36f)
                val first = 0.4f
                val tick = when {
                    draw <= first -> listOf(a, lerp(a, b, draw / first))
                    else -> listOf(a, b, lerp(b, c, (draw - first) / (1f - first)))
                }
                listOf(
                    ArtPart(listOf(ellipse(w / 2f, h / 2f, s * 0.45f, s * 0.45f)), ACCENT, alpha = fade),
                    ArtPart(listOf(ShapePoly(tick, closed = false)), 0xFFFFFFFF.toInt(), strokeShare = 0.09f, alpha = if (draw <= 0f) 0f else fade)
                )
            }
            StickerDesign.Confetti -> {
                // Pieces falling and turning; where one leaves the bottom it comes back in at the top.
                val colours = listOf(ACCENT, 0xFFFFD43B.toInt(), 0xFF5CE1E6.toInt(), 0xFF2EBD6B.toInt(), 0xFF8B5CF6.toInt())
                (0 until 14).map { i ->
                    val x0 = ((i * 0.37f) % 1f) * 0.84f + 0.08f
                    val fall = (t + i * 0.071f) % 1f
                    val cx = w * (x0 + 0.04f * wave(t * 2f + i * 0.3f))
                    val cy = h * (0.06f + 0.88f * fall)
                    val half = s * 0.04f
                    val turn = tau * (t * 2f + i * 0.17f)
                    val corners = listOf(-1f to -0.5f, 1f to -0.5f, 1f to 0.5f, -1f to 0.5f).map { (u, v) ->
                        p(cx + half * (u * cos(turn).toFloat() - v * sin(turn).toFloat()), cy + half * (u * sin(turn).toFloat() + v * cos(turn).toFloat()))
                    }
                    ArtPart(listOf(ShapePoly(corners, closed = true)), colours[i % colours.size])
                }
            }
            StickerDesign.TapPulse -> {
                val age = t
                // Out from the fingertip, never further than its nearest edge (0.36 of the box).
                val r = s * (0.08f + 0.27f * age)
                listOf(
                    ArtPart(listOf(ellipse(w * 0.42f, h * 0.36f, r, r)), ACCENT, strokeShare = 0.04f, alpha = (1f - age).coerceIn(0f, 1f)),
                    ArtPart(icon = ArtIcon.TouchApp, colour = ACCENT, rect = ArtRect(w * 0.16f, h * 0.16f, w * 0.9f, h * 0.9f))
                )
            }
            StickerDesign.Spinner -> {
                val start = tau * t
                val arc = (0..30).map { i ->
                    val a = start + 1.5 * PI * i / 30
                    p(w / 2f + s * 0.4f * cos(a).toFloat(), h / 2f + s * 0.4f * sin(a).toFloat())
                }
                listOf(
                    ArtPart(listOf(ellipse(w / 2f, h / 2f, s * 0.4f, s * 0.4f)), ACCENT, strokeShare = 0.1f, alpha = 0.25f),
                    ArtPart(listOf(ShapePoly(arc, closed = false)), ACCENT, strokeShare = 0.1f)
                )
            }
            StickerDesign.SpeedLines -> {
                // Lines streaming past to the left: each scrolls a whole width a loop and wraps.
                (0 until 7).map { k ->
                    val y = h * (0.12f + 0.76f * k / 6f)
                    val len = w * (0.25f + 0.2f * ((k * 0.61f) % 1f))
                    val x = (w + len) * ((1f - (t + k * 0.29f) % 1f)) - len
                    val l = x.coerceIn(0f, w)
                    val r = (x + len).coerceIn(0f, w)
                    ArtPart(listOf(ShapePoly(listOf(p(l, y), p(maxOf(r, l + 0.01f), y)), closed = false)), ACCENT, strokeShare = 0.05f)
                }
            }
            else -> emptyList()
        }
    }

    private fun centred(w: Float, h: Float, bw: Float, bh: Float) = ArtRect((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
    private fun lerp(a: ShapePoint, b: ShapePoint, t: Float) = p(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Where a design's words go, or null for one that has none. */
    fun words(design: StickerDesign, w: Float, h: Float): ArtWords? {
        if (!design.hasWords) return null
        return when (design) {
            StickerDesign.Speech -> ArtWords(ArtRect(w * 0.1f, h * 0.08f, w * 0.9f, h * 0.72f), INK)
            StickerDesign.Thought -> ArtWords(ArtRect(w * 0.16f, h * 0.1f, w * 0.84f, h * 0.7f), INK)
            StickerDesign.Burst, StickerDesign.Deal -> ArtWords(ArtRect(w * 0.24f, h * 0.28f, w * 0.76f, h * 0.72f), INK)
            StickerDesign.Shout -> ArtWords(ArtRect(w * 0.18f, h * 0.24f, w * 0.82f, h * 0.76f), WHITE)
            StickerDesign.Pill, StickerDesign.Follow, StickerDesign.Ribbon -> ArtWords(ArtRect(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f), WHITE)
            StickerDesign.Subscribe -> ArtWords(ArtRect(h * 1.0f, h * 0.2f, w - h * 0.2f, h * 0.8f), WHITE)
            StickerDesign.Hearts -> ArtWords(ArtRect(h * 0.95f, h * 0.18f, w - h * 0.25f, h * 0.82f), INK)
            StickerDesign.LookHere -> ArtWords(ArtRect(h * 0.25f, h * 0.18f, w - h * 0.6f, h * 0.82f), WHITE)
            StickerDesign.PriceTag -> ArtWords(ArtRect(h * 0.7f, h * 0.18f, w - h * 0.15f, h * 0.82f), INK)
            StickerDesign.SaleBadge -> ArtWords(ArtRect(w * 0.2f, h * 0.3f, w * 0.8f, h * 0.7f), WHITE)
            else -> null
        }
    }

    /** Half the sticker's colour: a highlighter's. */
    const val ACCENT_HALF = 0x00000002

    private fun p(x: Float, y: Float) = ShapePoint(x, y)
    private fun poly(vararg pts: ShapePoint) = ShapePoly(pts.toList(), closed = true)
    private fun open(vararg pts: ShapePoint) = ShapePoly(pts.toList(), closed = false)
    private fun rect(l: Float, t: Float, r: Float, b: Float) = poly(p(l, t), p(r, t), p(r, b), p(l, b))

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 48) =
        ShapePoly((0 until steps).map { i -> val a = 2.0 * PI * i / steps; p(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()) }, closed = true)

    private fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float): ShapePoly {
        val rad = minOf(radius, (r - l) / 2f, (b - t) / 2f)
        val pts = ArrayList<ShapePoint>()
        fun corner(cx: Float, cy: Float, from: Double) {
            for (i in 0..8) { val a = from + PI / 2 * i / 8; pts += p(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat()) }
        }
        corner(r - rad, t + rad, -PI / 2); corner(r - rad, b - rad, 0.0); corner(l + rad, b - rad, PI / 2); corner(l + rad, t + rad, PI)
        return ShapePoly(pts, closed = true)
    }

    private fun starburst(cx: Float, cy: Float, rx: Float, ry: Float, points: Int, inner: Float) =
        ShapePoly((0 until points * 2).map { i ->
            val a = -PI / 2 + PI * i / points
            val r = if (i % 2 == 0) 1f else inner
            p(cx + rx * r * cos(a).toFloat(), cy + ry * r * sin(a).toFloat())
        }, closed = true)

    private fun jagged(w: Float, h: Float): ShapePoly {
        // An irregular shout: radii fixed per spike, so it is the same shape every time.
        val radii = floatArrayOf(1f, 0.7f, 0.95f, 0.66f, 1f, 0.72f, 0.9f, 0.62f, 1f, 0.7f, 0.92f, 0.68f, 0.98f, 0.64f, 0.94f, 0.7f)
        return ShapePoly(radii.indices.map { i ->
            val a = -PI / 2 + 2 * PI * i / radii.size
            p(w / 2f + w / 2f * radii[i] * cos(a).toFloat(), h / 2f + h / 2f * radii[i] * sin(a).toFloat())
        }, closed = false).let { ShapePoly(it.points, closed = true) }
    }

    private fun tilted(w: Float, h: Float, turn: Double): ShapePoly {
        // A hand's circle: not quite closed, a little turned each time round.
        val pts = (0..44).map { i ->
            val a = 2.0 * PI * i / 40
            val x = 0.44f * cos(a).toFloat()
            val y = 0.4f * sin(a).toFloat()
            val c = cos(turn).toFloat(); val sn = sin(turn).toFloat()
            p(w * (0.5f + x * c - y * sn), h * (0.5f + x * sn + y * c))
        }
        return ShapePoly(pts, closed = false)
    }
}
