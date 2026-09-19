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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.data.ExportRecord
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.theme.SquishColors

private val ThumbAccents = listOf(SquishColors.Coral, SquishColors.Teal, SquishColors.Yellow, SquishColors.Purple)

fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1000.0)
}

@Composable
fun HomeScreen(
    onOpenVideo: (Uri) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val recent by viewModel.recentExports.collectAsState()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onOpenVideo)
    }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SquishLogoMark(modifier = Modifier.size(30.dp), cornerRadius = 9.dp)
                    Text("Squish", style = MaterialTheme.typography.headlineSmall, color = SquishColors.TextPrimary)
                }
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", color = SquishColors.TextSecondary)
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(SquishColors.Surface)
                        .clickable {
                            pickVideo.launch(
                                PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    .build()
                            )
                        }
                        .padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(SquishColors.Coral),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", color = SquishColors.Background, style = MaterialTheme.typography.headlineSmall)
                        }
                        Text("Add a video", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                        Text("MP4 · MOV · MKV · WebM & more", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextSecondary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
                    Text(
                        "See all",
                        style = MaterialTheme.typography.labelLarge,
                        color = SquishColors.Coral,
                        modifier = Modifier.clickable(onClick = onOpenHistory)
                    )
                }

                if (recent.isEmpty()) {
                    Text(
                        "Your squished videos will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextMuted
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recent.take(6)) { record ->
                            RecentVideoCard(record, ThumbAccents[Math.floorMod(record.id.hashCode(), ThumbAccents.size)])
                        }
                    }
                }
            }

            HomeBottomNav(onHistory = onOpenHistory, onSettings = onOpenSettings)
        }
    }
}

@Composable
private fun HomeBottomNav(onHistory: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SquishColors.Surface)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // The bar only ever renders on Home, so Home is the selected tab by
        // construction; History and Settings navigate away to their own screens.
        NavItem("Home", selected = true, onClick = {})
        NavItem("History", selected = false, onClick = onHistory)
        NavItem("Settings", selected = false, onClick = onSettings)
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) SquishColors.Coral else SquishColors.TextMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun RecentVideoCard(record: ExportRecord, accent: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(108.dp).background(accent),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = Color.White)
        }
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.title, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, maxLines = 1)
            Text(formatSize(record.outputSizeBytes), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
        }
    }
}
