# Squish roadmap: beating CapCut

# Squish build plan

## 1. Executive summary

Squish's foundations are better than CapCut's in three places CapCut cannot follow: everything is on-device with no account, the timeline is a pure state model with one `StateFlow` that preview and export both read, and the preview shares shaders with the exporter. Auto-sync, on-device beat detection and honest look thumbnails are genuine differentiators.

Against that, the audits show the app is not yet an editor a CapCut user can trust, and the gap is not features first, it is correctness:

- **Export is broken for anything beyond cuts.** On Media3 1.5.1 every transition, every picture-in-picture and every timeline gap fails at start (video gaps refused), and even if it ran, transitions would render as hard cuts and the PiP would be hidden under the base track and mis-sized. Cropping at any size other than Original pillarboxes the file. Keyframes run on the wrong clock under speed changes. A music cue placed on a silent or extended timeline starts early.
- **Data is lost by ordinary actions.** A successful export deletes the project and pops the editor. "Start fresh" on the recovery banner deletes a draft with one unconfirmed tap. Back within 1.5 s loses the last edit. Nothing is saved while the recovery banner is up. Back during an export silently cancels it and leaves an orphan file. A hand-drawn crop and the beat grid are never persisted. The backup file is one tick deep. Quick tools overwrite each other's drafts.
- **Undo is a lie in about 25 places.** Overlay placement, masks, chroma key, keyframes, transitions, every audio-panel edit and every caption edit bypass `record()`, so "Undo: Cut" silently reverts them too.
- **Preview and export disagree** on overlay position and size, overlay grading/FX/captions, rotation order, saturation maths, vignette placement and caption timing on retimed clips; the preview also stalls on every cut, drifts its parked frame backwards, and can loop its own unstick reload.
- **The interaction model is the opposite of CapCut's.** Free-floating main track with gaps and overlaps; no ripple; no long-press reorder; scrolling does not scrub; tapping a clip moves the playhead; no way to deselect; no contextual toolbar; ten of eleven tools hide the timeline and the undo button; no direct manipulation of anything on the picture; overlays added from the bottom of the Blend panel and positioned with four sliders; add-text with no keyboard.
- **Feature surface** is missing the everyday tools: voiceover, fades, per-clip volume/filter/crop, duplicate/replace/freeze/reverse, extract audio, in/out animations, canvas background, keyboard-first text, image stickers, project list with names.

The plan below fixes correctness and data safety first (waves 0–1, all traceable bugs kept), then rebuilds the editor's information architecture (wave 2), then makes overlays, the timeline and audio/text work the CapCut way (waves 3–4), then fills the remaining gaps and polishes (waves 5–6).

## 2. Target information architecture (decided)

**Screen stack (portrait):**
1. Header: `[Back] [Project name, editable] [Undo] [Redo] [Export]`. Undo/redo live here permanently and never disappear.
2. Preview: fills all remaining height; fullscreen toggle in its corner; transport row directly under it: `[-1 frame] [Play/Pause] [+1 frame] [timecode / duration]`. Direct manipulation happens here: the selected overlay, text, sticker or mask shows a bounding box with drag, pinch-scale, two-finger rotate, and corner handles (delete, duplicate, edit, scale/rotate). Tap on an item selects it; tap on empty picture deselects.
3. Timeline: **always visible**, fixed centre playhead, dragging the strip scrubs (and pauses playback), pinch zooms, double-tap fits. Lanes from top: overlay rows (auto-stacked, one row per overlapping item, collapse to 20 dp bars when not selected), main video track, audio rows (auto-stacked), text/sticker rows (auto-stacked), effects lane. Vertical scroll inside the strip when rows exceed the area. Each track head has one button that adds directly (video: picker; overlay: picker; audio: picker; text: new title with keyboard up). A ruler-level `+` at the right end of the main track appends media.
4. Toolbar (bottom, one row, horizontally scrollable, labelled icons):
   - **Level 0 (nothing selected):** `Edit` (opens the clip toolbar for the clip under the playhead; the video glyph, not scissors - it was `Cut`, which clashed with item 8) · `Sound` · `Text` · `Stickers` · `Overlay` · `Effects` · `Looks` (chips: Filters / Adjust / Templates) · `Frame` (chips: Ratio / Background / Rotate & flip). Export is only in the header. Finish, Speed, Motion, Blend are removed from level 0.
   - **Level 1 (clip selected; `<` chevron returns to level 0; tapping empty timeline also returns):**
     - Video (main): Split · Speed · Volume · Animation · Delete · Filters · Adjust · Mask · Cutout (Remove BG / Chroma key) · Crop · Rotate · Mirror · Transition (on the join) · Stabilize · Track · Freeze · Reverse · Duplicate · Replace · Extract audio · Switch to overlay.
     - Video/photo (overlay): the same list minus Transition, plus Opacity · Layer (raise/lower) · Switch to main.
     - Audio: Split · Volume · Fade · Speed · Beats · Sync · Voice · Delete · Duplicate.
     - Text/sticker: Edit (keyboard up immediately) · Style · Animation · Track · Split · Duplicate · Delete · Opacity.
     - Effect: Strength · Split · Delete.
   - A tool opens as a **bottom sheet over the toolbar region only**, with `[name] [Reset] [Done]`; the strip compresses to ruler + selected row but never disappears; the sheet has a guaranteed minimum height; system back closes the sheet, then the level, then leaves.
5. **Track model:** the main track is **magnetic**: clips are always butted, trim/delete/speed/transition ripple, overlap is impossible, long-press lifts a clip to reorder, a deliberate "Insert blank" adds a black clip. Overlay/audio/text tracks are free-placement with snapping (playhead, clip edges, markers, beats) and haptic ticks; overlapping items auto-stack onto extra rows; long-press vertical drag moves between rows.
6. **Overlays:** added from `Overlay` or the overlay track button; picker is images and videos; lands at the playhead on the lowest free overlay row, selected, with the bounding box showing; keeps its audio with its own Volume; scale/position are canvas-relative and identical in preview and export; blend modes are a later add.
7. **Per-clip, not global:** filters, adjust, crop, rotate, mirror, volume, mute and opacity live on the clip, with "Apply to all" on the relevant sheets. Project-level: ratio/canvas, background, export settings.
8. **One colour and one glyph per concept everywhere** (rail, gutter, sheet titles, clip tint): Violet video, Magenta overlay, Cyan sound, Amber text/stickers, Blue effects/looks, Orange primary action. "Cut" means split; trimming is only the handles.
9. **Tap budgets:** add music 2, add text 2 with keyboard, split 2, delete 2, change speed 3, add overlay 2; never more when a sheet is open.

## 3. Implementation batches

Paths are relative to `app/src/main/java/com/squish/app/`. Bug tags reference the audits (T=timeline, O=overlay, A=audio, X=text, V=visual, P=preview, E=export, D=data, S=shell, L=layout). Every verified bug appears in exactly one batch. Batches in the same wave own disjoint files and can run in parallel; "after" lists hard dependencies.

---

### Wave 0 (serial)

#### B1 — Data safety and exit paths
**Goal:** no ordinary action deletes or drops work; leaving, exporting, backing out and process death are all safe.
**Tasks:**
1. Export keeps the project (E5/D1): `markCompleted` marks the draft `exported` in meta instead of deleting; success navigates to Export **without** popping the editor; ExportScreen gets "Back to editor"; Drafts lists exported projects with a badge. Same in `QuickToolViewModel` (no `clearDraft` on export).
2. "Start fresh" (D2/S6) routes through `ConfirmDialog`; retitle banner "Your edit of this clip is saved"; primary = Continue; secondary = "Start a new project" which renames the old draft into `projects/trash/` instead of deleting.
3. Trash + snapshot (D3): `ProjectAutosave.delete` moves the slot into `trash/<slot>-<ts>/` (purge after 30 days); keep a time-based snapshot (`.snap.json`, updated at most every 10 min) beside `.bak.json`; Drafts discard gets an Undo snackbar. Same for `ToolAutosave`.
4. Flush on leave (T8/D4/S5/V20/L5): `saveNow()` honouring the baseline/recovery/isExporting rules, called from `onCleared` (blocking IO) and from a `BackHandler`/ON_STOP observer in `EditorScreen`; same for `QuickToolViewModel`.
5. Autosave while the recovery banner is up (T9/D5/L4): the first recorded edit auto-dismisses the offer (after renaming the old draft into trash); the banner is also made modal until answered on process-death restore.
6. Persist `cropRect`, `snapToMarkers`, `stabilizeStrength` and the beat grid (T10/V2, A8); bump `FORMAT_VERSION`; restore in `applying()`.
7. Atomic meta (D6): write meta via scratch+fsync+rename, before the live rename; `drafts()` falls back to live files without meta.
8. Tool drafts per session (S3): route carries a session UUID; `ToolAutosave` slot = `<toolId>-<session>`; drafts list shows all.
9. Back during export (E6/S4/L6): `BackHandler(enabled = isExporting)` → "Stop exporting?" confirm; export `Job` held in the VM with `cancelExport()`; `VideoProcessor.export` deletes `outputFile` in a `finally` on cancel/failure; `keepScreenOn` on the editor and tool windows while exporting.
10. Route double-decode (S2): remove the three `Uri.decode` calls in `SquishNavHost`.
11. System bar icons (S1): `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))`.

**Files:** `data/ProjectAutosave.kt`, `data/ToolAutosave.kt`, `editor/EditorViewModel.kt` (autosave loop, onCleared, export success, dismissRecovery, applying), `editor/EditorScreen.kt` (BackHandler, keepScreenOn), `editor/StatusCards.kt`, `tools/QuickToolViewModel.kt`, `tools/QuickToolScreen.kt`, `navigation/SquishNavHost.kt`, `navigation/Destinations.kt`, `export/ExportScreen.kt`, `home/HomeViewModel.kt`, `history/DraftsScreen.kt`, `media/VideoProcessor.kt` (finally only), `MainActivity.kt`.
**Size:** ~1200 lines. **After:** none.

---

### Wave 1 (parallel: B2, B3, B4, B5)

