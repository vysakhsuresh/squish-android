package com.squish.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.runtime.Composable
import com.squish.app.ui.theme.SquishColors

/**
 * Asked when back is pressed with an encode running. The render is minutes of
 * work and the only thing in the app that cannot be undone, so it is the one
 * place a back press has to be confirmed. The edit itself is never at stake:
 * stopping throws away the half-written file, nothing else.
 */
@Composable
fun StopExportDialog(onStop: () -> Unit, onKeepGoing: () -> Unit) {
    ConfirmDialog(
        title = "Stop exporting?",
        body = "The file is part-way through being written.",
        caution = "What has been rendered so far is thrown away. Your edit stays exactly as it is, ready to export again.",
        confirmLabel = "Stop",
        dismissLabel = "Keep going",
        icon = Icons.Filled.StopCircle,
        accent = SquishColors.Amber,
        onConfirm = onStop,
        onDismiss = onKeepGoing
    )
}
