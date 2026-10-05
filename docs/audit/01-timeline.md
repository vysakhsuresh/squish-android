# Timeline and clip editing (track model, selection, split, trim, drag, ripple, snapping, zoom, playhead, action bar, track buttons, undo/process death)

> **Historical. 28 September 2026, and almost none of it is true any more.**
> This was the audit against CapCut that the build plan came out of, written
> before batches B1–B16 existed. It is kept because the *reasoning* in it is
> what `docs/ROADMAP.md` was built from, and because a few of its "gap" entries
> are still open — but every statement about what the app does or does not have
> is seven batches out of date. For what is built, read `ARCHITECTURE.md` §5;
> for what is unseen on a device, `CLAUDE.md` and `docs/DEVICE_FINDINGS.md`.


Squish's strip is technically strong (windowed layout, frame-accurate maths, pinch guard, A/B roll preview) but its track model is the opposite of CapCut's: the main track is free-floating with gaps and overlaps allowed, nothing ripples, clips are not reordered by long-press, scrolling does not scrub, and tapping a clip moves the playhead. Overlays are added only from inside the Blend panel and positioned by four sliders rather than on the picture. Duplicate, replace, freeze, reverse, extract-audio and multi-select do not exist. Traceable defects: the strip refits its zoom and scrolls to 0 whenever the edit's end moves (every tail trim, speed change, delete of the last clip); trim handles truncate sub-millisecond drags so trimming stalls when zoomed in; a split copies keyframes and the incoming transition onto the second half unshifted; a cut within 200 ms of a clip edge produces two overlapping halves; head-trimming a clip parked at 0 grows its tail instead; dragging a caption to 0 shortens it; many edits (transition, layer, overlay geometry, add overlay, keyframes, mask, audio nudges) are not recorded in undo so "Undo: Cut" silently reverts them too; leaving the editor does not flush the 1.5 s autosave; a custom crop rectangle is never persisted; and while the recovery banner is up nothing is saved at all.

## gap · critical · M — Main track is not magnetic: no ripple on trim or delete, gaps and overlaps allowed

**Detail:** CapCut's main track is a ripple track: trimming a clip pulls everything after it, deleting closes the hole, clips can never overlap, and nothing goes black by accident. Squish leaves a gap on every delete and tail-trim (rendered as black, labelled 'Gap - no clip here'), lets a base clip be dragged over another (the preview hard-cuts to the later clip and silently hides the covered footage; the strip draws them on top of each other with no warning), and offers a manual 'Close gaps' button. Speed changes do ripple (resequenceAfterRetime) which makes the behaviour inconsistent. Recommended: ripple by default on the base lane (trim, delete, speed, split-delete), forbid overlap on drag, keep a deliberate 'insert gap/blank' for the rare case.

**CapCut:** Main track is magnetic; trim/delete ripple automatically; overlap is impossible; 'Delete' on a main-track clip closes the gap.

**Evidence:** TimelineModels.kt:273-292 withClipRemoved/withClipMoved leave holes and allow overlap; :294-320 withClipTrimmed 'without moving anything else'; PreviewEngine.kt:776-778 blend(None) hard cut when two base clips overlap; EditorViewModel.kt:1932-1933 closeGaps is manual; :757-782 retime does ripple.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · high · M — No long-press-to-reorder on the main track

**Detail:** Reordering in Squish means dragging a clip freely across others and then pressing Close gaps, with overlap and gap states in between. CapCut: long-press lifts the clip (haptic, scaled up), dragging it between neighbours shows an insertion slot, releasing ripples everything into place; short drag does nothing on the main track. Squish has no long-press gesture anywhere on the strip.

**CapCut:** Long-press a main-track clip to lift it, drag to a new slot, release to ripple-insert.

**Evidence:** TimelineEditor.kt:1089-1105 plain detectHorizontalDragGestures on every clip; TimelineModels.kt:279-292 comment explains free positioning replaced reorder.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## gap · high · L — Scrolling the strip does not scrub; playhead is not fixed

**Detail:** CapCut's core gesture: the playhead is a fixed line at the centre and dragging the timeline moves the footage under it - scrolling IS scrubbing, and a drag during playback pauses it. Squish scrolls the view without moving time, and the playhead is a small draggable head you must aim at; during playback the follow effect re-scrolls every 33 ms so a manual scroll fights it. Squish's model is defensible for a desktop, but on a phone it costs one extra targeting step for every seek.

**CapCut:** Fixed centre playhead; drag the strip to scrub; scrolling while playing pauses playback.

