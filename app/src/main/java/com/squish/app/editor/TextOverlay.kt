package com.squish.app.editor

import com.squish.app.media.video.MotionTrack
import com.squish.app.timeline.Clip

/*
 * A line of words, a sticker or a shape on the picture: the model, and nothing
 * else.
 *
 * Lifted out of EditorModels.kt as a pure move, for the same reason
 * DraftClipCodec came out of DraftCodec (docs/ROADMAP.md §6). A draft's second
 * largest value surface is the text track - a line carries a style, a bubble, a
 * shadow, an arrival, a leaving, a loop, word timings and a motion track - and
 * all of it was out of reach of a real round trip because this one model was
 * declared beside EditorUiState, which drags in ProjectSnapshot, MediaCompat,
 * SquishError and ExportProgress and so cannot be built off a phone.
 *
 * Nothing here touches Android. tools/jvm/DraftTextRoundTripChecks.kt runs it.
 */

/** The three things that share the text track: words, a sticker, a shape. */
enum class TextOverlayKind { Words, Sticker, Shape }

data class TextOverlayItem(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val colorArgb: Int,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.85f,
    val sizeSp: Int = TextStyleSpec.DEFAULT_SIZE_SP,

    // How it is set - see TextStyleSpec, which gathers these for Apply to all
    // and Copy style. New captions get an outline, which reads on any picture.
    val font: TextFont = TextFont.Sans,
    val fontFile: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: TextAlign = TextAlign.Center,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    val stroke: TextStroke = TextLook.OUTLINE_STROKE,
    val shadow: TextShadow = TextShadow.NONE,
    val background: TextBackground = TextBackground.NONE,
    val glow: Boolean = false,
    val opacity: Float = 1f,

    /** Turned on the picture, degrees clockwise, about its centre. */
    val rotationDegrees: Float = 0f,
    /** Mirrored left to right - a sticker facing the other way. */
    val flipped: Boolean = false,

    // How it arrives, leaves and behaves in between, each with its length.
    val motion: TextMotion = TextMotion.None,
    val motionInMs: Long = TextAnimation.DEFAULT_IN_MS,
    val motionOut: TextExit = TextExit.None,
    val motionOutMs: Long = TextAnimation.DEFAULT_OUT_MS,
    val loop: TextLoop = TextLoop.None,
    val loopMs: Long = TextAnimation.DEFAULT_LOOP_MS,
    /**
     * Where each word of an auto-caption starts, from [startMs], as the
     * segmenter heard them; empty for a line typed by hand. Read by the Words
     * arrival, so the words land on the speech.
     */
    val wordStartsMs: List<Long> = emptyList(),

    /**
     * A sticker: an emoji placed on the picture. Drawn exactly like a caption -
     * the same renderer, motions, timeline lane and export - but listed in its own
     * panel and left out of anything that treats captions as words, like .srt.
     */
    val sticker: Boolean = false,

    /**
     * A shape rather than words: a rectangle, a circle, an arrow. Drawn by the
     * same renderer, on the same track, through the same export, and carried as
     * a sticker is - so everything that leaves stickers out of words (subtitle
     * files, the transcript, Read aloud) leaves shapes out too.
     *
     * [sizeSp] is a shape's height, as it is a line's letter size; [shapeAspect]
     * is how much wider than tall it is.
     */
    val shape: AnnotationShape = AnnotationShape.None,
    val shapeAspect: Float = 1f,

    /** A shape's inside filled with its colour, rather than only its edge drawn. */
    val shapeFilled: Boolean = false,

    /**
     * Pins this caption to something moving. Stored in timeline time, matching
     * [startMs] and [endMs], because that is the clock the overlay renderer is
     * already handed.
     */
    val track: MotionTrack? = null,

    /**
     * Which of the text rows on the timeline strip this line would rather be on,
     * where nothing else is in the way there (see TimelineLanes.rows). Only where
     * it is drawn: it changes nothing on the picture.
     */
    val stripRow: Int = 0
) {
    /** Where the caption sits at a moment, following its track if it has one. */
    fun anchorAt(timelineMs: Long): Pair<Float, Float> {
        val sample = track?.sampleAt(timelineMs) ?: return xFraction to yFraction
        return sample.xFraction to sample.yFraction
    }

    /** The style alone: what Apply to all, Copy style and a saved style carry. */
    val style: TextStyleSpec
        get() = TextStyleSpec(
            font = font, fontFile = fontFile, bold = bold, italic = italic, underline = underline,
            align = align, letterSpacing = letterSpacing, lineSpacing = lineSpacing,
            colorArgb = colorArgb, sizeSp = sizeSp, stroke = stroke, shadow = shadow,
            background = background, glow = glow, opacity = opacity
        )

    /** This line in [spec]'s style; its words, timing and place are its own still. */
    fun withStyle(spec: TextStyleSpec): TextOverlayItem = copy(
        font = spec.font, fontFile = spec.fontFile, bold = spec.bold, italic = spec.italic, underline = spec.underline,
        align = spec.align, letterSpacing = spec.letterSpacing, lineSpacing = spec.lineSpacing,
        colorArgb = spec.colorArgb, sizeSp = spec.sizeSp, stroke = spec.stroke, shadow = spec.shadow,
        background = spec.background, glow = spec.glow, opacity = spec.opacity
    )

    /** How it moves, on its own: what Animation's Apply to all carries. */
    val motionSpec: TextMotionSpec
        get() = TextMotionSpec(motion, motionInMs, motionOut, motionOutMs, loop, loopMs)

    /** This line moving as [spec] says; its words, timing, place and style are its own still. */
    fun withMotion(spec: TextMotionSpec): TextOverlayItem = copy(
        motion = spec.motion, motionInMs = spec.motionInMs, motionOut = spec.motionOut,
        motionOutMs = spec.motionOutMs, loop = spec.loop, loopMs = spec.loopMs
    )

    /** Where it is and how big: what a finger on its box changes. */
    val placement: TextPlacement get() = TextPlacement(xFraction, yFraction, sizeSp, rotationDegrees)

    fun placed(at: TextPlacement): TextOverlayItem = copy(
        xFraction = at.xFraction, yFraction = at.yFraction, sizeSp = at.sizeSp, rotationDegrees = at.rotationDegrees
    )

    /** Styled and placed as a title preset is, and moving as it does. */
    fun styledBy(preset: TitlePreset): TextOverlayItem = withStyle(preset.style).copy(
        yFraction = preset.yFraction,
        motion = preset.motion,
        motionOut = preset.exit
    )

    /** A shape rather than words. */
    val isShape: Boolean get() = shape.isShape

    /** What the strip and the lists call it: its words, or - a shape having none - the shape's name. */
    val stripLabel: String get() = if (isShape) shape.label else text

    /**
     * What this is, for the places that treat the three differently - Apply to
     * all carries a style between lines, not from a sticker onto a shape.
     */
    val kindOfOverlay: TextOverlayKind
        get() = when {
            isShape -> TextOverlayKind.Shape
            sticker -> TextOverlayKind.Sticker
            else -> TextOverlayKind.Words
        }

    /** The caption's state at [timeMs] of the timeline, or null when it is not on screen. */
    fun frameAt(timeMs: Long): TextFrame? {
        // A shape has no words to be blank: it is on screen whenever its window is.
        if (timeMs < startMs || timeMs >= endMs || (text.isBlank() && !isShape)) return null
        val total = (endMs - startMs).coerceAtLeast(1L)
        return TextAnimation.frameAt(motion, motionOut, loop, motionInMs, motionOutMs, loopMs, timeMs - startMs, total, wordStartsMs)
    }

    /**
     * The same caption expressed in a clip's own source clock.
     *
     * The preview plays a source file, so its effects are handed source time, while
     * a caption is written in timeline time. Shifting once here is what makes a
     * caption appear at the right moment over a clip that has been trimmed or moved
     * - otherwise it shows up early by however far the clip was dragged.
     *
     * Mapped through the clip's speed curve rather than by one constant offset.
     * The player's clock is source time and a retimed clip runs it faster or
     * slower than the timeline, so over a clip at double speed a caption written
     * for 4-6 s of the timeline used to show for 2-3 s instead.
     */
    fun shiftedInto(clip: Clip): TextOverlayItem = copy(
        startMs = clip.sourceAtExtended(startMs),
        endMs = clip.sourceAtExtended(endMs),
        track = track?.let { t ->
            MotionTrack(t.samples.map { it.copy(atMs = clip.sourceAtExtended(it.atMs)) })
        }
    )
}

/**
 * Marks a caption made by auto-captioning, in its id. A second run replaces the
 * first run's lines rather than stacking a copy of every one on top of them, and
 * the id is the one thing about a caption every draft already keeps.
 */
const val AUTO_CAPTION_PREFIX = "auto-"

val TextOverlayItem.isAutoCaption: Boolean get() = id.startsWith(AUTO_CAPTION_PREFIX)
