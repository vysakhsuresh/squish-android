package com.squish.app.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.BuildConfig
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPage
import com.squish.app.ui.theme.SquishColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var proxyBytes by remember { mutableStateOf(ProxyEngine.cacheSizeBytes(context)) }

    SquishPage(
        title = "Settings",
        subtitle = "About Squish, and what it keeps on your device",
        onBack = onBack,
        accent = SquishColors.Blue
    ) {
        AboutCard()
        MakerCard()
        SupportCard()
        PrivacyCard()
        WhatItDoesCard()
        StorageCard(
            bytes = proxyBytes,
            onClear = {
                ProxyEngine.clearCache(context)
                proxyBytes = ProxyEngine.cacheSizeBytes(context)
            }
        )
        LicencesCard()
    }
}

/**
 * Who made it and how to reach them.
 *
 * Both rows open the right app rather than making anyone copy a string out by
 * hand, and the email arrives with the version already in its subject - a bug
 * report without a version number costs a round trip every time.
 *
 * Handing an address to another app is not a network call, so none of this needs
 * the internet permission the privacy card promises Squish does not hold.
 */
@Composable
private fun MakerCard() {
    val context = LocalContext.current
    var unreachable by remember { mutableStateOf<String?>(null) }

    SquishCard(accent = SquishColors.Cyan) {
        SectionHeading(
            title = "Made by Layerbit",
            subtitle = "Get in touch",
            icon = Icons.Filled.AlternateEmail,
            accent = SquishColors.Cyan
        )
        Text(
            "Squish is built by Layerbit. If something is broken, missing, or you have " +
                "an idea for where it should go next, we would rather hear it than not.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )

        // Two tiles, no addresses. The address and the number are what the tap
        // is for, not what the screen is for: printing them puts a live mailbox
        // and a live phone number in front of every screenshot, scraper and
        // shoulder, and buys the reader nothing they could not get by tapping.
        // If nothing on the phone can take the tap, it goes to the clipboard -
        // still reachable, still not on display.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ContactTile(
                icon = Icons.Filled.MailOutline,
                label = "Email us",
                hint = "Opens your mail app",
                modifier = Modifier.weight(1f)
            ) {
                val subject = "Squish ${BuildConfig.VERSION_NAME}"
                unreachable = context.openOrCopy(
                    intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$MAKER_EMAIL"))
                        .putExtra(Intent.EXTRA_SUBJECT, subject),
                    clip = MAKER_EMAIL,
                    clipLabel = "Layerbit email",
                    ifMissing = "No mail app here — the address is on your clipboard"
                )
            }

            ContactTile(
                icon = Icons.Filled.ChatBubbleOutline,
                label = "Message us",
                hint = "Opens your chat app",
                modifier = Modifier.weight(1f)
            ) {
                unreachable = context.openOrCopy(
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$MAKER_PHONE_E164")),
                    clip = MAKER_PHONE_E164,
                    clipLabel = "Layerbit number",
                    ifMissing = "Nothing here can open that chat — the number is on your clipboard"
                )
            }
        }

        unreachable?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow)
        }
    }
}

/**
 * Starts an intent; if nothing can take it, puts the detail on the clipboard.
 *
 * Package visibility hides other apps from a query on Android 11 and up, so
 * asking first would report "no mail app" on a phone that has three. Starting it
 * and catching the failure is the reading that is actually accurate.
 *
 * The clipboard is the fallback rather than printing the address on screen: a
 * phone with no mail app still needs a way to reach it, and the way to reach it
 * does not have to be legible to everyone looking at the phone.
 */
private fun Context.openOrCopy(
    intent: Intent,
    clip: String,
    clipLabel: String,
    ifMissing: String
): String? = try {
    startActivity(intent)
    null
} catch (_: ActivityNotFoundException) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(clipLabel, clip))
    ifMissing
}

/** Opens a link in whatever the phone uses for the web. */
private fun Context.openLink(url: String, ifMissing: String): String? = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    ifMissing
}

/**
 * One way of getting in touch, as a target rather than a transcript.
 *
 * Square-ish and side by side, because the two are equal choices - a list with
 * the address written out made one of them look like the real one and the other
 * like a footnote.
 */
@Composable
private fun ContactTile(
    icon: ImageVector,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SquishColors.Cyan.copy(alpha = 0.10f))
            .border(1.dp, SquishColors.Cyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SquishColors.Cyan, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        }
    }
}

/**
 * A tip jar, and nothing more than one.
 *
 * Squish has no subscription, no advertisements, no paid tier and no telemetry
 * to sell, which is a position worth keeping and also one that pays for nothing.
 * So: an entirely optional way to help, on a card that never nags, never counts
 * down, and never appears anywhere but here. Nothing in the app is locked behind
 * it and nothing about the app changes if it is never tapped - the moment a tip
 * jar starts withholding something it has stopped being a tip jar.
 */
