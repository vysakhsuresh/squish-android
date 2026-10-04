package com.squish.app.editor

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squish.app.media.keepReadAccess
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Stickers: pick one, it pops onto the picture at the playhead, then put it where
 * it belongs.
 *
 * A sticker is an emoji drawn by the caption renderer, so it has everything a
 * title has - motions, timing on the text lane, undo, export - without a second
 * system to keep in step with the first. A picture sticker - a PNG, a logo, a
 * cut-out - is a photo on an overlay row, which already keeps its transparency
 * through the preview and the file.
 */
@Composable
fun StickersPanel(viewModel: EditorViewModel) {
    val context = LocalContext.current
    var category by rememberSaveable { mutableStateOf(StickerSet.entries.first().name) }
    var query by rememberSaveable { mutableStateOf("") }
    var recents by remember { mutableStateOf(StickerRecents.list(context)) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_STICKER_PICK)) { uris ->
        uris.forEach { context.keepReadAccess(it) }
        viewModel.layers.addOverlayClips(uris)
    }
    // The keyboard goes once a sticker is picked from a search: left up, it kept
    // the picture shrunk to a corner just when the sticker was to be placed.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val add = { emoji: String ->
        focus.clearFocus(force = true)
        keyboard?.hide()
        viewModel.text.addSticker(emoji)
        StickerRecents.remember(context, emoji)
        recents = StickerRecents.list(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // First, because a shape is what someone marking up a shot is after and
        // an emoji is what someone decorating one is after - and the first is
        // the harder thing to find elsewhere.
        ShapesCard(viewModel)

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Stickers",
                "Tap one to put it on at the playhead",
                icon = Icons.Filled.EmojiEmotions,
                accent = SquishColors.Amber
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search — heart, fire, thumbs…", color = SquishColors.TextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SquishColors.Amber,
                    unfocusedBorderColor = SquishColors.Border,
                    focusedTextColor = SquishColors.TextPrimary,
                    unfocusedTextColor = SquishColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            val searching = query.isNotBlank()
            if (!searching) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    if (recents.isNotEmpty()) {
                        SelectableChip(label = "Recent", selected = category == RECENT, accentColor = SquishColors.Amber) { category = RECENT }
                    }
                    StickerSet.entries.forEach { set ->
                        SelectableChip(
                            label = set.label,
                            selected = set.name == category,
                            accentColor = SquishColors.Amber,
                            onClick = { category = set.name }
                        )
                    }
                }
            }
            val shown: List<Sticker> = when {
                searching -> {
                    val q = query.trim().lowercase()
                    // Every word typed, anywhere in the name, and each sticker once: "heart
                    // red" finds the red heart, and the snowflake in two sets showed twice.
                    val words = q.split(' ').filter { it.isNotBlank() }
                    StickerSet.entries.flatMap { it.stickers }.distinctBy { it.emoji }.filter { s -> words.all { it in s.name } }
                }
                category == RECENT -> recents.mapNotNull { emoji -> StickerSet.entries.flatMap { it.stickers }.firstOrNull { it.emoji == emoji } }
                else -> StickerSet.entries.firstOrNull { it.name == category }?.stickers ?: StickerSet.entries.first().stickers
            }
            if (shown.isEmpty()) {
                Text("Nothing called that here.", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
            }
            // Six to a row, sized for a thumb.
            shown.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { sticker ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SquishColors.Background)
                                .clickable(onClickLabel = sticker.name) { add(sticker.emoji) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(sticker.emoji, fontSize = 26.sp)
                        }
                    }
                    repeat(6 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }

        PanelSurface(accent = SquishColors.Amber) {
            PanelHeading(
                "Your own",
                "A PNG with transparency, a logo, a cut-out: it goes on an overlay row",
                accent = SquishColors.Amber
            )
            SquishOutlinedButton(text = "Import an image", modifier = Modifier.fillMaxWidth()) {
                pickImages.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build()
                )
            }
        }
    }
}

/**
 * Where one sticker is, how big and which way it faces: the sticker's
 * Placement. The box on the picture is the quick way; the sliders are the
 * precise one, each drag one undo step.
 */
@Composable
fun StickerPlacementPanel(sticker: TextOverlayItem, viewModel: EditorViewModel) {
    val onDrag = { change: (TextOverlayItem) -> TextOverlayItem ->
        viewModel.text.restyleCaption(sticker.id, change, dragging = true)
    }
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Placement",
            "${Timecode.format(sticker.startMs)} → ${Timecode.format(sticker.endMs)} · or drag it on the picture",
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
        LabeledSlider("Turn", sticker.rotationDegrees, -180f..180f, readout = Readout.degrees, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(rotationDegrees = v) }
        }
        OptionToggle(label = if (sticker.flipped) "Facing the other way" else "Flip to face the other way", active = sticker.flipped) {
            viewModel.text.flip(sticker.id)
        }
    }
}