**Evidence:** TimelineEditor.kt:343-347 scrollable only moves scrollMs; :373-385 follow effect keyed on playheadMs re-scrolls each tick while playing; :616-703 playhead as a draggable head.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · medium · S — Tapping a clip moves the playhead as well as selecting it

**Detail:** Every tap on a clip calls select AND scrub to the tapped moment. So a playhead placed precisely (nudged by frame, snapped to a beat) is thrown away the instant you tap the clip to select it for a tool - and there is no way to select without seeking. Combined with 'Cut splits every track' this means you cannot position, then select the one clip you meant, then cut. CapCut: tap selects only.

**Evidence:** TimelineEditor.kt:1075-1084 latestSelect(clip.id); latestScrub(atMs); stacked lanes do the same via stackTap at 435-441.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · medium · S — There is no way to deselect a clip

**Detail:** Tapping an empty part of a lane only scrubs (base lane) or picks from a pile (stacked lanes); the ruler scrubs. onSelect(null) is never sent by any gesture. Selection only clears after Cut or Delete. The trim handles, the 'X selected' hint and the Speed/Motion/Blend panels therefore stay bound to the last tapped clip forever. CapCut: tap blank timeline or preview to deselect.

**Evidence:** TimelineEditor.kt:900-905 lane tap -> stackTap or scrub only; :435-441 pickUnderTap on an empty pile returns null and selects nothing; :801-803 ruler tap scrubs.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · medium · S — Cut splits every track under the playhead, including music and overlays

**Detail:** With nothing selected CapCut splits the main-track clip; with a selection it splits that clip. Squish always cuts everything spanning the playhead - a music bed gets fragmented into as many pieces as there are picture cuts (40 pieces after 'Cut on the beat'), and overlays are cut whenever the picture is. The hint text admits it. Recommended: selected clip if any, else the base clip; keep 'cut all tracks' as a long-press or menu option.

**CapCut:** Split acts on the selected clip, else the main-track clip at the playhead.

**Evidence:** TimelineModels.kt:326-329 victims = clips.filter { it.spans(cut) ... }; EditorViewModel.kt:702-717 cutOnBeats; TimelineEditor.kt:1730 'Cut splits every track under the playhead'.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · high · L — Overlays: no direct manipulation on the picture, buried entry point, video-only

**Detail:** CapCut: Overlay > Add overlay (photo or video) at the playhead on a new track; the overlay shows a selection frame on the preview that you drag, pinch to scale and twist to rotate; tapping it on the preview selects it; it can be dragged vertically between overlay tracks. Squish: 'Add overlay clip' is inside the Blend panel (VideoOnly picker, so no photo overlays), placed at 40% top-right, and moved by four sliders ('Across', 'Up / down', 'Size', 'Opacity'); no rotation there (only via Motion); no handles on the preview; no vertical drag between layers (Raise/Lower buttons); the overlay lane badge in the gutter is inert; an overlay's full source length extends the edit past the main track while addAudioTrack deliberately trims to the video end.

**CapCut:** Overlay button on the toolbar; pinch/drag/rotate the overlay on the preview; drag overlay between tracks.

**Evidence:** EditorScreen.kt:166-168 + 404-414 VideoOnly picker only from Blend; TransitionPanel.kt:154-167 sliders; EditorViewModel.kt:1249-1271 addOverlayClip (no record, no end clamp; compare 378-396); TimelineEditor.kt:482 LaneBadge for overlay lanes has no onClick; TimelinePreview.kt:319-334 OverlaySurface has no gesture handling.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorScreen.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/TransitionPanel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/TimelinePreview.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · S — No Duplicate / Copy clip action

**Detail:** CapCut 'Copy' inserts an identical clip right after the selected one (keeping trim, speed, effects). Squish has nothing; the only way is re-picking the file and re-trimming.

**CapCut:** Select clip > Copy.

**Evidence:** grep for duplicate/copy across app/src finds no clip action; TimelineActionBar (TimelineEditor.kt:1614-1739) has Undo/Redo/Cut/Delete/Close gaps/Start/End/Zoom/Fit only.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · M — No Replace clip

**Detail:** CapCut 'Replace' swaps the media under a clip while keeping its position, length, speed, effects and transition, with a picker that lets you choose which part of the new file fills the slot. Squish requires delete + add + trim + reposition.

**CapCut:** Select clip > Replace.

**Evidence:** No replace path in EditorViewModel.kt; addSources (1186-1229) only appends.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorScreen.kt

## gap · medium · M — No Freeze frame

**Detail:** CapCut 'Freeze' splits at the playhead and inserts a 3 s still of that frame between the halves. Squish has the parts (ThumbnailExtractor.frameAt, StillClips.fromImage, withSplitAtPlayhead) but no action.

**CapCut:** Select clip > Freeze.

