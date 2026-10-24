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
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import com.squish.app.media.SoftwareDecoders
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun MusicPanel(viewModel: EditorViewModel, editorPlaying: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The category last used, across sessions: someone who adds their own
    // songs picked "On this phone" again every time the sheet opened.
    val musicPrefs = remember { context.getSharedPreferences("music", android.content.Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableIntStateOf(musicPrefs.getInt("lastTab", 0).coerceIn(0, 4)) }
    // And its list brought up into view when a category is picked: the list
    // is under the fold, so a tap seemed to do nothing but light a chip.
    val listInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    // Set from the start when a category was remembered: reopened on "On this
    // phone", the sheet showed its chips and hid the songs under them again.
    var tabTapped by remember { mutableStateOf(tab != 0) }
    LaunchedEffect(tab) {
        musicPrefs.edit().putInt("lastTab", tab).apply()
        // After the list has had its moment to load: the phone's songs are read
        // in the background, and asked at once there was nothing below to show.
        if (tabTapped) {
            kotlinx.coroutines.delay(400)
            listInView.bringIntoView()
        }
    }
    // The library is browsed as InShot's is: a wall of category tiles, then
    // the category's list; a search reaches across all of it. Null is the
    // wall. Kept per tab, so going between Originals and Effects keeps both.
    var musicShelf by rememberSaveable { mutableStateOf<String?>(null) }
    var sfxShelf by rememberSaveable { mutableStateOf<String?>(null) }
    var musicQuery by rememberSaveable { mutableStateOf("") }
    var sfxQuery by rememberSaveable { mutableStateOf("") }
    val musicShown = remember(musicShelf, musicQuery) {
        val q = musicQuery.trim()
        when {
            q.isNotEmpty() -> MusicSynth.styles.filter {
                it.title.contains(q, ignoreCase = true) || it.mood.contains(q, ignoreCase = true) ||
                    MusicSynth.vibeOf(it).contains(q, ignoreCase = true)
            }
            musicShelf == null -> emptyList()
            musicShelf == "All" -> MusicSynth.styles
            else -> MusicSynth.styles.filter { MusicSynth.vibeOf(it) == musicShelf }
        }
    }
    val sfxShown = remember(sfxShelf, sfxQuery) {
        val q = sfxQuery.trim()
        when {
            q.isNotEmpty() -> MusicSynth.effects.filter {
                it.title.contains(q, ignoreCase = true) || it.hint.contains(q, ignoreCase = true) ||
                    it.category.contains(q, ignoreCase = true)
            }
            sfxShelf == null -> emptyList()
            sfxShelf == "All" -> MusicSynth.effects
            else -> MusicSynth.effects.filter { it.category == sfxShelf }
        }
    }

    // The originals on show made ahead, in the background, a moment after
    // they are shown - so Listen and Add on one are instant rather than a
    // second's composing each (MusicLibrary.prepare). Made once; after that
    // this finds them on disk and returns at once.
    LaunchedEffect(musicShown) {
        if (musicShown.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        MusicLibrary.prepare(context, musicShown)
    }

    // One small player for listening before adding, released with the panel.
    var playingKey by remember { mutableStateOf<String?>(null) }
    val player = remember {
        // The FFmpeg decoders behind the platform's: a FLAC, an ALAC or an
        // Opus file in a phone's music folder auditions whatever the phone's
        // own decoders make of it (SoftwareDecoders).
        ExoPlayer.Builder(context, SoftwareDecoders.renderers(context))
            // Playing from a quarter second of sound, as the timeline's sound
            // players do, rather than Media3's default two and a half - which
            // was the pause between tapping Listen and hearing anything.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 15_000, 250, 500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(SoftwareDecoders.mediaSourceFactory(context))
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

    var usedCount by remember { mutableIntStateOf(0) }
    fun add(pick: MusicPick, uri: Uri) {
        stopListening()
        MusicLibrary.noteUsed(context, pick)
        usedCount++
        viewModel.audio.addAudioTrack(uri, pick.title, asSong = true)
    }

    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Music",
            "Tap to listen · the first song starts with the video, later ones at the playhead",
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
                    SelectableChip(label, tab == i, accentColor = SquishColors.Cyan, modifier = Modifier.weight(1f), onClick = { tabTapped = true; tab = i })
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(listInView), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        when (tab) {
            0 -> {
                Text(
                    "${MusicSynth.styles.size} tracks composed by Squish on your phone — yours to use anywhere, no credit needed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                LibrarySearch(musicQuery, { musicQuery = it }, "Search music: vlog, chill, dance…")
                if (musicQuery.isBlank() && musicShelf == null) {
                    ShelfGrid(
                        MusicSynth.vibes.map { v ->
                            musicShelfLook(v).copy(count = if (v == "All") MusicSynth.styles.size else MusicSynth.styles.count { MusicSynth.vibeOf(it) == v })
                        },
                        unit = "tracks",
                        onPick = { stopListening(); musicShelf = it }
                    )
                } else if (musicQuery.isBlank()) {
                    ShelfHeader(musicShelfLook(musicShelf!!).copy(count = musicShown.size), "tracks") { stopListening(); musicShelf = null }
                } else if (musicShown.isEmpty()) {
                    Text("No track matches \"${musicQuery.trim()}\".", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
                }
                musicShown.forEach { style ->
                    val pick = MusicPick("orig:${style.id}", style.title, "${style.mood} · ${style.seconds}s")
                    MusicRow(
                        pick = pick,
                        art = musicShelfLook(MusicSynth.vibeOf(style)).copy(emoji = MusicSynth.emojiOf(style)),
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
                    "${MusicSynth.effects.size} sounds: a whoosh on a cut, a pop on a sticker, a riser into the drop. Made on the phone, like the music.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                LibrarySearch(sfxQuery, { sfxQuery = it }, "Search sounds: whoosh, coin, rain…")
                if (sfxQuery.isBlank() && sfxShelf == null) {
                    ShelfGrid(
                        (listOf("All") + MusicSynth.effectCategories).map { c ->
                            sfxShelfLook(c).copy(count = if (c == "All") MusicSynth.effects.size else MusicSynth.effects.count { it.category == c })
                        },
                        unit = "sounds",
                        onPick = { stopListening(); sfxShelf = it }
                    )
                } else if (sfxQuery.isBlank()) {
                    ShelfHeader(sfxShelfLook(sfxShelf!!).copy(count = sfxShown.size), "sounds") { stopListening(); sfxShelf = null }
                } else if (sfxShown.isEmpty()) {
                    Text("No sound matches \"${sfxQuery.trim()}\".", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
                }
                sfxShown.forEach { effect ->
                    val pick = MusicPick("sfx:${effect.id}", effect.title, effect.hint)
                    MusicRow(
                        pick = pick,
                        art = sfxShelfLook(effect.category).copy(emoji = MusicSynth.emojiOf(effect)),
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
                // Read again after each add, not only when the stars change: a track
                // added from here went to the top of the list only after leaving it.
                val recents = remember(favourites, usedCount) { MusicLibrary.recents(context) }
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

/**
 * A shelf of the library as its tile draws it: a name, a glyph and two
 * colours. The same look is the artwork on each of its rows, so a track or a
 * sound says where it lives when it turns up in a search.
 */
private data class ShelfLook(val name: String, val emoji: String, val from: androidx.compose.ui.graphics.Color, val to: androidx.compose.ui.graphics.Color, val count: Int = 0)

private fun musicShelfLook(name: String): ShelfLook = when (name) {
    "Vlog" -> ShelfLook(name, "🎒", SquishColors.Orange, SquishColors.Amber)
    "Chill" -> ShelfLook(name, "☕", SquishColors.Cyan, SquishColors.Blue)
    "Upbeat" -> ShelfLook(name, "☀️", SquishColors.Amber, SquishColors.Orange)
    "Dance" -> ShelfLook(name, "🪩", SquishColors.Magenta, SquishColors.Violet)
    "Beats" -> ShelfLook(name, "🎧", SquishColors.Violet, SquishColors.Blue)
    "Cinematic" -> ShelfLook(name, "🎬", SquishColors.Blue, SquishColors.Violet)
    "Emotional" -> ShelfLook(name, "💗", SquishColors.Magenta, SquishColors.Orange)
    "Fun" -> ShelfLook(name, "🎈", SquishColors.Amber, SquishColors.Magenta)
    "Business" -> ShelfLook(name, "💼", SquishColors.Blue, SquishColors.Cyan)
    else -> ShelfLook("All", "🎵", SquishColors.Cyan, SquishColors.Violet)
}

private fun sfxShelfLook(name: String): ShelfLook = when (name) {
    "Transitions" -> ShelfLook(name, "💨", SquishColors.Cyan, SquishColors.Blue)
    "Impacts" -> ShelfLook(name, "💥", SquishColors.Orange, SquishColors.Magenta)
    "Funny" -> ShelfLook(name, "🤡", SquishColors.Amber, SquishColors.Orange)
    "UI & alerts" -> ShelfLook(name, "🔔", SquishColors.Blue, SquishColors.Cyan)
    "Game" -> ShelfLook(name, "🕹️", SquishColors.Violet, SquishColors.Magenta)
    "Musical" -> ShelfLook(name, "🎺", SquishColors.Amber, SquishColors.Violet)
    "Build-ups" -> ShelfLook(name, "🚀", SquishColors.Magenta, SquishColors.Violet)
    "Nature" -> ShelfLook(name, "🌧️", SquishColors.Cyan, SquishColors.Violet)
    "Horror" -> ShelfLook(name, "👻", SquishColors.Violet, SquishColors.Blue)
    else -> ShelfLook("All", "🔊", SquishColors.Cyan, SquishColors.Violet)
}

private fun ShelfLook.brush() = androidx.compose.ui.graphics.Brush.linearGradient(listOf(from, to))

/** The wall of category tiles, two to a row. */
@Composable
private fun ShelfGrid(shelves: List<ShelfLook>, unit: String, onPick: (String) -> Unit) {
    shelves.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            pair.forEach { shelf ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(shelf.brush())
                        .clickable(role = Role.Button, onClickLabel = "Open") { onPick(shelf.name) }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(shelf.name, style = MaterialTheme.typography.titleSmall, color = SquishColors.Background, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${shelf.count} $unit", style = MaterialTheme.typography.labelSmall, color = SquishColors.Background.copy(alpha = 0.75f))
                    }
                    // Decoration: the name beside it already says what this is.
                    Text(shelf.emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "" })
                }
            }
            // An odd last tile keeps its half width rather than stretching.
            if (pair.size == 1) Box(modifier = Modifier.weight(1f))
        }
    }
}

/** Above an open category: the way back to the wall, and what is open. */
@Composable
private fun ShelfHeader(shelf: ShelfLook, unit: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, SquishColors.Border, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Back to categories", onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = SquishColors.TextSecondary, modifier = Modifier.size(16.dp))
            Text(" Categories", style = MaterialTheme.typography.labelLarge, color = SquishColors.TextSecondary)
        }
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(shelf.brush()),
            contentAlignment = Alignment.Center
        ) { Text(shelf.emoji, style = MaterialTheme.typography.bodyMedium) }
        Column(modifier = Modifier.weight(1f)) {
            Text(shelf.name, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shelf.count} $unit", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        }
    }
}

@Composable
private fun LibrarySearch(query: String, onChange: (String) -> Unit, hint: String) {
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
        Box(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            if (query.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SquishColors.TextPrimary),
                cursorBrush = SolidColor(SquishColors.Cyan),
                // Named for what it searches, not by the placeholder, which is
                // gone the moment anything is typed (CLAUDE.md, 7 October).
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search the library" }
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = SquishColors.TextMuted,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onChange("") }.padding(7.dp)
            )
        }
    }
}

@Composable
private fun MusicRow(
    pick: MusicPick,
    art: ShelfLook? = null,
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
        // With artwork: the category's tile in small, the play state over it.
        Box(
            modifier = if (art != null) Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(art.brush()) else Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (art != null && !busy && !playing) {
                Text(art.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "" })
            }
            if (busy) {
                CircularProgressIndicator(color = if (art != null) SquishColors.Background else SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else if (art == null || playing) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Listen",
                    tint = if (art != null) SquishColors.Background else SquishColors.Cyan
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
