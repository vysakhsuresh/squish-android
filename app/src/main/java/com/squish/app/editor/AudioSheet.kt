package com.squish.app.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.hasValueKeyAt
import com.squish.app.timeline.valueAt
import com.squish.app.ui.components.AccentBadge
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishPrimaryButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.components.WaveformCanvas
import com.squish.app.ui.theme.SquishColors

/**
 * The Music chip of Sound, with adding first: a file from the phone, or a song
 * from the library, lands at the playhead. Sound, then Add, is two taps; the
 * file picker used to be the fifth card down a panel of nine.
 *
 * Each track is an ordinary timeline clip, so everything else about one -
 * dragging, trimming at the edges, cutting, its level - is done on the strip
 * and on its own toolbar once it is selected.
 */
@Composable
fun SoundMusicPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickAudio: () -> Unit,
    /**
     * A row tapped: the sound selected and its own tools up, the way a tap on
     * the strip does. It used to only highlight the row, with the tools it
     * spoke of under this sheet where they could not be seen.
     */
    onSelectSound: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SquishPrimaryButton(
            text = "Add from your files",
            modifier = Modifier.fillMaxWidth(),
            onClick = onPickAudio
        )

        MusicPanel(viewModel, editorPlaying = state.isPlaying)

        if (state.audioClips.isNotEmpty()) {
            PanelCard {
                PanelHeading(
                    "On the timeline",
                    "Tap one for its volume, fades, sync and more",
                    icon = Icons.Filled.MusicNote,
                    accent = SquishColors.Cyan,
                    trailing = {
                        Text(
                            "${state.audioClips.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = SquishColors.Cyan
                        )
                    }
                )
                state.audioClips.sortedBy { it.timelineStartMs }.forEach { clip ->
                    TrackRow(
                        clip = clip,
                        selected = clip.id == state.selectedClipId,
                        onSelect = { onSelectSound(clip.id) },
                        onRemove = { viewModel.audio.removeAudioClip(clip.id) },
                        // Beside the song, where someone with a 30 s song under a
                        // two-minute video looks for it; it is on the sound's
                        // Sync sheet too, but that was the eighth control down.
                        onLoop = if (state.pictureEndMs - clip.timelineEndMs >= MIN_CLIP_MS) ({ viewModel.audio.loopToEnd(clip.id) }) else null
                    )
                }
            }
        }
    }
}

/**
 * The Mic & camera chip of Sound: a voiceover taken over the picture, and the
 * camera's own sound. The voice changer that was here is each clip's own now,
 * on its toolbar as Voice - which is why the chip is no longer called
 * "Voice & FX": someone who used Robot last week went there and, finding a
 * mic button and a switch, took the effect for gone.
 */
@Composable
fun SoundVoicePanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RecordPanel(state, viewModel)
        CameraSoundPanel(state, viewModel)
    }
}

/**
 * Record: a take over the timeline, the way CapCut's Audio > Record works. One
 * big button; a three-second count-in; the picture plays silently while the
 * mic listens; Stop, or the end of the edit, lands the take at the moment it
 * started. Always a new take at the playhead; with a take selected, a second
 * link records that one again in its place - a deliberate act, since the
 * take just made is the selected one.
 *
 * The mic is asked for on the first press and never before: a permission
 * dialog on opening the editor would be asking for something nobody had
 * reached for yet. Granted, the count-in starts at once, rather than leaving
 * the person to press Record a second time for nothing.
 */
