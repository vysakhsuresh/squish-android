package com.squish.app.editor

/*
 * Sound stickers: a sticker that brings its own noise.
 *
 * The sticker and the sound both already exist - an emoji drawn by the caption
 * renderer, and sound effects synthesised on the phone - so this is the pairing
 * and nothing else. Tapping one lands both, at the playhead, as one undo step.
 *
 * tools/jvm/SoundStickerChecks.kt executes it against the effects the synth
 * actually has, which is the one way this list can rot: an effect renamed and
 * every sticker that named it silently landing without a sound.
 */

/** A sticker and the sound that goes on with it. [effectId] is a MusicSynth effect. */
data class SoundSticker(val emoji: String, val label: String, val effectId: String)

object SoundStickers {

    /**
     * The pairs, in the order the card offers them: the obvious ones first,
     * since a person looking for "a boom with an explosion" will not scroll.
     */
    val all: List<SoundSticker> = listOf(
        SoundSticker("🎉", "Ta-da", "sfx-tada"),
        SoundSticker("💥", "Boom", "sfx-boom"),
        SoundSticker("📸", "Shutter", "sfx-shutter"),
        SoundSticker("🔔", "Ding", "sfx-ding"),
        SoundSticker("⚡", "Zap", "sfx-zap"),
        SoundSticker("🥁", "Drum roll", "sfx-drumroll"),
        SoundSticker("✨", "Chime", "sfx-chime"),
        SoundSticker("💨", "Whoosh", "sfx-whoosh"),
        SoundSticker("🫧", "Pop", "sfx-pop"),
        SoundSticker("🤸", "Boing", "sfx-boing"),
        SoundSticker("📺", "Glitch", "sfx-glitch"),
        SoundSticker("❤️", "Heartbeat", "sfx-heartbeat"),
        SoundSticker("⌨️", "Typewriter", "sfx-typewriter"),
        SoundSticker("🚀", "Riser", "sfx-riser"),
        SoundSticker("👉", "Swipe", "sfx-swipe"),
        SoundSticker("🖱️", "Click", "sfx-click")
    )

    /** The pair an emoji is one of, or null - most stickers are silent. */
    fun forEmoji(emoji: String): SoundSticker? = all.firstOrNull { it.emoji == emoji }
}
