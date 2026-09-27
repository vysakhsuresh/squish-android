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
    )
}
