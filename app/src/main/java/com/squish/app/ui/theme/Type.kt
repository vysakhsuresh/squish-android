package com.squish.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.squish.app.R

/**
 * Space Grotesk, inherited from Layerlink - the same four weight files, bundled
 * rather than fetched, so bold is the real bold face and not a synthetic smear.
 *
 * One family throughout. Hierarchy comes from weight and size, which is what keeps
 * the interface quiet enough to put a video in front of.
 */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

val SquishTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
)

/**
 * The same style, with digits that all take the same width.
 *
 * For any number that changes while you watch it - a running timecode, a
 * countdown, a percentage. Ordinary typefaces set a 1 narrower than a 0, so a
 * readout counting upwards changes width several times a second and shoves
 * whatever sits beside it back and forth. On the editor's action bar that was
 * enough to squeeze a two-word button into two lines and back again, which read
 * as the whole app being unstable while a video played.
 *
 * `tnum` is an OpenType feature every font Android ships supports; on a face that
 * does not, it is ignored and nothing breaks.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