**Evidence:** StillClips used at EditorViewModel.kt:1154, frameAt at 1317; no freeze action anywhere.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## gap · medium · L — No Reverse clip

**Detail:** CapCut reverses a clip (renders a reversed copy in the background, then swaps it in). Squish's SpeedRamp cannot express negative speed and nothing renders a reversed file.

**CapCut:** Select clip > Reverse.

**Evidence:** grep 'reverse' finds only BeatDetector list reversal.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/SpeedRamp.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/media/VideoProcessor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · medium · M — No 'Extract audio' from a timeline clip

**Detail:** CapCut 'Extract audio' detaches a clip's sound onto the audio track (so it can be moved, trimmed, faded independently) and mutes the clip. Squish has an extract-audio quick tool that writes a file, but no timeline action; mute is global (muteOriginal) rather than per clip, so a detached track would double the sound.

**CapCut:** Select clip > Extract audio.

**Evidence:** EditorViewModel.kt:371-409 addAudioTrack takes any uri (a video uri would work as a source); muteOriginal at EditorModels.kt:294 is project-wide.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## gap · medium · S — Added clips append to the end instead of inserting at the playhead

**Detail:** CapCut inserts new media after the clip under the playhead (or at the end if the playhead is there). Squish always appends to the end of the base track and then refits the whole strip, so adding a shot into the middle of an edit means add, scroll to the end, drag it back across everything, Close gaps.

**CapCut:** Media added via '+' is inserted at the playhead position on the main track.

**Evidence:** EditorViewModel.kt:1207-1224 start = maxOf timelineEndMs of base clips; fitNonce bumped at 1223.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · medium · M — No snapping while dragging or trimming clips, no haptics

**Detail:** CapCut magnetically snaps a dragged clip's edges to the playhead, other clips' edges and markers, with a haptic tick and a highlighted line. Squish snaps only the playhead when scrubbing (snapToAnything); withClipMoved/withClipTrimmed take the raw delta. The snap candidate list already exists in the view model.

**CapCut:** Clip edges snap to playhead/other clips with haptic feedback during drag and trim.

**Evidence:** EditorViewModel.kt:312-335 snapToAnything used only in scrubTo (281-285); moveClip/trimClip at 1915-1924 pass deltas straight through; no HapticFeedback usage anywhere.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · low · M — No multi-select

**Detail:** Recent CapCut has 'Select multiple' to delete or move several clips at once. Squish's model is a single selectedClipId.

**CapCut:** Select multiple > tap clips > Delete / move.

**Evidence:** TimelineModels.kt:155 selectedClipId: String?; EditorModels.kt:353.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · medium · M — Action bar: ten unlabeled icons in a scroll row, half off-screen, plus a duplicate timecode and a hint line

**Detail:** Undo, Redo, Cut, Delete, Close gaps (a 'Compress' glyph nobody will decode), Start, End, Zoom-, Zoom+, Fit are one horizontally scrolling row; on a phone the zoom/fit trio is past the fold. The comment says descriptions show on long press but MiniAction uses clickable with no long-press, so there is no tooltip. Above it a second timecode duplicates the one in the transport under the picture; below it a hint line that, once anything is undoable, is permanently 'Undo: X · tap anywhere...' and never again explains selection or cut. CapCut instead shows a contextual toolbar (Split, Speed, Volume, Animation, Delete, ... with labels) that changes with the selection, and keeps undo/redo beside the preview. Recommended: labelled, contextual toolbar; drop Zoom-/Zoom+/Fit (pinch + double-tap-to-fit); drop the duplicate timecode.

**Evidence:** TimelineEditor.kt:1663-1719 scroll row; 1701-1706 Close gaps icon Compress; 1755-1810 MiniAction clickable without onLongClick despite the doc at 1751-1753; 1639-1654 timecode vs TimelinePreview.kt:369-381; 1723-1737 hint precedence.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/TimelinePreview.kt

## ux · medium · M — Lanes have no vertical scroll or collapse; with layers the strip overflows the screen

**Detail:** Every lane is a fixed 54 dp and the strip is a plain Column: up to 3 overlay lanes + base + sound + words + effects = ~380 dp. EditorScreen's Column gives the preview a fixed height when a panel is open and the panel weight(1f); the strip and action bar are unweighted, so with two or three overlays the panel collapses to nothing and the tool rail is pushed off the bottom. CapCut keeps the timeline area a fixed height and scrolls the tracks vertically, collapsing overlay/audio tracks to thin bars when not in focus.

