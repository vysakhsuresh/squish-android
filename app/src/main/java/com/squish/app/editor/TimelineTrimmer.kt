package com.squish.app.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * A real dual-handle trimmer: drag the coral bars, both driven by actual pixel
 * deltas mapped back to milliseconds against the real clip duration.
 */
@Composable
fun TimelineTrimmer(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    thumbnails: List<ImageBitmap>,
    onTrimChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val minGapMs = (durationMs * 0.05f).toLong().coerceAtLeast(500L)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            thumbnails.forEach { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxHeight().width((widthPx / thumbnails.size.coerceAtLeast(1)).dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        val startFraction = if (durationMs > 0) trimStartMs.toFloat() / durationMs else 0f
        val endFraction = if (durationMs > 0) trimEndMs.toFloat() / durationMs else 1f

        DarkenOverlay(fraction = startFraction, alignEnd = false)
        DarkenOverlay(fraction = 1f - endFraction, alignEnd = true)

        TrimHandle(
            xFraction = startFraction,
            widthPx = widthPx,
            onDrag = { deltaPx ->
                val deltaMs = (deltaPx / widthPx * durationMs).toLong()
                val newStart = (trimStartMs + deltaMs).coerceIn(0L, trimEndMs - minGapMs)
                onTrimChange(newStart, trimEndMs)
            }
        )
        TrimHandle(
            xFraction = endFraction,
            widthPx = widthPx,
            onDrag = { deltaPx ->
                val deltaMs = (deltaPx / widthPx * durationMs).toLong()
                val newEnd = (trimEndMs + deltaMs).coerceIn(trimStartMs + minGapMs, durationMs)
                onTrimChange(trimStartMs, newEnd)
            }
        )
    }
}

@Composable
private fun BoxScope.DarkenOverlay(fraction: Float, alignEnd: Boolean) {
    if (fraction <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
            .background(Color.Black.copy(alpha = 0.65f))
    )
}

@Composable
private fun TrimHandle(xFraction: Float, widthPx: Int, onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .offset { IntOffset((xFraction * widthPx).toInt() - 10, -4) }
            .width(20.dp)
            .height(68.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SquishColors.Coral)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            }
    )
}
