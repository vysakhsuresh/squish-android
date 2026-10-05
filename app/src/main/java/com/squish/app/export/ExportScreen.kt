package com.squish.app.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.data.ExportRecord
import com.squish.app.data.SquishRepositories
import com.squish.app.home.formatSize
import com.squish.app.media.ThumbnailCache
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.VideoMeta
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.VideoPreviewSheet
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What happened, where it went, and what to do with it.
 *
 * Three things this screen used to get wrong. It said "Compress another video"
 * whatever job had just run, so merging four clips ended on an offer to compress.
 * It never said where the file had gone, although it had already been published to
 * the gallery - so the commonest question after an export had no answer anywhere
 * in the app. And its share row was four coloured circles with no marks on them.
 *
 * And a fourth: it could not play the file it had just written. The moment a
 * wrong transition or a missing overlay is noticed is here, and it meant leaving
 * for the gallery. The file plays over the screen now, and the screen says what
 * the file *is* - frame, length, rate, weight, measured off the file itself -
 * where it used to compare "before" and "after" sizes, a leftover from the
 * compress tool that made no sense of an edit with added clips and music. That
 * comparison stays where it belongs: on Squeeze's done screen.
 *
 * The file it plays and shares is the export's one copy - the gallery's, once
 * that was verified (ExportRecord.mediaUri) - so the private path in the route
 * is the record's name, not necessarily a file that still exists.
 */
