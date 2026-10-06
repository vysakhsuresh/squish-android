package com.squish.app.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.media.effects.AdjustField
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
import com.squish.app.media.effects.Look
import com.squish.app.media.effects.LookFamily
import com.squish.app.media.effects.Looks
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.hasValueKeyAt
import com.squish.app.timeline.valueAt
import com.squish.app.ui.components.SquishSlider
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * The filter library, for one clip: the Filters chip of Looks, and a clip's
 * own Filters tool.
 *
 * Every chip paints what the look actually does, by running the grade over a
 * reference ramp with the same maths the shaders use. That is the whole reason to
 * build the swatch from the grade rather than hand-picking a color per filter: a
 * hand-picked chip is a drawing of a promise, and it starts lying the moment a
 * look is retuned.
 *
 * A look is each clip's own now (Clip.lookId); Apply to all puts this one on
 * every picture of the clip's kind, the way CapCut's does.
 */
@Composable
fun FiltersPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    var family by rememberSaveable { mutableStateOf(LookFamily.Essentials) }
    val active = Looks.byId(clip.lookId)

    // Every look previewed on the frame you are stopped on, which is how the
    // choice is actually made - a swatch tells you a look is warm, the shot tells
    // you whether warm is right for this face, in this light. This clip's own
    // frame at the playhead, through its trim and its speed - or its first frame
    // when the playhead is off it.
    val at = state.playheadMs.coerceIn(clip.timelineStartMs, (clip.timelineEndMs - 1).coerceAtLeast(clip.timelineStartMs))
    val picture = viewModel.analysis.pictureOf(state, clip, at)
    val frame = rememberLookFrame(picture?.first, picture?.second ?: 0L)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Filters",
                "On ${state.shotName(clip)} · tap to apply, again to clear",
                icon = Icons.Filled.AutoAwesome,
                accent = SquishColors.Blue
            )

            // Five families no longer divide evenly into a phone's width, so the
            // row scrolls rather than squeezing "Essentials" down to an ellipsis.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                LookFamily.entries.forEach { entry ->
                    SelectableChip(
                        label = entry.label,
                        selected = family == entry,
                        accentColor = SquishColors.Blue,
                        onClick = { family = entry }
                    )
                }
            }

            val shown = Looks.catalog.filter { it.family == family || it.id == Looks.None.id }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                shown.forEach { look ->
                    LookChip(
                        frame = frame,
                        look = look,
                        // The chip previews at the strength you have dialled in, so
                        // the row re-reads correctly instead of advertising full
                        // strength for a look you have pulled back to a third.
                        intensity = if (look.id == clip.lookId) clip.lookIntensity else 1f,
                        selected = look.id == clip.lookId || (clip.lookId == null && look.id == Looks.None.id),
                        onClick = { viewModel.clips.setLook(clip.id, look.id.takeIf { it != Looks.None.id }) }
                    )
                }
            }
        }

        if (clip.lookId != null) {
            val onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs
            PanelSurface(accent = SquishColors.Blue) {
                PanelHeading(
                    if (clip.lookKeys.isNotEmpty() && onClip) "${active.label} at ${Timecode.format(state.playheadMs)}" else active.label,
                    "How far the look is dialled in",
                    icon = Icons.Filled.FilterVintage,
                    accent = SquishColors.Blue
                )
                // Keyed over the clip, like opacity and level: a look brought up
                // as a scene turns, or pulled off before a cut. The slider then
                // sets the strength at the playhead and both the preview and the
                // file read the keys off the clock.
                KeyframeButton(
                    keyed = clip.hasValueKeyAt(ValueTrack.Look, state.playheadMs, state.frameMs),
                    count = clip.lookKeys.size,
                    onClip = onClip,
                    accent = SquishColors.Blue,
                    onToggle = { viewModel.clips.toggleValueKey(clip.id, ValueTrack.Look) },
                    onClear = { viewModel.clips.clearValueKeys(clip.id, ValueTrack.Look) }
                )
                LabeledSlider(
                    "Strength", clip.valueAt(ValueTrack.Look, state.playheadMs), 0f..1f,
                    onFinished = viewModel::endGesture,
                    onChange = { viewModel.clips.setLookIntensity(clip.id, it) }
                )
            }
        }

        LutCard(clip, viewModel)

        ApplyToAllRow(clip, what = "look") { viewModel.clips.applyLookToAll(clip.id) }
    }
}

/**
 * An imported `.cube`, which is how a brand's own grade actually arrives.
 *
 * Under the filters rather than among them: a LUT is somebody else's finished
 * look, and the fifty here are ours. It goes on last of all, after the sliders
 * and the curve, which is what a grading LUT expects of the picture it is
 * handed.
 */
