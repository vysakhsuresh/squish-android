package com.squish.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import com.squish.app.ui.theme.SquishColors

/**
 * Every slider in Squish.
 *
 * Material 3 draws a "stop indicator" - a filled dot at the far end of the
 * track - on every slider since 1.3. On a form it reads as the end of the
 * range; on the full-screen scrub bar it reads as a *marker on the video*, a
 * bright dot sitting at 0:22 that nothing put there and nothing does. The six
 * places that drew a slider each drew it, so the dot was on every control in
 * the app. There is no colour that hides it (it takes the active colour over
 * the inactive track), so the track is drawn without it.
 *
 * Everything else is the same slider: the same thumb, the same gap, the same
 * accent on both the thumb and the part of the track behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquishSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accent: Color = SquishColors.Teal,
    /** The thumb, where it is not the accent - the scrub bar's is white over orange. */
    thumbColor: Color = accent,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * What this slider is for, in the words above it - "Brightness", "Volume",
     * "Strength" - and what it currently reads, in the same words the readout
     * beside it shows ("+0%", "1.5x").
     *
     * Both are sibling `Text`s in every wrapper that uses this, and sibling
     * text is not merged into a control's node, so every slider in the app came
     * back from `uiautomator` as a bare `SeekBar` with no name at all. On the
     * Adjust tab that is thirteen of them in a row, and a screen reader would
     * read a percentage without ever saying of what.
     *
     * The state is the *readout* rather than the raw value, because the readout
     * is what is on screen: the float behind "+0%" is 0.5 on a -1..1 range, and
     * announcing 0.5 would disagree with the number printed beside it.
     *
     * Put here rather than in a wrapper because there are two wrappers -
     * `LabeledSlider` and the Adjust tab's own - and a fix in one of them is a
     * fix in half the app. Found that way: the first attempt went into
     * `LabeledSlider`, and the Adjust tab's sliders came back unnamed anyway.
     */
    label: String? = null,
    readout: String? = null
) {
    val colors = SliderDefaults.colors(
        thumbColor = thumbColor,
        activeTrackColor = accent,
        inactiveTrackColor = SquishColors.Border
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = colors,
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = null
            )
        },
        modifier = modifier.semantics {
            label?.let { contentDescription = it }
            readout?.let { stateDescription = it }
        }
    )
}
