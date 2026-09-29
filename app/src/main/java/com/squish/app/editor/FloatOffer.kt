package com.squish.app.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * What a cut-out or a key on a main-track shot shows through to, and the way
 * to put another shot there: "Float this clip", which lifts the shot onto an
 * overlay row *keeping its place and its moves* (LayerEdits.switchToOverlay
 * with keepPlacement), so the next shot slides in under the person.
 *
 * A hole in the video track shows black - the preview's base surface writes
 * alpha 1 (squish_fx_es2.glsl), so even over a padded canvas it cannot show
 * the backdrop the file would - which is why the Cut out chip and the key
 * buttons are not offered on a shot. The button is offered only when a shot
 * follows this one: the last shot floated hangs past the track's end over
 * nothing, and the only shot floated empties the track.
 *
 * [hole] names what the sheet is cutting, in the sentence's own words: "the
 * cut-out", "the keyed colour".
 */
@Composable
fun FloatOffer(state: EditorUiState, clip: Clip, viewModel: EditorViewModel, hole: String, forColour: String? = null) {
    val follows = OverlayRules.floatsOverAShot(state.videoClips, clip.id)
    val colour = forColour?.let { " $it" } ?: ""
    Text(
        if (follows)
            "Nothing is under the video track, so $hole shows black here. Float this clip over the next shot " +
                "for that shot to show through: it keeps its place and its moves.$colour"
        else
            "Nothing is under the video track, so $hole shows black here. Put a shot after this one and " +
                "float this clip over it.$colour",
        style = MaterialTheme.typography.bodySmall,
        color = SquishColors.TextMuted
    )
    if (follows) {
        SquishOutlinedButton(text = "Float this clip", modifier = Modifier.fillMaxWidth()) {
            viewModel.layers.switchToOverlay(clip.id, keepPlacement = true)
        }
    }
}
