package com.squish.app.editor

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.squish.app.media.ExportPlan
import com.squish.app.media.TransitionShapes
import kotlin.math.roundToInt

/**
 * EDGE_PLAN F3's parts of a draw on the preview: the shot turned and squeezed
 * about its pivot, darkened, and cut to its shape - in the order the file's
 * shader reads them (squish_transition_es2.glsl's bent() and coverage()), on
 * the frame the file keeps ([kept]). Outside the layer that slides and wipes,
 * as the shader undoes the bend before it undoes the shift.
 *
 * The shape is TransitionShapes itself, drawn into a small mask and laid over
 * the shot with DstIn: the very function the checks execute, rather than a
 * second copy of it in Compose's terms. A soft edge a twenty-fifth of the run
 * wide is several of the mask's pixels, and the mask is filtered up, so the
 * edge is smooth at the screen's size.
 */
fun Modifier.transitionShaped(d: ExportPlan.Draw, kept: PreviewBox.Frame, mask: TransitionMask): Modifier {
    if (d.isFlat) return this
    val masked = d.shape != TransitionShapes.NONE
    return this
        .graphicsLayer {
            transformOrigin = TransformOrigin(kept.left + d.pivotX * kept.width, kept.top + d.pivotY * kept.height)
            rotationZ = Math.toDegrees(d.turn.toDouble()).toFloat()
            scaleX = d.squashX
            scaleY = d.squashY
            // Its own buffer, so the shade and the mask land on this shot alone.
            if (masked || d.shade > 0f) compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawContent()
            val kx = kept.left * size.width
            val ky = kept.top * size.height
            val kw = kept.width * size.width
            val kh = kept.height * size.height
            if (d.shade > 0f) drawRect(Color.Black, alpha = d.shade.coerceIn(0f, 1f), blendMode = BlendMode.SrcAtop)
            if (masked && kw > 0f && kh > 0f) {
                drawImage(
                    mask.of(d, kw / kh),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(mask.width, mask.heightFor(kw / kh)),
                    dstOffset = IntOffset(kx.roundToInt(), ky.roundToInt()),
                    dstSize = IntSize(kw.roundToInt(), kh.roundToInt()),
                    blendMode = BlendMode.DstIn,
                    filterQuality = FilterQuality.Low
                )
            }
        }
}

/**
 * The mask a shape draw lets through, [width] pixels across. Made on the main
 * thread in the draw phase, where the preview asks for it, and remade only
 * when the shape, its progress or the frame's shape moves - a held frame asks
 * for the same mask again and is given it.
 */
class TransitionMask(val width: Int = 160) {
    // One per place that draws a mask: the drawing records the bitmap, not its
    // pixels, so two shots sharing one would both show whichever was made last.

    fun heightFor(aspect: Float): Int = (width / aspect.coerceIn(0.2f, 5f)).roundToInt().coerceIn(8, 800)

    private var key: List<Any>? = null
    private var image: ImageBitmap? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    fun of(d: ExportPlan.Draw, aspect: Float): ImageBitmap {
        val h = heightFor(aspect)
        val k = listOf(d.shape, d.shapeAt, d.shapeInvert, h)
        image?.let { if (k == key) return it }
        val bm = bitmap?.takeIf { it.width == width && it.height == h } ?: Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        if (pixels.size != width * h) pixels = IntArray(width * h)
        for (j in 0 until h) {
            val y = (j + 0.5f) / h
            for (i in 0 until width) {
                val a = (d.coverageAt((i + 0.5f) / width, y, aspect) * 255f).roundToInt().coerceIn(0, 255)
                pixels[j * width + i] = (a shl 24) or 0xFFFFFF
            }
        }
        bm.setPixels(pixels, 0, width, 0, 0, width, h)
        bm.prepareToDraw()
        val img = bm.asImageBitmap()
        key = k
        image = img
        return img
    }
}
