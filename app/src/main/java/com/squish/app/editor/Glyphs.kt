package com.squish.app.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squish.app.tools.QuickTool
import com.squish.app.ui.icons.SquishIcons

/**
 * One of Squish's own marks ([SquishIcons]) on its gradient sticker - how the
 * app shows a template, a voice or a quick tool. Emoji render
 * differently on every phone and read as chat; the stock icon set read as a
 * settings screen. These are meant to look like something you'd slap on a clip.
 *
 * [tilt] leans alternate stickers opposite ways so a row looks stuck on by hand.
 */
data class Glyph(val icon: ImageVector, val from: Color, val to: Color, val tilt: Float = 0f, val stock: Boolean = false)

private val Tilts = floatArrayOf(-4f, 3f, -2f, 4f, -3f, 2f)

private fun glyph(icon: ImageVector, from: Long, to: Long, index: Int) =
    Glyph(icon, Color(from), Color(to), Tilts[index % Tilts.size])

/** A stock Material mark on the sticker, drawn white: Squish has six marks of its own and forty-two templates. */
private fun stock(icon: ImageVector, from: Long, to: Long, index: Int) =
    Glyph(icon, Color(from), Color(to), Tilts[index % Tilts.size], stock = true)

val Template.glyph: Glyph
    get() = when (this) {
        Template.Reel -> glyph(SquishIcons.Reel, 0xFFFF5F8F, 0xFF8B5CF6, ordinal)
        Template.Vlog -> glyph(SquishIcons.Vlog, 0xFFFFB547, 0xFFFF6A3D, ordinal)
        Template.Cinematic -> glyph(SquishIcons.Cinematic, 0xFF4F7BFF, 0xFF1E2A78, ordinal)
        Template.Retro -> glyph(SquishIcons.Retro, 0xFFFF9A62, 0xFFB5476E, ordinal)
        Template.Party -> glyph(SquishIcons.Party, 0xFF22E3C4, 0xFFB14DFF, ordinal)
        Template.Memories -> glyph(SquishIcons.Memories, 0xFFB8BCCB, 0xFF4A4E63, ordinal)
        Template.Travel -> stock(Icons.Filled.Flight, 0xFF3DD6E0, 0xFF1F8AC0, ordinal)
        Template.Birthday -> stock(Icons.Filled.Cake, 0xFFFFD166, 0xFFFF5FA2, ordinal)
        Template.Food -> stock(Icons.Filled.Restaurant, 0xFFFF9A3D, 0xFFC0392B, ordinal)
        Template.Fitness -> stock(Icons.Filled.FitnessCenter, 0xFFFF5B5B, 0xFF2B2F8F, ordinal)
        Template.Gaming -> stock(Icons.Filled.SportsEsports, 0xFF7CFF6B, 0xFF5B2DB0, ordinal)
        Template.Love -> stock(Icons.Filled.Favorite, 0xFFFF8FB1, 0xFFB14DFF, ordinal)
        Template.News -> stock(Icons.Filled.Newspaper, 0xFFE0E4F0, 0xFFC0392B, ordinal)
        Template.Sale -> stock(Icons.Filled.LocalOffer, 0xFFFFC53D, 0xFFFF6A3D, ordinal)

        // The twenty-eight added on 4 October. They wore six marks between
        // forty-two tiles until 24 October, when Polaroid was a television and
        // Podcast a camcorder; each subject has its own mark now, and the
        // colours are taken from the look the template applies.
        Template.Trailer -> stock(Icons.Filled.Theaters, 0xFF9AA7C7, 0xFF11162E, ordinal)
        Template.Thriller -> stock(Icons.Filled.Fingerprint, 0xFF5EC8D8, 0xFF16213A, ordinal)
        Template.Western -> stock(Icons.Filled.Landscape, 0xFFE0A765, 0xFF8A4A22, ordinal)
        Template.Noir -> stock(Icons.Filled.Contrast, 0xFFD6D9E3, 0xFF2A2C36, ordinal)
        Template.Documentary -> stock(Icons.Filled.Videocam, 0xFFBFC6D4, 0xFF4C5468, ordinal)
        Template.Wedding -> stock(Icons.Filled.Diamond, 0xFFF6D7C4, 0xFFC98D8D, ordinal)
        Template.Baby -> stock(Icons.Filled.ChildCare, 0xFFFFD9E8, 0xFFA5C8F0, ordinal)
        Template.Pets -> stock(Icons.Filled.Pets, 0xFFFFC46B, 0xFFE2605B, ordinal)
        Template.Sunset -> stock(Icons.Filled.WbTwilight, 0xFFFFB05C, 0xFFD2456B, ordinal)
        Template.Beach -> stock(Icons.Filled.BeachAccess, 0xFF6FE3E1, 0xFF2A7FD4, ordinal)
        Template.Nature -> stock(Icons.Filled.Park, 0xFF7ED98A, 0xFF1F6B4A, ordinal)
        Template.Desert -> stock(Icons.Filled.WbSunny, 0xFFF0C987, 0xFFB06B3C, ordinal)
        Template.Night -> stock(Icons.Filled.NightsStay, 0xFF6C7BFF, 0xFF11143A, ordinal)
        Template.City -> stock(Icons.Filled.LocationCity, 0xFF8FA8C8, 0xFF2B3550, ordinal)
        Template.Y2k -> stock(Icons.Filled.Album, 0xFF9CFFEB, 0xFFFF6BD6, ordinal)
        Template.Vhs -> stock(Icons.Filled.VideoLibrary, 0xFFB8A0FF, 0xFF4A2D7A, ordinal)
        Template.Super8 -> stock(Icons.Filled.CameraRoll, 0xFFE8B071, 0xFF8C5A2B, ordinal)
        Template.Polaroid -> glyph(SquishIcons.Memories, 0xFFF2E9D8, 0xFFA8A090, ordinal)
        Template.Crt -> stock(Icons.Filled.DesktopWindows, 0xFF77FF9E, 0xFF14321F, ordinal)
        Template.Trippy -> stock(Icons.Filled.Cyclone, 0xFFFF7BE5, 0xFF5BE1FF, ordinal)
        Template.Workout -> stock(Icons.Filled.DirectionsRun, 0xFF9EE6FF, 0xFF1E3A5F, ordinal)
        Template.Sport -> stock(Icons.Filled.SportsSoccer, 0xFFFFE066, 0xFF1F7A4C, ordinal)
        Template.Recipe -> stock(Icons.Filled.RestaurantMenu, 0xFFFFC078, 0xFFB03A2E, ordinal)
        Template.Product -> stock(Icons.Filled.ShoppingBag, 0xFFE9EEF7, 0xFF7B869C, ordinal)
        Template.Tutorial -> stock(Icons.Filled.Lightbulb, 0xFFFFE066, 0xFF7A6A1F, ordinal)
        Template.Podcast -> glyph(SquishIcons.Mic, 0xFFFFD2A1, 0xFF8A5A3C, ordinal)
        Template.Quote -> stock(Icons.Filled.FormatQuote, 0xFFD8D4CC, 0xFF5A564E, ordinal)
        Template.Follow -> stock(Icons.Filled.PersonAdd, 0xFFFF6BA8, 0xFFFFC46B, ordinal)
    }

