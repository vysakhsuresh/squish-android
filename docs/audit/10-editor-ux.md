# Overall editor layout and interaction design versus CapCut: toolbar structure, panels, preview, tap counts, consistency, discoverability

Squish's editor (EditorScreen.kt) is a single vertical stack: header (back / title / Export), preview (max 300dp when a tool is open), status rows, timeline strip + action bar, an optional tool panel, and a bottom rail of 11 always-identical tabs (Cut, Frame, Speed, Blend, Motion, Sound, Words, Stickers, Effects, Looks, Finish). There is no clip-context toolbar: selecting a clip changes nothing in the rail, and every clip-scoped tool (Speed, Motion, Blend/Mask/Chroma) silently falls back to "the first video clip" when the selection is not a video. Ten of the eleven tabs open with the timeline AND its action bar folded away (ROOMY_TABS, EditorScreen.kt:603), so undo/redo, split, delete, zoom and the timecode disappear whenever a tool is open; the one tab that keeps the strip (Blend) is left with roughly 40-100dp of panel on a phone. Common-task tap counts: add music 2-3 (Sound -> Music card -> Add), add text 3+ with no keyboard focus, split 2 (but 3 when any panel is open), delete 2, change speed 2-3; all comparable to CapCut only when no panel is open. Colour and icon systems are inconsistent (Amber = Motion and Words; Magenta = Blend, Stickers, Looks; gutter and rail use different glyphs for the same track), "Cut" means trim in the rail and split on the action bar, Effects/Looks are two adjacent tabs with inverted file names, Export exists twice (header sheet and Finish tab) with different size chips, and rotation/crop/looks/adjust are global rather than per clip. Concrete defects: rotation sliders print "+4500%"; ~25 edit operations bypass record() so undo skips them and mislabels what it will reverse; autosave is suspended for as long as the recovery banner is on screen; the last 1.5s of edits are dropped on back; system back during export cancels the encode silently; the same clip.scale/offset fields are edited by two panels with different ranges; the Cut button lights over a caption it cannot split and pushes an empty undo step.

Proposed information architecture (concrete):
1. Layout: header [Back | project name | Undo Redo | Export]. Preview fills all remaining height with a fullscreen toggle; transport + timecode directly under it. Timeline strip is ALWAYS visible (ruler + lanes, compressed to ruler + the selected lane when a panel is open). A tool opens as a bottom sheet over the rail region only, with a title bar [name | Reset | Done]; the strip is never hidden and the split/delete/undo controls never leave the screen.
2. Level-0 toolbar (nothing selected), in this order: Cut (opens clip tools for the clip under the playhead), Sound, Text, Stickers, Overlay, Effects, Looks (Templates + Filters + Adjust in three chips), Frame (Ratio + Rotate + Background). Export lives only in the header; drop Finish, Speed, Motion and Blend from level 0.
3. Level-1 clip toolbar, swapped in when a clip is selected (a "<" chevron returns to level 0). Video clip: Split, Speed, Volume, Motion, Stabilize, Transition (base only), Mask, Cutout (Remove background / Chroma key), Layer (Opacity, Raise/Lower), Track, Crop/Rotate, Reverse, Freeze, Duplicate, Replace, Delete. Audio clip: Split, Volume, Fade, Speed, Beat, Sync, Delete. Text/sticker: Edit text (keyboard up immediately), Style, Motion, Track, Split, Duplicate, Delete. Effect: Strength, Delete. Tap on empty lane deselects and returns to level 0.
4. Direct manipulation on the preview for overlays, text, stickers and masks (drag, pinch, rotate handles); sliders remain as precision fallback.
5. One colour per track kind used everywhere (rail, gutter, panel headings, clip tint): Violet video, Magenta overlay, Cyan sound, Amber text, Blue effects/looks, Orange primary; one glyph per concept (the gutter icon IS the rail icon). Gutter track buttons stay (no "+"), and the overlay lane gets one.
6. Tap budgets: add music 2, add text 2 with keyboard, split 2, delete 2, speed 3, never more when a panel is open.

## gap · high · L — No clip-context toolbar; the rail never changes on selection

**Detail:** EditorTab.entries is drawn unconditionally in ToolRail regardless of what is selected. Speed/Motion/Blend/Mask/Chroma are clip-scoped tools but sit in the global rail, and each quietly falls back to the first video clip when the selection is a caption, sound or effect (targetVideoClip EditorModels.kt:529-531, trimTargetClip EditorViewModel.kt:234-236, backgroundTarget :927-930). Restructure: level-0 rail for adding things, level-1 rail for the selected clip's tools with a back chevron.

**CapCut:** Main toolbar Edit/Audio/Text/Stickers/Overlay/Effects/Filters/Adjust/Canvas; tapping a clip swaps in Split/Speed/Volume/Animation/Delete/Style/Filters/Adjust/Mask/Chroma key/Extract audio/Replace/Copy/Freeze/Reverse/Opacity, with a '<' to go back.

**Evidence:** EditorScreen.kt:436 ToolRail(selected = tab, ...) and :519-582; EditorScreen.kt:80-105 EditorTab; scenario: select a sticker, open Motion, drag Scale -> the first video clip scales.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## ux · high · M — Every tool but Blend hides the timeline AND the action bar (undo, redo, split, delete, zoom, timecode)

**Detail:** ROOMY_TABS = all tabs except Blend; when panelExpanded the whole `if (showStrip)` block including TimelineActionBar is skipped. So in the 'Cut' tab there is no cut button, and undo is invisible in 10 of 11 tools until 'Show timeline' is tapped. Move undo/redo to the header, keep a compressed strip (ruler + selected lane) and the split/delete row visible at all times.

**CapCut:** Undo/redo sit beside the preview and stay put; the timeline stays visible under every panel; split is on the clip toolbar which is always reachable.

**Evidence:** EditorScreen.kt:326-367 (`val showStrip = !(panelOpen && panelExpanded)` wraps both TimelineEditor and TimelineActionBar), :603 ROOMY_TABS; TimelineEditor.kt:1613-1739 TimelineActionBar holds undo/redo/split/delete.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · medium · M — Blend panel gets a sliver of height when the strip is visible

**Detail:** With Blend open (strip kept), the panel is what remains after header (~60dp), preview (up to 300dp), ruler+3 lanes (26+3x54=188dp), action bar (~90dp), PanelBar (~30dp) and rail (~70dp): roughly 40-100dp on a 780dp phone for Transition + Layers + Mask + Green screen. The comment at :595-601 admits other panels showed 'one row'. Panels should be sheets with a guaranteed minimum height and the strip should compress instead.

**CapCut:** Preview shrinks to ~40% and the tool panel takes a fixed bottom sheet with the timeline still visible between them.

**Evidence:** EditorScreen.kt:231-246 preview height, :394-399 panel Column weight(1f); TimelineEditor.kt:92-97 lane sizes; EditorScreen.kt:595-603 comment.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/PreviewBox.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt

## gap · high · L — No direct manipulation on the preview: overlays, text, stickers and masks are placed with 'Across' / 'Up / down' sliders

**Detail:** TimelinePreview only toggles play on tap; the only on-picture gesture is the custom crop rectangle. Overlay position/size (TransitionPanel), sticker position (StickersPanel), caption place (Top/Middle/Bottom chips only, no X), mask centre (MaskPanel) and motion placement (MotionPanel) are all sliders. Add drag/pinch/rotate handles over the selected element in pictureOverlay, keeping sliders as precision fallback.

**CapCut:** Every overlay, text and sticker has a bounding box on the preview with drag, pinch-to-scale, rotate and delete/duplicate corner handles; masks have on-preview handles for size, feather and rotation.

**Evidence:** TimelinePreview.kt:204-210 clickable toggles play; TransitionPanel.kt:155-166 sliders; StickersPanel.kt:140-148; CaptionsPanel.kt:320-322 CaptionPlace; MaskPanel.kt:33-36 states handles are deliberately omitted.

**Files:** app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/StickersPanel.kt, app/src/main/java/com/squish/app/editor/CaptionsPanel.kt, app/src/main/java/com/squish/app/editor/MaskPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt

## gap · high · L — Rotation, crop, looks, adjust and camera volume are global, not per clip

