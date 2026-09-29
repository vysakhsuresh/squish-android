package com.squish.app.editor

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
fun StickersPanel(viewModel: EditorViewModel) {
    var category by rememberSaveable { mutableStateOf(StickerSet.entries.first()) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Stickers",
                "Tap one to put it on at the playhead",
                icon = Icons.Filled.EmojiEmotions,
                accent = SquishColors.Amber
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                StickerSet.entries.forEach { set ->
                    SelectableChip(
                        label = set.label,
                        selected = set == category,
                        accentColor = SquishColors.Amber,
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
                                .clickable { viewModel.text.addSticker(emoji) }
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
    }
}

/**
 * Where one sticker is and how big: the sticker's Placement. The sliders are
 * the precise way; each drag is one undo step.
 */
@Composable
fun StickerPlacementPanel(sticker: TextOverlayItem, viewModel: EditorViewModel) {
    val onDrag = { change: (TextOverlayItem) -> TextOverlayItem ->
        viewModel.text.restyleCaption(sticker.id, change, dragging = true)
    }
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Placement",
            "${Timecode.format(sticker.startMs)} → ${Timecode.format(sticker.endMs)}",
            icon = Icons.Filled.EmojiEmotions,
            accent = SquishColors.Amber
        )
        LabeledSlider("Across", sticker.xFraction * 2 - 1, -1f..1f, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(xFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider("Up / down", sticker.yFraction * 2 - 1, -1f..1f, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(yFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider("Size", sticker.sizeSp / 64f, 0.4f..3f, readout = Readout.times, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(sizeSp = (v * 64).toInt().coerceIn(24, 192)) }
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
