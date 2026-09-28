package com.squish.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
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
fun StopExportDialog(saving: Boolean, onStop: () -> Unit, onKeepGoing: () -> Unit) {
    // Once the encode is done there is nothing left to stop: the file is whole
    // and on its way into the gallery. Offering Stop then offered to throw away
    // work that could no longer be thrown away - the tap did nothing, the dialog
    // closed, and a moment later the result screen opened on the "stopped"
    // export. So it says what is happening instead, and the only answer is OK.
    // Read live, so a dialog opened during the render changes when the copy starts.
    if (saving) {
        ConfirmDialog(
            title = "Saving to your gallery",
            body = "The video is finished and is being copied into your gallery. A large file can take a little while.",
            caution = "This part can't be stopped. The result opens as soon as the copy is done.",
            confirmLabel = null,
            dismissLabel = "OK",
            icon = Icons.Filled.SaveAlt,
            accent = SquishColors.Amber,
            onConfirm = onKeepGoing,
            onDismiss = onKeepGoing
        )
        return
    }
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
