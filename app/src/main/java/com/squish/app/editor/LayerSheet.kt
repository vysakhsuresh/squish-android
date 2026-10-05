package com.squish.app.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MAX_FOOTAGE_LAYER
import com.squish.app.editor.OverlayRules.canStep
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.hasValueKeyAt
import com.squish.app.timeline.readsMuted
import com.squish.app.timeline.valueAt
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * An overlay's opacity. Its own tool on the overlay's toolbar; it was one slider
 * among the Blend panel's Size, Across and Up / down. Placement is done on the
 * picture now - the box, its handles, two fingers - and on the Placement sheet,
 * both through the one way placement is written (ClipEdits.setClipTransform).
 */
@Composable
fun OpacityPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    val onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs
    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            if (clip.opacityKeys.isNotEmpty() && onClip) "Opacity at ${Timecode.format(state.playheadMs)}" else "Opacity",
            "How much of the picture underneath shows through",
            icon = Icons.Filled.Opacity,
            accent = SquishColors.Magenta
        )
        // Keyed over the clip: the slider then sets the opacity at the playhead
        // (ValueTracks), and the preview and the file fade between the keys.
        KeyframeButton(
            keyed = clip.hasValueKeyAt(ValueTrack.Opacity, state.playheadMs, state.frameMs),
            count = clip.opacityKeys.size,
            onClip = onClip,
            accent = SquishColors.Magenta,
            onToggle = { viewModel.clips.toggleValueKey(clip.id, ValueTrack.Opacity) },
            onClear = { viewModel.clips.clearValueKeys(clip.id, ValueTrack.Opacity) }
        )
        LabeledSlider("Opacity", clip.valueAt(ValueTrack.Opacity, state.playheadMs), 0f..1f, onFinished = viewModel::endGesture) {
            viewModel.layers.setOpacity(clip.id, it)
        }

        // Blend modes, on a still only. A video overlay's layers meet inside
        // Media3's compositor, which blends one way and offers no way in, so a
        // blend set on one would show here and not in the file.
        if (com.squish.app.media.StillClips.isStill(clip.uri)) {
            Text(
                "Blend",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                com.squish.app.timeline.LayerBlend.entries.forEach { blend ->
                    SelectableChip(
                        label = blend.label,
                        selected = clip.blend == blend,
                        accentColor = SquishColors.Magenta,
                        onClick = { viewModel.layers.setBlend(clip.id, blend) }
                    )
                }
            }
            if (!clip.blend.isPlain) {
                Text(
                    "A blended picture covers the whole frame - that is what a light leak or a dust overlay is for. " +
                        "Place it, or turn it, with Blend off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
        }
    }
}

/**
 * What an overlay is drawn over and under: Send back and Bring forward, each
 * past the nearest overlay on screen with it (OverlayRules.withOverlayStepped).
 * Offered only when they would change something - a button that raised a lone
 * overlay a row at a time changed nothing on the picture and left empty lanes.
 * The main track is To main, on the toolbar; it was here a second time.
 */