@Composable
fun ExportScreen(
    resultPath: String,
    /** Already in the past tense — "Stitched", "Exported". The screen adds nothing. */
    jobLabel: String,
    /**
     * Where back leads - "Back to editor", "Back to Stitch" - because the screen
     * that made this file is still underneath, with the edit on it. The only way
     * off this screen used to be the dashboard, with the editor popped and the
     * draft deleted behind it.
     */
    backLabel: String,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    // Watched rather than read once: the record lands a moment after the
    // route, when the history's write has been awaited.
    val records by SquishRepositories.history(context).records.collectAsState()
    val record = records.firstOrNull { it.outputPath == resultPath }
    val isAudio = record?.isAudio ?: resultPath.endsWith(".m4a", ignoreCase = true)
    val fileUri = record?.mediaUri ?: Uri.fromFile(File(resultPath))
    var notice by remember { mutableStateOf<String?>(null) }

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
                Spacer(modifier = Modifier.height(20.dp))

                // The tick springs in once, the moment the file is ready.
                var landed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { landed = true }
                val pop by animateFloatAsState(
                    if (landed) 1f else 0.4f,
                    spring(dampingRatio = 0.42f, stiffness = 260f),
                    label = "tick"
                )
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }
                        .clip(CircleShape)
                        .background(accentSweep(SquishColors.Cyan)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SquishColors.Background,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Text(
                    jobLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    color = SquishColors.TextPrimary
                )
                Text(
                    "No watermark, no upload, nothing held back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                ExportedFile(
                    uri = fileUri,
                    isAudio = isAudio,
                    fileName = File(resultPath).name,
                    title = record?.title ?: File(resultPath).name,
                    fallbackDurationMs = record?.durationMs ?: 0L,
                    // The comparison is the compress tool's own question. An edit
                    // has added clips, stills and music; "before" means nothing there.
                    originalBytes = record?.originalSizeBytes?.takeIf { jobLabel == "Squeezed" } ?: 0L,
                    // An older record never recorded it either way; those went
                    // through the same copy and are taken at their word.
                    inGallery = record?.savedToGallery != false,
                    notice = notice,
                    onNotice = { notice = it }
                )

                Spacer(modifier = Modifier.height(10.dp))
                SquishPrimaryButton(
                    text = backLabel,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBack
                )
                SquishOutlinedButton(
                    text = "Back to Squish",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone
                )
                // No floating back orb: it sat on the share row, and the way back
                // is the button just above.
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * One exported file, as the done screen and the library's detail screen both
 * show it: its cover (tap to play), the facts measured off it, where it went,
 * a copy to Files, and the share row. Everything below the celebration and
 * above the way out.
 */
@Composable
fun ExportedFile(
    uri: Uri,
    isAudio: Boolean,
    fileName: String,
    title: String,
    fallbackDurationMs: Long,
    originalBytes: Long,
    inGallery: Boolean,
    notice: String?,
    onNotice: (String?) -> Unit
) {
    val context = LocalContext.current
    // Measured off the file, not read off the record: the record says what was
    // asked for, the file says what was written.
    var meta by remember(uri) { mutableStateOf<VideoMeta?>(null) }
    var sizeBytes by remember(uri) { mutableStateOf(0L) }
    var cover by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var previewing by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        sizeBytes = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)
        }
        meta = ThumbnailExtractor.probe(context, uri)
        // A third of the way in, as the library takes it: the first frame of an
        // edit that fades or twirls in is black, and the card read as empty.
        val lengthMs = meta?.durationMs?.takeIf { it > 0L } ?: fallbackDurationMs
        if (!isAudio) cover = ThumbnailCache.frame(context, uri, (lengthMs / 3).coerceAtLeast(0L))
    }

    // A second copy, wherever they want it. The automatic publish puts it in the
    // gallery, which is right for most people and useless for anyone who wants it
    // on an SD card or in a folder they sync.
    val copyScope = rememberCoroutineScope()
    val saveCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (isAudio) "audio/mp4" else "video/mp4")
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        // Off the main thread: a few hundred MB to an SD card or a cloud folder
        // froze the screen until Android offered to close the app.
        onNotice("Copying...")
        copyScope.launch {
            onNotice(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } ?: error("no source")
                } ?: error("no stream")
                "Copy saved."
            }.getOrElse { "Could not write there — try a different folder." } })
        }
    }

    Cover(
        cover = cover,
        meta = meta,
        isAudio = isAudio,
        uri = uri,
        onPlay = { previewing = true }
    )

    FactsCard(
        meta = meta,
        sizeBytes = sizeBytes,
        isAudio = isAudio,
        originalBytes = originalBytes
    )

    SavedToCard(
        isAudio = isAudio,
        fileName = fileName,
        inGallery = inGallery
    )

    // A copy wherever they want it, and - for a video - a GIF of its first seconds.
    // Made in the app's own scope (GifJobs): leaving this screen does not stop
    // it, and a toast says when it lands.
    val gifs by com.squish.app.media.gif.GifJobs.progress.collectAsState()
    val gifProgress = gifs[uri]
    // The note said "Making a GIF…" long after it was made. It says so when
    // the job lets go of this file.
    var gifRunning by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(gifProgress) {
        if (gifProgress != null) gifRunning = true
        else if (gifRunning) {
            gifRunning = false
            val made = com.squish.app.media.gif.GifJobs.finished.value[uri] == true
            onNotice(
                if (made) "GIF saved to Pictures › Squish - the first ${com.squish.app.media.gif.GifMaker.MAX_SECONDS} s."
                else "Couldn't make a GIF of this video."
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SquishOutlinedButton(
            text = "Copy to Files",
            modifier = Modifier.weight(1f),
            onClick = { saveCopy.launch(fileName) }
        )
        if (!isAudio) {
            SquishOutlinedButton(
                text = gifProgress?.let { "GIF ${(it * 100).toInt()}%" } ?: "Save as GIF",
                modifier = Modifier.weight(1f),
                onClick = {
                    if (gifProgress != null) return@SquishOutlinedButton
                    com.squish.app.media.gif.GifJobs.start(context, uri, fileName.substringBeforeLast('.'))
                    onNotice(
                        "Making a GIF of the first ${com.squish.app.media.gif.GifMaker.MAX_SECONDS} s - it carries on if you leave; " +
                            "it lands in Pictures › Squish."
                    )
                }
            )
        }
    }

    notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Cyan)
    }

    val mime = if (isAudio) "audio/mp4" else "video/mp4"
    Text(
        "Share",
        style = MaterialTheme.typography.labelSmall,
        color = SquishColors.TextMuted,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShareTarget("WhatsApp", ShareGlyphs.WhatsApp, ShareStyle.WhatsApp) {
            if (!ShareUtils.share(context, uri, mime, "com.whatsapp")) {
                onNotice("Nothing on this phone can share that.")
            }
        }
        ShareTarget("Instagram", ShareGlyphs.Instagram, ShareStyle.Instagram) {
            if (!ShareUtils.share(context, uri, mime, "com.instagram.android")) {
                onNotice("Nothing on this phone can share that.")
            }
        }
        ShareTarget("Mail", ShareGlyphs.Mail, ShareStyle.Mail) {
            sendByEmail(context, uri, fileName, isAudio) { onNotice(it) }
        }
        ShareTarget("More", ShareGlyphs.More, ShareStyle.More) {
            if (!ShareUtils.share(context, uri, mime, null)) {
                onNotice("Nothing on this phone can share that.")
            }
        }
    }

    if (previewing) {
        VideoPreviewSheet(
            title = title,
            subtitle = factsLine(meta, sizeBytes, isAudio),
            uri = uri,
            durationMs = meta?.durationMs ?: fallbackDurationMs,
            accent = SquishColors.Cyan,
            aspect = meta?.let { if (it.displayWidth > 0 && it.displayHeight > 0) it.displayWidth.toFloat() / it.displayHeight else 0f } ?: 0f,
            audioOnly = isAudio,
            onDismiss = { previewing = false }
        )
    }
}