@Composable
fun RecordPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val mic = rememberPermission(android.Manifest.permission.RECORD_AUDIO, onGranted = { viewModel.audio.startVoiceover() })
    val rec = state.recording
    val replacing = state.audioClips.firstOrNull { it.id == state.selectedClipId && it.isVoiceover }
    val hasPicture = state.pictureEndMs > 0L
    PanelCard {
        PanelHeading(
            "Record",
            when (rec.phase) {
                RecordingState.Phase.Idle -> "A voiceover over the picture, from the playhead"
                RecordingState.Phase.Countdown -> "Get ready…"
                RecordingState.Phase.Recording -> if (rec.pictureStalled) "Listening" else "Listening - the picture plays silently"
                RecordingState.Phase.Saving -> "Saving the take…"
            },
            icon = Icons.Filled.Mic,
            accent = SquishColors.Cyan
        )
        when (rec.phase) {
            RecordingState.Phase.Idle -> {
                if (!mic.granted && mic.deniedForGood) {
                    Text(
                        "Squish was refused the microphone. Allow it in Settings to record a voiceover.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.Yellow
                    )
                    SquishOutlinedButton(text = "Open settings", modifier = Modifier.fillMaxWidth(), onClick = mic.openSettings)
                } else {
                    if (rec.failed) {
                        Text(
                            "The microphone couldn't be opened, or nothing was heard. Check nothing else is using it and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.Yellow
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Off with nothing on the picture: there is nothing to
                        // record over, and the button used to run the count-in
                        // and land a take by itself.
                        RecordButton(recording = false, enabled = hasPicture) {
                            if (mic.granted) viewModel.audio.startVoiceover() else mic.ask()
                        }
                        Text(
                            if (!hasPicture) "Add a clip first: the take is recorded over the picture."
                            else "Tap to start after a count of three. Stop, or the end of the edit, ends the take.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.TextMuted
                        )
                    }
                    if (replacing != null && hasPicture) {
                        SquishOutlinedButton(
                            text = "Record this take again",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { if (mic.granted) viewModel.audio.startVoiceover(replaceSelected = true) else mic.ask() }
                        )
                        Text(
                            "The new take lands where \"${replacing.label}\" starts, and that one goes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.TextMuted
                        )
                    }
                }
            }
            RecordingState.Phase.Countdown -> Box(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${rec.countdown}",
                    style = MaterialTheme.typography.displayMedium,
                    color = SquishColors.Cyan
                )
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = SquishColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { viewModel.audio.stopVoiceover() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            RecordingState.Phase.Recording -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                RecordButton(recording = true) { viewModel.audio.stopVoiceover() }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The take's own clock, from the mic: the playhead's would
                    // sit at zero while the picture has not started.
                    Text(
                        Timecode.format(rec.recordedMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = SquishColors.TextPrimary
                    )
                    LevelMeter(rec.level)
                    if (rec.pictureStalled) {
                        Text(
                            "The picture hasn't started, so the take runs without it. It still lands at " +
                                "${Timecode.format(rec.startMs)} when you press Stop.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Yellow
                        )
                    }
                }
            }
            RecordingState.Phase.Saving -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = SquishColors.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Closing the file…", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            }
        }
    }
}

/** The one big button: red to start, a stop square while a take runs; dim when there is nothing to record over. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) SquishColors.Danger else SquishColors.Danger.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClickLabel = if (recording) "Stop recording" else "Record", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
            tint = SquishColors.Background,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** The mic's level: a bar that says the phone is hearing something. */
@Composable
private fun LevelMeter(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SquishColors.Background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(if (level > 0.9f) SquishColors.Danger else SquishColors.Cyan)
        )
    }
}

/**
 * A runtime permission as the sheet needs it: whether it is held, how to ask,
 * and - once the phone has stopped asking on Squish's behalf - the way to
 * Settings. A button that asked and got nothing back, forever, was what a
 * permission refused twice used to leave.
 */
@Stable
class PermissionState(
    val granted: Boolean,
    val deniedForGood: Boolean,
    val ask: () -> Unit,
    val openSettings: () -> Unit
)

/**
 * [onGranted] runs when the dialog answers yes: the thing the press was for
 * goes ahead, rather than the person pressing again to find out.
 */
