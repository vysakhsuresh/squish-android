# Export/render correctness

The single-sequence "cuts only" export path is sound (composition-relative timestamps, ramp-aware offsets, captions/FX on the right clock), but everything the app calls compositing does not survive Media3 1.5.1: video gaps are refused outright, the default compositor draws the primary roll on top with no per-input alpha, and overlays are sized against the wrong frame. So any edit with a transition, a picture-in-picture, or a timeline gap either fails at start or renders as hard cuts with the PiP hidden. Beyond that: cropping at a non-Original size pillarboxes the file, keyframes drift under speed changes, music cues land early on silent or extended timelines, a finished export destroys the project, back/cancel leave orphan files, and there is no cancel, fps, bitrate, codec, or HDR policy. Read-only audit; Media3 behaviour was traced in the 1.5.1 source jars from the Gradle cache, not observed on a device.

## gap · high · S — No cancel button and no keep-screen-on during export

**Detail:** ExportProgressCard has no cancel affordance and ExportSheet hides Close while isExporting; the only escape is killing the app. Nothing sets FLAG_KEEP_SCREEN_ON, so a long export runs behind a locked screen (and on OEMs with aggressive sleep, gets killed). Fix: hold the export Job in the view model, expose cancel(), add a cancel button with a confirm, and set keepScreenOn on the editor window while exporting.

**CapCut:** Export screen shows a percentage with a cancel (X) and keeps the display awake, warning 'Don't lock your screen or switch apps'.

**Evidence:** grep -i cancel over ExportSheet.kt/ExportProgressCard.kt/QuickToolScreen.kt: none; grep KEEP_SCREEN_ON/keepScreenOn: none; ExportProgressCard.kt:80-85 only says 'Keep Squish open'.

**Files:** app/src/main/java/com/squish/app/ui/components/ExportProgressCard.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/tools/QuickToolScreen.kt

## gap · medium · M — No HDR policy: HDR sources silently switch codec or run the custom shaders on HDR textures

**Detail:** The Composition never sets hdrMode, so Media3 keeps HDR when an HEVC HDR encoder exists - the output becomes HEVC HDR despite setVideoMimeType(H264), and every custom GlEffect (ChromaKey, Mask, Background, Fx, Look) is constructed with useHdr = true and samples linear BT.2020 16-bit textures with SDR maths, so colours and keys drift; captions are SDR bitmaps blended into an HDR frame. Where no HDR encoder exists Media3 tone-maps in OpenGL. Modern Pixel/Samsung cameras record HLG by default, so this is common footage. Fix: default to HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL unless an explicit 'Keep HDR' toggle is on and no SDR-only effect is in use; add a SquishError case. (Traced in Media3 source; not observed on a device.)

**CapCut:** Export sheet has a 'Smart HDR' toggle; off tone-maps to SDR, on keeps HDR when the device can encode it.

**Evidence:** grep setHdrMode/HDR_MODE in app: none; Media3 TransformerUtil.java:268-286, Composition.java:217-230; OverlayEffect.java:30 note on HDR.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/media/SquishError.kt

## gap · medium · M — Fit-to-size keeps the full resolution and never verifies the result against the target

**Detail:** With fitToSize the bitrate is solved from the target but outputP stays Original, so 16 MB for a 60 s 4K clip is ~2 Mbps at 4K (macroblocks) where 720p would look far better at that size. The encoder is VBR by default, so the file can overshoot the target and nothing checks file.length() afterwards. Fix: when fitting, also solve the resolution (bits per pixel floor), consider BITRATE_MODE_CBR, and after export compare size to target and offer a re-run.

**CapCut:** No fit-to-size, but the size estimate is shown live for every resolution/bitrate combination and users pick 720p to shrink.

**Evidence:** VideoProcessor.kt:529 (no Presentation when fitToSize); EditorModels.kt:556-558; Media3 VideoEncoderSettings.java:90 (VBR default).

**Files:** app/src/main/java/com/squish/app/media/ExportPresets.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## gap · medium · M — No frame-rate option; slow motion below 24 fps only warns

**Detail:** CapCut's export sheet has a frame-rate row (24/25/30/50/60). Squish always inherits the source rate, so a 60 fps source exports at 60 fps H.264 at 4K (which some encoders refuse) and there is no way to get 24/30 for a film look or a smaller file. Slow motion that drops below ~24 fps out is stepped; the panel says so but nothing blends frames. Fix: outputFps in state, EditedMediaItem.Builder.setFrameRate / FrameDropEffect; frame blending is a larger separate item.

**CapCut:** Resolution, frame rate and code rate are three rows on the export sheet; 'Smooth slow-mo' is offered on the speed tool.

**Evidence:** No fps field on the export path (EditorModels.kt:282-292); SpeedPanel.kt:103-108 / SlowMotion.kt only warn.

**Files:** app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/data/ProjectAutosave.kt

## gap · medium · S — No bitrate/quality control and no HEVC option

**Detail:** CapCut exposes Code rate (Lower / Recommended / Higher / custom slider) and exports HEVC where supported. Squish derives the bitrate from the source's file size (fine as a default) but offers no way to raise or lower it except fit-to-size, and always encodes H.264 (4K capped at 80 Mbps, files 30-40% larger than HEVC). Fix: a quality chip row mapping to a multiplier on exportVideoBitrate, and a 'Smaller file (HEVC)' toggle that sets VIDEO_H265 when EncoderUtil finds an encoder.

**CapCut:** Code rate row plus HEVC export in settings.

**Evidence:** VideoProcessor.kt:170-189 (H264 only, bitrate only); ExportPresets.kt:70-104.

**Files:** app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/media/ExportPresets.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

## gap · medium · S — Resolutions the device's encoder cannot produce are offered and only fail after starting

**Detail:** Any source can be exported at 4K/2160 (and Custom up to 2160) with no capability check; mid-range phones whose H.264 encoder tops out at 1080p or 4K30 fail at runtime with 'Resolution beyond this encoder'. Media3's DefaultEncoderFactory falls back only within a codec's supported range and otherwise throws. Fix: at sheet open, query MediaCodecList/EncoderUtil.getSupportedResolution for H.264 (and HEVC) and grey out chips above the ceiling with a note.

**CapCut:** Unsupported resolutions/frame rates are disabled on the export sheet for the device.

**Evidence:** OutputSizePicker.kt:75-103 (all presets always enabled); SquishError.kt:64-69, 173-176 (only a pixel-count guard at 8.5 MP).