**Detail:** rotationDegrees, cropAspect/cropRect, lookId/intensity, brightness/contrast/saturation, muteOriginal and originalVolume live on EditorUiState. With several shots on the track you cannot rotate one, crop one, filter one or mute one. Clip already has a `volume` field for audio; video clips need the same plus per-clip rotation/mirror and grade.

**CapCut:** Clip toolbar has Rotate, Mirror, Crop, Volume, Filters and Adjust per clip, with an 'Apply to all' option.

**Evidence:** EditorModels.kt:294-308; EditorViewModel.kt:535-537 toggleRotate, :470-472 setOriginalVolume, :827 setLook; AudioPanel.kt:102-119 'Camera audio' is one global switch; ARCHITECTURE.md lists 'Per-clip looks' as not built.

**Files:** app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/editor/AudioPanel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## gap · medium · L — Missing clip operations: Duplicate/Copy, Replace, Reverse, Freeze frame, Mirror, Extract/Detach audio, audio fade, base-clip opacity

**Detail:** None of these exist in the view model or the action bar. Delete/Split/Close-gaps are the only clip actions on the strip.

**CapCut:** All are one tap on the clip toolbar (Copy, Replace, Reverse, Freeze, Mirror, Extract audio; Fade in/out on audio clips; Opacity on any clip).

**Evidence:** TimelineEditor.kt:1673-1718 the full action list; EditorViewModel.kt:1915-1944 the only clip mutations.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · S — Deleting a base clip leaves a black gap; ripple is a separate 'Close gaps' button

**Detail:** withClipRemoved leaves the hole and rippleVideo is only fired by the Compress button. The preview then shows 'Gap - no clip here' and the export writes black. Default delete on the base track should ripple; keep free placement for overlays/sound.

**CapCut:** Delete on the main track closes the gap; dragging a clip reorders with the others sliding to make room.

**Evidence:** EditorViewModel.kt:1935-1944 deleteSelectedClip -> withClipRemoved; TimelineModels.kt:273-277 'Removing leaves the hole'; :1933 closeGaps separate.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · S — Adding an overlay is buried in Blend, video-only, and has no track button

**Detail:** 'Add overlay clip' is the last button of the Layers card inside Blend; the picker is VideoOnly so photos and stickers cannot float; the overlay lanes in the gutter are plain LaneBadge icons while video/sound/words got buttons in commit 40a623f. Add a dedicated Overlay rail entry and an overlay-lane gutter button, and allow ImageAndVideo.

**CapCut:** 'Overlay' is a main-toolbar item (Add overlay -> photo or video), and the overlay clip appears on its own track above the main one.

**Evidence:** EditorScreen.kt:404-414 VideoOnly picker; TransitionPanel.kt:176-180; TimelineEditor.kt:482 LaneBadge for overlay layers vs :483-497 buttons for the other tracks.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · M — Adding text does not focus the field or raise the keyboard; editing needs hunting for the row

**Detail:** Words -> 'Add a line at the playhead' appends an empty caption; the user must then find it in the 'Lines' list and tap its OutlinedTextField. Title tiles drop preset sample text ('BIG NEWS') that also needs a second trip to the list to change. Add text should open the editor with the field focused and the keyboard up, previewing live on the picture.

**CapCut:** Text -> Add text opens the keyboard immediately with the text box on the preview; Done commits.

**Evidence:** CaptionsPanel.kt:220-234 add button and list; :436-447 the text field with no FocusRequester (grep for FocusRequester/bringIntoView returns nothing); EditorViewModel.kt:855-868, :875-894.

**Files:** app/src/main/java/com/squish/app/editor/CaptionsPanel.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · high · S — No system-back handling for panels and sheets: back exits the editor from inside any tool

**Detail:** With a tool panel or the export sheet open, back pops the whole editor instead of closing the panel. Add a BackHandler that closes the export sheet, then the open tab, then leaves.

**CapCut:** Back closes the innermost panel level by level; only from the top level does it leave the editor.

**Evidence:** EditorScreen.kt:127-141 tab/exportSheetOpen are local state with no BackHandler; SquishNavHost.kt:179.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · medium · S — 'Cut' means trim in the rail and split on the action bar

**Detail:** EditorTab.Cut opens 'In and out points' (precision trim + markers) while the action bar's scissors is 'Cut at the playhead' (split). Two different operations share the word and the ContentCut icon on one screen. Rename the tab 'Trim' (or fold it into the clip toolbar) and keep 'Split' for the razor.

**CapCut:** 'Split' on the clip toolbar; trimming is only the clip's drag handles.

**Evidence:** EditorScreen.kt:81-82 Cut("Cut", Icons.Filled.ContentCut); TimelineEditor.kt:1687-1693 MiniAction(Icons.Filled.ContentCut, "Cut at the playhead"); EditorPanels.kt:78-83 heading 'In and out points' with the same icon.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/editor/EditorPanels.kt

## ux · medium · S — Rail order hides the most-used tools: Sound and Words are off-screen on arrival

**Detail:** 11 items x (66+6)dp = 792dp; at 360-411dp only 5 fit, so Sound, Words, Stickers, Effects, Looks and Finish need a horizontal scroll to be discovered. The gutter buttons mitigate Sound/Words but nothing signals the rail scrolls. Reorder by frequency and cut the count (see IA): Cut, Sound, Text, Stickers, Overlay, Effects, Looks, Frame.

**CapCut:** 9 items with Edit/Audio/Text first; the row scrolls but the primary five are visible.

**Evidence:** EditorScreen.kt:80-105 order; :590 RAIL_ITEM_WIDTH = 66.dp; :537 spacedBy(6.dp).

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · S — Colour coding contradicts its own rule and icons differ per surface

**Detail:** EditorTab's doc says each tool wears its track's colour, but Amber is Motion AND Words, Magenta is Blend, Stickers AND Looks, Violet is Cut, Frame AND Effects, and Blue is Speed and Finish. Gutter icons (Videocam, MusicNote, TextFields) differ from rail icons (ContentCut, GraphicEq, ClosedCaption) and from panel headings (Title/Subtitles/Mic). Pick one colour and one glyph per concept and reuse them in the rail, gutter, PanelBar and headings.

**CapCut:** One icon per tool reused on toolbar and track heads; track colours: video grey-blue, audio blue, text orange, overlay purple.

**Evidence:** EditorScreen.kt:75-105 accents; TimelineEditor.kt:486-497 gutter icons; Color.kt:5-12 the stated meaning of each hue.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/ui/theme/Color.kt, app/src/main/java/com/squish/app/editor/CaptionsPanel.kt, app/src/main/java/com/squish/app/editor/AudioPanel.kt

## ux · low · S — 'Effects' and 'Looks' are adjacent tabs with inverted source files; Templates and Background removal live under Looks

**Detail:** Rail Effects -> FxPanel.kt (timed effects); rail Looks -> EffectsPanel.kt (filters + adjust) which also hosts Templates and BackgroundPanel (person cut-out). A background removal is a clip cutout tool, not a look; templates are a project-level starting point. Rename files to match tabs, move Background to the clip toolbar's Cutout (with Chroma key and Mask), and put Templates at project start or under Effects.

**CapCut:** Effects (video/body effects), Filters, Adjust are separate top-level items; Remove background / Chroma key / Custom cutout are under the clip's 'Cutout'.

**Evidence:** EditorScreen.kt:423-424 Effects -> FxPanel, Looks -> EffectsPanel; EffectsPanel.kt:68-103 Templates card then BackgroundPanel(state, viewModel).

**Files:** app/src/main/java/com/squish/app/editor/EffectsPanel.kt, app/src/main/java/com/squish/app/editor/FxPanel.kt, app/src/main/java/com/squish/app/editor/BackgroundPanel.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · S — Export is duplicated: header sheet and a 'Finish' tab with different options

**Detail:** The header's Export opens ExportSheet (size picker, fit-to-size 16/25/50/100). The Finish tab shows ExportPanel with the same size picker, fit-to-size 16/25/50 (no 100), plus a 'Video track' list and 'Add another clip' that belong on the timeline. Two names ('Finish' vs 'Export'), two chip sets, one setting. Drop the Finish tab.

**CapCut:** One Export button top-right opening resolution/frame-rate/bitrate options.