**Evidence:** TimelineEditor.kt:92-96 LANE_HEIGHT 54.dp, GUTTER; :417-420 laneCount = overlays + 3; :520-575 Column without verticalScroll; EditorScreen.kt:172-437 outer Column with only the preview or the panel weighted.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · S — Track buttons in the gutter mean three different things

**Detail:** The video tile opens a menu (Video or photo / Blank) that adds; the sound tile opens the Sound panel (another tap to 'pick audio'); the words tile opens the Words panel; the overlay tiles do nothing; the effects tile does nothing. A user who learned 'tap the tile to add' from the video track gets a panel instead on the next two. CapCut's per-track affordance is uniform: '+ Add audio', '+ Add text', '+ Add overlay' each open the picker/editor for that thing directly. Recommended: every tile adds directly (sound -> picker, words -> new title at playhead, overlay -> picker), panel opens as a consequence.

**Evidence:** TimelineEditor.kt:480-499 gutter column; EditorScreen.kt:345-348 onOpenSound = { tab = Sound }, onOpenWords = { tab = Words }; LaneBadge at 482 and 498 inert.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorScreen.kt

## ux · low · S — Undo coalescing merges distinct edits made within 700 ms under one label

**Detail:** UndoStack coalesces by label+time. Labels for Move/Trim include the clip id (good), but 'Cut', 'Delete', 'Add marker' etc. do not: two Cut presses within 700 ms, or two frame-nudge taps on the Cut panel (label 'Trim <id>'), become one undo step. Undo after five quick nudges jumps back all five. A gesture id (drag start) rather than a time window would coalesce only real gestures.

**Evidence:** UndoStack.kt:49-61 coalesce when label equal and within COALESCE_MS (700); EditorViewModel.kt:238-243 nudgeTrim -> trimClip -> record('Trim <id>'); :1930 record('Cut').

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/UndoStack.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · low · M — Stacked sound/words lanes: overlaps pile into one row and are reached by tap-cycling

**Detail:** Two overlapping songs or a title over captions share one 54 dp row, shortest on top, and the one underneath is reached by tapping the same spot again ('2/3' badge). It is clever but undiscoverable, and the hidden clip's waveform and trim grips are invisible until you find it. CapCut gives each overlapping audio/text item its own sub-track row automatically (and collapses them when not selected).

**Evidence:** TimelineEditor.kt:863-870 stacked mode doc; 1363-1364 stackOrder; 1483-1499 pickUnderTap cycling.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · low · S — Empty and edge states on the strip

**Detail:** (1) A new caption has empty text so its bar has no label at all (clip.text ?: clip.label is ''). (2) Deleting the last video clip leaves '0 clips', an 8 s empty ruler and no prompt on the strip to add one. (3) Cut is lit for a clip spanning the playhead even when its source span is <= 400 ms, and then silently does nothing. (4) The transition badge uses the text glyphs '|' and 'X' that the MiniAction comment itself calls out as unreliable. (5) The tail handle gives no indication of how much unused source remains (CapCut greys the unavailable stretch).

**Evidence:** TimelineEditor.kt:1190-1197 label text; EditorScreen.kt:190 '0 clips'; TimelineEditor.kt:1631 vs TimelineModels.kt:328; TimelineEditor.kt:978-983 badge glyphs; 1259-1262 tail handle.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · high · S — Strip refits zoom and scrolls to 0 whenever the edit's end moves

**Detail:** The fit effect is keyed on state.durationMs as well as fitNonce, and fitNonce is >0 for the whole session after load. So every change to the last clip's end - dragging the tail handle of the last clip, changing speed on any clip whose followers are butted, deleting the last clip, adding a caption or sound past the end - re-runs the block, which calls scrollTo(0.0) and onZoomTo(fit). Mid-drag the zoom changes under the finger, so the px->ms conversion of the next drag event changes too. The durationMs key was probably added so the load-time fit waits for the clip to arrive; it needs a consumed-nonce (remember the last handled nonce, refit once when duration first becomes non-zero) instead of re-firing forever.

**Evidence:** TimelineEditor.kt:355-362 LaunchedEffect(fitNonce, viewportPx, state.durationMs) { if (fitNonce <= 0L || viewportPx <= 0) return; ...; scrollTo(0.0); onZoomTo(...) }. fitNonce (EditorModels.kt:362, default 0) is only incremented, never reset: EditorViewModel.kt:185 (load), 1223 (addSources), 1906 (fitTimeline), 2232 (recovery). EditorScreen.kt:323,331 passes state.toTimeline() whose durationMs is clips.maxOfOrNull { it.timelineEndMs } (TimelineModels.kt:173; timelineEndMs = timelineStartMs + durationMs at :105-107). Per-event path: TrimHandle detectHorizontalDragGestures (TimelineEditor.kt:1594-1598) -> SelectionFrame tail handle latestTrim(id, 0, msForDp(delta)) (:1557-1559) -> onTrim = viewModel::trimClip (EditorScreen.kt:334) -> EditorViewModel.kt:1921-1923 mutateTimeline { withClipTrimmed } -> TimelineModels.kt:300-319 changes sourceOutMs and therefore the last clip's timelineEndMs. Each drag event therefore changes state.durationMs, re-runs the fit effect, scrolls to 0 and refits zoom under the finger.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## bug · high · S — Trim handles drop sub-millisecond drag remainders, so trimming stalls when zoomed in

