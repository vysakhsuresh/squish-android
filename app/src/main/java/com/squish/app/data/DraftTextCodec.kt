package com.squish.app.data

import com.squish.app.editor.AnnotationShape
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.TextAlign
import com.squish.app.editor.TextAnimation
import com.squish.app.editor.TextBackground
import com.squish.app.editor.TextBubble
import com.squish.app.editor.TextExit
import com.squish.app.editor.TextFont
import com.squish.app.editor.TextLook
import com.squish.app.editor.TextLoop
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TextShadow
import com.squish.app.editor.TextStroke
import com.squish.app.editor.TextStyleSpec
import com.squish.app.editor.WordMode
import com.squish.app.editor.WordStyle
import com.squish.app.editor.TextFill
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import org.json.JSONArray
import org.json.JSONObject

/**
 * A line of words, a sticker or a shape as JSON and back.
 *
 * The third cut of the draft codec, and for the reason the first two were made:
 * this half names nothing but TextOverlayItem, the text style types and the
 * vision track, every one of them free of Android, so it can be **run**.
 * tools/jvm/DraftTextRoundTripChecks.kt encodes a line with all of its fields
 * set, writes it as text, parses it and reads it back, field by field - the only
 * check that sees a field whose name and key are both present and whose *value*
 * does not survive.
 *
 * What is left in DraftCodec is the edit-wide handful, whose model is
 * EditorUiState: that one still reaches MediaCompat, SquishError and
 * ExportProgress, and docs/ROADMAP.md §6 says what moving it would cost.
 */
internal object DraftTextCodec {

    internal fun encodeText(item: TextOverlayItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("text", item.text)
        put("startMs", item.startMs)
        put("endMs", item.endMs)
        putFinite("xFraction", item.xFraction)
        putFinite("yFraction", item.yFraction)
        TextStyleJson.write(this, item.style)
        putFinite("rotation", item.rotationDegrees)
        put("flipped", item.flipped)
        put("motion", item.motion.name)
        put("motionInMs", item.motionInMs)
        put("motionOut", item.motionOut.name)
        put("motionOutMs", item.motionOutMs)
        put("loop", item.loop.name)
        put("loopMs", item.loopMs)
        if (item.wordStartsMs.isNotEmpty()) put("wordStarts", JSONArray(item.wordStartsMs))
        // A colour for single words (EDGE_PLAN E3), by their place in the line.
        if (item.wordColors.isNotEmpty()) put("wordColors", JSONObject().apply { item.wordColors.forEach { (k, v) -> put(k.toString(), v) } })
        if (item.knockout) put("knockout", true)
        // Keyword emphasis, an emoji after a word and a second language (EDGE_PLAN E2, P4).
        if (item.emphasis.isNotEmpty()) put("emphasis", JSONArray(item.emphasis.sorted()))
        if (item.wordEmoji.isNotEmpty()) put("wordEmoji", JSONObject().apply { item.wordEmoji.forEach { (k, v) -> put(k.toString(), v) } })
        item.secondLine?.let { put("secondLine", it) }
        if (item.showBoth) put("showBoth", true)
        item.secondLangs?.let { put("secondLangs", it) }
        put("sticker", item.sticker)
        // Written only when it is one, so a draft of words is the shape it
        // always was and an older build reading this one simply finds no shape.
        // A designed sticker (EDGE_PLAN F4): its design, and its width over its height as a shape keeps it.
        // A GIF sticker (EDGE_PLAN F4, P6): the copy it plays from, and its shape.
        if (item.isGif) {
            put("gif", item.gif)
            putFinite("shapeAspect", item.shapeAspect)
        }
        if (item.isDesign) {
            put("design", item.design.name)
            putFinite("shapeAspect", item.shapeAspect)
        }
        if (item.isShape) {
            put("shape", item.shape.name)
            putFinite("shapeAspect", item.shapeAspect)
            put("shapeFilled", item.shapeFilled)
        }
        put("stripRow", item.stripRow)
        if (item.trackGrows) put("trackGrows", true)
        item.track?.let { t ->
            put("track", JSONArray().apply {
                t.samples.forEach { sample ->
                    put(JSONObject().apply {
                        put("atMs", sample.atMs)
                        putFinite("x", sample.xFraction)
                        putFinite("y", sample.yFraction)
                        putFinite("scale", sample.scale)
                        putFinite("confidence", sample.confidence)
                    })
                }
            })
        }
    }