**Evidence:** EditorPanels.kt:255-335 (chips listOf(16, 25, 50) at :292); ExportSheet.kt:167 listOf(16, 25, 50, 100); EditorScreen.kt:104 Finish, :209-213 'Export'.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · S — Blend panel stacks three empty-state messages when nothing is selected

**Detail:** With no selection the panel shows a 'Nothing selected' card, then 'Transition in' with 'Select a clip on the video track.', then 'Layers' with 'Select a video clip to move it between layers.', then still the 'Add overlay clip' button. One empty state with the single available action is enough.

**CapCut:** (none)

**Evidence:** TransitionPanel.kt:44-53, :62-71, :168-174.

**Files:** app/src/main/java/com/squish/app/editor/TransitionPanel.kt

## ux · medium · M — Sound panel is up to nine cards long; adding a file is the fifth

**Detail:** Order: Voice, Music (with its own tabs), Beat, Camera audio, Added tracks (the file picker button), then per-track Level, Trim, Place, Align. Adding your own music from a file is Sound -> scroll -> 'Add audio track' -> picker (3 taps + scroll). Split Sound into Music / Voice & effects / Sync as chips, put 'Add' actions first.

**CapCut:** Audio -> Sounds / Extracted / Voiceover / Effects as a second-level row; 'Add' on any row is 2 taps from the main toolbar.

**Evidence:** AudioPanel.kt:56-288 card order; :153-157 the picker button.

**Files:** app/src/main/java/com/squish/app/editor/AudioPanel.kt, app/src/main/java/com/squish/app/editor/MusicPanel.kt, app/src/main/java/com/squish/app/editor/BeatPanel.kt

## ux · low · S — Motion panel leads with Stabilize and Track; placement and presets come third

**Detail:** The tab is called Motion but its first two cards are analysis tools (Stabilize, Track an object); the presets and Placement sliders that the name promises are below them. Reorder or split Stabilize/Track into the clip toolbar.

**CapCut:** Animation (In/Out/Combo), Stabilize and Tracking are separate clip-toolbar entries.

**Evidence:** MotionPanel.kt:60-64 StabilizeCard then TrackPanel before the presets card at :66.

**Files:** app/src/main/java/com/squish/app/editor/MotionPanel.kt

## ux · low · S — No way to deselect a clip; split clears the selection; hint line prefers the undo label

**Detail:** Tapping empty lane only scrubs (onSelect(null) is never called from the strip), so the selection persists until delete. withSplitAtPlayhead sets selectedClipId = null so a split-then-delete needs a re-tap. The action bar's status line shows 'Undo: X' whenever history exists, hiding the '<clip> selected · drag to move' hint.

**CapCut:** Tap empty timeline deselects and returns to the main toolbar; split keeps the left half selected.

**Evidence:** TimelineEditor.kt:900-905 lane tap, :435-441 stackTap; TimelineModels.kt:358 selectedClipId = null; TimelineEditor.kt:1723-1737 hint precedence.

**Files:** app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## ux · low · S — Text glyph characters used where the codebase's own rule says icons

**Detail:** MiniAction's comment explains why '↶', '✂' were replaced with vector icons, yet TrimPointRow and AudioPointRow use '◀'/'▶' text, and TransitionBadge draws '|' and '✕' (an active transition looks like a remove button). Use ImageVectors (ChevronLeft/Right, a transition glyph).

**CapCut:** Transition points are a small bow-tie icon on the join, filled when a transition is set.

**Evidence:** EditorPanels.kt:152-153; AudioPanel.kt:367-368; TimelineEditor.kt:978-983; TimelineEditor.kt:1741-1754 rationale.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/AudioPanel.kt, app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · low · S — Stale label: 'Add a caption in the Captions tab' — the tab is 'Words'

**Detail:** TrackPanel's empty state points to a tab name that no longer exists after the rail rename.

**CapCut:** (none)

**Evidence:** TrackPanel.kt:232 vs EditorScreen.kt:97 Words("Words").

**Files:** app/src/main/java/com/squish/app/editor/TrackPanel.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## gap · medium · S — Editor opens with a single clip; no multi-select at project start and no project name

**Detail:** Home's picker is single PickVisualMedia (VideoOnly); more shots are added later via the track button. The header title is the first clip's file name (e.g. VID_2024...mp4) rather than an editable project name.

**CapCut:** New project -> multi-select photos and videos -> editor; project name editable from the header.

**Evidence:** HomeScreen.kt:85 PickVisualMedia(), :143-144 VideoOnly; EditorScreen.kt:182 title from videoClips.first().label.

**Files:** app/src/main/java/com/squish/app/home/HomeScreen.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

## gap · low · S — No full-screen preview; preview capped at 300dp whenever a tool is open

**Detail:** PreviewBox.MAX_HEIGHT_DP = 300 with a panel open and there is no expand button; judging a caption or mask on a 300dp-tall portrait frame is hard. Add a fullscreen toggle on the transport bar.

**CapCut:** An expand icon at the preview's corner opens a full-screen player.

**Evidence:** PreviewBox.kt:33 MAX_HEIGHT_DP; EditorScreen.kt:239-246; TimelinePreview.kt:336-383 Transport has only play/skip.

**Files:** app/src/main/java/com/squish/app/editor/PreviewBox.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt

## ux · low · S — Tool panel state is per-screen `remember`, not saved; every tab switch resets 'More room'

**Detail:** tab, panelExpanded and exportSheetOpen are plain remember; LaunchedEffect(tab) resets panelExpanded on every tab change, so a user who chose 'Show timeline' loses it when switching tools. Use rememberSaveable and keep the user's choice per session.

**CapCut:** (none)

**Evidence:** EditorScreen.kt:127-138; grep rememberSaveable in editor/ -> none.

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt

## bug · medium · S — Keyboard likely covers the caption text field: edge-to-edge with no IME insets anywhere

**Detail:** MainActivity calls enableEdgeToEdge(); no imePadding()/WindowInsets.ime/windowSoftInputMode is used in the app, and the caption fields live in a verticalScroll column above the rail at the bottom of the screen. With edge-to-edge the window is not resized for the keyboard, so a field in the lower half of the panel is hidden while typing. Traceable in code; not observed on a device.

**CapCut:** (none)

**Evidence:** MainActivity.kt:20 enableEdgeToEdge() (decorFitsSystemWindows=false; window not resized for the IME on API 30+). AndroidManifest.xml:23-27 MainActivity has no android:windowSoftInputMode. app/build.gradle.kts:13 minSdk=29. EditorScreen.kt:170-171 Scaffold(containerColor=...) with default contentWindowInsets = ScaffoldDefaults.contentWindowInsets = WindowInsets.systemBarsForVisualComponents (no ime), and Box.padding(padding) only pads system bars. EditorScreen.kt:394-399 panel Column weight(1f).verticalScroll(...) is the bottom of the content; EditorScreen.kt:421 hosts CaptionsPanel. CaptionsPanel.kt:194+ the 'Lines' PanelSurface is the third card, and CaptionRow's OutlinedTextField at CaptionsPanel.kt:436-447 has no imePadding/ime-aware scrolling. Repo-wide grep of app/src/main for imePadding|WindowInsets\.ime|adjustResize|windowSoftInputMode|safeDrawing|contentWindowInsets|isImeVisible|imeNestedScroll|bringIntoView: no matches. Other fields exposed by the same omission: MusicPanel.kt:196 (song search, in the Sound bottom panel), OutputSizePicker.kt:213 (used from EditorPanels.kt:265, ExportSheet.kt:128, QuickToolScreen.kt:372), LibraryScreen.kt:225. Caveat: the unset soft-input mode resolves to adjustPan, so the framework may pan the whole window on some devices; not something the code relies on and it still hides the top of the editor. Fix: Modifier.imePadding() on the panel Column (or contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime) on the Scaffold) plus the same on ExportSheet/QuickToolScreen; files: EditorScreen.kt, CaptionsPanel.kt, ExportSheet.kt, QuickToolScreen.kt.

**Files:** app/src/main/java/com/squish/app/MainActivity.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/CaptionsPanel.kt

**Verified:** true

