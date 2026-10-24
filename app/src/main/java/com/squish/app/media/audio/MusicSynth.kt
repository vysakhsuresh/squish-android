package com.squish.app.media.audio

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Squish Originals: music the app composes itself, on the phone.
 *
 * Every track here is written by this code - drums, bass, chords and melody
 * synthesised from nothing - so there is no licence to clear, nothing to
 * download, and nothing to upload. A track is rendered the first time it is
 * used and kept as a WAV from then on, so it costs no space until it is wanted.
 */
object MusicSynth {

    const val SAMPLE_RATE = 32_000

    /** Instruments a style can use. */
    internal enum class Lead { Pluck, Bell, Square, None }

    /**
     * One track's recipe: tempo, key, chords, and which parts play. The notes
     * themselves come from the chords, so every recipe stays in key.
     */
    class Style(
        val id: String,
        val title: String,
        val mood: String,
        val bpm: Int,
        /** Chord roots as MIDI notes, one per bar, and whether each is minor. */
        val chords: List<Pair<Int, Boolean>>,
        val bars: Int,
        val kickPattern: String,
        val snarePattern: String,
        val hatPattern: String,
        val swing: Float = 0f,
        val pad: Boolean = true,
        val bassEighths: Boolean = false,
        private val lead: Int = 0,
        val crackle: Boolean = false,
        val echo: Float = 0.25f
    ) {
        internal val leadKind: Lead get() = Lead.entries[lead]
        val seconds: Int get() = (bars * 4 * 60f / bpm).roundToInt()
    }

    private const val PLUCK = 0
    private const val BELL = 1
    private const val SQUARE = 2
    private const val NONE = 3