/**
 * The file's first frame with a play button over it, in the file's own shape;
 * a sound file gets a note in place of a picture. Tapping it plays the file
 * over this screen (VideoPreviewSheet), so a wrong transition is seen here
 * and not after a trip to the gallery.
 */
@Composable
private fun Cover(cover: Bitmap?, meta: VideoMeta?, isAudio: Boolean, uri: Uri, onPlay: () -> Unit) {
    // A sound's own shape where a picture would be, as Extract audio shows it
    // before the cut: a note alone said nothing about what was saved.
    val context = androidx.compose.ui.platform.LocalContext.current
    var wave by remember(uri) { mutableStateOf<FloatArray?>(null) }
    if (isAudio) LaunchedEffect(uri) {
        // Through decodePeaks, which is the function that exists for this: one
        // float per 50 ms, read straight off the decoder. decodeMono held the
        // whole decimated file - a ten-minute sound is 4.8 million floats, and
        // the doubling plus the final copy is about 38 MB live at the peak - to
        // produce ninety bars, and on a tight heap it returns null by design,
        // so the wave silently never appeared. The same twelve thousand peaks
        // the strip draws from, gathered into the card's ninety columns.
        val sound = com.squish.app.media.audio.PcmDecoder.decodePeaks(context, uri, maxDurationMs = 10 * 60_000L)
        wave = sound?.let { com.squish.app.media.audio.WaveformBuilder.columns(it.peaks, columns = 90) }
    }
    val shape = when {
        isAudio -> 3.2f
        meta != null && meta.displayWidth > 0 && meta.displayHeight > 0 -> (meta.displayWidth.toFloat() / meta.displayHeight).coerceIn(0.5f, 2.2f)
        cover != null -> (cover.width.toFloat() / cover.height.coerceAtLeast(1)).coerceIn(0.5f, 2.2f)
        else -> 16f / 9f
    }
    // Sized outright: aspectRatio under a height cap took the width first, so a
    // portrait file's cover was laid out 300 dp tall but drawn twice that,
    // over the title above it and the cards below.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val width = minOf(maxWidth, COVER_MAX_HEIGHT * shape)
    Box(
        modifier = Modifier
            .size(width, width / shape)
            .clip(RoundedCornerShape(18.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(18.dp))
            // Named, because nothing inside this one is words: the cover is an
            // image with no description and the sound-only case is a waveform
            // drawn on a Canvas, so a screen reader had a button and no idea
            // what it did - on the one screen whose whole point is "watch what
            // you just made".
            .clickable(role = Role.Button, onClickLabel = if (isAudio) "Play the file" else "Watch the file", onClick = onPlay),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (isAudio && wave?.any { it > 0f } == true) {
            val peaks = wave!!
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp)) {
                val step = size.width / peaks.size
                val mid = size.height / 2f
                peaks.forEachIndexed { i, p ->
                    val x = i * step + step / 2f
                    val half = p.coerceIn(0.04f, 1f) * size.height * 0.46f
                    drawLine(
                        SquishColors.Cyan.copy(alpha = 0.55f),
                        androidx.compose.ui.geometry.Offset(x, mid - half),
                        androidx.compose.ui.geometry.Offset(x, mid + half),
                        strokeWidth = step * 0.6f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        } else if (isAudio) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = SquishColors.Cyan.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp).align(Alignment.CenterStart).padding(start = 16.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(SquishColors.Background.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play the file",
                tint = SquishColors.TextPrimary,
                modifier = Modifier.size(34.dp)
            )
        }
    }
    }
}

/** The tallest a cover is drawn: a portrait file's stays clear of the cards under it. */
private val COVER_MAX_HEIGHT = 300.dp

/**
 * What the file is: its frame, length, rate and weight, each measured off the
 * file. For a squeeze, how much lighter it came out than what went in.
 */