@Composable
fun LayerPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    val timeline = TimelineState(clips = state.videoClips)
    val back = timeline.canStep(clip.id, up = false)
    val forward = timeline.canStep(clip.id, up = true)
    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Row ${clip.layer} of ${timeline.layerCount}",
            "Higher rows are drawn over lower ones",
            icon = Icons.Filled.Layers,
            accent = SquishColors.Magenta
        )
        if (back || forward) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (back) {
                    SquishOutlinedButton(text = "Send back", modifier = Modifier.weight(1f)) {
                        viewModel.layers.stepLayer(clip.id, up = false)
                    }
                }
                if (forward) {
                    SquishOutlinedButton(text = "Bring forward", modifier = Modifier.weight(1f)) {
                        viewModel.layers.stepLayer(clip.id, up = true)
                    }
                }
            }
        }
        // Three cases, not two. A step is refused both when nothing shares the
        // screen *and* when the row it would move to is one this clip may not
        // take - footage keeps to the lowest rows, since each is a decoder - and
        // the sheet said "no other overlay is on screen" for both, with the
        // other overlay plainly on the picture at that moment.
        val sharing = state.videoClips.any {
            it.id != clip.id && it.isOverlay &&
                it.timelineStartMs < clip.timelineEndMs && it.timelineEndMs > clip.timelineStartMs
        }
        Text(
            when {
                back || forward -> "Moves it past the next overlay on screen with it, over or under."
                sharing -> "The overlay on screen with this one is on a row it cannot reach: only the " +
                    "lowest $MAX_FOOTAGE_LAYER rows take video, because each one is a decoder."
                else -> "No other overlay is on screen at the same time as this one, so there is nothing to put it " +
                    "in front of or behind."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

/**
 * A shot's or an overlay's own sound: a level, and a switch to silence it that
 * gives the level back when it is switched on again.
 *
 * A shot is also under the camera sound for the whole edit (Sound, Mic & camera);
 * when that is off, the sheet says so, rather than showing a level nobody hears.
 */
@Composable
fun ClipVolumePanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    // The level to come back to after Mute. Kept per clip, across a rotation.
    var lastHeard by rememberSaveable(clip.id) { mutableFloatStateOf(clip.volume.takeIf { it > 0f } ?: 1f) }
    // Where a drag of the slider began. Noting every level on the way through
    // left a drag down to nothing remembering the last sliver above it, and the
    // switch then brought the clip back at 5%, as if it had done nothing.
    var dragFrom by remember(clip.id) { mutableStateOf<Float?>(null) }
    val volumeNow by rememberUpdatedState(clip.volume)
    // Changes from elsewhere - Reset, undo - but not mid-drag.
    LaunchedEffect(clip.volume) { if (dragFrom == null && clip.volume > 0f) lastHeard = clip.volume }
    val accent = if (clip.isOverlay) SquishColors.Magenta else SquishColors.Violet
    // The switch, over the level: a keyed clip is muted by the switch alone,
    // and a draft that wrote Mute as a level of nothing still reads off.
    val muted = clip.readsMuted
    val onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs
    // A photo, blank or freeze carries a silent track: a switch and a level on
    // it changed nothing anyone could hear.
    if (!clip.isFootage) {
        PanelSurface(accent = accent) {
            PanelHeading(
                "No sound of its own",
                "A still picture is silent. Music and voice go under it from Sound.",
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                accent = accent
            )
        }
        return
    }
    PanelSurface(accent = accent) {
        PanelHeading(
            if (clip.isOverlay) "Overlay sound" else "Clip sound",
            "This clip's own sound, mixed with the rest",
            icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            accent = accent,
            trailing = {
                SquishToggleSwitch(
                    checked = !muted,
                    onCheckedChange = { on -> viewModel.clips.setClipMuted(clip.id, muted = !on, restoreTo = lastHeard) }
                )
            }
        )
        // Keyed over the clip: a level ducked under a line, by hand.
        KeyframeButton(
            keyed = clip.hasValueKeyAt(ValueTrack.Volume, state.playheadMs, state.frameMs),
            count = clip.volumeKeys.size,
            onClip = onClip,
            accent = accent,
            onToggle = { viewModel.clips.toggleValueKey(clip.id, ValueTrack.Volume) },
            onClear = { viewModel.clips.clearValueKeys(clip.id, ValueTrack.Volume) }
        )
        LabeledSlider(
            "Level",
            clip.valueAt(ValueTrack.Volume, state.playheadMs),
            0f..1f,
            onFinished = {
                // Where it ended, if that is a level; dragged down to nothing,
                // where it began.
                val from = dragFrom
                dragFrom = null
                when {
                    volumeNow > 0f -> lastHeard = volumeNow
                    from != null && from > 0f -> lastHeard = from
                }
                viewModel.endGesture()
            }
        ) {
            if (dragFrom == null) dragFrom = clip.volume
            viewModel.clips.setClipVolume(clip.id, it)
        }
        if (!clip.isOverlay && state.muteOriginal) {
            Text(
                "Camera sound is off for the whole edit, so this clip is silent. Turn it back on in " +
                    "Sound, Mic & camera.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Amber
            )
        } else if (!clip.isOverlay && state.originalVolume < 0.999f) {
            Text(
                "Heard at this level under the camera sound for the whole edit " +
                    "(${(state.originalVolume * 100).toInt()}%, in Sound, Mic & camera).",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        // Remove silences: the pauses cut out of a talking shot, as jump cuts.
        // Footage only: a main-track photo is a rendered MP4 whose track is all silence.
        if (clip.isMain && clip.isFootage) {
            var working by remember(clip.id) { mutableStateOf(false) }
            var note by remember(clip.id) { mutableStateOf<String?>(null) }
            SquishOutlinedButton(
                text = if (working) "Listening for pauses…" else "Remove silences",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (working) return@SquishOutlinedButton
                    working = true
                    note = null
                    viewModel.audio.removeSilences(clip.id) { removed ->
                        working = false
                        note = if (removed <= 0L) "No pauses long enough to cut - or no talking found in this shot."
                        else "Cut ${"%.1f".format(removed / 1000f)} s of pauses. Undo puts them back."
                    }
                }
            )
            Text(
                note ?: "Cuts the pauses longer than 0.7 s out of this shot, keeping a little air round every word.",
                style = MaterialTheme.typography.labelSmall,
                color = if (note != null) SquishColors.Teal else SquishColors.TextMuted
            )
        }
    }
}