    // Patterns are 16 steps a bar: x is a hit, - is a rest.
    val styles = listOf(
        Style(
            "lofi-sunset", "Lo-fi Sunset", "Chill · 78 BPM", 78,
            listOf(57 to true, 62 to true, 55 to false, 60 to false), 16,
            kickPattern = "x-----x---x-----", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            swing = 0.18f, lead = PLUCK, crackle = true, echo = 0.3f
        ),
        Style(
            "good-vibes", "Good Vibes", "Happy · 112 BPM", 112,
            listOf(60 to false, 55 to false, 57 to true, 53 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            lead = PLUCK, bassEighths = true
        ),
        Style(
            "epic-rise", "Epic Rise", "Cinematic · 90 BPM", 90,
            listOf(50 to true, 58 to false, 53 to false, 60 to false), 16,
            kickPattern = "x-------x-------", snarePattern = "--------x-------", hatPattern = "----------------",
            lead = BELL, echo = 0.4f
        ),
        Style(
            "night-drive", "Night Drive", "Synthwave · 100 BPM", 100,
            listOf(57 to true, 53 to false, 60 to false, 55 to false), 20,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = SQUARE, bassEighths = true, echo = 0.35f
        ),
        Style(
            "chill-house", "Chill House", "Dance · 120 BPM", 120,
            listOf(53 to true, 56 to false, 51 to false, 58 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = BELL, bassEighths = true
        ),
        Style(
            "street-beat", "Street Beat", "Hip-hop · 90 BPM", 90,
            listOf(52 to true, 52 to true, 57 to true, 55 to false), 16,
            kickPattern = "x-----x-x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-xx",
            swing = 0.12f, pad = false, lead = BELL
        ),
        Style(
            "sunny-day", "Sunny Day", "Upbeat · 116 BPM", 116,
            listOf(55 to false, 62 to false, 64 to true, 60 to false), 24,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            pad = false, lead = PLUCK, bassEighths = true
        ),
        Style(
            "calm-keys", "Calm Keys", "Gentle · 70 BPM", 70,
            listOf(60 to false, 57 to true, 53 to false, 55 to false), 12,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = BELL, echo = 0.45f
        ),
        Style(
            "romantic-piano", "Romantic Piano", "Love · 76 BPM", 76,
            listOf(53 to false, 57 to true, 58 to false, 60 to false), 16,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = BELL, echo = 0.4f
        ),
        Style(
            "trap-energy", "Trap Energy", "Trap · 140 BPM", 140,
            listOf(52 to true, 52 to true, 48 to false, 50 to false), 32,
            kickPattern = "x-----x---x--x--", snarePattern = "--------x-------", hatPattern = "xxxxxxxxxxxxxxxx",
            pad = false, lead = SQUARE, bassEighths = true, echo = 0.2f
        ),
        Style(
            "funky-groove", "Funky Groove", "Funk · 104 BPM", 104,
            listOf(57 to true, 62 to false, 57 to true, 64 to false), 24,
            kickPattern = "x--x--x---x--x--", snarePattern = "----x--x----x---", hatPattern = "xxxxxxxxxxxxxxxx",
            swing = 0.1f, pad = false, lead = PLUCK, bassEighths = true
        ),
        Style(
            "dreamy-ambient", "Dreamy", "Ambient · 60 BPM", 60,
            listOf(53 to false, 60 to false, 57 to true, 55 to false), 12,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = BELL, echo = 0.55f
        ),
        Style(
            "summer-pop", "Summer Pop", "Pop · 124 BPM", 124,
            listOf(60 to false, 55 to false, 57 to true, 53 to false), 32,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = PLUCK, bassEighths = true
        ),
        Style(
            "dark-tension", "Dark Tension", "Thriller · 85 BPM", 85,
            listOf(50 to true, 51 to false, 50 to true, 49 to false), 16,
            kickPattern = "x-------x-------", snarePattern = "----------------", hatPattern = "--x---x---x---x-",
            lead = NONE, echo = 0.3f
        ),
        Style(
            "acoustic-morning", "Acoustic Morning", "Acoustic · 96 BPM", 96,
            listOf(55 to false, 60 to false, 52 to true, 62 to false), 20,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            pad = false, lead = PLUCK, echo = 0.2f
        ),
        Style(
            "retro-arcade", "Retro Arcade", "8-bit · 132 BPM", 132,
            listOf(60 to false, 57 to true, 53 to false, 55 to false), 32,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            pad = false, lead = SQUARE, bassEighths = true, echo = 0.15f
        ),
        Style(
            "jazz-cafe", "Jazz Café", "Jazz · 92 BPM", 92,
            listOf(50 to true, 55 to false, 60 to false, 57 to true), 16,
            kickPattern = "x-------x-------", snarePattern = "------x-------x-", hatPattern = "x--x-xx--x-xx--x",
            swing = 0.3f, lead = BELL, crackle = true, echo = 0.25f
        ),
        Style(
            "travel-vlog", "Travel Vlog", "Travel · 108 BPM", 108,
            listOf(62 to false, 57 to false, 59 to true, 55 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = PLUCK, bassEighths = true, echo = 0.3f
        ),
        Style(
            "inspiring", "Inspiring", "Uplifting · 96 BPM", 96,
            listOf(60 to false, 55 to false, 57 to true, 53 to false), 20,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = BELL, echo = 0.4f
        ),
        Style(
            "lofi-rain", "Rainy Study", "Study · 72 BPM", 72,
            listOf(62 to true, 55 to false, 60 to false, 57 to true), 16,
            kickPattern = "x-----x---x-----", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            swing = 0.2f, lead = BELL, crackle = true, echo = 0.35f
        ),
        Style(
            "party-bounce", "Party Bounce", "Party · 128 BPM", 128,
            listOf(57 to true, 53 to false, 60 to false, 55 to false), 32,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = SQUARE, bassEighths = true, echo = 0.25f
        ),
        Style(
            "heartfelt", "Heartfelt", "Emotional · 68 BPM", 68,
            listOf(57 to true, 53 to false, 60 to false, 55 to false), 12,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = PLUCK, echo = 0.45f
        ),

        // The library's second shelf (24 October): the genres InShot's music
        // tab opens on - vlog, dance, kids and fun, business - that the first
        // twenty-two did not cover. Same engine, so same licence: none needed.
        Style(
            "daily-vlog", "Daily Vlog", "Vlog · 104 BPM", 104,
            listOf(60 to false, 64 to true, 57 to true, 65 to false), 24,
            kickPattern = "x-------x-x-----", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            swing = 0.08f, lead = PLUCK, bassEighths = true, echo = 0.25f
        ),
        Style(
            "city-walk", "City Walk", "Vlog · 98 BPM", 98,
            listOf(62 to true, 55 to false, 60 to false, 57 to true), 20,
            kickPattern = "x-----x---x-----", snarePattern = "----x-------x---", hatPattern = "x-xxx-x-x-xxx-x-",
            swing = 0.14f, pad = false, lead = BELL, echo = 0.3f
        ),
        Style(
            "weekend", "Weekend", "Vlog · 110 BPM", 110,
            listOf(55 to false, 59 to true, 60 to false, 62 to false), 24,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = PLUCK, bassEighths = true, echo = 0.2f
        ),
        Style(
            "deep-house", "Deep House", "House · 122 BPM", 122,
            listOf(57 to true, 57 to true, 53 to false, 55 to false), 28,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---xx",
            lead = BELL, bassEighths = true, echo = 0.35f
        ),
        Style(
            "festival", "Festival", "EDM · 128 BPM", 128,
            listOf(53 to false, 55 to false, 57 to true, 60 to false), 32,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "xxxxxxxxxxxxxxxx",
            lead = SQUARE, bassEighths = true, echo = 0.3f
        ),
        Style(
            "disco-nights", "Disco Nights", "Disco · 118 BPM", 118,
            listOf(57 to true, 62 to true, 55 to false, 60 to false), 28,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            pad = false, lead = PLUCK, bassEighths = true, echo = 0.2f
        ),
        Style(
            "kids-party", "Kids Party", "Kids · 126 BPM", 126,
            listOf(60 to false, 65 to false, 67 to false, 60 to false), 24,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            pad = false, lead = BELL, echo = 0.15f
        ),
        Style(
            "silly-march", "Silly March", "Comedy · 110 BPM", 110,
            listOf(55 to false, 60 to false, 55 to false, 62 to false), 20,
            kickPattern = "x-------x-------", snarePattern = "----x-------x-x-", hatPattern = "----------------",
            pad = false, lead = SQUARE, echo = 0.1f
        ),
        Style(
            "cartoon-chase", "Cartoon Chase", "Fun · 150 BPM", 150,
            listOf(57 to true, 58 to false, 57 to true, 52 to false), 32,
            kickPattern = "x---x---x---x---", snarePattern = "--x---x---x---x-", hatPattern = "xxxxxxxxxxxxxxxx",
            pad = false, lead = SQUARE, bassEighths = true, echo = 0.1f
        ),
        Style(
            "bright-ideas", "Bright Ideas", "Corporate · 112 BPM", 112,
            listOf(60 to false, 67 to false, 57 to true, 65 to false), 24,
            kickPattern = "x-------x-------", snarePattern = "----x-------x---", hatPattern = "--x---x---x---x-",
            lead = PLUCK, bassEighths = true, echo = 0.3f
        ),
        Style(
            "tech-launch", "Tech Launch", "Tech · 100 BPM", 100,
            listOf(52 to true, 48 to false, 55 to false, 50 to false), 20,
            kickPattern = "x---x---x---x---", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            lead = BELL, bassEighths = true, echo = 0.4f
        ),
        Style(
            "clean-slide", "Clean Slide", "Presentation · 92 BPM", 92,
            listOf(62 to false, 59 to true, 55 to false, 57 to false), 16,
            kickPattern = "x-------x-------", snarePattern = "----------------", hatPattern = "--x---x---x---x-",
            lead = PLUCK, echo = 0.3f
        ),
        Style(
            "adventure", "Adventure", "Epic · 96 BPM", 96,
            listOf(50 to true, 53 to false, 48 to false, 55 to false), 20,
            kickPattern = "x-------x-x-----", snarePattern = "--------x-------", hatPattern = "----------------",
            lead = BELL, echo = 0.45f
        ),
        Style(
            "memories", "Memories", "Nostalgic · 72 BPM", 72,
            listOf(55 to false, 52 to true, 60 to false, 62 to false), 12,
            kickPattern = "----------------", snarePattern = "----------------", hatPattern = "----------------",
            lead = PLUCK, crackle = true, echo = 0.5f
        ),
        Style(
            "boom-bap", "Boom Bap", "Hip-hop · 88 BPM", 88,
            listOf(50 to true, 53 to false, 50 to true, 48 to false), 16,
            kickPattern = "x-------x-x-----", snarePattern = "----x-------x---", hatPattern = "x-x-x-x-x-x-x-x-",
            swing = 0.22f, pad = false, lead = PLUCK, crackle = true, echo = 0.15f
        )
    )

    fun byId(id: String): Style? = styles.firstOrNull { it.id == id }

    /**
     * Where every beat of [style] is, exactly as [render] writes it: beat n at
     * n x 60/bpm seconds, each bar starting on a fourth. Listening for them
     * found only 7 of 22 originals on their tempo - an octave off on 8, and
     * at 4/3 of it on others (SynthTempoChecks) - when the answer was never
     * in doubt. Full confidence: it is not a guess.
     */
    fun beatMap(style: Style): BeatMap {
        val beatMs = 60_000.0 / style.bpm
        val count = style.bars * 4
        return BeatMap(
            beatsMs = List(count) { (it * beatMs).toLong() },
            bpm = style.bpm.toFloat(),
            confidence = 1f,
            downbeatOffset = 0
        )
    }

    /** What the originals are browsed by: a mood, coarser than each track's own. */
    val vibes = listOf("All", "Vlog", "Chill", "Upbeat", "Dance", "Beats", "Cinematic", "Emotional", "Fun", "Business")

    fun vibeOf(style: Style): String = when (style.id) {
        "lofi-sunset", "calm-keys", "dreamy-ambient", "lofi-rain", "jazz-cafe" -> "Chill"
        "acoustic-morning", "travel-vlog", "daily-vlog", "city-walk", "weekend" -> "Vlog"
        "chill-house", "party-bounce", "deep-house", "festival", "disco-nights" -> "Dance"
        "street-beat", "trap-energy", "funky-groove", "boom-bap" -> "Beats"
        "epic-rise", "night-drive", "dark-tension", "inspiring", "adventure" -> "Cinematic"
        "romantic-piano", "heartfelt", "memories" -> "Emotional"
        "retro-arcade", "kids-party", "silly-march", "cartoon-chase" -> "Fun"
        "bright-ideas", "tech-launch", "clean-slide" -> "Business"
        else -> "Upbeat"
    }

    /** One sound effect: a moment of sound, made from nothing like the music. */
    class Effect(val id: String, val title: String, val hint: String, val seconds: Float, val category: String)

    /**
     * The shelves the library is browsed by, in the order its tiles show -
     * InShot's and CapCut's own, near enough, since that is where a person
     * coming from either will look. Every one holds at least four sounds
     * (MusicSynthChecks).
     */
    val effectCategories = listOf(
        "Transitions", "Impacts", "Funny", "UI & alerts", "Game", "Musical", "Build-ups", "Nature", "Horror"
    )

    /**
     * The sound effects: the handful every montage reaches for - a whoosh on a
     * cut, a pop on a sticker, a click, a riser into the drop, a ding on a
     * point made. Synthesised, so there is no library to license or download.
     */
    val effects = listOf(
        Effect("sfx-whoosh", "Whoosh", "Transition · 0.8s", 0.8f, "Transitions"),
        Effect("sfx-pop", "Pop", "Funny · 0.3s", 0.3f, "Funny"),
        Effect("sfx-click", "Click", "UI · 0.15s", 0.15f, "UI & alerts"),
        Effect("sfx-riser", "Riser", "Build-up · 2.5s", 2.5f, "Build-ups"),
        Effect("sfx-ding", "Ding", "Notice · 1.5s", 1.5f, "UI & alerts"),
        Effect("sfx-swipe", "Swipe", "Transition · 0.4s", 0.4f, "Transitions"),
        Effect("sfx-boom", "Boom", "Impact · 1.4s", 1.4f, "Impacts"),
        Effect("sfx-glitch", "Glitch", "Digital · 0.6s", 0.6f, "Game"),
        Effect("sfx-shutter", "Camera shutter", "Photo · 0.35s", 0.35f, "UI & alerts"),
        Effect("sfx-boing", "Boing", "Funny · 0.7s", 0.7f, "Funny"),
        Effect("sfx-chime", "Chime", "Notice · 1.2s", 1.2f, "UI & alerts"),
        Effect("sfx-heartbeat", "Heartbeat", "Tension · 1.1s", 1.1f, "Horror"),
        Effect("sfx-typewriter", "Typewriter", "Text · 1.2s", 1.2f, "UI & alerts"),
        Effect("sfx-drumroll", "Drum roll", "Build-up · 1.8s", 1.8f, "Build-ups"),
        Effect("sfx-tada", "Ta-da", "Reveal · 1.6s", 1.6f, "Musical"),
        Effect("sfx-zap", "Zap", "Game · 0.4s", 0.4f, "Game"),

        // The sound design a fast reel is actually made of. A FinalCut timeline
        // for a 22-second wedding reel was read off a screen recording and had
        // ten of these on it - reverse bass into a cut, a glass shard on a
        // whip pan, cloth on a turn, a sub under an impact. Synthesised like
        // everything else here, so there is nothing to licence or download.
        Effect("sfx-reverse", "Reverse whoosh", "Into a cut · 1.2s", 1.2f, "Transitions"),
        Effect("sfx-subdrop", "Sub drop", "Impact · 1.6s", 1.6f, "Impacts"),
        Effect("sfx-impact", "Impact", "Hit · 1.0s", 1.0f, "Impacts"),
        Effect("sfx-glass", "Glass", "Shatter · 1.1s", 1.1f, "Impacts"),
        Effect("sfx-metal", "Metal", "Ring · 1.3s", 1.3f, "Impacts"),
        Effect("sfx-cloth", "Cloth", "Movement · 0.5s", 0.5f, "Transitions"),
        Effect("sfx-swish", "Swish", "Whip pan · 0.35s", 0.35f, "Transitions"),
        Effect("sfx-build", "Long riser", "Build-up · 4.0s", 4f, "Build-ups"),

        // The library proper (24 October): the shelves a person coming from
        // InShot looks for. Built from the same few generators below, so every
        // sound is still ours. Applause and laughter are not here: a synth
        // makes a poor crowd, and a bad one is worse than none.
        Effect("sfx-whoosh-fast", "Fast whoosh", "Quick cut · 0.4s", 0.4f, "Transitions"),
        Effect("sfx-whoosh-deep", "Deep whoosh", "Slow pass · 1.2s", 1.2f, "Transitions"),
        Effect("sfx-swoosh-up", "Swoosh up", "Rising · 0.7s", 0.7f, "Transitions"),
        Effect("sfx-swoosh-down", "Swoosh down", "Falling · 0.7s", 0.7f, "Transitions"),
        Effect("sfx-tapestop", "Tape stop", "Slow-down · 1.0s", 1.0f, "Transitions"),
        Effect("sfx-rewind", "Rewind", "Flashback · 1.0s", 1.0f, "Transitions"),

        Effect("sfx-punch", "Punch", "Hit · 0.4s", 0.4f, "Impacts"),
        Effect("sfx-slam", "Door slam", "Hit · 0.8s", 0.8f, "Impacts"),
        Effect("sfx-thud", "Thud", "Drop · 0.5s", 0.5f, "Impacts"),
        Effect("sfx-cinematic-boom", "Cinematic boom", "Trailer · 3.0s", 3f, "Impacts"),
        Effect("sfx-slash", "Sword slash", "Blade · 0.5s", 0.5f, "Impacts"),

        Effect("sfx-slide-up", "Slide whistle up", "Cartoon · 0.9s", 0.9f, "Funny"),
        Effect("sfx-slide-down", "Slide whistle down", "Cartoon · 0.9s", 0.9f, "Funny"),
        Effect("sfx-trombone", "Sad trombone", "Fail · 2.0s", 2f, "Funny"),
        Effect("sfx-squeak", "Squeak", "Toy · 0.25s", 0.25f, "Funny"),
        Effect("sfx-honk", "Honk", "Clown horn · 0.5s", 0.5f, "Funny"),
        Effect("sfx-bubbles", "Bubbles", "Cartoon · 1.0s", 1f, "Funny"),
        Effect("sfx-wobble", "Wobble", "Jelly · 0.8s", 0.8f, "Funny"),
        Effect("sfx-plop", "Plop", "Drop in · 0.3s", 0.3f, "Funny"),
        Effect("sfx-zip", "Zip away", "Run off · 0.6s", 0.6f, "Funny"),

        Effect("sfx-notify", "Notification", "Phone · 0.8s", 0.8f, "UI & alerts"),
        Effect("sfx-success", "Success", "Done · 0.9s", 0.9f, "UI & alerts"),
        Effect("sfx-error", "Error", "Wrong · 0.6s", 0.6f, "UI & alerts"),
        Effect("sfx-message", "Message", "Chat · 0.5s", 0.5f, "UI & alerts"),
        Effect("sfx-tap", "Tap", "Touch · 0.1s", 0.1f, "UI & alerts"),
        Effect("sfx-switch", "Switch", "Toggle · 0.15s", 0.15f, "UI & alerts"),
        Effect("sfx-cash", "Cash register", "Sale · 1.2s", 1.2f, "UI & alerts"),
        Effect("sfx-ticktock", "Tick-tock", "Clock · 2.0s", 2f, "UI & alerts"),

        Effect("sfx-coin", "Coin", "Collect · 0.5s", 0.5f, "Game"),
        Effect("sfx-jump", "Jump", "Hop · 0.35s", 0.35f, "Game"),
        Effect("sfx-powerup", "Power-up", "Bonus · 1.0s", 1f, "Game"),
        Effect("sfx-laser", "Laser", "Shoot · 0.4s", 0.4f, "Game"),
        Effect("sfx-gameover", "Game over", "Lose · 1.6s", 1.6f, "Game"),
        Effect("sfx-levelup", "Level up", "Win · 1.2s", 1.2f, "Game"),
        Effect("sfx-blip", "Blip", "Menu · 0.12s", 0.12f, "Game"),
        Effect("sfx-explode8", "Pixel explosion", "Retro · 0.9s", 0.9f, "Game"),

        Effect("sfx-gong", "Gong", "Reveal · 3.0s", 3f, "Musical"),
        Effect("sfx-cymbal", "Cymbal crash", "Accent · 2.5s", 2.5f, "Musical"),
        Effect("sfx-harp", "Harp glissando", "Dream · 1.6s", 1.6f, "Musical"),
        Effect("sfx-scratch", "Record scratch", "Stop · 0.6s", 0.6f, "Musical"),
        Effect("sfx-orchhit", "Orchestra hit", "Stab · 1.2s", 1.2f, "Musical"),

        Effect("sfx-tension", "Tension rise", "Suspense · 3.0s", 3f, "Build-ups"),
        Effect("sfx-charge", "Charge up", "Power · 1.2s", 1.2f, "Build-ups"),

        Effect("sfx-thunder", "Thunder", "Storm · 3.0s", 3f, "Nature"),
        Effect("sfx-wind", "Wind", "Gust · 3.0s", 3f, "Nature"),
        Effect("sfx-rain", "Rain", "Shower · 3.0s", 3f, "Nature"),
        Effect("sfx-bird", "Bird chirp", "Morning · 1.0s", 1f, "Nature"),
        Effect("sfx-drop", "Water drop", "Drip · 0.4s", 0.4f, "Nature"),

        Effect("sfx-drone", "Dark drone", "Dread · 3.0s", 3f, "Horror"),
        Effect("sfx-stinger", "Horror stinger", "Jump scare · 1.8s", 1.8f, "Horror"),
        Effect("sfx-ghost", "Ghostly", "Haunted · 2.0s", 2f, "Horror"),
        Effect("sfx-darkhit", "Dark hit", "Doom · 1.5s", 1.5f, "Horror")
    )

    fun effectById(id: String): Effect? = effects.firstOrNull { it.id == id }

    /** Renders [effect] to a stereo 16-bit WAV at [out]. */
    fun renderEffect(effect: Effect, out: File) {
        val frames = (effect.seconds * SAMPLE_RATE).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val rng = Random(effect.id.hashCode())
        when (effect.id) {
            "sfx-whoosh" -> whoosh(left, right, rng)
            "sfx-pop" -> pop(left, right)
            "sfx-click" -> click(left, right, rng)
            "sfx-riser" -> riser(left, right, rng)
            "sfx-swipe" -> whoosh(left, right, rng)
            "sfx-boom" -> boom(left, right, rng)
            "sfx-glitch" -> glitch(left, right, rng)
            "sfx-shutter" -> shutter(left, right, rng)
            "sfx-boing" -> boing(left, right)
            "sfx-chime" -> chime(left, right)
            "sfx-heartbeat" -> heartbeatThumps(left, right)
            "sfx-typewriter" -> typewriter(left, right, rng)
            "sfx-drumroll" -> drumroll(left, right, rng)
            "sfx-tada" -> tada(left, right)
            "sfx-zap" -> zap(left, right)
            "sfx-reverse" -> reverseWhoosh(left, right, rng)
            "sfx-subdrop" -> subDrop(left, right, rng)
            "sfx-impact" -> impact(left, right, rng)
            "sfx-glass" -> glass(left, right, rng)
            "sfx-metal" -> metal(left, right, rng)
            "sfx-cloth" -> cloth(left, right, rng)
            "sfx-swish" -> swish(left, right, rng)
            "sfx-build" -> riser(left, right, rng)
            // Named rather than left to the fallback, so every effect on the
            // list has a branch of its own and ControlChecks can say so.
            "sfx-ding" -> ding(left, right)
            "sfx-whoosh-fast" -> sweep(left, right, rng, { x -> 0.03 + 0.5 * sin(PI * x) }, { x -> sin(PI * x.pow(0.6)) }, 0.9)
            "sfx-whoosh-deep" -> deepWhoosh(left, right, rng)
            "sfx-swoosh-up" -> sweep(left, right, rng, { x -> 0.02 + 0.6 * x }, { x -> sin(PI * x) }, 1.4, band = true)
            "sfx-swoosh-down" -> sweep(left, right, rng, { x -> 0.62 - 0.6 * x }, { x -> sin(PI * x) }, 1.4, band = true)
            "sfx-tapestop" -> tapeStop(left, right)
            "sfx-rewind" -> rewind(left, right, rng)
            "sfx-punch" -> punch(left, right, rng)
            "sfx-slam" -> slam(left, right, rng)
            "sfx-thud" -> thud(left, right, rng)
            "sfx-cinematic-boom" -> cinematicBoom(left, right, rng)
            "sfx-slash" -> slash(left, right, rng)
            "sfx-slide-up" -> tone(left, right, 0.0, 0.9, 500.0, 1800.0, level = 0.6, attack = 0.03, vibHz = 6.0, vibDepth = 0.02)
            "sfx-slide-down" -> tone(left, right, 0.0, 0.9, 1800.0, 450.0, level = 0.6, attack = 0.03, vibHz = 6.0, vibDepth = 0.02)
            "sfx-trombone" -> trombone(left, right)
            "sfx-squeak" -> squeak(left, right)
            "sfx-honk" -> honk(left, right)
            "sfx-bubbles" -> bubbles(left, right, rng)
            "sfx-wobble" -> tone(left, right, 0.0, 0.8, 300.0, level = 0.7, decay = 3.0, vibHz = 12.0, vibDepth = 0.25)
            "sfx-plop" -> plop(left, right, rng)
            "sfx-zip" -> zip(left, right, rng)
            "sfx-notify" -> notify(left, right)
            "sfx-success" -> success(left, right)
            "sfx-error" -> errorBuzz(left, right)
            "sfx-message" -> message(left, right)
            "sfx-tap" -> tap(left, right, rng)
            "sfx-switch" -> switchClick(left, right)
            "sfx-cash" -> cash(left, right, rng)
            "sfx-ticktock" -> tickTock(left, right)
            "sfx-coin" -> coin(left, right)
            "sfx-jump" -> tone(left, right, 0.0, 0.3, 260.0, 780.0, Wave.Square, level = 0.3, decay = 3.0, soften = 0.5)
            "sfx-powerup" -> powerUp(left, right)
            "sfx-laser" -> laser(left, right)
            "sfx-gameover" -> gameOver(left, right)
            "sfx-levelup" -> levelUp(left, right)
            "sfx-blip" -> tone(left, right, 0.0, 0.1, 1500.0, wave = Wave.Square, level = 0.3, decay = 25.0, soften = 0.6)
            "sfx-explode8" -> pixelExplosion(left, right, rng)
            "sfx-gong" -> gong(left, right, rng)
            "sfx-cymbal" -> cymbal(left, right, rng)
            "sfx-harp" -> harp(left, right, rng)
            "sfx-scratch" -> scratch(left, right, rng)
            "sfx-orchhit" -> orchestraHit(left, right, rng)
            "sfx-tension" -> tensionRise(left, right, rng)
            "sfx-charge" -> chargeUp(left, right)
            "sfx-thunder" -> thunder(left, right, rng)
            "sfx-wind" -> wind(left, right, rng)
            "sfx-rain" -> rain(left, right, rng)
            "sfx-bird" -> bird(left, right)
            "sfx-drop" -> waterDrop(left, right)
            "sfx-drone" -> drone(left, right)
            "sfx-stinger" -> stinger(left, right, rng)
            "sfx-ghost" -> ghost(left, right)
            "sfx-darkhit" -> darkHit(left, right, rng)
            else -> ding(left, right)
        }
        release(left, right)
        write(out, left, right)
    }

    /**
     * The last three milliseconds faded to nothing.
     *
     * A sound that is still at level on its last sample is a step straight to
     * silence, and a step is a click. Several of these end loud by design - a
     * riser rises, a reverse whoosh swells into the cut it is laid against -
     * and "Sound on every cut" butts them against a join, so the tick landed on
     * every cut in the edit. Three milliseconds is a hundred and thirty-two
     * samples: under the ear's resolution for a change of level, and it cannot
     * take the swell off a riser a second and a half long.
     *
     * The start is left alone. A transient begins at level because that is what
     * a transient is; silence to a hit is the sound, not a fault.
     */
    private fun release(l: FloatArray, r: FloatArray) {
        val fade = minOf(SAMPLE_RATE * 3 / 1000, l.size)
        if (fade <= 1) return
        for (i in 0 until fade) {
            val gain = (fade - 1 - i).toFloat() / (fade - 1)
            val at = l.size - fade + i
            l[at] *= gain
            r[at] *= gain
        }
    }

    /** A deep hit: a sub tone dropping in pitch, a burst of low noise on top. */
    private fun boom(l: FloatArray, r: FloatArray, rng: Random) {
        var phase = 0.0
        var lp = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val f = 38 + 90 * exp(-x * 12)
            phase += 2 * PI * f / SAMPLE_RATE
            lp += ((rng.nextFloat() * 2 - 1) - lp) * 0.05
            val v = sin(phase) * exp(-x * 2.6) + lp * 1.6 * exp(-x * 9)
            l[i] = (v * minOf(1.0, x * 500)).toFloat()
            r[i] = l[i]
        }
    }

    /** Short bursts of crushed tone and noise, jumping between pitches. */
    private fun glitch(l: FloatArray, r: FloatArray, rng: Random) {
        val chunk = SAMPLE_RATE / 25
        var hz = 400.0
        var hold = 0f
        for (i in l.indices) {
            if (i % chunk == 0) hz = 150.0 + rng.nextInt(1400)
            val on = (i / chunk) % 3 != 2
            if (i % 6 == 0) hold = if (rng.nextInt(4) == 0) rng.nextFloat() * 2 - 1 else (if ((i * hz / SAMPLE_RATE) % 1.0 < 0.5) 0.8f else -0.8f)
            val v = if (on) hold * 0.6f else 0f
            l[i] = v
            r[i] = if ((i / chunk) % 2 == 0) v else -v
        }
    }

    /** Two clicks a breath apart with a slap of noise: a mirror flipping. */
    private fun shutter(l: FloatArray, r: FloatArray, rng: Random) {
        var prev = 0f
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            val first = if (x < 0.06) exp(-x * 90) else 0.0
            val second = if (x >= 0.14) exp(-(x - 0.14) * 70) else 0.0
            val v = hp * (first * 0.9 + second * 0.7) + (if (x < 0.002 || (x >= 0.14 && x < 0.142)) 0.6 else 0.0)
            l[i] = v.toFloat()
            r[i] = v.toFloat()
        }
    }

    /** A spring: a tone wobbling in pitch as it dies away. */
    private fun boing(l: FloatArray, r: FloatArray) {
        var phase = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val f = 180 + 120 * sin(2 * PI * 9 * x) * exp(-x * 3) + 60 * exp(-x * 8)
            phase += 2 * PI * f / SAMPLE_RATE
            l[i] = (sin(phase) * exp(-x * 4.5) * minOf(1.0, x * 300) * 0.8).toFloat()
            r[i] = l[i]
        }
    }