**Verification:** The reviewer's premises check out (MainActivity.kt:20 enableEdgeToEdge(); no imePadding/WindowInsets.ime anywhere; AndroidManifest.xml and res/ have no windowSoftInputMode; EditorScreen.kt:170 Scaffold with default contentWindowInsets, which in Material3 is systemBars-only; the caption OutlinedTextField at CaptionsPanel.kt:436 sits in the weight(1f).verticalScroll Column at EditorScreen.kt:394-399). But the conclusion "the field is hidden while typing" does not follow from that code, and the reviewer admits it was not observed on a device.

The missing windowSoftInputMode is exactly what prevents the failure. With no attribute the window starts in adjustUnspecified; ViewRootImpl resolves that at first traversal to adjustResize only if a View in the hierarchy is a registered scroll container, otherwise to adjustPan. A pure-Compose hierarchy (DecorView > content FrameLayout > ComposeView > AndroidComposeView) has no scroll container (Compose scrollables are modifiers, not Views), so the window runs in adjustPan. In adjustPan the framework itself pans the whole window surface so the focused rect ends above the keyboard: ViewRootImpl.scrollToRectOrFocus compares visible insets (which include the IME for any mode except adjustNothing) with content insets (which exclude the IME under pan), and shifts the content by mScrollY. Two things feed it the right rect: Compose's BringIntoView chain terminates in AndroidBringIntoViewParent -> View.requestRectangleOnScreen(rect) (text fields call bringIntoView for the cursor on focus and on every edit), and Compose UI 1.7 (BOM 2024.09.02 here) overrides AndroidComposeView.getFocusedRect with the focused node's bounds, so the traversal-time pan (rectangle == null path, triggered when the IME insets change) also works. enableEdgeToEdge() only nulls PhoneWindow's content-insets applier (no DecorView padding); it does not disable ViewRootImpl panning. This is the familiar Compose default: the entire screen, header and preview included, slides up when a text field takes focus.

So the concrete claim "hidden while typing" is not produced by the code as it stands. It would only become true if someone adds android:windowSoftInputMode="adjustResize" (then, with edge-to-edge, the window is not resized, the IME arrives purely as insets, and nothing consumes them). What is real is a UX weakness, not a bug: pan shoves the header, video preview and timeline off the top of the screen while a caption is being typed, instead of a clean resize with the preview kept in view. The same applies to the numeric field at OutputSizePicker.kt:219. | The cited code is accurate and does produce the failure. MainActivity.kt:20 calls enableEdgeToEdge(), which sets decorFitsSystemWindows(false), so from API 30 up the window is no longer shrunk for the keyboard; IME size is only delivered as WindowInsets.ime and the app must pad for it. Nothing in app/src/main does: a grep for imePadding, WindowInsets.ime, adjustResize, windowSoftInputMode, safeDrawing, contentWindowInsets, isImeVisible, imeNestedScroll and bringIntoView returns no matches, and AndroidManifest.xml (activity at lines 23-45) sets no windowSoftInputMode. EditorScreen.kt:170 uses Scaffold with the default contentWindowInsets, which in Material3 is systemBarsForVisualComponents (status/navigation bars only, no ime), so the padding at :171 does not account for the keyboard. The panel Column at :394-399 is weight(1f) + verticalScroll at the bottom of that content; CaptionsPanel's per-line OutlinedTextField (CaptionsPanel.kt:436-447) lives in the third PanelSurface ('Lines', :194+), i.e. below the Titles and Auto-captions cards, so it sits in the lower part of the screen exactly where the keyboard lands. Compose's automatic bringIntoView on focus only scrolls the field into the column's own viewport, which still extends under the keyboard, so it does not rescue it. minSdk is 29 (app/build.gradle.kts:13), so the pre-30 'adjustResize still resizes' behaviour is irrelevant for almost all devices (and would need the manifest attribute anyway, which is absent). One honest caveat: with no windowSoftInputMode the framework resolves to adjustPan and may pan the whole window based on AndroidComposeView.getFocusedRect, which can partially uncover the field on some OS builds; that is undocumented behaviour the app does not rely on and it still pushes the whole editor (preview, toolbar) off-screen, so the defect stands either way. Same omission affects the other bottom-panel text fields.

## bug · medium · S — LabeledSlider prints rotation as a percentage: '+4500%', '-18000%'

**Detail:** LabeledSlider formats every value as value*100 with a % sign. MotionPanel's Rotation slider spans -45..45 degrees and MaskPanel's -180..180, so the readouts show '+4500%' and '-18000%'. Add a formatter parameter (degrees, px, plain).

**CapCut:** (none)

**Evidence:** EditorPanels.kt:346 `val readout = if (range.start < 0f) "%+.0f%%".format(value * 100) else "%.0f%%".format(value * 100)` is the only readout path (single LabeledSlider overload at EditorPanels.kt:340-363, no formatter parameter). MotionPanel.kt:118 `LabeledSlider("Rotation", here.rotationDegrees, -45f..45f)` and MaskPanel.kt:183 `LabeledSlider("Rotation", mask.rotationDegrees, -180f..180f)` both pass degree values (Keyframe.kt:34, Mask.kt:49; consumed as degrees in ClipTransformEffect.kt:70 and MaskEffect.kt:77), so 45 degrees reads "+4500%" and -180 degrees reads "-18000%". Note the same unit-blind formatting also mislabels StickersPanel.kt:146 "Size" (sizeSp/64) and MaskPanel.kt:186 "Feather" as percentages, though those are less jarring than rotation.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/editor/MaskPanel.kt

**Verified:** true

**Verification:** Verified in code. EditorPanels.kt:340-363 defines the only LabeledSlider overload; line 346 is exactly `val readout = if (range.start < 0f) "%+.0f%%".format(value * 100) else "%.0f%%".format(value * 100)` with no formatter hook. MotionPanel.kt:118 passes `here.rotationDegrees` (Keyframe.kt:34, a Float in degrees, applied via Matrix.postRotate in ClipTransformEffect.kt:70) with range -45f..45f, and MaskPanel.kt:183 passes `mask.rotationDegrees` (Mask.kt:49, converted with Math.toRadians in MaskEffect.kt:77) with range -180f..180f. Both ranges start below 0, so the signed branch runs and a value of 45 degrees prints "+4500%", -180 degrees prints "-18000%". The readout is genuinely wrong for these callers; the evidence lines cited are accurate. | Confirmed by reading the code. `LabeledSlider` (app/src/main/java/com/squish/app/editor/EditorPanels.kt:340-363) is the only overload in the project and has no formatter parameter; line 346 unconditionally builds the readout as `"%+.0f%%".format(value * 100)` (signed, because the range starts below 0) or `"%.0f%%".format(value * 100)`. MotionPanel.kt:118 passes `here.rotationDegrees` with range `-45f..45f`, and MaskPanel.kt:183 passes `mask.rotationDegrees` with range `-180f..180f`. Both fields really are degrees, not normalised fractions: `Transform.rotationDegrees` (timeline/Keyframe.kt:34) is fed straight to `matrix.postRotate(transform.rotationDegrees)` in media/ClipTransformEffect.kt:70, and `Mask.rotationDegrees` (timeline/Mask.kt:49) is converted with `Math.toRadians(mask.rotationDegrees.toDouble())` in media/effects/MaskEffect.kt:77; the view model clamps both to -180..180 (EditorViewModel.kt:1776, 1811). So a clip rotated 45° reads "+4500%" and a mask at -180° reads "-18000%". The rest of the app does show degrees correctly elsewhere (MotionPanel.kt:209 prints keyframe rotation as "…°", EditorPanels.kt:244 prints the crop/rotate state as "${state.rotationDegrees}°"), which makes the slider readout inconsistent as well as wrong. Severity low-medium (cosmetic but visible on two commonly used sliders), effort S: add a `format: (Float) -> String` parameter (or an enum) to LabeledSlider and pass a degrees formatter at the two rotation call sites.

## bug · high · M — About 25 edits bypass record(): they are not undoable and Undo reverses the wrong thing

