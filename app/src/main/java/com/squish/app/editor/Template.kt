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
    ),

    // Twenty-six more, 4 October. Fourteen was a shelf nobody browsed: a new
    // person opens this looking for the one that is near enough and changes it,
    // and near enough needs a shelf. Every one of these is the looks, the title
    // presets and the effects that already exist, put together - so they cost
    // nothing to carry and each part can still be changed or taken off on its own.
    Trailer(
        "Trailer", "Wide and dark, a chapter card, a slow push",
        CropAspect.Cinema, "blockbuster", TitlePreset.Chapter, "COMING SOON",
        listOf(EffectKind.ZoomIn to 0f, EffectKind.Flash to 0.75f)
    ),
    Thriller(
        "Thriller", "Cold and tense, a heartbeat under it",
        CropAspect.Cinema, "thriller", TitlePreset.Cinema, "No way out",
        listOf(EffectKind.Heartbeat to 0.4f, EffectKind.ZoomIn to 0f)
    ),
    Western(
        "Western", "Dust and sun, a title held still",
        CropAspect.Landscape, "western", TitlePreset.Chapter, "High noon",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Noir(
        "Noir", "Black and white, hard shadows, old film",
        CropAspect.Landscape, "noir", TitlePreset.Chapter, "The case",
        listOf(EffectKind.OldFilm to 0f)
    ),
    Documentary(
        "Doc", "Flat and honest, a name in the corner",
        CropAspect.Landscape, "documentary", TitlePreset.LowerThird, "Name, place",
        emptyList()
    ),
    Wedding(
        "Wedding", "Soft warm film, a name that fades in",
        null, "portra", TitlePreset.Subtitle, "Anna & Sam",
        listOf(EffectKind.Dream to 0f)
    ),
    Baby(
        "Baby", "Pastel and gentle, a little wobble",
        CropAspect.Square, "pastel", TitlePreset.Subtitle, "Six months",
        listOf(EffectKind.Sway to 0f)
    ),
    Pets(
        "Pets", "Bright colour, a bouncing name",
        CropAspect.Portrait, "vivid", TitlePreset.Bounce, "Good dog",
        listOf(EffectKind.Punch to 0.5f)
    ),
    Sunset(
        "Sunset", "Deep orange, a slow sway",
        null, "sunset", TitlePreset.Quote, "Golden hour",
        listOf(EffectKind.Sway to 0f, EffectKind.ZoomIn to 0.5f)
    ),
    Beach(
        "Beach", "Blue and bright, a holiday card",
        CropAspect.Portrait, "beach", TitlePreset.Vlog, "Beach day",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Nature(
        "Nature", "Green and deep, a chapter card",
        CropAspect.Landscape, "forest", TitlePreset.Chapter, "Into the trees",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Desert(
        "Desert", "Hot and dry, wide and still",
        CropAspect.TwoOne, "desert", TitlePreset.Cinema, "Nothing for miles",
        emptyList()
    ),
    Night(
        "Night", "Midnight blue, neon title",
        CropAspect.Portrait, "midnight", TitlePreset.Neon, "After dark",
        listOf(EffectKind.RgbSplit to 0.6f)
    ),
    City(
        "City", "Hard city colour, a split on the beat",
        CropAspect.Portrait, "city", TitlePreset.Headline, "The city",
        listOf(EffectKind.RgbSplit to 0f, EffectKind.Shake to 0.6f)
    ),
    Y2k(
        "Y2K", "Blown-out colour, tape glitches, a spin",
        CropAspect.Portrait, "y2k", TitlePreset.Spin, "so 2003",
        listOf(EffectKind.Glitch to 0f, EffectKind.Static to 0.5f)
    ),
    Vhs(
        "VHS", "Tape, tracking lines, a typed date",
        CropAspect.FourThree, "vhs", TitlePreset.Typewriter, "12-08-1994",
        listOf(EffectKind.Vhs to 0f, EffectKind.Static to 0.6f)
    ),
    Super8(
        "Super 8", "Home-movie film, a classic card",
        CropAspect.FourThree, "super8", TitlePreset.Chapter, "Summer",
        listOf(EffectKind.OldFilm to 0f)
    ),
    Polaroid(
        "Polaroid", "Washed instant film, a handwritten feel",
        CropAspect.Square, "polaroid", TitlePreset.Quote, "That day",
        emptyList()
    ),
    Crt(
        "CRT", "Scan lines and a glowing tube",
        CropAspect.FourThree, "crt", TitlePreset.Typewriter, "PLAY",
        listOf(EffectKind.Static to 0f, EffectKind.RgbSplit to 0.5f)
    ),
    Trippy(
        "Trippy", "Colour that will not sit still",
        CropAspect.Portrait, "dream", TitlePreset.Spin, "woah",
        listOf(EffectKind.Trippy to 0f, EffectKind.Rainbow to 0.5f)
    ),
    Workout(
        "Workout", "Cold and hard, a big number",
        CropAspect.Portrait, "bleach", TitlePreset.BigNumber, "20",
        listOf(EffectKind.Punch to 0f, EffectKind.Shake to 0.6f)
    ),
    Sport(
        "Sport", "Punchy contrast, a strobe on the win",
        CropAspect.Landscape, "punch", TitlePreset.Breaking, "FULL TIME",
        listOf(EffectKind.Strobe to 0.7f, EffectKind.Punch to 0f)
    ),
    Recipe(
        "Recipe", "Warm food colour, a step on screen",
        CropAspect.FourFive, "food", TitlePreset.Subtitle, "Step 1",
        emptyList()
    ),
    Product(
        "Product", "Clean and even, a price in a box",
        CropAspect.Square, "clean", TitlePreset.Headline, "₹1,499",
        listOf(EffectKind.ZoomIn to 0f)
    ),
    Tutorial(
        "Tutorial", "Plain colour, a yellow step marker",
        CropAspect.Landscape, "clean", TitlePreset.LowerThird, "Step one",
        emptyList()
    ),
    Podcast(
        "Podcast", "Warm room, a name under the speaker",
        CropAspect.Portrait, "warm", TitlePreset.LowerThird, "Episode 12",
        emptyList()
    ),
    Quote(
        "Quote", "Matte and quiet, words that hold",
        CropAspect.Square, "matte", TitlePreset.Quote, "“Say something”",
        emptyList()
    ),
    Follow(
        "Follow", "Bright and loud, a follow card",
        CropAspect.Portrait, "insta", TitlePreset.Follow, "Follow for more",
        listOf(EffectKind.Punch to 0.6f)
    )
}

/**
 * The shelves the templates sit on. Forty-two in one list is a scroll nobody
 * finishes; five shelves is a glance.
 *
 * Kept beside the table rather than in it, so adding a template is still one
 * entry and the table stays a table.
 */
enum class TemplateFamily(val label: String) {
    Social("Social"),
    Film("Film"),
    Life("Life"),
    Retro("Retro"),
    Work("Work")
}

val Template.family: TemplateFamily
    get() = when (this) {
        Template.Reel, Template.Party, Template.Birthday, Template.Fitness, Template.Gaming,
        Template.Workout, Template.Sport, Template.Trippy, Template.Night, Template.City,
        Template.Follow -> TemplateFamily.Social

        Template.Cinematic, Template.Trailer, Template.Thriller, Template.Western,
        Template.Noir, Template.Documentary, Template.News -> TemplateFamily.Film

        Template.Vlog, Template.Travel, Template.Memories, Template.Love, Template.Wedding,
        Template.Baby, Template.Pets, Template.Sunset, Template.Beach, Template.Nature,
        Template.Desert -> TemplateFamily.Life

        Template.Retro, Template.Y2k, Template.Vhs, Template.Super8, Template.Polaroid,
        Template.Crt, Template.Quote -> TemplateFamily.Retro

        Template.Food, Template.Sale, Template.Recipe, Template.Product, Template.Tutorial,
        Template.Podcast -> TemplateFamily.Work
    }