@Composable
private fun LutCard(clip: Clip, viewModel: EditorViewModel) {
    var problem by remember { mutableStateOf<String?>(null) }
    val known = remember(clip.adjust.lutFile) { com.squish.app.media.effects.LutStore.names() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.clips.importLut(clip.id, it) { why -> problem = why } }
    }
    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            "Your own LUT",
            "A .cube from any grading tool, on top of everything else",
            icon = Icons.Filled.Palette,
            accent = SquishColors.Blue
        )
        if (known.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                SelectableChip(
                    label = "None",
                    selected = clip.adjust.lutFile == null,
                    accentColor = SquishColors.Blue,
                    onClick = { viewModel.clips.setLut(clip.id, null) }
                )
                known.forEach { name ->
                    SelectableChip(
                        label = name.removeSuffix(".cube"),
                        selected = clip.adjust.lutFile == name,
                        accentColor = SquishColors.Blue,
                        onClick = { viewModel.clips.setLut(clip.id, name) }
                    )
                }
            }
        }
        com.squish.app.ui.components.SquishOutlinedButton(
            text = "Import a .cube",
            modifier = Modifier.fillMaxWidth(),
            // Any type: a .cube is text, and plenty of file browsers hand it
            // over as octet-stream or refuse to show it under a narrower filter.
            onClick = { problem = null; pick.launch(arrayOf("*/*")) }
        )
        problem?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Pink)
        }
        if (clip.adjust.lutFile != null) {
            LabeledSlider(
                "Strength", clip.adjust.lutStrength, 0f..1f,
                onFinished = viewModel::endGesture,
                onChange = { viewModel.clips.setLutStrength(clip.id, it) }
            )
            if (com.squish.app.media.effects.LutStore.get(clip.adjust.lutFile) == null) {
                Text(
                    "“${clip.adjust.lutFile}” is not on this phone any more, so nothing is applied.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Amber
                )
            }
        }
    }
}

/**
 * The colour sliders of one clip: the Adjust chip of Looks, and a clip's own
 * Adjust tool. Laid out as CapCut lays it: one row of chips - the thirteen
 * sliders, then the eight colours of the HSL wheel - and under it the one
 * control picked, with its own reset. Thirteen stacked sliders needed a
 * screen of scrolling to reach Vignette or Apply to all, and each was a
 * full-width drag inside a sheet that scrolled. A chip whose value has moved
 * carries a mark, so what is on is seen from the row. Every value is folded
 * with the look into one shader pass (Looks.grade), so however many are
 * moved the picture costs the same.
 */