**Detail:** record() is the only path onto the UndoStack, yet these mutate state directly: addCaptionAtPlayhead (:855), removeTextOverlay (:1127, used by CaptionsPanel 'Remove' :231 and StickersPanel :100), updateCaptionText (:1445), clearCaptions (:1455), importSrt (:1462), addOverlayClip (:1249), setTransition (:1273), changeLayer (:1276), setOverlayGeometry (:1278), setChromaKey/updateChromaKey (:1289-1311), setMask/updateMask (:1750-1754), setClipTransform (:1797), addKeyframeAtPlayhead/removeKeyframe/setKeyframeEasing/applyMotionPreset (:1829-1866), setAudioTrim/placeAudioAtPlayhead/setAudioClipVolume/nudgeAudioOffset/resetAudioAlignment (:431-468), runAutoSync result (:493), applyRampShape/setSpeedPointAtPlayhead (:790-798), setStabilizeStrength (:1679). Scenario: add an overlay, press Undo -> the overlay stays and the previous unrelated edit (e.g. 'Add title') is reverted while the button said 'Undo: Add title'. Every one of these needs to go through record(label) (they already coalesce by label).

**CapCut:** Every edit, including overlay placement and text edits, is one undo step.

**Evidence:** EditorViewModel.kt:1968-1972 record() is the only path onto `history` (UndoStack.kt:49-61); undo() :1994-1999 restores the top EditSnapshot. Bypassing edits (no record call, mutate via _state.update / mutateTimeline :2008 / updateAudioClip :423 / retime :741): addCaptionAtPlayhead :855-868; removeTextOverlay :1127-1129 (CaptionsPanel.kt:231, StickersPanel.kt:100; the :1938 Delete path is fine); updateCaptionText :1445; clearCaptions :1455; importSrt :1479; addOverlayClip :1249-1271 (EditorScreen.kt:167); setTransition :1273; changeLayer :1276; setOverlayGeometry :1278; setChromaKey/updateChromaKey :1289-1311; setMask/updateMask :1750-1784; setClipTransform :1797 (MotionPanel.kt:110-119); addKeyframeAtPlayhead :1829, removeKeyframe :1837, setKeyframeEasing :1843, clearKeyframes :1852, applyMotionPreset :1866; setAudioTrim :431, placeAudioAtPlayhead :437, setAudioClipVolume :442, nudgeAudioOffset :450, resetAudioAlignment :465; runAutoSync result :493; setSpeedPointAtPlayhead :798 and removeSpeedPoint :812 (SpeedPanel.kt:201); setStabilizeStrength :1679. NOT a bypass: applyRampShape :790-795 already calls record("Speed ramp"). Corrected scenario: because EditSnapshot (EditorModels.kt:239-258, :475-517) carries videoClips/audioClips/textOverlays, 'Add title' then addOverlayClip then Undo ('Undo: Add title') removes both the title and the overlay in one step; redo restores both. With no prior recorded edit, the bypassed edit is not undoable at all (undoLabel stays null). setStabilizeStrength additionally needs stabilizeStrength added to EditSnapshot/restoring for record() to have any effect.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/CaptionsPanel.kt, app/src/main/java/com/squish/app/editor/StickersPanel.kt

**Verified:** true

**Verification:** Confirmed by reading EditorViewModel.kt. record() (:1968-1972) is the only caller of history.record(); undo() (:1994-1999) restores whatever EditSnapshot is on top of the stack. The listed functions mutate _state directly or via mutateTimeline (:2008) / updateAudioClip (:423) / retime (:741), none of which record: addCaptionAtPlayhead :855-868, removeTextOverlay :1127-1129 (called from CaptionsPanel.kt:231 and StickersPanel.kt:100; only the :1938 'Delete' path wraps it in record), updateCaptionText :1445, clearCaptions :1455, importSrt :1479, addOverlayClip :1249-1271 (EditorScreen.kt:167), setTransition :1273, changeLayer :1276, setOverlayGeometry :1278, setChromaKey/updateChromaKey :1289-1311, setMask/updateMask :1750-1784, setClipTransform :1797 (MotionPanel.kt:110-119), addKeyframeAtPlayhead/removeKeyframe/setKeyframeEasing/clearKeyframes/applyMotionPreset :1829-1866+, setAudioTrim/placeAudioAtPlayhead/setAudioClipVolume/nudgeAudioOffset/resetAudioAlignment :431-468, runAutoSync result :493, setSpeedPointAtPlayhead :798 and removeSpeedPoint :812 (SpeedPanel.kt:201), setStabilizeStrength :1679. Two corrections: (1) applyRampShape (:790-795) is NOT a bypass, it calls record("Speed ramp"); remove it from the list, and add removeSpeedPoint (:812) and clearKeyframes (:1852) which are. (2) The described failure is slightly wrong: EditSnapshot (EditorModels.kt:239-258, editSnapshot :475, restoring :498) includes videoClips/audioClips/textOverlays, so after 'Add title' then add-overlay, pressing 'Undo: Add title' restores the pre-title snapshot, which removes BOTH the title and the overlay in one step (and redo brings both back). The overlay does not 'stay'; it is silently folded into the previous step. When there is no earlier recorded edit, undoLabel stays null and the bypassed edit is simply not undoable. Also note stabilizeStrength is not a field of EditSnapshot, so wrapping setStabilizeStrength in record() alone would not make it undoable; it would need adding to the snapshot too. | Confirmed by reading the code. record() (EditorViewModel.kt:1968-1972) is the only caller of history.record(); undo() (:1994-1999) restores the whole EditSnapshot (videoClips, audioClips, textOverlays, effects, ... EditorModels.kt:239-258, 498-517). The listed methods mutate _state / mutateTimeline (:2008-2028) / updateAudioClip (:423-428) with no record() call, so they never create an undo entry. Verified bypassers: addCaptionAtPlayhead :855-868, removeTextOverlay :1127-1129 (called directly from CaptionsPanel.kt:231 and StickersPanel.kt:100; only the deleteSelectedClip path at :1937 wraps it in record), updateCaptionText :1445-1453, clearCaptions :1455-1459, importSrt :1479-1492, addOverlayClip :1249-1271, setTransition :1273, changeLayer :1276, setOverlayGeometry :1278-1284, setChromaKey/updateChromaKey :1289-1311, setMask/updateMask :1750-1784, setClipTransform :1797-1826, addKeyframeAtPlayhead/removeKeyframe/setKeyframeEasing/clearKeyframes/applyMotionPreset :1829-1877, setAudioTrim/placeAudioAtPlayhead/setAudioClipVolume/nudgeAudioOffset/resetAudioAlignment :431-468, runAutoSync :493-500, setSpeedPointAtPlayhead :798-810 and removeSpeedPoint :812-817 (both call retime() at :741-747 which is bare mutateTimeline). Two corrections to the claim: (1) applyRampShape (:790-795) DOES go through record("Speed ramp") at :794, so it is wrongly listed; setSpeedPointAtPlayhead and removeSpeedPoint are the real speed-curve offenders. (2) setStabilizeStrength (:1679) bypasses record, but stabilizeStrength is not a field of EditSnapshot at all, so wrapping it in record() alone would not make it undoable; the snapshot/restoring pair must also gain the field. (3) The stated scenario is inverted: after 'Add title' (recorded) then addOverlayClip (unrecorded), Undo restores the pre-title snapshot, whose videoClips lack the overlay, so BOTH the overlay and the title vanish under a button that said 'Undo: Add title' (the unrecorded edit is silently lumped into the previous step; Redo brings both back). The overlay 'stays' only in the opposite ordering (overlay first, then title), which is correct behaviour. Either way the failure stands: these edits are not independently undoable and one Undo press reverts more than its label claims, which is a real high-severity UX/reliability defect.

## bug · high · S — Autosave is suspended for as long as the recovery banner is showing

**Detail:** The autosave loop `continue`s while state.recovery != null. The banner is non-modal and nothing dismisses it on a first edit, so a user who ignores it and edits for an hour has nothing on disk; a process kill loses everything and the stale draft is offered again next time. Fix: auto-dismiss (or auto-accept) the offer on the first recorded edit, or make the banner modal.

**CapCut:** (none)