    internal fun decodeText(json: JSONObject?): TextOverlayItem? {
        if (json == null) return null
        val motion = enumOrNull<TextMotion>(json.optString("motion")) ?: TextMotion.None
        val item = TextOverlayItem(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            text = json.optString("text"),
            startMs = json.optLong("startMs"),
            endMs = json.optLong("endMs"),
            colorArgb = json.optInt("colorArgb"),
            xFraction = json.optDouble("xFraction", 0.5).toFloat(),
            yFraction = json.optDouble("yFraction", 0.85).toFloat(),
            rotationDegrees = json.optDouble("rotation", 0.0).toFloat(),
            flipped = json.optBoolean("flipped", false),
            motion = motion,
            motionInMs = json.optLong("motionInMs", TextAnimation.DEFAULT_IN_MS),
            // Before a line had its own leaving, every arrival left by fading
            // and a still line did not; a draft from then keeps that.
            motionOut = enumOrNull<TextExit>(json.optString("motionOut"))
                ?: if (motion != TextMotion.None) TextExit.Fade else TextExit.None,
            motionOutMs = json.optLong("motionOutMs", TextAnimation.DEFAULT_OUT_MS),
            loop = enumOrNull<TextLoop>(json.optString("loop")) ?: TextLoop.None,
            loopMs = json.optLong("loopMs", TextAnimation.DEFAULT_LOOP_MS),
            wordStartsMs = json.optJSONArray("wordStarts")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty(),
            knockout = json.optBoolean("knockout", false),
            emphasis = json.optJSONArray("emphasis")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }.orEmpty(),
            wordEmoji = json.optJSONObject("wordEmoji")?.let { o -> o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optString(k) } }.filter { it.second.isNotEmpty() }.toMap() }.orEmpty(),
            secondLine = json.optString("secondLine").takeIf { it.isNotEmpty() },
            showBoth = json.optBoolean("showBoth", false),
            secondLangs = json.optString("secondLangs").takeIf { it.isNotEmpty() },
            wordColors = json.optJSONObject("wordColors")?.let { o -> o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optInt(k) } }.toMap() }.orEmpty(),
            sticker = json.optBoolean("sticker", false),
            shape = AnnotationShape.named(json.optString("shape").takeIf { it.isNotEmpty() }),
            shapeAspect = json.optDouble("shapeAspect", 1.0).toFloat()
                .coerceIn(ShapeGeometry.MIN_ASPECT, ShapeGeometry.MAX_ASPECT),
            shapeFilled = json.optBoolean("shapeFilled", false),
            design = com.squish.app.editor.StickerDesign.named(json.optString("design").takeIf { it.isNotEmpty() }),
            gif = json.optString("gif").takeIf { it.isNotEmpty() },
            stripRow = json.optInt("stripRow", 0).coerceAtLeast(0),
            trackGrows = json.optBoolean("trackGrows", false),
            track = json.optJSONArray("track")?.let { array ->
                MotionTrack(
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { o ->
                            TrackSample(
                                atMs = o.optLong("atMs"),
                                xFraction = o.optDouble("x", 0.5).toFloat(),
                                yFraction = o.optDouble("y", 0.85).toFloat(),
                                scale = o.optDouble("scale", 1.0).toFloat(),
                                confidence = o.optDouble("confidence", 1.0).toFloat()
                            )
                        }
                    }
                ).takeIf { !it.isEmpty }
            }
        )
        return item.withStyle(TextStyleJson.read(json))
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}

/**
 * A line's style as JSON: the fields of [TextStyleSpec], flat, beside the
 * line's own in a draft and on their own in a saved style. One reader for both,
 * so a style saved from a line reads back exactly as the line would.
 */
object TextStyleJson {
    fun write(json: JSONObject, s: TextStyleSpec) {
        json.put("font", s.font.name)
        s.fontFile?.let { json.put("fontFile", it) }
        json.put("bold", s.bold)
        json.put("italic", s.italic)
        json.put("underline", s.underline)
        json.put("align", s.align.name)
        json.putFinite("letterSpacing", s.letterSpacing)
        json.putFinite("lineSpacing", s.lineSpacing)
        json.put("colorArgb", s.colorArgb)
        json.put("sizeSp", s.sizeSp)
        json.put("strokeColor", s.stroke.colorArgb)
        json.putFinite("strokeWidth", s.stroke.width)
        json.put("shadowColor", s.shadow.colorArgb)
        json.putFinite("shadowOpacity", s.shadow.opacity)
        json.putFinite("shadowBlur", s.shadow.blur)
        json.putFinite("shadowOffset", s.shadow.offset)
        json.putFinite("shadowAngle", s.shadow.angleDegrees)
        json.put("bgColor", s.background.colorArgb)
        json.putFinite("bgOpacity", s.background.opacity)
        json.putFinite("bgRadius", s.background.radius)
        json.put("bubble", s.background.bubble.name)
        json.put("glow", s.glow)
        json.putFinite("opacity", s.opacity)
        // Written only when on, so a line with none is the shape a draft always was.
        if (s.words.isOn) {
            json.put("wordMode", s.words.mode.name)
            json.put("wordColor", s.words.colorArgb)
            json.put("wordsPerScreen", s.words.perScreen)
        }
        // Text with depth (EDGE_PLAN E3), each only when it is set.
        if (s.fill.isGradient) {
            json.put("fillColors", org.json.JSONArray(s.fill.colors))
            json.putFinite("fillAngle", s.fill.angleDegrees)
        }
        s.glowColorArgb?.let { json.put("glowColor", it) }
        if (s.glowStrength != 1f) json.putFinite("glowStrength", s.glowStrength)
        if (s.outline2.isOn) {
            json.put("outline2Color", s.outline2.colorArgb)
            json.putFinite("outline2Width", s.outline2.width)
        }
        if (s.padding != 1f) json.putFinite("padding", s.padding)
        if (s.arc != 0f) json.putFinite("arc", s.arc)
    }

