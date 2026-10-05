# Visual tools: filters/looks, adjust, effects library, transitions, speed, crop/rotate, canvas, masks, chroma key, keyframes/motion, stabilize, background removal, auto-reframe

> **Historical. 28 September 2026, and almost none of it is true any more.**
> This was the audit against CapCut that the build plan came out of, written
> before batches B1–B16 existed. It is kept because the *reasoning* in it is
> what `docs/ROADMAP.md` was built from, and because a few of its "gap" entries
> are still open — but every statement about what the app does or does not have
> is seven batches out of date. For what is built, read `ARCHITECTURE.md` §5;
> for what is unseen on a device, `CLAUDE.md` and `docs/DEVICE_FINDINGS.md`.


Read-only audit of the visual-tool panels and the render paths behind them (EffectsPanel/FxPanel/TransitionPanel/SpeedPanel/MotionPanel/MaskPanel/ChromaKeyPanel/BackgroundPanel/CropOverlay/CustomCropOverlay, media/effects, PreviewEngine, VideoProcessor, CompositionFactory, ProjectAutosave, EditorViewModel). The rendering foundations are strong (shared shaders between preview and export, honest look thumbnails, per-frame effect hooks), but there are several places where what the preview shows is not what the export writes (overlay grading, saturation maths, keyframes on retimed clips, rotation order, mixed-aspect surfaces), a data-loss hole (custom crop never persisted), an undo hole (masks, chroma key, keyframes, presets, transitions, stabilize and speed points are not undoable), and a playback-stall hole (every mask/chroma slider tick stops and reloads the player). Against CapCut the biggest structural gaps are: everything colour/crop/rotation is global rather than per clip, there is no canvas/background (padded 9:16 with blurred fill), no direct manipulation of clips or overlays on the preview, a 3-slider Adjust, no in/out/combo animations with duration, keyframes only for transform, and overlays that cannot be photos, keep no audio and have no blend modes. 22 bugs, 16 gaps, 12 UX items follow, most severe first within each kind.

## gap · high · L — Filters and Adjust are global to the whole edit, not per clip

**Detail:** `lookId`, `lookIntensity`, `brightness`, `contrast`, `saturation`, `cropAspect`, `cropRect` and `rotationDegrees` live on `EditorUiState`, and `buildVideoEffects` applies the same grade to every base clip. A two-camera edit cannot warm one shot and cool the other, and mixing a photo with footage cannot grade them separately. ARCHITECTURE lists per-clip looks as a 2-3 day item.

**CapCut:** Filter and Adjust are clip tools; each has an 'Apply to all' button, and a filter can also be dropped as its own track-level layer spanning any range.

**Files:** app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/data/ProjectAutosave.kt

## gap · high · M — Adjust has 3 sliders; CapCut has 12+ (exposure, temperature, tint, highlights, shadows, sharpen, vignette, hue, fade, grain, HSL)

**Detail:** Only brightness (implemented as a gain, so -100% is black), contrast and saturation exist. The look shader already carries fade, split-tone, bloom, vignette and grain uniforms, so exposing those five as manual sliders is mostly UI + folding into `Looks.grade`; temperature/tint (a channel-gain pair is already there), highlights/shadows, sharpen and HSL need shader additions. No 'reset' per slider.

**CapCut:** Adjust: Brightness, Contrast, Saturation, Exposure, Sharpen, Highlights, Shadows, Temperature, Tint, Hue, Fade, Vignette, Grain, plus HSL per-colour and Curves; each with a reset and 'Apply to all'.

**Files:** app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/media/effects/Look.kt, app/src/main/assets/squish_look_es2.glsl, app/src/main/java/com/squish/app/media/effects/LookEffect.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## gap · high · M — No canvas: no ratio with padded background (colour / blurred copy / image)

**Detail:** Choosing 9:16 always centre-crops (`Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP`); there is no way to put landscape footage on a 9:16 canvas with a blurred, coloured or image fill, which is the standard way reels are made from horizontal clips. The compositor path already places layers on a canvas, so a base 'canvas' layer with the blurred copy underneath is the natural build.

**CapCut:** 'Ratio' sets the canvas (9:16, 16:9, 1:1, 4:3, 3:4, 2:1, 2.35:1, 1.85:1, 4:5...) and 'Background' fills it with a colour, a blur of the clip, or an image.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## gap · high · M — Crop: fixed ratios are always centred, few ratios, no free straighten, no flip or mirror, rotation only in 90-degree cycles

**Detail:** `CropRect.centred` is the only placement for 9:16/1:1/16:9 (the window cannot be dragged; auto-reframe is the sole alternative). Ratios offered: Original, 9:16, 1:1, 16:9, Custom. Rotation is one 'Rotate 90' button that cycles clockwise; there is no horizontal/vertical flip, no mirror, and no fine straighten angle with a dial, even though `ScaleAndRotateTransformation` and the transform matrix support arbitrary angles. Crop is also global (see per-clip gap).

**CapCut:** Crop screen: draggable window for every ratio, ratios Free/9:16/16:9/1:1/4:3/3:4/2:1/2.35:1, a straighten dial (-45..45 degrees), rotate and flip buttons, 'Reset'; Mirror and Rotate are also one-tap clip tools.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/editor/CropRect.kt, app/src/main/java/com/squish/app/editor/CropOverlay.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt

## gap · high · L — No direct manipulation on the preview: clips, overlays, masks and the chroma picker are slider-only

**Detail:** `TimelinePreview` handles a single tap (play/pause) and nothing else. Positioning an overlay means four sliders in Blend or Motion; scaling means a slider; the mask has no on-screen shape or handles by design; the chroma-key colour is picked on a separate thumbnail in the panel. This is the interaction CapCut users reach for first, and it is the owner's specific complaint about overlays.

**CapCut:** Selected clip/overlay shows a bounding box on the preview: drag to move, pinch to scale, two-finger rotate, corner handle to scale/rotate, tap to select layers; mask shows its outline with feather and size handles; chroma key shows a draggable colour-picker loupe on the picture.

**Files:** app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/MaskPanel.kt, app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · high · L — Overlay model: video only, audio always stripped, no blend modes, no in/out animations, no split-screen presets

**Detail:** The overlay picker is `VideoOnly` (no photo or PNG logo), the export always `setRemoveAudio(true)` for overlays and the preview mutes them, there are no blend modes (the compositor only has alpha), no fade/slide in-out for a layer, and no 'split screen' presets. Overlay keyframes exist but only through Motion.

**CapCut:** Overlay accepts videos and photos; overlay audio is kept with its own volume; Blend modes: Normal, Multiply, Screen, Overlay, Darken, Lighten, Soft light, Hard light, Color burn, Color dodge, Difference, Exclusion, Hue...; Animation in/out/combo; Split screen layouts.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt

## gap · medium · M — No In / Out / Combo animations with a duration control; presets overwrite keyframes

**Detail:** Motion presets are six two-key moves that replace whatever keyframes were there and span the whole clip. There is no separate In and Out animation with its own duration slider, no fade-in/fade-out of opacity (keyframed alpha is listed in the roadmap), and no way to keep hand keyframes alongside a preset.

**CapCut:** Animation tool with In, Out and Combo tabs (Fade, Zoom, Slide, Spin, Flip, Bounce...), each with a duration slider and 'Apply to all'; animations sit on top of keyframes.

**Files:** app/src/main/java/com/squish/app/editor/MotionPreset.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/media/ClipTransformEffect.kt

