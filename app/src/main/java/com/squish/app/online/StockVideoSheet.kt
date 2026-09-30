package com.squish.app.online

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Free stock footage, searched and picked: a grid of the Archive's clips, a tap
 * downloads one and hands its file to [onPicked]. Only reached through the
 * online question (OnlineGate), so it is never open with online features off.
 */
@Composable
fun StockVideoSheet(onPicked: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<OnlineStock.Video>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0L else 500L)
        videos = null
        failed = false
        videos = runCatching { OnlineStock.search(context, query) }.getOrElse { failed = true; emptyList() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(SquishColors.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Free stock footage", style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
                    Text(
                        "Public domain and Creative Commons, from the Internet Archive. Tap one to add it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = SquishColors.TextSecondary) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SquishColors.Surface)
                    .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = SquishColors.TextMuted, modifier = Modifier.size(18.dp))
                Box(modifier = Modifier.padding(start = 8.dp).fillMaxWidth()) {
                    if (query.isEmpty()) Text("Search: fire, ocean, city, clouds…", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextMuted)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = SquishColors.TextPrimary),
                        cursorBrush = SolidColor(SquishColors.Cyan),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            val list = videos
            when {
                failed -> Text("Couldn't reach the Internet Archive. Check the connection and try again.", style = MaterialTheme.typography.bodySmall, color = SquishColors.Yellow)
                list == null -> CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                list.isEmpty() -> Text("Nothing matches \"$query\".", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(list, key = { it.id }) { video ->
                        StockTile(
                            video = video,
                            busy = fetching == video.id,
                            onClick = {
                                if (fetching != null) return@StockTile
                                fetching = video.id
                                scope.launch {
                                    val uri = runCatching { OnlineStock.download(context, video) }.getOrNull()
                                    fetching = null
                                    if (uri == null) failed = true else onPicked(uri)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StockTile(video: OnlineStock.Video, busy: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, video.id) { value = OnlineStock.thumbnail(context, video) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(SquishColors.Background), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (busy) {
                Box(modifier = Modifier.fillMaxSize().background(SquishColors.Background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
        Text(video.title, style = MaterialTheme.typography.labelMedium, color = SquishColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            "${video.licenseLabel} · ${"%.0f".format(video.sizeBytes / 1_000_000.0)} MB",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
        )
    }
}