**Detail:** TrimHandle converts each drag event to dp, then the caller converts to ms with .toLong(), truncating toward zero and discarding the fraction. The clip drag (ClipView) and the playhead drag both carry the remainder for exactly this reason; the three trim call sites do not. Above roughly 1 px per ms (about 380 pps at 2.6x density, i.e. one pinch past default) a slow drag yields 0 ms per event and the handle does not move; at ZOOM_MAX (2000 pps) a pixel is ~0.17 ms and trimming is effectively impossible - precisely when precision trimming is wanted.

**Evidence:** Evidence lines are accurate: TimelineEditor.kt:1255-1262 and 1553-1560 call latestTrim(..., latestWindow.msForDp(deltaDp).toLong(), ...) with no carried remainder; TrimHandle at 1594-1598 emits dragAmount / density per event; TimelineWindow.kt:71-74 msForDp returns a Double so toLong() truncates; guardedTrim (TimelineEditor.kt:288-289) and EditorViewModel.trimClip (1921) / trimEffect (1104) pass the Long delta straight through. Compare the carriedMs logic at 1089-1105 and the float positionMs at 660-680. One correction to the framing: the default zoom is 42 pps (TimelineModels.kt:157) and a zoom step is x1.35 (EditorViewModel.kt:1908), so the ~385 pps threshold at density 2.6 is roughly 7-8 zoom-in steps (about 9x default), not "one pinch past default". The ceiling is ZOOM_MAX = 2000 pps (TimelineModels.kt:179), where one pixel is ~0.19 ms at density 2.6.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## bug · high · S — Split copies keyframes and the incoming transition onto the second half unshifted

**Detail:** withSplitAtPlayhead builds the second half with clip.copy(id, sourceInMs, timelineStartMs = cut, speedRamp sliced) and leaves keyframes and transitionIn untouched. Keyframes are measured from the clip's timeline start (Clip.transformAt), so the second half replays the animation from its first key - a push-in split in the middle visibly snaps back to scale 1. The first half keeps keys past its own end and never reaches the end pose. The second half also inherits transitionIn: the strip badge shows an active dissolve into itself, the Blend panel shows it selected, and the next Close gaps pulls the half back over its sibling by the transition length, producing a ghosted dissolve of a shot into itself. Cut on the beat multiplies both effects across every cut.

**Evidence:** TimelineModels.kt:344-355 - withSplitAtPlayhead copies both halves via clip.copy without touching `keyframes` (field at line 73) or `transitionIn` (field at line 57). Keyframe clock: TimelineModels.kt:147-150 (local = timelineMs - timelineStartMs) plus Keyframe.kt:54-56 (atMs is from timeline start) and Keyframe.kt:74-79 (holds at first key when local <= first.atMs, at last key when local >= last.atMs). Motion preset keys at 0 and clip.durationMs: EditorViewModel.kt:1866-1877. Transition inherited: rippleVideo overlap at TimelineModels.kt:194-197 uses transitionIn of every non-first base clip; badge at TimelineEditor.kt:961-979 reads clip.transitionIn.isActive; TransitionPanel.kt:73 reads selected.transitionIn; PreviewEngine.kt:733-748 blends any two overlapping base clips with incoming.transitionIn.type; CompositionFactory.kt:50 also forces the compositor export path whenever any clip has an active transitionIn. cutOnBeats loops the same split per beat at EditorViewModel.kt:702-717. Note withLayerChanged (TimelineModels.kt:238) does reset transitionIn, confirming the split is the omission. Fix: in the split, give the first half keyframes clipped/held to its new durationMs, give the second half keyframes shifted by -played (with an interpolated key at 0) and transitionIn = Transition(). Files: TimelineModels.kt (plus optionally a helper in Keyframe.kt).

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## bug · medium · S — Cutting within 200 ms of a clip edge makes the two halves overlap