**Files:** app/src/main/java/com/squish/app/ui/components/OutputSizePicker.kt, app/src/main/java/com/squish/app/media/ExportPresets.kt, app/src/main/java/com/squish/app/media/SquishError.kt

## gap · medium · L — No background export: switching apps or a call can kill a running encode

**Detail:** The encode runs inside the Activity's ViewModel scope with no foreground service, wake lock or notification (POST_NOTIFICATIONS is declared but unused). On Android 12+ a backgrounded app is a cached process the OS may kill during a multi-minute 4K export, and the partial file is left behind (see orphan-file bug). CapCut also blocks backgrounding, but keeps the screen on and warns. Minimum: keep screen on + warning; better: a foreground service with progress notification so the export survives leaving the app.

**CapCut:** Keeps the display on and shows a persistent progress screen; some versions continue in a notification.

**Evidence:** grep startForeground/ForegroundService/NotificationManager/WakeLock: none; AndroidManifest.xml:4 (POST_NOTIFICATIONS unused).

**Files:** app/src/main/AndroidManifest.xml, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · medium · M — Photos are downscaled to 1080 short side and capped at 10 s before they ever reach the export

**Detail:** StillClips renders every photo/blank once as a 30 fps H.264 file with short side <= 1080 and 10 s long; a 4K edit then upscales the photo (soft), and a photo cannot be stretched beyond 10 s. The export could instead use image MediaItems directly (setImageDurationMs, native in Media3, arbitrary length and full resolution) while the preview keeps the proxy still. Fix: in editedClip, when clip.uri is a rendered still, substitute the original image (store it on the Clip) with setImageDurationMs(sourceSpan) and setFrameRate.

**CapCut:** Photos keep their resolution up to the export size and can be any length on the timeline.

**Evidence:** StillClips.kt:46-52 (DEFAULT_MS 3 s, RENDER_MS 10 s, MAX_SHORT_SIDE 1080); EditorViewModel.kt:1210-1221 (still capped to meta.durationMs).

**Files:** app/src/main/java/com/squish/app/media/StillClips.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/timeline/TimelineModels.kt, app/src/main/java/com/squish/app/data/ProjectAutosave.kt

## ux · medium · S — Export controls are duplicated between the Finish tab and the Export sheet, with different fit-to-size chips

**Detail:** The Finish tab (ExportPanel) shows Size + Fit to a size (16/25/50 MB) and the sheet shows the same picker plus Fit to a size (16/25/50/100 MB). Two places to set the same thing, one with a chip the other lacks, and the header's Export button opens the sheet anyway. Fix: make the Finish tab the single home for settings (with the video track list) and have the sheet show a compact summary + Render, or drop the tab's duplicate.

**Evidence:** EditorPanels.kt:256-302 (listOf(16, 25, 50)); ExportSheet.kt:128-177 (listOf(16, 25, 50, 100)).

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt

## ux · medium · S — The export screen cannot play the file it just wrote

**Detail:** ExportScreen shows a tick, sizes, 'Saved to your gallery' and share tiles but no thumbnail or play button; the user must leave to the gallery to check the render (which is exactly when a wrong transition or missing PiP would be noticed). VideoPreviewSheet already exists for the Library. Fix: a cover frame with a play button that opens VideoPreviewSheet on resultPath.

**CapCut:** The export-complete screen shows the video with a play button above the share row.

**Evidence:** ExportScreen.kt:108-243 (no player or cover).

**Files:** app/src/main/java/com/squish/app/export/ExportScreen.kt, app/src/main/java/com/squish/app/ui/components/VideoPreviewSheet.kt

## ux · low · S — Progress card has one stage ('Rendering'); saving to the gallery and the pre-encode work show nothing

**Detail:** The Transformer's percentage is honest, but the copy to MediaStore after 100% (seconds for large files) has no state at all because isExporting is already false, and the indeterminate 'Starting the encoder…' can persist for a plain trim where Media3 reports no progress. Fix: an explicit stage enum (Preparing / Rendering / Saving to gallery) on ExportProgress and a cover thumbnail on the card.

**CapCut:** Progress ring over the project cover, percentage, and a distinct 'Saving' moment.

**Evidence:** ExportProgressCard.kt:52-56; VideoProcessor.kt:54-63 (fraction/elapsed/remaining only).

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/ui/components/ExportProgressCard.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · low · S — 'Before / After / N% smaller' framing on the export screen is a compression-app leftover for an edit

**Detail:** For an edit with added clips, stills, music and 4K upscales, 'Before' is the first source's size and '% smaller' rarely applies; when the file is bigger the card just goes quiet. Fix: show the output's resolution, duration, fps and size; keep the before/after comparison only for the Squeeze tool.

**Evidence:** ExportScreen.kt:83-89, 156-191.

**Files:** app/src/main/java/com/squish/app/export/ExportScreen.kt

## ux · low · S — Export settings are per project and reset to Original every time; Custom size has no encoder guard

**Detail:** CapCut remembers the last resolution/fps/bitrate across projects; Squish starts every new edit at Original (a 4K60 file re-encodes at 4K60), and the Custom short-edge field accepts any even 144..2160 with no check that the encoder takes it. Fix: persist the last picks in SharedPreferences as the default for a new project; clamp Custom to the device ceiling.

**Evidence:** EditorModels.kt:283 (default ORIGINAL); OutputSizePicker.kt:186-235.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/ui/components/OutputSizePicker.kt, app/src/main/java/com/squish/app/settings/SettingsScreen.kt

## ux · low · S — Audio-only export exists in the pipeline but not in the editor

**Detail:** EditorUiState.audioOnly is honoured by VideoProcessor (removeVideo, .m4a via Rip) but the editor has no control for it, so extracting the mixed soundtrack of an edit (voiceover + music + camera) means leaving for the Rip tool, which only takes one file. Fix: an 'Audio only (.m4a)' toggle on the Finish tab; name the file .m4a and publish to Music.

**CapCut:** Export sheet offers video or audio-only output.

**Evidence:** No setAudioOnly in EditorViewModel.kt (grep); VideoProcessor.kt:187, 350, 416 honour audioOnly; EditorViewModel.kt:2109 always writes .mp4.

**Files:** app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/export/ExportScreen.kt

## ux · low · S — Silent downgrade when a clip's sound cannot be decoded is not restated at export time