**Evidence:** EditorViewModel.kt:112-142 autosave loop, line 122 `if (current.recovery != null) continue` runs before the only `autosave.save` call (line 137); no other save path exists (onCleared 2311-2314 only evicts thumbnails; no ON_STOP/ON_PAUSE observer in the app). `recovery` set at offerRecovery 2156-2172 and cleared only by dismissRecovery 2174-2177 / acceptRecovery 2179-2181; record() at 1968-1972 (the funnel for every edit) never clears it and no edit is gated on it. EditorScreen.kt:369-375 places RecoveryBanner inline in the editor Column; StatusCards.kt:75-136 is a bordered Column with two SquishOutlinedButtons, not a Dialog, so editing continues around it. Note lines 117-121: the skip is intentional to avoid overwriting the on-disk draft with the bare video, so the fix must resolve the offer on first edit (or write to a separate key) rather than drop the guard.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/StatusCards.kt

**Verified:** true

**Verification:** Confirmed by reading the code. (1) EditorViewModel.kt:112-142 is the only writer of the draft: the loop `continue`s at line 122 whenever `current.recovery != null`, before any `autosave.save` call, and there is no other save path (onCleared at 2311-2314 only evicts thumbnails; no lifecycle ON_STOP/ON_PAUSE observer exists anywhere in the app). (2) `recovery` is set at offerRecovery (2171) and cleared only in dismissRecovery (2176) and acceptRecovery (2181); grep over the whole package shows no other assignment. `record()` at 1968-1972, which every edit goes through, does not touch `recovery`, and nothing gates editing on it. (3) EditorScreen.kt:369-375 renders RecoveryBanner inline in the editor Column below the timeline action bar; StatusCards.kt:75-136 is a plain bordered Column with two buttons, not a Dialog, so the timeline, tools and panels remain fully interactive. Scenario: open a clip with a non-trivial stale draft -> banner appears -> user ignores it and edits -> every autosave tick skips -> process death loses all edits, and on the next open `autosave.peek` still returns the old draft so the same stale offer is shown again. The comment at lines 117-121 shows the skip was deliberate (to stop the bare-video state overwriting the draft on disk), so the fix should clear or resolve the offer on the first `record()` (or save under a separate key) rather than just removing the guard. | Confirmed by reading the code. EditorViewModel.kt:112-143 is the only autosave path in the editor (onCleared at 2311-2314 only evicts thumbnails; there is no save-on-stop), and line 122 `if (current.recovery != null) continue` skips the whole tick, including the baseline comparison and save, while an offer stands. `recovery` is set only by offerRecovery (2171) and cleared only by dismissRecovery (2176) and acceptRecovery (2181); a grep of the entire app shows no other write to `recovery` and no edit/undo/history function that clears it. The banner is rendered inline in EditorScreen.kt:369-376 between the timeline action bar and the tool panel, and nothing in the UI reads `state.recovery` to disable the timeline, action bar or panels, so the user can edit freely with the banner up. Consequence follows exactly as claimed: edits made while ignoring the banner never reach disk; after a process kill, load() calls autosave.peek(uri) (line 157) which returns the same old non-trivial snapshot, and offerRecovery re-offers it (2161 passes since it is non-trivial), so the stale draft is presented again and the new work is gone. The in-code comment at 117-121 shows the guard was a deliberate fix for a different bug (saving the bare video over the offered draft), so the correct fix is to resolve the offer on the first recorded edit (dismiss it, or accept-then-apply) rather than delete the guard, or to make the banner modal so no edit can happen while it stands. Severity high (silent data loss), effort S, files: EditorViewModel.kt (autosave loop / history-recording entry point / offerRecovery), optionally EditorScreen.kt and StatusCards.kt if made modal.

## bug · medium · S — Leaving the editor drops the last 1.5 s of edits and never flushes the draft

**Detail:** Autosave runs only on a 1.5 s timer inside viewModelScope; onCleared() evicts thumbnails and does not save. Back pops the nav entry, the ViewModel is cleared, the timer coroutine is cancelled, and whatever changed since the last tick (a drag, a delete, a caption) is not in the draft. Scenario: move a clip then press back immediately -> the draft reopens with the clip in its old place. Save synchronously (off-main) in onCleared or on onBack.

**CapCut:** Back from the editor saves the project instantly and returns to the project list.

**Evidence:** EditorScreen.kt:114 (viewModel() scoped to the nav back-stack entry); SquishNavHost.kt:179 (popBackStack on back, no save); EditorViewModel.kt:112-142 (autosave only in the 1.5 s timer loop; line 137 is the sole autosave.save call in the ViewModel); EditorViewModel.kt:2311-2314 (onCleared evicts thumbnails only). No BackHandler / lifecycle ON_STOP observer exists in app/src/main/java/com/squish/app, so the system back gesture loses the same edits as the BackOrb. Fix should touch EditorViewModel.kt (synchronous off-main flush in onCleared, or a saveNow() called from a BackHandler/ON_STOP observer in EditorScreen.kt); note the `recovery != null` and `isExporting` guards at lines 116-122 must be preserved in any flush path.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt

**Verified:** true

**Verification:** Confirmed by code. EditorScreen.kt:114 obtains the ViewModel via viewModel() inside a NavHost composable, so it is scoped to the NavBackStackEntry; SquishNavHost.kt:179 pops that entry on back, clearing the ViewModel and cancelling viewModelScope. The only autosave.save call in EditorViewModel is line 137, inside the `while (true) { delay(1_500ms) ... }` loop started in init (lines 112-142). onCleared (lines 2311-2314) only evicts filmstrip thumbnails. There is no BackHandler, lifecycle observer (ON_STOP/ON_PAUSE) or DisposableEffect anywhere in the editor that flushes the draft on leave, so the system back gesture has the same effect as the BackOrb. Any state change made after the last tick (up to 1.5 s of edits, or the entire edit if no draft had been written yet) is never persisted, contradicting ARCHITECTURE.md:109 and README.md:120 ("No edit is ever lost"). The code comment at lines 109-111 only acknowledges this loss for a process kill, but a voluntary back press triggers the same loss. | Confirmed by reading the code. (1) The only call site of `autosave.save` in EditorViewModel.kt is line 137, inside the `while (true) { delay(AUTOSAVE_INTERVAL) ... }` loop launched in `init` on `viewModelScope` (lines 112-142); no edit action saves directly, and there is no debounce/flush entry point. (2) `onCleared()` (lines 2311-2314) only calls `FilmstripLoader.evictAll()`; it does not save. (3) EditorScreen.kt:114 obtains the VM with `viewModel()` inside the NavHost `composable`, so it is scoped to the NavBackStackEntry; SquishNavHost.kt:179 wires `onBack` to `popBackStack()`, which clears the entry's ViewModelStore, cancels `viewModelScope`, and therefore cancels the timer coroutine while it is sitting in `delay`. (4) A grep of the whole app package finds no `BackHandler`, no `LifecycleEventEffect`/`ON_STOP`/`ON_PAUSE` observer, and no `DisposableEffect` in EditorScreen that would persist the state on the way out (the only DisposableEffects release players). So any edit made after the last tick and before back is pressed (up to 1.5 s of work, or the entire first edit if it happens within 1.5 s of load, since the `baseline`/`untouched` check at 130-136 would not yet have written any draft) is never written. The code comment at lines 110-111 only acknowledges the 1.5 s window for a process kill; user-initiated back is an ordinary path that hits the same window. ARCHITECTURE.md:109 does say "No edit is ever lost". The reviewer's scenario (drag a clip, press back immediately, reopen draft with clip in old place) follows directly.

## bug · high · S — System back during an export cancels the encode silently and leaves a partial file

**Detail:** There is no BackHandler in EditorScreen or ExportSheet. The scrim's tap is disabled mid-render, but a hardware/gesture back pops the editor, clears the ViewModel, cancels viewModelScope, which triggers invokeOnCancellation -> transformer.cancel(). The user sees the Home screen, no error, no file in the gallery, and squish_<ts>.mp4 is left half-written under exports/. Add a BackHandler that closes sheet/panel first, and while exporting either blocks back or asks to cancel.

**CapCut:** Export runs on a dedicated screen; back asks 'Cancel export?'.

