package com.squish.app.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squish.app.media.CaptionRenderer
import kotlin.math.roundToInt

/** The painted size of one line's letters, in pixels of the frame it is drawn in - what its box outlines. */
data class TextBox(val width: Float, val height: Float)

/**
 * Every caption and sticker on the preview, drawn once, above every picture layer.
 *
 * They used to be baked into each player's picture by an effect on its chain.
 * That drew them on every surface that had one - twice across a dissolve, and a
 * shrunken copy inside every picture-in-picture - never over a gap, where there
 * is no player, and in the player's source clock, so over a clip at 2x a caption
 * came and went at the wrong moments. The export draws each caption once over the
 * finished frame, in timeline time; so does this.
 *
 * Painted by [CaptionRenderer], the same code the export uses, laid out in the
 * part of the canvas the crop keeps ([frame]), which is the frame the export lays
 * them out in.
 */
@Composable
fun CaptionLayer(
    captions: List<TextOverlayItem>,
    /** Timeline time. */
    timeMs: Long,
    /**
     * True while paused. A paused editor shows every caption as it looks at rest -
     * a pop-in is invisible on its first frame, which is exactly where one is
     * added, so a new sticker seemed not to appear. Motion plays when the video does.
     */
    atRest: Boolean,
    frame: PreviewBox.Frame,
    modifier: Modifier = Modifier,
    /**
     * Told the painted size of each line drawn whole, in the frame's pixels: what
     * the box on the picture is drawn round. Only the layer knows, since the
     * letters' extent is the renderer's answer for this frame.
     */
    boxes: MutableMap<String, TextBox>? = null
) {
    // Each caption's letters, by what they look like and the size they were drawn
    // for, so animating one or scrubbing past it does not redraw its text.
    val glyphs = remember { HashMap<String, PaintedLetters>() }

    Canvas(modifier = modifier) {
        val boxLeft = frame.left * size.width
        val boxTop = frame.top * size.height
        val boxWidth = (frame.width * size.width).roundToInt()
        val boxHeight = (frame.height * size.height).roundToInt()
        if (boxWidth <= 0 || boxHeight <= 0) return@Canvas

        val live = HashSet<String>()
        for (item in captions) {
            val moving = CaptionRenderer.frameAt(item, timeMs) ?: continue
            val look = if (atRest) TextFrame() else moving
            val shown = CaptionRenderer.shownText(item, look)
            if (shown.isBlank()) continue

            val key = "${item.id}/${item.style}/${item.flipped}/${item.align}/${item.motion}/" +
                "${item.text}/$shown@${boxWidth}x$boxHeight"
            live.add(key)
            val (glyph, grab) = glyphs.getOrPut(key) {
                val painted = CaptionRenderer.paint(item, shown, boxWidth, boxHeight)
                PaintedLetters(painted.bitmap.asImageBitmap(), TextBox(painted.grabWidth.toFloat(), painted.grabHeight.toFloat()))
            }
            if (boxes != null && shown == item.text) {
                // Written only when it changed: every write wakes whoever builds
                // the box from it, and that is every frame while playing. The
                // letters' own extent, not a Band's full-width picture.
                if (boxes[item.id] != grab) boxes[item.id] = grab
            }

            val (x, y) = item.anchorAt(timeMs)
            val cx = boxLeft + x * boxWidth
            val cy = boxTop + (y - look.rise) * boxHeight
            val w = glyph.width * look.scale
            val h = glyph.height * look.scale
            rotate(degrees = item.rotationDegrees + look.tilt, pivot = Offset(cx, cy)) {
                drawImage(
                    image = glyph,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(glyph.width, glyph.height),
                    dstOffset = IntOffset((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    alpha = (look.alpha * item.opacity).coerceIn(0f, 1f)
                )
            }
        }
        // Old renders of text that has since changed are dropped, so editing a
        // caption letter by letter does not pile up a bitmap per keystroke.
        if (glyphs.size > MAX_GLYPHS) glyphs.keys.retainAll(live)
    }
}

/** One caption's letters as painted, and the part of them a finger takes hold of. */
private data class PaintedLetters(val image: ImageBitmap, val grab: TextBox)

private const val MAX_GLYPHS = 48

/**
 * The eyedropper: laid over the picture while a colour is being picked, it
 * reads the pixels under the finger - from the window itself, since the
 * picture is a stack of video surfaces no Compose canvas can read - and hands
 * the colour back when the finger lifts. A patch of pixels is averaged, so a
 * single grain of noise is not the colour picked; the green screen asks for a
 * wider patch than a title's colour does.
 *
 * A loupe follows the finger: a disc of the colour read so far, held above
 * the fingertip where it can be seen, the way CapCut's picker is dragged over
 * the picture. A tap without a drag picks where it landed.
 */
@Composable
fun EyedropperLayer(
    /** The colour picked, or null when the pick was given up or could not be read. */
    onPick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    /** The side of the patch of pixels averaged, in pixels of the screen. */
    patch: Int = 5,
    hint: String = "Touch the picture to pick its colour"
) {
    val view = LocalView.current
    val latestPick by rememberUpdatedState(onPick)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var sample by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(patch) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // One read at a time: a read is asynchronous, and asking for
                    // a new one on every move would queue dozens behind the first.
                    var reading = false
                    var latest: Int? = null
                    fun read(at: Offset) {
                        if (reading) return
                        reading = true
                        val here = origin + at
                        samplePixels(view, here.x.roundToInt(), here.y.roundToInt(), patch) { colour ->
                            reading = false
                            if (colour != null) {
                                latest = colour
                                sample = colour
                            }
                        }
                    }
                    finger = down.position
                    read(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        finger = change.position
                        read(change.position)
                    }
                    finger = null
                    // A read is asked for on every move, so the last one landed
                    // is at most a fingertip behind the finger.
                    latestPick(latest ?: sample)
                }
            }
    ) {
        finger?.let { at ->
            val loupe = with(LocalDensity.current) { LOUPE_SIZE.roundToPx() }
            val lift = with(LocalDensity.current) { LOUPE_LIFT.roundToPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(at.x.roundToInt() - loupe / 2, at.y.roundToInt() - loupe / 2 - lift) }
                    .size(LOUPE_SIZE)
                    .clip(CircleShape)
                    .background(sample?.let { Color(it) } ?: Color.Black.copy(alpha = 0.4f))
                    .border(3.dp, Color.White, CircleShape)
            ) {
                Box(modifier = Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            sample?.let {
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(it)))
                Box(modifier = Modifier.size(6.dp))
            }
            Text(hint, style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Text(
            "Cancel",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { latestPick(null) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** The average colour of the [patch] pixels square round ([x], [y]) of the window, or null when they cannot be read. */
private fun samplePixels(view: View, x: Int, y: Int, patch: Int, onDone: (Int?) -> Unit) {
    val window = view.context.findActivity()?.window
    if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onDone(null)
        return
    }
    val side = patch.coerceAtLeast(1)
    val decor = window.decorView
    val left = (x - side / 2).coerceIn(0, (decor.width - side).coerceAtLeast(0))
    val top = (y - side / 2).coerceIn(0, (decor.height - side).coerceAtLeast(0))
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    runCatching {
        PixelCopy.request(window, Rect(left, top, left + side, top + side), bitmap, { result ->
            onDone(if (result == PixelCopy.SUCCESS) average(bitmap) else null)
        }, Handler(Looper.getMainLooper()))
    }.onFailure { onDone(null) }
}

private fun average(bitmap: Bitmap): Int {
    var r = 0L; var g = 0L; var b = 0L
    val n = bitmap.width * bitmap.height
    for (px in 0 until bitmap.width) for (py in 0 until bitmap.height) {
        val c = bitmap.getPixel(px, py)
        r += AndroidColor.red(c); g += AndroidColor.green(c); b += AndroidColor.blue(c)
    }
    return AndroidColor.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The loupe's size, and how far above the fingertip it is held so the finger does not cover it. */
private val LOUPE_SIZE = 56.dp
private val LOUPE_LIFT = 64.dp