    /** Two bell notes a fifth apart, the second answering the first. */
    private fun chime(l: FloatArray, r: FloatArray) {
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            fun note(hz: Double, at: Double): Double {
                if (x < at) return 0.0
                val t = x - at
                return (sin(2 * PI * hz * t) + 0.3 * sin(2 * PI * hz * 2.76 * t) * exp(-t * 6)) * exp(-t * 3.5) * minOf(1.0, t * 600)
            }
            val v = note(1046.5, 0.0) + note(1568.0, 0.16)
            l[i] = (v * 0.4).toFloat()
            r[i] = (v * 0.4).toFloat()
        }
    }

    /** Lub-dub: two soft, low thumps. */
    private fun heartbeatThumps(l: FloatArray, r: FloatArray) {
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            fun thump(at: Double, level: Double): Double {
                if (x < at) return 0.0
                val t = x - at
                return sin(2 * PI * (55 + 30 * exp(-t * 30)) * t) * exp(-t * 18) * level
            }
            val v = thump(0.0, 1.0) + thump(0.22, 0.75)
            l[i] = v.toFloat()
            r[i] = v.toFloat()
        }
    }

    /** Keys struck unevenly, and the bell at the end of the line. */
    private fun typewriter(l: FloatArray, r: FloatArray, rng: Random) {
        val n = l.size
        var t = 0.03
        while (t < 0.9) {
            val start = (t * SAMPLE_RATE).toInt()
            var prev = 0f
            for (k in 0 until (0.03 * SAMPLE_RATE).toInt()) {
                if (start + k >= n) break
                val x = k.toDouble() / SAMPLE_RATE
                val noise = rng.nextFloat() * 2 - 1
                val hp = noise - prev
                prev = noise
                val v = (hp * 0.6 + sin(2 * PI * 1800 * x) * 0.3) * exp(-x * 160)
                l[start + k] += v.toFloat()
                r[start + k] += (v * 0.8).toFloat()
            }
            t += 0.07 + rng.nextDouble() * 0.06
        }
        val bellAt = (0.95 * SAMPLE_RATE).toInt()
        for (k in 0 until n - bellAt) {
            val x = k.toDouble() / SAMPLE_RATE
            val v = sin(2 * PI * 2100 * x) * exp(-x * 9) * 0.35
            l[bellAt + k] += v.toFloat()
            r[bellAt + k] += v.toFloat()
        }
    }

    /** A snare rolling faster and louder, then a crash on the last moment. */
    private fun drumroll(l: FloatArray, r: FloatArray, rng: Random) {
        val n = l.size
        val total = n.toDouble() / SAMPLE_RATE
        var t = 0.0
        while (t < total - 0.25) {
            val progress = t / total
            snare(l, r, t, rng)
            t += 0.07 - 0.035 * progress
        }
        val crashAt = ((total - 0.25) * SAMPLE_RATE).toInt()
        var prev = 0f
        for (k in 0 until n - crashAt) {
            val x = k.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            l[crashAt + k] += (hp * exp(-x * 6) * 0.8).toFloat()
            r[crashAt + k] += (hp * exp(-x * 6) * 0.8).toFloat()
        }
        // A crescendo: the roll's early hits brought down.
        for (i in 0 until crashAt.coerceAtMost(n)) {
            val g = (0.35 + 0.65 * i.toDouble() / crashAt).toFloat()
            l[i] *= g
            r[i] *= g
        }
    }

    /** Two quick notes, then a bright major chord held. */
    private fun tada(l: FloatArray, r: FloatArray) {
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            fun tone(hz: Double, at: Double, decay: Double): Double {
                if (x < at) return 0.0
                val t = x - at
                val saw = 2 * ((t * hz) % 1.0) - 1
                return (sin(2 * PI * hz * t) * 0.7 + saw * 0.15) * exp(-t * decay) * minOf(1.0, t * 400)
            }
            val v = tone(392.0, 0.0, 9.0) + tone(523.25, 0.12, 2.2) + tone(659.25, 0.12, 2.2) + tone(783.99, 0.12, 2.2)
            l[i] = (v * 0.3).toFloat()
            r[i] = (v * 0.3).toFloat()
        }
    }

    /** A laser: a bright tone diving down in pitch. */
    private fun zap(l: FloatArray, r: FloatArray) {
        var phase = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val f = 200 + 2200 * exp(-x * 14)
            phase += 2 * PI * f / SAMPLE_RATE
            val sq = if (sin(phase) >= 0) 1.0 else -1.0
            l[i] = (sq * 0.35 * exp(-x * 7)).toFloat()
            r[i] = l[i]
        }
    }

    /** Noise through a low-pass whose cutoff sweeps up and back: air moving past. */
    private fun whoosh(l: FloatArray, r: FloatArray, rng: Random) {
        var lpL = 0.0
        var lpR = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / n
            // Loudest a third of the way in, gone by the end.
            val env = sin(PI * x.pow(0.7))
            val cutoff = 0.02 + 0.3 * sin(PI * x)
            val nl = rng.nextFloat() * 2 - 1
            val nr = rng.nextFloat() * 2 - 1
            lpL += (nl - lpL) * cutoff
            lpR += (nr - lpR) * cutoff
            l[i] = (lpL * env * 0.9).toFloat()
            r[i] = (lpR * env * 0.9).toFloat()
        }
    }

    /**
     * A whoosh played backwards: quiet, rising, and cut off dead at the end.
     *
     * The one every reel uses, and the reason it works: the silence where it
     * stops is the cut. Laid so it *ends* on the join (CutSounds.LEAD_IN),
     * it pulls the eye into the next shot rather than following the last.
     */
    private fun reverseWhoosh(l: FloatArray, r: FloatArray, rng: Random) {
        var lpL = 0.0
        var lpR = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / n
            // Rising all the way, loudest at the very end, then nothing.
            val env = x.pow(2.2)
            // The filter opens as it rises, which is what makes it read as
            // coming towards you rather than merely getting louder.
            val cutoff = 0.01 + 0.45 * x
            lpL += ((rng.nextFloat() * 2 - 1) - lpL) * cutoff
            lpR += ((rng.nextFloat() * 2 - 1) - lpR) * cutoff
            l[i] = (lpL * env * 0.95).toFloat()
            r[i] = (lpR * env * 0.95).toFloat()
        }
    }

    /** A sine falling two octaves under a short noise slap: the drop on an impact. */
    private fun subDrop(l: FloatArray, r: FloatArray, rng: Random) {
        var phase = 0.0
        var lp = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / SAMPLE_RATE
            val f = 120 * exp(-x * 2.4) + 28
            phase += 2 * PI * f / SAMPLE_RATE
            lp += ((rng.nextFloat() * 2 - 1) - lp) * 0.12
            val slap = lp * 1.2 * exp(-x * 26)
            val sub = sin(phase) * exp(-x * 1.1)
            // Up over the first two milliseconds, or the start clicks.
            val v = (sub + slap) * minOf(1.0, i / (SAMPLE_RATE * 0.002))
            l[i] = (v * 0.9).toFloat()
            r[i] = l[i]
        }
    }

    /** A broadband hit with a short tail: the thing a cut lands on. */
    private fun impact(l: FloatArray, r: FloatArray, rng: Random) {
        var lp = 0.0
        var hp = 0.0
        var prev = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val n = (rng.nextFloat() * 2 - 1).toDouble()
            lp += (n - lp) * 0.08
            hp = n - prev
            prev = n
            val body = lp * exp(-x * 7)
            val crack = hp * 0.5 * exp(-x * 55)
            val v = (body * 1.6 + crack) * minOf(1.0, i / (SAMPLE_RATE * 0.001))
            l[i] = (v * 0.85).toFloat()
            r[i] = (v * 0.85).toFloat()
        }
    }

    /** Bright shards: a burst of high partials that die at different rates. */
    private fun glass(l: FloatArray, r: FloatArray, rng: Random) {
        val partials = 14
        val hz = DoubleArray(partials) { 2200.0 + rng.nextDouble() * 5200.0 }
        val decay = DoubleArray(partials) { 6.0 + rng.nextDouble() * 26.0 }
        val start = DoubleArray(partials) { if (it < 4) 0.0 else rng.nextDouble() * 0.09 }
        val pan = DoubleArray(partials) { rng.nextDouble() }
        val phase = DoubleArray(partials)
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            var sl = 0.0
            var sr = 0.0
            for (p in 0 until partials) {
                if (x < start[p]) continue
                phase[p] += 2 * PI * hz[p] / SAMPLE_RATE
                val v = sin(phase[p]) * exp(-(x - start[p]) * decay[p])
                sl += v * (1.0 - pan[p])
                sr += v * pan[p]
            }
            l[i] = (sl / partials * 2.4).toFloat()
            r[i] = (sr / partials * 2.4).toFloat()
        }
    }

    /** A struck metal ring: a few inharmonic partials over a long tail. */
    private fun metal(l: FloatArray, r: FloatArray, rng: Random) {
        // Inharmonic on purpose - whole-number partials ring as a musical note
        // and stop sounding like metal.
        val hz = doubleArrayOf(523.0, 831.0, 1193.0, 1657.0, 2311.0, 3137.0)
        val gain = doubleArrayOf(1.0, 0.7, 0.55, 0.4, 0.3, 0.22)
        val phase = DoubleArray(hz.size)
        var lp = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            var s = 0.0
            hz.indices.forEach { p ->
                phase[p] += 2 * PI * hz[p] / SAMPLE_RATE
                s += sin(phase[p]) * gain[p] * exp(-x * (1.4 + p * 0.9))
            }
            lp += ((rng.nextFloat() * 2 - 1) - lp) * 0.4
            val strike = lp * exp(-x * 90) * 0.6
            val v = (s / 3.0 + strike) * minOf(1.0, i / (SAMPLE_RATE * 0.001))
            l[i] = (v * 0.8).toFloat()
            r[i] = (v * 0.8).toFloat()
        }
    }

    /** Fabric moving: band-passed noise with two soft swells. */
    private fun cloth(l: FloatArray, r: FloatArray, rng: Random) {
        var lp = 0.0
        var prev = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val raw = rng.nextFloat() * 2 - 1
            lp += (raw - lp) * 0.25
            val band = lp - prev
            prev = lp
            // Two rubs rather than one, a third of the way apart: one swell
            // reads as a whoosh, two read as cloth.
            val env = sin(PI * x) * (0.75 + 0.25 * sin(PI * 3 * x))
            l[i] = (band * env * 2.6).toFloat()
            r[i] = (band * env * 2.4).toFloat()
        }
    }

    /** A short, high whip: the sound of the camera flicking across. */
    private fun swish(l: FloatArray, r: FloatArray, rng: Random) {
        var lp = 0.0
        var prev = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val raw = rng.nextFloat() * 2 - 1
            lp += (raw - lp) * (0.1 + 0.6 * sin(PI * x))
            val band = lp - prev
            prev = lp
            val env = sin(PI * x.pow(0.55))
            // Across the stereo field, left to right, which is the pan itself.
            l[i] = (band * env * 2.2 * (1.0 - x)).toFloat()
            r[i] = (band * env * 2.2 * x).toFloat()
        }
    }

    /** A sine that drops an octave and a half in sixty milliseconds: a cork. */
    private fun pop(l: FloatArray, r: FloatArray) {
        var phase = 0.0
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val f = 220 + 700 * exp(-x * 45)
            phase += 2 * PI * f / SAMPLE_RATE
            val v = sin(phase) * exp(-x * 22) * 0.9
            l[i] = v.toFloat()
            r[i] = v.toFloat()
        }
    }

    /** A two-millisecond impulse with a little high-passed noise behind it. */
    private fun click(l: FloatArray, r: FloatArray, rng: Random) {
        var prev = 0f
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            val impulse = if (x < 0.002) 1.0 - x / 0.002 else 0.0
            val v = impulse * 0.8 + hp * 0.25 * exp(-x * 400)
            l[i] = v.toFloat()
            r[i] = v.toFloat()
        }
    }

    /** Noise and a tone both climbing, louder all the way, ending on the hit. */
    private fun riser(l: FloatArray, r: FloatArray, rng: Random) {
        var lp = 0.0
        var phase = 0.0
        val n = l.size
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val t = i.toDouble() / SAMPLE_RATE
            val env = x.pow(2.2)
            val noise = rng.nextFloat() * 2 - 1
            lp += (noise - lp) * (0.05 + 0.4 * x)
            val f = 180 * 2.0.pow(x * 3.2)
            phase += 2 * PI * f / SAMPLE_RATE
            val tone = sin(phase) * 0.35
            // A hit at the very end, so a cut on it lands on something.
            val hit = if (x > 0.97) sin(2 * PI * 60 * t) * (1 - (x - 0.97) / 0.03) else 0.0
            val v = (lp * 0.7 + tone) * env * 0.8 + hit * 0.6
            l[i] = v.toFloat()
            r[i] = (v * (1 - 0.3 * x)).toFloat()
        }
    }

    /** A struck bell: a tone and an inharmonic partial, decaying apart. */
    private fun ding(l: FloatArray, r: FloatArray) {
        for (i in l.indices) {
            val x = i.toDouble() / SAMPLE_RATE
            val v = sin(2 * PI * 1318.5 * x) * exp(-x * 2.5) +
                0.4 * sin(2 * PI * 1318.5 * 2.76 * x) * exp(-x * 6.0) +
                0.2 * sin(2 * PI * 1318.5 * 5.4 * x) * exp(-x * 12.0)
            val attack = minOf(1.0, x * 800)
            l[i] = (v * attack * 0.6).toFloat()
            r[i] = (v * attack * 0.6).toFloat()
        }
    }

    // ---- The library's generators (24 October) ---------------------------------
    //
    // Two building blocks - a tone that glides between two pitches, and noise
    // through a moving filter - and each sound below is a few of them laid
    // together. Every one still goes through release() and write(), so it ends
    // without a click and peaks where every other effect does.

    internal enum class Wave { Sine, Square, Saw, Triangle }

    private fun osc(w: Wave, cycles: Double): Double {
        val f = cycles - kotlin.math.floor(cycles)
        return when (w) {
            Wave.Sine -> sin(2 * PI * f)
            Wave.Square -> if (f < 0.5) 1.0 else -1.0
            Wave.Saw -> 2 * f - 1
            Wave.Triangle -> 4 * abs(f - 0.5) - 1
        }
    }

    /**
     * A tone from [f0] to [f1] Hz over [length] seconds starting at [at], the
     * pitch moving exponentially (so a glide sounds even to the ear). [soften]
     * is a one-pole low-pass on the wave, 1 for none. The last six
     * milliseconds taper, so a note inside a longer sound does not click.
     */
    private fun tone(
        l: FloatArray, r: FloatArray, at: Double, length: Double, f0: Double, f1: Double = f0,
        wave: Wave = Wave.Sine, level: Double = 0.5, decay: Double = 0.0, attack: Double = 0.004,
        vibHz: Double = 0.0, vibDepth: Double = 0.0, pan: Float = 0f, soften: Double = 1.0
    ) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        if (count <= 0) return
        val tail = (0.006 * SAMPLE_RATE).toInt().coerceIn(1, maxOf(1, count / 2))
        var cycles = 0.0
        var lp = 0.0
        for (n in 0 until count) {
            val t = n.toDouble() / SAMPLE_RATE
            val f = f0 * (f1 / f0).pow(n.toDouble() / count) * (1 + vibDepth * sin(2 * PI * vibHz * t))
            cycles += f / SAMPLE_RATE
            lp += (osc(wave, cycles) - lp) * soften
            val env = minOf(1.0, t / attack) * exp(-t * decay) * minOf(1.0, (count - n).toDouble() / tail)
            add(l, r, start + n, (lp * env * level).toFloat(), pan)
        }
    }

    /**
     * Noise over the whole of [l], through a one-pole low-pass whose
     * coefficient is [cutoff] at position 0..1, shaped by [env]. [band] takes
     * the low-pass's difference as well, which leaves a band rather than a
     * rumble. Left and right are separate noise, so it is wide.
     */
    private fun sweep(
        l: FloatArray, r: FloatArray, rng: Random, cutoff: (Double) -> Double, env: (Double) -> Double,
        level: Double, band: Boolean = false
    ) = noiseLayer(l, r, rng, 0.0, l.size.toDouble() / SAMPLE_RATE, cutoff, env, level, band)

    private fun noiseLayer(
        l: FloatArray, r: FloatArray, rng: Random, at: Double, length: Double,
        cutoff: (Double) -> Double, env: (Double) -> Double, level: Double, band: Boolean = false
    ) {
        val start = (at * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        var lpL = 0.0
        var lpR = 0.0
        var prevL = 0.0
        var prevR = 0.0
        for (n in 0 until count) {
            val i = start + n
            if (i !in l.indices) break
            val x = n.toDouble() / count
            val c = cutoff(x).coerceIn(0.001, 1.0)
            lpL += ((rng.nextFloat() * 2 - 1) - lpL) * c
            lpR += ((rng.nextFloat() * 2 - 1) - lpR) * c
            val outL = if (band) (lpL - prevL) * 3 else lpL
            val outR = if (band) (lpR - prevR) * 3 else lpR
            prevL = lpL
            prevR = lpR
            val g = env(x) * level
            l[i] += (outL * g).toFloat()
            r[i] += (outR * g).toFloat()
        }
    }

    private fun deepWhoosh(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { x -> 0.008 + 0.08 * sin(PI * x) }, { x -> sin(PI * x.pow(0.8)) }, 1.6)
        tone(l, r, 0.0, 1.2, 80.0, 45.0, level = 0.25, attack = 0.3)
    }

    /** Three notes of a chord slowing to a stop, the way a tape deck losing power does. */
    private fun tapeStop(l: FloatArray, r: FloatArray) {
        for ((k, hz) in listOf(220.0, 277.2, 329.6).withIndex()) {
            tone(l, r, 0.0, 1.0, hz, hz * 0.05, Wave.Saw, level = 0.22, decay = 0.6, soften = 0.2, pan = (k - 1) * 0.3f)
        }
    }

    /** Fast chirps sweeping down over a hiss: a cassette spooled backwards. */
    private fun rewind(l: FloatArray, r: FloatArray, rng: Random) {
        var t = 0.0
        while (t < 0.94) {
            tone(l, r, t, 0.05, 2400.0, 900.0, Wave.Saw, level = 0.16, soften = 0.3)
            t += 0.06
        }
        sweep(l, r, rng, { 0.5 }, { x -> 0.3 + 0.2 * sin(PI * x) }, 0.25, band = true)
    }

    private fun punch(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.4, 160.0, 45.0, level = 0.9, decay = 14.0, attack = 0.001)
        sweep(l, r, rng, { 0.7 }, { x -> exp(-x * 30) }, 0.7, band = true)
    }

    private fun slam(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.8, 110.0, 70.0, level = 0.8, decay = 9.0, attack = 0.001)
        tone(l, r, 0.0, 0.5, 240.0, wave = Wave.Triangle, level = 0.3, decay = 25.0, attack = 0.001)
        sweep(l, r, rng, { 0.1 }, { x -> exp(-x * 12) }, 1.0)
        // The latch rattling a moment after the door lands.
        noiseLayer(l, r, rng, 0.07, 0.08, { 0.6 }, { x -> exp(-x * 6) }, 0.25, band = true)
    }

    private fun thud(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.5, 90.0, 40.0, level = 1.0, decay = 10.0, attack = 0.002)
        sweep(l, r, rng, { 0.03 }, { x -> exp(-x * 10) }, 0.8)
    }

    /** The trailer hit: a falling sub, a long rumble and a crack on top. */
    private fun cinematicBoom(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 3.0, 70.0, 28.0, level = 1.0, decay = 1.2, attack = 0.002)
        tone(l, r, 0.0, 3.0, 35.0, level = 0.5, decay = 0.8, attack = 0.01)
        sweep(l, r, rng, { 0.01 }, { x -> exp(-x * 3) }, 1.5)
        sweep(l, r, rng, { 0.9 }, { x -> exp(-x * 60) }, 0.3, band = true)
    }

    private fun slash(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { x -> 0.1 + 0.7 * x }, { x -> sin(PI * x.pow(0.4)) * (1 - x) }, 1.2, band = true)
        tone(l, r, 0.12, 0.38, 2600.0, level = 0.25, decay = 8.0, attack = 0.002)
        tone(l, r, 0.12, 0.38, 3910.0, level = 0.15, decay = 10.0, attack = 0.002, pan = 0.3f)
    }

    /** Four falling notes, the last one wavering: the sound of a plan not working. */
    private fun trombone(l: FloatArray, r: FloatArray) {
        val notes = listOf(293.7, 277.2, 261.6)
        notes.forEachIndexed { k, hz ->
            tone(l, r, k * 0.45, 0.42, hz, wave = Wave.Saw, level = 0.4, attack = 0.03, soften = 0.15)
        }
        tone(l, r, 1.35, 0.65, 246.9, 240.0, Wave.Saw, level = 0.4, attack = 0.03, soften = 0.15, vibHz = 6.0, vibDepth = 0.03)
    }

    private fun squeak(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.12, 1400.0, 2600.0, level = 0.5, attack = 0.005)
        tone(l, r, 0.12, 0.13, 2600.0, 1800.0, level = 0.5, attack = 0.002)
    }

    private fun honk(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.5, 330.0, 310.0, Wave.Square, level = 0.35, decay = 1.0, attack = 0.01, soften = 0.25)
        tone(l, r, 0.0, 0.5, 415.0, 392.0, Wave.Saw, level = 0.3, decay = 1.0, attack = 0.01, soften = 0.25)
    }

    private fun bubbles(l: FloatArray, r: FloatArray, rng: Random) {
        repeat(9) {
            val at = rng.nextDouble() * 0.9
            val f0 = 300.0 + rng.nextDouble() * 500.0
            tone(l, r, at, 0.07, f0, f0 * 2.5, level = 0.5, decay = 20.0, attack = 0.002, pan = (rng.nextFloat() - 0.5f))
        }
    }

    private fun plop(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.3, 350.0, 120.0, level = 0.9, decay = 14.0, attack = 0.002)
        noiseLayer(l, r, rng, 0.0, 0.03, { 0.3 }, { x -> 1 - x }, 0.2)
    }

    private fun zip(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.6, 400.0, 3000.0, level = 0.5, decay = 2.0, attack = 0.01)
        sweep(l, r, rng, { 0.6 }, { x -> (1 - x) * 0.6 }, 0.3, band = true)
    }

    private fun notify(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.1, 1318.5, level = 0.5, decay = 12.0, attack = 0.002)
        tone(l, r, 0.1, 0.7, 1760.0, level = 0.5, decay = 6.0, attack = 0.002)
        tone(l, r, 0.1, 0.7, 3520.0, level = 0.1, decay = 12.0, attack = 0.002)
    }

    private fun success(l: FloatArray, r: FloatArray) {
        listOf(1046.5, 1318.5, 1568.0).forEachIndexed { k, hz ->
            tone(l, r, k * 0.1, 0.9 - k * 0.1, hz, wave = Wave.Triangle, level = 0.4, decay = 6.0, attack = 0.003)
        }
    }

    private fun errorBuzz(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.18, 196.0, wave = Wave.Square, level = 0.35, soften = 0.3)
        tone(l, r, 0.22, 0.3, 185.0, wave = Wave.Square, level = 0.35, soften = 0.3)
    }

    private fun message(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.08, 880.0, 1320.0, level = 0.5, attack = 0.002)
        tone(l, r, 0.08, 0.42, 1320.0, level = 0.5, decay = 10.0, attack = 0.002)
    }

    private fun tap(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 0.1, 2400.0, 1800.0, level = 0.6, decay = 80.0, attack = 0.0005)
        noiseLayer(l, r, rng, 0.0, 0.01, { 0.8 }, { x -> 1 - x }, 0.3, band = true)
    }

    private fun switchClick(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.04, 3000.0, wave = Wave.Triangle, level = 0.6, decay = 120.0, attack = 0.0005)
        tone(l, r, 0.06, 0.06, 2000.0, wave = Wave.Triangle, level = 0.5, decay = 120.0, attack = 0.0005)
    }

    /** A rattle of the keys and the drawer, then the bell. */
    private fun cash(l: FloatArray, r: FloatArray, rng: Random) {
        var t = 0.0
        while (t < 0.22) {
            noiseLayer(l, r, rng, t, 0.015, { 0.7 }, { x -> 1 - x }, 0.35, band = true)
            t += 0.03 + rng.nextDouble() * 0.02
        }
        tone(l, r, 0.25, 0.95, 2093.0, level = 0.5, decay = 3.0, attack = 0.002)
        tone(l, r, 0.25, 0.95, 2093.0 * 2.76, level = 0.18, decay = 7.0, attack = 0.002)
        noiseLayer(l, r, rng, 0.3, 0.5, { 0.05 }, { x -> sin(PI * x) }, 0.5)
    }

    private fun tickTock(l: FloatArray, r: FloatArray) {
        for (k in 0 until 4) {
            val hz = if (k % 2 == 0) 2000.0 else 1500.0
            tone(l, r, k * 0.5, 0.05, hz, wave = Wave.Triangle, level = 0.6, decay = 90.0, attack = 0.0005, pan = if (k % 2 == 0) -0.2f else 0.2f)
        }
    }

    private fun coin(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.08, 988.0, wave = Wave.Square, level = 0.3, soften = 0.6)
        tone(l, r, 0.08, 0.42, 1319.0, wave = Wave.Square, level = 0.3, decay = 5.0, soften = 0.6)
    }

    private fun powerUp(l: FloatArray, r: FloatArray) {
        val notes = listOf(60, 64, 67, 72, 64, 67, 72, 76, 67, 72, 76, 79)
        notes.forEachIndexed { k, m ->
            val hz = midiHz(m + 12)
            tone(l, r, k * 0.075, 0.08, hz, hz * 1.03, Wave.Square, level = 0.25, soften = 0.5)
        }
    }

    private fun laser(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.4, 1800.0, 150.0, Wave.Saw, level = 0.35, decay = 6.0, soften = 0.5, pan = -0.2f)
        tone(l, r, 0.0, 0.4, 1830.0, 160.0, Wave.Saw, level = 0.25, decay = 6.0, soften = 0.5, pan = 0.2f)
    }

    private fun gameOver(l: FloatArray, r: FloatArray) {
        listOf(392.0, 370.0, 349.2).forEachIndexed { k, hz ->
            tone(l, r, k * 0.3, 0.28, hz, wave = Wave.Square, level = 0.3, soften = 0.4)
        }
        tone(l, r, 0.9, 0.7, 329.6, 320.0, Wave.Square, level = 0.3, decay = 1.5, soften = 0.4, vibHz = 7.0, vibDepth = 0.02)
    }

    private fun levelUp(l: FloatArray, r: FloatArray) {
        listOf(72, 76, 79).forEachIndexed { k, m -> tone(l, r, k * 0.07, 0.07, midiHz(m), wave = Wave.Triangle, level = 0.45) }
        listOf(84, 88, 91).forEach { m -> tone(l, r, 0.21, 0.99, midiHz(m), wave = Wave.Triangle, level = 0.25, decay = 3.0) }
    }

    /** Noise held for longer and longer stretches as it fades: a blast in eight bits. */
    private fun pixelExplosion(l: FloatArray, r: FloatArray, rng: Random) {
        var hold = 0.0
        var left = 0
        for (i in l.indices) {
            val x = i.toDouble() / l.size
            if (left <= 0) {
                hold = rng.nextDouble() * 2 - 1
                left = (2 + x * 40).toInt()
            }
            left--
            // Crushed to sixteen levels, which is most of what makes it sound old.
            val v = (kotlin.math.round(hold * 8) / 8) * exp(-x * 4) * minOf(1.0, i / 64.0)
            l[i] += (v * 0.8).toFloat()
            r[i] += (v * 0.8).toFloat()
        }
    }

    private fun gong(l: FloatArray, r: FloatArray, rng: Random) {
        val partials = listOf(130.0 to 1.0, 197.0 to 0.7, 266.0 to 0.55, 349.0 to 0.4, 513.0 to 0.3, 698.0 to 0.2, 931.0 to 0.12)
        partials.forEachIndexed { p, (hz, g) ->
            tone(l, r, 0.0, 3.0, hz, hz * 0.995, level = g * 0.35, decay = 0.8 + p * 0.4, attack = 0.01,
                vibHz = 0.7 + p * 0.3, vibDepth = 0.002, pan = if (p % 2 == 0) -0.2f else 0.2f)
        }
        sweep(l, r, rng, { 0.2 }, { x -> exp(-x * 80) }, 0.5)
    }

    private fun cymbal(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { 0.9 }, { x -> exp(-x * 4.5) * minOf(1.0, x * 1250) }, 1.0, band = true)
        sweep(l, r, rng, { 0.4 }, { x -> exp(-x * 2.5) * 0.4 }, 0.6, band = true)
    }

    /** Two octaves of C major plucked upwards, fast. */
    private fun harp(l: FloatArray, r: FloatArray, rng: Random) {
        val scale = listOf(0, 2, 4, 5, 7, 9, 11)
        for (k in 0 until 15) {
            val m = 60 + 12 * (k / 7) + scale[k % 7]
            pluck(l, r, k * 0.05, midiHz(m), rng, pan = (k / 14f - 0.5f))
        }
        // A plucked string's noise burst is not centred on zero, and fifteen of
        // them panned one way left this one sitting 1.5% off it (MusicSynthChecks).
        removeDc(l)
        removeDc(r)
    }

    private fun removeDc(x: FloatArray) {
        if (x.isEmpty()) return
        val mean = x.sum() / x.size
        for (i in x.indices) x[i] -= mean
    }

    /** A record pushed back and forth under the needle. */
    private fun scratch(l: FloatArray, r: FloatArray, rng: Random) {
        var lp = 0.0
        var cycles = 0.0
        for (i in l.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val rate = sin(2 * PI * 3.3 * t)
            val speed = abs(rate)
            lp += ((rng.nextFloat() * 2 - 1) - lp) * (0.05 + 0.5 * speed)
            cycles += (180 + 900 * speed) / SAMPLE_RATE
            val v = (lp * 1.4 + osc(Wave.Saw, cycles) * 0.3) * speed * minOf(1.0, t * 300)
            l[i] += v.toFloat()
            r[i] += v.toFloat()
        }
    }

    private fun orchestraHit(l: FloatArray, r: FloatArray, rng: Random) {
        listOf(48, 55, 60, 64, 67, 72).forEachIndexed { k, m ->
            tone(l, r, 0.0, 1.2, midiHz(m), wave = Wave.Saw, level = 0.18, decay = 4.0, attack = 0.005, soften = 0.3,
                pan = if (k % 2 == 0) -0.3f else 0.3f)
        }
        tone(l, r, 0.0, 1.2, 65.0, 60.0, level = 0.6, decay = 5.0, attack = 0.002)
        sweep(l, r, rng, { 0.3 }, { x -> exp(-x * 20) }, 0.6)
    }

    /** Three notes a semitone apart climbing two octaves, louder all the way. */
    private fun tensionRise(l: FloatArray, r: FloatArray, rng: Random) {
        val n = l.size
        val base = listOf(110.0, 116.5, 123.5)
        val cycles = DoubleArray(base.size)
        var lp = 0.0
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val env = x.pow(1.8)
            var s = 0.0
            base.forEachIndexed { k, hz ->
                cycles[k] += hz * 4.0.pow(x) / SAMPLE_RATE
                s += osc(Wave.Saw, cycles[k])
            }
            lp += (s / 3 - lp) * (0.03 + 0.3 * x)
            l[i] += (lp * env * 0.8).toFloat()
            r[i] += (lp * env * 0.8).toFloat()
        }
        sweep(l, r, rng, { x -> 0.05 + 0.4 * x }, { x -> x.pow(2.5) }, 0.4)
    }

    private fun chargeUp(l: FloatArray, r: FloatArray) {
        val n = l.size
        var cycles = 0.0
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val t = i.toDouble() / SAMPLE_RATE
            val f = 300 * 8.0.pow(x) * (1 + 0.05 * sin(2 * PI * 20 * t))
            cycles += f / SAMPLE_RATE
            val v = sin(2 * PI * cycles) * x.pow(1.2) * 0.5
            l[i] += v.toFloat()
            r[i] += v.toFloat()
        }
    }

    /** A crack, then a low rumble rolling in two or three swells. */
    private fun thunder(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { 0.8 }, { x -> exp(-x * 40) }, 0.6, band = true)
        sweep(l, r, rng, { x -> 0.004 + 0.01 * (1 - x) },
            { x -> minOf(1.0, x * 30) * exp(-x * 1.6) * (0.65 + 0.35 * sin(2 * PI * 2.2 * x)) }, 4.0)
    }

    private fun wind(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { x -> 0.01 + 0.03 * (0.5 + 0.5 * sin(2 * PI * 1.2 * x)) },
            { x -> sin(PI * x) * (0.7 + 0.3 * sin(2 * PI * 1.2 * x)) }, 3.0, band = true)
        tone(l, r, 0.0, 3.0, 520.0, 680.0, level = 0.04, attack = 0.8, vibHz = 0.5, vibDepth = 0.15)
    }

    /** A wash of hiss with drops landing in it, all over the stereo field. */
    private fun rain(l: FloatArray, r: FloatArray, rng: Random) {
        sweep(l, r, rng, { 0.35 }, { x -> minOf(1.0, x * 20, (1 - x) * 20) }, 0.3)
        repeat(420) {
            val at = rng.nextDouble() * 2.95
            val hz = 2000.0 + rng.nextDouble() * 3000.0
            tone(l, r, at, 0.02, hz, hz * 0.8, level = 0.08 + rng.nextDouble() * 0.12, decay = 300.0, attack = 0.0003,
                pan = rng.nextFloat() * 2 - 1)
        }
    }

    private fun bird(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.07, 2800.0, 4200.0, level = 0.5, decay = 10.0, attack = 0.003)
        tone(l, r, 0.12, 0.07, 2900.0, 4400.0, level = 0.5, decay = 10.0, attack = 0.003)
        tone(l, r, 0.4, 0.3, 3500.0, 3300.0, level = 0.4, attack = 0.01, vibHz = 30.0, vibDepth = 0.08)
    }

    private fun waterDrop(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 0.08, 500.0, 1500.0, level = 0.9, decay = 25.0, attack = 0.001)
        tone(l, r, 0.06, 0.34, 1500.0, 1450.0, level = 0.2, decay = 12.0, attack = 0.002)
    }

    private fun drone(l: FloatArray, r: FloatArray) {
        val env = { x: Double -> minOf(1.0, x / 0.2) * minOf(1.0, (1 - x) / 0.17) }
        val n = l.size
        val hz = listOf(55.0, 55.6, 82.4)
        val cycles = DoubleArray(hz.size)
        var lp = 0.0
        for (i in 0 until n) {
            val x = i.toDouble() / n
            var s = 0.0
            hz.forEachIndexed { k, f -> cycles[k] += f / SAMPLE_RATE; s += osc(Wave.Saw, cycles[k]) }
            lp += (s - lp) * (0.02 + 0.015 * sin(2 * PI * 0.5 * x))
            l[i] += (lp * env(x) * 0.5).toFloat()
            r[i] += (lp * env(x) * 0.5).toFloat()
        }
        tone(l, r, 0.0, 3.0, 27.5, level = 0.4, attack = 0.6)
    }

    /** A dissonant stab and a high screech over it: the jump scare. */
    private fun stinger(l: FloatArray, r: FloatArray, rng: Random) {
        listOf(60, 61, 66, 67).forEachIndexed { k, m ->
            tone(l, r, 0.0, 1.8, midiHz(m), wave = Wave.Saw, level = 0.18, decay = 2.5, attack = 0.004, soften = 0.3,
                pan = if (k % 2 == 0) -0.3f else 0.3f)
        }
        tone(l, r, 0.0, 1.8, 1400.0, wave = Wave.Saw, level = 0.12, decay = 2.0, attack = 0.01, soften = 0.2, vibHz = 7.0, vibDepth = 0.02)
        sweep(l, r, rng, { 0.4 }, { x -> exp(-x * 25) }, 0.6)
    }

    private fun ghost(l: FloatArray, r: FloatArray) {
        tone(l, r, 0.0, 1.0, 600.0, 900.0, level = 0.45, attack = 0.4, vibHz = 5.0, vibDepth = 0.04, pan = -0.3f)
        tone(l, r, 0.9, 1.1, 900.0, 500.0, level = 0.45, attack = 0.1, vibHz = 5.0, vibDepth = 0.04, pan = 0.3f)
    }

    private fun darkHit(l: FloatArray, r: FloatArray, rng: Random) {
        tone(l, r, 0.0, 1.5, 60.0, 35.0, level = 0.9, decay = 2.5, attack = 0.002)
        tone(l, r, 0.0, 1.5, 180.0, level = 0.25, decay = 3.0, attack = 0.002)
        tone(l, r, 0.0, 1.5, 267.0, level = 0.18, decay = 3.5, attack = 0.002)
        sweep(l, r, rng, { 0.05 }, { x -> exp(-x * 8) }, 1.0)
    }

    /** Renders [style] to a stereo 16-bit WAV at [out]. */
    fun render(style: Style, out: File) {
        val frames = style.seconds * SAMPLE_RATE
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val rng = Random(style.id.hashCode())

        val beat = 60.0 / style.bpm
        val step = beat / 4.0

        for (bar in 0 until style.bars) {
            val (root, minor) = style.chords[bar % style.chords.size]
            val third = if (minor) 3 else 4
            val chord = intArrayOf(root, root + third, root + 7, root + (if (minor) 10 else 11))
            val barStart = bar * 4 * beat
            // A lighter first bar and a fuller middle: the track builds and breathes.
            val intro = bar < 2
            val outro = bar >= style.bars - 1

            for (s in 0 until 16) {
                val swingDelay = if (s % 2 == 1) style.swing * step else 0.0
                val t = barStart + s * step + swingDelay
                if (!intro && style.kickPattern[s] == 'x') kick(left, right, t)
                if (!intro && style.snarePattern[s] == 'x') snare(left, right, t, rng)
                if (style.hatPattern[s] == 'x' && !outro) hat(left, right, t, rng, if (s % 4 == 0) 0.9f else 0.55f)
            }

            // Bass: the root, on the beat or in eighths.
            val bassNote = root - 24
            val bassSteps = if (style.bassEighths) (0 until 8).map { it * 2 } else listOf(0, 8)
            for (s in bassSteps) {
                bass(left, right, barStart + s * step, midiHz(bassNote), step * (if (style.bassEighths) 1.8 else 7.5))
            }

            if (style.pad) pad(left, right, barStart, 4 * beat, chord.map { midiHz(it) })

            // Lead: an arpeggio up the chord an octave higher, varied every other bar.
            if (style.leadKind != Lead.None && !intro) {
                val order = if (bar % 2 == 0) intArrayOf(0, 1, 2, 3, 2, 1, 2, 3) else intArrayOf(3, 2, 1, 2, 0, 2, 1, 3)
                for (i in 0 until 8) {
                    val note = chord[order[i]] + 12
                    val t = barStart + i * 2 * step
                    when (style.leadKind) {
                        Lead.Pluck -> pluck(left, right, t, midiHz(note), rng, pan = if (i % 2 == 0) -0.3f else 0.3f)
                        Lead.Bell -> if (i % 2 == 0) bell(left, right, t, midiHz(note + 12))
                        Lead.Square -> square(left, right, t, midiHz(note), step * 1.6)
                        Lead.None -> Unit
                    }
                }
            }
        }

        if (style.echo > 0f) echo(left, right, (beat * 0.75 * SAMPLE_RATE).toInt(), style.echo)
        if (style.crackle) crackle(left, right, rng)
        fadeOutTail(left, right)
        write(out, left, right)
    }

    // ---- Instruments ----------------------------------------------------------------

    private fun midiHz(note: Int): Double = 440.0 * 2.0.pow((note - 69) / 12.0)

    private fun add(l: FloatArray, r: FloatArray, i: Int, v: Float, pan: Float = 0f) {
        if (i !in l.indices) return
        l[i] += v * (1f - pan.coerceAtLeast(0f))
        r[i] += v * (1f + pan.coerceAtMost(0f))
    }

    private fun kick(l: FloatArray, r: FloatArray, t: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        var phase = 0.0
        for (n in 0 until (0.35 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val f = 48 + 110 * exp(-x * 28)
            phase += 2 * PI * f / SAMPLE_RATE
            add(l, r, start + n, (sin(phase) * exp(-x * 9) * 0.9).toFloat())
        }
    }

    private fun snare(l: FloatArray, r: FloatArray, t: Double, rng: Random) {
        val start = (t * SAMPLE_RATE).toInt()
        var prev = 0f
        for (n in 0 until (0.22 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            val tone = sin(2 * PI * 185 * x) * exp(-x * 30)
            add(l, r, start + n, ((hp * 0.35 * exp(-x * 18)) + tone * 0.25).toFloat())
        }
    }

    private fun hat(l: FloatArray, r: FloatArray, t: Double, rng: Random, level: Float) {
        val start = (t * SAMPLE_RATE).toInt()
        var prev = 0f
        for (n in 0 until (0.05 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2 - 1
            val hp = noise - prev
            prev = noise
            add(l, r, start + n, (hp * 0.12 * level * exp(-x * 70)).toFloat(), pan = 0.25f)
        }
    }

    private fun bass(l: FloatArray, r: FloatArray, t: Double, hz: Double, length: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        var lp = 0.0
        for (n in 0 until count) {
            val x = n.toDouble() / SAMPLE_RATE
            val saw = 2 * ((x * hz) % 1.0) - 1
            lp += (saw - lp) * 0.08
            val env = (minOf(1.0, x * 200) * exp(-x * 2.2)).coerceAtLeast(0.0) *
                (1 - (n.toDouble() / count).pow(8))
            add(l, r, start + n, (tanh(lp * 1.6) * env * 0.45).toFloat())
        }
    }

    private fun pad(l: FloatArray, r: FloatArray, t: Double, length: Double, notes: List<Double>) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        for ((k, hz) in notes.withIndex()) {
            var lp = 0.0
            val detune = 1.0 + (k - 1.5) * 0.003
            for (n in 0 until count) {
                val x = n.toDouble() / SAMPLE_RATE
                val saw = 2 * ((x * hz * detune) % 1.0) - 1
                lp += (saw - lp) * 0.02
                val env = minOf(1.0, x / 0.4) * minOf(1.0, (length - x) / 0.3).coerceAtLeast(0.0)
                add(l, r, start + n, (lp * env * 0.07).toFloat(), pan = if (k % 2 == 0) -0.4f else 0.4f)
            }
        }
    }

    /** Karplus-Strong: a burst of noise in a short damped loop - a plucked string. */
    private fun pluck(l: FloatArray, r: FloatArray, t: Double, hz: Double, rng: Random, pan: Float) {
        val start = (t * SAMPLE_RATE).toInt()
        val period = (SAMPLE_RATE / hz).toInt().coerceAtLeast(2)
        val buf = FloatArray(period) { rng.nextFloat() * 2 - 1 }
        var idx = 0
        for (n in 0 until (0.9 * SAMPLE_RATE).toInt()) {
            val next = (idx + 1) % period
            val v = buf[idx]
            buf[idx] = (v + buf[next]) * 0.4965f
            idx = next
            add(l, r, start + n, v * 0.22f, pan)
        }
    }

    private fun bell(l: FloatArray, r: FloatArray, t: Double, hz: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        for (n in 0 until (1.6 * SAMPLE_RATE).toInt()) {
            val x = n.toDouble() / SAMPLE_RATE
            val v = sin(2 * PI * hz * x) * exp(-x * 2.4) + 0.35 * sin(2 * PI * hz * 2.76 * x) * exp(-x * 5.0)
            add(l, r, start + n, (v * 0.13 * minOf(1.0, x * 400)).toFloat(), pan = 0.2f)
        }
    }

    private fun square(l: FloatArray, r: FloatArray, t: Double, hz: Double, length: Double) {
        val start = (t * SAMPLE_RATE).toInt()
        val count = (length * SAMPLE_RATE).toInt()
        var lp = 0.0
        for (n in 0 until count) {
            val x = n.toDouble() / SAMPLE_RATE
            val sq = if ((x * hz) % 1.0 < 0.5) 1.0 else -1.0
            lp += (sq - lp) * 0.12
            val env = minOf(1.0, x * 300) * exp(-x * 3.0)
            add(l, r, start + n, (lp * env * 0.08).toFloat(), pan = -0.2f)
        }
    }

    // ---- Mix ----------------------------------------------------------------------

    /** A ping-pong echo, so the lead has somewhere to live. */
    private fun echo(l: FloatArray, r: FloatArray, delay: Int, amount: Float) {
        if (delay <= 0) return
        for (i in delay until l.size) {
            l[i] += r[i - delay] * amount * 0.6f
            r[i] += l[i - delay] * amount * 0.6f
        }
    }

    private fun crackle(l: FloatArray, r: FloatArray, rng: Random) {
        for (i in l.indices) {
            val hiss = (rng.nextFloat() * 2 - 1) * 0.006f
            val pop = if (rng.nextInt(6000) == 0) (rng.nextFloat() - 0.5f) * 0.25f else 0f
            l[i] += hiss + pop
            r[i] += hiss + pop
        }
    }

    /**
     * The last two seconds of a track faded out, so it ends rather than stops.
     *
     * One edge, not both - the name said edges and it has only ever touched the
     * tail, which is right: a track is meant to start. [release] is the
     * millisecond-scale version for the effects, which are shorter than this
     * fade is long.
     */
    private fun fadeOutTail(l: FloatArray, r: FloatArray) {
        val fade = SAMPLE_RATE * 2
        for (i in 0 until minOf(fade, l.size)) {
            val g = i.toFloat() / fade
            val j = l.size - 1 - i
            l[j] *= g
            r[j] *= g
        }
    }

    private fun write(out: File, l: FloatArray, r: FloatArray) {
        // Normalised to a safe peak, then a gentle limiter, so every track sits at
        // the same loudness under a video.
        var peak = 1e-6f
        for (i in l.indices) peak = maxOf(peak, abs(l[i]), abs(r[i]))
        val gain = 0.85f / peak
        val tmp = File(out.parentFile, out.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered()).use { o ->
            val dataBytes = l.size * 4
            o.writeBytes("RIFF"); o.writeIntLe(36 + dataBytes); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.writeIntLe(16); o.writeShortLe(1); o.writeShortLe(2)
            o.writeIntLe(SAMPLE_RATE); o.writeIntLe(SAMPLE_RATE * 4); o.writeShortLe(4); o.writeShortLe(16)
            o.writeBytes("data"); o.writeIntLe(dataBytes)
            for (i in l.indices) {
                o.writeShortLe((tanh(l[i] * gain) * 32000).toInt())
                o.writeShortLe((tanh(r[i] * gain) * 32000).toInt())
            }
        }
        tmp.renameTo(out)
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
