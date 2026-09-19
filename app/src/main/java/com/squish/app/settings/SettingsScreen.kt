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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.BuildConfig
import com.squish.app.ui.theme.SquishColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = SquishColors.TextPrimary) }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            }
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Squish v${BuildConfig.VERSION_NAME}", color = SquishColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "No watermark. No cloud upload — every export is processed on your device.",
                    color = SquishColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