**Detail:** The split offset is coerced into [MIN_CLIP_MS, span-MIN_CLIP_MS] but the second half is still placed at timelineStartMs = cut (the playhead), not at where the coerced source offset actually lands. Cut 50 ms into a clip: first half ends at 200 ms, second half starts at 50 ms -> a 150 ms overlap on the base track. The preview hard-cuts to the incoming clip (blend(None)) so 150 ms of footage is skipped; the strip shows two clips over each other. The action bar's splittable test only checks spans(), so the button is lit for this case.

**Evidence:** TimelineModels.kt:338-354 is correct: `offset` is coerced to [MIN_CLIP_MS, span-MIN_CLIP_MS] (line 341) but the second half is placed at `timelineStartMs = cut` (line 352) rather than at `clip.timelineAtSource(clip.sourceInMs + offset)`. The defect is symmetric: cutting within 200 ms of the HEAD gives an overlap (cut at 50 ms into a 1000 ms clip -> halves at 0..200 and 50..850, 150 ms overlap), while cutting within 200 ms of the TAIL gives a GAP (cut at 950 ms -> halves at 0..800 and 950..1150, 150 ms hole), because timelineStartMs=cut is pinned at the playhead while the source split point is clamped 150 ms away in either direction. Also worth noting: the victims filter at line 328 (`sourceSpanMs > MIN_CLIP_MS * 2`) is stricter than the button gate at TimelineEditor.kt:1631 (`spans(playheadMs)` only), so for clips <= 400 ms the Cut button is lit but pressing it does nothing (and still records an undo step "Cut" via EditorViewModel.kt:1930). The same defect is reachable without the button through cutOnBeats (EditorViewModel.kt:713), where any beat within 200 ms of a clip edge overlaps or gaps the halves. mutateTimeline (EditorViewModel.kt:2008+) does not normalise overlaps afterwards, so the state persists. Fix: either compute the second half's start as `clip.timelineAtSource(clip.sourceInMs + offset)` (and the first half's end follows automatically), or make spans()/the button gate refuse cuts inside the MIN_CLIP_MS margin and require sourceSpanMs > 2*MIN_CLIP_MS so the button state matches what the cut will do.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## bug · medium · S — Head-trimming a clip parked at timeline 0 extends its tail instead

**Detail:** withClipTrimmed moves timelineStartMs by the played shift and then coerceAtLeast(0). For a clip whose timelineStartMs is 0 but sourceInMs > 0 (e.g. split, delete the first half, Close gaps), dragging the head handle left reveals earlier source (sourceIn decreases) but the start cannot go negative, so the clip's duration grows to the right: its tail overlaps the next clip by exactly the amount dragged. nudgeAudioOffset handles the equivalent case correctly for moves.

**Evidence:** TimelineModels.kt:306 `playedShift = clip.speedRamp.outputOffsetAt(newIn - clip.sourceInMs, clip.sourceSpanMs)` is passed a negative source offset on any head-drag-left; SpeedRamp.kt:131 `val target = sourceMs.coerceIn(0L, spanMs.coerceAtLeast(0L))` clamps it to 0 so playedShift is always 0 in that direction. TimelineModels.kt:317 then leaves timelineStartMs unchanged while sourceInMs (line 298/309) has decreased, so durationMs (TimelineModels.kt:105) grows and the tail extends right over the next clip by the amount dragged (scaled by the ramp). Applies to every clip with sourceInMs > 0 at any timeline position, not just at 0; the coerceAtLeast(0) on line 317 is not the cause. UI path: TimelineEditor.kt:1255-1257 -> EditorViewModel.kt:1921-1924 -> mutateTimeline (EditorViewModel.kt:2008), no re-ripple. Fix touches TimelineModels.kt (compute the played shift as a signed value, e.g. -outputOffsetAt on the widened window, or via outputDurationMs(sourceIn - newIn) of the sliced-in ramp) and possibly SpeedRamp.kt; the timeline-0 case additionally needs the nudgeAudioOffset-style fallback (keep start at 0, stop revealing earlier source).

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt

## bug · medium · S — Dragging a caption or sticker left past the start shortens it

**Detail:** shiftOverlay clamps startMs and endMs to >= 0 independently, so once the start hits 0 the end keeps moving left. A 2 s caption at 1.0-3.0 s dragged by -1.5 s becomes 0-1.5 s. withClipMoved (video/audio) preserves duration by clamping only the start.

