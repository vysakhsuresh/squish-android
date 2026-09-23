package com.squish.app.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val icon: ImageVector,
    val actionLabel: String
) {
    Compress(
        id = "compress",
        title = "Compress",
        blurb = "Shrink a video to a size you choose",
        accent = SquishColors.Blue,
        icon = Icons.Filled.Compress,
        actionLabel = "Compress video"
    ),
    Trim(
        id = "trim",
        title = "Trim",
        blurb = "Keep only the part you want",
        accent = SquishColors.Cyan,
        icon = Icons.Filled.ContentCut,
        actionLabel = "Trim video"
    ),
    ExtractAudio(
        id = "audio",
        title = "Extract audio",
        blurb = "Save the sound as an audio file",
        accent = SquishColors.Amber,
        icon = Icons.Filled.MusicNote,
        actionLabel = "Extract audio"
    ),
    Merge(
        id = "merge",
        title = "Merge",
        blurb = "Join clips end to end",
        accent = SquishColors.Magenta,
        icon = Icons.Filled.PlaylistAdd,
        actionLabel = "Merge clips"
    );

    /** Whether the job works on a chosen part of the source rather than all of it. */
    val usesRange: Boolean get() = this == Trim || this == ExtractAudio

    /** What the finished file is, said once so every screen agrees on the wording. */
    val outputNoun: String
        get() = if (this == ExtractAudio) "audio file" else "video"

    companion object {
        fun fromId(id: String?): QuickTool = entries.firstOrNull { it.id == id } ?: Compress
    }
}