/**
 * Effects wear the same plain line marks as the editor's tool rail, not
 * stickers: they sit in a grid of ten beside that rail and in a slim timeline
 * lane, where a quieter mark reads better than a crowd of little pictures.
 */
val EffectKind.icon: ImageVector
    get() = when (this) {
        EffectKind.Shake -> Icons.Filled.Vibration
        EffectKind.Punch -> Icons.Filled.CenterFocusStrong
        EffectKind.ZoomIn -> Icons.Filled.ZoomIn
        EffectKind.Glitch -> Icons.Filled.BrokenImage
        EffectKind.Flash -> Icons.Filled.FlashOn
        EffectKind.Vhs -> Icons.Filled.VideoLibrary
        EffectKind.Mono -> Icons.Filled.Contrast
        EffectKind.Invert -> Icons.Filled.InvertColors
        EffectKind.Blur -> Icons.Filled.BlurOn
        EffectKind.Rainbow -> Icons.Filled.Gradient
        EffectKind.RgbSplit -> Icons.Filled.Layers
        EffectKind.Strobe -> Icons.Filled.FlashAuto
        EffectKind.Earthquake -> Icons.Filled.Landslide
        EffectKind.Heartbeat -> Icons.Filled.Favorite
        EffectKind.Static -> Icons.Filled.Tv
        EffectKind.OldFilm -> Icons.Filled.Movie
        EffectKind.Dream -> Icons.Filled.Cloud
        EffectKind.NegativePulse -> Icons.Filled.Brightness6
        EffectKind.Trippy -> Icons.Filled.Cyclone
        EffectKind.Sway -> Icons.Filled.Waves
        EffectKind.ZoomOut -> Icons.Filled.ZoomOut
        EffectKind.LightLeak -> Icons.Filled.WbTwilight
        EffectKind.FilmBurn -> Icons.Filled.Whatshot
        EffectKind.Bokeh -> Icons.Filled.BubbleChart
        EffectKind.LensFlare -> Icons.Filled.Flare
        EffectKind.GlowPulse -> Icons.Filled.LightMode
        EffectKind.Vignette -> Icons.Filled.Vignette
        EffectKind.Snow -> Icons.Filled.AcUnit
        EffectKind.Rain -> Icons.Filled.Umbrella
        EffectKind.Sparkles -> Icons.Filled.AutoAwesome
        EffectKind.Hearts -> Icons.Filled.FavoriteBorder
        EffectKind.Confetti -> Icons.Filled.Celebration
        EffectKind.Dust -> Icons.Filled.Grain
        EffectKind.ZoomBlur -> Icons.Filled.FilterTiltShift
        EffectKind.Fisheye -> Icons.Filled.Lens
        EffectKind.Kaleidoscope -> Icons.Filled.FilterVintage
        EffectKind.Mirror -> Icons.Filled.Flip
        EffectKind.MirrorUp -> Icons.Filled.SwapVert
        EffectKind.Split2 -> Icons.Filled.GridView
        EffectKind.Split3 -> Icons.Filled.ViewModule
        EffectKind.Split4 -> Icons.Filled.Apps
        EffectKind.Ripple -> Icons.Filled.Water
        EffectKind.Pixelate -> Icons.Filled.Grid4x4
        EffectKind.VhsTracking -> Icons.Filled.Tune
        EffectKind.Super8 -> Icons.Filled.CameraRoll
        EffectKind.EightBit -> Icons.Filled.VideogameAsset
        EffectKind.Camcorder -> Icons.Filled.Videocam
        EffectKind.Sepia -> Icons.Filled.Coffee
        EffectKind.WashedOut -> Icons.Filled.Opacity
        EffectKind.BlockGlitch -> Icons.Filled.ViewQuilt
        EffectKind.Tear -> Icons.Filled.ContentCut
        EffectKind.ChromaWave -> Icons.Filled.GraphicEq
        EffectKind.Scramble -> Icons.Filled.Shuffle
        EffectKind.Shake3D -> Icons.Filled.Rotate90DegreesCw
        EffectKind.Spin -> Icons.Filled.RotateRight
        EffectKind.Pendulum -> Icons.Filled.Rotate90DegreesCcw
        EffectKind.BounceZoom -> Icons.Filled.SportsBasketball
        EffectKind.Duotone -> Icons.Filled.Tonality
        EffectKind.Posterize -> Icons.Filled.Palette
        EffectKind.Warm -> Icons.Filled.WbSunny
        EffectKind.Cool -> Icons.Filled.Thermostat
    }

