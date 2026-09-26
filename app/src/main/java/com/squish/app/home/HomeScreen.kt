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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    onOpenLibrary: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val recent by viewModel.recentExports.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    // Re-read on every return to the dashboard, so an edit left five minutes ago
    // is here rather than whatever the list happened to hold at launch.
    LaunchedEffect(Unit) { viewModel.refreshDrafts() }
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

            // One door, not a list. Stacking every unfinished thing here made the
            // dashboard longer the more work was outstanding - which is exactly
            // backwards, and the same mistake the export history made before it
            // moved out to a screen of its own.
            if (drafts.isNotEmpty()) {
                DraftsDoor(count = drafts.size, onClick = onOpenDrafts)
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
                Header("Fast lane", "One job, one tap")
                QuickTool.entries.chunked(2).forEach { pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT)
                    ) {
                        pair.forEach { tool ->
                            ToolTile(
                                tool = tool,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) { onOpenTool(tool) }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            LibraryDoor(count = recent.size, onClick = onOpenLibrary)

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
            // "The Studio", not "Video editor". Every app on the phone that opens
            // a timeline calls itself a video editor; the word describes the
            // category, not this. What it is, is the room with everything in it -
            // and the one-job tools below are the opposite of a room.
            Text(
                "The\nStudio",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 38.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = SquishColors.Background
            )
            Text(
                "Timeline, blends, looks, words and automatic audio sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.Background.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Open the studio",
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

/** "4 minutes ago" — the only thing anyone wants to know about a draft's age. */
internal fun agoOf(millis: Long): String {
    val elapsed = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1_440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1_440} d ago"
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
        Text(
            tool.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The way into the library, as one row rather than a list that grows forever.
 *
 * It says how many are in there, which is the only thing the dashboard needs to
 * tell you about work you have already finished.
 */
@Composable
private fun LibraryDoor(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SquishColors.Violet.copy(alpha = 0.1f))
            .border(1.dp, SquishColors.Violet.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentSweep(SquishColors.Violet)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.VideoLibrary,
                contentDescription = null,
                tint = SquishColors.Background,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Library", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            Text(
                when (count) {
                    0 -> "Everything you export lands here"
                    1 -> "1 export · search and share"
                    else -> "$count exports · search and share"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = SquishColors.Violet,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * The way in to everything left half-done.
 *
 * Deliberately the same shape as the library's door, because they are the same
 * kind of thing: a pile of your own work that belongs on a screen of its own
 * rather than stacked on the one you start from.
 */
@Composable
private fun DraftsDoor(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SquishColors.Cyan.copy(alpha = 0.09f))
            .border(1.dp, SquishColors.Cyan.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentSweep(SquishColors.Cyan)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = SquishColors.Background,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Pick up where you left off",
                style = MaterialTheme.typography.titleMedium,
                color = SquishColors.TextPrimary
            )
            Text(
                if (count == 1) "1 unfinished · saved automatically"
                else "$count unfinished · saved automatically",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = SquishColors.Cyan,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Two rows of text and a glyph, at a height that does not depend on the words. */
private val TILE_HEIGHT = 138.dp