## gap · high · L — Keyframes only for transform; diamonds on the strip are not interactive; no keyframe button in panels

**Detail:** Only scale/position/rotation animate. Opacity, volume, mask shape/position/feather, look intensity, Adjust values and effect strength are static per clip (`Mask` is 'static per clip for now', `AlphaScale` is fixed). The amber diamonds drawn on clips cannot be tapped to jump or dragged to retime; keys are managed only from the list in Motion.

**CapCut:** A keyframe diamond button in every clip panel (opacity, mask, filter, adjust, volume, transform); diamonds on the clip are tappable to jump, and a keyframe added at the playhead applies to whatever slider moves next.

**Evidence:** TimelineEditor.kt:1236-1248 (diamonds drawn only), Mask.kt (no time), CompositionFactory.kt:128 (fixed AlphaScale).

**Files:** app/src/main/java/com/squish/app/timeline/Keyframe.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/media/effects/MaskEffect.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt

## gap · medium · L — Transitions: 4 types, no thumbnails, no 'apply to all', length capped silently, none for overlays

**Detail:** Dissolve, Dip to black, Slide, Wipe only. Chips are text with no motion preview. The length slider allows up to 2000 ms but `withTransition`/`rippleVideo` silently cap at half the shorter clip, so the readout can say 2000 ms while 400 ms renders. No apply-to-all-cuts. Overlay layers cannot have transitions.

**CapCut:** Transition picker with animated thumbnails in categories (Basic, Camera, Glitch, Light, Mask, Movement, Split, Cover...), duration in seconds, 'Apply to all'.

**Evidence:** TransitionPanel.kt:104-115 vs TimelineModels.kt:218-219.

**Files:** app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt

## gap · medium · M — Speed: no draggable curve editor, points only at 4 fixed rates, max 10x, no pitch toggle, no freeze frame, no reverse

**Detail:** Points are dropped at the playhead at 0.25/0.5/1/2 only; an arbitrary rate at a point needs the flat slider first. Points cannot be dragged on the curve. `MAX_SPEED` is 10. Audio is always pitch-preserved (`SpeedChangingAudioProcessor`), with no 'Change pitch' option. There is no Freeze frame (a still clip from the frame under the playhead) and no Reverse.

**CapCut:** Speed > Normal: slider 0.1x-100x, 'Change voice' (pitch) toggle, 'Smooth slow-mo' (optical flow). Speed > Curve: Custom, Montage, Hero, Bullet, Jump cut, Flash in/out, with draggable points on a curve, add/delete point at the playhead. Freeze and Reverse are clip tools.

**Files:** app/src/main/java/com/squish/app/editor/SpeedPanel.kt, app/src/main/java/com/squish/app/timeline/SpeedRamp.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt

## gap · medium · L — Effects library: 10 effects with icon-only tiles, one Strength slider, no categories or previews

**Detail:** CapCut ships hundreds of effects in categories with animated thumbnails, each with 2-3 adjustable parameters, plus Body effects. Squish has Shake, Zoom punch, Slow zoom, Glitch, Flash, VHS, B&W, Invert, Blur, Rainbow; tiles show a Material icon; the only control is Strength, shown after placing. Effects are global to the picture and (see bug) not on overlays in the export.

**CapCut:** Effects tab (Video effects / Body effects) with categories Trending, Basic, Retro, Party, Dreamy, Glitch..., live preview on the frame, per-effect parameter sliders, effect placed as its own track clip and applicable to a single clip or the whole picture.

**Files:** app/src/main/java/com/squish/app/editor/FxPanel.kt, app/src/main/java/com/squish/app/editor/TimedEffect.kt, app/src/main/assets/squish_fx_es2.glsl, app/src/main/java/com/squish/app/media/effects/FxEffect.kt

## gap · medium · M — Masks: no on-preview handles, no heart/star, no keyframing, no mask on text

**Detail:** Four SDF shapes (rectangle, ellipse, linear, mirror) driven by eight sliders. No shape outline is drawn while adjusting, so a large feather makes it hard to tell where the edge is. `Mask` has no time dimension (the shader hook exists). Captions cannot be masked.

**CapCut:** Mask: Linear, Mirror, Circle, Rectangle, Heart, Star; drag to move, pinch to scale, rotate handle, feather handle, Invert; keyframeable.

**Files:** app/src/main/java/com/squish/app/editor/MaskPanel.kt, app/src/main/java/com/squish/app/timeline/Mask.kt, app/src/main/assets/squish_mask_es2.glsl, app/src/main/java/com/squish/app/editor/TimelinePreview.kt

## gap · medium · S — Chroma key: colour picked from a single pixel of a panel thumbnail; no Intensity/Shadow controls

**Detail:** `bitmap[px, py]` takes one pixel of compressed video, which is noisy; CapCut averages under a loupe dragged on the preview. Controls are Similarity/Edge softness/Spill; CapCut's Intensity and Shadow map roughly onto similarity and spill but the panel offers no way to tune the matte's black/white points.

**CapCut:** Chroma key: a colour-picker circle dragged on the preview (averaged), Intensity and Shadow sliders, Reset.

**Files:** app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/timeline/ChromaKey.kt, app/src/main/assets/squish_chroma_key_es2.glsl

## gap · low · L — Background removal has no manual cutout/brush and lives under Looks

**Detail:** CapCut groups Remove background (auto), Custom cutout (brush/erase with a stroke size) and Chroma key under one 'Cutout' tool on the clip. Squish's auto segmentation is a card inside the Looks tab, acts on 'the selected clip, else the first' without naming it, and has no manual correction when the segmenter misses hair or hands.

**CapCut:** Cutout > Remove background / Custom cutout (brush, erase, size, feather) / Chroma key.

**Files:** app/src/main/java/com/squish/app/editor/BackgroundPanel.kt, app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/media/video/Segmenter.kt

## gap · low · M — Stabilize strength requires a full re-analysis; CapCut previews levels instantly

**Detail:** The measured motions are discarded after `Stabilizer.analyze`; only the resulting keyframes are kept, so changing Strength means decoding the clip again ('Measure again at this strength'). Keeping the raw motion track per clip would let strength (and the crop it costs) be re-solved instantly through `TrajectorySmoother`.

**CapCut:** Stabilize: None / Minimal cropping / Recommended / Most stable, switching between them re-renders without re-analysing.

**Files:** app/src/main/java/com/squish/app/media/video/Stabilizer.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · low · M — No copy/paste of attributes between clips

**Detail:** Because looks/adjust/crop are global today there is nothing to copy, but once they are per clip (the CapCut model) a 'copy attributes / apply to all' path is needed for masks, chroma key, speed, animation and grading. Nothing in the timeline model or view model supports it.

**CapCut:** 'Apply to all' in Filter/Adjust/Speed/Animation/Transition; Copy and Paste of a clip carries its effects.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## ux · medium · L — Tool rail is 11 fixed tabs with tools filed under surprising names instead of a selection-driven toolbar

**Detail:** 'Blend' contains transitions, layer raise/lower, overlay geometry, Mask and Green screen; 'Looks' contains Templates, Background removal, Looks and Adjust; 'Motion' contains Stabilize, Object tracking, presets, placement and keyframes. Several tabs depend on a selection but stay visible with 'Nothing selected' placeholders. A user looking for Mask, Chroma key, Stabilize or Remove background has to guess the tab.

