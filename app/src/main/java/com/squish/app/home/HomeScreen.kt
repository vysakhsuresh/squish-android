package com.squish.app.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.data.ExportRecord
import com.squish.app.editor.Timecode
import com.squish.app.tools.QuickTool
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.components.accentSweep
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
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SquishLogoMark(modifier = Modifier.size(34.dp))
                    Text("Squish", style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SquishColors.Surface)
                        .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = SquishColors.TextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            EditorHero(
                onClick = {
                    pickForEditor.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            .build()
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header("Quick tools", "One job, one tap")
                QuickTool.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { tool ->
                            ToolTile(tool = tool, modifier = Modifier.weight(1f)) { onOpenTool(tool) }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Header("Recent", "Everything you have exported")
                    Text(
                        "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = SquishColors.Cyan,
                        modifier = Modifier.clickable(onClick = onOpenHistory)
                    )
                }

                if (recent.isEmpty()) {
                    EmptyRecent()
                } else {
                    recent.take(4).forEach { record -> RecentRow(record) }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

/**
 * The editor, as the thing the screen is obviously for.
 *
 * It carries the brand's whole sweep where the tiles below carry one hue each, so
 * the hierarchy is visible before a word is read: this is the main event, those are
 * the shortcuts.
 */
@Composable
private fun EditorHero(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(SquishColors.Cyan, SquishColors.Blue, SquishColors.Violet, SquishColors.Magenta)
                )
            )
            .clickable(onClick = onClick)
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Video\neditor",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 38.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = SquishColors.Background
            )
            Text(
                "Timeline, transitions, looks, captions and automatic audio sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.Background.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pick a video",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.Background
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(SquishColors.Background.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = SquishColors.Background,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/** One shortcut. Its own colour, its own glyph, recognisable before it is read. */
@Composable
private fun ToolTile(tool: QuickTool, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tool.accent.copy(alpha = 0.1f))
            .border(1.dp, tool.accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentSweep(tool.accent)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                tool.icon,
                contentDescription = null,
                tint = SquishColors.Background,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(tool.title, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
        Text(tool.blurb, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

@Composable
private fun EmptyRecent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Nothing exported yet", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        Text(
            "Whatever you make lands here, with how much smaller it came out.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

@Composable
private fun RecentRow(record: ExportRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(16.dp))
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
            .background(SquishColors.Cyan.copy(alpha = 0.16f))
            .border(1.dp, SquishColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("−$saved%", style = MaterialTheme.typography.labelSmall, color = SquishColors.Cyan)
    }
}