**Detail:** SquishError.exportable mutes the original if MediaCompat found undecodable audio; the only warning was a failure card shown when the file was opened, which is dismissable. The sheet then says 'Render and save' with no note that the file will have no camera sound. Fix: a line on the sheet ('Camera sound left out: DTS not supported on this phone') when exportable() would mute.

**Evidence:** SquishError.kt:185-190; EditorViewModel.kt:2111 (exportable applied without UI).

**Files:** app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/media/SquishError.kt

## bug · critical · L — Any transition, overlay or timeline gap makes the export fail at start (video gaps unsupported in Media3 1.5.1)

**Detail:** CompositionFactory.buildComposited pads roll B, gapped base clips and late-starting overlays with EditedMediaItemSequence.Builder.addGap. Media3 1.5.1 documents 'Gaps are only supported in sequences of audio' and TransformerInternal.onTrackAdded does checkArgument(trackType != VIDEO || !sequence.hasGaps(), "Gaps in video sequences are not supported."). Roll B always begins with a gap (its first clip starts after 0), so every dissolve/dip/slide/wipe, every overlay not placed at 0, and every gap between clips trips it. The IllegalArgumentException surfaces through the asset loader as ExportException code 1000, which SquishError maps to Unknown: 'Export stopped unexpectedly … Try once more' - wrong advice for a deterministic failure. Fix on 1.5.1: replace gaps with real items (a transparent/black still from StillClips.blank with AlphaScale(0) on roll B, sized to the base frame), or move to a Media3 that supports video gaps; either way SquishError needs a case for it.

**CapCut:** Transitions and overlays always export; CapCut renders its own compositor rather than relying on the platform's.

**Evidence:** Squish: app/src/main/java/com/squish/app/media/CompositionFactory.kt:49-55 (needsCompositing), 79-91 (parity dealing; addGap at 83 and 87), 96-101 (overlay addGap at 98); app/src/main/java/com/squish/app/media/VideoProcessor.kt:127-136, 147-155 (sequences passed unchanged to Composition/Transformer); app/src/main/java/com/squish/app/media/SquishError.kt:136-142 (Unknown text), 210-228 (code 1000 falls to else -> Unknown; CODE_FAILED_RUNTIME_CHECK is 1001 at line 257, bands 2000-7999 at 263-268); gradle/libs.versions.toml:14 (media3 = "1.5.1").

Media3 1.5.1 (media3-transformer-1.5.1-sources.jar): EditedMediaItemSequence.java:90 ("Gaps are only supported in sequences of audio"); TransformerInternal.java:612-614 (checkArgument video sequences must not have gaps) - this path fires when a gap follows a video item in the same sequence (e.g. roll A = [clip1, gap, clip3]); SequenceAssetLoader.java:630-660 (GapSignalingAssetLoader registers only a raw AUDIO track) and 304-319 (checkStateNotNull: "The preceding MediaItem does not contain any track of type 1") - this is the path the two-clip CrossFade scenario and every late-starting overlay actually take, since roll B's first item is the gap; ExoAssetLoaderBaseRenderer.java:99-124 (render() catches only ExportException, so the IllegalArgument/IllegalStateException propagates as an unexpected PlaybackException); ExoPlayerAssetLoader.java:365-371 (onPlayerError maps it to ERROR_CODE_UNSPECIFIED = 1000, ExportException.java:120); CompositionPlayer.java:340 (checkArgument(!composition.hasGaps())) shows the same limitation on the preview-player side.

Scenario: two clips, CrossFade on clip 2 -> rollA=[clip1], rollB=[gap, clip2] -> export fails once clip 2 begins loading, reported as "Export stopped unexpectedly / Try once more". Same for any overlay with timelineStartMs > 0, a first clip starting after 0, or a gap between clips. A fix must replace addGap with real items (a blank still needs forceAudioTrack, which needsForcedAudio at VideoProcessor.kt:301-302 already enables for multi-source edits) or upgrade Media3, and SquishError needs a specific case or a preflight rejection.

**Files:** app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/SquishError.kt, gradle/libs.versions.toml

**Verified:** yes

## bug · critical · L — Transitions never render in the export: no VideoCompositorSettings, primary roll drawn on top at full alpha

**Detail:** transitionAlphaAt exists but nothing in the export reads it (only PreviewEngine mirrors it). The Composition is built without setVideoCompositorSettings, so VideoCompositorSettings.DEFAULT applies: every input gets a default OverlaySettings (alpha 1) and DefaultVideoCompositor draws frames back-to-front with index 0 (the primary = roll A) last, i.e. on top. Even once gaps are worked around, a dissolve A->B becomes a hard cut at the *end* of A's overlap and B->A a hard cut at the *start* of the overlap; dip-to-black, slide and wipe do nothing. Captions are also burned per base clip, so during an overlap both rolls carry the caption and it would be drawn twice. Fix: implement VideoCompositorSettings.getOverlaySettings(inputId, timeUs) (alpha for dissolve/dip, anchor translation for slide, a time-varying MaskEffect for wipe) and move captions/FX to Composition.Builder.setEffects.

**CapCut:** Every transition previews and exports identically; transition duration is editable on the strip.

**Evidence:** app/src/main/java/com/squish/app/media/CompositionFactory.kt:137-148 (transitionAlphaAt; only reference elsewhere is a doc comment at app/src/main/java/com/squish/app/editor/PreviewEngine.kt:753, no export caller); CompositionFactory.kt:93-94 (roll A is sequences[0] = primary input); app/src/main/java/com/squish/app/media/VideoProcessor.kt:147-155 (Composition.Builder with setEffects + experimentalSetForceAudioTrack only, no setVideoCompositorSettings); VideoProcessor.kt:345 and 564-569 (buildVideoEffects incl. caption OverlayEffect appended to every base clip); VideoProcessor.kt:234-252 (compositionEffects contributes Presentation only). Media3 1.5.1 (gradle/libs.versions.toml:14): transformer/Composition.java:79 (default = VideoCompositorSettings.DEFAULT), effect/VideoCompositorSettings.java:25-50 (DEFAULT returns new OverlaySettings.Builder().build(); TODO notes "primary stream on top"), effect/OverlaySettings.java:43 (alphaScale = 1f default), effect/DefaultVideoCompositor.java:308 (primary = framesToComposite.get(primaryInputIndex)) and :483-485 ("Draw textures from back to front", index 0 drawn last).

**Files:** app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/effects/MaskEffect.kt

**Verified:** yes

## bug · critical · L — Picture-in-picture is hidden under the base track and mis-sized whenever the base is resized or cropped