#### B2 — Timeline model correctness (pure `timeline/` package + strip mechanics)
**Goal:** every timeline operation produces a valid, non-overlapping, keyframe-correct state; the strip does not fight the finger.
**Tasks:**
1. Strip refit on duration change (T1): consume the nonce once; never refit on `durationMs`.
2. Trim handles carry sub-ms remainder (T2), matching the clip-drag path.
3. Split rebases keyframes on both halves and clears `transitionIn` on the second half (T3, V10); head/tail trim shifts and clamps keyframes (T11); helper in `Keyframe.kt`.
4. Cut inside the 200 ms margin: place the second half at `timelineAtSource(sourceIn + offset)`; refuse cuts on clips ≤ 2×MIN (T4); `splittable` computed from video+audio only and honouring the same rule.
5. Head-drag-left: signed played shift via the ramp (T5, `SpeedRamp.outputOffsetAt` negative-safe); clip at 0 stalls instead of extending.
6. **Magnetic main track in the model:** `withClipRemoved`, `withClipTrimmed`, `withTransition` (V3), `withClipMoved` on layer 0 ripple followers and never overlap; `withClipReordered(id, index)` added for long-press; `withOverlayGeometry` unified clamps (O6 model side); overlay lanes auto-bump to the next free row on overlap (O7).
7. `withSplitAtPlayhead(selection)`: selected clip if any, else main-track clip; `withSplitAllTracks` kept for the beat cutter's video-only use (A9 model half).

**Files:** `timeline/TimelineModels.kt`, `timeline/SpeedRamp.kt`, `timeline/Keyframe.kt`, `timeline/TimelineEditor.kt` (fit effect, TrimHandle, `splittable`, Cut hint text only), `tools/jvm/*` (add executed checks for split/trim/keyframe rebasing).
**Size:** ~900 lines.

#### B3 — Undo and view-model correctness
**Goal:** every edit is one undo step with a truthful label; the view model's per-clip maths is right.
**Tasks:**
1. Wrap all unrecorded mutators in `record()` (T7/O4/A7/X3/V1/L3): audio (trim, place, level, nudge, reset, auto-sync), speed points, captions (add, edit, remove, clear, import, auto-caption run), overlay (add, layer, geometry), chroma, mask, transform, keyframes, presets, pin/unpin, stabilize (record in the coroutine), transitions. Add `beats` and `stabilizeStrength` to `EditSnapshot`. Coalesce by gesture id, not by 700 ms window; slider labels per clip. "Clear all" captions asks first.
2. `shiftOverlay`/`resizeOverlay` clamp delta to `[-start, trimmedDuration-end]` (T6/X7); add-at-end places the item ending at the last frame, never after it (X8).
3. Split of a selected caption/sticker splits the text item (X9/L8); no-op edits do not push an undo step (guard `before == after`).
4. Stabilizer baking (V5): `setClipTransform`/`addKeyframeAtPlayhead`/MotionPanel read the **user** transform (`composeTransform` without the fix); add `Clip.userTransformAt`.
5. `LabeledSlider` gains a formatter; rotation prints degrees (V13/L2).
6. Stabilize card keyed by clip id (V14); `sampleFrame` uses `clip.sourceAt` and the proxy (V15); look thumbnails from the clip under the playhead (V18); "Keep it smooth" clamps ramp points instead of flattening (V17).
7. Audio: `markBeats` merges with manual markers (A10); `cutOnBeats` cuts video only (A9); auto-sync applies the head clip's `sourceIn - timelineStart` (ramp-aware) and analyses the head clip's uri; Align readout subtracts the same delta (A6); unreadable audio refused with `FileUnreadable`, no zero-length clip, `setAudioTrim` cannot throw (A11); `videoWaveform` decode dropped (A13).
8. Captions: auto-caption maps segments through the carrying clip (`timelineAtSource`), filters to its source window, and replaces the previous auto run (X1 basic, X4); rows keyed by id (X10); status text fixed and SRT notice clears (X13); `shiftedInto` and `TimedEffect.shiftedInto` map through the ramp (X2).
9. Deselect entry point `selectClip(null)`; `insertSourcesAtPlayhead`; `reorderClip` (used by B6/B7).

**Files:** `editor/EditorViewModel.kt`, `editor/UndoStack.kt`, `editor/EditorModels.kt`, `editor/TimedEffect.kt`, `editor/EditorPanels.kt`, `editor/MotionPanel.kt`, `editor/MaskPanel.kt`, `editor/SpeedPanel.kt`, `editor/AudioPanel.kt`, `editor/CaptionsPanel.kt`, `editor/StickersPanel.kt`, `editor/BeatPanel.kt`, `editor/LookThumbnails.kt`, `editor/EffectsPanel.kt`.
**Size:** ~1300 lines.

#### B4 — Preview engine reliability and fidelity
**Goal:** playback never stalls on a cut, the parked frame is the right frame, nothing rebuilds the player per slider tick, and the preview shows overlays and captions the way the export will.
**Tasks:**
1. Video lookahead (P1): when the clock clip has <1 s left, park the idle roll and upcoming overlay players on their first frame; surfaces stay hidden until `onRenderedFirstFrame`/READY.
2. `redraw()` seeks to `clip.sourceAt(positionMs)` ±1 ms, never `currentPosition-1` (P2); end of edit seeks to `sourceAt(end-1)` (P6).
3. Unstick escalates only when `bufferedPosition` has not advanced; clears scoped to the stuck key; CLOSEST_SYNC while a scrub is in flight, EXACT on release (P3, P9 with a `scrubbing` flag; audio primed once on drag end).
4. Overlay players get `.watched(key)`, per-player stall tracking, `attachSurface` from `OverlaySurface` (P4).
5. Speed ramps: dead-band 0.03 + 150 ms minimum interval on `setSpeed`; immediate push on clip change (P5).
6. Lifecycle: pause on ON_STOP/ON_PAUSE (P7).
7. `editSignature` drops opacity/transform/keyframes/volume/captions/grade; `setTimeline` skips `primeAudio` when the audio set is unchanged (O5/A2/X5).
8. Mask and chroma key read from `AtomicReference` per frame; no chain rebuild per tick (V4); `MaskEffect`/`ChromaKeyEffect` keep the value constructor for export.
9. `setSpeed` pitch only for base players (A1); audio players disable the video track type (A12).
10. Overlay surfaces: no rotation, grade, FX or captions (O3/V7/X6) — export parity per B5 decision; overlay translation measured in canvas space like the export (O1 preview side, with B5).
11. Captions drawn **once, in Compose, above every surface, from timeline time** (retire `LiveCaptionOverlay` in the preview; keep `CaptionRenderer`): fixes X6, X12 preview, X14, X11 preview (box = `effectiveCrop`), and overlay-caption doubling.
12. Gap veil: "End of picture" vs "Gap" wording; veil hidden when an overlay covers the moment (O8); `pictureOverlay` z-index above overlay surfaces (O10 z-order).
13. Proxy swap deferred until paused or seeking (P8 swap half).

**Files:** `editor/PreviewEngine.kt`, `editor/TimelinePreview.kt`, `editor/PreviewBox.kt`, `media/LiveCaptionOverlay.kt`, `media/effects/MaskEffect.kt`, `media/effects/ChromaKeyEffect.kt`, `editor/CaptionLayer.kt` (new).
**Size:** ~1200 lines.

#### B5 — Export pipeline: Media3 upgrade, transitions, PiP, sizing, audio
**Goal:** every edit the preview can show exports identically, or fails before it starts with a specific message.
**Tasks:**
1. Upgrade `media3` in `gradle/libs.versions.toml` to the newest stable 1.x; fix API breaks. **Device gate:** export a two-clip Dissolve and a PiP starting at 2 s. If video gaps are still refused, replace `addGap` with a filler item (black `StillClips.blank` + `AlphaScale(0)`) sized to the canvas (E1). If the compositor still draws the primary on top, invert roles: overlay stack becomes the primary (padded to full canvas with a transparent-surround GlEffect), base rolls become secondaries (E3 order).
2. Transitions in the export (E2): `VideoCompositorSettings.getOverlaySettings(inputId, timeUs)` from `transitionAlphaAt` — alpha for Dissolve/Dip, anchor translation for Slide, time-varying `MaskEffect` for Wipe; ≥1 s of transition renders visibly.
3. Overlay geometry in canvas space (O1/O2/E3): `Presentation` to the **final** canvas (post-crop, post-outputP) before `ClipTransformEffect`; overlay canvas = base output size.
4. Captions and FX move to `Composition.Builder.setEffects` (composition level) so they draw once over all layers and over gaps (X12 export, E2 caption note).
5. Crop-aware output size (E4, E13): `EditorUiState.outputSize` derived from `effectiveCrop` + `outputP`; used by encoder, summary and bitrate; drop the redundant per-clip Presentation when the composition one exists.
6. Audio sequences use `addGap` for the lead-in (A3/E7); audio clips clipped to `sourceIn..sourceOut` with room converted through the ramp (A4); >2-channel input downmixed to stereo always (A5/E14); preflight reads every audio uri and runs `MediaCompat` on it (E15).
7. Keyframe clock (V6/E8): `ClipTransformEffect` maps source-elapsed → played via `speedRamp.outputOffsetAt` for keyframes, source time for the stabilizer; overlays get `SpeedChangeEffect`; rotation order (V8): stabilizer before rotation, user transform after rotation in canvas NDC (preview already does the latter).
8. Saturation (V9): all non-identity grades go through `LookEffect`; drop the `HslAdjustment` path. Grade/FX before crop/Presentation so vignette/grain/shake match the preview (V12).
9. Auto-reframe per clip (V11/E12 export half): `ReframeEffect` latches its origin and takes the clip's own `sourceIn`/ramp; analysis per clip lands in B12.
10. `isExporting` held until `onResult` with a `Saving to gallery` stage (E9); publish result carried in `ExportRecord` and "Saved to your gallery" only when true (E10); `StatFs` on the exports dir with a 2.2× budget (E11 preflight half); Render disabled while `preparingStills > 0` (E16); history persisted on IO (E17); `SquishError` gains cases for gap/compositor failures and names the missing clip.

**Files:** `gradle/libs.versions.toml`, `media/VideoProcessor.kt`, `media/CompositionFactory.kt`, `media/ClipTransformEffect.kt`, `media/effects/ReframeEffect.kt`, `media/effects/ColorGrade.kt`, `media/AudioMixing.kt`, `media/SquishError.kt`, `media/ExportPresets.kt`, `media/StillClips.kt`, `media/GallerySaver.kt`, `media/SquishTextOverlay.kt`, `data/ExportRecord.kt`, `data/HistoryRepository.kt`, `editor/ExportSheet.kt`, `ui/components/OutputSizePicker.kt`, `ui/components/ExportProgressCard.kt`, `tools/jvm/*` (framing/keyframe-clock checks).
**Size:** ~1500 lines. **Risk:** highest in the plan; the Media3 gate must run on the phone before anything else in this batch is built on.

---

### Wave 2 (serial)