**Evidence:** grep BackHandler|OnBackPressedCallback in app/src/main -> no matches. EditorScreen.kt:114 `viewModel: EditorViewModel = viewModel()` (owner = NavBackStackEntry; SquishNavHost.kt:165-190 registers Editor as a plain composable destination, so back pops it and clears the VM). EditorScreen.kt:439-448 ExportSheet is an inline overlay, not a Dialog. ExportSheet.kt:69-79 scrim `enabled = !state.isExporting` only. EditorViewModel.kt:2104-2114 sets isExporting and runs processor.export in `viewModelScope.launch` with outputFile `exports/squish_${currentTimeMillis}.mp4`; no NonCancellable, no finally, no outputFile.delete(). app/src/main/java/com/squish/app/media/VideoProcessor.kt:211 (note: media/, not editor/) `continuation.invokeOnCancellation { runCatching { transformer.cancel() } }`; Media3 Transformer.cancel() does not remove the partial output. No code sweeps the exports/ dir (only creations at EditorViewModel.kt:2107 and QuickToolViewModel.kt:500). onCleared at EditorViewModel.kt:2311-2314 only calls FilmstripLoader.evictAll(). Contrast ProxyEngine.kt:96-112 / StillClips.kt:87 which delete their partial files. Files a fix would touch: app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt (delete partial on cancel/error), and app/src/main/java/com/squish/app/tools/QuickToolViewModel.kt (same pattern at ~500).

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

**Verified:** true

**Verification:** Confirmed by reading the code. grep finds no BackHandler/OnBackPressedCallback anywhere under app/src/main. EditorScreen.kt:114 uses `viewModel()` with the default owner, which in a NavHost `composable` destination (SquishNavHost.kt:165-190) is the editor's NavBackStackEntry, so a hardware/gesture back pops the editor, clears that entry's ViewModel and cancels viewModelScope. ExportSheet is an in-tree overlay (EditorScreen.kt:439-448), not a Dialog, and ExportSheet.kt:69-79 only disables the scrim tap while exporting; it does nothing about system back. EditorViewModel.export (2089-2147) launches processor.export in viewModelScope.launch (2106) with outputFile = exports/squish_<ts>.mp4 (2107-2109), with no NonCancellable, no try/finally and no delete of the partial file. media/VideoProcessor.kt:211 wires continuation.invokeOnCancellation { transformer.cancel() }, and Media3's cancel() leaves the partially muxed output on disk. Nothing ever sweeps the exports/ directory (the only references to "exports" are the two File(...,"exports") creations in EditorViewModel.kt:2107 and QuickToolViewModel.kt:500). onCleared (EditorViewModel.kt:2311-2314) only evicts filmstrip thumbnails. Result: user lands on Home with no error, no gallery entry, and an orphaned half-written mp4. By comparison ProxyEngine.kt:96-112 and StillClips.kt:87 do delete their partials on failure, so the export path is the odd one out. Severity high, effort S; the same unguarded pattern also exists in QuickToolViewModel.kt around line 500. | Every link in the claimed chain checks out in code. (1) There is no BackHandler, onBackPressed, OnBackPressedCallback or predictive-back handling anywhere under app/src/main (grep over the whole source tree, not just the editor package), and MainActivity.kt is a plain ComponentActivity that only calls setContent { SquishNavHost(...) }. (2) EditorScreen is a NavHost destination (SquishNavHost.kt:165-190); NavHost installs its own back handling, so a system/gesture back while the editor is on top pops the Editor entry. (3) EditorViewModel is obtained via the parameterless `viewModel()` at EditorScreen.kt:114, so it is scoped to the NavBackStackEntry; popping the entry clears the ViewModel and cancels viewModelScope. (4) export() launches the encode in viewModelScope (EditorViewModel.kt:2106) and awaits processor.export(), which is coroutineScope { ... suspendCancellableCoroutine } with `continuation.invokeOnCancellation { runCatching { transformer.cancel() } }` at VideoProcessor.kt:211. Cancelling the scope therefore cancels the Transformer. (5) Nothing deletes the partial output: the only finally block is VideoProcessor.kt:110-114 and it just cancels the progress poll; export()'s onFailure (EditorViewModel.kt:2143-2145) never runs because the coroutine is cancelled, not failed, and even the state reset at :2114 is skipped. Media3's Transformer.cancel() does not remove the output file. Contrast ProxyEngine.kt:96/106 and StillClips.kt:87, which explicitly `partial.delete()` on their own cancel paths; the export path has no equivalent. The file lives under getExternalFilesDir(null)/exports (EditorViewModel.kt:2107-2109) with no history record pointing at it, so no existing cleanup (HistoryRepository.kt:46 only deletes recorded files) will ever remove it. (6) The scrim comment at ExportSheet.kt:66-68 shows the author intended to prevent leaving mid-render, but only the scrim tap (enabled = !state.isExporting, :76) was gated; the ExportSheet has no back interception and EditorScreen's `onBack` at :112/:179 is not gated on isExporting either. Outcome matches the claim: encode cancelled, no error surfaced (the ViewModel holding `failure` is gone), no gallery publish/history record (both are after the awaited call at :2116-2142), orphaned squish_<ts>.mp4. Small nit: the destination after the pop is whatever entry was beneath the editor (Home, Library, Drafts or QuickTool), not always Home.

## bug · medium · S — Two panels edit the same clip.scale/offset fields with different ranges and labels

**Detail:** Blend's Layers card writes clip.scale/offsetX/offsetY via withOverlayGeometry (clamped 0.1..2 / ±1, slider 0.1..1 labelled 'Size'); Motion's Placement writes the same fields via setClipTransform (clamped 0.1..4 / ±1.5, slider 0.2..3 labelled 'Scale') when the clip has no keyframes. Scenario: set Scale 2.0 in Motion, open Blend -> 'Size' shows pinned at 100% (slider max 1.0); touching it snaps the layer to half. Once keyframes exist Motion writes keys but Blend still writes the static fields, which are then ignored by transformAt. Keep one owner of placement (Motion) and leave Blend with opacity/layer only.

**CapCut:** (none)

**Evidence:** TransitionPanel.kt:36 (selected = timeline.selectedClip), :154-166 (Opacity/Size 0.1..1/Across/Up-down sliders -> setOverlayGeometry); EditorViewModel.kt:1278-1284 setOverlayGeometry -> TimelineModels.kt:242-258 withOverlayGeometry (always writes static scale/offset, clamped 0.1..2 / ±1); MotionPanel.kt:43 (targetVideoClip) and :109-120 (Scale 0.2..3 etc. -> setClipTransform); EditorModels.kt:529-530 targetVideoClip includes overlay clips; EditorViewModel.kt:1797-1826 setClipTransform (clamp 0.1..4 / ±1.5; static fields only when keyframes.isEmpty(), else keyframe upsert); TimelineModels.kt:131-132 staticTransform and :147-150 transformAt; Keyframe.kt:74-79 List<Keyframe>.transformAt ignores the fallback whenever keyframes exist. Note: with scale 2.0 the Blend 'Size' readout shows '200%' (EditorPanels.kt:346 formats the raw value) while the slider thumb is pinned at the 1.0 max; touching it snaps the value to <= 1.0.

**Files:** app/src/main/java/com/squish/app/editor/TransitionPanel.kt, app/src/main/java/com/squish/app/editor/MotionPanel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

**Verified:** true

**Verification:** The code confirms the claim. Both panels can target the same overlay clip: TransitionPanel uses timeline.selectedClip (TransitionPanel.kt:36) and shows the Size/Across/Up-down sliders when selected.layer > 0 (line 154); MotionPanel uses state.targetVideoClip (MotionPanel.kt:43), which is EditorModels.kt:529-530 videoClips.firstOrNull { id == selectedClipId } - videoClips includes overlay layers, so the same overlay clip gets Motion's Placement card. Blend's sliders call viewModel.setOverlayGeometry (TransitionPanel.kt:155-166, 'Size' 0.1..1) which is a thin wrapper (EditorViewModel.kt:1278-1284) over TimelineState.withOverlayGeometry (TimelineModels.kt:242-258), which unconditionally writes clip.scale/offsetXFraction/offsetYFraction clamped to 0.1..2 / ±1. Motion's sliders call setClipTransform (MotionPanel.kt:109-117, 'Scale' 0.2..3), which (EditorViewModel.kt:1797-1826) clamps to 0.1..4 / ±1.5 and writes the same static fields only when keyframes.isEmpty(), else upserts a Keyframe. Scenario 1 (Scale 2.0 in Motion, open Blend): Clip.scale = 2.0, Blend's LabeledSlider gets value 2.0 with range 0.1..1; Compose Slider pins the thumb at max, and any touch reports a value <= 1.0, so the layer snaps to half or smaller. Scenario 2 (keyframes exist): Blend still writes the static fields, but Clip.transformAt (TimelineModels.kt:147-150) -> composeTransform (Keyframe.kt:110) -> List<Keyframe>.transformAt (Keyframe.kt:74-79) returns keyframe transforms whenever the list is non-empty and only uses the static fallback when it is empty, so Blend's edits have no visible effect (and the sliders, bound to selected.scale etc., show values that do not match what is on screen). One small correction to the evidence: the readout text is not '100%' - LabeledSlider (EditorPanels.kt:346) formats the raw value, so it reads '200%' while the thumb sits pinned at the slider's max; the snap-on-touch part is accurate. | Confirmed by reading the code. Two panels write the same three Clip fields (scale, offsetXFraction, offsetYFraction) through different paths with different clamps, slider ranges, and labels, and only one of them is keyframe-aware.

