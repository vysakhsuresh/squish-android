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
        cache.getOrPut(file) {
            dir?.let { File(it, file) }?.takeIf { it.isFile }?.let(::load)
        }
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
        val stem = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "font" }
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
    fun shownText(item: TextOverlayItem, frame: TextFrame): String = if (item.isShape) "" else when (item.motion) {
        TextMotion.Typewriter -> TextAnimation.shownLetters(item.text, frame.reveal)
        TextMotion.Words -> TextAnimation.shownWords(item.text, frame.reveal)
        else -> item.text
    }

    /** The face a caption is set in: its own file if it brought one, else its system face, in its weight and slant. */
    fun typefaceOf(item: TextOverlayItem): Typeface {
        val own = item.fontFile?.let(CustomFonts::typeface)
        val base = own ?: Typeface.create(item.font.family, Typeface.NORMAL)
        val bold = item.bold || (own == null && item.font.bold)
        val style = (if (bold) Typeface.BOLD else 0) or (if (item.italic) Typeface.ITALIC else 0)
        return if (style == 0) base else Typeface.create(base, style)
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
    fun render(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int): Bitmap =
        paint(item, shown, frameWidth, frameHeight).bitmap

    /** [render], with the part of the picture that is the letters and their own decoration. */
    fun paint(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int): Painted {
        if (item.isShape) return paintShape(item, frameWidth, frameHeight)
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
        val fullLayout = layout(item, item.text, paint, maxWidth)
        val shownLayout = layout(item, shown, paint, fullLayout.width)

        // Room round the letters for the edge, the shadow, the glow or the box.
        val background = item.background
        val pad = (textSize * padShare(item)).roundToInt()
        val band = background.isOn && background.bubble == TextBubble.Band
        val tail = if (background.isOn && background.bubble == TextBubble.Speech) (textSize * 0.5f).roundToInt() else 0
        val grabWidth = (fullLayout.width + pad * 2).coerceAtLeast(1)
        val width = if (band) frameWidth.coerceAtLeast(1) else grabWidth
        val boxHeight = (fullLayout.height + pad * 2).coerceAtLeast(1)
        val height = boxHeight + tail
        val textLeft = if (band) (width - fullLayout.width) / 2f else pad.toFloat()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (background.isOn) drawBubble(canvas, item, textSize, width.toFloat(), boxHeight.toFloat(), tail.toFloat())

        canvas.save()
        canvas.translate(textLeft, pad.toFloat())
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
            layout(item, shown, cast, fullLayout.width).draw(canvas)
            canvas.restore()
        }
        if (item.glow) {
            // A wide soft pass and a tight one in the letter colour, then the
            // letters themselves lightened, which is what reads as a tube lit up.
            val glow = TextPaint(paint).apply {
                maskFilter = BlurMaskFilter(textSize * 0.45f, BlurMaskFilter.Blur.NORMAL)
            }
            layout(item, shown, glow, fullLayout.width).draw(canvas)
            glow.maskFilter = BlurMaskFilter(textSize * 0.15f, BlurMaskFilter.Blur.NORMAL)
            layout(item, shown, glow, fullLayout.width).draw(canvas)
        }
        if (stroke.isOn) {
            val edge = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textSize * stroke.width
                strokeJoin = Paint.Join.ROUND
                color = stroke.colorArgb
            }
            layout(item, shown, edge, fullLayout.width).draw(canvas)
        }
        if (item.glow) paint.color = lighten(item.colorArgb)
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

    /** How much room round the letters, as a share of the text size: the widest of what is drawn past them. */
    private fun padShare(item: TextOverlayItem): Float {
        var share = 0.05f
        if (item.stroke.isOn) share = maxOf(share, item.stroke.width * 0.6f + 0.05f)
        if (item.shadow.isOn) share = maxOf(share, item.shadow.blur + item.shadow.offset)
        if (item.glow) share = maxOf(share, 0.6f)
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

    private fun layout(item: TextOverlayItem, text: String, paint: TextPaint, width: Int): StaticLayout {
        val measured = ceil(StaticLayout.getDesiredWidth(item.text, paint)).toInt()
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
