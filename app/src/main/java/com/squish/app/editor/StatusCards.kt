package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squish.app.media.SquishError
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.theme.SquishColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A failure the user can read and act on: what happened, why, and the one thing
 * that fixes it. Never a stack trace, never "Export failed", and never a frozen
 * screen with no explanation at all.
 */
@Composable
fun FailureCard(error: SquishError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Danger, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = SquishColors.Danger,
                modifier = Modifier.size(18.dp)
            )
            Text(error.title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }
        Text(error.detail, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
        Text(
            error.fix,
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.Cyan
        )
        SquishOutlinedButton(text = "Dismiss", onClick = onDismiss)
    }
}

/**
 * The parachute opening. An edit survived the app being killed, and it is offered
 * rather than applied - quietly overwriting the clip someone just opened would be
 * its own kind of data loss.
 *
 * Continue is the primary and the only filled button: it is what almost everyone
 * wants, and it keeps work. "Start a new project" is the quiet one, and it never
 * deletes anything - the saved edit goes to the bin, where the drafts screen can
 * bring it back. The card used to say "Unsaved edit found" about an edit that
 * was saved, and offer "Start fresh" in the same weight as "Restore it", one tap
 * from deleting the only copy.
 *
 * Three lines tall, the two answers side by side: the card has a capped share
 * of the screen above the strip, and stacked buttons put the second one under
 * the strip, where it could be neither seen nor tapped.
 */
@Composable
fun RecoveryBanner(
    offer: RecoveryOffer,
    onContinue: () -> Unit,
    onStartNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.SurfaceElevated)
            .border(1.dp, SquishColors.Amber, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.HistoryToggleOff,
                contentDescription = null,
                tint = SquishColors.Amber,
                modifier = Modifier.size(18.dp)
            )
            Text("Your edit of this clip is saved", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }

        Text(
            buildString {
                append(offer.clipCount)
                append(if (offer.clipCount == 1) " clip, " else " clips, ")
                // Whole seconds. Nobody needs the milliseconds of an edit to
                // decide whether it is theirs.
                append(Timecode.format(offer.durationMs).substringBefore('.'))
                append(" of edit, saved ")
                append(relativeTime(offer.savedAtMillis))
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary,
            maxLines = 2
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SquishPrimaryButton(
                text = "Continue",
                modifier = Modifier.weight(1f),
                onClick = onContinue
            )
            // A plain action rather than a second bordered button: the words
            // are long, and beside the filled one a border made two equals of
            // an answer and its alternative.
            Text(
                "Start a new project",
                style = MaterialTheme.typography.labelLarge,
                color = SquishColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onStartNew)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * Said once the first change to the bare clip has answered the offer above: the
 * saved edit went to the bin, and where it went and how to get it back are both
 * on screen. The offer used to vanish at the first edit with nothing said, so a
 * slip of the finger looked like the loss of a whole project.
 */
@Composable
fun SetAsideNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.HistoryToggleOff,
                contentDescription = null,
                tint = SquishColors.Amber,
                modifier = Modifier.size(18.dp)
            )
            Text("Started a new project", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        }
        Text(
            "Your saved edit of this clip moved to Recently discarded on the Unfinished screen. " +
                "Undo back to the untouched clip to have it offered again.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )
        SquishOutlinedButton(text = "Got it", onClick = onDismiss)
    }
}

/**
 * Only ever shown for footage heavy enough to need it, and only while it matters.
 * Says plainly that the preview is the low-resolution copy and the export is not.
 *
 * With more than one heavy file in the edit - a 4K overlay over a 4K shot - it
 * counts them: [ready] of [total] copies made.
 */
@Composable
fun ProxyIndicator(status: ProxyStatus, modifier: Modifier = Modifier, ready: Int = 0, total: Int = 0) {
    if (status == ProxyStatus.NotNeeded) return
    val count = if (total > 1) " ($ready of $total ready)" else ""

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (status == ProxyStatus.Building) {
            CircularProgressIndicator(
                color = SquishColors.Cyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            when (status) {
                ProxyStatus.Building -> "Building a light preview copy$count — editing stays responsive while it works"
                ProxyStatus.Ready -> "Previewing at 540p for smooth scrubbing · exports at full resolution"
                ProxyStatus.Failed -> "Playing the original$count — scrubbing may stutter on footage this large"
                ProxyStatus.NotNeeded -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (status == ProxyStatus.Failed) SquishColors.TextMuted else SquishColors.Cyan
        )
    }
}

/** Photos and blanks take a few seconds to become clips; this says they are coming. */
@Composable
fun PreparingIndicator(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(color = SquishColors.Violet, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        Text(
            if (count == 1) "Preparing 1 photo or blank for the timeline…" else "Preparing $count photos or blanks for the timeline…",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.Violet
        )
    }
}

/**
 * A clip's footage being written backwards (ReverseRenderer): a render that
 * takes as long as the clip, or longer, and lands as one step when done. Said
 * per clip with how far along it is, since the button is greyed meanwhile,
 * with a Cancel: a three-minute 4K clip reversed by mistake used to be minutes
 * of rendering with no way out but leaving the editor.
 */
@Composable
fun ReversingIndicator(
    reversing: Map<String, Float>,
    nameOf: (String) -> String,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (reversing.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        reversing.forEach { (id, fraction) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = SquishColors.Violet, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Text(
                    "Reversing “${nameOf(id)}”… ${(fraction * 100).toInt()}% - it lands on the strip when done.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Violet,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Stop reversing ${nameOf(id)}") { onCancel(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * A line being read aloud: the phone's engine takes seconds to bind and speak,
 * and with nothing said the button looked dead and got tapped again.
 */
@Composable
fun SpeakingIndicator(speaking: Boolean, modifier: Modifier = Modifier) {
    if (!speaking) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(color = SquishColors.Amber, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        Text(
            "Reading the line aloud… it lands as a sound clip where the line starts.",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.Amber
        )
    }
}

private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return "recently"
    val elapsed = System.currentTimeMillis() - millis
    return when {
        elapsed < 60_000 -> "moments ago"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} h ago"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
