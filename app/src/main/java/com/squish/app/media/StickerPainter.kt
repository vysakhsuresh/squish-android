package com.squish.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.squish.app.editor.ACCENT
import com.squish.app.editor.ArtIcon
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.StickerArt
import com.squish.app.editor.TextOverlayItem
import kotlin.math.ceil
import kotlin.math.min

/**
 * A designed sticker (EDGE_PLAN F4) painted from StickerArt's description:
 * the one drawing for the preview's caption layer and the export's overlay, as
 * every other text item is (CaptionRenderer.paint hands designs here).
 *
 * Sized as a shape is: [TextOverlayItem.sizeSp] is its height on the
 * 1/360-of-the-short-edge scale, [TextOverlayItem.shapeAspect] its width over
 * that, so it lands at the same place and weight in a 4K file as on the phone.
 */
internal object StickerPainter {

    fun paint(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int): CaptionRenderer.Painted {
        val shortEdge = minOf(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)
        val h = (item.sizeSp / 360f * shortEdge).coerceAtLeast(4f)
        val w = (h * item.shapeAspect.coerceIn(ShapeGeometry.MIN_ASPECT, ShapeGeometry.MAX_ASPECT)).coerceAtLeast(4f)
        val parts = StickerArt.parts(item.design, w, h)
        val s = min(w, h)
        val pad = (parts.maxOfOrNull { it.strokeShare } ?: 0f) * s * 0.6f + 2f
        val bw = ceil(w + pad * 2).toInt().coerceAtLeast(1)
        val bh = ceil(h + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pad, pad)

        for (part in parts) {
            val colour = colourOf(part.colour, item.colorArgb)
            if (part.icon != null && part.rect != null) {
                drawIcon(canvas, part.icon, part.rect.left, part.rect.top, part.rect.width, part.rect.height, colour)
                continue
            }
            val path = Path().apply { fillType = if (part.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING }
            part.polys.forEach { poly ->
                poly.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
                if (poly.closed) path.close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colour
                if (part.strokeShare > 0f) {
                    style = Paint.Style.STROKE
                    strokeWidth = part.strokeShare * s
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                } else style = Paint.Style.FILL
            }
            canvas.drawPath(path, paint)
        }

        StickerArt.words(item.design, w, h)?.let { slot ->
            val lines = shown.split('\n').filter { it.isNotEmpty() }.ifEmpty { listOf("") }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = CaptionRenderer.typefaceOf(item)
                color = colourOf(slot.colour, item.colorArgb)
                textAlign = Paint.Align.CENTER
            }
            // As large as fits: the height shared among the lines, then down to the widest one.
            var size = slot.rect.height / lines.size * 0.82f
            paint.textSize = size
            val widest = lines.maxOf { paint.measureText(it) }
            if (widest > slot.rect.width && widest > 0f) size *= slot.rect.width / widest
            paint.textSize = size.coerceAtLeast(2f)
            val fm = paint.fontMetrics
            val lineH = fm.descent - fm.ascent
            var y = slot.rect.top + (slot.rect.height - lineH * lines.size) / 2f - fm.ascent
            for (line in lines) {
                canvas.drawText(line, slot.rect.left + slot.rect.width / 2f, y, paint)
                y += lineH
            }
        }

        if (!item.flipped) return CaptionRenderer.Painted(bitmap, bw, bh)
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        return CaptionRenderer.Painted(Bitmap.createBitmap(bitmap, 0, 0, bw, bh, mirror, true), bw, bh)
    }

    private fun colourOf(c: Int, accent: Int): Int = when (c) {
        ACCENT -> accent
        StickerArt.ACCENT_HALF -> (accent and 0x00FFFFFF) or ((((accent ushr 24) and 0xFF) / 2) shl 24)
        else -> c
    }

    private fun vectorOf(icon: ArtIcon): ImageVector = when (icon) {
        ArtIcon.Play -> Icons.Filled.PlayArrow
        ArtIcon.ThumbUp -> Icons.Filled.ThumbUp
        ArtIcon.Bell -> Icons.Filled.Notifications
        ArtIcon.Share -> Icons.Filled.Share
        ArtIcon.Heart -> Icons.Filled.Favorite
        ArtIcon.TouchApp -> Icons.Filled.TouchApp
        ArtIcon.Cart -> Icons.Filled.ShoppingCart
        ArtIcon.Fire -> Icons.Filled.LocalFireDepartment
    }

    // Each icon's outline, parsed once from the Material vector into the
    // platform's own path, in the vector's viewport units.
    private val paths = HashMap<ArtIcon, Pair<Path, RectF>>()

    private fun drawIcon(canvas: Canvas, icon: ArtIcon, left: Float, top: Float, width: Float, height: Float, colour: Int) {
        val (path, viewport) = synchronized(paths) {
            paths.getOrPut(icon) {
                val v = vectorOf(icon)
                val out = Path()
                fun walk(g: VectorGroup) {
                    for (child in g) when (child) {
                        is VectorPath -> out.addPath(PathParser().addPathNodes(child.pathData).toPath().asAndroidPath())
                        is VectorGroup -> walk(child)
                    }
                }
                walk(v.root)
                out to RectF(0f, 0f, v.viewportWidth, v.viewportHeight)
            }
        }
        val scale = min(width / viewport.width(), height / viewport.height())
        canvas.save()
        canvas.translate(left + (width - viewport.width() * scale) / 2f, top + (height - viewport.height() * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; style = Paint.Style.FILL })
        canvas.restore()
    }
}
