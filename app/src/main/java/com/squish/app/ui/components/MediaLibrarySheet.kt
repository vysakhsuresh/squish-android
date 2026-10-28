package com.squish.app.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.core.app.ActivityCompat
import com.squish.app.media.MediaLibrary
import com.squish.app.ui.theme.SquishColors

/**
 * The app's own gallery: every photo and video the phone has indexed, by
 * album, several picked at once in the order they are tapped.
 *
 * Built after the first real edit cut on this app came back with "some of
 * the videos I took were not showing in the library" - the system photo
 * picker, which was the only way in, shows a curated subset that differs by
 * phone (see [MediaLibrary]). This reads the index directly, as InShot and
 * CapCut do, and keeps the picker and the file browser as the other two
 * doors at the foot of the sheet: the picker for cloud photos and for
 * someone who would rather not grant access, the browser for a file in a
 * folder the index never scans.
 *
 * [kinds] is what the sheet offers; [maxPick] caps the selection, with the
 * count shown on the Done button. [onPicked] gets the URIs in tap order.
 * [onSystemPicker] and [onBrowseFiles] open the other doors, after this
 * sheet has closed itself - they are launchers the caller already owns.
 */
@Composable
fun MediaLibrarySheet(
    title: String,
    kinds: Set<MediaLibrary.Kind>,
    maxPick: Int,
    onPicked: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
    onSystemPicker: (() -> Unit)? = null,
    onBrowseFiles: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var access by remember { mutableStateOf(MediaLibrary.access(context)) }
    // Re-read after the permission dialog, whatever it answered: a "Select
    // photos" answer on Android 14 grants the partial one, which is access.
    var askedNonce by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = MediaLibrary.access(context)
        askedNonce++
    }
    val refusedForGood = remember(access, askedNonce) {
        access == MediaLibrary.Access.None && askedNonce > 0 && activity != null &&
            MediaLibrary.permissions().none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }

    // Suppressed for the reason CLAUDE.md gives: the check does not see the
    // assignment in Compose 1.7.2, written any of three ways.
    @Suppress("ProduceStateDoesNotAssignValue")
    val items by produceState<List<MediaLibrary.Item>?>(initialValue = null, access, askedNonce, kinds) {
        value = if (access == MediaLibrary.Access.None) emptyList() else MediaLibrary.load(context, kinds)
    }
    var album by remember { mutableStateOf<String?>(null) }
    // Videos first, as an editor should open: the phone in hand had 46,000
    // WhatsApp pictures ahead of every video under "All".
    var kindFilter by remember { mutableStateOf(if (MediaLibrary.Kind.Video in kinds && kinds.size > 1) MediaLibrary.Kind.Video else null) }
    val albums = remember(items) { items?.let { MediaLibrary.albums(it) } ?: emptyList() }
    val shown = remember(items, album, kindFilter) {
        (items ?: emptyList()).filter { (album == null || it.album == album) && (kindFilter == null || it.kind == kindFilter) }
    }
    // Tap order is the order they land on the track, so the set is a list.
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(album, kindFilter) { gridState.scrollToItem(0) }

    // Laid in the screen's own tree, over everything, rather than in a Dialog
    // window: in a Dialog here the navigation bar's inset was never reported,
    // with or without decorFitsSystemWindows, so the last row of the grid and
    // the two doors under it sat beneath the bar (seen on the phone, 14
    // October). The activity's own insets are the ones the rest of the app
    // lays out by, and they are right. The press swallower keeps a tap on the
    // sheet's own background from reaching the screen underneath.
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SquishColors.Background)
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = SquishColors.TextSecondary)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = SquishColors.TextPrimary)
                    val count = items?.size
                    Text(
                        when {
                            access == MediaLibrary.Access.None -> "Needs access to the phone's photos and videos"
                            count == null -> "Reading the phone's library…"
                            // Full: a tap past the cap does nothing, so the header says why
                            // (28 Oct: the 31st of 50 photos was tapped and nothing happened).
                            maxPick > 1 && picked.size >= maxPick -> "$maxPick picked - the most one go adds"
                            access == MediaLibrary.Access.Partial -> "Showing the $count you selected"
                            else -> if (count == 1) "1 item, newest first" else "$count items, newest first"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.TextMuted
                    )
                }
                val n = picked.size
                SquishPrimaryButton(
                    text = if (n == 0) "Add" else "Add $n",
                    enabled = n > 0,
                    onClick = { onPicked(picked) }
                )
            }

            when (access) {
                MediaLibrary.Access.None -> AccessCard(
                    refusedForGood = refusedForGood,
                    onAsk = { ask.launch(MediaLibrary.permissions()) },
                    onSettings = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    }
                )
                MediaLibrary.Access.Partial -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SquishColors.Surface)
                        .clickable(role = Role.Button) { ask.launch(MediaLibrary.permissions()) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Only the photos and videos you selected are shown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text("Select more", style = MaterialTheme.typography.labelSmall, color = SquishColors.Cyan)
                }
                MediaLibrary.Access.Full -> Unit
            }

            if (access != MediaLibrary.Access.None && (items?.isNotEmpty() == true)) {
                FilterRow(
                    kinds = kinds,
                    kindFilter = kindFilter,
                    onKind = { kindFilter = it },
                    albums = albums,
                    album = album,
                    onAlbum = { album = it }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = items
                when {
                    access == MediaLibrary.Access.None -> Unit
                    list == null -> CircularProgressIndicator(
                        color = SquishColors.Cyan, strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp).align(Alignment.Center)
                    )
                    // Empty said why, with the way out under it. With only some
                    // files shared (Android 14's partial access) the grid opened on
                    // Videos and read "Nothing here." when the one file shared was
                    // a photo - a dead end that looked like an empty phone.
                    shown.isEmpty() -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            when {
                                list.isEmpty() && access == MediaLibrary.Access.Partial -> "Squish can only see the files you shared with it, and none are shared yet."
                                list.isEmpty() -> "Nothing in the phone's library yet."
                                access == MediaLibrary.Access.Partial -> "None of the files you shared with Squish is here."
                                else -> "Nothing here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SquishColors.TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (access == MediaLibrary.Access.Partial) {
                            SelectableChip(label = "Choose more files", selected = true, accentColor = SquishColors.Cyan,
                                onClick = { ask.launch(MediaLibrary.permissions()) })
                        }
                        if (kindFilter != null && list.isNotEmpty()) {
                            SelectableChip(label = "Show photos and videos", selected = false, accentColor = SquishColors.Cyan,
                                onClick = { kindFilter = null })
                        }
                    }
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.uri.toString() }) { item ->
                            val order = picked.indexOf(item.uri)
                            MediaTile(
                                item = item,
                                order = if (order >= 0) order + 1 else null,
                                onClick = {
                                    picked = when {
                                        order >= 0 -> picked - item.uri
                                        maxPick == 1 -> listOf(item.uri)
                                        picked.size < maxPick -> picked + item.uri
                                        else -> picked
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (onSystemPicker != null || onBrowseFiles != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    onSystemPicker?.let { open ->
                        OtherDoor(Icons.Filled.PhotoLibrary, "Photos app", Modifier.weight(1f)) { onDismiss(); open() }
                    }
                    onBrowseFiles?.let { open ->
                        OtherDoor(Icons.Filled.FolderOpen, "Browse files", Modifier.weight(1f)) { onDismiss(); open() }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessCard(refusedForGood: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Squish reads the phone's photos and videos to show them here. Nothing is uploaded - the library stays on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = SquishColors.TextSecondary
        )
        if (refusedForGood) {
            Text(
                "Access was turned off. It can be turned on in the app's settings, or use the Photos app below.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Yellow
            )
            SquishOutlinedButton(text = "Open settings", onClick = onSettings)
        } else {
            SquishPrimaryButton(text = "Allow access", onClick = onAsk)
        }
    }
}

@Composable
private fun FilterRow(
    kinds: Set<MediaLibrary.Kind>,
    kindFilter: MediaLibrary.Kind?,
    onKind: (MediaLibrary.Kind?) -> Unit,
    albums: List<Pair<String, Int>>,
    album: String?,
    onAlbum: (String?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectableChip("All", selected = kindFilter == null && album == null, onClick = { onKind(null); onAlbum(null) })
        if (kinds.size > 1) {
            SelectableChip("Videos", selected = kindFilter == MediaLibrary.Kind.Video, accentColor = SquishColors.Violet) {
                onKind(if (kindFilter == MediaLibrary.Kind.Video) null else MediaLibrary.Kind.Video)
            }
            SelectableChip("Photos", selected = kindFilter == MediaLibrary.Kind.Photo, accentColor = SquishColors.Violet) {
                onKind(if (kindFilter == MediaLibrary.Kind.Photo) null else MediaLibrary.Kind.Photo)
            }
        }
        // Only albums worth a chip: a single folder is "All" already.
        if (albums.size > 1) {
            albums.take(MAX_ALBUM_CHIPS).forEach { (name, count) ->
                SelectableChip("$name · $count", selected = album == name, accentColor = SquishColors.Cyan) {
                    onAlbum(if (album == name) null else name)
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaLibrary.Item, order: Int?, onClick: () -> Unit) {
    val context = LocalContext.current
    @Suppress("ProduceStateDoesNotAssignValue")
    val tile by produceState<Bitmap?>(initialValue = MediaLibrary.tile(item.uri), item.uri) {
        if (value == null) value = MediaLibrary.loadTile(context, item.uri)
    }
    val label = buildString {
        append(if (item.kind == MediaLibrary.Kind.Video) "Video " else "Photo ")
        append(item.name)
        if (item.kind == MediaLibrary.Kind.Video && item.durationMs > 0) append(", ").append(MediaLibrary.durationLabel(item.durationMs))
        if (order != null) append(", picked $order")
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(SquishColors.Surface)
            .selectable(selected = order != null, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        val bitmap = tile
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // The provider could not picture it (or has not yet): the kind and
            // the name, so a file the gallery itself cannot draw is still a
            // thing that can be picked - which is the whole point of this grid.
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (item.kind == MediaLibrary.Kind.Video) Icons.Filled.Videocam else Icons.Filled.Image,
                    contentDescription = null,
                    tint = SquishColors.TextMuted,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (order != null) {
            Box(modifier = Modifier.fillMaxSize().background(SquishColors.Primary.copy(alpha = 0.22f)))
            Box(modifier = Modifier.fillMaxSize().border(2.dp, SquishColors.Primary, RoundedCornerShape(8.dp)))
            OrderBadge(order, SquishColors.Primary, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        if (item.kind == MediaLibrary.Kind.Video && item.durationMs > 0) {
            Text(
                MediaLibrary.durationLabel(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun OtherDoor(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SquishColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
    }
}

private const val MAX_ALBUM_CHIPS = 12