@Composable
fun rememberPermission(permission: String, onGranted: () -> Unit = {}): PermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    fun held() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(held()) }
    var deniedForGood by remember { mutableStateOf(false) }
    val latestOnGranted by rememberUpdatedState(onGranted)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // Refused, and the phone will not show the dialog again: only Settings can change it now.
        if (!ok) deniedForGood = activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false
        else latestOnGranted()
    }
    // Back from Settings, or from anywhere: what is held may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = held()
        if (granted) deniedForGood = false
    }
    return PermissionState(
        granted = granted,
        deniedForGood = deniedForGood,
        ask = { ask.launch(permission) },
        openSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The sound recorded with the video: on or off, and how loud. One setting for
 * every shot on the main track, which the heading says - and which is why it is
 * here on Sound and not on a shot's toolbar, where it silenced every shot while
 * looking like that shot's own level.
 *
 * No switch for a clip with no sound: it toggled a setting that then silenced
 * clips added later, for a track that was never there.
 */
@Composable
fun CameraSoundPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelCard {
        PanelHeading(
            "Camera sound",
            if (state.sourceHasAudio) "The sound recorded with the video, on every shot"
            else "This clip has no audio track",
            icon = Icons.Filled.Mic,
            accent = SquishColors.Cyan,
            trailing = if (!state.sourceHasAudio) null else ({
                SquishToggleSwitch(
                    checked = !state.muteOriginal,
                    onCheckedChange = { viewModel.audio.setMuteOriginal(!it) }
                )
            })
        )
        if (!state.muteOriginal && state.sourceHasAudio) {
            LabeledSlider(
                "Level", state.originalVolume, 0f..1f,
                onFinished = viewModel::endGesture,
                onChange = viewModel.audio::setOriginalVolume
            )
        }
    }
}

/**
 * The Sync chip of Sound: the beat grid, the markers it fills in, and lining the
 * selected sound - or the first one - up with the picture.
 */
@Composable
fun SoundSyncPanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BeatPanel(state, viewModel)
        state.targetAudioClip?.let { AlignPanel(state, it, viewModel) }
        MarkersPanel(state, viewModel)
    }
}

/**
 * One added sound's level, over its waveform: the sound's Volume. Up to four
 * times its own level, as CapCut allows, for a quiet phone recording under
 * loud music; past 100% the preview boosts it as the file will (AudioRules).
 */
@Composable
fun SoundVolumePanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AccentBadge(icon = Icons.Filled.GraphicEq, accent = SquishColors.Cyan)
            Text(
                clip.label,
                style = MaterialTheme.typography.titleSmall,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        WaveformCanvas(
            waveform = state.waveformFor(clip),
            color = SquishColors.Teal,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SquishColors.Background)
                .padding(vertical = 4.dp)
        )

        // Keyed over the sound: a bed ducked under a line, by hand. The slider
        // then sets the level at the playhead (ValueTracks).
        KeyframeButton(
            keyed = clip.hasValueKeyAt(ValueTrack.Volume, state.playheadMs, state.frameMs),
            count = clip.volumeKeys.size,
            onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs,
            accent = SquishColors.Cyan,
            onToggle = { viewModel.clips.toggleValueKey(clip.id, ValueTrack.Volume) },
            onClear = { viewModel.clips.clearValueKeys(clip.id, ValueTrack.Volume) }
        )
        val level = clip.valueAt(ValueTrack.Volume, state.playheadMs)
        LabeledSlider(
            "Level", level, 0f..AudioRules.MAX_SOUND_GAIN,
            readout = { "%.0f%%".format(it * 100) },
            onFinished = viewModel::endGesture
        ) {
            viewModel.audio.setAudioClipVolume(clip.id, it)
        }
        if (level > 1f) {
            Text(
                "Above 100% a loud passage may distort. The file is written at this level.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }

        // Auto-duck: the song down under every line spoken over it, as keys the
        // row above then shows and the slider can still change.
        var ducking by remember(clip.id) { mutableStateOf(false) }
        var duckNote by remember(clip.id) { mutableStateOf<String?>(null) }
        SquishOutlinedButton(
            text = if (ducking) "Listening for speech…" else "Duck under speech",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (ducking) return@SquishOutlinedButton
                ducking = true
                duckNote = null
                viewModel.audio.duckUnderSpeech(clip.id) { dips ->
                    ducking = false
                    duckNote = if (dips == 0) "No talking found under this sound - voiceovers and the shots' own sound are listened to."
                    else "Turned down under $dips ${if (dips == 1) "stretch" else "stretches"} of speech. Undo takes it back."
                }
            }
        )
        Text(
            duckNote ?: "Lowers this sound wherever someone talks over it, and brings it back up between.",
            style = MaterialTheme.typography.labelSmall,
            color = if (duckNote != null) SquishColors.Teal else SquishColors.TextMuted
        )
    }
}