**Detail:** Overlay sequences are secondary inputs; DefaultVideoCompositor draws the primary (base) last, so an opaque base covers the PiP entirely. Independently, overlayEffects presents the overlay at framedWidth x framedHeight while the base clip is presented at outputResolution (720p/1080p) or at the crop aspect; OverlayMatrixProvider scales secondaries pixel-for-pixel against the output (overlaySize/backgroundSize), so a PiP over a 1280x720 export from a 1080p source is drawn 1.5x too large and clipped, and over a 9:16 crop it is off-position. The overlay chain also lacks the global rotation, the colour grade, the FX pass and captions that PreviewEngine.applySurfaceEffects gives every overlay surface, so the preview and the file disagree even where the PiP shows. Fix: present overlays at the same size the base ends up at (cropped, at outputP), apply rotation/grade consistently on both sides, and make the overlay stack the primary (or use a compositor that draws in layer order).

**CapCut:** Overlay track composites above the main track in layer order, scaled relative to the canvas, with the same filters applied as in preview.

**Evidence:** CompositionFactory.kt:67-104 (base as first sequence = primary; addGap at 87 and 98); CompositionFactory.kt:115-130 (overlay Presentation at framedWidth x framedHeight, no rotation/grade/fx); VideoProcessor.kt:133-145 (sequence order, no setVideoCompositorSettings), 325-346 (overlay skips buildVideoEffects), 495-537 (base gets rotation, aspect crop/Crop and Presentation at outputResolution), 232-251 (composition Presentation runs post-compositor and only when multi-source); transitionAlphaAt (CompositionFactory.kt:137) never used at export, so the base is opaque; PreviewEngine.kt:459-520 and 784-813 (rotation, LiveLookEffect, FxEffect, captions applied to overlay surfaces); Media3 1.5.1: DefaultVideoCompositor.java:149-157 (first input = primary), 343-358 (primary first in framesToComposite), 473-486 (alpha blend, draw back to front so primary is on top); VideoCompositorSettings.java:26 ("instead of primary stream on top"), 39-41 (output size = primary's size); OverlayMatrixProvider.java:73-79 (overlaySize/backgroundSize scaling). Additional: TransformerInternal.java:612-614 rejects gaps in video sequences, so layered edits with a second base clip or an overlay starting after 0 ms fail before rendering; the hidden/mis-sized PiP is what a single-base-clip, overlay-at-0 export produces.

**Files:** app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/PreviewEngine.kt

**Verified:** yes

## bug · high · S — Cropping then exporting at any size other than Original (or in a merge) pillarboxes the file

**Detail:** buildVideoEffects applies the crop (Presentation.createForAspectRatio or Crop) and then Presentation.createForWidthAndHeight(outputResolution, LAYOUT_SCALE_TO_FIT), where outputResolution = resolutionFor(outputP, framedWidth, framedHeight) ignores the crop. A 9:16 crop of a 1080p landscape clip exported at 1080p becomes a 608x1080 picture fitted into 1920x1080 with black pillars. compositionEffects does the same for every multi-source edit even at Original. The preview shows the cropped frame only. Fix: derive the output size from effectiveCrop (or previewAspect) and drop the redundant per-clip Presentation when the composition one exists.

**CapCut:** Export size follows the canvas ratio; a 9:16 project at 1080p is 1080x1920.

**Evidence:** app/src/main/java/com/squish/app/media/VideoProcessor.kt:509-527 adds the crop (Crop for Custom, ReframeEffect for auto-reframe, or Presentation.createForAspectRatio(ratio, LAYOUT_SCALE_TO_FIT_WITH_CROP) for a preset aspect); lines 529-540 then add Presentation.createForWidthAndHeight(state.outputResolution.width, .height, LAYOUT_SCALE_TO_FIT) whenever outputP != ORIGINAL && !fitToSize. app/src/main/java/com/squish/app/editor/EditorModels.kt:545-550: outputResolution = ExportPresets.resolutionFor(outputP, framedWidth, framedHeight), where framedWidth/Height (457-458) are only the rotated source size; no crop field is consulted (the property's own comment says "before any crop"). app/src/main/java/com/squish/app/media/ExportPresets.kt:20-27: resolutionFor scales the source rectangle by outputP/shortEdge, so 1920x1080 at 720p -> 1280x720 regardless of crop. LAYOUT_SCALE_TO_FIT fits the incoming (already 9:16, ~608x1080) frame inside the 16:9 box and pads with black, so the file is pillarboxed. Multi-source: VideoProcessor.kt:234-252 compositionEffects adds Presentation.createForWidthAndHeight(outputSize(state), LAYOUT_SCALE_TO_FIT) for any edit with >1 distinct video URI (isMultiSource, 275-276), including at ORIGINAL; outputSize (259-272) is again resolutionFor(outputP, framedWidth, framedHeight), crop-blind, so cropped clips are fitted into an uncropped-shape frame. Corrections to the original claim: (1) the editor preview does not show "the cropped frame only" - EditorScreen.kt:238 and :282 frame the preview to state.sourceFrameAspect (the whole picture) and draw the crop over it, so the mismatch is between the crop overlay the user drew and the exported file, not between a cropped preview and the file; (2) single-source exports with fitToSize enabled skip the per-clip Presentation (line 529 condition) and are not affected; only non-Original sizes without fit-to-size, plus every multi-source export, are. Repro: Frame -> 9:16 on a 1080p landscape clip, Export -> 720p (or Original with two files on the video track). Fix would touch VideoProcessor.kt (derive both Presentation sizes from effectiveCrop/previewAspect, and skip the per-clip Presentation when compositionEffects already supplies one) and EditorModels.kt (outputResolution should account for the crop); ExportPresets.kt only if resolutionFor grows a cropped-shape variant.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt, app/src/main/java/com/squish/app/media/ExportPresets.kt

**Verified:** yes

## bug · critical · M — Finishing an export deletes the project: draft cleared and editor popped, so it cannot be reopened for a fix

**Detail:** On success the view model calls autosave.markCompleted(sourceUri) (= clear) and the nav host navigates to Export with popUpTo(Home), destroying the EditorViewModel. The edit then exists nowhere. A user who spots a typo in a caption on the exported file has to rebuild the whole edit. This contradicts the 'No edit is ever lost' guarantee. Fix: keep the draft (mark it 'exported' instead of deleting), keep the editor entry on the back stack (or offer 'Edit again' on ExportScreen), and let Drafts list exported projects.

**CapCut:** Projects stay in the project list forever; exporting never removes one, and the export screen has a back arrow to the editor.

**Evidence:** EditorViewModel.kt:2116-2142 (success branch: 2120 baseline reset, 2141 markCompleted; note 2139-2140 comment shows this is intentional); ProjectAutosave.kt:217 markCompleted -> 182 clear -> 176-180 delete removes live, backup, scratch and meta files; autosave loop 129-139 will not rewrite because editKey == baseline. SquishNavHost.kt:180-187 navigate to Export with popUpTo(Home) (non-inclusive) pops the Editor entry; EditorScreen.kt:114 `viewModel()` scopes EditorViewModel to that entry. ExportScreen.kt:237-241 and 245-249 both only call onDone -> SquishNavHost.kt:204-209 (Home, inclusive). No recovery path: ProjectAutosave.kt:172 drafts() only lists slots whose live file exists; LibraryScreen.kt:138/170 onOpen passes record.outputPath (the rendered mp4), never a timeline. Guarantee text: ARCHITECTURE.md:109-117, README.md:120-124 (both describe crash recovery specifically).

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/navigation/SquishNavHost.kt, app/src/main/java/com/squish/app/export/ExportScreen.kt, app/src/main/java/com/squish/app/data/ProjectAutosave.kt, app/src/main/java/com/squish/app/home/HomeViewModel.kt

**Verified:** yes

## bug · high · S — System back during an export silently kills it and leaves an orphan partial file; failures leave partials too

**Detail:** There is no BackHandler anywhere, the sheet only disables its scrim, and the export runs in viewModelScope. Back pops the editor, the view model is cleared, the coroutine is cancelled and transformer.cancel() runs - Media3 documents that the output file is not deleted on cancel, and the app never deletes outputFile on failure either. Each aborted export leaves squish_<ms>.mp4 in external files/exports with no record, invisible to Library's delete and to Settings (which only clears proxies). Same in QuickToolViewModel. Fix: BackHandler while exporting (confirm cancel), delete outputFile on failure/cancel in VideoProcessor.export's finally, and sweep unrecorded files in the exports dir on startup.

**CapCut:** Progress screen has an explicit cancel; cancelled exports leave nothing behind.

**Evidence:** No BackHandler/PredictiveBackHandler/onBackPressed in app/src/main/java (grep; only rememberLauncherForActivityResult/setContent from androidx.activity.compose). ExportSheet.kt:69-79 (scrim disabled while exporting, no cancel control); EditorScreen.kt:179 and QuickToolScreen.kt:139 back buttons ungated by isExporting, wired to popBackStack at SquishNavHost.kt:143/179. VideoProcessor.kt:110-114 (finally only cancels the poll), :211 (invokeOnCancellation -> transformer.cancel()). EditorViewModel.kt:2106-2109 (viewModelScope, exports/squish_<ms>.mp4), :2143-2145 (onFailure never deletes); QuickToolViewModel.kt:499-503, :535-540 (same). HistoryRepository.kt:41-46 deletes only recorded outputPath; SettingsScreen.kt:72 clears proxies only; no code lists the exports dir. Media3 1.5.1 (gradle/libs.versions.toml:14) Transformer.java:1297 (cancel: output file not deleted) and :726 (onError: output file not deleted).

**Files:** app/src/main/java/com/squish/app/editor/EditorScreen.kt, app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/tools/QuickToolViewModel.kt

**Verified:** yes

## bug · high · S — Music/voiceover cue starts at the wrong time on a silent source or a timeline longer than the source

**Detail:** buildAudioSequence pads a cue by borrowing the source video's own audio at zero gain. If state.sourceHasAudio is false (screen recording, muted camera, GIF-like clip) padMs is forced to 0 and the cue plays from the start of the file regardless of where it was placed. If headSourceIn + padMs exceeds the source's duration (stills or slow motion stretched the timeline, or the head clip is trimmed late), ClippingMediaSource clamps the end to the file's duration, the pad comes up short and the cue starts early. Media3 supports addGap in audio-only sequences, and needsForcedAudio is already true whenever audioClips is non-empty, so the pad hack can simply become builder.addGap(timelineStartMs * 1000).

**CapCut:** Audio clips sit at their timeline position in the export regardless of the video's own sound.

**Evidence:** app/src/main/java/com/squish/app/media/VideoProcessor.kt:138-139 (headSourceIn = first clip's sourceInMs; timelineDuration = state.trimmedDurationMs, i.e. played time), :154 and :301-302 (experimentalSetForceAudioTrack already true when audioClips is non-empty), :430-431 (padMs = if (state.sourceHasAudio) requestedPad else 0L), :442-458 (pad = ClippingConfiguration over the source video from headSourceIn to headSourceIn + padMs at zero gain). app/src/main/java/com/squish/app/editor/EditorModels.kt:393-395 (trimmedDurationMs = max timelineEndMs of video clips). app/src/main/java/com/squish/app/timeline/TimelineModels.kt:105-107 (Clip.durationMs = speedRamp.outputDurationMs(sourceSpanMs); timelineEndMs = timelineStartMs + durationMs, so a slowed clip's timeline outruns the source file). app/src/main/java/com/squish/app/editor/AudioPanel.kt:237-243 (the UI already warns that on a silent source "the cue will start at the beginning of the video instead", confirming path 1 is the shipped behaviour). Media3 1.5.1 sources (Gradle cache): androidx/media3/exoplayer/source/ClippingMediaSource.java:326-329 clamps the clip end to window.durationUs; androidx/media3/transformer/EditedMediaItemSequence.java:84-103 addGap, "Gaps are only supported in sequences of audio"; androidx/media3/transformer/TransformerUtil.java:88-92 gaps require audio to be encoded, not transmuxed, which is already the case here (setAudioMimeType(AUDIO_AAC) at VideoProcessor.kt:186 and multiple sequences). Scenario: 10 s source at 0.25x gives a 40 s timeline; music placed at 30 s asks for a pad over source [0, 30 s], which is clamped to 10 s, so the music starts at ~10 s in the export. Silent-source scenario: any timelineStartMs > 0 exports with the cue at 0 s.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt

**Verified:** yes

## bug · high · M — Keyframed motion on a speed-changed clip plays on the wrong clock in the export; overlay clips ignore speed entirely

**Detail:** For a base clip, ClipTransformEffect is placed before SpeedChangeEffect, so its presentation times are pre-retime (source-elapsed), but keyframe times are timeline-local (MotionPanel writes playhead - timelineStart; Clip.transformAt evaluates the same way in the preview). At 0.5x a key at 2 s fires at 4 s of output; the stabilizer term, keyed by source time, is right in this position, so moving the effect after the speed change would break that instead - the effect needs the ramp so it can convert (speedRamp.outputOffsetAt / sourceOffsetAt). For overlay clips, overlayEffects contains no SpeedChangeEffect at all while PreviewEngine plays them at clip.speedAt(t), so a slowed PiP exports at 1x and ends early.

**CapCut:** Keyframes are stored in clip output time and stay aligned after a speed change; overlays honour speed.

**Evidence:** VideoProcessor.kt:336-345 (ClipTransformEffect in `leading`, then `+ speedEffects(clip)`); ClipTransformEffect.kt:59-65 (tInClipMs from first presentation time, passed as localMs; stabilizer at sourceInMs + tInClipMs); timeline/Keyframe.kt:54-57 (atMs documented as timeline-local); TimelineModels.kt:114-121 and 147-149 (sourceAt/speedAt/transformAt on played time); keyframes are written in EditorViewModel.kt:1822-1823 (setClipTransform) and 1831 (addKeyframeAtPlayhead) as `playhead - clip.timelineStartMs` — MotionPanel.kt:170 only reads that same convention in isUnderPlayhead, it does not write keys; CompositionFactory.kt:115-130 (overlayEffects has no SpeedChangeEffect; VideoProcessor.kt:325-331 uses it alone for overlays); PreviewEngine.kt:814 (setSpeed(key, player, clip.speedAt(t))). Media3 1.5.1 sources: SpeedChangeShaderProgram extends PassthroughShaderProgram and only alters the timestamp passed to super.queueInputFrame; DefaultVideoFrameProcessor.createGlShaderPrograms flushes buffered GlMatrixTransformations into a DefaultShaderProgram placed before the next non-matrix effect's program.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/CompositionFactory.kt, app/src/main/java/com/squish/app/media/ClipTransformEffect.kt

**Verified:** yes

## bug · medium · S — isExporting is cleared before the gallery copy and history write, re-enabling Export mid-save and allowing a second concurrent export

**Detail:** After processor.export returns, the view model sets isExporting = false and only then runs GallerySaver.publish (a full byte copy of a possibly multi-GB file on IO) and historyRepository.add, then navigates. During that window the sheet flips back to its form ('Render and save' enabled), the header shows 'Export', and a second tap starts another Transformer while the first file is still being copied. QuickToolViewModel has the same ordering. Fix: keep isExporting (with a 'Saving to gallery…' stage) until onResult.

**Evidence:** EditorViewModel.kt:2089-2147 (no re-entrancy guard; 2104 sets isExporting; 2114 clears it before 2121 GallerySaver.publish, 2122 historyRepository.add, 2141 autosave.markCompleted, 2142 onResult). GallerySaver.kt insert(): withContext(Dispatchers.IO) { source.inputStream().copyTo(resolver.openOutputStream(target)) } – full byte copy. VideoProcessor.kt:77-115: export() builds a fresh Transformer per call, no single-flight guard. editor/ExportSheet.kt:107-110 (progress card only while isExporting), 179-184 ("Render and save" enabled = !state.isLoadingSource, calls onRender -> viewModel.export). editor/EditorScreen.kt:138-141, 200-212, 439-443 (sheet only closed on failure/dismiss; header re-enabled when isExporting false). tools/QuickToolViewModel.kt:497-542 (508 clears isExporting before 512/514 publish, 516 history, 533 clearDraft, 534 onResult). tools/QuickToolScreen.kt:138, 219-232 (button returns, enabled = !state.isLoading). Files a fix touches: editor/EditorViewModel.kt, tools/QuickToolViewModel.kt, editor/ExportSheet.kt, editor/EditorScreen.kt, tools/QuickToolScreen.kt, and editor/EditorModels.kt / ui/components/ExportProgressCard.kt if a "Saving to gallery…" stage is added to ExportProgress.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/tools/QuickToolViewModel.kt, app/src/main/java/com/squish/app/ui/components/ExportProgressCard.kt

**Verified:** yes

## bug · medium · S — 'Saved to your gallery' is claimed even when the MediaStore publish failed

**Detail:** GallerySaver.publish/publishAudio return null when insert or the stream copy fails (and delete the pending row), but both callers discard the result and ExportScreen unconditionally renders SavedToCard ('Saved to your gallery · Movies › Squish'). The private copy still exists, so the user is told the video is in Photos when it is not. Fix: carry the published Uri (or failure) into the ExportRecord / route and show 'Only saved inside Squish - tap to retry' when null.

**Evidence:** GallerySaver.kt:58 (insert returns null), :63 (openOutputStream null → null), :69-72 (throwable → delete pending row, return null). EditorViewModel.kt:2121 result of publish discarded, then :2122-2142 record added and onResult called regardless. QuickToolViewModel.kt:511-515 result of publishAudio/publish discarded, then :516-534 record added and onResult called regardless. ExportRecord.kt:3-13 has no published-Uri/outcome field. ExportScreen.kt:73-77 route carries only resultPath; :193 SavedToCard rendered unconditionally; :284 'Saved to your gallery' / 'Saved to Music › Squish'; :289 'Movies › Squish · fileName'. Minor addition: GallerySaver.kt:58 resolver.insert is outside the try, so a thrown (not null-returning) insert would escape the coroutine uncaught rather than return null.

**Files:** app/src/main/java/com/squish/app/media/GallerySaver.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/tools/QuickToolViewModel.kt, app/src/main/java/com/squish/app/export/ExportScreen.kt, app/src/main/java/com/squish/app/data/ExportRecord.kt

**Verified:** yes

## bug · medium · M — Free-space preflight checks the wrong volume and budgets one copy when two are written; private export copies are never reclaimable

**Detail:** preflight measures StatFs(context.filesDir) while the output goes to getExternalFilesDir/exports, and every export is written twice (private file, then a full MediaStore copy) but the check allows estimate x 1.6 for a single copy. The private copies accumulate: only deleting a Library row removes one and Settings' storage card clears proxies only. Fix: StatFs on the exports dir with a 2x+ budget; add 'exports kept inside Squish: N MB - clear' to Settings, or stop keeping the private copy once the gallery copy is verified (share via the MediaStore Uri).

**Evidence:** SquishError.kt:169-171 (StatFs on context.filesDir), 240-242, 272-273 (SPACE_HEADROOM=1.6, MIN_SPACE_BYTES=40MB); EditorViewModel.kt:2099 (preflight call), 2107-2109 (output under getExternalFilesDir/exports), 2121 (GallerySaver.publish return value ignored: a failed second copy is silent); QuickToolViewModel.kt:492, 500 (same pattern for quick tools); GallerySaver.kt insert() (full byte copy into MediaStore VOLUME_EXTERNAL_PRIMARY, returns null on failure); SettingsScreen.kt:56-73 and 354-374 (storage card only reads/clears ProxyEngine cache); ProxyEngine.kt:115-121 (cacheSizeBytes/clearCache are proxy-dir only); HistoryRepository.kt:41-47 (only place a private export file is deleted); LibraryScreen.kt:140, 161, 282 (Library opens/shares the private file path, so it cannot simply be dropped without switching to the MediaStore Uri). Nuance: on most devices filesDir and the external files dir share the /data partition, so the volume mismatch rarely changes the number; the under-budgeted second copy and the silently ignored publish failure are the concrete defects.

**Files:** app/src/main/java/com/squish/app/media/SquishError.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt, app/src/main/java/com/squish/app/settings/SettingsScreen.kt, app/src/main/java/com/squish/app/data/HistoryRepository.kt

**Verified:** yes

## bug · medium · M — Auto-reframe samples the motion track with the head clip's offset for every clip and ignores speed

**Detail:** ReframeEffect is given timeOffsetMs = head.sourceInMs - head.timelineStartMs and adds it to composition presentation time to look up the track (which is in source time). Any second clip, a reordered split, a trimmed-later clip, or a speed ramp breaks the mapping, so the crop window follows the subject from the wrong moment. The preview computes focus per frame from the same single offset (TimelinePreview reframeOffsetMs), so both are wrong together on multi-clip edits. Fix: per-clip ReframeEffect instances with the clip's own sourceIn/ramp (as MaskEffect already does with sourceInMs), or convert via Clip.sourceAt.

**Evidence:** VideoProcessor.kt:514-522 (single head-clip offset handed to every ReframeEffect); VideoProcessor.kt:340-345 (buildVideoEffects appended per clip AFTER speedEffects(clip), so ReframeEffect sees retimed timestamps); ReframeEffect.kt:86-89 (absolute presentationTimeUs/1000 + timeOffsetMs, no per-clip origin) vs MaskEffect.kt:113-117 and BackgroundEffect.kt:125-127 (latched originUs + clip.sourceInMs); EditorViewModel.kt:985-997 (track analyzed only over the head clip's source range); ObjectTracker.kt:38-43 (sampleAt clamps to the track's ends, so out-of-range clips freeze the window rather than centre it); preview: EditorScreen.kt:258-260 and TimelinePreview.kt:235 (same head-only offset applied to the playhead for the whole timeline). Files: app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/effects/ReframeEffect.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt, app/src/main/java/com/squish/app/editor/EditorScreen.kt.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt, app/src/main/java/com/squish/app/media/effects/ReframeEffect.kt, app/src/main/java/com/squish/app/editor/TimelinePreview.kt

**Verified:** yes

## bug · low · S — Export summary reports the unrotated, uncropped frame size

**Detail:** ExportSheet and the Finish panel pass sourceWidth/sourceHeight to OutputSizePicker, whose summary calls resolutionFor(outputP, sourceWidth, sourceHeight). After a 90° rotate the sheet says '1920 × 1080' for a file that is 1080 × 1920, and after any crop it shows the full-frame size; the size estimate likewise uses full-frame pixels so a 9:16 crop is over-budgeted. Fix: pass framedWidth/framedHeight and fold effectiveCrop into a single outputSize on EditorUiState that both the summary and the encoder read.

**Evidence:** Display bug: OutputSizePicker.kt:140-145 computes the summary from the raw sourceWidth/sourceHeight it is handed; ExportSheet.kt:131-132 and EditorPanels.kt:268-269 pass state.sourceWidth/state.sourceHeight (unrotated metadata size, EditorViewModel.kt:175-176). The encoder instead uses state.outputResolution (EditorModels.kt:545-550, built from framedWidth/framedHeight at 457-458) at VideoProcessor.kt:529-540, after the rotation (VideoProcessor.kt:498-503) and crop/presentation effects (VideoProcessor.kt:509-527). Result: after Rotate 90° a 1920×1080 source reads "1920 × 1080" (Original) or "1280 × 720" (720p) while the file is 1080×1920 / 720×1280; after any crop the summary shows the uncropped frame. Size-estimate part corrected: EditorModels.kt:556-563 exportVideoBitrate is shared by the estimate (566-572) and the encoder (VideoProcessor.kt:157,173), so the shown estimate matches the delivered file; the true defect there is that bitrateFor budgets full-frame pixels for a cropped frame, so cropped exports are encoded at a higher bitrate than needed (a quality/size inefficiency, not a wrong estimate). Rotation does not affect the bitrate at all since the pixel count is unchanged. Fix touches ExportSheet.kt, EditorPanels.kt, OutputSizePicker.kt (or better: add a cropped output size to EditorModels.kt and read it from the summary, exportVideoBitrate and VideoProcessor.kt:529-540).

**Files:** app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorPanels.kt, app/src/main/java/com/squish/app/ui/components/OutputSizePicker.kt, app/src/main/java/com/squish/app/editor/EditorModels.kt

**Verified:** yes

## bug · medium · S — Per-track gain only handles mono and stereo; a 5.1 source with camera level below 100% (or used as the silent pad) fails the audio pipeline

**Detail:** AudioMixing.gain registers ChannelMixingMatrix for 1 and 2 channels only. ChannelMixingAudioProcessor throws UnhandledAudioFormatException when configured with an input channel count it has no matrix for, so a film rip or a phone recording in 5.1/6ch with originalVolume < 0.999, or any music cue padded from such a source, fails with 'Something went wrong with the sound'. Fix: register matrices for 1..8 channels (or downmix to stereo first).

**Evidence:** app/src/main/java/com/squish/app/media/AudioMixing.kt:20-29 (matrices registered only for 1..2 channels). Consumers: app/src/main/java/com/squish/app/media/VideoProcessor.kt:397 (gainOnly(state.originalVolume, ...) on the camera track), :455 + :490-493 (silentProcessors() = gain(0f) on a slice of state.sourceUri used as the lead-in pad for every music cue with a non-zero start), :596 (buildAudioProcessors for each audio clip at clip.volume), :601-603 (gainOnly). media3 1.5.1: ChannelMixingAudioProcessor.onConfigure throws UnhandledAudioFormatException when no matrix exists for inputAudioFormat.channelCount; AudioGraphInput.configureProcessing adds editedMediaItem.effects.audioProcessors before its own channel-count changer, so the gain processor sees the decoder's native channel count (6 for 5.1). Error surfaces via ExportException ERROR_CODE_AUDIO_PROCESSING_FAILED -> SquishError.kt:212-224 (BAND_AUDIO) -> AudioProcessingFailed "Something went wrong with the sound" (SquishError.kt:122-127). Files a fix touches: app/src/main/java/com/squish/app/media/AudioMixing.kt only.

**Files:** app/src/main/java/com/squish/app/media/AudioMixing.kt

**Verified:** yes

## bug · medium · S — Preflight never checks that added sound files are still readable

**Detail:** SquishError.preflight verifies every video clip URI and the source, but audioClips URIs are not in the list, so a music file whose grant lapsed (picked via a provider that refuses persistable grants, or after the file was deleted) fails minutes into the encode with a generic error. Fix: include audioClips.mapNotNull { it.uri } in sources for the canRead pass (and a decodability check via MediaCompat).

**Evidence:** app/src/main/java/com/squish/app/media/SquishError.kt:156-157 — `val sources = (state.videoClips.mapNotNull { it.uri } + state.sourceUri).distinct()` followed by `if (sources.any { !canRead(context, it) }) return FileUnreadable()`; state.audioClips URIs are absent, though VideoProcessor.kt:428 (buildAudioSequence, `val audioUri = clip.uri ?: return null`) feeds every one of them into the export composition. Nothing else checks them: EditorViewModel.kt:371-409 (addAudioTrack) never calls checkDecodable; EditorScreen.kt:145-152 swallows a failed takePersistableUriPermission; DraftsScreen.kt:223 checks only draft.sourceUri on resume. When the encode reaches the cue (a cue starting partway in is preceded by a pad cut from sourceUri, VideoProcessor.kt:442-458, so the music file is only opened then), Media3's IO-band ExportException is mapped by fromExport (SquishError.kt:213, 220) to FileUnreadable, whose fix text "Pick the clip again from the gallery" misdirects the user at a video clip rather than the sound — specific but wrong, rather than generic. Fix: include `state.audioClips.mapNotNull { it.uri }` in `sources` at line 156 (and in `exportable` at line 187), and call checkDecodable from addAudioTrack.

**Files:** app/src/main/java/com/squish/app/media/SquishError.kt

**Verified:** yes

## bug · low · S — 'Render and save' is enabled while photos are still being converted, so a just-added photo is left out of the export

**Detail:** Adding photos runs StillClips.fromImage asynchronously and only appends to videoClips when done (preparingStills > 0 meanwhile). The sheet's button checks only isLoadingSource, so exporting during that window renders without the photo, which then appears on the timeline after the file is written. Fix: enabled = !isLoadingSource && preparingStills == 0, with a 'Preparing 2 photos…' line.

**Evidence:** The evidence is right but the fix is incomplete on two points. (a) The window is wider than preparingStills: the counter is decremented at EditorViewModel.kt:1155 immediately after each fromImage returns, i.e. BEFORE addSources (1160) runs ThumbnailExtractor.probe (1194) and the record/append at 1207-1224. So `preparingStills == 0` still leaves a probe-length gap; the decrement should move to after addSources (or the guard should be a separate 'pending adds' flag that is cleared in addSources after the append). (b) The top-bar Export control in EditorScreen.kt:204 (`clickable(enabled = !state.isExporting && !state.isLoadingSource)`) has the same hole and should be gated too, and/or SquishError.preflight (SquishError.kt:151) should return an error while stills are pending so export() itself refuses rather than relying on two UI gates. Also note the existing PreparingIndicator label is 'Preparing N photos or blanks for the timeline…' (StatusCards.kt:184); the sheet can reuse it rather than adding a new string. Files: ExportSheet.kt, EditorScreen.kt, EditorViewModel.kt (1155/1160-1226), optionally SquishError.kt.

**Files:** app/src/main/java/com/squish/app/editor/ExportSheet.kt, app/src/main/java/com/squish/app/editor/EditorViewModel.kt

**Verified:** yes

## bug · low · S — History JSON is rewritten on the main thread after every export

**Detail:** historyRepository.add runs persist(), which does file.writeText on the caller's thread; the caller is viewModelScope (Main). With a long history this is a visible jank right before navigating to the export screen. Fix: persist on Dispatchers.IO.

**Evidence:** HistoryRepository.kt:20-24 (add -> persist) and 70-88 (persist does file.writeText synchronously, no dispatcher). Callers: EditorViewModel.kt:2106 (viewModelScope.launch, Main.immediate) -> 2122 historyRepository.add; QuickToolViewModel.kt:499 -> 516. The preceding suspend calls do not move the caller off Main: VideoProcessor.kt:77-81 (coroutineScope) and GallerySaver.kt:49 (withContext(Dispatchers.IO), returns to Main). Same-thread neighbours in the same block also do blocking work: EditorViewModel.kt:2125 displayNameOf (ContentResolver.query IPC), 2128 file.length(), 2141 autosave.markCompleted -> ProjectAutosave.clear; QuickToolViewModel.kt:533 clearDraft -> autosave.clear. Additionally, the repository constructor runs loadFromDisk() (HistoryRepository.kt:17, 49-68: file.readText + JSON parse) on whichever thread first touches SquishRepositories.history, which is ViewModel init on Main (EditorViewModel.kt:84, QuickToolViewModel.kt:105, HomeViewModel.kt:17) or composition (LibraryScreen.kt:80, ExportScreen.kt:81). delete() (HistoryRepository.kt:41-47) also writes JSON and deletes the export file synchronously from the Library screen. Severity: low (payload is a few hundred bytes per record; not a visible-jank source at realistic sizes), effort S. Fix touches HistoryRepository.kt (make add/delete suspend and wrap persist/loadFromDisk in withContext(Dispatchers.IO), or hand persistence to a dedicated scope) and the two call sites.

**Files:** app/src/main/java/com/squish/app/data/HistoryRepository.kt

**Verified:** yes

## Refuted

- ExportRecord stores the source's framed size, not the written file's size
- Preset widths truncate instead of round (852x480 rather than 854x480)
