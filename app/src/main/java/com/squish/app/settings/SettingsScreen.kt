package com.squish.app.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material.icons.filled.AlternateEmail
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

        ContactRow(
            icon = Icons.Filled.AlternateEmail,
            label = "Email",
            value = MAKER_EMAIL
        ) {
            val subject = "Squish ${BuildConfig.VERSION_NAME}"
            unreachable = context.openOrNull(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$MAKER_EMAIL"))
                    .putExtra(Intent.EXTRA_SUBJECT, subject),
                ifMissing = "No email app installed — write to $MAKER_EMAIL"
            )
        }

        ContactRow(
            icon = Icons.Filled.Chat,
            label = "WhatsApp",
            value = MAKER_PHONE_DISPLAY
        ) {
            unreachable = context.openOrNull(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$MAKER_PHONE_E164")),
                ifMissing = "Nothing here can open WhatsApp — message $MAKER_PHONE_DISPLAY"
            )
        }

        unreachable?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow)
        }
    }
}

/**
 * Starts an intent, and returns the message to show if nothing could handle it.
 *
 * Package visibility hides other apps from a query on Android 11 and up, so
 * asking first would report "no email app" on a phone that has three. Starting it
 * and catching the failure is the reading that is actually accurate.
 */
private fun Context.openOrNull(intent: Intent, ifMissing: String): String? = try {
    startActivity(intent)
    null
} catch (_: ActivityNotFoundException) {
    ifMissing
}

@Composable
private fun ContactRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Cyan.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SquishColors.Cyan, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
        }
        Icon(
            Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = SquishColors.Cyan,
            modifier = Modifier.size(16.dp)
        )
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