/**
 * A sound's Fade: in and out, in seconds, drawn on the clip as wedges. The two
 * share the clip, so on a short one setting either pulls the other in.
 */
@Composable
fun FadePanel(clip: Clip, viewModel: EditorViewModel) {
    val longest = minOf(AudioRules.MAX_FADE_MS, clip.durationMs).coerceAtLeast(0L) / 1000f
    PanelCard {
        PanelHeading(
            "Fade",
            "Ease the sound in at the start and out at the end",
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            accent = SquishColors.Cyan
        )
        LabeledSlider(
            "Fade in", clip.fadeInMs / 1000f, 0f..longest,
            readout = ::seconds,
            onFinished = viewModel::endGesture
        ) { viewModel.audio.setFadeIn(clip.id, (it * 1000f).toLong()) }
        LabeledSlider(
            "Fade out", clip.fadeOutMs / 1000f, 0f..longest,
            readout = ::seconds,
            onFinished = viewModel::endGesture
        ) { viewModel.audio.setFadeOut(clip.id, (it * 1000f).toLong()) }
    }
}

private fun seconds(v: Float): String = "%.1f s".format(v)

/**
 * A clip's Voice: the effect on its own sound - a shot's, an overlay's, an
 * added sound's - heard live and written to the file. It was one setting for
 * the whole edit, so the song could not be left alone while the take was made
 * a robot; "Apply to all" below is the one tap that setting was, for every
 * shot (or every sound) at once.
 */
@Composable
fun VoicePanel(clip: Clip, viewModel: EditorViewModel) {
    val isSound = clip.kind == com.squish.app.timeline.ClipKind.Audio
    val accent = when {
        isSound -> SquishColors.Cyan
        clip.isOverlay -> SquishColors.Magenta
        else -> SquishColors.Violet
    }
    PanelSurface(accent = accent) {
        PanelHeading(
            "Voice",
            "Change how this clip's sound sounds - the others are left alone",
            icon = Icons.Filled.RecordVoiceOver,
            accent = accent
        )
        VoiceEffect.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { effect ->
                    val on = clip.voice == effect
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) accent.copy(alpha = 0.14f) else SquishColors.Background)
                            .border(
                                if (on) 1.5.dp else 1.dp,
                                if (on) accent else SquishColors.Border,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { viewModel.audio.setClipVoice(clip.id, effect) }
                            .padding(vertical = 10.dp)
                    ) {
                        GlyphTile(effect.glyph, size = 36.dp)
                        Text(
                            effect.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) SquishColors.TextPrimary else SquishColors.TextSecondary
                        )
                    }
                }
                repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
        SquishOutlinedButton(
            text = if (isSound) "Apply to all sounds" else "Apply to all shots",
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.audio.setVoiceForAll(clip.id, clip.voice) }
        )
    }
}