@Composable
private fun SupportCard() {
    val context = LocalContext.current
    var unreachable by remember { mutableStateOf<String?>(null) }

    SquishCard(accent = SquishColors.Amber) {
        SectionHeading(
            title = "Buy us a coffee",
            subtitle = "Optional, always",
            icon = Icons.Filled.LocalCafe,
            accent = SquishColors.Amber
        )
        Text(
            "Squish is free, has no advertisements and asks for nothing about you. " +
                "If it saved you an evening and you feel like putting something in the " +
                "jar, it goes straight into the next build. If not, nothing here changes.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SquishColors.Amber.copy(alpha = 0.12f))
                .border(1.dp, SquishColors.Amber.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                .clickable {
                    unreachable = context.openLink(
                        SUPPORT_URL,
                        ifMissing = "No browser here to open that with"
                    )
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Filled.LocalCafe,
                contentDescription = null,
                tint = SquishColors.Amber,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Buy us a coffee",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = null,
                tint = SquishColors.Amber,
                modifier = Modifier.size(16.dp)
            )
        }

        unreachable?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow)
        }
    }
}

@Composable
private fun AboutCard() {
    SquishCard(accent = SquishColors.Blue) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SquishLogoMark(modifier = Modifier.size(52.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Squish", style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }
        Text(
            "A video editor for people who care where the cut lands. Frame-accurate trimming, " +
                "a real multi-track timeline, and the sync work that usually needs a desktop — " +
                "all of it running on the phone in your hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = SquishColors.TextSecondary
        )
    }
}

@Composable
private fun PrivacyCard() {
    SquishCard(accent = SquishColors.Cyan) {
        SectionHeading(
            title = "Your footage stays yours",
            subtitle = "Not a policy — an architecture",
            icon = Icons.Filled.Lock,
            accent = SquishColors.Cyan
        )
        Promise("No account, ever.")
        Promise("No upload. Squish requests no network permission at all, so there is nowhere for your video to go.")
        Promise("No watermark and no paywalled resolution.")
        Promise("Speech recognition runs on the device or not at all — it never falls back to a server.")
    }
}

@Composable
private fun WhatItDoesCard() {
    SquishCard(accent = SquishColors.Violet) {
        SectionHeading(
            title = "What's inside",
            subtitle = "The short version",
            icon = Icons.Filled.Bolt,
            accent = SquishColors.Violet
        )
        Promise("Multi-track timeline with frame-accurate trim, split and transitions.")
        Promise("Automatic sync for separately recorded sound.")
        Promise("Sixteen graded looks, chroma key, shape masks and motion tracking.")
        Promise("Stabilization, keyframed motion and auto-captions.")
    }
}

@Composable
private fun StorageCard(bytes: Long, onClear: () -> Unit) {
    SquishCard(accent = SquishColors.Amber) {
        SectionHeading(
            title = "Preview cache",
            subtitle = "Using ${SquishError.formatBytes(bytes)}",
            icon = Icons.Filled.Storage,
            accent = SquishColors.Amber
        )
        Text(
            "Light 540p copies of large clips, kept only to keep scrubbing smooth. Exports always " +
                "read the original file, so clearing these costs nothing but a rebuild.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        SquishOutlinedButton(
            text = "Clear preview cache",
            modifier = Modifier.fillMaxWidth(),
            onClick = onClear
        )
    }
}

@Composable
private fun LicencesCard() {
    SquishCard(accent = SquishColors.Magenta) {
        SectionHeading(
            title = "Open source",
            subtitle = "What Squish is built on",
            icon = Icons.Filled.Gavel,
            accent = SquishColors.Magenta
        )
        Text(
            "Jetpack Compose and AndroidX Media3, both Apache 2.0. The typeface is Space Grotesk " +
                "under the SIL Open Font License. Full notices ship with the source in " +
                "THIRD_PARTY_NOTICES.md.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

/** A single claim, bulleted. Each is checkable against the code rather than a slogan. */
@Composable
private fun Promise(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("—", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        Text(text, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
    }
}

// Fill these in and the contact card is complete. Left as placeholders rather than
// guessed at: an address published inside a shipped app is the maker's call.
private const val MAKER_EMAIL = "ceo@layerbit.co.in"

/** What a person reads, and what wa.me needs: country code, no plus, no spaces. */
private const val MAKER_PHONE_DISPLAY = "+91 62825 95823"
private const val MAKER_PHONE_E164 = "916282595823"

/**
 * The tip jar's page.
 *
 * Change this to the real handle before shipping - it is the one string on this
 * screen that cannot be checked from inside the app, and a support link that goes
 * to a 404 is worse than no support link.
 */
private const val SUPPORT_URL = "https://buymeacoffee.com/layerbit"