@Composable
fun AdjustPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    val done = viewModel::endGesture
    // The chip chosen: a slider by its ordinal, or a colour of the wheel past them.
    // Kept on the view model, not in a rememberSaveable: the sheet leaves
    // composition when it closes, so a remembered chip went with it and the
    // row - twenty-four chips long - opened at the left every time.
    var chosen by rememberSaveable { mutableStateOf(viewModel.adjustChip) }
    LaunchedEffect(chosen) { viewModel.adjustChip = chosen }
    val fields = AdjustField.entries
    val field = fields.getOrNull(chosen)
    val band = HueBand.entries.getOrNull(chosen - fields.size)
    // Past the sliders and the wheel: the Curves tool, which is a square to
    // draw in rather than a slider, so it gets the control's whole room.
    val curveIndex = fields.size + HueBand.entries.size
    val onCurve = chosen == curveIndex
    // And past the curve, the three wheels, which are a disc to drag in.
    val wheelIndex = curveIndex + 1
    val onWheels = chosen == wheelIndex
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Adjust",
                "On ${state.shotName(clip)} · refines whatever filter is on",
                icon = Icons.Filled.Tune,
                accent = SquishColors.Blue
            )
            // One tap: exposure, white balance, contrast and colour measured off this shot.
            var autoWorking by remember(clip.id) { mutableStateOf(false) }
            var autoNote by remember(clip.id) { mutableStateOf<String?>(null) }
            com.squish.app.ui.components.SquishOutlinedButton(
                text = if (autoWorking) "Measuring the shot…" else "Auto adjust",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (autoWorking) return@SquishOutlinedButton
                    autoWorking = true
                    autoNote = null
                    viewModel.clips.autoAdjust(clip.id) { ok ->
                        autoWorking = false
                        autoNote = if (ok) "Exposure, white balance, contrast and colour set for this shot. Undo takes it back."
                        else "Couldn't read a frame of this shot."
                    }
                }
            )
            autoNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.Teal) }
            // Twenty-four chips in one scroller - fourteen sliders, the eight
            // wheel colours, Curves and Wheels - and the row always opened at
            // the left. Picking the one at the far end meant six flings, and
            // coming back to it meant six more. The chosen chip is scrolled to
            // when the sheet opens and whenever it changes, so the row lands
            // where the work is.
            val chips = rememberScrollState()
            var chipEnds by remember { mutableStateOf(mapOf<Int, Pair<Int, Int>>()) }
            // Where each chip sits in the un-scrolled row, reported as it is laid
            // out. Written only when it moves, or every layout pass would set
            // state and ask for another.
            val noteChip: (Int) -> Modifier = { i ->
                Modifier.onGloballyPositioned { c ->
                    val left = c.positionInParent().x.toInt()
                    val width = c.size.width
                    val had = chipEnds[i]
                    if (had == null || had.first != left || had.second != width) {
                        chipEnds = chipEnds + (i to (left to width))
                    }
                }
            }
            LaunchedEffect(chosen, chipEnds[chosen]) {
                chipEnds[chosen]?.let { (left, width) ->
                    val middle = left + width / 2 - chips.viewportSize / 2
                    chips.animateScrollTo(middle.coerceIn(0, chips.maxValue.coerceAtLeast(0)))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(chips)
            ) {
                fields.forEachIndexed { i, entry ->
                    val touched = entry.of(clip.adjust) != 0f
                    SelectableChip(
                        label = if (touched) "${entry.label} •" else entry.label,
                        selected = chosen == i,
                        accentColor = SquishColors.Blue,
                        modifier = noteChip(i),
                        onClick = { chosen = i }
                    )
                }
                // The wheel's colours, as swatches: a colour says what it is
                // better than its name does.
                HueBand.entries.forEachIndexed { i, entry ->
                    val index = fields.size + i
                    val touched = !clip.adjust.band(entry).isIdentity
                    // Named and selectable, and the chosen ring in white.
                    //
                    // clickable(onClickLabel) labels the *action*, not the
                    // node, so a screen reader landed on eight unlabelled
                    // circles whose state was announced nowhere - in a row
                    // whose other twenty members read "Exposure, selected".
                    // And the chosen ring was Primary, which on the Orange band
                    // is the swatch's own colour to within a shade, so nobody
                    // of any kind could see which band was open. The touched
                    // ring keeps the band's own colour at 2 dp; chosen is white
                    // at 3 dp, which reads on all eight.
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = chosen == index,
                                role = Role.RadioButton
                            ) { chosen = index }
                            .semantics {
                                contentDescription =
                                    if (touched) "${entry.label}, adjusted" else entry.label
                            }
                            .then(noteChip(index)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(entry.swatch))
                                .border(
                                    if (chosen == index) 3.dp else if (touched) 2.dp else 1.dp,
                                    when {
                                        chosen == index -> Color.White
                                        touched -> SquishColors.TextPrimary
                                        else -> SquishColors.Border
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
                SelectableChip(
                    label = if (clip.adjust.curve.isIdentity) "Curves" else "Curves •",
                    selected = onCurve,
                    accentColor = SquishColors.Blue,
                    modifier = noteChip(curveIndex),
                    onClick = { chosen = curveIndex }
                )
                SelectableChip(
                    label = if (clip.adjust.wheels.isIdentity) "Wheels" else "Wheels •",
                    selected = onWheels,
                    accentColor = SquishColors.Blue,
                    modifier = noteChip(wheelIndex),
                    onClick = { chosen = wheelIndex }
                )
            }
            when {
                onWheels -> ColorWheelsEditor(
                    wheels = clip.adjust.wheels,
                    onChange = { viewModel.clips.setWheels(clip.id, it) },
                    onFinished = done,
                    onReset = { viewModel.clips.resetWheels(clip.id) }
                )
                onCurve -> CurveEditor(
                    curve = clip.adjust.curve,
                    onChange = { viewModel.clips.setCurve(clip.id, it) },
                    onFinished = done,
                    onReset = { viewModel.clips.resetCurve(clip.id) }
                )
                field != null -> AdjustSlider(
                    label = field.label,
                    value = field.of(clip.adjust),
                    range = field.min..field.max,
                    readout = if (field == AdjustField.Hue) Readout.hueDegrees else Readout.percent(field.min..field.max),
                    onReset = { viewModel.clips.resetAdjustField(clip.id, field) },
                    onFinished = done,
                    onChange = { viewModel.clips.setAdjust(clip.id, field, it) }
                )
                band != null -> {
                    val values = clip.adjust.band(band)
                    Text(
                        "${band.label} · turn it, deepen it, lift it",
                        style = MaterialTheme.typography.labelMedium,
                        color = SquishColors.TextSecondary
                    )
                    AdjustSlider(
                        label = "Hue", value = values.hue, range = -1f..1f,
                        readout = Readout.bandDegrees,
                        onReset = { viewModel.clips.setHsl(clip.id, band, values.copy(hue = 0f), discrete = true) },
                        onFinished = done
                    ) { viewModel.clips.setHsl(clip.id, band, values.copy(hue = it)) }
                    AdjustSlider(
                        label = "Saturation", value = values.saturation, range = -1f..1f,
                        onReset = { viewModel.clips.setHsl(clip.id, band, values.copy(saturation = 0f), discrete = true) },
                        onFinished = done
                    ) { viewModel.clips.setHsl(clip.id, band, values.copy(saturation = it)) }
                    AdjustSlider(
                        label = "Luminance", value = values.luminance, range = -1f..1f,
                        onReset = { viewModel.clips.setHsl(clip.id, band, values.copy(luminance = 0f), discrete = true) },
                        onFinished = done
                    ) { viewModel.clips.setHsl(clip.id, band, values.copy(luminance = it)) }
                }
            }
        }

        ApplyToAllRow(clip, what = "adjustments") { viewModel.clips.applyAdjustToAll(clip.id) }
    }
}