#### B6 — Editor information architecture
**Goal:** the screen described in section 2: header with undo/redo, full-height preview, always-visible strip, level-0/level-1 toolbars, tool sheets; and the view model split so later waves can work in parallel.
**Tasks:**
1. Mechanical split of `EditorViewModel.kt` into `editor/edits/ClipEdits.kt`, `AudioEdits.kt`, `TextEdits.kt`, `LayerEdits.kt`, `AnalysisEdits.kt` (internal helpers holding the state flow); no behaviour change; `assembleDebug` green.
2. Header: project name (editable, stored in meta), Undo/Redo moved from the action bar; Export only here; Finish tab removed; export settings live only in `ExportSheet` (one chip list).
3. Preview fills remaining height; fullscreen route sharing the same engine; transport with ±1 frame.
4. `ToolBar.kt` (new): level-0 and level-1 definitions from section 2; swap on selection; `<` chevron; tap on empty lane/picture deselects; labelled icons; single colour/glyph table.
5. `ToolSheet.kt` (new): bottom sheet with title/Reset/Done, min height, strip compressed to ruler + selected row beneath it; `BackHandler` closes sheet → level → editor; `rememberSaveable` for tab/sheet state.
6. Panels re-homed: Blend dissolved (Transition → join badge sheet; Layer/Opacity → overlay toolbar; Mask, Chroma → level-1); Templates → Looks chips; Background removal → Cutout; Stabilize/Track → level-1; Sound panel split into Music / Voice & FX / Sync chips with Add first; Motion → Animation + Placement.
7. Action bar reduced to Split/Delete/Duplicate under the strip; zoom buttons dropped (pinch + double-tap fit); duplicate timecode removed; "Cut" tab renamed/removed; transition badge uses a vector glyph; hint line fixed.
8. Stale copy: "Captions tab" → "Text"; Effects/Looks file names swapped to match.

**Deferred from section 2's level-1 lists** (a button for a tool that does not exist yet would do nothing, so each arrives with the batch that builds it; `ToolRules.toolsFor` says the same): Volume on main shots and overlays - per-clip sound, B8 (a shot's Volume opened the camera sound for the whole edit, so it was taken off; that setting stays on Sound → Voice & FX); Fade and Voice on sounds, B9; Opacity on text and stickers, B10; Rotate, Mirror, Freeze, Reverse, Replace, Extract audio, B11; Filters, Adjust and Crop per clip, and Frame's Background chip (canvas background), B12. Precision controls removed with the old panels: the sound's In/Out ±100 ms trim rows go for good in B9 task 8 (the handles trim); a sound's "Move to playhead" is back as "Start at the playhead" on its Sync sheet; a shot's in/out to the playhead is Split and Delete.
**Action bar, as built:** it shows only what the toolbar under it does not - the selection's line alone while a clip's tools are up, Split with nothing selected, and Split/Duplicate/Delete while a sheet covers the toolbar (section 2 item 9: never more taps with a sheet open).

**Files:** `editor/EditorScreen.kt`, `editor/ToolBar.kt` (new), `editor/ToolSheet.kt` (new), `editor/EditorViewModel.kt` + `editor/edits/*.kt` (new), `editor/EditorModels.kt`, `editor/TransitionPanel.kt` (→ `TransitionSheet.kt`, `LayerSheet.kt`), `editor/EffectsPanel.kt`, `editor/FxPanel.kt`, `editor/MotionPanel.kt`, `editor/AudioPanel.kt`, `editor/TrackPanel.kt`, `editor/EditorPanels.kt`, `timeline/TimelineEditor.kt` (action bar, hint, badge only), `ui/theme/Color.kt`, `ui/components/Buttons.kt`.
**Size:** ~1500 lines (the VM split is moved lines, not new logic).

---

### Wave 3 (parallel: B7, B8)

#### B7 — Magnetic timeline and gestures
**Goal:** CapCut's timeline feel: fixed playhead, scroll-to-scrub, ripple, long-press reorder, snapping with haptics, auto-stacked rows, vertical scroll.
**Tasks:**
1. Fixed centre playhead; dragging the strip scrubs; scrolling while playing pauses; follow-while-playing scrolls the strip, not the head.
2. Tap = select only; tap empty = deselect; ruler drag reports `onScrubbingChange`.
3. Main track ripple in the UI: delete closes the gap, tail-trim pulls followers, no manual "Close gaps"; "Insert blank" action.
4. Long-press lifts a main-track clip (haptic, scaled), insertion slot shown, release calls `reorderClip`; long-press vertical drag on overlay/audio/text rows moves between rows.
5. Snapping of dragged/trimmed edges to playhead, clip edges, markers, beats with a highlighted line and `HapticFeedback`.
6. Auto-stacked rows for overlays, audio and text (replace the tap-cycling pile); rows collapse to thin bars when not selected; vertical scroll inside a fixed-height strip.
7. Tail handle greys the unused source; keyframe diamonds tappable (jump/select); empty-strip state with "Add media"; empty caption label placeholder; track buttons all add directly (overlay row button included).

**Files:** `timeline/TimelineEditor.kt`, `timeline/TimelineWindow.kt`, `timeline/TimelineGestures.kt` (new), `timeline/TimelineModels.kt` (row assignment helpers only), `timeline/TimelineLanes.kt` (new).
**Size:** ~1400 lines. **After:** B2, B3, B6.

#### B8 — Overlays done properly
**Goal:** an overlay you can grab.
**Tasks:**
1. `Overlay` tool + overlay row button → `PickMultipleVisualMedia(ImageAndVideo)`; photos and PNGs become overlays (transparent PNG kept via a still path that preserves alpha for export: render stills to PNG frames via image `MediaItem` with `setImageDurationMs`, not H.264); lands at playhead on the lowest free row, selected.
2. `OverlayHandles.kt` (new) in `pictureOverlay`: bounding box, drag, pinch-scale, two-finger rotate, corner handles (delete, duplicate, scale/rotate), snap lines at centre/edges/other layers with haptics, numeric readout while dragging; auto-key when keyframes exist; Reset placement.
3. One owner of placement (`setClipTransform`) with one clamp set; `LayerSheet` keeps Opacity, Raise/Lower, Switch to main/overlay (which resets geometry and ripples the main track) (O6/O9/L7).
4. Overlay audio kept: preview volume = `clip.volume`, export keeps audio with `buildAudioProcessors(clip, clip.volume)`; Volume + Mute per clip for **all** video clips; `originalVolume/muteOriginal` migrated to per-clip on load.
5. Proxies per media item (`proxyUris: Map<Uri, Uri>`), started for every added video/overlay (P8).
6. Overlay lengths clamped to the main track's end on add; overlapping overlays on a row auto-bump (UI half of O7); overlay lanes appear only when needed and are not capped at 3.
7. Recovery/preflight name the missing layer file (O10 restore half, with B15 relink).

**Files:** `editor/OverlayHandles.kt` (new), `editor/TimelinePreview.kt`, `editor/PreviewEngine.kt`, `editor/LayerSheet.kt`, `editor/edits/LayerEdits.kt`, `editor/edits/ClipEdits.kt` (volume), `editor/EditorModels.kt` (proxy map, per-clip volume), `media/VideoProcessor.kt` (overlay audio, per-clip volume), `media/CompositionFactory.kt` (image items), `media/StillClips.kt`, `media/ProxyEngine.kt`, `data/ProjectAutosave.kt` (fields).
**Size:** ~1400 lines. **After:** B4, B5, B6.

---

### Wave 4 (parallel: B9, B10)

#### B9 — Audio suite
**Goal:** the audio tools a vlogger reaches for, in the contextual toolbar.
**Tasks:**
1. Voiceover: `RECORD_AUDIO`, `VoiceRecorder.kt` (AudioRecord → WAV, NoiseSuppressor/AGC), Record button with 3-2-1 countdown, records from the playhead while the timeline plays, lands as an audio clip with a mic icon, re-record replaces.
2. Fade in/out fields on `Clip`, `FadeProcessor.kt`, preview fade via per-tick volume, strip triangles, persisted.
3. Gain to 400%; per-clip Voice changer on any clip (replaces global `voiceEffect`); preview pitch on the right players only.
4. Extract audio from a timeline clip (detached audio clip at the same in/out, clip muted); "Rip" tool renamed "Extract audio".
5. Beats on the clip: dots that travel with the clip, tap-to-add beat while listening, density as a real toggle; Beat chips are toggles.
6. Music library: loop-to-fit, favourites/recent, Settings deep link on denied permission; procedural SFX set (whoosh, pop, click, riser, ding) as a category.
7. Music audition pauses the engine (pause request in state honoured by `TimelinePreview`), stops on chip switch and ON_STOP, audio focus (A15).
8. Trim card removed (handles do it); Align readout fixed copy; camera switch hidden when no audio; waveform bucketed at fixed ms-per-bucket, full-length decode for long files (A14).
9. Volume keyframes (data + preview + export) as the last item if budget remains; ducking under speech spans behind a toggle.

**Files:** `AndroidManifest.xml`, `media/audio/VoiceRecorder.kt` (new), `media/audio/FadeProcessor.kt` (new), `media/audio/MusicSynth.kt`, `media/audio/MusicLibrary.kt`, `media/audio/Waveform.kt`, `media/audio/PcmDecoder.kt`, `editor/AudioSheet.kt` (from `AudioPanel.kt`), `editor/MusicPanel.kt`, `editor/BeatPanel.kt`, `editor/edits/AudioEdits.kt`, `editor/EditorModels.kt` (audio fields only, coordinated with B10 via separate sections), `media/VideoProcessor.kt` (audio processors only), `data/ProjectAutosave.kt` (audio fields), `tools/QuickTool.kt`, `tools/QuickToolScreen.kt`.
**Size:** ~1500 lines. **After:** B6, B8. *(B9 and B10 both touch `EditorModels.kt` and `ProjectAutosave.kt`; run B10 after B9 if the agents cannot coordinate on those two files.)*

#### B10 — Text and stickers
**Goal:** keyboard-first text with a real style editor and on-canvas handles.
**Tasks:**
1. Add text: item created centred with sample text selected, keyboard up, sheet with Style/Animation/Bubble tabs above the keyboard; IME insets on the sheet (`imePadding`, L1); double-tap on the preview opens the editor.
2. `TextOverlayItem` gains x, rotation, opacity, alignment, letter/line spacing, bold/italic/underline, stroke (colour/width), shadow (colour/opacity/blur/offset/angle), background (colour/opacity/radius); full colour picker with eyedropper; `CaptionRenderer` honours them; autosave round-trips them.
3. On-canvas handles reuse `OverlayHandles` for text and stickers.
4. Animations: In / Out / Loop with duration sliders; per-word reveal for captions.
5. Apply-to-all, caption style presets, duplicate, split, copy style; caption list follows the playhead; selecting a text clip opens its toolbar; `LazyColumn` for lines.
6. Auto-captions: language and source pickers; runs over every base clip and voiceover; word timings from segmenter; SRT import offers replace.
7. Stickers: image/PNG import, search/recents, flip/rotate/opacity; custom fonts import (.ttf) bundled with the draft; saved user styles.
8. Text-to-speech via `TextToSpeech.synthesizeToFile` → audio clip at the caption's start.