    fun encode(s: TextStyleSpec): JSONObject = JSONObject().also { write(it, s) }

    fun read(json: JSONObject): TextStyleSpec {
        val defaults = TextStyleSpec()
        // Captions saved before styles existed were plain white letters.
        val font = enumOrNull<TextFont>(json.optString("font")) ?: TextFont.Sans
        val colour = json.optInt("colorArgb", defaults.colorArgb)
        val size = json.optInt("sizeSp", defaults.sizeSp)
        if (!json.has("glow")) {
            // Saved before a line's decorations were its own fields: the look named them.
            val look = enumOrNull<TextLook>(json.optString("look")) ?: TextLook.Plain
            return look.applied(TextStyleSpec(font = font, colorArgb = colour, sizeSp = size))
        }
        return TextStyleSpec(
            font = font,
            fontFile = json.optString("fontFile").takeIf { it.isNotBlank() },
            bold = json.optBoolean("bold", false),
            italic = json.optBoolean("italic", false),
            underline = json.optBoolean("underline", false),
            align = enumOrNull<TextAlign>(json.optString("align")) ?: TextAlign.Center,
            letterSpacing = json.optDouble("letterSpacing", 0.0).toFloat(),
            lineSpacing = json.optDouble("lineSpacing", 1.0).toFloat(),
            colorArgb = colour,
            sizeSp = size,
            stroke = TextStroke(
                json.optInt("strokeColor", TextStroke.NONE.colorArgb),
                json.optDouble("strokeWidth", 0.0).toFloat()
            ),
            shadow = TextShadow(
                json.optInt("shadowColor", TextShadow.NONE.colorArgb),
                json.optDouble("shadowOpacity", 0.0).toFloat(),
                json.optDouble("shadowBlur", TextShadow.NONE.blur.toDouble()).toFloat(),
                json.optDouble("shadowOffset", TextShadow.NONE.offset.toDouble()).toFloat(),
                json.optDouble("shadowAngle", TextShadow.NONE.angleDegrees.toDouble()).toFloat()
            ),
            background = TextBackground(
                json.optInt("bgColor", TextBackground.NONE.colorArgb),
                json.optDouble("bgOpacity", TextBackground.NONE.opacity.toDouble()).toFloat(),
                json.optDouble("bgRadius", TextBackground.NONE.radius.toDouble()).toFloat(),
                enumOrNull<TextBubble>(json.optString("bubble")) ?: TextBubble.None
            ),
            glow = json.optBoolean("glow", false),
            opacity = json.optDouble("opacity", 1.0).toFloat(),
            words = WordStyle(
                mode = enumOrNull<WordMode>(json.optString("wordMode")) ?: WordMode.None,
                colorArgb = json.optInt("wordColor", WordStyle.HIGHLIGHT_YELLOW),
                perScreen = json.optInt("wordsPerScreen", 0).coerceIn(0, WordStyle.MAX_PER_SCREEN)
            ),
            fill = json.optJSONArray("fillColors")?.let { a ->
                TextFill((0 until a.length()).map { a.optInt(it) }.take(TextFill.MAX_STOPS), json.optDouble("fillAngle", 90.0).toFloat())
            } ?: TextFill.SOLID,
            glowColorArgb = if (json.has("glowColor")) json.optInt("glowColor") else null,
            glowStrength = json.optDouble("glowStrength", 1.0).toFloat().coerceIn(0.2f, 2f),
            outline2 = TextStroke(json.optInt("outline2Color", TextStroke.NONE.colorArgb), json.optDouble("outline2Width", 0.0).toFloat()),
            padding = json.optDouble("padding", 1.0).toFloat().coerceIn(0.5f, 3f),
            arc = json.optDouble("arc", 0.0).toFloat().coerceIn(-1f, 1f)
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