@Composable
private fun FactsCard(meta: VideoMeta?, sizeBytes: Long, isAudio: Boolean, originalBytes: Long) {
    SquishCard(accent = SquishColors.Cyan) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!isAudio) {
                Fact("Frame", meta?.takeIf { it.displayWidth > 0 }?.let { "${it.displayWidth} × ${it.displayHeight}" } ?: "…")
            }
            Fact("Length", meta?.let { lengthOf(it.durationMs) } ?: "…")
            if (!isAudio) {
                Fact("Rate", meta?.let { fpsOf(it.fps) } ?: "…")
            }
            Fact("Size", if (sizeBytes > 0) formatSize(sizeBytes) else "…", accent = true)
        }
        val savedPercent = if (originalBytes > 0 && sizeBytes > 0) (100 - (sizeBytes * 100 / originalBytes)).coerceIn(0, 99) else 0
        if (savedPercent > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(SquishColors.Cyan.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "$savedPercent% smaller · was ${formatSize(originalBytes)}",
                    color = SquishColors.Cyan,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, accent: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (accent) SquishColors.Cyan else SquishColors.TextPrimary,
            maxLines = 1
        )
    }
}

/** The facts on one line, for the player's subtitle. */
private fun factsLine(meta: VideoMeta?, sizeBytes: Long, isAudio: Boolean): String = buildList {
    if (meta != null) {
        if (!isAudio && meta.displayWidth > 0) add("${meta.displayWidth} × ${meta.displayHeight}")
        add(lengthOf(meta.durationMs))
        if (!isAudio) add(fpsOf(meta.fps))
    }
    if (sizeBytes > 0) add(formatSize(sizeBytes))
}.joinToString("  ·  ")

/** "12.4 s" under a minute, "1:02" over it: the resolution anyone reads a length at. */
private fun lengthOf(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds < 60) "%.1f s".format(seconds) else "%d:%02d".format((seconds / 60).toInt(), (seconds % 60).toInt())
}

/** "30 fps", or "29.97 fps" where the rate is not whole. */
private fun fpsOf(fps: Float): String = if (fps % 1f > 0.05f) "%.2f fps".format(fps) else "%.0f fps".format(fps)

/**
 * Where the file went. Stated, because it already went there.
 *
 * The export is published to the gallery the moment it finishes, so "how do I get
 * this onto my phone" has always had an answer - the app just never gave it.
 */
@Composable
private fun SavedToCard(isAudio: Boolean, fileName: String, inGallery: Boolean) {
    SquishCard(accent = SquishColors.Violet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SquishColors.Violet.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isAudio) Icons.Filled.FolderOpen else Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = SquishColors.Violet,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        // Said only when it is true. The copy can fail - a full
                        // phone, a storage error - and this card used to claim it
                        // had worked whatever happened.
                        !inGallery -> "Only saved inside Squish"
                        isAudio -> "Saved to Music › Squish"
                        else -> "Saved to your gallery"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary
                )
                Text(
                    when {
                        !inGallery -> "The copy to your ${if (isAudio) "music" else "gallery"} failed. " +
                            "Save a copy to Files below, or share it from here."
                        isAudio -> fileName
                        else -> "Movies › Squish · $fileName"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    // One line, cut short at the end. Allowed to wrap, a name one
                    // character too long broke after the dot and the one-line limit
                    // then hid the name completely.
                    maxLines = if (inGallery) 1 else 3,
                    softWrap = !inGallery,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One place to send the file, as a mark rather than a caption.
 *
 * The name is not printed. A row that reads WhatsApp / Instagram / Email / More
 * puts two companies' names in Squish's own type, on Squish's own screen, which
 * is not something to do lightly and not something anyone needs: the marks are
 * recognisable at a glance and the colours carry them. The name stays as the
 * content description, so a screen reader announces it and nobody navigating by
 * touch loses anything.
 */
@Composable
private fun ShareTarget(label: String, icon: ImageVector, tile: Brush, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f), label = "press")
    Box(
        modifier = Modifier
            .size(62.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(tile)
            // A soft light across the top edge, so the tile reads as a surface
            // rather than a flat swatch.
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = 90f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = "Share to $label",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * Email gets its own path rather than a filtered share.
 *
 * ACTION_SEND with an email package name only works if you guess the right one out
 * of a dozen. A mailto-typed send lets the system offer whichever mail app is
 * actually set up.
 */
private fun sendByEmail(
    context: Context,
    uri: Uri,
    fileName: String,
    isAudio: Boolean,
    onProblem: (String) -> Unit
) {
    val stream = ShareUtils.shareableUri(context, uri) ?: run {
        onProblem("Could not attach that file.")
        return
    }
    val mime = if (isAudio) "audio/mp4" else "video/mp4"

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, stream)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Narrows the chooser to apps that handle mail, without naming one.
        selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (!ShareUtils.share(context, uri, mime, null)) onProblem("No email app set up on this phone.")
    }
}
