package com.squish.app.tools

import androidx.compose.ui.graphics.Color
import com.squish.app.ui.theme.SquishColors

/**
 * Standalone one-job tools. Each is reachable in a single tap from the dashboard,
 * and every one of them also exists inside the full editor - the point is that a
 * person who just needs a smaller file never has to meet a timeline.
 */
enum class QuickTool(
    val id: String,
    val title: String,
    val blurb: String,
    val accent: Color,
    val actionLabel: String
) {
    Compress(
        id = "compress",
        title = "Compress",
        blurb = "Shrink a video to a size you choose",
        accent = SquishColors.Coral,
        actionLabel = "Compress video"
    ),
    Trim(
        id = "trim",
        title = "Trim",
        blurb = "Keep only the part you want",
        accent = SquishColors.Teal,
        actionLabel = "Trim video"
    ),
    ExtractAudio(
        id = "audio",
        title = "Extract audio",
        blurb = "Save the sound as an audio file",
        accent = SquishColors.Yellow,
        actionLabel = "Extract audio"
    ),
    Merge(
        id = "merge",
        title = "Merge",
        blurb = "Join clips end to end",
        accent = SquishColors.Purple,
        actionLabel = "Merge clips"
    );

    companion object {
        fun fromId(id: String?): QuickTool = entries.firstOrNull { it.id == id } ?: Compress
    }
}
