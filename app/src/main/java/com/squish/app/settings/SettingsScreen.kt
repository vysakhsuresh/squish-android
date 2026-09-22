package com.squish.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var proxyBytes by remember { mutableStateOf(ProxyEngine.cacheSizeBytes(context)) }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = SquishColors.TextPrimary) }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            }
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    "Squish v${BuildConfig.VERSION_NAME}",
                    color = SquishColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "No watermark. No account. No paywall. No cloud upload — every frame is decoded, " +
                        "composed and encoded on this device, and nothing about your footage leaves it.",
                    color = SquishColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Preview cache",
                        color = SquishColors.TextPrimary,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Light 540p copies of large clips, used only to keep scrubbing smooth. Exports " +
                            "always read the original file, so clearing these costs nothing but a rebuild.",
                        color = SquishColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Using ${SquishError.formatBytes(proxyBytes)}",
                        color = SquishColors.Cyan,
                        style = MaterialTheme.typography.bodySmall
                    )
                    SquishOutlinedButton(text = "Clear preview cache") {
                        ProxyEngine.clearCache(context)
                        proxyBytes = ProxyEngine.cacheSizeBytes(context)
                    }
                }
            }
        }
    }
}
