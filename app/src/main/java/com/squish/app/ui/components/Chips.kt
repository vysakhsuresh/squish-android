package com.squish.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    // Blue, not the orange of the primary action: a chip left on its default
    // was a second orange in a sheet whose Done is the orange one.
    accentColor: Color = SquishColors.Blue,
    /**
     * False for a choice that cannot be taken - a size past the encoder's
     * ceiling, a size larger than the file being squeezed.
     *
     * There was no such parameter, so a chip drawn dead (alpha 0.35) was still
     * wired live: it kept a real `selectable`, announced "not selected" rather
     * than "disabled", and swallowed a tap with no feedback at all. The drawn
     * state and the announced state disagreed. ToolBar.ToolItem has always
     * passed `enabled` into its clickable, one screen away.
     */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // A flat fill reads as a button that happens to be on; a sweep reads as the
    // chosen one. Since every panel in the app builds its options out of this, the
    // difference is the difference between the whole editor looking inert or alive.
    val scale by animateFloatAsState(if (selected) 1.03f else 1f, spring(), label = "chipScale")
    val border by animateColorAsState(
        if (selected) Color.Transparent else SquishColors.Border, label = "chipBorder"
    )
    val content by animateColorAsState(
        if (selected) SquishColors.Background else SquishColors.TextSecondary, label = "chipContent"
    )

    Box(
        modifier = modifier
            // Finger-sized: the chips are most of the editor's controls, and at
            // 37dp they were the most-missed.
            .heightIn(min = 44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.background(accentSweep(accentColor))
                else Modifier.background(SquishColors.Background)
            )
            .border(1.5.dp, border, RoundedCornerShape(12.dp))
            // selectable, not clickable: the sweep behind the chosen chip is
            // the only thing that said which one it was, and a colour is not an
            // answer to someone using a screen reader. This is the control
            // fifty-nine chip rows are built out of, so a plain clickable left
            // every one of them reading as a list of identical buttons.
            //
            // No role: one of these rows is a set of tabs, the next is one
            // value out of several, the next is a set of toggles, and the
            // state - which `selectable` speaks either way - is the thing that
            // was missing. A role would name two of the three wrongly.
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            // 8 at the sides: at 12, four to a row on a 360dp phone cut "Camera"
            // and "Bottom" short.
            .padding(vertical = 8.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ellipsis rather than the default clip: a label a few pixels too
        // wide for a narrow phone lost its last letter mid-glyph.
        Text(
            label,
            color = if (enabled) content else content.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SquishToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What this switch is for, in the words next to it - "Ticks when snapping",
     * "Keep HDR". Without it the node has a state and no name.
     *
     * The label is a sibling `Text` in every row that uses this, and sibling
     * text is not merged into a control's node, so a screen reader landing on
     * the switch said "on, switch, double tap to toggle" and never which
     * setting it had hold of. `uiautomator` flags exactly that as `NAF="true"`
     * - clickable, no text, no description - and did, on both switches of the
     * Settings screen, on 7 October.
     *
     * The sibling fix to the one above it: that one gave these nodes a *state*
     * where they had none, this gives them a *name*. Neither is any use alone.
     */
    label: String? = null,
    /** The track when on: a switch inside a tool takes that tool's colour. */
    accent: Color = SquishColors.Primary
) {
    val trackColor by animateColorAsState(if (checked) accent else SquishColors.Border, label = "switchTrack")
    Box(
        modifier = modifier
            // Drawn 44x26, pressed anywhere in a 48dp square round it.
            .minimumInteractiveComponentSize()
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(trackColor)
            // toggleable, not clickable with a Role: Role.Switch only makes a
            // screen reader say "switch", while the on or off it then says
            // comes from the node's ToggleableState, which only this sets. On
            // a clickable the whole app's switches announced themselves as
            // switches and never said which way they were - the colour of the
            // track was the only answer, which is no answer at all to someone
            // using TalkBack. Nine controls go through here and not one of
            // them carried semantics of its own.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .then(
                if (label == null) Modifier
                else Modifier.semantics { contentDescription = label }
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(SquishColors.TextPrimary)
        )
    }
}
