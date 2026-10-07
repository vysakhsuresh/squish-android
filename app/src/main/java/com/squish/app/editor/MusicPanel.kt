@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.editor

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.squish.app.online.Online
import com.squish.app.online.OnlineMusic
import com.squish.app.online.rememberOnlineGate
import androidx.compose.runtime.collectAsState
import com.squish.app.media.audio.MusicLibrary
import com.squish.app.media.audio.MusicPick
import com.squish.app.media.audio.MusicSynth
import com.squish.app.media.audio.PhoneTrack
import com.squish.app.ui.components.SelectableChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music to put under the video: Squish Originals, composed on the phone, its
 * sound effects, a browser for the songs already on it, and what was starred
 * or used lately. Tap a row to hear it; Add drops it on the timeline at the
 * playhead.
 *
 * An audition is heard on its own: it pauses the editor before it starts
 * (a song over the edit's own sound was two songs at once), stops when the
 * editor plays, when the list changes, when the app is put away, or when it
 * ends - and it takes audio focus, so whatever else the phone was playing
 * pauses for it.
 */
@Composable
fun MusicPanel(viewModel: EditorViewModel, editorPlaying: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var vibe by rememberSaveable { mutableStateOf("All") }

    // One small player for listening before adding, released with the panel.
    var playingKey by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playingKey = null
            }
            // Focus lost - a call, another app - pauses it; the row says so.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState != Player.STATE_BUFFERING) playingKey = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    var preparing by remember { mutableStateOf<String?>(null) }

    fun stopListening() {
        if (player.playWhenReady) player.pause()
        playingKey = null
    }

    fun listen(key: String, uri: Uri) {
        if (playingKey == key) {
            stopListening()
            return
        }
        viewModel.audio.requestPause()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        playingKey = key
    }

    // The editor playing is the end of the audition; so is a change of list.
    LaunchedEffect(editorPlaying) { if (editorPlaying) stopListening() }
    LaunchedEffect(tab) { stopListening() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopListening() }

    // Starred, read once and kept as it changes under the taps here.
    var favourites by remember { mutableStateOf(MusicLibrary.favourites(context)) }
    fun star(pick: MusicPick) {
        MusicLibrary.toggleFavourite(context, pick)
        favourites = MusicLibrary.favourites(context)
    }
    fun isStarred(key: String) = favourites.any { it.key == key }

    fun add(pick: MusicPick, uri: Uri) {
        stopListening()
        MusicLibrary.noteUsed(context, pick)
        viewModel.audio.addAudioTrack(uri, pick.title)
    }

    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Music",
            "Tap to listen · Add puts it at the playhead",
            icon = Icons.Filled.LibraryMusic,
            accent = SquishColors.Cyan
        )
        // Two rows of two, each filling the width, so all four are in sight on
        // a phone. In one scrolling row the fourth sat off screen with nothing
        // to say so, and the starred list was never found; and a scrolling
        // chip row straight under the sheet's own read as one muddled control.
        // The fifth, free music online, has the last row to itself.
        listOf("Squish originals", "Sound effects", "On this phone", "Starred & recent", "Free music online").chunked(2).forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { c, label ->
                    val i = r * 2 + c
                    SelectableChip(label, tab == i, accentColor = SquishColors.Cyan, modifier = Modifier.weight(1f), onClick = { tab = i })
                }
            }
        }

        when (tab) {
            0 -> {
                Text(
                    "Composed by Squish on your phone — yours to use anywhere, no credit needed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    MusicSynth.vibes.forEach { v ->
                        SelectableChip(v, vibe == v, accentColor = SquishColors.Cyan, onClick = { vibe = v })
                    }
                }
                MusicSynth.styles.filter { vibe == "All" || MusicSynth.vibeOf(it) == vibe }.forEach { style ->
                    val pick = MusicPick("orig:${style.id}", style.title, "${style.mood} · ${style.seconds}s")
                    MusicRow(
                        pick = pick,
                        playing = playingKey == pick.key,
                        busy = preparing == pick.key,
                        starred = isStarred(pick.key),
                        onListen = {
                            scope.launch {
                                preparing = pick.key
                                val uri = MusicLibrary.original(context, style)
                                preparing = null
                                uri?.let { listen(pick.key, it) }
                            }
                        },
                        onStar = { star(pick) },
                        onAdd = {
                            scope.launch {
                                preparing = pick.key
                                val uri = MusicLibrary.original(context, style)
                                preparing = null
                                uri?.let { add(pick, it) }
                            }
                        }
                    )
                }
            }
            1 -> {
                Text(
                    "A whoosh on a cut, a pop on a sticker, a riser into the drop. Made on the phone, like the music.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                MusicSynth.effects.forEach { effect ->
                    val pick = MusicPick("sfx:${effect.id}", effect.title, effect.hint)
                    MusicRow(
                        pick = pick,
                        playing = playingKey == pick.key,
                        busy = preparing == pick.key,
                        starred = isStarred(pick.key),
                        onListen = {
                            scope.launch {
                                preparing = pick.key
                                val uri = MusicLibrary.effect(context, effect)
                                preparing = null
                                uri?.let { listen(pick.key, it) }
                            }
                        },
                        onStar = { star(pick) },
                        onAdd = {
                            scope.launch {
                                preparing = pick.key
                                val uri = MusicLibrary.effect(context, effect)
                                preparing = null
                                uri?.let { add(pick, it) }
                            }
                        }
                    )
                }
            }
            4 -> OnlineMusicList(
                playingKey = playingKey,
                preparing = preparing,
                onBusy = { preparing = it },
                isStarred = ::isStarred,
                onListen = { key, uri -> listen(key, uri) },
                onStar = { pick -> star(pick) },
                onAdd = { pick, uri -> add(pick, uri) }
            )
            2 -> PhoneMusic(
                playingKey = playingKey,
                isStarred = ::isStarred,
                onListen = { track -> listen(track.uri.toString(), track.uri) },
                onStar = { track -> star(track.pick) },
                onAdd = { track -> add(track.pick, track.uri) }
            )
            else -> {
                val recents = remember(favourites) { MusicLibrary.recents(context) }
                if (favourites.isEmpty() && recents.isEmpty()) {
                    Text(
                        "Nothing here yet. Star a track, or add one, and it will be waiting here next time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                }
                if (recents.isNotEmpty()) {
                    Text("Recently added", style = MaterialTheme.typography.labelMedium, color = SquishColors.TextSecondary)
                    recents.forEach { pick ->
                        RememberedRow(pick, playingKey, preparing, isStarred(pick.key), context,
                            onListen = { key, uri -> listen(key, uri) },
                            onBusy = { preparing = it },
                            onStar = { star(pick) },
                            onAdd = { uri -> add(pick, uri) })
                    }
                }
                if (favourites.isNotEmpty()) {
                    Text("Starred", style = MaterialTheme.typography.labelMedium, color = SquishColors.TextSecondary)
                    favourites.forEach { pick ->
                        RememberedRow(pick, playingKey, preparing, starred = true, context = context,
                            onListen = { key, uri -> listen(key, uri) },
                            onBusy = { preparing = it },
                            onStar = { star(pick) },
                            onAdd = { uri -> add(pick, uri) })
                    }
                }
            }
        }
    }
}

