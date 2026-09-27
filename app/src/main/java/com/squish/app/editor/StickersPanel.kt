package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors

/**
 * Stickers: pick one, it pops onto the picture at the playhead, then put it where
 * it belongs.
 *
 * A sticker is an emoji drawn by the caption renderer, so it has everything a
 * title has - motions, timing on the text lane, undo, export - without a second
 * system to keep in step with the first.
 */
@Composable
fun StickersPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val placed = state.textOverlays.filter { it.sticker }.sortedBy { it.startMs }
    var category by remember { mutableStateOf(StickerSet.entries.first()) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Magenta) {
            PanelHeading(
                "Stickers",
                "Tap one to put it on at the playhead",
                icon = Icons.Filled.EmojiEmotions,
                accent = SquishColors.Magenta
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                StickerSet.entries.forEach { set ->
                    SelectableChip(
                        label = set.label,
                        selected = set == category,
                        accentColor = SquishColors.Magenta,
                        onClick = { category = set }
                    )
                }
            }
            // Six to a row, sized for a thumb.
            category.stickers.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SquishColors.Background)
                                .clickable { viewModel.addSticker(emoji) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 26.sp)
                        }
                    }
                    repeat(6 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }

        PanelSurface(accent = SquishColors.Magenta) {
            PanelHeading(
                "On the video",
                if (placed.isEmpty()) "None yet" else "${placed.size} placed",
                icon = Icons.Filled.Layers,
                accent = SquishColors.Magenta
            )
            placed.forEach { sticker ->
                PlacedSticker(
                    sticker = sticker,
                    onJump = { viewModel.scrubTo(sticker.startMs) },
                    onRemove = { viewModel.removeTextOverlay(sticker.id) },
                    onChange = { change -> viewModel.restyleCaption(sticker.id, change) }
                )
            }
        }
    }
}

/** One sticker on the video: where it is, how big, how it arrives. */
@Composable
private fun PlacedSticker(
    sticker: TextOverlayItem,
    onJump: () -> Unit,
    onRemove: () -> Unit,
    onChange: ((TextOverlayItem) -> TextOverlayItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(sticker.text, fontSize = 28.sp, modifier = Modifier.size(44.dp).padding(end = 6.dp))
            Text(
                "${Timecode.format(sticker.startMs)} → ${Timecode.format(sticker.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Cyan,
                modifier = Modifier.weight(1f).clickable(onClick = onJump)
            )
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }
        LabeledSlider("Across", sticker.xFraction * 2 - 1, -1f..1f) { v ->
            onChange { it.copy(xFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider("Up / down", sticker.yFraction * 2 - 1, -1f..1f) { v ->
            onChange { it.copy(yFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider("Size", sticker.sizeSp / 64f, 0.4f..3f) { v ->
            onChange { it.copy(sizeSp = (v * 64).toInt().coerceIn(24, 192)) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            TextMotion.entries.filter { it != TextMotion.Typewriter }.forEach { motion ->
                SelectableChip(
                    label = motion.label,
                    selected = sticker.motion == motion,
                    accentColor = SquishColors.Magenta,
                    onClick = { onChange { it.copy(motion = motion) } }
                )
            }
        }
    }
}

/** The sticker sheet, grouped the way people look for one. */
private enum class StickerSet(val label: String, val stickers: List<String>) {
    Faces("Faces", listOf("😂", "🤣", "😍", "🥰", "😎", "🤩", "😭", "😱", "🤯", "😡", "🥳", "🤔", "😴", "🤫", "😇", "🙄", "😬", "🤡")),
    Hands("Hands", listOf("👍", "👎", "👏", "🙌", "🙏", "👌", "✌️", "🤞", "🤟", "👉", "👈", "👆", "👇", "💪", "🫶", "👋", "✋", "🤝")),
    Hearts("Hearts", listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💖", "💘", "💔", "❣️", "💕", "💯", "✨", "⭐", "🌟", "💫")),
    Party("Party", listOf("🎉", "🎊", "🎂", "🎁", "🎈", "🥂", "🍾", "🏆", "🥇", "🎯", "🔥", "💥", "⚡", "🚀", "🌈", "☀️", "🌙", "❄️")),
    Things("Things", listOf("📍", "📸", "🎬", "🎵", "🎧", "📢", "💡", "💰", "🛒", "✅", "❌", "⚠️", "❓", "❗", "➡️", "⬅️", "⬆️", "⬇️")),
    Food("Food", listOf("🍕", "🍔", "🍟", "🌮", "🍜", "🍣", "🍩", "🍰", "🍫", "🍿", "☕", "🧋", "🍺", "🥭", "🍉", "🍓", "🌶️", "🥑"))
}