Path 1 (Blend tab): TransitionPanel.kt:158-166 renders LabeledSlider("Size", selected.scale, 0.1f..1f), "Across" and "Up / down" (-1..1) for any video clip with layer > 0, calling viewModel.setOverlayGeometry -> EditorViewModel.kt:1278-1284 -> TimelineState.withOverlayGeometry (TimelineModels.kt:242-258), which does clip.copy(scale = ...coerceIn(0.1f, 2f), offsetX/Y ...coerceIn(-1f, 1f)) unconditionally, i.e. it always writes the static fields and never looks at clip.keyframes. It also displays the static field (selected.scale), not transformAt(playhead).

Path 2 (Motion tab): MotionPanel.kt:57 reads here = clip.transformAt(playhead); lines 109-117 render LabeledSlider("Scale", here.scale, 0.2f..3f) and the same two offset sliders, calling setClipTransform (EditorViewModel.kt:1797-1826), which clamps 0.1..4 / -1.5..1.5 and, when clip.keyframes.isEmpty(), writes the same static fields (lines 1814-1820); otherwise it upserts a Keyframe (1821-1824). Motion reaches overlay clips too: state.targetVideoClip (EditorModels.kt:529-530) is any clip in videoClips that is selected, with no layer filter, so the same overlay clip is editable from both tabs.

Scenario 1 (range mismatch): set Scale to 2.0 in Motion on an overlay clip with no keys -> clip.scale = 2.0. Open Blend -> LabeledSlider("Size", 2.0, 0.1f..1f). The Material3 Slider clamps the thumb to the range end, and any touch emits a value <= 1.0, which withOverlayGeometry stores, halving the layer. Confirmed.

Scenario 2 (keyframes): once keyframes exist, Motion writes keys, but Blend's sliders still overwrite the static fields. Clip.transformAt (TimelineModels.kt:147-150) -> composeTransform (Keyframe.kt:103-110) -> List<Keyframe>.transformAt(localMs, staticTransform) (Keyframe.kt:74-79) returns the fallback only when the list is empty, so the static fields Blend just wrote have no effect on the picture. Blend's readout still updates (it reads selected.scale), so the user sees the number change while nothing moves. Confirmed.

One detail in the claim is slightly off: the Size readout does not show "100%". LabeledSlider (EditorPanels.kt:346) formats the raw value, so it prints "200%" while the thumb sits pinned at the slider's max; the snap-to-half-on-touch behaviour is as described.

## bug · low · S — Cut button lights over a caption it cannot split, and pushes an empty undo step

**Detail:** splittable = state.clips.any { it.spans(playhead) } uses the TimelineState that includes caption clips, but splitAtPlayhead runs mutateTimeline over videoClips + audioClips only, so text is never split. With the playhead over a caption in a video gap, Cut is enabled, pressing it does nothing, and record("Cut") still pushes a snapshot so the bar reads 'Undo: Cut' with nothing to undo. Same empty-step issue for 'Close gaps' when there are none. Either split captions too or compute splittable from video+audio, and skip recording when the state is unchanged.

**CapCut:** Split works on text and sticker clips as well.

**Evidence:** TimelineEditor.kt:1631 (splittable over state.clips incl. Text), :1692 (enabled = splittable), :1730 (status text claims every track is split), :1701-1706 (Close gaps always enabled); EditorScreen.kt:323,352-355 (toTimeline() feeds the action bar); EditorModels.kt:581-605 (toTimeline adds captions as ClipKind.Text); EditorViewModel.kt:1930 splitAtPlayhead, :1933 closeGaps, :1968-1972 record pushes unconditionally, :2008-2015 mutateTimeline builds from videoClips + audioClips only; TimelineModels.kt:326-329 withSplitAtPlayhead returns `this` when no video/audio victim; UndoStack.kt:49-61 no no-op dedup. Additional case with the same effect: a video/audio clip whose sourceSpanMs <= 2*MIN_CLIP_MS spans the playhead, so splittable is true but withSplitAtPlayhead (line 328) excludes it and an empty "Cut" step is still recorded. Fix files: TimelineEditor.kt, EditorViewModel.kt (record/mutateTimeline/splitAtPlayhead), TimelineModels.kt, optionally UndoStack.kt (skip when before == current after change).

**Files:** app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt

**Verified:** true

**Verification:** The code produces exactly the claimed failure. EditorScreen.kt:323-353 builds `timeline = state.toTimeline()` and passes it as `state` to both TimelineEditor and TimelineActionBar. EditorModels.kt:581-605 `toTimeline()` puts `videoClips + audioClips + captions` into `clips`, where captions are `ClipKind.Text` clips built from textOverlays. TimelineEditor.kt:1631 `val splittable = state.clips.any { it.spans(state.playheadMs) }` therefore goes true when only a caption spans the playhead, and line 1692 enables the Cut button (line 1730 even prints "Cut splits every track under the playhead"). Pressing it calls EditorViewModel.splitAtPlayhead (line 1930) = `record("Cut") { mutateTimeline { it.withSplitAtPlayhead() } }`. mutateTimeline (lines 2008-2015) constructs its TimelineState from `current.videoClips + current.audioClips` only, so no Text clip is present; TimelineModels.kt:326-329 withSplitAtPlayhead finds no victims and returns `this` unchanged. Nothing in splitAtPlayhead touches textOverlays. Meanwhile record (lines 1968-1972) unconditionally calls history.record before running the change, and UndoStack.record (UndoStack.kt:49-61) has no unchanged-snapshot check, so a snapshot identical to the current state is pushed and publishHistory sets undoLabel = "Cut"; the status line (TimelineEditor.kt:1728) then reads "Undo: Cut" with nothing to undo. closeGaps (line 1933) has the same shape: the Close gaps button (lines 1701-1706) is always enabled, rippleVideo may return an identical layout, and a "Close gaps" step is still recorded. Only nuance: two presses within 700ms coalesce into one step (UndoStack.kt:52), but a single press still pushes an empty step. | Traced fully in code. EditorScreen.kt:323/351-352 feeds TimelineActionBar the caption-inclusive TimelineState from EditorUiState.toTimeline() (EditorModels.kt:581-605, which appends ClipKind.Text clips). TimelineEditor.kt:1631 computes splittable over all those clips, so a caption alone under the playhead enables Cut (:1687-1693) and shows the hint "Cut splits every track under the playhead" (:1730). EditorViewModel.kt:1930 splitAtPlayhead runs record("Cut") { mutateTimeline { withSplitAtPlayhead() } }; mutateTimeline (:2008-2014) builds its TimelineState from videoClips + audioClips only, so withSplitAtPlayhead (TimelineModels.kt:326-329) finds no victims and returns this. record (:1968-1972) pushes history.record before change() unconditionally, and UndoStack.record (UndoStack.kt:49-61) always pushes unless coalescing the same label within 700 ms, so undoLabel becomes "Cut" and the bar shows "Undo: Cut" (TimelineEditor.kt:1728) for a no-op step. Close gaps has the same shape: the MiniAction at TimelineEditor.kt:1701-1706 has no enabled flag and closeGaps (:1933) records regardless of whether rippleVideo changed anything. Nothing in the code prevents the described failure.
