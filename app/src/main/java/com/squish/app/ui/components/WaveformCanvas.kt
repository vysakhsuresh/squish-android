package com.squish.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.squish.app.media.audio.Waveform
import com.squish.app.media.audio.WaveformBuilder

/**
 * Mirrored amplitude bars. Seeing the audio is what makes a cut land on the beat
 * or between two words instead of somewhere in the middle of a syllable.
 */
@Composable
fun WaveformCanvas(
    waveform: Waveform?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val all = waveform?.peaks ?: return@Canvas
        if (all.isEmpty()) return@Canvas

        // At most one bar per pixel across (WaveformBuilder.columns says why).
        // A line per peak is twelve thousand draw calls for a ten-minute song
        // and seventy-two thousand for an hour's, on every frame, into a canvas
        // a thousand pixels wide.
        val peaks = WaveformBuilder.columns(all, size.width.toInt())
        val midY = size.height / 2f
        val barWidth = size.width / peaks.size
        val strokeWidth = barWidth.coerceAtLeast(1f)

        for (i in peaks.indices) {
            val x = i * barWidth + barWidth / 2f
            val half = (peaks[i] * midY * 0.92f).coerceAtLeast(0.6f)
            drawLine(
                color = color,
                start = Offset(x, midY - half),
                end = Offset(x, midY + half),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