/** A starred or recent pick, resolved to its file when it is heard or added. */
@Composable
private fun RememberedRow(
    pick: MusicPick,
    playingKey: String?,
    preparing: String?,
    starred: Boolean,
    context: android.content.Context,
    onListen: (String, Uri) -> Unit,
    onBusy: (String?) -> Unit,
    onStar: () -> Unit,
    onAdd: (Uri) -> Unit
) {
    val scope = rememberCoroutineScope()
    MusicRow(
        pick = pick,
        playing = playingKey == pick.key,
        busy = preparing == pick.key,
        starred = starred,
        onListen = {
            scope.launch {
                onBusy(pick.key)
                val uri = MusicLibrary.resolve(context, pick)
                onBusy(null)
                if (uri != null) onListen(pick.key, uri)
            }
        },
        onStar = onStar,
        onAdd = {
            scope.launch {
                onBusy(pick.key)
                val uri = MusicLibrary.resolve(context, pick)
                onBusy(null)
                if (uri != null) onAdd(uri)
            }
        }
    )
}

private val PhoneTrack.pick: MusicPick
    get() = MusicPick(
        uri.toString(), title,
        listOfNotNull(artist, Timecode.format(durationMs).substringBefore('.')).joinToString(" · ")
    )

@Composable
private fun PhoneMusic(
    playingKey: String?,
    isStarred: (String) -> Boolean,
    onListen: (PhoneTrack) -> Unit,
    onStar: (PhoneTrack) -> Unit,
    onAdd: (PhoneTrack) -> Unit
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val access = rememberPermission(permission)

    if (!access.granted) {
        if (access.deniedForGood) {
            // The phone has stopped asking on Squish's behalf; a button that
            // asked again did nothing at all.
            Text(
                "Squish was refused access to the music on your phone. Allow it in Settings to browse your songs here.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )
            SquishOutlinedButton(text = "Open settings", modifier = Modifier.fillMaxWidth(), onClick = access.openSettings)
        } else {
            Text(
                "Squish needs permission to see the music on your phone. It only reads the list — nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            SquishOutlinedButton(text = "Allow access to music", modifier = Modifier.fillMaxWidth(), onClick = access.ask)
        }
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search songs, artists, albums" }
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
        else -> {
            list.take(SHOWN).forEach { track ->
                MusicRow(
                    pick = track.pick,
                    playing = playingKey == track.uri.toString(),
                    busy = false,
                    starred = isStarred(track.uri.toString()),
                    onListen = { onListen(track) },
                    onStar = { onStar(track) },
                    onAdd = { onAdd(track) }
                )
            }
            if (list.size > SHOWN) {
                Text(
                    "Showing the first $SHOWN of ${list.size} — search to narrow it down.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }
    }
}

/** The list stops here and says so; a search finds the rest. It used to stop at sixty in silence. */
private const val SHOWN = 100

@Composable
private fun MusicRow(
    pick: MusicPick,
    playing: Boolean,
    busy: Boolean,
    starred: Boolean,
    onListen: () -> Unit,
    onStar: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) SquishColors.Cyan.copy(alpha = 0.12f) else SquishColors.Background)
            // The row reads as a title and a subtitle; what the tap does is
            // not in either of them.
            .clickable(onClickLabel = if (playing) "Stop" else "Listen", onClick = onListen)
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
            Text(pick.title, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(pick.subtitle, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (starred) "Unstar" else "Star",
            tint = if (starred) SquishColors.Amber else SquishColors.TextMuted,
            modifier = Modifier
                // 44, not 32: the star is the one control on this row that is
                // not the row itself, and the app's own convention is a
                // finger's width after "at 37dp they were the most-missed".
                // The glyph stays 20 - the padding is the reach.
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(role = Role.Button, onClick = onStar)
                .padding(12.dp)
        )
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

/**
 * Free, Creative Commons music from the Internet Archive, searched and heard
 * streamed, and downloaded onto the phone when it is added. Online features
 * are asked for first (OnlineGate); nothing of the edit is sent - only the
 * search typed and the track picked.
 */
@Composable
private fun OnlineMusicList(
    playingKey: String?,
    preparing: String?,
    onBusy: (String?) -> Unit,
    isStarred: (String) -> Boolean,
    onListen: (String, Uri) -> Unit,
    onStar: (MusicPick) -> Unit,
    onAdd: (MusicPick, Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberOnlineGate()
    val enabled by Online.enabledFlow(context).collectAsState()

    if (enabled != true) {
        Text(
            "Thousands of free tracks, Creative Commons licensed, from the Internet Archive. " +
                "Searching them needs the internet - your videos are never sent.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        SquishOutlinedButton(
            text = "Browse free music online",
            modifier = Modifier.fillMaxWidth(),
            onClick = { gate.request("Free music") {} }
        )
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf(OnlineMusic.Genre.Popular) }
    var tracks by remember { mutableStateOf<List<OnlineMusic.Track>?>(null) }
    // Pages fetched so far for this search, and whether the last came back full.
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    // One track that will not play is said about that track, not as the list failing.
    var rowNote by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query, genre) {
        delay(if (query.isEmpty()) 0L else 500L)
        tracks = null
        failed = false
        page = 1
        // A cancelled search (a chip tapped while the last was loading) is not a
        // failure: caught as one, it set the error after the new search had
        // cleared it, and the list read "Couldn't reach" over good results.
        val first = try {
            OnlineMusic.search(context, query, genre = genre)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        failed = first == null
        more = first?.full == true
        tracks = first?.tracks.orEmpty()
        loadingMore = false
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
                Text("Search free music: lofi, piano, happy…", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextMuted)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SquishColors.TextPrimary),
                cursorBrush = SolidColor(SquishColors.Cyan),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search free music" }
            )
        }
    }
    // Browse by style: thousands of tracks each, where an empty search showed
    // the same twenty.
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        OnlineMusic.Genre.entries.forEach { g ->
            SelectableChip(g.label, genre == g, accentColor = SquishColors.Cyan, onClick = { genre = g })
        }
    }
    Text(
        "Creative Commons: credit the artist where the licence is CC BY.",
        style = MaterialTheme.typography.labelSmall,
        color = SquishColors.TextMuted
    )
    rowNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Yellow) }

    val list = tracks
    when {
        failed -> Text(
            "Couldn't reach the Internet Archive. Check the connection and try again.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.Yellow
        )
        list == null -> CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        list.isEmpty() -> Text("Nothing matches \"$query\".", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
        else -> list.forEach { track ->
            val key = "online:${track.id}"
            val pick = MusicPick(key, track.title, listOfNotNull(track.artist, track.licenseLabel).joinToString(" · "))
            MusicRow(
                pick = pick,
                playing = playingKey == key,
                busy = preparing == key,
                // Starred under its downloaded file, which is what the starred list plays.
                starred = isStarred(key) || isStarred(Uri.fromFile(OnlineMusic.localFile(context, track)).toString()),
                onListen = {
                    scope.launch {
                        onBusy(key)
                        val url = runCatching { OnlineMusic.streamUrl(context, track) }.getOrNull()
                        onBusy(null)
                        if (url != null) onListen(key, Uri.parse(url)) else rowNote = "“${track.title}” couldn't be played - try another."
                    }
                },
                // Kept by its downloaded file, so the starred list plays it offline.
                onStar = {
                    scope.launch {
                        onBusy(key)
                        val uri = runCatching { OnlineMusic.download(context, track) }.getOrNull()
                        onBusy(null)
                        if (uri != null) onStar(MusicPick(uri.toString(), pick.title, pick.subtitle)) else rowNote = "“${track.title}” couldn't be downloaded - try another."
                    }
                },
                onAdd = {
                    scope.launch {
                        onBusy(key)
                        val uri = runCatching { OnlineMusic.download(context, track) }.getOrNull()
                        onBusy(null)
                        if (uri != null) onAdd(MusicPick(uri.toString(), pick.title, pick.subtitle), uri) else rowNote = "“${track.title}” couldn't be downloaded - try another."
                    }
                }
            )
        }
    }
    // Offered on an empty page too: a page the licence filter emptied is not the end.
    if (!failed && list != null && more) {
        SquishOutlinedButton(
            text = if (loadingMore) "Loading…" else "Load more",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (loadingMore) return@SquishOutlinedButton
                loadingMore = true
                // The search this page belongs to: a chip tapped or a word typed while
                // it loads makes it another search's page, and it is dropped.
                val forQuery = query
                val forGenre = genre
                val forPage = page
                scope.launch {
                    val next = runCatching { OnlineMusic.search(context, forQuery, genre = forGenre, page = forPage + 1) }.getOrNull()
                    if (query != forQuery || genre != forGenre || page != forPage) return@launch
                    loadingMore = false
                    if (next == null) {
                        rowNote = "Couldn't load more - check the connection."
                    } else {
                        page += 1
                        val seen = tracks.orEmpty().map { it.id }.toSet()
                        tracks = tracks.orEmpty() + next.tracks.filter { it.id !in seen }
                        more = next.full
                    }
                }
            }
        )
    }
}
