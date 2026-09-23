package com.squish.app.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.data.SquishRepositories
import com.squish.app.home.formatSize
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.theme.SquishColors
import java.io.File

@Composable
fun ExportScreen(resultPath: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val record = remember(resultPath) {
        SquishRepositories.history(context).records.value.firstOrNull { it.outputPath == resultPath }
    }
    val savedPercent = record?.let {
        if (it.originalSizeBytes > 0) (100 - (it.outputSizeBytes * 100 / it.originalSizeBytes)).coerceIn(0, 99) else null
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(SquishColors.Teal),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = SquishColors.Background, style = MaterialTheme.typography.displayLarge)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text("All done!", style = MaterialTheme.typography.headlineSmall, color = SquishColors.TextPrimary)
                Text(
                    "Your video is ready to share — no watermark attached.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))

                if (record != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SquishColors.Surface).padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Before", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                                Text(formatSize(record.originalSizeBytes), style = MaterialTheme.typography.titleMedium, color = SquishColors.TextMuted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("After", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                                Text(formatSize(record.outputSizeBytes), style = MaterialTheme.typography.titleMedium, color = SquishColors.Teal)
                            }
                        }
                        if (savedPercent != null) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(SquishColors.Teal.copy(alpha = 0.15f)).padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("$savedPercent% smaller", color = SquishColors.Teal, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                } else {
                    Text(
                        "Saved to ${File(resultPath).name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ShareTarget("WhatsApp", SquishColors.Teal) { ShareUtils.shareVideo(context, resultPath, "com.whatsapp") }
                    ShareTarget("Instagram", SquishColors.Pink) { ShareUtils.shareVideo(context, resultPath, "com.instagram.android") }
                    ShareTarget("Email", SquishColors.Purple) { ShareUtils.shareVideo(context, resultPath, null) }
                    ShareTarget("More", SquishColors.TextSecondary) { ShareUtils.shareVideo(context, resultPath, null) }
                }

                Spacer(modifier = Modifier.weight(1f))
                SquishOutlinedButton(text = "Compress another video", modifier = Modifier.fillMaxWidth(), onClick = onDone)
                // Clearance for the orb, which floats over this column's bottom-left.
                Spacer(modifier = Modifier.height(92.dp))
            }

            BackOrb(
                accent = SquishColors.Cyan,
                onClick = onDone,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ShareTarget(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(color).clickable(onClick = onClick))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextSecondary)
    }
}