**Files:** `editor/TextSheet.kt` (from `CaptionsPanel.kt`), `editor/StickersPanel.kt`, `editor/TextStyle.kt`, `editor/edits/TextEdits.kt`, `editor/EditorModels.kt` (text fields), `media/CaptionRenderer.kt`, `media/SquishTextOverlay.kt`, `media/audio/Transcriber.kt`, `media/audio/SpeechSegmenter.kt`, `media/audio/Tts.kt` (new), `data/ProjectAutosave.kt` (text fields), `editor/Template.kt`, `editor/CaptionLayer.kt`.
**Size:** ~1500 lines. **After:** B6, B8.

---

### Wave 5 (parallel: B11, B12, B13, B14)

#### B11 — Clip operations
Duplicate/Copy (inserts after, keeps everything), Replace (picker with in-point choice, keeps position/length/effects), Freeze frame (3 s still from `frameAt`, split around it), Reverse (background reversed render via `VideoProcessor` reverse pass, then swap uri), Mirror/flip and per-clip Rotate 90 (fields on `Clip`, export effects, preview graphicsLayer), Extract audio button wiring (B9 logic), multi-select (delete/move), copy/paste attributes.
**Files:** `timeline/TimelineModels.kt`, `editor/edits/ClipEdits.kt`, `media/VideoProcessor.kt` (reverse pass, mirror/rotate effects), `media/ReverseRenderer.kt` (new), `editor/ClipToolbar` entries in `editor/ToolBar.kt`, `data/ProjectAutosave.kt`.
**Size:** ~1100 lines. **After:** B7, B8.

#### B12 — Frame and colour
Per-clip looks/adjust with Apply-to-all (fields on `Clip`; `LiveLookEffect` per surface; export per item); Adjust to 12 sliders (exposure, temperature, tint, highlights, shadows, sharpen, vignette, hue, fade, grain, HSL) with reset per slider; brightness as offset; Canvas: ratio list + background (colour / blurred copy / image) via a base canvas layer; Crop tool: draggable window for every ratio, straighten dial ±45°, flip, reset; per-clip crop; auto-reframe analysed per clip (V11 analysis half); crop dim only in the Frame sheet; chroma picker loupe on the preview with averaged sample; mask outline drawn on the preview, heart/star shapes; person-mask files swept (V19).
**Files:** `media/effects/Look.kt`, `media/effects/LookEffect.kt`, `assets/squish_look_es2.glsl`, `editor/LooksSheet.kt` (from `LooksPanel.kt`, the Filters / Adjust / Templates chips since B6), `editor/FrameSheet.kt` (from `EditorPanels.kt`), `editor/CropOverlay.kt`, `editor/CustomCropOverlay.kt`, `editor/CropRect.kt`, `editor/ChromaKeyPanel.kt`, `editor/MaskPanel.kt`, `timeline/Mask.kt`, `editor/BackgroundPanel.kt`, `media/video/Segmenter.kt`, `media/video/Reframer.kt`, `editor/edits/AnalysisEdits.kt`, `media/VideoProcessor.kt` (canvas/per-clip grade section), `media/CompositionFactory.kt` (canvas layer).
**Size:** ~1500 lines. **After:** B5, B6, B8.

#### B13 — Animation, speed and transitions
Animation sheet: In / Out / Combo presets with duration sliders layered over keyframes; opacity keyframes (data, `AlphaScale` per frame in export, preview alpha per tick); keyframe button on every sheet; mask/volume/filter keyframes; Speed: slider 0.1–100×, pitch toggle, draggable curve editor with named curves (Montage, Hero, Bullet, Jump cut, Flash), points draggable, frame-blend option for slow-mo below 24 fps (`FrameBlendEffect`); Transitions: animated thumbnails, categories (Basic, Camera, Glitch, Light), seconds readout, length clamp shown, apply-to-all cuts, transitions on overlays; effects library thumbnails and per-effect parameters; stabilizer keeps raw motion so strength re-solves instantly.
**Files:** `editor/MotionPreset.kt`, `editor/AnimationSheet.kt` (from `MotionPanel.kt`), `editor/SpeedSheet.kt` (from `SpeedPanel.kt`), `editor/TransitionSheet.kt`, `editor/EffectsPanel.kt` (the effects library; `FxPanel.kt` before B6), `timeline/Keyframe.kt`, `timeline/SpeedRamp.kt`, `media/ClipTransformEffect.kt`, `media/effects/FxEffect.kt`, `assets/squish_fx_es2.glsl`, `media/video/Stabilizer.kt`, `media/video/FrameBlendEffect.kt` (new), `editor/PreviewEngine.kt` (opacity keyframes only).
**Size:** ~1400 lines. **After:** B5, B6.