**CapCut:** A bottom toolbar that changes with selection: with nothing selected it shows Edit/Audio/Text/Stickers/Overlay/Effects/Filters/Adjust/Ratio/Background...; with a clip selected it shows Split/Speed/Animation/Delete/Filters/Adjust/Mask/Chroma key/Opacity/Crop/Stabilize/Remove BG/Volume/Extract audio/Freeze/Reverse/Replace.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt

## ux · low · S — Panels edit an implicit clip when nothing is selected, without saying which

**Detail:** Speed, Motion and Background fall back to the first clip (`speedTargetClip`, `targetVideoClip`, `backgroundTarget`) so the panel is 'never inert', but Background's heading never names the clip, Speed shows fps advice even for an audio clip, and the Frame/Looks panels are global while sitting next to per-clip ones with no cue about scope.

**Evidence:** BackgroundPanel.kt:39-49, SpeedPanel.kt:103, EditorViewModel.kt:728-730, 927-930.

**Files:** app/src/main/java/com/squish/app/editor/BackgroundPanel.kt, app/src/main/java/com/squish/app/editor/SpeedPanel.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · low · S — Ramp-shape and motion-preset chips never show which one is active; the Ramps hint is always the Bullet text

**Detail:** Both chip rows pass `selected = false`, so after tapping 'Slow in' nothing indicates it; SpeedPanel's hint line hard-codes `RampShape.entries.first { it.label == "Bullet" }.hint` regardless of choice.

**Evidence:** SpeedPanel.kt:165-183, MotionPanel.kt:75-94.

**Files:** app/src/main/java/com/squish/app/editor/SpeedPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt

## ux · low · S — Crop dim and thirds guides stay over the picture in every tab once a ratio is chosen

**Detail:** `CropOverlay` is drawn whenever `cropAspect != Original`, not only in the Frame tab, so the darkened surround and grid lines sit over the preview while editing captions, colour or sound.

**Evidence:** EditorScreen.kt:296-312.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/CropOverlay.kt

## ux · low · S — 'Brightness' is a multiplicative gain, so the bottom half of the slider is unusable

**Detail:** `Looks.grade` maps brightness to `gain = 1 + brightness`, so -100% is a black frame and -50% halves every pixel; +100% doubles and clips highlights. CapCut's Brightness is an offset/lift that keeps the picture readable across the range; the shader's `uFade` is closer to what users expect at the low end.

**Evidence:** Look.kt:354-358.

**Files:** app/src/main/java/com/squish/app/media/effects/Look.kt, app/src/main/java/com/squish/app/editor/EffectsPanel.kt

## ux · low · S — Sliders have no reset, no tap-to-type and no unit; 'Turn off'/'Remove' text links act instantly with no undo (see undo bug)

**Detail:** `LabeledSlider` is label + percent + track. There is no double-tap or reset icon to return to the default, no value entry, and the readout unit is always %. Mask/Green screen 'Turn off', stabilization 'Remove', effect 'Remove' and keyframe 'Clear' are small text links that discard the whole setup on one tap.

**CapCut:** Every panel has a Reset button and sliders show their value as they are dragged.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/MaskPanel.kt, app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt

## ux · low · S — Transition badge on the cut shows '✕' when active, which reads as 'remove' but opens the Blend panel

**Detail:** `TransitionBadge` draws '|' for none and '✕' for active; tapping either selects the clip and opens Blend. Users will expect the ✕ to clear the transition. CapCut shows a small transition icon on the join that opens the picker, and clears through 'None' in it.

**Evidence:** TimelineEditor.kt:960-984, EditorScreen.kt:334-337.

**Files:** app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · M — Effect tiles give no idea what an effect looks like; Start/End here buttons duplicate the lane's drag

**Detail:** FxPanel tiles are a Material icon plus a label. After placing, each effect gets a card with Strength and 'Start here'/'End here', while the effect lane on the strip already supports move and trim. The list of placed effects is not tied to the selected effect on the strip.

**CapCut:** Effect tiles are looping animated thumbnails; tapping previews it on the frame; the placed effect is a clip on its own lane with trim handles and a parameter sheet.

**Files:** app/src/main/java/com/squish/app/editor/FxPanel.kt, app/src/main/java/com/squish/app/editor/TimedEffect.kt

## ux · low · S — Templates silently replace crop, look and intensity and are only reversible by Undo

**Detail:** `applyTemplate` overwrites `cropAspect`, `lookId`, `lookIntensity`, removes any earlier template's title/effects, but leaves Adjust sliders and `cropRect`; the card's subtitle 'undo to take it off' is the only exit and templates live in the Looks tab.

**Evidence:** EditorViewModel.kt:1028-1065.

**Files:** app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/editor/Template.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · low · M — Mask Linear/Mirror give no cue which side or band is kept, and there is no outline while adjusting

**Detail:** The panel says 'Keeping the shape' / 'Hiding the shape' but for Linear the 'shape' is a half-plane whose side depends on rotation sign; with a wide feather the boundary is invisible on the preview. A thin outline of the SDF zero line drawn over the preview while the panel is open would make every slider legible.

**Evidence:** MaskPanel.kt:137-151, squish_mask_es2.glsl:95-98.

**Files:** app/src/main/java/com/squish/app/editor/MaskPanel.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · M — Chroma sampler shows a stale separate thumbnail with a Refresh button instead of the live picture

**Detail:** `FrameSampler` decodes a frame keyed on the clip id only, so after scrubbing the thumbnail is from the old moment until Refresh is pressed; the sampled pixel is single and unaveraged; the standard green/blue chips sit under it. Picking on the preview itself removes the thumbnail, the button and the staleness.

**Evidence:** ChromaKeyPanel.kt:131-162, 178-186.

**Files:** app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt

## ux · low · S — Speed and Blend panels expose internal units: 'ms' transition length, '0.35x' ramp points listed by timecode, and the fps verdict in three colours

**Detail:** Transition length reads '500 ms'; CapCut and every consumer editor show seconds with one decimal. Ramp points are a text list with timecodes and rates rather than draggable dots on the curve (the curve already draws them). The slow-motion advice is useful but its colour-coding (cyan/amber/pink) is not explained.

**Evidence:** TransitionPanel.kt:96-103, SpeedPanel.kt:212-242, 355-369.

**Files:** app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/SpeedPanel.kt

## bug · high · M — Undo does not cover masks, chroma key, keyframes, motion presets, transitions, overlay geometry, stabilization or speed points

**Detail:** Only edits routed through `record()` are undoable. These view-model entry points mutate state directly via `mutateTimeline`/`_state.update` and never call `record`: setTransition (1273), changeLayer (1276), setOverlayGeometry (1278), setChromaKey (1289), updateChromaKey (1295), setSpeedPointAtPlayhead (798), removeSpeedPoint (812), pinMaskToTrack (1591), unpinMask (1611), pinLayerToTrack (1646), stabilizeClip (1689), clearStabilization (1736), setMask (1750), updateMask (1754), setClipTransform (1797), addKeyframeAtPlayhead (1829), removeKeyframe (1837), setKeyframeEasing (1843), clearKeyframes (1852), applyMotionPreset (1866). The Undo button label still names the last *recorded* edit, so pressing it after one of these reverts something else entirely while the change the user just made stays. CapCut: every panel change is one undo step.

**CapCut:** Every slider/toggle in every panel is an undo step; the undo/redo arrows sit above the preview.

