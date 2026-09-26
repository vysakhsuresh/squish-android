package com.squish.app.editor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.squish.app.media.audio.MusicLibrary
import com.squish.app.media.audio.MusicSynth
import com.squish.app.media.audio.PhoneTrack
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music to put under the video: Squish Originals, composed on the phone, and a
 * browser for the songs already on it. Tap a row to hear it; Add drops it on
 * the timeline at the playhead.
 */
@Composable
fun MusicPanel(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    // One small player for listening before adding, released with the panel.
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf<String?>(null) }

    fun listen(key: String, uri: Uri) {
        if (playingKey == key) {
            player.pause()
            playingKey = null
            return
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        playingKey = key
    }

    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Music",
            "Tap to listen · Add puts it at the playhead",
            icon = Icons.Filled.LibraryMusic,
            accent = SquishColors.Cyan
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Squish originals", tab == 0, accentColor = SquishColors.Cyan, onClick = { tab = 0 })
            SelectableChip("On this phone", tab == 1, accentColor = SquishColors.Cyan, onClick = { tab = 1 })
        }

        if (tab == 0) {
            Text(
                "Composed by Squish on your phone — yours to use anywhere, no credit needed.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
            MusicSynth.styles.forEach { style ->
                MusicRow(
                    title = style.title,
                    subtitle = "${style.mood} · ${style.seconds}s",
                    playing = playingKey == style.id,
                    busy = preparing == style.id,
                    onListen = {
                        scope.launch {
                            preparing = style.id
                            val uri = MusicLibrary.original(context, style)
                            preparing = null
                            listen(style.id, uri)
                        }
                    },
                    onAdd = {
                        scope.launch {
                            preparing = style.id
                            val uri = MusicLibrary.original(context, style)
                            preparing = null
                            player.pause()
                            playingKey = null
                            viewModel.addAudioTrack(uri, style.title)
                        }
                    }
                )
            }
        } else {
            PhoneMusic(
                playingKey = playingKey,
                onListen = { track -> listen(track.uri.toString(), track.uri) },
                onAdd = { track ->
                    player.pause()
                    playingKey = null
                    viewModel.addAudioTrack(track.uri, track.title)
                }
            )
        }
    }
}

@Composable
private fun PhoneMusic(
    playingKey: String?,
    onListen: (PhoneTrack) -> Unit,
    onAdd: (PhoneTrack) -> Unit
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Text(
            "Squish needs permission to see the music on your phone. It only reads the list — nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        com.squish.app.ui.components.SquishOutlinedButton(
            text = "Allow access to music",
            modifier = Modifier.fillMaxWidth(),
            onClick = { ask.launch(permission) }
        )
        return
    }

    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<PhoneTrack>?>(null) }
    LaunchedEffect(query) {
        delay(250)
        tracks = MusicLibrary.phoneTracks(context, query)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = SquishColors.TextMuted, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.padding(start = 8.dp).fillMaxWidth()) {
            if (query.isEmpty()) {
                Text("Search songs, artists, albums", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextMuted)
            }
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

    val list = tracks
    when {
        list == null -> CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        list.isEmpty() -> Text(
            if (query.isBlank()) "No music found on this phone." else "Nothing matches \"$query\".",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        else -> list.take(60).forEach { track ->
            MusicRow(
                title = track.title,
                subtitle = listOfNotNull(track.artist, Timecode.format(track.durationMs).substringBefore('.')).joinToString(" · "),
                playing = playingKey == track.uri.toString(),
                busy = false,
                onListen = { onListen(track) },
                onAdd = { onAdd(track) }
            )
        }
    }
}

@Composable
private fun MusicRow(
    title: String,
    subtitle: String,
    playing: Boolean,
    busy: Boolean,
    onListen: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) SquishColors.Cyan.copy(alpha = 0.12f) else SquishColors.Background)
            .clickable(onClick = onListen)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Listen",
                    tint = SquishColors.Cyan
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(SquishColors.Cyan.copy(alpha = 0.16f))
                .clickable(enabled = !busy, onClick = onAdd)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = SquishColors.Cyan, modifier = Modifier.size(16.dp))
            Text("Add", style = MaterialTheme.typography.labelLarge, color = SquishColors.Cyan)
        }
    }
}