**Evidence:** EditorViewModel.kt:2049-2061 shiftOverlay clamps startMs and endMs to >= 0 independently with no duration preservation and no minimum length. Reached via the text lane (TimelineEditor.kt:557-562 passes guardedMove), the per-frame incremental drag deltas from the clip drag detector (TimelineEditor.kt ~1098-1103, latestMove(clip.id, wholeMs)), EditorScreen.kt:331 (onMove = viewModel::moveClip) and moveClip (EditorViewModel.kt:1915-1918), which routes any textOverlays id, caption or sticker, to shiftOverlay. Because deltas are incremental, once startMs hits 0 each further leftward frame subtracts only from endMs: a 1.0-3.0 s caption dragged 1.5 s left becomes 0-1.5 s, and dragging on collapses it to 0-0. Compare withClipMoved at TimelineModels.kt:288-292, which clamps only timelineStartMs so video/audio keep their length. Fix: clamp delta to -startMs before applying it to both ends (files: EditorViewModel.kt).

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · high · M — Many timeline edits are not recorded in undo, so Undo reverts them together with the previous step

**Detail:** record() snapshots the whole edit before a recorded change; edits that bypass record() are absorbed into whatever the last recorded step was. setTransition, changeLayer, setOverlayGeometry, addOverlayClip, addCaptionAtPlayhead, removeTextOverlay, updateCaptionText, placeAudioAtPlayhead, setAudioTrim, nudgeAudioOffset, setAudioClipVolume, resetAudioAlignment, runAutoSync, setSpeedPointAtPlayhead, removeSpeedPoint, setClipTransform, addKeyframeAtPlayhead, removeKeyframe, clearKeyframes, applyMotionPreset, pinLayerToTrack, setMask/updateMask, setChromaKey/updateChromaKey, stabilizeClip/clearStabilization, importSrt, clearCaptions all mutate without record(). Scenario: Cut (recorded), then add a Dissolve (not recorded), press Undo labelled 'Undo: Cut' -> the cut AND the dissolve vanish; there is no way to undo the dissolve alone. Also the label lies about what it will do.

