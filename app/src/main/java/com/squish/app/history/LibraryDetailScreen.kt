package com.squish.app.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.data.SquishRepositories
import com.squish.app.export.ExportedFile
import com.squish.app.home.agoOf
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.SquishDangerButton
import com.squish.app.ui.theme.SquishColors
import java.io.File

/**
 * One export from the library, plainly: what it is, where it went, and the
 * ways to share or delete it. Opening a library row used to land on the done
 * screen - a tick springing in and "nothing held back" over a file made weeks
 * ago - whose "Back to Squish" dropped the library off the stack while the
 * system back went to it, two back controls with two destinations. Here back
 * is back.
 */
@Composable
fun LibraryDetailScreen(recordId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SquishRepositories.history(context) }
    val records by repository.records.collectAsState()
    val record = records.firstOrNull { it.id == recordId }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Deleted from here: the row is gone from the list, and so is this screen.
    LaunchedEffect(record, records) { if (record == null && records.isNotEmpty()) onBack() }

    Scaffold(containerColor = SquishColors.Background) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                if (record != null) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.shownTitle, style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
                        Text(
                            "Exported ${agoOf(record.createdAtMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SquishColors.TextMuted
                        )
                    }
                    ExportedFile(
                        uri = record.mediaUri,
                        isAudio = record.isAudio,
                        fileName = File(record.outputPath).name,
                        title = record.shownTitle,
                        fallbackDurationMs = record.durationMs,
                        originalBytes = 0L,
                        inGallery = record.savedToGallery != false,
                        notice = notice,
                        onNotice = { notice = it }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SquishDangerButton(
                        text = "Delete this export",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { confirmDelete = true }
                    )
                } else {
                    Text(
                        "This export is no longer in the library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextMuted
                    )
                }
                Spacer(modifier = Modifier.height(96.dp))
            }

            BackOrb(
                accent = SquishColors.Violet,
                onClick = onBack,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)
            )
        }
    }

    if (confirmDelete && record != null) {
        DeleteExportDialog(
            record = record,
            onConfirm = {
                confirmDelete = false
                repository.delete(record.id)
                onBack()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}
