package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.ShapeRole
import com.squish.app.editor.TextAlign
import com.squish.app.editor.TextAnimation
import com.squish.app.editor.TextBubble
import com.squish.app.editor.TextFont
import com.squish.app.editor.TextFrame
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.CaptionWords
import com.squish.app.editor.WordMode
import com.squish.app.editor.WordState
import com.squish.app.editor.WordStyle
import com.squish.app.editor.TextDepth
import com.squish.app.editor.TextFill
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The system face a [TextFont] names. */
fun TextFont.typeface(): Typeface =
    Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)

/**
 * Fonts the person brought in as .ttf or .otf files, kept under the app's own
 * files so a draft that names one can be opened next week, and read back by the
 * name the draft holds. Installed once by the editor; the export runs in the
 * same process and finds them here.
 */
object CustomFonts {
    private var dir: File? = null
    private val cache = HashMap<String, Typeface?>()

    fun install(fontsDir: File) {
        dir = fontsDir
        fontsDir.mkdirs()
    }

    /** The face saved under [file], or null when it is gone or unreadable - the caption falls back to its system face. */
    fun typeface(file: String): Typeface? = synchronized(cache) {
        // containsKey, not getOrPut: that one reads a stored null as absent, and
        // null is the answer this cache exists to remember - a font imported
        // once and since deleted was looked for on disk, and attempted, on every
        // caption of every frame of a render.
        if (!cache.containsKey(file)) {
            cache[file] = dir?.let { File(it, file) }?.takeIf { it.isFile }?.let(::load)
        }
        cache[file]
    }

    /** Every font on hand, by file name. */
    fun names(): List<String> =
        dir?.list()?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }?.sorted().orEmpty()

    /** What a font is called on a chip: its file name without the extension. */
    fun label(file: String): String = file.substringBeforeLast('.')

    /**
     * Copies a picked font file in and returns its name, or null when it could
     * not be read as a font. A bad file is refused here, not found out at the
     * first render.
     */
    fun import(context: Context, uri: Uri): String? {
        val target = dir ?: return null
        val display = displayName(context, uri) ?: "font.ttf"
        val ext = display.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        // Only what a file name cannot hold is taken out, not everything that
        // is not ASCII. Stripping to [A-Za-z0-9 _-] destroyed the whole name of
        // a font named in Cyrillic, Greek, Arabic, Devanagari or CJK - the first
        // landed as "font", the second as "font 2", and the chips had nothing to
        // tell them apart. Android's filesystem takes UTF-8 names, so nothing
        // downstream wanted the stripping; only the label was lost to it.
        val stem = display.substringBeforeLast('.')
            .replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "")
            .trim()
            .take(64)
            .ifEmpty { "font" }
        var file = File(target, "$stem.$ext")
        var n = 2
        while (file.exists()) file = File(target, "$stem $n.$ext").also { n++ }
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!ok || load(file) == null) {
            file.delete()
            return null
        }
        synchronized(cache) { cache.remove(file.name) }
        return file.name
    }

    private fun load(file: File): Typeface? =
        // A file that is not a font comes back as the default face rather than
        // as an error on newer phones; either way it is not the font asked for.
        runCatching { Typeface.createFromFile(file) }.getOrNull()?.takeIf { it != Typeface.DEFAULT }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

/**
 * Draws one styled caption as a bitmap, and says how it animates.
 *
 * Shared by the export overlay and the preview's caption layer, so a caption is
 * painted by one piece of code wherever it appears.
 *
 * Size is relative to the frame: a caption set to 28 is 28/360 of the frame's
 * short edge, whatever the resolution, so a title laid out on a small preview
 * lands in the same place and at the same weight on a 4K export. The stroke,
 * the shadow's reach and the bubble's corners are shares of the text size for
 * the same reason.
 */
object CaptionRenderer {

    /** The caption's state at [timeMs], or null when it is not on screen. */
    fun frameAt(item: TextOverlayItem, timeMs: Long): TextFrame? = item.frameAt(timeMs)