#### B14 — Export UX and policy
Cancel button on the progress card (uses B1's `cancelExport`); stages Preparing/Rendering/Saving with a cover; foreground service with progress notification (`POST_NOTIFICATIONS` used) and `keepScreenOn`; fps row (24/25/30/50/60) via `setFrameRate`/`FrameDropEffect`; quality row (Lower/Recommended/Higher) and HEVC toggle when `EncoderUtil` finds an encoder; resolutions above the device's encoder ceiling greyed; HDR policy (tone-map to SDR by default, "Keep HDR" toggle); fit-to-size also solves resolution and verifies file size afterwards with a re-run offer; export screen plays the file (`VideoPreviewSheet`), shows resolution/duration/fps/size instead of before/after; Back returns to the editor; audio-only toggle; codec-mute warning restated on the sheet; last export settings remembered as defaults; photos exported as image `MediaItem`s at full resolution and arbitrary length.
**Files:** `media/ExportService.kt` (new), `media/VideoProcessor.kt` (encoder settings, fps, HDR, image items), `media/ExportPresets.kt`, `media/SquishError.kt`, `editor/ExportSheet.kt`, `ui/components/ExportProgressCard.kt`, `ui/components/OutputSizePicker.kt`, `export/ExportScreen.kt`, `ui/components/VideoPreviewSheet.kt`, `settings/Preferences.kt` (new, DataStore), `AndroidManifest.xml`.
**Size:** ~1300 lines. **After:** B5.

---

### Wave 6 (parallel: B15, B16)

#### B15 — Shell and project management
Projects keyed by UUID with editable name, cover from the edit, size, last-edited; Home is the project grid with New project (multi-select photos+videos, Browse files, Record), rename/duplicate/delete/multi-select; one project per URI removed; drafts resume by project id; missing media: per-clip readability check on open, placeholder clip with Relink; URI grants released on delete, non-persistable share URIs copied into app storage at import; Settings gets defaults (export, ratio, still duration, transition, haptics, keep-screen-on), storage card lists exports/segments/stills with clear; exports stored once (share via MediaStore uri; private copy deleted after verified publish); backup rules exclude projects/exports; single-task launch with `onNewIntent`; disk I/O off main (`peek`, cache size, history load, S8/E17); library/draft thumbnail LRU + disk cache; tap targets ≥48 dp with content descriptions; tiles wrap at large font; first-run coach marks; quick-tool trim uses the filmstrip; Stitch "Change" → "Add clips", open-in-editor passes the whole list; library open lands on a detail screen; picker auto-open gated.
**Files:** `home/*`, `history/*`, `data/ProjectAutosave.kt`, `data/HistoryRepository.kt`, `data/SquishRepositories.kt`, `settings/*`, `media/MediaAccess.kt`, `media/GallerySaver.kt`, `media/ThumbnailExtractor.kt`, `media/ThumbnailCache.kt` (new), `navigation/*`, `MainActivity.kt`, `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `tools/*`, `ui/components/Surfaces.kt`, `ui/theme/Type.kt`, `export/ExportScreen.kt` (library detail variant).
**Size:** ~1500 lines. **After:** B1, B6.

#### B16 — Editor polish
Everything low-severity left: consistent names ("Overlay" everywhere, "Text" everywhere), empty states (Blend-style triple messages gone, Cut disabled for tiny clips), units on sliders (seconds not ms, degrees, px), ramp/preset chips show the active choice with the right hint, Motion sheet order, effect Start/End buttons removed in favour of lane trims, per-tick allocation churn in `applySurfaceEffects`, proxy progress percentage, "Gap" wording, chroma/cutout hidden on the base with a "Float this clip" action, transition badge glyph, text-glyph buttons replaced by icons, first-frame hints.
**Files:** `editor/*Sheet.kt`, `editor/EditorPanels.kt`, `editor/StatusCards.kt`, `editor/PreviewEngine.kt` (allocation only), `media/ProxyEngine.kt`, `timeline/TimelineEditor.kt` (labels/badges only).
**Size:** ~700 lines. **After:** B7–B14.

**Dependency graph:** B1 → {B2, B3, B4, B5} → B6 → {B7, B8} → {B9, B10} → {B11, B12, B13, B14} → {B15, B16}. B14 may start as soon as B5 lands; B15 may start as soon as B6 lands.

## 4. Device test scripts

All on the moto g84 (`adb -s ZY32J8HF2S`), `adb logcat -G 16M` first, build with JDK 21. "Export check" means: export, open the file from Photos, scrub it, compare against the preview at the same timecode. Use a 30 s 1080p landscape phone clip (A), a 15 s portrait clip (B), a 60 s song, and a photo unless stated.

**B1**
1. Open A, cut at 5 s, press back within 1 s → reopen from Drafts: the cut is there.
2. Open A, edit, Export → export completes; the editor is still on the back stack; Back to editor returns to the same edit; Drafts shows the project with "Exported".
3. Open A (with draft) from Home: banner reads "Your edit of this clip is saved"; tap "Start a new project" → confirm dialog appears; cancel; Continue → edit restored.
4. Kill the app mid-edit (`am kill`), reopen: banner; ignore it and make a cut → banner disappears, wait 2 s, kill again, reopen from Drafts → both the old draft (in trash, visible under "Recently discarded") and the new cut exist.
5. Frame → Custom crop, drag a tight rectangle, kill the app, reopen from Drafts → rectangle intact; Export check: file is cropped.
6. Find the beat, kill, reopen → beat grid still shown.
7. Start an export, press system back → "Stop exporting?"; confirm → returns, no file in `exports/` (`run-as com.squish.app ls files/../exports`), screen stayed on during the run.
8. Open A via Files app "Open with" (document URI) → editor loads the video.
9. Phone in system light mode: status-bar clock is white on navy.
10. Start Stitch with 3 clips, back out, start another Stitch → Drafts lists both.
11. Export an edit, Back to editor, scrub and pinch-zoom for 5 s, back out → Drafts says "Exported", not "Exported · edited since". Reopen it from Drafts, look, back out → still "Exported". Make a cut → "edited since". Same for a Compress session: export, back, reopen from Drafts, back → "Exported".
12. Open A with a saved edit so the inline banner shows; without touching the banner add a caption and type into it (unrecorded edits), press back → Drafts has the caption edit, and the old edit is under Recently discarded. Repeat, but drag a trim handle then Undo at once → the banner comes back and nothing new is in Recently discarded; the "Started a new project" card showed between the two.
13. Dashboard → Stitch, pick 4 clips, reorder, wait 2 s, `am kill` from the background, reopen → the 4-clip merge is back, no picker over it.
14. Open a trimmed Compress draft from Drafts and press back the instant the preview appears → reopen: the trim is intact.
15. Start an export, press back, leave "Stop exporting?" open until the export finishes → the dialog closes by itself and the done screen follows.
16. Discard a draft and press back before the snackbar goes; reopen Unfinished → no Undo snackbar.
17. Edit a draft for 25 minutes (or set the clock forward 11 min between two edits, twice) → Drafts shows "Earlier version · N min ago"; tap it, confirm → the older edit opens; Undo on the snackbar puts the newer one back.

**B2**
1. Open A + B (Add). Zoom in ×6, drag the tail handle of the last clip slowly: the strip does not jump to 0 and the handle follows the finger at sub-pixel speed.
2. Apply "Push in" to A, cut at 15 s: no visible snap at the cut on playback; second half continues the zoom and ends at 1.18×.
3. Cut at 0.05 s into a clip: refused (button dim); cut at 0.3 s: two butted halves, no overlap, no gap on the strip.
4. Cut A at 10 s, delete the first half: the second half moves to 0 with B butted after it (ripple).
5. Drag the head handle of that clip left: it stalls at the trim limit; the tail does not grow into B.
6. Add a Dissolve on B: no black gap appears between the clips; playback shows the blend.
7. Add two overlays at the same time on the same row: the second lands on a new row.

**B3**
1. Add a song, drag Level to 20%, press Undo: level returns to 100%, song stays. Undo again: song removed. Redo twice.
2. Cut, add Dissolve, Undo: only the dissolve goes; label read "Undo: Transition".
3. Drag Motion Scale for 2 s: one undo step; the readout shows degrees on Rotation.
4. Drag a caption left past the start: it stops at 0 with its length intact; scrub to the end, Add title: it ends at the last frame.
5. Select a caption, tap Split: two captions.
6. Stabilize A, open Motion: sliders show scale 100%/offset 0; nudge Across; the picture does not zoom further.
7. Trim 5 s off A's head, Close gaps (until B7 removes it), then Auto-caption: cards land on the spoken words. Run Auto-caption again: no duplicates.
8. Add a song, Cut on the beat: the song stays one clip.
9. Add a manual marker, Snap to the beat: the manual marker survives.
10. Pick a corrupt/unreadable audio file: error card, no invisible clip, trim buttons safe.

**B4**
1. A + B butted, play through the cut at 30 s: no hitch, no flash of a stale frame; check with slow-mo screen recording if unsure.
2. Pause, open/close a panel five times, drag Brightness for 4 s, press Play: the playhead does not jump back.
3. Play to the end: the parked frame is the last frame of the clip, not the first frame after the trim (set out-point on a scene cut to check).
4. Add B at 4K if available (or an original with proxy Failed), scrub fast for 10 s: the picture never goes black and stays black; logcat shows no repeated "reload".
5. Add a green-screen overlay, drag Similarity for 5 s while playing: playback continues, no freeze.
6. Chipmunk voice + song playing: the song is at normal pitch.
7. Press Home while playing: audio stops; reopen: paused at the same frame.
8. Overlay running past the base end: the overlay is visible over black; caption spanning a PiP shows once; caption over a gap shows.
9. Song playing, drag an overlay Size slider: no audio stutter.

**B5** (first: the Media3 gate, steps 1–2, before building on it)
1. A + B, Dissolve 0.5 s on B, Export → completes; file shows a 0.5 s dissolve at 30 s.
2. A with B as a PiP at 40% top-right starting at 2 s, Export → file shows the PiP where the preview did, at the same size, above the base.
3. Frame 9:16, Export at 720p → file is 720×1280 with no pillars; sheet said 720 × 1280. Rotate 90° → summary shows the rotated size and the file matches.
4. 8 s clip + a 30 s photo, song at 20 s: song starts at 20 s in the file. Song at 0.5× speed: plays the whole song, slowed.
5. A 5.1-AAC file at Camera level 50% → exports; music over it → exports.
6. Push in at 0.5× → animation completes at the clip's end in the file; PiP at 2× → PiP plays at 2×.
7. Vivid look → preview and file match on a saturated colour (screenshot both); Super 8 + 9:16 crop → vignette identical.
8. Export a 2 GB-class file: the progress card reads "Saving to gallery…" until the Photos entry exists; deleting the gallery copy first then exporting shows the honest message.
9. Add 3 photos and tap Export immediately: Render is disabled until "Preparing" finishes.
10. Delete a song's file after adding it: Export refuses before starting, naming the sound.
11. The 2026-09-28 failures, re-run on the new build: A + a photo with a Dissolve on the join, 480p → completes (it failed on 1.5.1 with "The preceding MediaItem does not contain any track of type 2", shown as "These clips don't fit together"); the photo first then A; A + photo + a video overlay → completes with the PiP on top. Use a photo added before this build too (its clip has no sound track) as well as a new one (silent AAC).
12. A, a 2 s gap, B, and a clip starting at 3 s on a fresh timeline: black in the gaps, no small square in the middle of the frame; captions over the gap are in the file.
13. Three clips with Slide on both joins, then Wipe on both: the first join has the incoming shot on the lower roll and the second on the upper; both match the preview.
14. An HLG clip (the phone's HDR video setting) cut to a second one, exported; then the same with a Dissolve: the first file is HDR, the second SDR and tone-mapped (see BUILD_NOTES, "what the export leans on"), not failed and not washed out. If tone-mapping fails, the message names HDR conversion, not the transition.
15. During the "Saving to gallery" stage of a large export, press back: the dialog says it is saving and can't be stopped, with only OK; during the render it still offers Stop. Change a caption during the render, then press home during the copy and `am kill`: the change is in the draft.
16. Add a photo: if the log shows `SquishStill could not render` once, the picture-only retry must still add it.

**B6**
1. Open A: header shows name, Undo, Redo, Export; tap the name and rename it; Drafts shows the new name.
2. Open every level-0 tool: the strip stays visible (ruler + a row), the sheet has room, Done/Reset work, system back closes the sheet, then leaves.
3. Tap a clip: level-1 toolbar with Split/Speed/Animation/… (a song: Split/Volume/Speed/…); `<` returns; tap empty timeline: returns and deselects.
4. Fullscreen toggle → same frame, scrub, back.
5. Tap counts: add music 2, split 2, delete 2, speed 3, with a sheet open the same.
6. Rotate the phone/large font: labels readable, nothing clipped.
7. Regression: Export, drafts, undo all still work after the VM split.

**B7**
1. Drag the strip: the playhead stays centred and the frame follows; do it while playing: playback pauses.
2. Tap a clip: it selects, the playhead does not move; tap empty: deselect.
3. Delete the middle of three clips: the third slides left; trim a tail: followers slide; change speed: followers slide.
4. Long-press clip 3, drag before clip 1, release: reordered, all butted.
5. Drag an audio clip near the playhead and a beat: it snaps with a tick and a highlighted line.
6. Add two songs overlapping and a title over a caption: separate rows; rows collapse when a video clip is selected; strip scrolls vertically with five rows.
7. Tap a keyframe diamond: playhead jumps there.

**B8**
1. Overlay tool → pick a photo and a portrait video: both land at the playhead on their own rows, the last one selected with a bounding box.
2. Drag it, pinch it, rotate it, snap to centre (tick), use the corner handle; tap the delete handle. Undo each.
3. Add a portrait reaction clip over A: Export check: PiP identical in size, position and rotation; its audio is audible in preview and file; set its Volume to 0: silent in both.
4. Set A's Volume to 30%, B's to 100%: preview and file match.
5. Two 4K overlays: the proxy status shows per item and scrubbing is smooth once built.
6. Overlay Switch to main: it joins the main track at the playhead at full size and the track ripples.
7. PNG logo with transparency: transparent in preview and file.
8. Pinch a logo to about 20%: one finger still drags it; a tap on its middle does not delete it. Drag a PiP half off a 9:16 crop and tap its buttons over the bars: they work.
9. A logo over a PiP, both at the playhead: Layer → Send back swaps them; a lone overlay offers neither button and the strip never shows an empty overlay lane.
10. Park the playhead at the end of the edit and add a 60 s clip: it ends where the main track ends; the edit does not grow.
11. Four videos as overlays at one moment: three land, the fourth says the video rows are taken; a photo still lands above them.

**B9**
1. Sound → Record: countdown, timeline plays, release: a mic clip at the playhead; export contains it.
2. Fade in 1 s / out 2 s on the song: audible in preview and file; triangles on the clip.
3. Extract audio from A: an audio clip appears under it, A is muted; delete the audio clip and Undo.
4. Beats: Auto → dots on the clip; drag the clip 2 s: dots move with it; tap "Add beat" while listening: a dot at the playhead; cut on beats: video only.
5. Tap a song in the library while the timeline plays: the timeline pauses; switch chips: audition stops; press Home: silence.
6. Song shorter than the video → Loop to fit: repeats; export matches.
7. Voice changer Robot on the voiceover only: song unaffected in preview and file.

**B10**
1. Text → Add text: box centred on the preview with the keyboard up and sample text selected; type; the keyboard never covers the field; Done.
2. Drag/pinch/rotate the text on the preview; export check.
3. Style: stroke red 4 px, shadow, background box 60%, letter spacing, italic, opacity 70%: preview and file match at 9:16 custom off-centre crop.
4. Animation In "Slide" 0.8 s, Out "Fade" 0.5 s, Loop "Pulse": preview and file match.
5. Forty auto-captions → Apply to all a style: all change; Auto-captions over three clips and a voiceover: every cue on the spoken frame.
6. Sticker from a PNG; emoji sticker flip; TTS on a caption produces an audio clip at its start.
7. Kill the app after styling: everything restored.

**B11**
1. Duplicate a trimmed, sped-up, filtered clip: identical copy after it.
2. Replace it with B choosing a later in-point: same length and position; export check.
3. Freeze at 12 s: 3 s still; Reverse: plays backwards in preview and file.
4. Mirror + Rotate 90 on one clip only: others unaffected in the file.
5. Multi-select two clips, delete: ripple.

**B12**
1. Warm look on clip 1, cool on clip 2: file matches; Apply to all.
2. Adjust: exposure, temperature, highlights, vignette, sharpen each with reset.
3. Landscape A on a 9:16 canvas with blurred background: export check.
4. Crop: drag the window off-centre for 1:1, straighten 5°, flip: export check; auto-reframe on a two-clip edit follows the subject in both clips.
5. Chroma loupe on the preview picks the averaged colour; mask outline visible while adjusting.
6. Turn off background removal, delete the draft: `files/segments` is empty.
7. 16:9 shot, Crop 1:1, then Placement 1.6x and 20°; then Frame → Ratio 16:9 over the 1:1 crop: the file matches the preview in both (the square grows past its fitted frame; the square is pillarboxed, not cut to a band).
8. Mask on a PiP at 35% in a corner: it stays in the corner with its outline on it while the feather is adjusted; the outline drags the shape under the finger.
9. 9:16 canvas with Blur, video A then a photo, a 1 s gap between: the file and the preview show A's blur across the gap and a blur of the photo behind it; drag A's trim handle across a few seconds: `files/stills/backdrops` gains a handful of files, not hundreds, and the editor does not stall.
10. Photo overlay: Filters → Warm and Adjust → Vignette: the preview and the file agree; "Apply look to all overlays" from a video PiP reaches the photo.

**B13**
1. In "Zoom" 1 s + Out "Slide" 0.5 s over existing keyframes: both play, keys intact; opacity keyframe fade of a PiP: preview and file match.
2. Speed curve Hero, drag a point, pitch toggle on: audio pitch changes; slow-mo 0.25× with frame blend: smoother than without.
3. Transitions: apply to all cuts; overlay transition; readout in seconds; length clamp shown.
4. Stabilize, change strength: updates instantly without re-analysis.
5. Pitch toggle off (the default) on a 0.5× and a 2× shot with speech: the file's voice is at the preview's pitch, with no click at a Hero ramp's tread boundaries; toggle on: both drop and rise like a tape.
6. Fade in on a main-track shot: black-to-picture in the preview, in a cuts-only file and in a file with a PiP over it, all alike; a keyed opacity on the Opacity sheet: the diamond lights under a key, the slider keys the playhead, Clear settles on the first frame's level.
7. Every transition on the Basic / Camera / Glitch / Light tabs, on a three-shot chain so one join has the new shot on the lower roll: the file matches the preview at 0, 25, 50, 75 and 100 percent of each; the tiles animate; Dip to black still dips.
8. Volume keys on a song under a voiceover (duck to 20% and back): the preview and the file follow the keys; Reset on Level clears them.
9. 100× on a minute of footage: 0.6 s that plays and exports; Blend frames on 0.1×: the file is not a slideshow, frames in order, no encoder stall.
10. Effect tiles animate; Shake's Speed and Punch's Beats per second knobs change the preview and the file the same way.
11. From the review round: duck a shot with two keys (100% → 20% → 100%), then Mute on the Clip sound sheet: silent in the preview and the file, the switch reads off, the keys are still on the strip; Unmute brings the duck back exactly; dragging the level up on a muted shot unmutes it.
12. Slide up and Slide down on a join: the file is the same way up as the preview at 25 / 50 / 75%; Flash and Dip to white as a PiP's arrival whiten the PiP alone (the base shot and a keyed hole stay as they are) in both.
13. Overlay B butted after overlay A on its row: the join mark is on the strip; give B a Slide left; drag B away, or delete or trim A short: the transition is gone from the preview, the file and the strip, and Undo brings it back; the tiles on B's sheet show one shot arriving.
14. Stabilize shot 1 and shot 3 at different strengths; nudge shot 1's Strength: shot 3's crop does not change; the slider on shot 3 reads shot 3's own strength.
15. Opacity and level keys are diamonds on the clip (violet, cyan); a tap on one parks the playhead there and the sheet's diamond lights.
16. 100x on a minute of footage plays in the preview without stalling (at eight times, the sink's ceiling - the clock keeps moving) and exports at 100x; a Hero ramp with the pitch held has no click at a tread boundary (Media3's own processor now); Speed's Reset turns Blend frames and Pitch follows speed off; Blend frames at 0.1x on a 4K export completes.
17. Speed sheet on a narrow phone: the six rate chips read whole in two rows; a swipe that starts on the curve off a point scrolls the sheet; dragging a point straight up does not move it sideways; a tap on the curve adds a point on the line; the Transition sheet's "Plain cuts everywhere" clears every join; Rainbow's Speed at the left end still turns, slowly.

**B14**
1. Cancel from the progress card mid-render: confirm; no file left.
2. Lock the screen during a 3-minute 4K export: notification shows progress; file completes.
3. Export at 30 fps from a 60 fps source; HEVC toggle: file is HEVC and smaller; 4K greyed on a device whose encoder caps at 1080p (S23 for HDR test).
4. HLG source: default export is SDR with correct colours; Keep HDR exports HDR.
5. Fit to 16 MB: file under 16 MB and 720p; export screen plays the result; Back returns to the editor; settings remembered on the next project. Back on the "over the limit" card keeps the file and lands on the done screen; "Back to editor" lands on the bare editor, no sheet.
6. Android 15 (S23): lock the screen during a 4K export - the notification must appear (logcat: no `could not go foreground`) and stay full during Saving. Two HLG clips with a Dissolve: HEVC can be turned off, Keep HDR reads converted, the file is SDR.
7. Drag a main-track photo's tail to 40 s: it goes; play across it straight away and scrub into its second half (the ten-second file is held on its last frame until the longer one lands); export it and the photo runs 40 s sharp. With Keep HDR on an HLG edit that has a photo, the export must complete or fail cleanly; with it off it must complete.

**B15**
1. Home shows the project grid; New project with 3 videos + 2 photos: editor opens with all five butted; rename and duplicate a project; two projects from the same clip coexist.
2. Delete a source video from the gallery, open its project: a hatched "Missing" placeholder clip on the strip, the file named on the Relink card; relink to B (the preview box and Frame take B's shape); Undo brings the placeholder and the card back; a missing sound's Relink opens the file browser on audio; a swept photo still relinks to its JPEG.
3. Share a video from the Files app: the project survives a process kill.
4. Settings: default ratio 9:16 and 4 s stills are honoured; storage card lists exports, clears them; exporting keeps only the gallery copy and Library still opens/shares it.
5. Scroll an 80-item library: no re-decode stutter; TalkBack reads every button; 200% font shows no clipping.
6. Quick trim with the filmstrip lands on a frame; Stitch "Add clips" appends; "Open in editor" carries all clips.
7. A draft saved before this build (slot "p" + hash) shows on the grid with a cover, opens, and saves back into the same file; `am kill` under a project made a moment ago (no save yet) reopens it on its files.
8. Long-press a card: selection mode; Delete two, Undo on the snackbar brings both back; a card's menu offers "Earlier version" once the ten-minute snapshot differs, and a long-press on row four shows the count and Delete above the grid; purge one from the bin, then `dumpsys package com.squish.app | grep -A3 "grantedUriPermissions"` no longer lists its video (unless another project names it).
9. Export from the editor: `run-as com.squish.app ls files/../exports` is empty afterwards, the Library row plays and shares the gallery copy, Delete on its detail screen removes it from Photos; Settings → Storage lists every kind, and Clear on "Photos and freezes" leaves the stills a project uses.
10. Settings → Ticks when snapping off: no tick on a snap in the strip or on the overlay box; Keep screen on: the editor never dims; the first open of the dashboard and of the editor each show one hint, once.
11. Share a video from Files (not Open with): the project opens on a copy under `files/imports/`, and after `am kill` it still plays.

**B16**
1. Walk every sheet: names consistent, no "Captions tab", no "%" on degrees, seconds on transitions, active chip highlighted.
2. Base clip: Chroma/Cutout offer "Float this clip" when a shot follows it; a tiny clip dims Split.
3. Proxy building shows a percentage; the end-of-picture veil reads "End of picture" while a song plays on.
4. Speed → Curves: tap Hero, the Hero chip lights and the line under the chips reads Hero's; drag a point on the curve, the chip goes out and the line says to tap a curve; slider to 2x: no chip is lit and the line reads "One rate, 2x…"; tap Normal, it lights and the shot is at 1x; tap the lit Normal again, nothing changes. Animation → Moves: tap Push in, it lights; trim a frame off the tail, still lit; add a key, it goes out.
5. Cutout on a main-track shot with a shot after it and a Push in on it: no Key green / Key blue / Cut out, a "Float this clip" button under Chroma key; press it: the shot is on an overlay row, selected, full frame where it was with its Push in still on it and the next shot under it, and the sheet now offers the key buttons; Key green shows the next shot through. Under Background, the button shows only with Cut out chosen (an old draft), never under Blur or Colour; Colour gives the person over a colour on the main track. On the last shot, and on the only shot, no button, the line says to put a shot after it. With six overlay rows taken it says the rows are taken and the shot stays. Paste attributes from a keyed overlay onto a shot: Chroma key shows the swatch, Pick and the sliders, and Turn off. The panel is headed "Chroma key".
6. Effects: a placed effect's card has Strength and Remove only, and says to close the sheet and tap the effect to drag its ends. Crop: the Flip buttons carry left-right and up-down icons. Track: a pinned line shows a tick icon, not "✓". Sync: the four nudges are two rows of two, one line each at the largest font.
7. First open on a fresh install (clear the app's data): one gesture hint (B15's editor coach mark) appears under the strip's notices, Got it dismisses it, and it does not come back on the next project; no second card ever shows beside it.

## 5. The first hour with a phone (5 October)

§4 is per batch and exhaustive; nothing above says what to do **first**. By
5 October the unseen backlog ran to sixteen batches and five sweeps, and a
session that reaches a device and works down §4 in order will spend its hour on
B1 and never reach the things most likely to be broken.

This list is ordered by risk times reach: how badly a thing could be wrong,
multiplied by how many edits touch it. Work down it, and delete from it what
holds up. Everything in it is unseen.

`adb -s ZY32J8HF2S`, `adb logcat -G 16M` first, `JAVA_HOME=$HOME/.jdks/jbr-21.0.11
./gradlew clean assembleDebug` (clean, because `TimelinePreview.kt` has changed).

1. ~~**It opens, plays and scrubs the right way.**~~ **Done, 6 October.** A drag
   to the right takes the edit backwards (0:36.9 to 0:25.6) with the film
   following the finger, and the playhead line does not move - which is the
   whole of the fix for the fault the owner found on 5 October. What the same
   session *did* find wrong there: the strip opened half way across the screen
   and crept too slowly to see. Both fixed - see
   `TimelineWindow.PLAYHEAD_FRACTION` and `TimelineLanes.MAX_FIT_SECONDS`, and
   `docs/DEVICE_FINDINGS.md`'s last section.
2. ~~**An export still works, and is a fifth of the size.**~~ **Done, 6 October.**
   A three-second cuts-only export: 183 KB, of which `Mp4Probe` accounts for
   179,388 bytes of sample data - **97.9% of the file**. The 400 KB of
   streamable-moov padding is gone (`media/CompactMuxer.kt`), which for a file
   this size was two thirds of it. 91 video frames at exactly 33.33 ms each,
   30.000 fps even. It plays in the app's own done screen. Not checked: WhatsApp
   and Chrome - sharing to a person is not something to do from a test.
3. **The composited export gate**, §4's B5 steps 1 to 4, in that order: a video
   and a photo with a Dissolve on the join, then the photo first, then a video
   overlay at 40%, then a gap. These are the four that failed on Media3 1.5.1
   and the four the whole layered pipeline rests on. If one fails, the log line
   `SquishExport failed: N sequences` names which assumption went.

   **Steps 1 and 2 done, 6 October, and the first run of them found a real
   failure.** A photo first and a video second with a Dissolve between them died
   before its first frame: `failed: 3 sequences`, a bare
   `NullPointerException` out of `SequenceAssetLoader.onOutputFormat`, reached
   from `ImageAssetLoader`. Media3 will not start a sequence that declares sound
   on an asset with only a picture - it asks its listener for a forced audio
   consumer and `checkNotNull`s the answer, while the listener answers null
   until every *other* sequence has registered its tracks, and an image has no
   file to open so it always wins that race. `StillClips` has known this since
   photos were added (its rendered stills carry a track of silence and its
   comment says why); what reopened it was B14 sending a main-track photo in as
   the picture it was made from, which has no sound track at all.
   `ExportPlan.mustCarrySound` now names the one clip that opens such a
   sequence, and that one goes in as its still. Fixed, re-run, and read frame by
   frame off the file: the dissolve is there, 2.6 s to 3.0 s, the photo fading
   out as the video fades in. **Steps 3 and 4 done too**: a video overlay at 40%
   starting at 2 s, over the dissolve, with an empty stretch at the head of its
   row - it exports, the PiP is top-right from 2.0 s, over the dissolve at
   2.5 s, and gone after 5 s with nothing left behind.
4. ~~**The two new joins** (5 October): put a **Blur** and a **Burn out** on a
   cut and look at the preview, then at the file.~~ **Done, 6 October**, read
   frame by frame off the exported files. **Blur**: the base picture softens
   through the cut and comes back sharp, 2.6 s to 3.0 s - and the preview
   agrees, which is what this one was for, since the preview softens the decoded
   picture and the file softens the finished canvas by different routes. **Burn
   out**: the outgoing shot blows out white at 2.6 s and hangs over the new one
   as a thinning ghost to 3.0 s, with the new shot whole underneath from its
   first frame. In both, an overlay over the join stays sharp and unwhitened.
   Still to do: a Blur on an **overlay's own** transition, where the softness
   goes through the premultiply pass instead, and a Burn out over a padded
   canvas.
5. ~~**Reverse a short window of a long recording.**~~ **Done, 6 October, on an
   eight-hour one.** 2.428 s off the head of a 7:58:30 recording: about fifteen
   seconds, and the render is 525 KB holding exactly 73 frames at 30.000 fps,
   every one 33.33 ms. The window is bounded; nothing is missing from either
   end; it plays backwards; no `.part` file left.
6. **Track and Stabilize on a 60 fps clip in a 30 fps project**, and a Track
   aimed at the clip's last frame, which used to throw and say nothing.
7. **The beat grid after a head trim.** Find the beat on the camera sound, trim
   five seconds off the head shot, and the dots must still be on the music.
8. **The audio suite by ear** (B9): a voiceover recorded and heard back, a 300%
   sound, a fade, a voice effect on one clip and not the one beside it. None of
   it has been heard at all.
9. **A long export on this phone** - twenty-odd cuts over a light leak - for the
   blended-still cache, which used to be the shape of an out-of-memory.
10. **Then §4 from B1**, and `docs/DEVICE_FINDINGS.md`'s own lists.

**The Fast lane, added and done on 7 October.** It was on none of these lists at
all, which is how **Extract audio came to be broken for every file there is** and
stayed that way: the export preflight asks `anyCameraAudio`, which counts the
clips on the timeline, and a quick tool deliberately has none - so an audio-only
render was refused on every file, with a sentence telling the user to turn off a
switch the screen does not have. Nobody had ever tapped the button.

Squeeze, Snip, Extract audio and Stitch have all now been driven end to end and
their files probed; see `docs/DEVICE_FINDINGS.md` §14 and §15. **The lesson for
this list: a whole screen nobody has opened is worse than a feature nobody has
checked, because the second at least fails in a way somebody would see.** What
is still unopened anywhere in the app belongs here.

Left from that round:

a. **Squeeze's "Fit to a size"** - the chip was never tapped, so B14's "Keep
   this one / Try again, tighter" card is still unseen on the one screen whose
   whole job is hitting a size.
b. **"Save as GIF" and "Copy to Files"** on a done screen, both offered on every
   tool and neither pressed.
c. ~~**A quick tool's export gets no Library row.**~~ **Wrong, and the mistake
   is worth keeping.** The Library held at 35 through four tool exports, so it
   looked as though `historyRepository.add` was not reached. It is: the four
   rows were there, and `LibraryScreen` forgets a row whose gallery file is gone
   the moment it opens (`HistoryRepository.forgetDeleted`). The test files had
   been deleted by MediaStore id first, so all four were forgotten before the
   count was read. 35 to 39 and back to 35.

   What that accidentally settles: **`forgetDeleted` does what it says on a real
   gallery deletion** - four rows, four files taken out from under them, four
   rows gone and the rest untouched, which is the path that must never take a
   row whose file still exists.
d. **A tool session resumed from the list** - the row's play opens it, and that
   path (restore a slot, re-probe the files, put the handles back) has only been
   seen by accident, when a stray tap reopened a Stitch and it came back with
   its order intact.

**Added after sweeps five, six and seven** (same evening). These are cheap, and
each one is a thing that was plainly broken for anyone who did it:

11. **"Take out every um and uh"** on a talking head with two captioned lines,
    the first ending "um" and the second beginning "uh": two short cuts, not one
    long one. The old code cut twenty-nine seconds for two words.
12. **Delete a word over a gap** on the main track, and ~~**delete a song that
    runs past the last shot**~~: in the first nothing moves, in the second the
    playhead comes back. **The song half is done, 8 October** - the edit went to
    0:25.257, "End of picture" showed past 22.266, and deleting the song brought
    both the length and the playhead back to 0:22.266.
    `docs/DEVICE_FINDINGS.md` §26. The word-over-a-gap half still wants speech.
13. ~~**A song's beat grid at x2**: the dots double, the bpm on the card
    doubles, and "Every bar" still falls on the bar.~~ **Done, 7 October.** 23
    beats / 120.0 BPM / 6 bars found on a 120 BPM track, x2 gives 46 / 240.0 /
    12 with the phase held, and ÷2 lands back exactly. `docs/DEVICE_FINDINGS.md`
    §21.
14. ~~**Blend a still over a shot and change its Blend mode and Opacity**~~
    **Done, 7 October.** Multiply redrew on the tap and Opacity faded under the
    finger, with the shot never touched. `docs/DEVICE_FINDINGS.md` §18.
15. **Trim a sixty-second clip down to two in Snip**: both handles still answer.
16. **A 9:16 crop on a landscape edit**, with the safe-area guide on: the dashed
    rectangle is inside the picture the file keeps.
17. ~~**A slideshow of photos**: no Camera sound row on the Sound sheet.~~
    **Done, 7 October** - two photos, and "Mic & camera" offers the Record card
    alone. Also held by an executed check now (`ToolRules.hasCameraAudio` in
    `tools/jvm/ToolRulesChecks.kt`), because that day's fix for Extract audio
    touched the same decision and this is the case it must not break.

**Added after sweep seven** (5 October, night). The first two are the only
things on this list that can only be judged *by ear*, so they want quiet:

18. **Enhance on a take that opens with room tone.** A second of silence before
    the first word, Enhance on the clip: that second must be quieter than the
    untreated clip, not hissier. Then cut the take into three-second pieces,
    Enhance each, export - no piece used to be long enough for the noise floor
    to climb back, so every one was 1.4x hissier than the source.
19. **Megaphone at 50% camera level, preview against file.** They must be the
    same voice now; they used to be a loud-hailer on screen and a clean voice in
    the file. Radio and Telephone are the same test at a fifth of the size.
20. **A photo overlay with Brightness up and a vignette on**, beside the video it
    is over: the white part of the photo must be as white as the video's, with no
    grey ring where the falloff begins. Off a screencap, not by eye.
21. **The Curves tool with a point pulled below the one before it** - a highlight
    rolled off. Nothing between two points may be brighter than the higher of
    them, on screen and in the file.
22. ~~**TalkBack on, Settings open.**~~ **Done, 7 October**, by dumping the
    accessibility tree rather than listening - exact and scriptable. The state
    was there (sweep seven's `toggleable`); the **name** was not, on any of the
    nine, and uiautomator flagged both Settings switches `NAF`. Fixed and seen:
    zero NAF nodes, and the switches read "Ticks when snapping" and "Keep the
    screen on while editing" with their state. `docs/DEVICE_FINDINGS.md` §22.

**Added after sweep eight** (5 October, night). The first is the single most
important thing on this whole list: until tonight it could not be done at all.

23. ~~**Auto-caption two minutes of talking and render it.**~~ **The overlay-pass
    half is done, 7 October**, with an imported .srt rather than the recogniser -
    the fault was in the pass, not in speech, and twenty cues by hand crosses the
    fifteen-overlay limit deterministically. Twenty lines imported with their
    milliseconds kept, the render completed (`frames=664`), and twenty frames
    pulled from the file show the right line at every point inside a cue, lines
    16 to 20 among them. `docs/DEVICE_FINDINGS.md` §17 has the table.

    **Still to do here:** the recogniser itself - word timings, and the Words
    arrival landing each word on its own rather than a third of a second early
    (item 25) - and two captions that *overlap*, where the question is which is
    on top. The .srt used had no overlaps.
24. **Set the phone to Arabic, export subtitles, import the file back.** The
    timing lines must be ASCII digits; they used to come out in Eastern
    Arabic-Indic numerals that no tool reads, this app's own parser included.
25. **A thirteen-word auto-caption with the Words arrival on**: each word lands
    on its own word, not a third of a second early.
26. **Choose a run of words in "Edit by transcript", then undo from outside the
    panel until the transcript is shorter.** The choice goes away; it used to
    take the editor down.
27. **Pin an overlay to a track**: its size must not pulse at the frame rate.
    And reverse a stabilized clip, nudge Strength, reverse it back: it must be
    as steady as it started.
28. **"Keep HDR" on an HLG clip with a caption on it**: off, dim, and saying
    why - Media3 refuses a bitmap overlay in an HDR graph below Android 14.
29. ~~**Open "Add media", `am kill` the app behind the picker, then pick two
    clips.**~~ **Done, 8 October** - with "Don't keep activities", since `am kill`
    will not take the app while it hosts the picker's result receiver. The pick
    landed at the playhead with the edit whole, and one Undo gave back exactly
    the edit before it. `docs/DEVICE_FINDINGS.md` §25. They must land at the playhead, or nothing must happen - and in
    either case one tap on Undo afterwards must not blank the edit. The same
    with Add sound, Add overlay and Frame → Background → a picture. (Sweep ten:
    the pick used to run against the default empty state and leave an undo step
    whose "before" was an empty timeline, which the next autosave wrote to disk.)
30. **Drag a main-track photo's tail out to 40 s, drop a marker while the
    longer still is rendering, then undo the marker and redo it.** The photo
    must still play all forty seconds, not hold its tenth-second frame while the
    clock runs on.
31. **Set the phone to Arabic and open a project.** The ruler's ticks must read
    four characters rather than nine; the strip must still run left to right,
    with a drag moving the picture the way the finger goes; and a quick trim's
    two handles must sit where their own times are.
32. **Auto-caption a 30-second talking head trimmed out of a long recording**
    (the out-point several minutes into the file). Lines, not "no speech found" -
    and then Remove silences on the same shot, whose card must name the time it
    actually cuts.
33. **Render a padded-canvas edit of sixty shots with Blur as the background.**
    It must complete with no black stretches, and `files/stills/backdrops` must
    be back to 48 files afterwards.
34. **Fit a ten-minute edit to 16 MB.** The sheet must say the edit is too long
    for that size and what the least it can be is, and the overshoot card must
    offer only "Keep this one". Then fit a one-minute edit, overshoot it, and
    tap "Try again, tighter": the second file must be *smaller*.
35. **Add a panorama as a photo overlay**, and start a project from a portrait
    photo straight off the camera. The overlay must appear rather than be
    silently refused, and the project's card must be upright.
36. **Put a Bullet curve on a shot and look at its filmstrip tiles**, then put a
    curve on a song and look at the wave under its beat dots. Both must agree
    with what the playhead shows.
37. **Turn TalkBack on and set a caption's colour, pick a filter, and tilt the
    Shadows wheel.** Each must say what it is and which one is chosen; the wheel
    must be reachable at all, and its four actions must move the dot.
38. **Open a gallery video with "Open with", change nothing, swipe the app off
    recents.** The dashboard must not gain a card for it.


~~**Clean-up owed from the 5 October session**~~ **Done, 7 October 00:05.**
MediaStore ids 1001326343, 1001326344 and 1001326345 deleted *by id* through the
app's own Library, which names the file in the dialog and says there is no undo;
the scratch projects binned. `accelerometer_rotation` is back at 1.

**And the 6 October session's own scratch, cleared the same way before it
ended:** seven exports made for the gate and the joins, five projects (the
eight-hour ASTERIA split, the photo-and-video one, and three openings of the
same 86-second clip), and one Snip session. Projects and tool sessions go to
Recently deleted for thirty days rather than being destroyed, which is what made
clearing them safe to do from a script.

**The rule, since it keeps being the thing that matters:** by id, never by a
`LIKE` pattern - `_` is a wildcard and `sq_%` once matched every `squish_`
export the owner had.

## 6. Structural work worth doing, in order

Not features. Each of these is a place where the shape of the code is what let
a fault in, and where fixing the shape is worth more than fixing the fault.

**1. Lift the draft codec out of `ProjectAutosave`. Done, 6 October — and in
four cuts, not one.** `data/ProjectAutosave.kt` was two things in one file: the
file handling - atomic writes, the backup, the snapshot rotation, the sidecar,
the bin - which needs a `Context`, and about nine hundred lines of JSON codec
which does not touch one. Both cuts were pure moves, each proved by `diff`
against the captured runs ("MOVED BODIES IDENTICAL") rather than by reading.

The second cut is the one that was not in the plan. `data/DraftCodec.kt` names
the editor's own state types, and the file they are declared in
(`editor/EditorModels.kt`) reaches Compose - `EffectSpan` carries an
`ImageVector` and a `Color` - so lifting the codec out of the `Context` was not
enough to run it. What *is* free of Android, but for `Uri`, is everything a
**clip** holds: the timeline, the colour pipeline and the vision types. So
`data/DraftClipCodec.kt` came out of `DraftCodec` as well, and that is the half
`tools/jvm/DraftRoundTripChecks.kt` now runs: a clip with all 50 of its fields
set and every nested model filled, encoded, written as **text**, parsed and
read back, compared field by field, then a second time to show the document on
disk is a fixed point, then a bare clip to show it comes back bare.

It held honest two ways, which is the part worth copying. The fixture is
checked against a default clip by reflection over `Clip`'s own constructor, so
a field added to the model and not to the fixture fails by name *before* the
round trip can pass it by default. And it goes through JSON text rather than a
live `JSONObject`, which is what a draft actually is.

It found, on its first run, a fault no names check could have: **a non-finite
number anywhere in an edit threw the whole autosave away.** JSON has no NaN,
`put` throws on one, and `encode` is called *outside* the try that guards the
write - so the save failed, and every save after it for as long as the number
was there, with nothing on screen. `data/DraftNumbers.kt` answers it: on an
object the key is left out, so the value reads back as the default the decoder
already declares; in an array a zero is written, because the stabilizer's
measurement is four parallel columns and a dropped element shifts every frame
after it. Which then exposed the other end of the same mistake:
**`optDouble(name)` with no second argument answers NaN**, not zero - so
thirteen fields came back NaN from any draft written before they existed. All
thirteen now carry their own default, and the suite fails on either mistake
appearing again, as text.

`tools/jvm/DraftFieldChecks.kt` still carries the half that cannot be run: the
edit-wide settings and the lines of words. Its own blind spot is now written
down in it - every file goes into one haystack, so `TextOverlayItem.text` made
a `Clip`'s own `text` look both written and read when neither was true.

**The third cut, done the same day: the lines of words.** `TextOverlayItem` was
declared at `EditorModels.kt:98`, beside `EditorUiState`, for no reason but
history - it needs only itself, `editor/TextStyle.kt` and the vision track. It
is now `editor/TextOverlay.kt`, and `encodeText`, `decodeText` and
`TextStyleJson` are `data/DraftTextCodec.kt`. Both moves were pure, proved by
`diff`, and `tools/jvm/DraftTextRoundTripChecks.kt` runs the 36 fields of a
line the way the clip suite runs the 50 of a shot - plus the one thing a line
has that a clip does not: `TextStyleJson` has two callers, a line in a draft
and a saved style in SharedPreferences, and its whole point is that they are
one document, so the suite asserts that a style saved off a line reads back as
the line's. Eight negative tests, each watched failing. It passed first time,
which is worth saying plainly: it found nothing, and now it cannot stop
looking.

**The fourth cut, and the last: the edit itself. Done the same day.** This was
the one that was supposed to cost the most, and the cost was not where it
looked.

`editor/EditorModels.kt` imports nothing from Compose at all. It *reached*
Compose in exactly one place: `EffectSpan` carried an `ImageVector` and a
`Color`. Two presentation values on a model - for a glyph and a tint that
`Glyphs.kt` derives from the effect's kind anyway - and they put `EditorUiState`
and every rule hanging off it out of reach of the harness. `EffectSpan` carries
the kind now and `Glyphs` is consulted at draw time, which is where it belongs.

`ProjectSnapshot` - the *declared contract* of what a draft holds - was
declared inside `ProjectAutosave`, the one file in `data/` that needs a
`Context`, while every function that fills it had already moved out. That is
`data/ProjectSnapshot.kt` now, which is what `DraftFieldChecks` had been
calling odd for two cuts.

What is left is three stands-in, in `tools/jvm/stub/EditorState.kt`
(`MediaCompat`, `SquishError`, `ExportProgress`), and that file's header says
exactly what a suite over it may and may not claim. The thing that keeps them
honest is an assertion rather than a promise: `DraftStateRoundTripChecks` reads
the codec as text and fails if it reads any field of the state that is not a
plain constructor parameter, because a computed property is what might route
through `MediaCompat`. All twenty-nine the codec reads are plain.

`tools/jvm/DraftStateRoundTripChecks.kt` then compares the 30 fields of a
`ProjectSnapshot` against the edit they were written from. It is not a round
trip of one type but of two - `encode` takes the live state and `decode`
answers the contract - so the fields are matched by name through a map, and
**the map is the thing that is checked**: every field of the contract is either
read off the state there or named as derived, so one added to both the model and
the codec and to neither list fails by name. It also pins the four refusals a
draft can meet (no source, no clip *list*, a version below the floor, a version
above it), the one it must not (every shot deleted is a real edit), and the
floor itself as a **literal 9** rather than as the codec's own constant - the
first version read the constant and so could not catch the thing it was for:
raising the floor narrowed the loop with it and the suite passed while every
older draft on the phone had been orphaned. Nine negative tests, each watched
failing.

So all three round trips exist now, and together they cover 116 fields:
`tools/jvm/DraftRoundTripChecks.kt` (50, a clip), `DraftTextRoundTripChecks.kt`
(36, a line) and `DraftStateRoundTripChecks.kt` (30, the edit). What
`DraftFieldChecks` and `DraftKeyChecks` still add over them is the sidecar's own
keys and the cross-file name symmetry; what they cannot say, the three now say.

**2. `EditArea.withTimeline` was two private copies until 6 October**, which is
why two background landings stayed on `record` instead of `recordLate` for a
day. The lesson generalises: a helper duplicated in two edit areas is a helper
that belongs in `EditArea`, and the duplication is what stops the next fix
reaching both. `grep -n "private fun" app/src/main/java/com/squish/app/editor/edits/*.kt`
and look for pairs.

**3. `EncoderCeiling` does not model Media3's HDR narrowing.** Media3 filters
the encoder set by HDR editing support *before* it filters by resolution, so a
"Keep HDR" ceiling is measured against encoders the render may drop. The fix is
one more filter, but it needs a device with an HDR encoder to be worth trusting
- the S23, not the moto.

