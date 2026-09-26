package com.squish.app.export

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.squish.app.data.SquishRepositories
import com.squish.app.home.formatSize
import com.squish.app.ui.components.BackOrb
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.accentSweep
import com.squish.app.ui.theme.SquishColors
import java.io.File

/**
 * What happened, where it went, and what to do with it.
 *
 * Three things this screen used to get wrong. It said "Compress another video"
 * whatever job had just run, so merging four clips ended on an offer to compress.
 * It never said where the file had gone, although it had already been published to
 * the gallery - so the commonest question after an export had no answer anywhere
 * in the app. And its share row was four coloured circles with no marks on them.
 */
@Composable
fun ExportScreen(
    resultPath: String,
    /** Already in the past tense — "Stitched", "Exported". The screen adds nothing. */
    jobLabel: String,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val record = remember(resultPath) {
        SquishRepositories.history(context).records.value.firstOrNull { it.outputPath == resultPath }
    }
    val savedPercent = record?.let {
        if (it.originalSizeBytes > 0) {
            (100 - (it.outputSizeBytes * 100 / it.originalSizeBytes)).coerceIn(0, 99)
        } else {
            null
        }
    }
    val isAudio = resultPath.endsWith(".m4a", ignoreCase = true)
    var notice by remember { mutableStateOf<String?>(null) }

    // A second copy, wherever they want it. The automatic publish puts it in the
    // gallery, which is right for most people and useless for anyone who wants it
    // on an SD card or in a folder they sync.
    val saveCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (isAudio) "audio/mp4" else "video/mp4")
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        notice = runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                File(resultPath).inputStream().use { it.copyTo(out) }
            } ?: error("no stream")
            "Copy saved."
        }.getOrElse { "Could not write there — try a different folder." }
    }

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

                Box(
                    modifier = Modifier.size(78.dp).clip(CircleShape).background(accentSweep(SquishColors.Cyan)),
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

                if (record != null) {
                    SquishCard(accent = SquishColors.Cyan) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Before", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                                Text(
                                    formatSize(record.originalSizeBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SquishColors.TextMuted
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("After", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                                Text(
                                    formatSize(record.outputSizeBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SquishColors.Cyan
                                )
                            }
                        }
                        if (savedPercent != null && savedPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(SquishColors.Cyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "$savedPercent% smaller",
                                    color = SquishColors.Cyan,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                SavedToCard(isAudio = isAudio, fileName = File(resultPath).name)

                SquishOutlinedButton(
                    text = "Save a copy to Files",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { saveCopy.launch(File(resultPath).name) }
                )

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = SquishColors.Cyan)
                }

                Text(
                    "Share",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShareTarget("WhatsApp", ShareGlyphs.WhatsApp, WHATSAPP_GREEN) {
                        if (!ShareUtils.share(context, resultPath, "com.whatsapp")) {
                            notice = "Nothing on this phone can share that."
                        }
                    }
                    ShareTarget("Instagram", ShareGlyphs.Instagram, INSTAGRAM_PINK) {
                        if (!ShareUtils.share(context, resultPath, "com.instagram.android")) {
                            notice = "Nothing on this phone can share that."
                        }
                    }
                    ShareTarget("Mail", Icons.Filled.MailOutline, SquishColors.Blue) {
                        sendByEmail(context, resultPath, isAudio) { notice = it }
                    }
                    ShareTarget("More apps", Icons.Filled.MoreHoriz, SquishColors.Violet) {
                        if (!ShareUtils.share(context, resultPath, null)) {
                            notice = "Nothing on this phone can share that."
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                SquishOutlinedButton(
                    text = "Back to Squish",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone
                )
                Spacer(modifier = Modifier.height(96.dp))
            }

            BackOrb(
                accent = SquishColors.Cyan,
                onClick = onDone,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)
            )
        }
    }
}

/**
 * Where the file went. Stated, because it already went there.
 *
 * The export is published to the gallery the moment it finishes, so "how do I get
 * this onto my phone" has always had an answer - the app just never gave it.
 */
@Composable
private fun SavedToCard(isAudio: Boolean, fileName: String) {
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
                    if (isAudio) "Saved to Music › Squish" else "Saved to your gallery",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquishColors.TextPrimary
                )
                Text(
                    if (isAudio) fileName else "Movies › Squish · $fileName",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted,
                    // One line, cut short at the end. Allowed to wrap, a name one
                    // character too long broke after the dot and the one-line limit
                    // then hid the name completely.
                    maxLines = 1,
                    softWrap = false,
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
private fun ShareTarget(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = "Share to $label",
            tint = Color.White,
            modifier = Modifier.size(27.dp)
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
    context: android.content.Context,
    path: String,
    isAudio: Boolean,
    onProblem: (String) -> Unit
) {
    val uri = runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path)
        )
    }.getOrNull() ?: run {
        onProblem("Could not attach that file.")
        return
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (isAudio) "audio/mp4" else "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, File(path).name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Narrows the chooser to apps that handle mail, without naming one.
        selector = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (!ShareUtils.share(context, path, null)) onProblem("No email app set up on this phone.")
    }
}

private val WHATSAPP_GREEN = Color(0xFF25D366)
private val INSTAGRAM_PINK = Color(0xFFE1306C)
