package com.squish.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.squish.app.home.formatSize
import com.squish.app.ui.theme.SquishColors

/**
 * A fitted export that came out over its limit. The file is whole and saved;
 * the question is whether to keep it as it is or run once more, aimed lower.
 *
 * **Shared, and that is the point of the file.** This lived inside
 * `ExportSheet` as a private composable, so the editor had it and the quick
 * tool did not - and Squeeze is the screen whose whole job is hitting a size.
 * Until 7 October a squeeze fitted to 16 MB that came out at 19 published the
 * file and reported "Squeezed · N% smaller", with nothing anywhere saying the
 * number asked for had been missed. Two screens offering one feature need one
 * card, or the second screen is a copy nobody remembers to make.
 *
 * [subject] is what the person is looking at - "edit" in the editor, "video" on
 * Squeeze - since the one sentence that names it reads wrong with the other's
 * word.
 *
 * [unreachable] drops the retry: below the smallest bitrate anything is written
 * at, every run lands on the same size, and each one publishes another copy to
 * the gallery and shows this card again.
 */
@Composable
fun OvershootCard(
    actualBytes: Long,
    targetBytes: Long,
    unreachable: Boolean,
    smallestBytes: Long,
    subject: String,
    onKeep: () -> Unit,
    onRetry: () -> Unit
) {
    SquishCard(accent = SquishColors.Amber) {
        Text(
            "Came out at ${formatSize(actualBytes)} - over the ${formatSize(targetBytes)} limit",
            style = MaterialTheme.typography.titleSmall,
            color = SquishColors.TextPrimary
        )
        Text(
            if (unreachable) {
                "This $subject is too long to be made that small - about ${formatSize(smallestBytes)} is " +
                    "the least it can be. The file is saved in your gallery as it is; to go smaller, trim " +
                    "the $subject or pick a larger size."
            } else {
                "Encoders overshoot on busy footage. This file is saved in your gallery as it is. " +
                    "A second run aims lower and should land under the limit."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )
        // Stacked: side by side at half width "Try again, tighter" was cut short.
        if (!unreachable) {
            SquishPrimaryButton(
                text = "Try again, tighter",
                modifier = Modifier.fillMaxWidth(),
                onClick = onRetry
            )
        }
        SquishOutlinedButton(
            text = "Keep this one",
            modifier = Modifier.fillMaxWidth(),
            onClick = onKeep
        )
    }
}
