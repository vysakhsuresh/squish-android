package com.squish.app.editor

/**
 * A finished style in one tap: the shape of the frame, a look, a title and the
 * effects that suit it, chosen to go together.
 *
 * Everything a template does is an ordinary edit - the crop, the look, a styled
 * caption, timed effects - so afterwards each part can be changed or removed on
 * its own, and the whole template is one undo.
 */
enum class Template(
    val label: String,
    val blurb: String,
    val crop: CropAspect?,
    val lookId: String?,
    val title: TitlePreset?,
    val titleText: String?,
    /** Effects and when they start, as a fraction of the edit's length. */
    val effects: List<Pair<EffectKind, Float>>
) {
    Reel(
        "Reel", "Vertical, punchy, a title that pops",
        CropAspect.Portrait, "punch", TitlePreset.Headline, "WATCH THIS",
        listOf(EffectKind.Punch to 0f, EffectKind.Flash to 0.5f)
    ),
    Vlog(
        "Vlog", "Warm and bright, a name on screen",
        null, "golden", TitlePreset.LowerThird, "Today's vlog",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Cinematic(
        "Cinematic", "Wide frame, film look, slow push in",
        CropAspect.Landscape, "kodak", TitlePreset.Subtitle, "Chapter one",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Retro(
        "Retro", "Tape and grain, typed title",
        null, "faded", TitlePreset.Typewriter, "Summer '96",
        listOf(EffectKind.Vhs to 0f, EffectKind.Glitch to 0.6f)
    ),
    Party(
        "Party", "Neon colour, bouncing title, flashes",
        CropAspect.Portrait, "neon", TitlePreset.Neon, "Let's go!",
        listOf(EffectKind.Flash to 0f, EffectKind.Rainbow to 0.3f, EffectKind.Shake to 0.7f)
    ),
    Memories(
        "Memories", "Soft black and white, gentle title",
        CropAspect.Square, "silver", TitlePreset.Subtitle, "Remember this",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Travel(
        "Travel", "Sunny colour, a place on screen, a slow sway",
        null, "beach", TitlePreset.Vlog, "Day 1 in Goa",
        listOf(EffectKind.ZoomIn to 0f, EffectKind.Sway to 0.5f)
    ),
    Birthday(
        "Birthday", "Glowing colour, a bouncing wish, flashes",
        CropAspect.Portrait, "glow", TitlePreset.Bounce, "Happy birthday!",
        listOf(EffectKind.Flash to 0f, EffectKind.Heartbeat to 0.5f)
    ),
    Food(
        "Food", "Rich colour for a feed, a slow push in",
        CropAspect.FourFive, "food", TitlePreset.Quote, "Taste test",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Fitness(
        "Fitness", "Hard contrast, a counter, punches on the beat",
        CropAspect.Portrait, "city", TitlePreset.Breaking, "DAY 30",
        listOf(EffectKind.Punch to 0f, EffectKind.Strobe to 0.7f)
    ),
    Gaming(
        "Gaming", "Neon grade, glitches, a win on screen",
        CropAspect.Landscape, "cyberpunk", TitlePreset.Gaming, "GG!",
        listOf(EffectKind.RgbSplit to 0f, EffectKind.Glitch to 0.5f)
    ),
    Love(
        "Love", "Soft and warm, a dreamy glow",
        CropAspect.Portrait, "romance", TitlePreset.Love, "Forever",
        listOf(EffectKind.Dream to 0f, EffectKind.Heartbeat to 0.6f)
    ),
    News(
        "News", "Plain and clear, a breaking banner",
        CropAspect.Landscape, "documentary", TitlePreset.Breaking, "BREAKING",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Sale(
        "Sale", "Bright and loud, a price that pops",
        CropAspect.Square, "bright", TitlePreset.Sale, "50% OFF",
        listOf(EffectKind.Punch to 0f, EffectKind.Flash to 0.5f)
    )
}