**Evidence:** The scenario outcome is worse than described, not "the preset remains". EditSnapshot (EditorModels.kt:239-258) captures `videoClips` wholesale, and keyframes, mask, chromaKey, transition, stabilizer and speedRamp all live on Clip. So: trim (recorded, snapshot = clips before trim) -> apply 'Push in' via applyMotionPreset (not recorded) -> tap Undo: `restoring()` (EditorModels.kt:498) replaces videoClips with the pre-trim list, which reverts BOTH the trim and the preset in one step while the button said only "Undo: Trim clip". Redo then brings both back together. There is no way to undo only the preset, or to keep the preset while undoing the trim. Same for every other unrecorded mutation: it is silently folded into whichever recorded step precedes it. Fix: wrap each entry point in record("<label>") like setClipSpeed/applyRampShape/clearSpeed already do (EditorViewModel.kt:785-825); for slider-driven ones (updateMask, updateChromaKey, setOverlayGeometry, setClipTransform) use a per-clip label so UndoStack's 700 ms coalescing (UndoStack.kt:50-62) turns a drag into one step. Note stabilizeClip's result lands asynchronously in a coroutine (1721), so its record() must happen there, not at launch. Files: app/src/main/java/com/squish/app/editor/EditorViewModel.kt (lines 741-747, 798-817, 1273-1311, 1591-1617, 1646-1675, 1689-1746, 1750-1877); UndoStack.kt needs no change.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/UndoStack.kt

## bug · high · S — Hand-drawn (Custom) crop is never autosaved: lost on process death, and a crop-only edit never triggers a save

**Detail:** `ProjectAutosave.encode` writes `cropAspect` but not `cropRect`; `ProjectSnapshot` has no cropRect field and `EditorViewModel.applying` cannot restore one. On recovery the state comes back as cropAspect=Custom with the default full rect, which `previewCropRatio` treats as no crop, so the export is uncropped. Because `editKey` is built from `encode`, dragging only the custom crop also never differs from `baseline`, so no draft is written at all for a crop-only session.

**Evidence:** ProjectAutosave.kt:226-273 (encode writes cropAspect at 239, no cropRect), 450 (decode reads only cropAspect), 619-645 (ProjectSnapshot has cropAspect at 638, no cropRect), 147-151 (editKey = encode minus playhead/zoom). EditorViewModel.kt:2223-2264 (applying sets cropAspect at 2254, never cropRect, so it stays the default full CropRect() from EditorModels.kt:351), 561-563 (setCropRect changes only cropRect, invisible to editKey), 130-138 (save gated on editKey != baseline). Consequence on restore: EditorModels.kt:426-431 previewCropRatio -> null for Custom+full rect; VideoProcessor.kt:509-527 skips Crop because crop.isFull and Custom.ratio is null (EditorModels.kt:76), so export is uncropped. Correction to the claim: selecting Custom from Original does change cropAspect, which is in the key, so a draft IS written for a crop-only session; it simply contains no rectangle, and any further drags of the rect never dirty the key. Scenario as stated (choose Custom, drag a tight crop, get killed, reopen) reproduces: Custom chip selected, whole frame kept, uncropped export. Fix touches ProjectAutosave.kt (encode/decode/ProjectSnapshot, bump FORMAT_VERSION) and EditorViewModel.kt (applying).

**Files:** app/src/main/java/com/squish/app/data/ProjectAutosave.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · high · S — Adding a transition leaves a black gap after the clip it was added to

**Detail:** `withTransition` pulls only the incoming clip back by the overlap and deliberately leaves everything else in place, so the clip that used to butt against its tail now starts `overlap` ms after it ends. The preview shows 'Gap - no clip here' (black) there and the export renders black, until the user finds 'Close gaps'. `resequenceAfterRetime` already solves this shape of problem for speed changes but is not used here.

**CapCut:** Adding a transition never opens a gap; downstream clips ripple with it.

**Evidence:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt:210-228 (`withTransition` sets `timelineStartMs = previous.timelineEndMs - overlap` on `clipId` only; followers untouched). app/src/main/java/com/squish/app/editor/EditorViewModel.kt:1273-1274 (`setTransition` = plain `mutateTimeline { it.withTransition(...) }`, no ripple and no `record`, so not undoable); EditorViewModel.kt:2008-2028 (`mutateTimeline` does not re-lay clips); EditorViewModel.kt:1933 (`rippleVideo` only reachable via the separate "Close gaps" action); EditorViewModel.kt:757-783 (`resequenceAfterRetime`, the existing follow-on logic, unused here). app/src/main/java/com/squish/app/editor/PreviewEngine.kt:693-707 (`composeBase`: no covering clip -> `inGap = true`); app/src/main/java/com/squish/app/editor/TimelinePreview.kt:262-273 (black "Gap — no clip here" overlay). app/src/main/java/com/squish/app/media/CompositionFactory.kt:79-90 (`buildComposited` emits `addGap(start - cursor)` before the following clip, so the export is black there). Scenario: three butted base clips each >= 1000 ms; apply Dissolve 500 ms to clip 2 -> clip 2 moves back 500 ms, clip 3 does not -> 500 ms black between clip 2's new end and clip 3 in both preview and exported file until "Close gaps" is pressed.

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt

## bug · high · M — Every mask or chroma-key slider tick stops the player, rebuilds the GL chain and reloads the file

**Detail:** `applySurfaceEffects` keys the effect chain on `"$chroma|$mask|$rotationDegrees"`; any change to a Mask or ChromaKey field produces a new signature, which stops the prepared player, calls setVideoEffects, forgets loadedUri and re-prepares/seeks. `LabeledSlider` calls onValueChange on every pixel of travel, so dragging Feather, Similarity, Width, Rotation etc. triggers dozens of stop/reload cycles per second - exactly the freeze the look shader (`LiveLookEffect`) and `BackgroundEffect` were rewritten to avoid. The 2-second `unstick` reload can also fire mid-drag. The ARCHITECTURE claim that 'the sliders move the finished result live' is not what the code does.

**Evidence:** Slider path: EditorPanels.kt:352-354 (Slider with continuous onValueChange, no onValueChangeFinished) used by MaskPanel.kt:102-188 (Obscure strength, Across, Up/down, Width, Height, Corner round, Rotation, Feather) and ChromaKeyPanel.kt:96-104 (Similarity, Edge softness, Spill removal). Each calls EditorViewModel.updateMask (1754-1784) / updateChromaKey (1295-1311) -> mutateTimeline (2008-2028), a synchronous state update producing a new Mask/ChromaKey (data classes, Mask.kt:43, ChromaKey.kt:14). TimelinePreview.kt:143-144 includes ":C${it.chromaKey}" and ":M${it.mask}" in editSignature; LaunchedEffect(editSignature) at 160-165 calls engine.setTimeline (PreviewEngine.kt:344-400), which swaps in the new Clip objects. On the next 33 ms tick (TimelinePreview.kt:403) syncSurface (PreviewEngine.kt:805-813) calls applySurfaceEffects; the signature at 488-490 differs, so it builds new ChromaKeyEffect/MaskEffect (494, 499), stops the prepared player (530-531), calls setVideoEffects (532), drops loadedUri/activeClip (533-536), and syncSurface then re-runs setMediaItem/prepare/seekTo (818-824). Shaders read uniforms only in init (MaskEffect.kt:66-84, ChromaKeyEffect.kt:61-64; only uCenter is refreshed per frame for tracked masks at MaskEffect.kt:114-120), so no live path exists. Rate bound: one rebuild per tick, i.e. up to ~30 stop/reload cycles per second during a drag, not one per slider callback. Unstick: each reload leaves the clock player in STATE_BUFFERING -> wedged (PreviewEngine.kt:639) -> unstick (968-991) fires after STALL_RELOAD_MS = 2000 ms (1012) if the drag keeps the player from reaching READY, doing stop/clearMediaItems/surface re-attach mid-drag. Contrast: applyGrade (445-449, comment 437-439) and BackgroundEffect via liveBackground (485-497) already use the AtomicReference-per-frame pattern; ARCHITECTURE.md:324-326 claims the mask sliders "move the finished result live". Fix touches PreviewEngine.kt (drop chroma/mask from the signature, hand AtomicReference<Mask?>/AtomicReference<ChromaKey?> to always-present effects), MaskEffect.kt and ChromaKeyEffect.kt (load uniforms in drawFrame from the reference, keep the value-constructor for export).