/** Each effect's colour on the timeline, so neighbours on the one lane can be told apart. */
val EffectKind.color: Color
    get() = firstColours() ?: Color.hsv((ordinal * 137.5f) % 360f, 0.55f, 1f)

/**
 * The first twenty-one's own colours, kept as they were. The forty after them
 * take a hue each round the wheel by the golden angle, so neighbours in the
 * list - which are often neighbours on the lane - never share one.
 */
private fun EffectKind.firstColours(): Color? {
    val argb = when (this) {
            EffectKind.Shake -> 0xFFFF7A59
            EffectKind.Punch -> 0xFFFF5B7A
            EffectKind.ZoomIn -> 0xFF4FD1FF
            EffectKind.Glitch -> 0xFF3DE0C0
            EffectKind.Flash -> 0xFFFFC53D
            EffectKind.Vhs -> 0xFFFF9A62
            EffectKind.Mono -> 0xFFB8BCCB
            EffectKind.Invert -> 0xFFFF7AC6
            EffectKind.Blur -> 0xFF9AB6FF
            EffectKind.Rainbow -> 0xFFB14DFF
            EffectKind.RgbSplit -> 0xFFFF4F9A
            EffectKind.Strobe -> 0xFFFFF27A
            EffectKind.Earthquake -> 0xFFC9864A
            EffectKind.Heartbeat -> 0xFFFF4F6A
            EffectKind.Static -> 0xFF9AA3B8
            EffectKind.OldFilm -> 0xFFD9B37A
            EffectKind.Dream -> 0xFFC9B8FF
            EffectKind.NegativePulse -> 0xFF7AF0FF
            EffectKind.Trippy -> 0xFF7CFF6B
            EffectKind.Sway -> 0xFF6BC4FF
            EffectKind.ZoomOut -> 0xFF4FA8FF
            else -> return null
        }
    return Color(argb)
}