    /** The letters showing at [frame] - all of them, unless they are being revealed. */
    fun shownText(item: TextOverlayItem, frame: TextFrame): String = item.shownAt(frame)

    /** The face a caption is set in: its own file if it brought one, else its system face, in its weight and slant. */
    fun typefaceOf(item: TextOverlayItem): Typeface {
        val own = item.fontFile?.let(CustomFonts::typeface)
        val base = own ?: Typeface.create(item.font.family, Typeface.NORMAL)
        val bold = item.bold || (own == null && item.font.bold)
        // Named rather than or-ed together. BOLD or ITALIC does come to
        // BOLD_ITALIC, but Typeface.create takes one of the four styles and not
        // a flag set - it happened to be right, and nothing said so.
        val style = when {
            bold && item.italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            item.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    /**
     * A caption painted: its picture, and the part of it a finger takes hold
     * of. The two differ for a Band, whose picture is the frame's whole width
     * while its letters are what a box should outline - a box the width of the
     * frame put its buttons past the picture's edge, out of reach.
     */
    class Painted(val bitmap: Bitmap, val grabWidth: Int, val grabHeight: Int)

    /**
     * The caption drawn for a frame [frameWidth] by [frameHeight], showing [shown]
     * inside the box the full text needs - so typed text does not re-centre itself
     * letter by letter.
     */
    fun render(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int, words: WordState? = null, cut: Cut? = null, elapsedMs: Long = 0L): Bitmap =
        paint(item, shown, frameWidth, frameHeight, words, cut, elapsedMs).bitmap

    /** Where a knockout card's letters are cut (EDGE_PLAN E4): the line's place on the frame, 0..1, and its turn. */
    data class Cut(val at: Pair<Float, Float>, val turnDegrees: Float)

    /**
     * [render], with the part of the picture that is the letters and their own
     * decoration. [words] is the line's [WordState] at this frame, from
     * [TextFrame.words]: which page is shown and which word is being said.
     */
    fun paint(item: TextOverlayItem, shownIn: String, frameWidth: Int, frameHeight: Int, words: WordState? = null, cut: Cut? = null, elapsedMs: Long = 0L): Painted {
        if (item.isShape) return paintShape(item, frameWidth, frameHeight)
        if (item.isDesign) return StickerPainter.paint(item, shownIn, frameWidth, frameHeight, elapsedMs)
        // A GIF plays by the time into the sticker ([elapsedMs]), round and round.
        if (item.isGif) return com.squish.app.media.gif.GifStickers.paint(item, elapsedMs, frameWidth, frameHeight)
        if (item.knockout) return paintKnockout(item, shownIn, frameWidth, frameHeight, words, cut ?: Cut(item.xFraction to item.yFraction, item.rotationDegrees))
        val shortEdge = minOf(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)
        val textSize = (item.sizeSp / 360f * shortEdge).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = typefaceOf(item)
            color = item.colorArgb
            letterSpacing = item.letterSpacing
            isUnderlineText = item.underline
        }

        val maxWidth = (frameWidth * 0.9f).roundToInt().coerceAtLeast(1)

        // The word being said (EDGE_PLAN E2). A page of a few words is laid
        // out in the box that page needs, not the whole line's; a popped word
        // is given room on every frame, so the box does not grow and shrink
        // as the speech moves from word to word.
        val wordStyle = item.wordStyle.takeIf { words != null && it.isOn }
        val lineWords = CaptionWords.words(item.text).size
        val paged = words != null && (words.from > 0 || words.to < lineWords)
        val firstWord = if (words != null) words.from else 0
        // An emoji after a picked word (EDGE_PLAN E2), held to it so the words
        // are counted the same, in what is shown and in the box it is laid in.
        val shown = CaptionWords.decorated(shownIn, firstWord, item.wordEmoji)
        val sizingWords = CaptionWords.decorated(if (paged) CaptionWords.pageText(item.text, words!!) else item.text, if (paged) firstWord else 0, item.wordEmoji)
        val shownWords = if (wordStyle != null && words != null && shown.isNotEmpty()) CaptionWords.words(shown) else null
        val active = if (shownWords != null && words != null && words.active >= 0) CaptionWords.charRange(shownWords, words, words.active) else null
        val lit = if (shownWords != null && words != null && wordStyle != null) CaptionWords.coloured(wordStyle.mode, shownWords, words) else null
        val pops = wordStyle?.mode == WordMode.Pop
        val grow = if (pops) WordStyle.POP_SCALE else 1f
        // Words picked out by hand: bigger, in the box too, so nothing is clipped.
        val picked = CaptionWords.emphasised(shown, firstWord, item.emphasis)
        val pickedInBox = CaptionWords.emphasised(sizingWords, if (paged) firstWord else 0, item.emphasis)
        // The line's second language under it, smaller (EDGE_PLAN P4), laid
        // after the first so every range above still lands where it did.
        val (sizingText, secondInBox) = CaptionWords.withSecondLine(sizingWords, item.secondLine, item.showBoth)
        val (shownText, secondAt) = CaptionWords.withSecondLine(shown, item.secondLine, item.showBoth)
        val sizing: CharSequence = if (pickedInBox.isEmpty() && secondInBox < 0) sizingText else SpannableString(sizingText).apply {
            pickedInBox.forEach { setSpan(RelativeSizeSpan(CaptionWords.EMPHASIS_SCALE), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (secondInBox >= 0) setSpan(RelativeSizeSpan(CaptionWords.SECOND_LINE_SCALE), secondInBox, sizingText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val geometry: CharSequence = if (!(pops && active != null) && picked.isEmpty() && secondAt < 0) shownText else SpannableString(shownText).apply {
            if (pops && active != null) setSpan(RelativeSizeSpan(WordStyle.POP_SCALE), active.first, active.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            picked.forEach { setSpan(RelativeSizeSpan(CaptionWords.EMPHASIS_SCALE), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (secondAt >= 0) setSpan(RelativeSizeSpan(CaptionWords.SECOND_LINE_SCALE), secondAt, shownText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val filled: CharSequence = if ((wordStyle == null || lit == null) && item.wordColors.isEmpty() && picked.isEmpty()) geometry else SpannableString(geometry).apply {
            // A picked-out word in its own colour, else the highlight colour.
            picked.forEachIndexed { _, r ->
                val index = firstWord + CaptionWords.words(shown.substring(0, r.first)).size - 1
                val colour = item.wordColors[index] ?: item.wordStyle.colorArgb
                setSpan(ForegroundColorSpan(colour), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // A colour of its own for single words (EDGE_PLAN E3), by their place
            // in the whole line - so a page of a few words keeps each word's.
            // Set first, so the word being said still wears the highlight.
            if (item.wordColors.isNotEmpty()) {
                var at = 0
                CaptionWords.words(shown).forEachIndexed { i, w ->
                    item.wordColors[(words?.from ?: 0) + i]?.let { setSpan(ForegroundColorSpan(it), at, at + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    at += w.length + 1
                }
            }
            if (wordStyle != null && lit != null) {
                val ink = if (wordStyle.mode == WordMode.Box) CaptionWords.inkOn(wordStyle.colorArgb) else wordStyle.colorArgb
                setSpan(ForegroundColorSpan(ink), lit.first, lit.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // A line bent into an arc is laid along a circle instead (EDGE_PLAN E3).
        if (item.arc != 0f) return paintArc(item, shown, textSize, paint)

        val fullLayout = layout(item, sizing, paint, maxWidth, sizing, grow)
        val shownLayout = layout(item, filled, paint, fullLayout.width, sizing, grow)
        val popRoom = if (pops) (textSize * (WordStyle.POP_SCALE - 1f)).roundToInt() else 0

        // Room round the letters for the edge, the shadow, the glow or the box.
        val background = item.background
        val pad = (textSize * padShare(item) * (if (item.background.isOn) item.padding.coerceIn(0.5f, 3f) else 1f)).roundToInt() +
            if (wordStyle?.mode == WordMode.Box) (textSize * 0.15f).roundToInt() else 0
        val band = background.isOn && background.bubble == TextBubble.Band
        val tail = if (background.isOn && background.bubble == TextBubble.Speech) (textSize * 0.5f).roundToInt() else 0
        val grabWidth = (fullLayout.width + pad * 2).coerceAtLeast(1)
        val width = if (band) frameWidth.coerceAtLeast(1) else grabWidth
        val boxHeight = (fullLayout.height + popRoom + pad * 2).coerceAtLeast(1)
        val height = boxHeight + tail
        val textLeft = if (band) (width - fullLayout.width) / 2f else pad.toFloat()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (background.isOn) drawBubble(canvas, item, textSize, width.toFloat(), boxHeight.toFloat(), tail.toFloat())

        canvas.save()
        canvas.translate(textLeft, pad.toFloat())
        if (wordStyle?.mode == WordMode.Box && active != null) {
            // A rounded box in the highlight colour behind the word being said,
            // read off the laid-out line so it sits on the word wherever it wrapped.
            val line = shownLayout.getLineForOffset(active.first)
            val a = shownLayout.getPrimaryHorizontal(active.first)
            val b = shownLayout.getPrimaryHorizontal(active.last + 1).let { if (shownLayout.getLineForOffset(active.last + 1) != line) shownLayout.getLineRight(line) else it }
            val reach = textSize * 0.14f
            val box = RectF(minOf(a, b) - reach, shownLayout.getLineTop(line) - reach * 0.4f, maxOf(a, b) + reach, shownLayout.getLineBottom(line) + reach * 0.4f)
            canvas.drawRoundRect(box, textSize * 0.22f, textSize * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wordStyle.colorArgb })
        }
        val stroke = item.stroke
        val shadow = item.shadow
        if (shadow.isOn) {
            // A blurred copy in the shadow's colour, moved where the shadow falls
            // - following the outline when there is one, so an outlined line
            // casts the shape it shows. A shadow layer on the fill pass drew
            // the shadow over the stroke instead of under it.
            val cast = TextPaint(paint).apply {
                color = withOpacity(shadow.colorArgb, shadow.opacity)
                maskFilter = if (shadow.blur > 0f) BlurMaskFilter(textSize * shadow.blur, BlurMaskFilter.Blur.NORMAL) else null
                if (stroke.isOn) {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = textSize * stroke.width
                    strokeJoin = Paint.Join.ROUND
                }
            }
            val radians = Math.toRadians(shadow.angleDegrees.toDouble())
            canvas.save()
            canvas.translate((cos(radians) * shadow.offset * textSize).toFloat(), (sin(radians) * shadow.offset * textSize).toFloat())
            layout(item, geometry, cast, fullLayout.width, sizing, grow).draw(canvas)
            canvas.restore()
        }
        if (item.glow) {
            // A wide soft pass and a tight one in the letter colour, then the
            // letters themselves lightened, which is what reads as a tube lit up.
            // Its own colour and reach where they are set (EDGE_PLAN E3).
            val reach = item.glowStrength.coerceIn(0.2f, 2f)
            val glow = TextPaint(paint).apply {
                color = item.glowColorArgb ?: item.colorArgb
                maskFilter = BlurMaskFilter(textSize * 0.45f * reach, BlurMaskFilter.Blur.NORMAL)
            }
            layout(item, geometry, glow, fullLayout.width, sizing, grow).draw(canvas)
            glow.maskFilter = BlurMaskFilter(textSize * 0.15f * reach, BlurMaskFilter.Blur.NORMAL)
            layout(item, geometry, glow, fullLayout.width, sizing, grow).draw(canvas)
        }
        if (item.outline2.isOn) {
            // The second edge, outside the first: drawn wider and under it.
            layout(item, geometry, outerEdge(paint, item, textSize), fullLayout.width, sizing, grow).draw(canvas)
        }
        if (stroke.isOn) {
            val edge = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textSize * stroke.width
                strokeJoin = Paint.Join.ROUND
                color = stroke.colorArgb
            }
            layout(item, geometry, edge, fullLayout.width, sizing, grow).draw(canvas)
        }
        if (item.glow) paint.color = lighten(item.colorArgb)
        // A gradient on the letters' fill alone - set now, after every pass
        // above copied the paint - across the box the whole line takes.
        gradientFor(item, fullLayout.width.toFloat(), fullLayout.height.toFloat())?.let { paint.shader = it }
        shownLayout.draw(canvas)
        canvas.restore()

        // The letters are centred in a band, so the grab is centred where the picture is.
        if (!item.flipped) return Painted(bitmap, minOf(grabWidth, width), height)
        // Mirrored in the picture itself, so the preview and the file - which
        // places a bitmap and cannot flip one - show the same sticker.
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        return Painted(Bitmap.createBitmap(bitmap, 0, 0, width, height, mirror, true), minOf(grabWidth, width), height)
    }

    /**
     * A shape drawn: a rectangle, a circle, an arrow.
     *
     * The points come from [ShapeGeometry], which knows nothing about Android,
     * so the preview and the file are drawing the same shape rather than two
     * that happen to agree. [TextOverlayItem.sizeSp] is the shape's height on
     * the same 1/360-of-the-short-edge scale a caption's letters use, so a
     * shape placed on the preview lands in the same place and at the same
     * weight on a 4K export.
     */
    private fun paintShape(item: TextOverlayItem, frameWidth: Int, frameHeight: Int): Painted {
        val shortEdge = minOf(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)
        val height = (item.sizeSp / 360f * shortEdge).coerceAtLeast(4f)
        val aspect = item.shapeAspect.coerceIn(ShapeGeometry.MIN_ASPECT, ShapeGeometry.MAX_ASPECT)
        val width = (height * aspect).coerceAtLeast(4f)

        val share = item.stroke.width.takeIf { it > 0f } ?: ShapeGeometry.DEFAULT_THICKNESS
        val thickness = ShapeGeometry.thicknessPx(width, height, share)
        // Room for half the line, for a sharp corner's join, and for the shadow
        // to fall outside the shape - a star's spikes were shaved off without it.
        val shadow = item.shadow
        val reach = if (shadow.isOn) height * (shadow.blur + shadow.offset) else 0f
        val pad = ShapeGeometry.padPx(width, height, share) + reach

        val bitmapWidth = ceil(width + pad * 2).toInt().coerceAtLeast(1)
        val bitmapHeight = ceil(height + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val polys = ShapeGeometry.polys(item.shape, width, height)
        val body = Path()
        val head = Path()
        polys.forEach { poly ->
            val into = if (poly.role == ShapeRole.Head) head else body
            poly.points.forEachIndexed { i, point ->
                val x = point.x + pad
                val y = point.y + pad
                if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
            }
            if (poly.closed) into.close()
        }

        val solid = item.shapeFilled && item.shape.hasInside
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = item.colorArgb
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = thickness
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = item.colorArgb
        }

        if (shadow.isOn) {
            // The whole silhouette blurred and moved where the shadow falls -
            // the same two numbers a caption's shadow uses, on the shape's height.
            val cast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withOpacity(shadow.colorArgb, shadow.opacity)
                maskFilter = if (shadow.blur > 0f) BlurMaskFilter(height * shadow.blur, BlurMaskFilter.Blur.NORMAL) else null
            }
            val radians = Math.toRadians(shadow.angleDegrees.toDouble())
            canvas.save()
            canvas.translate((cos(radians) * shadow.offset * height).toFloat(), (sin(radians) * shadow.offset * height).toFloat())
            if (solid) canvas.drawPath(body, cast)
            else canvas.drawPath(body, Paint(cast).apply {
                style = Paint.Style.STROKE
                strokeWidth = thickness
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            })
            if (!head.isEmpty) canvas.drawPath(head, cast)
            canvas.restore()
        }

        if (solid) canvas.drawPath(body, fill) else canvas.drawPath(body, edge)
        // An arrow's head is solid whatever the body is: a hollow arrowhead
        // reads as a chevron, which is a different mark.
        if (!head.isEmpty) canvas.drawPath(head, fill)

        if (!item.flipped) return Painted(bitmap, bitmapWidth, bitmapHeight)
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        return Painted(
            Bitmap.createBitmap(bitmap, 0, 0, bitmapWidth, bitmapHeight, mirror, true),
            bitmapWidth,
            bitmapHeight
        )
    }

    /**
     * Video seen through the letters (EDGE_PLAN E4): a card the size of the
     * frame in the line's colour, its letters cut out where the line stands,
     * at its turn. The letters are the line's own painting - its font, size,
     * bend and edge - so the hole is the shape of what the straight line
     * would show. Its grab is the letters', so the box sits round them.
     */
    private fun paintKnockout(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int, words: WordState?, cut: Cut): Painted {
        val w = frameWidth.coerceAtLeast(1)
        val h = frameHeight.coerceAtLeast(1)
        // The letters alone, solid, with nothing round them but their own edge.
        val letters = paint(
            item.copy(knockout = false, flipped = false, colorArgb = Color.WHITE, fill = TextFill.SOLID, glow = false,
                shadow = com.squish.app.editor.TextShadow.NONE, background = com.squish.app.editor.TextBackground.NONE, wordColors = emptyMap()),
            shown, frameWidth, frameHeight, words
        )
        val card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(withOpacity(item.colorArgb, 1f))
        val cx = cut.at.first * w
        val cy = cut.at.second * h
        canvas.save()
        canvas.rotate(cut.turnDegrees, cx, cy)
        if (item.flipped) canvas.scale(-1f, 1f, cx, cy)
        canvas.drawBitmap(
            letters.bitmap, cx - letters.bitmap.width / 2f, cy - letters.bitmap.height / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
        )
        canvas.restore()
        return Painted(card, letters.grabWidth, letters.grabHeight)
    }

    /** The second edge (EDGE_PLAN E3): its width reaches past the first edge by its own. */
    private fun outerEdge(paint: TextPaint, item: TextOverlayItem, textSize: Float) = TextPaint(paint).apply {
        style = Paint.Style.STROKE
        strokeWidth = textSize * ((if (item.stroke.isOn) item.stroke.width else 0f) + 2f * item.outline2.width)
        strokeJoin = Paint.Join.ROUND
        color = item.outline2.colorArgb
        shader = null
    }

    /** The letters' gradient across a box [w] by [h] (TextDepth.gradientEnds), or null for one colour. */
    private fun gradientFor(item: TextOverlayItem, w: Float, h: Float): LinearGradient? {
        val fill = item.fill
        if (!fill.isGradient) return null
        val e = TextDepth.gradientEnds(w, h, fill.angleDegrees)
        val colours = fill.colors.take(TextFill.MAX_STOPS).toIntArray()
        return LinearGradient(e[0], e[1], e[2], e[3], colours, null, Shader.TileMode.CLAMP)
    }

    /**
     * A line bent into an arc (EDGE_PLAN E3): laid along a circle the line's
     * own length round, centre below for a rainbow and above for a smile
     * (TextDepth's radius), every pass the straight line has drawn in the
     * same order. One line only - a bend over wrapped lines would bend each
     * round a different circle - and the word styles stay with straight lines.
     */
    private fun paintArc(item: TextOverlayItem, shown: String, textSize: Float, paint: TextPaint): Painted {
        val bend = item.arc.coerceIn(-1f, 1f)
        paint.textAlign = Paint.Align.LEFT
        // The whole line's length, so revealed letters do not re-bend it.
        val lineW = paint.measureText(item.text).coerceAtLeast(1f)
        val r = TextDepth.arcRadius(lineW, bend)
        val half = Math.toRadians(TextDepth.arcHalfDegrees(bend).toDouble())
        val fm = paint.fontMetrics
        val ascent = -fm.ascent
        val descent = fm.descent
        val rainbow = bend > 0f
        // The letters stand outward on a rainbow and inward on a smile.
        val inner = if (rainbow) r - descent else r - ascent
        val outer = if (rainbow) r + ascent else r + descent
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0..24) {
            val phi = -half + 2 * half * i / 24.0
            for (radius in floatArrayOf(inner, outer)) {
                val x = (radius * kotlin.math.sin(phi)).toFloat()
                val y = (if (rainbow) -radius * kotlin.math.cos(phi) else radius * kotlin.math.cos(phi)).toFloat()
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        val pad = (textSize * padShare(item) * (if (item.background.isOn) item.padding.coerceIn(0.5f, 3f) else 1f)).roundToInt()
        val width = (ceil(maxX - minX).toInt() + pad * 2).coerceAtLeast(1)
        val height = (ceil(maxY - minY).toInt() + pad * 2).coerceAtLeast(1)
        // The circle's centre, so the bounds sit inside the padding.
        val cx = pad - minX
        val cy = pad - minY
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val halfDeg = Math.toDegrees(half).toFloat()
        val path = Path().apply {
            if (rainbow) addArc(oval, 270f - halfDeg, 2 * halfDeg) else addArc(oval, 90f + halfDeg, -2 * halfDeg)
        }
        // From the arc's start, by half of what the shown letters leave of it: drawn
        // on a path, text runs from the offset whatever the paint's alignment, and a
        // centred paint at half the length started the line in the middle (seen on the moto).
        val along = ((lineW - paint.measureText(shown)) / 2f).coerceAtLeast(0f)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (item.background.isOn) drawBubble(canvas, item, textSize, width.toFloat(), height.toFloat(), 0f)
        val stroke = item.stroke
        val shadow = item.shadow
        if (shadow.isOn) {
            val cast = TextPaint(paint).apply {
                color = withOpacity(shadow.colorArgb, shadow.opacity)
                maskFilter = if (shadow.blur > 0f) BlurMaskFilter(textSize * shadow.blur, BlurMaskFilter.Blur.NORMAL) else null
                if (stroke.isOn) { style = Paint.Style.FILL_AND_STROKE; strokeWidth = textSize * stroke.width; strokeJoin = Paint.Join.ROUND }
            }
            val radians = Math.toRadians(shadow.angleDegrees.toDouble())
            canvas.save()
            canvas.translate((cos(radians) * shadow.offset * textSize).toFloat(), (sin(radians) * shadow.offset * textSize).toFloat())
            canvas.drawTextOnPath(shown, path, along, 0f, cast)
            canvas.restore()
        }
        if (item.glow) {
            val reach = item.glowStrength.coerceIn(0.2f, 2f)
            val glow = TextPaint(paint).apply { color = item.glowColorArgb ?: item.colorArgb; maskFilter = BlurMaskFilter(textSize * 0.45f * reach, BlurMaskFilter.Blur.NORMAL) }
            canvas.drawTextOnPath(shown, path, along, 0f, glow)
            glow.maskFilter = BlurMaskFilter(textSize * 0.15f * reach, BlurMaskFilter.Blur.NORMAL)
            canvas.drawTextOnPath(shown, path, along, 0f, glow)
        }
        if (item.outline2.isOn) canvas.drawTextOnPath(shown, path, along, 0f, outerEdge(paint, item, textSize))
        if (stroke.isOn) {
            val edge = TextPaint(paint).apply { style = Paint.Style.STROKE; strokeWidth = textSize * stroke.width; strokeJoin = Paint.Join.ROUND; color = stroke.colorArgb }
            canvas.drawTextOnPath(shown, path, along, 0f, edge)
        }
        if (item.glow) paint.color = lighten(item.colorArgb)
        gradientFor(item, width.toFloat(), height.toFloat())?.let { paint.shader = it }
        canvas.drawTextOnPath(shown, path, along, 0f, paint)

        if (!item.flipped) return Painted(bitmap, width, height)
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        return Painted(Bitmap.createBitmap(bitmap, 0, 0, width, height, mirror, true), width, height)
    }

    /** How much room round the letters, as a share of the text size: the widest of what is drawn past them. */
    private fun padShare(item: TextOverlayItem): Float {
        var share = 0.05f
        if (item.stroke.isOn) share = maxOf(share, item.stroke.width * 0.6f + 0.05f)
        if (item.outline2.isOn) share = maxOf(share, (if (item.stroke.isOn) item.stroke.width else 0f) * 0.6f + item.outline2.width * 1.1f + 0.05f)
        if (item.shadow.isOn) share = maxOf(share, item.shadow.blur + item.shadow.offset)
        if (item.glow) share = maxOf(share, 0.6f * item.glowStrength.coerceIn(0.2f, 2f))
        if (item.background.isOn) share = maxOf(share, if (item.background.bubble == TextBubble.Pill) 0.6f else 0.45f)
        return share
    }

    private fun drawBubble(canvas: Canvas, item: TextOverlayItem, textSize: Float, width: Float, height: Float, tail: Float) {
        val background = item.background
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(background.colorArgb, background.opacity) }
        val box = RectF(0f, 0f, width, height)
        when (background.bubble) {
            TextBubble.None -> Unit
            TextBubble.Box -> {
                val r = textSize * background.radius
                canvas.drawRoundRect(box, r, r, fill)
            }
            TextBubble.Pill -> canvas.drawRoundRect(box, height / 2f, height / 2f, fill)
            TextBubble.Band -> canvas.drawRect(box, fill)
            TextBubble.Speech -> {
                val r = textSize * background.radius
                canvas.drawRoundRect(box, r, r, fill)
                val path = Path().apply {
                    moveTo(textSize * 0.7f, height - 1f)
                    lineTo(textSize * 1.4f, height - 1f)
                    lineTo(textSize * 0.5f, height + tail)
                    close()
                }
                canvas.drawPath(path, fill)
            }
            TextBubble.Stamp -> {
                val r = textSize * background.radius
                fill.color = withOpacity(background.colorArgb, background.opacity * 0.3f)
                canvas.drawRoundRect(box, r, r, fill)
                val edge = textSize * 0.08f
                val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = edge
                    color = withOpacity(background.colorArgb, background.opacity)
                    pathEffect = DashPathEffect(floatArrayOf(textSize * 0.3f, textSize * 0.18f), 0f)
                }
                val inset = edge / 2f
                canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), r, r, dashed)
            }
        }
    }

    /**
     * [text] laid out as wide as [measure] wants (times [grow], the room a
     * popped word takes), no wider than [width]. Measured on the line or the
     * page rather than on [text], so revealed or highlighted words wrap where
     * the whole would.
     */
    private fun layout(
        item: TextOverlayItem,
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        measure: CharSequence = item.text,
        grow: Float = 1f
    ): StaticLayout {
        val measured = ceil(StaticLayout.getDesiredWidth(measure, paint) * grow).toInt()
        val lineWidth = measured.coerceIn(1, width.coerceAtLeast(1))
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, lineWidth)
            // Typed text grows from the left, so the line does not slide about as
            // each letter lands; otherwise the line's own alignment.
            .setAlignment(
                when {
                    item.motion == TextMotion.Typewriter -> Layout.Alignment.ALIGN_NORMAL
                    item.align == TextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
                    item.align == TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                }
            )
            .setLineSpacing(0f, item.lineSpacing.coerceAtLeast(0.5f))
            .setIncludePad(false)
            .build()
    }

    private fun withOpacity(argb: Int, opacity: Float): Int =
        Color.argb((opacity.coerceIn(0f, 1f) * 255f).roundToInt(), Color.red(argb), Color.green(argb), Color.blue(argb))

    private fun lighten(argb: Int): Int {
        val mix = { c: Int -> (c + (255 - c) * 0.55f).roundToInt().coerceIn(0, 255) }
        return Color.argb(Color.alpha(argb), mix(Color.red(argb)), mix(Color.green(argb)), mix(Color.blue(argb)))
    }
}