/** One sticker and what it is called, so it can be searched for. */
class Sticker(val emoji: String, val name: String)

private fun s(emoji: String, name: String) = Sticker(emoji, name)

/** The sticker sheet, grouped the way people look for one. */
private enum class StickerSet(val label: String, val stickers: List<Sticker>) {
    Faces("Faces", listOf(
        s("😂", "laughing crying"), s("🤣", "rolling laughing"), s("😍", "heart eyes love"), s("🥰", "smiling hearts love"),
        s("😎", "cool sunglasses"), s("🤩", "star struck wow"), s("😭", "crying sad"), s("😱", "scream shock"),
        s("🤯", "mind blown"), s("😡", "angry"), s("🥳", "party face"), s("🤔", "thinking hmm"),
        s("😴", "sleeping tired"), s("🤫", "shush quiet secret"), s("😇", "angel halo"), s("🙄", "eye roll"),
        s("😬", "grimace awkward"), s("🤡", "clown")
    )),
    Hands("Hands", listOf(
        s("👍", "thumbs up like"), s("👎", "thumbs down dislike"), s("👏", "clap applause"), s("🙌", "raised hands hooray"),
        s("🙏", "pray please thanks"), s("👌", "ok perfect"), s("✌️", "peace victory"), s("🤞", "fingers crossed luck"),
        s("🤟", "love you rock"), s("👉", "point right"), s("👈", "point left"), s("👆", "point up"),
        s("👇", "point down"), s("💪", "muscle strong flex"), s("🫶", "heart hands"), s("👋", "wave hello bye"),
        s("✋", "stop hand"), s("🤝", "handshake deal")
    )),
    Hearts("Hearts", listOf(
        s("❤️", "red heart love"), s("🧡", "orange heart"), s("💛", "yellow heart"), s("💚", "green heart"),
        s("💙", "blue heart"), s("💜", "purple heart"), s("🖤", "black heart"), s("🤍", "white heart"),
        s("💖", "sparkling heart"), s("💘", "heart arrow cupid"), s("💔", "broken heart"), s("❣️", "heart exclamation"),
        s("💕", "two hearts"), s("💯", "hundred percent"), s("✨", "sparkles"), s("⭐", "star"),
        s("🌟", "glowing star"), s("💫", "dizzy shooting star")
    )),
    Party("Party", listOf(
        s("🎉", "party popper celebrate"), s("🎊", "confetti"), s("🎂", "birthday cake"), s("🎁", "gift present"),
        s("🎈", "balloon"), s("🥂", "cheers clink glasses"), s("🍾", "champagne bottle"), s("🏆", "trophy winner"),
        s("🥇", "gold medal first"), s("🎯", "target bullseye"), s("🔥", "fire flame lit hot trending"), s("💥", "boom explosion bang fire"),
        s("⚡", "lightning bolt"), s("🚀", "rocket launch"), s("🌈", "rainbow"), s("☀️", "sun sunny"),
        s("🌙", "moon night"), s("❄️", "snowflake cold"), s("🎆", "fireworks firework diwali new year"), s("🎇", "sparkler fireworks diwali"), s("🪔", "diya lamp diwali festival")
    )),
    Things("Things", listOf(
        s("📍", "pin location"), s("📸", "camera photo"), s("🎬", "clapper action movie"), s("🎵", "music note"),
        s("🎧", "headphones"), s("📢", "megaphone announce"), s("💡", "idea lightbulb"), s("💰", "money bag"),
        s("🛒", "cart shopping"), s("✅", "check tick yes"), s("❌", "cross no wrong"), s("⚠️", "warning"),
        s("❓", "question"), s("❗", "exclamation"), s("➡️", "arrow right"), s("⬅️", "arrow left"),
        s("⬆️", "arrow up"), s("⬇️", "arrow down")
    )),
    Food("Food", listOf(
        s("🍕", "pizza"), s("🍔", "burger"), s("🍟", "fries chips"), s("🌮", "taco"),
        s("🍜", "noodles ramen"), s("🍣", "sushi"), s("🍩", "donut doughnut"), s("🍰", "cake slice"),
        s("🍫", "chocolate"), s("🍿", "popcorn movie"), s("☕", "coffee"), s("🧋", "bubble tea boba"),
        s("🍺", "beer"), s("🥭", "mango"), s("🍉", "watermelon"), s("🍓", "strawberry"),
        s("🌶️", "chilli pepper hot spicy fire"), s("🥑", "avocado")
    )),
    Travel("Travel", listOf(
        s("✈️", "plane flight travel"), s("🏖️", "beach holiday"), s("🏝️", "island"), s("🏔️", "mountain"),
        s("🗺️", "map trip"), s("🧳", "luggage suitcase"), s("🚗", "car road trip"), s("🚆", "train"),
        s("🛵", "scooter bike"), s("⛺", "tent camping"), s("🌅", "sunrise sunset"), s("🌃", "city night"),
        s("🗽", "statue liberty new york"), s("🕌", "mosque"), s("🛕", "temple india"), s("🏰", "castle"),
        s("🎡", "ferris wheel fair"), s("📷", "camera")
    )),
    Animals("Animals", listOf(
        s("🐶", "dog puppy"), s("🐱", "cat kitten"), s("🐼", "panda"), s("🦁", "lion"),
        s("🐯", "tiger"), s("🐵", "monkey"), s("🙈", "see no evil monkey"), s("🦊", "fox"),
        s("🐰", "rabbit bunny"), s("🐻", "bear"), s("🐨", "koala"), s("🐸", "frog"),
        s("🦄", "unicorn"), s("🐝", "bee"), s("🦋", "butterfly"), s("🐬", "dolphin"),
        s("🦜", "parrot bird"), s("🐘", "elephant")
    )),
    Nature("Nature", listOf(
        s("🌸", "cherry blossom flower"), s("🌹", "rose"), s("🌻", "sunflower"), s("🌷", "tulip"),
        s("🍀", "four leaf clover luck"), s("🌴", "palm tree"), s("🌵", "cactus"), s("🍁", "maple leaf autumn"),
        s("🌊", "wave ocean sea"), s("🌧️", "rain"), s("⛈️", "storm thunder"), s("🌪️", "tornado"),
        s("☁️", "cloud"), s("🌍", "earth world globe"), s("🌋", "volcano"), s("❄️", "snow"),
        s("🔆", "bright"), s("🌺", "hibiscus")
    )),
    Sports("Sports", listOf(
        s("⚽", "football soccer"), s("🏏", "cricket"), s("🏀", "basketball"), s("🏈", "american football"),
        s("🎾", "tennis"), s("🏸", "badminton"), s("🏐", "volleyball"), s("🏓", "table tennis ping pong"),
        s("🥊", "boxing glove"), s("🏋️", "gym weights lifting"), s("🚴", "cycling bike"), s("🏃", "running run"),
        s("🧘", "yoga"), s("🏊", "swimming"), s("⛳", "golf"), s("🏁", "chequered flag finish race"),
        s("🎮", "game controller gaming"), s("🎲", "dice game")
    )),
    Signs("Signs", listOf(
        s("🆕", "new"), s("🆓", "free"), s("🔝", "top"), s("🆗", "ok"),
        s("🔞", "eighteen"), s("💬", "speech bubble chat"), s("💭", "thought bubble"), s("🗯️", "anger bubble shout"),
        s("🔔", "bell notification subscribe"), s("📌", "pushpin"), s("🔗", "link"), s("⏰", "alarm clock time"),
        s("⏳", "hourglass wait"), s("🔴", "red circle live record"), s("🟢", "green circle"), s("▶️", "play"),
        s("⏸️", "pause"), s("🔁", "repeat loop")
    )),
    Flags("Flags", listOf(
        s("🇮🇳", "india flag"), s("🇦🇪", "uae flag emirates"), s("🇺🇸", "usa america flag"), s("🇬🇧", "uk britain flag"),
        s("🇸🇦", "saudi flag"), s("🇶🇦", "qatar flag"), s("🇴🇲", "oman flag"), s("🇰🇼", "kuwait flag"),
        s("🇧🇭", "bahrain flag"), s("🇵🇰", "pakistan flag"), s("🇧🇩", "bangladesh flag"), s("🇱🇰", "sri lanka flag"),
        s("🇳🇵", "nepal flag"), s("🇵🇭", "philippines flag"), s("🇨🇦", "canada flag"), s("🇦🇺", "australia flag"),
        s("🇫🇷", "france flag"), s("🇩🇪", "germany flag")
    ))
}

/** The stickers used last, newest first, kept across edits. */
private object StickerRecents {
    fun list(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    fun remember(context: Context, emoji: String) {
        val next = (listOf(emoji) + list(context).filterNot { it == emoji }).take(MAX_RECENT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(SEPARATOR)).apply()
    }

    private const val PREFS = "stickers"
    private const val KEY = "recents"
    private const val SEPARATOR = "\u0001"
    private const val MAX_RECENT = 12
}

/** The Recent chip's category, which is not a set. */
private const val RECENT = "recent"

/** The most images one pick brings in as stickers. */
private const val MAX_STICKER_PICK = 10