val VoiceEffect.glyph: Glyph
    get() = when (this) {
        VoiceEffect.None -> stock(Icons.Filled.Block, 0xFF8A90AE, 0xFF4A4F6E, ordinal)
        VoiceEffect.Enhance -> glyph(SquishIcons.Mic, 0xFF3DE0C0, 0xFF2F80ED, ordinal)
        VoiceEffect.Chipmunk -> glyph(SquishIcons.Chipmunk, 0xFF3DE0C0, 0xFF1F8A70, ordinal)
        VoiceEffect.Deep -> glyph(SquishIcons.Deep, 0xFF6C7BFF, 0xFF2B2F8F, ordinal)
        VoiceEffect.Robot -> glyph(SquishIcons.Robot, 0xFF4FA8FF, 0xFF2F4FC0, ordinal)
        VoiceEffect.Echo -> glyph(SquishIcons.Echo, 0xFFB14DFF, 0xFF5B2DB0, ordinal)
        VoiceEffect.Radio -> glyph(SquishIcons.Radio, 0xFFFF8FB1, 0xFFB14DFF, ordinal)
        VoiceEffect.Helium -> stock(Icons.Filled.BubbleChart, 0xFFFFD166, 0xFFFF8F3D, ordinal)
        VoiceEffect.Giant -> stock(Icons.Filled.Accessibility, 0xFF3DE0A0, 0xFF1F6A50, ordinal)
        VoiceEffect.Telephone -> stock(Icons.Filled.Phone, 0xFFB8BCCB, 0xFF4A4E63, ordinal)
        VoiceEffect.Megaphone -> stock(Icons.Filled.Campaign, 0xFFFF7A59, 0xFFC0392B, ordinal)
        VoiceEffect.Cave -> stock(Icons.Filled.Terrain, 0xFF6C7BFF, 0xFF1B1F5F, ordinal)
        VoiceEffect.Wobble -> stock(Icons.Filled.Waves, 0xFF5CE1E6, 0xFF2F80ED, ordinal)
        VoiceEffect.Alien -> stock(Icons.Filled.Public, 0xFF7CFF6B, 0xFF2F8F3D, ordinal)
    }

val QuickTool.glyph: Glyph
    get() = when (this) {
        QuickTool.Squeeze -> glyph(SquishIcons.Squeeze, 0xFF5B8CFF, 0xFF8B5CF6, ordinal)
        QuickTool.Snip -> glyph(SquishIcons.Snip, 0xFF2EE6D6, 0xFF2F80ED, ordinal)
        QuickTool.Rip -> glyph(SquishIcons.Rip, 0xFFFF9F3D, 0xFFFF4F6A, ordinal)
        QuickTool.Stitch -> glyph(SquishIcons.Stitch, 0xFFFF5FA2, 0xFFB14DFF, ordinal)
    }

private val StickerInk = Color(0xFF140C26)

/**
 * The sticker: a gradient squircle with an ink rim, a gloss along the top and
 * a hard shadow knocked down and right, leaning at the glyph's tilt.
 */
@Composable
fun GlyphTile(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = size * 0.72f,
    tilt: Float = glyph.tilt
) {
    val shape = RoundedCornerShape(size * 0.34f)
    val drop = (size.value * 0.06f).coerceAtLeast(1.5f).dp
    Box(modifier = modifier.size(size).rotate(tilt), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .offset(drop, drop)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.55f))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Brush.linearGradient(listOf(glyph.from, glyph.to)))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)))
                .border(1.5.dp, StickerInk, shape),
            contentAlignment = Alignment.Center
        ) {
            if (glyph.stock) {
                Image(
                    glyph.icon, contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                    modifier = Modifier.size(iconSize * 0.8f)
                )
            } else {
                Image(glyph.icon, contentDescription = null, modifier = Modifier.size(iconSize))
            }
        }
    }
}