/** A slider with a reset beside its value, shown once the value has moved off nothing. */
@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: (Float) -> String = Readout.percent(range),
    onReset: () -> Unit,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            // Held at Reset's height whether it shows or not: it appears the
            // moment a drag leaves zero, and a row that grew then would slide
            // the slider out from under the finger.
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value != 0f) {
                    com.squish.app.ui.components.TextAction(
                        "Reset",
                        color = SquishColors.TextMuted,
                        onClick = onReset
                    )
                }
                Text(readout(value), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
            }
        }
        SquishSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range
        )
    }
}

/** Apply to all: this clip's colour onto every picture of its kind, said in those words. */
@Composable
private fun ApplyToAllRow(clip: Clip, what: String, onApply: () -> Unit) {
    val kind = if (clip.isOverlay) "overlays" else "shots"
    SquishOutlinedButton(text = "Apply $what to all $kind", modifier = Modifier.fillMaxWidth(), onClick = onApply)
}

/**
 * A whole style in one tap: the Templates chip of Looks. It used to sit above
 * the filters under a tab called Looks, with the person cut-out between them;
 * the cut-out is the clip's Cutout tool now. The look goes on every shot.
 */
@Composable
fun TemplatesPanel(viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            "Templates",
            "Look, frame, effects and a title at once · undo to take it off",
            icon = Icons.Filled.AutoAwesome,
            accent = SquishColors.Blue
        )
        // Five shelves rather than one list of forty-two: a new person opens
        // this looking for the one that is near enough, and near enough is a
        // glance, not a scroll.
        var shelf by rememberSaveable { mutableStateOf(TemplateFamily.entries.first().name) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            TemplateFamily.entries.forEach {
                SelectableChip(
                    label = it.label,
                    selected = it.name == shelf,
                    accentColor = SquishColors.Blue,
                    onClick = { shelf = it.name }
                )
            }
        }
        Template.entries.filter { it.family.name == shelf }.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { template ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SquishColors.Background)
                            .border(1.dp, SquishColors.Border, RoundedCornerShape(14.dp))
                            .clickable { viewModel.clips.applyTemplate(template) }
                            .padding(12.dp)
                    ) {
                        GlyphTile(template.glyph, size = 40.dp)
                        Text(template.label, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                        Text(
                            template.blurb,
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(2 - row.size) { Column(modifier = Modifier.weight(1f)) {} }
            }
        }
    }
}

/** Level 0's Looks with no picture to work on: what it would need. */
@Composable
fun NoPicturePanel() {
    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            "Looks",
            "Add a video or a photo first",
            icon = Icons.Filled.AutoAwesome,
            accent = SquishColors.Blue
        )
        Text(
            "A look and the colour sliders belong to a shot. With one on the strip, they work on the shot under the playhead.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

@Composable
private fun LookChip(
    frame: LookFrame?,
    look: Look,
    intensity: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    // The swatch is the fallback, not the design. It is what shows for the second
    // before the frame arrives, and for an audio-only or unreadable source.
    val stops = remember(look.id, intensity) {
        Looks.swatch(look, intensity).map { Color(it) }
    }
    val preview = remember(frame, look.id, intensity) {
        frame?.graded(look.atIntensity(intensity))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.width(IntrinsicChipWidth)
            // selectable, not clickable: the chosen look was marked by a
            // border colour and nothing else, so a screen reader read the
            // applied filter exactly as it read the other twenty-nine.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(IntrinsicChipWidth, 52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) SquishColors.Blue else SquishColors.Border,
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                    drawRect(brush = Brush.verticalGradient(stops))
                }
            }
        }
        com.squish.app.ui.components.FitText(
            look.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SquishColors.TextPrimary else SquishColors.TextSecondary
        )
    }
}

private val IntrinsicChipWidth = 64.dp