/** Lining one sound up with the picture, by ear or by Squish listening to both: the sound's Sync. */
@Composable
fun AlignPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    PanelCard {
        PanelHeading(
            "Align ${clip.label}",
            "Nudge it into sync, or let Squish find the match",
            icon = Icons.Filled.Sync,
            accent = SquishColors.Cyan
        )
        SyncStatusLine(state)
        // Against the picture, not against the timeline: the head shot's own
        // trim and position are taken off. Without that a plain trim of the
        // shot read as a sync offset, and a synced sound read as off.
        val offsetMs = clip.sourceInMs - clip.timelineStartMs - state.headPictureDeltaMs
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SquishColors.Background)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                Timecode.formatOffset(offsetMs, state.fps),
                style = MaterialTheme.typography.headlineSmall,
                color = if (offsetMs == 0L) SquishColors.TextMuted else SquishColors.Teal,
                textAlign = TextAlign.Center
            )
        }
        Text(
            "How far into the sound's file the picture's first frame falls. Zero means the two start together.",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
        // A sound playing at its own speed cannot follow a shot that does not:
        // it can meet it at one moment and drifts from there. Said, rather
        // than letting a zero above read as "in sync all the way through".
        if (state.headVideoClip?.speedRamp?.isIdentity == false) {
            Text(
                "The first shot is retimed, so a sound at normal speed can only match it at one " +
                    "moment. Squish lines them up at the shot's first frame, and reads the offset there.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )
        }
        // A frame or ten milliseconds either way, said as such: "-1f" was the
        // one label on the sheet that needed decoding. Two rows of two: four
        // across, "−1 frame" wrapped at the larger font sizes while "−10 ms"
        // did not, and the row was two tall buttons and two short.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            NudgeButton("−1 frame", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffsetFrames(clip.id, -1) }
            NudgeButton("+1 frame", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffsetFrames(clip.id, 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            NudgeButton("−10 ms", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffset(clip.id, -10) }
            NudgeButton("+10 ms", Modifier.weight(1f)) { viewModel.audio.nudgeAudioOffset(clip.id, 10) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = if (state.syncStatus == SyncStatus.Analyzing) "Listening…" else "Auto-sync",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.audio.runAutoSync(clip.id) }
            )
            SquishOutlinedButton(
                text = "Start at 0:00",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.audio.resetAudioAlignment(clip.id) }
            )
        }
        // Where the sound starts, put on a moment picked with the picture: the
        // playhead. Dragging on the strip does it by eye; this does it exactly.
        SquishOutlinedButton(
            text = "Start at the playhead",
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.audio.placeAudioAtPlayhead(clip.id) }
        )
        // A song shorter than the picture, repeated to its end - the way a
        // song dragged out past its length loops in CapCut. Only offered
        // while there is room for a repeat worth hearing.
        if (state.pictureEndMs - clip.timelineEndMs >= MIN_CLIP_MS) {
            SquishOutlinedButton(
                text = "Loop to the end of the picture",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.audio.loopToEnd(clip.id) }
            )
        }
    }
}

/** One added sound. Selecting it hands over to its own tools; [onLoop] repeats it to the end of the picture, when there is room. */
@Composable
private fun TrackRow(clip: Clip, selected: Boolean, onSelect: () -> Unit, onRemove: () -> Unit, onLoop: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SquishColors.SurfaceElevated else SquishColors.Background)
            .border(
                width = 1.dp,
                color = if (selected) SquishColors.Teal else SquishColors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (clip.isVoiceover) {
            Icon(Icons.Filled.Mic, contentDescription = "Voiceover", tint = SquishColors.Cyan, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                clip.label,
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Timecode.format(clip.durationMs)} at ${Timecode.format(clip.timelineStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }
        if (onLoop != null) {
            Text(
                "Loop to end",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Cyan,
                modifier = Modifier.clickable(onClick = onLoop)
            )
        }
        Text(
            "Remove",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.Pink,
            modifier = Modifier.clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) =
    PanelSurface(accent = SquishColors.Cyan, content = content)

@Composable
private fun SyncStatusLine(state: EditorUiState) {
    val (message, color) = when (state.syncStatus) {
        SyncStatus.Idle -> "Drag it on the strip, or tap auto-sync" to SquishColors.TextMuted
        SyncStatus.Analyzing -> "Matching waveforms…" to SquishColors.TextSecondary
        SyncStatus.Matched -> "Matched · ${(state.syncConfidence * 100).toInt()}% confidence" to SquishColors.Teal
        // The search covers the first 45 s of both and lags of up to 8 s;
        // said, so a recorder started earlier than that is not read as a bad take.
        SyncStatus.NoMatch -> "No clear match within 8 s — nudge it close first, or align it by hand" to SquishColors.Yellow
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.syncStatus == SyncStatus.Analyzing) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = SquishColors.Teal,
                strokeWidth = 2.dp
            )
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SquishColors.Background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = SquishColors.TextPrimary)
    }
}
