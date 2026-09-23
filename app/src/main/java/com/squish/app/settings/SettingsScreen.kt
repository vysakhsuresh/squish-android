package com.squish.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.BuildConfig
import com.squish.app.media.ProxyEngine
import com.squish.app.media.SquishError
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var proxyBytes by remember { mutableStateOf(ProxyEngine.cacheSizeBytes(context)) }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = SquishColors.TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onBack)
                )
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AboutCard()
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
                Spacer(modifier = Modifier.height(28.dp))
            }
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