**Evidence:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt: record() at 1968-1972 is the sole caller of history.record (UndoStack.kt:49-61); mutateTimeline at 2008-2024 and updateAudioClip at 423-428 only _state.update. Unrecorded mutators of snapshot fields: setAudioTrim 431-435, placeAudioAtPlayhead 437-440, setAudioClipVolume 442-443, nudgeAudioOffset 450-460, resetAudioAlignment 465-468, runAutoSync 474+; setSpeedPointAtPlayhead 798-810 and removeSpeedPoint 812-817 (via retime 741-747); addCaptionAtPlayhead 855-868; removeTextOverlay 1127-1129 (only recorded when reached through deleteSelectedClip 1935-1944); addOverlayClip 1249-1271; setTransition 1273-1274 (called from TransitionPanel.kt:80, 90, 107); changeLayer 1276; setOverlayGeometry 1278-1284; setChromaKey/updateChromaKey 1289-1311; updateCaptionText 1445-1453 (claim's "1437-467" is a typo); clearCaptions 1455-1459; importSrt 1462+; pinLayerToTrack 1646+; stabilizeClip 1689-1734 (writes clip.stabilizer at 1721-1732) and clearStabilization 1736-1746; setMask/updateMask 1750-1784; setClipTransform 1797-1826, addKeyframeAtPlayhead 1829-1835, removeKeyframe 1837-1841, setKeyframeEasing 1843-1849 (omitted from the claim), clearKeyframes 1852-1863, applyMotionPreset 1866-1877. Recorded comparators: moveClip 1915, trimClip 1921, splitAtPlayhead 1930, closeGaps 1933. Snapshot/restore: EditorModels.kt:239 (EditSnapshot), 475-495 (editSnapshot), 498-517 (restoring). Label shown: TimelineEditor.kt:1675 and 1728 from state.undoLabel (EditorViewModel.kt:1974-1976).

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · high · S — Leaving the editor does not flush the autosave; up to 1.5 s of edits are lost

**Detail:** Saving happens only on a 1.5 s timer inside viewModelScope; onCleared only evicts filmstrips and there is no save on back or on the navigation pop. Press back within 1.5 s of a cut/trim/drag and that edit is gone from the draft. On a phone this is the normal rhythm: do a thing, hit back.

**Evidence:** EditorViewModel.kt:112-143 is the only caller of `autosave.save` (confirmed by grep across app/src/main/java/com/squish/app); `AUTOSAVE_INTERVAL = 1_500.milliseconds` at :2318. EditorViewModel.kt:2311-2314 `onCleared` only calls `FilmstripLoader.evictAll()`. EditorScreen.kt:114 `viewModel: EditorViewModel = viewModel()` scopes the VM to the nav entry; EditorScreen.kt:179 BackOrb -> `onBack`; SquishNavHost.kt:179 `onBack = { navController.fromTopOf(entry) { navController.popBackStack() } }` (fromTopOf at :44-46 only guards that the entry is on top). No BackHandler, no onStop/onPause, no lifecycle observer exists in the app. Popping the entry cancels viewModelScope at `delay`, so edits made since the last completed tick (0-1.5 s) are never persisted. Same defect in QuickToolViewModel.kt:155-175 (timer-only save, no onCleared). Fix: in onCleared (and/or a BackHandler / ON_STOP observer) run the same untouched/baseline test and call autosave.save(state) synchronously on a non-main thread (e.g. runBlocking(Dispatchers.IO) or a GlobalScope/application-scoped job) before the VM is dropped; files: EditorViewModel.kt, QuickToolViewModel.kt (optionally EditorScreen.kt for a BackHandler).

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorScreen.kt

## bug · medium · S — Nothing is saved while the recovery banner is showing

**Detail:** The autosave loop skips every tick while state.recovery != null. If the user ignores the banner and starts editing the freshly opened video, none of that work is saved; leave the editor and it is all lost, and the banner still offers the stale draft. Dismissing the banner also deletes the old draft before the new work has ever been written.

**Evidence:** EditorViewModel.kt:122 skips every autosave tick while state.recovery != null; 2171/2176/2181 are the only writes to `recovery`, none triggered by an edit; onCleared (2311-2314) does no save; RecoveryBanner (StatusCards.kt:75-135) is a non-blocking card, rendered inline at EditorScreen.kt:369-376, so editing continues behind it. Edits made while the banner is up are never persisted and are lost on leaving or process death, while the stale draft is re-offered. Correction: dismissRecovery clearing the old draft (2175) is not itself data loss - the next tick saves the new state since baseline (set at 201) already differs; only a kill inside that 1.5s window loses anything.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · S — A custom crop rectangle is never persisted, so process death loses it

**Detail:** ProjectAutosave encodes cropAspect but not cropRect. A Custom crop restores as CropAspect.Custom with the default full rect: the export then crops nothing while the panel says Custom. snapToMarkers and stabilizeStrength are dropped too (minor).

**Evidence:** Encode: C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/data/ProjectAutosave.kt:226-273 (cropAspect at 239, no cropRect/snapToMarkers/stabilizeStrength). Decode: ProjectAutosave.kt:450 reads cropAspect only. ProjectSnapshot: ProjectAutosave.kt:619-645, no cropRect field. Restore: EditorViewModel.kt:2223-2264 `applying()` sets cropAspect (2254) and never cropRect; called from acceptRecovery() at EditorViewModel.kt:2179-2192 on a fresh post-death state whose cropRect defaults to CropRect() (EditorModels.kt:351; CropRect.kt:18-29, isFull). Export consequence: VideoProcessor.kt:509-514 skips the Crop because crop.isFull, and CropAspect.Custom.ratio is null (EditorModels.kt:76), so no crop effect is added while the panel still shows Custom (EditorPanels.kt:221,228). Extra: ProjectAutosave.kt:147-151 editKey() is built from encode(), so per EditorViewModel.kt:131-139 a change to cropRect alone never dirties the draft and never triggers an autosave write. The only cropRect restore in the code is the in-memory undo path EditorModels.kt:498-517, not persistence.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/data/ProjectAutosave.kt, C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · S — Head trim leaves keyframes anchored to the new head, so the animation slides off its footage

**Detail:** Keyframes are in played time from the clip's start. withClipTrimmed moves timelineStartMs by playedShift but does not shift keyframes by -playedShift, so trimming 500 ms off the head makes the whole animation start 500 ms later in the footage; a tail trim leaves keys past the end so the end pose is never reached. CapCut keeps keyframes on the frames they were set on when trimming.

**Evidence:** TimelineModels.kt:295-320 withClipTrimmed: playedShift computed at 306, speedRamp sliced at 314, timelineStartMs shifted at 317, keyframes not copied/shifted (should be atMs - playedShift, drop keys < 0 and > new durationMs or clamp). Keyframe.kt:51-61 documents atMs as clip-relative played time; Keyframe.kt:73-88 transformAt interpolates toward a last key that is now past the clip's end, so the end pose is never reached after a tail trim. Clip.transformAt at TimelineModels.kt:147-150 and ClipTransformEffect.kt:59-65 both evaluate from the new head, so preview and export share the defect. Same bug in withSplitAtPlayhead (TimelineModels.kt:349-354): the right-hand half keeps the unshifted keyframe list. Files: app/src/main/java/com/squish/app/timeline/TimelineModels.kt (primary); optionally app/src/main/java/com/squish/app/editor/MotionPanel.kt to stop listing keys outside the clip.

**Files:** C:/Users/vysak/StudioProjects/squish-android/app/src/main/java/com/squish/app/timeline/TimelineModels.kt
