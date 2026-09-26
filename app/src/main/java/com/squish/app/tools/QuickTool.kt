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
    /**
     * The stable handle, and deliberately not the name on screen.
     *
     * It is in the navigation route, in the filename of every draft of a session,
     * and in the filename of every file the tool writes. A rename that touched it
     * would orphan drafts people are in the middle of and break any link already
     * in existence - so the names below are free to change and this never does.
     */
    val id: String,
    val title: String,
    val blurb: String,
    val accent: Color,
    val icon: ImageVector,
    val actionLabel: String,
    /**
     * What the done screen says once the file exists.
     *
     * A past tense of its own, because the screen used to append "done" to the
     * tool's name and "Squeeze done" is not a sentence anyone writes. One word
     * that is already finished says it better than two that are not.
     */
    val doneLabel: String
) {
    Squeeze(
        id = "compress",
        title = "Squeeze",
        blurb = "Make a video smaller without making it worse",
        accent = SquishColors.Blue,
        icon = Icons.Filled.Compress,
        actionLabel = "Squeeze it",
        doneLabel = "Squeezed"
    ),
    Snip(
        id = "trim",
        title = "Snip",
        blurb = "Keep only the part worth keeping",
        accent = SquishColors.Cyan,
        icon = Icons.Filled.ContentCut,
        actionLabel = "Snip it",
        doneLabel = "Snipped"
    ),
    Rip(
        id = "audio",
        title = "Rip",
        blurb = "Pull the sound out as its own file",
        accent = SquishColors.Amber,
        icon = Icons.Filled.MusicNote,
        actionLabel = "Rip the sound",
        doneLabel = "Ripped"
    ),
    Stitch(
        id = "merge",
        title = "Stitch",
        blurb = "Join clips end to end",
        accent = SquishColors.Magenta,
        icon = Icons.Filled.PlaylistAdd,
        actionLabel = "Stitch them",
        doneLabel = "Stitched"
    );

    /** Whether the job works on a chosen part of the source rather than all of it. */
    val usesRange: Boolean get() = this == Snip || this == Rip

    /** What the finished file is, said once so every screen agrees on the wording. */
    val outputNoun: String
        get() = if (this == Rip) "audio file" else "video"

    companion object {
        fun fromId(id: String?): QuickTool = entries.firstOrNull { it.id == id } ?: Squeeze
    }
}
