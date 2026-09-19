package com.squish.app.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.data.SquishRepositories
import com.squish.app.home.formatSize
import com.squish.app.ui.theme.SquishColors

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SquishRepositories.history(context) }
    val records by repository.records.collectAsState()

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = SquishColors.TextPrimary) }
                Spacer(modifier = Modifier.width(8.dp))
                Text("History", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            }
            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing squished yet.", color = SquishColors.TextMuted)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(records) { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SquishColors.Surface).padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(record.title, color = SquishColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${formatSize(record.originalSizeBytes)} → ${formatSize(record.outputSizeBytes)}",
                                    color = SquishColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "Remove",
                                color = SquishColors.Pink,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { repository.remove(record.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
