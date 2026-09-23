package com.squish.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The details below are placeholders on purpose: publishing a contact address is
 * the maker's decision to make, not something to infer. Fill these in and the card
 * is done.
 */
@Composable
private fun MakerCard() {
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
        ContactRow("Email", MAKER_EMAIL)
        ContactRow("Web", MAKER_SITE)
    }
}

@Composable
private fun ContactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = SquishColors.Cyan)
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
private const val MAKER_EMAIL = "hello@layerbit.com"
private const val MAKER_SITE = "layerbit.com"