**Files:** app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/java/com/squish/app/media/effects/MaskEffect.kt, app/src/main/java/com/squish/app/media/effects/ChromaKeyEffect.kt

## bug · high · S — Motion sliders and 'Add key' bake the stabilizer's per-frame correction into the user transform

**Detail:** `setClipTransform` and `addKeyframeAtPlayhead` read `clip.transformAt(playhead)`, which is `composeTransform(...)` and therefore already includes the stabilizer's scale/offset/rotation for that frame. The composed value is then written back as the static transform or a keyframe, after which composeTransform applies the stabilizer again on top. Touching any Motion slider on a stabilized clip therefore doubles the crop zoom and freezes one frame's shake correction as a permanent offset/rotation; MotionPanel also displays these composed values as if they were the user's.

**Evidence:** EditorViewModel.kt:1806 (`val current = clip.transformAt(playhead)`) and 1810-1826 (composed values written back as static transform or keyframe); EditorViewModel.kt:1832-1833 (addKeyframeAtPlayhead stores composed `here` as a Keyframe); TimelineModels.kt:147-150 (Clip.transformAt = composeTransform, fix included); Keyframe.kt:110-118 (fix multiplied/added onto user); Stabilizer.kt:145-160 (stabilizer keys carry scale = 1 + crop and per-frame offsets/rotation); MotionPanel.kt:57 and the LabeledSliders below it (composed values shown as the user's). EditorViewModel.kt:2008-2028 mutateTimeline edits `videoClips`, the same clips that hold `stabilizer` (set at 1724). Scenario: Stabilize (scale 1.08, wobbling offsets), then nudge any Motion slider or tap Add key -> that frame's correction offset/rotation is baked into the user transform for the whole clip and scale becomes ~1.08 x 1.08 (a Scale nudge writes the displayed 1.08-ish value as user scale; any other slider writes exactly 1.08) in both preview and export; the panel then displays the doubled values.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/timeline/Keyframe.kt

## bug · high · S — Keyframes run on played time in the preview but on source time in the export for any retimed clip

**Detail:** In the export `ClipTransformEffect` is placed before `SpeedChangeEffect`, so `getMatrix` receives un-retimed timestamps and `tInClipMs` is a source offset; keyframes are defined in played (timeline-local) time and the preview evaluates them that way (`Clip.transformAt`). For an unramped clip both agree; for 0.5x the export only reaches half the animation, for 2x it finishes at the clip's midpoint and holds. (The stabilizer half of the same call is correct precisely because it wants source time, so the fix has to map one clock to the other via speedRamp.outputOffsetAt/sourceOffsetAt rather than reorder the chain.)

**Evidence:** VideoProcessor.kt:338-344 (ClipTransformEffect in `leading`, then `+ speedEffects(clip)` at :344; SpeedChangeEffect built at :368-373); ClipTransformEffect.kt:59-64 (tInClipMs from raw presentationTimeUs used as keyframe clock); Keyframe.kt:74-89 (holds at last key) and :103-116 (composeTransform: localMs for keyframes, stabilizerMs for stabilizer); TimelineModels.kt:139-150 (transformAt: played-time keyframes, source-time stabilizer; comment states the intent); PreviewEngine.kt:726-742, :797 (preview uses transformAt). Media3 1.5.1 sources (media3-effect-1.5.1-sources.jar): SpeedChangeShaderProgram.queueInputFrame rewrites presentationTimeUs before forwarding; DefaultShaderProgram.drawFrame passes the timestamp at its stage to getGlMatrixArray, so a MatrixTransformation before SpeedChangeEffect receives source-relative time. Also: CompositionFactory.kt:115-128 overlayEffects adds ClipTransformEffect with no SpeedChangeEffect at all, so a retimed overlay has the same source-vs-played mismatch. Fix: map tInClipMs through clip.speedRamp.outputOffsetAt(tInClipMs, sourceSpanMs) (SpeedRamp.kt:130) for the keyframe clock only, keeping sourceInMs + tInClipMs for the stabilizer; files: ClipTransformEffect.kt, VideoProcessor.kt:342, CompositionFactory.kt:126.

**Files:** app/src/main/java/com/squish/app/media/ClipTransformEffect.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt

## bug · high · S — Overlay layers are graded and given library effects in the preview but not in the export

**Detail:** The preview adds `LiveLookEffect` and `FxEffect` to every surface, overlay players included. The export's overlay branch uses only `CompositionFactory.overlayEffects` (key, background, mask, transform, presentation, alpha) and never `buildVideoEffects`, so the look, the Adjust sliders and timed effects vanish from picture-in-picture in the rendered file.

**Evidence:** PreviewEngine.kt: applySurfaceEffects (459-538) is invoked for overlay players via syncOverlays (785-788) -> syncSurface (813); it adds LiveLookEffect(liveGrade) at 514 and FxEffect at 515 to every surface, and the overlayPlayer comment at 331-337 states overlays are meant to be graded. VideoProcessor.kt: editedClip overlay branch (325-331) uses only CompositionFactory.overlayEffects; buildVideoEffects (495-572), which carries the grade (545-555) and FxEffect (559-562), is only reached at 345 (base clips) and 398 (single-file path). compositionEffects (234-252) adds only a Presentation for multi-source merges, no grade. CompositionFactory.kt: overlayEffects (115-130) contains no colour or Fx effects. Fix would touch VideoProcessor.editedClip (append the grade and FxEffect, time-shifted into the overlay clip, after overlayEffects but before Presentation/AlphaScale) or CompositionFactory.overlayEffects.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt

## bug · medium · M — Rotation is applied before the clip transform in the preview and after it in the export

**Detail:** Preview: rotation lives in the player's effect chain, and the clip transform (keyframes, stabilizer, static offset) is applied afterwards to the view in screen space. Export: `ClipTransformEffect` runs first in source space, then `ScaleAndRotateTransformation`. With Rotate 90 set, a horizontal pan or a stabilizer x-correction is horizontal on screen in the preview and vertical in the file.

**Evidence:** PreviewEngine.kt:504-510 (ScaleAndRotateTransformation in the base player's chain, source space); PreviewEngine.kt:726-727, 741-742 (SurfaceDraw.transform = clip.transformAt(at), which via TimelineModels.kt:147-150 and Keyframe.kt:103-119 includes keyframes, staticTransform and stabilizer); TimelinePreview.kt:289-299 (that transform applied to the TextureView in screen space, after rotation). VideoProcessor.kt:336-345 (ClipTransformEffect first in `leading`, then buildVideoEffects appended) and 498-504 (ScaleAndRotateTransformation first in buildVideoEffects); ClipTransformEffect.kt:67-73 (postTranslate in source NDC before the rotation matrix). No rotation compensation anywhere (CompositionFactory.kt has no rotationDegrees reference; composeTransform takes none). Scenario: Rotate 90 + MotionPreset.PanRight (MotionPreset.kt:16) or a stabilizer x-correction -> preview drifts horizontally, export drifts vertically.

**Files:** app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt

## bug · medium · M — Preview and look thumbnails use luma-mix saturation; the export uses Media3 HslAdjustment for every non-shader look and for the Saturation slider

**Detail:** `PreviewEngine` always installs `LiveLookEffect` (shader: `mix(vec3(lum), c, 1+sat)`), and `LookPreview`/`Grade.applyTo` use the same luma mix. `ColorGrade.effects` sends any grade without fade/split/bloom/vignette/grain (Vivid, Punch, Soft, Clean, Warm, Cool, Noir, Kodak, Bleach, Golden, Arctic, Moonlit, Ember, Neon, Mint, and the manual Saturation slider on its own) to `HslAdjustment.adjustSaturation`, which scales S in HSL space - a different transform with visibly different hue/brightness behaviour on saturated colours. So 'the graded frame on screen is the graded frame that gets written' (VideoProcessor comment) does not hold for roughly half the catalogue.

**Evidence:** ColorGrade.kt:24-41 (needsShader gate, then HslAdjustment.adjustSaturation at 38-40); VideoProcessor.kt:542-555 (the only caller of ColorGrade.effects, with the now-false "same code the preview uses" comment); PreviewEngine.kt:511-514 (LiveLookEffect added unconditionally, ColorGrade never consulted); LookEffect.kt:46-50 (LiveLookEffect runs the shader for every grade); squish_look_es2.glsl:86-87 (luma mix); Look.kt:153-159 (Grade.applyTo luma mix) and Look.kt:124-126 (needsShader); LookPreview.kt:92-95 (luma mix). Scope correction: affected are the 14 built-in-path looks with non-zero saturation (Look.kt:213-218, 221, 237-238, 256-261 minus Clean at 216, which has saturation 0) and the manual Saturation slider when the folded grade has no fade/split/bloom/vignette/grain - i.e. 14 of 40 catalogue looks, not "roughly half".

**Files:** app/src/main/java/com/squish/app/media/effects/ColorGrade.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/assets/squish_look_es2.glsl, app/src/main/java/com/squish/app/media/effects/LookPreview.kt

## bug · medium · S — Keyframes are not re-based when a clip is split or trimmed, and preset end keys outlive the clip

**Detail:** `withSplitAtPlayhead` copies `keyframes` verbatim onto both halves, so the second half restarts the animation from its first key at the cut (a visible jump), and the first half keeps an end key past its new end. `withClipTrimmed` shifts `timelineStartMs` but not `atMs`, so trimming the head slides the whole move later than the frames it was drawn on; trimming the tail leaves the end key unreachable. `applyMotionPreset` writes the end key at the clip's duration at that moment.

**CapCut:** Keyframes stay attached to their frames through split and trim.

**Evidence:** TimelineModels.kt:344-355 (withSplitAtPlayhead: both halves via clip.copy without touching keyframes), TimelineModels.kt:308-318 (withClipTrimmed: shifts timelineStartMs and slices speedRamp, keyframes untouched), TimelineModels.kt:147-150 (transformAt uses local = timelineMs - timelineStartMs), Keyframe.kt:74-89 (transformAt holds at first/last key, so an end key past durationMs is never reached), EditorViewModel.kt:1866-1877 (applyMotionPreset writes end key at clip.durationMs at that moment), MotionPreset.kt:22 (PushIn 1.0 -> 1.18). Callers: EditorViewModel.kt:713, 1923, 1930 delegate straight to the model functions with no re-basing. Scenario: 'Push in' on a 10 s clip, cut at 5 s -> first half ends at ~1.09x, second half starts at 1.0x (snap) and pushes to ~1.09x over its 5 s; end key at 10000 ms is unreachable on both halves. Head-trimming an animated clip by N ms delays the whole move by N ms relative to the footage; tail-trimming leaves the end key unreachable so the animation never completes.

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · M — Auto-reframe analyses only the first clip but its track is applied to every base clip

**Detail:** `autoReframe` tracks `videoClips.firstOrNull()`; the export builds one `ReframeEffect(ratio, track, headOffset)` per base clip inside `buildVideoEffects`, and the preview clips the whole canvas with one focus. For every clip after the first, the track is sampled at times beyond its range and holds the head clip's last position. The panel says 'The crop follows the subject' with no caveat that only clip 1 was analysed.

**Evidence:** EditorViewModel.kt:985-1008 (only head clip analysed, single state.reframe); EditorModels.kt:313-318 (one project-wide MotionTrack, "in the main source's time"); VideoProcessor.kt:314-346, esp. line 345 (buildVideoEffects called per base clip) and 514-523 (ReframeEffect built with head-clip offset for every clip); ReframeEffect.kt:86-90 (no origin latch, just adds timeOffsetMs); ObjectTracker.kt:38-44 (sampleAt clamps to last sample beyond range); EditorScreen.kt:258-260 and TimelinePreview.kt:235 (preview samples one track with the head offset across the whole timeline); EditorPanels.kt:175 (copy with no caveat). Note: the export's exact wrong behaviour (hold vs replay of clip 1's path) depends on Media3 per-item timestamp semantics, which the codebase itself treats as version-dependent (ClipTransformEffect.kt:45-54); the preview's "holds clip 1's last position" is definite.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt

## bug · low · S — Vignette, grain, bloom and library effects are evaluated on the full frame in the preview but on the cropped frame in the export

**Detail:** The preview crops by clipping the view after the whole effect chain has run, so a vignette or a Shake/Zoom-punch pivot is centred on the uncropped picture and then cut. The export applies `Crop`/`Presentation` before `ColorGrade` and `FxEffect`, so they are centred on the crop. A 9:16 crop of 16:9 footage with Super 8 or Midnight shows bright, asymmetric edges in the preview and a centred falloff in the file.

**Evidence:** Preview chain without crop: app/src/main/java/com/squish/app/editor/PreviewEngine.kt:403-417 (applyFraming keeps crop out of the pipeline) and 492-519 (LiveLookEffect + FxEffect applied to the full frame); view clip afterwards: app/src/main/java/com/squish/app/editor/TimelinePreview.kt:235-239 (CentredCrop, focus-following for reframe) and the 'pixel-for-pixel' comment at 230-234. Export crop before grade/fx: app/src/main/java/com/squish/app/media/VideoProcessor.kt:509-527 (Crop / ReframeEffect / Presentation) then 545-555 (ColorGrade -> LookEffect when grade.needsShader, ColorGrade.kt:24) and 559-562 (FxEffect). Frame-relative shaders: app/src/main/assets/squish_look_es2.glsl:99-102 (vignette about 0.5 scaled by uAspect from LookEffect.kt:105-108 configure(inputWidth, inputHeight)), :106 (grain hashed in full-frame uv); app/src/main/assets/squish_fx_es2.glsl:56 (zoom about 0.5, uOffset in uv units). Symptom correction: with a centred fixed-ratio crop (e.g. 9:16 of 16:9, Super 8 or Midnight) the preview's vignette is symmetric but nearly absent at the crop's side edges (crop edge at ~0.28 of the full-frame radius, under the 0.42 smoothstep start) while the export shows a full corner falloff; the asymmetric case only arises with auto-reframe, where the preview crop window follows the subject and the vignette stays centred on the full frame. Shake offsets are also ~3x larger relative to the picture in the file than in the preview.

**Files:** app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt

## bug · low · S — LabeledSlider prints degrees and frame fractions as percentages

**Detail:** The readout is `value*100` with a % sign for every slider. Motion 'Rotation' (-45..45) shows '+2000%' for 20 degrees; Mask 'Rotation' (-180..180) shows '+9000%'; Mask Width/Height show '150%'. There is no unit parameter and no reset-to-default.

**Evidence:** EditorPanels.kt:346 — `val readout = if (range.start < 0f) "%+.0f%%".format(value * 100) else "%.0f%%".format(value * 100)` is applied to every caller; the composable (lines 340-363) takes only label/value/range/onChange, with no unit, formatter, or default/reset parameter. MotionPanel.kt:118-120 passes `here.rotationDegrees` (range -45f..45f), so 20 degrees shows "+2000%". MaskPanel.kt:183-185 passes `mask.rotationDegrees` (range -180f..180f), so 90 degrees shows "+9000%". Mask Width/Height (MaskPanel.kt:168-176) are frame fractions, so "150%" for 1.5 is arguably correct and should be dropped from the claim; the defect is limited to the two degree-valued Rotation sliders plus the missing reset-to-default.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/editor/MaskPanel.kt

## bug · low · S — Stabilize card reports the last analysis on whichever clip is selected

**Detail:** `StabilizeProgress` is a single global field; the card checks `status.finished` before `clip.isStabilized`, so after stabilizing clip A, selecting an untouched clip B shows 'Measured 240 frames. Zoomed in 8%' (or the failure text) while the button still says 'Stabilize this clip'.

**Evidence:** MotionPanel.kt:43 (clip = state.targetVideoClip), MotionPanel.kt:244-245 and 266-314 (status = state.stabilize; when-branches check status.finished before anything clip-specific; only lines 250/255/331 use clip.isStabilized); EditorModels.kt:157-165 (StabilizeProgress has no clipId), EditorModels.kt:323 (single global field), EditorModels.kt:530 (targetVideoClip follows selectedClipId); EditorViewModel.kt:1246 (selectClip does not reset stabilize), 1714-1731 (finished/failed written globally), 1736-1746 (only reset path is clearStabilization, reachable only from the 'Remove' link that is shown only when clip.isStabilized). Fix would also touch EditorViewModel.kt, not just the two files listed.

**Files:** app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## bug · low · S — Chroma-key and tracking sample frame ignores the speed ramp and decodes the full-resolution source

**Detail:** `sampleFrame` computes `sourceInMs + (atMs - timelineStartMs)` instead of `clip.sourceAt(atMs)`, so on a retimed clip the sampled frame is not the one under the playhead. It also always reads the original file via MediaMetadataRetriever even when a 540p proxy exists.

**Evidence:** EditorViewModel.kt:1314-1318: `sampleFrame` uses `clip.sourceInMs + (atMs - clip.timelineStartMs)` instead of `clip.sourceAt(atMs)` (TimelineModels.kt:112-115, which applies `speedRamp.sourceOffsetAt`, SpeedRamp.kt:150-162). Reachable via `setClipSpeed` (EditorViewModel.kt:785) / `setSpeedPointAtPlayhead` (:798). Scenario: clip at flat speed 0.5x, playhead 4 s past clip start -> preview shows source frame at sourceIn+2 s, sampled frame is sourceIn+4 s (clamped to sourceOutMs if beyond the span). Callers: ChromaKeyPanel.kt:138 (FrameSampler swatch) and TrackPanel.kt:61 (tracking box picker). Proxy: sampleFrame passes the original `uri` to `ThumbnailExtractor.frameAt` (ThumbnailExtractor.kt:161-177, MediaMetadataRetriever.getScaledFrameAtTime to 480x480, OPTION_CLOSEST_SYNC) and never checks `_state.value.proxyUri`, unlike `analysisSourceFor` (EditorViewModel.kt:580-586). Wording correction: the returned bitmap is scaled to <=480 px, so it is not a full-resolution bitmap; the waste is decoding the original 4K frame to produce it when a 540p proxy with identical timings exists. Fix touches: EditorViewModel.kt (sampleFrame: use clip.sourceAt(atMs) and route through analysisSourceFor/proxyUri); optionally ThumbnailExtractor.kt if a proxy-aware overload is added. ChromaKeyPanel.kt and TrackPanel.kt need no change for the retime fix.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/editor/TrackPanel.kt

## bug · low · S — Blend's overlay sliders clamp a Motion scale above 2 and do nothing on an animated overlay

**Detail:** `withOverlayGeometry` re-coerces scale to 0.1..2 on every call, so touching Opacity in Blend after setting scale 2.5 in Motion snaps the layer smaller. Blend writes the static fields, which `Clip.transformAt` ignores once keyframes exist, so on an animated overlay the four Blend sliders move and nothing changes. Blend's Size range (0.1..1) and Motion's Scale range (0.2..3) also disagree for the same value.

**Evidence:** TimelineModels.kt:242-258 (withOverlayGeometry re-clamps scale to 0.1..2 even when only opacity is passed); EditorViewModel.kt:1797-1826 (setClipTransform clamps scale 0.1..4 and writes static fields only when keyframes.isEmpty(), otherwise upserts a key); MotionPanel.kt:109 (Scale slider 0.2..3); TransitionPanel.kt:154-167 (Opacity/Size 0.1..1/Across/Up-down all call setOverlayGeometry and display the static fields); TimelineModels.kt:147-150 -> Keyframe.kt:103-111 -> Keyframe.kt:74-75 (List<Keyframe>.transformAt returns the static fallback only when the list is empty, so with >=1 key the static scale/offsets are ignored). Correction: only three Blend sliders (Size, Across, Up/down) are dead on an animated overlay; Opacity is a separate Clip field outside Transform (Keyframe.kt:30-35) and is read directly by PreviewEngine.kt:796 / CompositionFactory.kt:128 / TimelinePreview.kt:326, so it still works. The dead-slider condition is keyframes.isNotEmpty() (one key suffices), not isAnimated (>=2). The clamp scenario applies only to a non-animated overlay (with keys, Motion never writes the static scale).

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt

## bug · low · S — 'Keep it smooth' silently flattens a speed ramp

**Detail:** The toggle calls `setClipSpeed`, which replaces the whole ramp with `SpeedRamp.flat`, so a Bullet-time curve becomes one constant rate with no warning and (per the undo bug above via record 'Speed') is only recoverable by undo.

**Evidence:** SpeedPanel.kt:105-109 (onHold -> setClipSpeed) and 384-388 (toggle shown when ramp.slowestSpeed is below SlowMotion.smoothestSpeed(fps), which is true for any Bullet/Slow-in/Slow-out/Montage preset on 30 fps footage since smoothestSpeed(30) = 0.8); EditorViewModel.kt:785-787 (setClipSpeed = record("Speed") { retime(clipId, SpeedRamp.flat(speed)) }); EditorViewModel.kt:741-747 (retime replaces clip.speedRamp wholesale); SpeedRamp.kt:222 (flat builds a one-point ramp); SpeedRamp.kt:247-252 (BulletTime points 2x/0.25x/0.25x/2x are all discarded). The action is undoable via record("Speed"), so "recoverable by undo" is correct; the flattening itself is silent, with no confirmation and no per-point clamp.

**Files:** app/src/main/java/com/squish/app/editor/SpeedPanel.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · low · S — Look thumbnails always grab a frame from the first source file at raw timeline time

**Detail:** `rememberLookFrame(state.sourceUri, state.playheadMs)` ignores which clip covers the playhead and the clip's trim/speed. With several clips the row previews looks on clip 1's frame at the wrong time; past clip 1's end `frameAt` returns null/last frame and the chips fall back to gradient swatches.

**Evidence:** EffectsPanel.kt:65 and LookThumbnails.kt:71-84 are the right anchors; add EditorViewModel.kt:159 (sourceUri set once on load), EditorViewModel.kt:1208-1223 (added clips carry their own uri and timelineStartMs; sourceUri untouched), and the correct mapping to reuse at EditorViewModel.kt:1314-1318 (sampleFrame) / TimelineModels.kt:112-115 (Clip.sourceAt, which also handles speed ramps). One correction to the failure description: past clip 1's end the chips do NOT reliably fall back to gradient swatches. LookThumbnails.kt:81 is `LookFrame.grab(...)?.let { frame = it }` and the state is `remember(uri)` (line 74), so when `frameAt` returns null the previously grabbed frame is kept and the row silently shows a stale frame from an earlier bucket; if MediaMetadataRetriever instead returns the closest sync frame, the row shows clip 1's last frame. The swatch fallback only appears if no frame has ever been grabbed for that uri (e.g. the panel was first opened with the playhead already past the first file's end and the retriever returned null). Fix should touch EffectsPanel.kt (pass the covering layer-0 clip, or a resolved uri + source time), LookThumbnails.kt (take uri + source ms, and clear/replace the frame when the grab fails or the uri changes), and optionally EditorViewModel.kt to expose a `clipAtPlayhead`/`sampleFrame`-style resolver.

**Files:** app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/editor/LookThumbnails.kt

## bug · low · S — Person-mask files from background removal are never deleted

**Detail:** `Segmenter.analyze` writes a new file under filesDir/segments per run; nothing removes it when background removal is turned off, when the clip is deleted, or when the draft is deleted (`ProjectAutosave.delete` touches only the JSON files). Every re-run adds another file to app storage permanently.

**Evidence:** Segmenter.kt:163-166 (writes to filesDir/segments, name `${uriHash}_${fromMs}_${toMs}.bin`; a re-run with the same trim overwrites via Segmenter.kt:54 renameTo, so only trim changes and abandoned edits accumulate files, each up to ~14.7 MB: 128*128 bytes x 900 samples). No deleter exists anywhere: ProjectAutosave.kt:176-180 removes only JSON (called from HomeViewModel.kt:60 on draft delete and EditorViewModel.kt:2141/2175 after export/recovery); BackgroundPanel.kt:114 -> EditorViewModel.kt:970-974 only nulls clip.background; EditorViewModel.kt:1935-1944 deleteSelectedClip only edits the timeline; EditorViewModel.kt:2311-2314 onCleared only evicts thumbnails; SettingsScreen.kt:72 "clear storage" calls ProxyEngine.clearCache which sweeps cacheDir/proxies only (ProxyEngine.kt:119-121, 165-166). Files a fix touches: Segmenter.kt (or a new sweep helper), ProjectAutosave.kt, EditorViewModel.kt, HomeViewModel.kt, SettingsScreen.kt/ProxyEngine.kt (include segments in the storage line and clear button).

**Files:** app/src/main/java/com/squish/app/media/video/Segmenter.kt, app/src/main/java/com/squish/app/data/ProjectAutosave.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · S — Leaving the editor within 1.5 s of an edit loses it: no flush on back or onCleared

**Detail:** Autosave runs only on the 1.5 s timer; `onCleared` evicts thumbnails but does not save, and `onBack` pops the stack. Any slider drag, mask change or look pick made in the last tick before leaving is not on disk. Cross-cutting, but it hits every panel in this area.

**Evidence:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt:108-143 (the only autosave write path: a delay-loop in viewModelScope), :2318 (AUTOSAVE_INTERVAL = 1_500 ms), :2311-2314 (onCleared evicts thumbnails only; viewModelScope cancellation also kills the timer so no final tick runs), :2175 (the only other autosave call, a clear on discarding a recovery offer). app/src/main/java/com/squish/app/editor/EditorScreen.kt:114 (ViewModel obtained via default `viewModel()`, i.e. scoped to the nav back-stack entry), :179 (BackOrb calls onBack with no save/confirm). app/src/main/java/com/squish/app/navigation/SquishNavHost.kt:179 (onBack = popBackStack, which clears the entry's ViewModelStore). No BackHandler, lifecycle observer, or flush method exists anywhere in app/src/main/java (ProjectAutosave.kt exposes only save/editKey/peek/clear). Same pattern in app/src/main/java/com/squish/app/tools/QuickToolViewModel.kt:154-176.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## bug · low · S — Chroma key and 'Cut out' background are offered on the base track, where they only produce black

**Detail:** ChromaKeyPanel's empty state says keying 'only makes sense on a layer' yet shows Key green/Key blue for a layer-0 clip; BackgroundPanel offers 'Cut out' on the base with a note that it shows black. Neither offers the obvious next step (float this clip / add a background).

**Evidence:** Category is best classed as "ux" (severity low-medium, effort S). Evidence: app/src/main/java/com/squish/app/editor/TransitionPanel.kt:183-186 (gates only on isVideo, not selected.layer; Raise/Lower exist at 144-145 but are not linked from the key panel); app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt:77-92 (unconditional "only makes sense on a layer" text followed by Key green/Key blue; clip.layer never read, so the caveat also shows on overlays where it is irrelevant); app/src/main/java/com/squish/app/editor/EditorViewModel.kt:927-930 (backgroundTarget falls back to the first layer-0 clip) and 1289-1293 (setChromaKey has no layer guard); app/src/main/java/com/squish/app/editor/EffectsPanel.kt:103 (BackgroundPanel always mounted); app/src/main/java/com/squish/app/editor/BackgroundPanel.kt:65-72 (all BackgroundFill entries including Remove offered) and 95-101 (black caveat only after Remove is chosen, no next step). Correction to "only produce black": app/src/main/assets/squish_chroma_key_es2.glsl and the uFill==2 branch of squish_background_es2.glsl emit straight alpha with RGB retained; the base shows black only where the compositor clears to black (multi-sequence preview/export). In a single-sequence export (app/src/main/java/com/squish/app/media/VideoProcessor.kt:338-345) the encoder discards alpha, so the keyed region would show the spill-suppressed source rather than black. Either way the effect is useless on the base, so the UX point stands. Fix would touch ChromaKeyPanel.kt, BackgroundPanel.kt, TransitionPanel.kt (and optionally EditorViewModel.backgroundTarget).

**Files:** app/src/main/java/com/squish/app/editor/ChromaKeyPanel.kt, app/src/main/java/com/squish/app/editor/BackgroundPanel.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt

## Refuted

- Preview stretches any surface whose shape differs from the canvas (overlays, mixed-orientation clips)
