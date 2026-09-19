package com.squish.app.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.data.ExportRecord
import com.squish.app.editor.Timecode
import com.squish.app.tools.QuickTool
import com.squish.app.ui.theme.SquishColors

fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1000.0)
}

/**
 * The dashboard. Every capability is a separate door: one-job tools for people who
 * just need a smaller file, and the full editor for people making something.
 * Nothing is buried behind a timeline it does not need.
 */
@Composable
fun HomeScreen(
    onOpenEditor: (Uri) -> Unit,
    onOpenTool: (QuickTool) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val recent by viewModel.recentExports.collectAsState()
    val pickForEditor = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onOpenEditor)
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Squish", style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                Text(
                    "Settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier.clickable(onClick = onOpenSettings)
                )
            }

            EditorEntryCard(
                onClick = {
                    pickForEditor.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            .build()
                    )
                }
            )

            SectionHeading("Quick tools", "One job, one tap")

            val tools = QuickTool.entries
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tools.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { tool ->
                            ToolTile(tool = tool, modifier = Modifier.weight(1f)) { onOpenTool(tool) }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                SectionHeading("Recent", "Everything you have exported")
                Text(
                    "See all",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.Coral,
                    modifier = Modifier.clickable(onClick = onOpenHistory)
                )
            }

            if (recent.isEmpty()) {
                Text(
                    "Nothing yet. Pick a tool above to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextMuted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    recent.take(4).forEach { record -> RecentRow(record) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

@Composable
private fun EditorEntryCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SquishColors.Coral)
            .clickable(onClick = onClick)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Video editor", style = MaterialTheme.typography.headlineSmall, color = SquishColors.Background)
        Text(
            "Timeline, trim, crop, captions, colour and automatic audio sync.",
            style = MaterialTheme.typography.bodyMedium,
            color = SquishColors.Background.copy(alpha = 0.78f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("Pick a video", style = MaterialTheme.typography.labelLarge, color = SquishColors.Background)
    }
}

@Composable
private fun ToolTile(tool: QuickTool, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.22f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tool.accent)
        )
        Text(tool.title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
        Text(tool.blurb, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

@Composable
private fun RecentRow(record: ExportRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SquishColors.Surface)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.title,
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1
            )
            Text(
                "${Timecode.format(record.durationMs)}  ·  ${formatSize(record.outputSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
        SavingBadge(record)
    }
}

@Composable
private fun SavingBadge(record: ExportRecord) {
    if (record.originalSizeBytes <= 0 || record.outputSizeBytes >= record.originalSizeBytes) return
    val saved = 100 - (record.outputSizeBytes * 100 / record.originalSizeBytes)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SquishColors.Teal.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("-$saved%", style = MaterialTheme.typography.labelSmall, color = SquishColors.Teal)
    }
}
