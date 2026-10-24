# Edge plan over CapCut and InShot

Written 24 October 2026 from the owner's brief, against main `aff74b8`. A session
that opens this file reads `CLAUDE.md` and `docs/INSHOT_PARITY.md` first (their
rules apply to every item), then **carries on from the first item below not
marked `[x]`**. Resume with: "Carry on docs/EDGE_PLAN.md from the first item not
marked [x]."

Marks, as in INSHOT_PARITY.md: `[ ]` not started · `[~]` built and JVM-checked ·
`[x]` seen on the phone AND in an exported file. Each mark carries the commit and
one line of evidence. P items are also ticked in `docs/INSHOT_PARITY.md`.

## Checklist (in the order to work them)

- [~] **A1** Benchmark harness -> docs/BENCHMARKS.md - baseline on the moto: cold start 383 ms, scrub 40% janky (p90 48 ms), playback ~20% janky, export 1.37x real time, SSIM 0.864; import timings and competitors not measured yet (phone dropped off adb; no InShot on it)
- [~] **B1** Footage matrix: every format lands, plays, scrubs, cuts, exports - RUN on the moto 25 Oct (tools/bench/matrix.sh, 24 files: H.264 in MP4/MKV/MOV/FLV/AVI(XviD)/WebM(VP9)/3GP(H.263), 6-channel, no audio, 1 s, 3 h, rotated 90/180/270, 1080x2400 and 886x1920 screen sizes, WhatsApp, VFR phone, 120 and 240 fps, sync clips): every one opened, played and exported at 480p with no crash; the 240 fps one failed its export (the encoder was configured at 240 fps) - fixed (ExportSettings.encoderFrameRate, checked) and seen exporting at an even 60.00 fps. Not yet: the HEVC 10-bit/HDR10/HLG/DV files through the matrix runner, scrub and cut per file, colour read off each export
- [~] **B2** Conversion never blocks the editor - already so since d6b9299 (18 Oct): nothing converts, FFmpeg reads every door; seen 24 Oct: a 10-bit HEVC MKV opened the editor in 160 ms, first frame 2.1 s. Left: Freeze, Reverse, Stabilize, Track on such a file, on the phone
- [~] **B3** VFR and A/V sync at 1, 10, 60 min - SEEN on the moto 25 Oct (tools/bench/matrix.sh + debug/SyncProbeActivity on the flash-and-beep VFR clips): drift over the file 3, 3 and 1 ms at 1, 10 and 60 min, source and 480p export alike; the export's static offset reads 0 ms once the audio priming its edit list declares is skipped (the probe did not skip it at first and read -33 ms; fixed in the probe). Not measured: the preview's sync (no way yet to read the screen and the speaker together)
- [~] **B4** HDR end to end - HLG: Media3 put HLG's reference white at half brightness in the preview and the file alike (75% bar 134 of 255, measured); HdrLiftEffect (ToneMap.liftMedia3, checked, fails without the white level) now brings it to the thumbnails' tone map - SEEN on the moto 25 Oct, 75% bar 240 in the preview and 241 in a 480p export, 40% bar 99 in both, yellow kept. Lift limited to HLG, the curve it was fitted on. FOUND, open: PQ (HDR10) through the FFmpeg decoder on this phone (it has no HEVC Main 10 decoder) shows red, magenta and blue bars black and the rest blown, with or without the lift - the software path's PQ hand-over to Media3. Not done: Keep HDR on the S23, EncoderCeiling HDR narrowing, Dolby Vision
- [~] **B5** High-frame-rate footage as true slow motion - "Auto" no longer writes 240 fps on a cuts-only export (capped at 60, extra frames dropped evenly; ExportSettingsChecks failed on it, 2 counts); the Speed sheet says "Shot at 240 fps - slow it up to 8x and every frame is a real one" (SlowMotionChecks). SEEN 25 Oct: the 240 fps clip's Export sheet reads "Auto writes 60 fps - the footage is 240…", and its export is 300 frames in 5 s, every one 16.67 ms (after the encoder-rate fix). Not seen: slowing it on the Speed sheet and the hint there
- [x] **B6** Export never dies - free-space preflight was already in; now an export killed with its process is said on the next launch (ExportJournal, checked), a hot phone and a quiet encoder are said on the card and the notification. SEEN on the moto 25 Oct: a 1080p export force-stopped 2 s in - the next launch said "The export of “bench_6” didn't finish…", "Open the project" opened it, no journal and no half-written file left. Not seen: the hot-phone note (needs a hot phone)
- [ ] **C1** Export speed
- [x] **D1** Main clip on the picture: drag, pinch, turn - the selected main shot takes the overlay box (drag, pinch, turn, snap, the Resize and Edit corners; no Delete or Copy-on-top), measured in the canvas frame its placement is in, through withOverlayGeometry so it keys at the playhead when animated; not on a padded canvas yet (its frame differs). SEEN on the moto 25 Oct: the selected shot dragged, then shrunk and turned by its corner, and a 480p export with it in the same place, size and turn as the preview (centre 0.64,0.66 of the frame in both). Still open: a padded canvas
- [x] **D2** Keyframe curves - Ease in, Ease out, Strong, Back, Bounce, Elastic and a two-handle Custom curve per key on placement, opacity, level, look strength and mask; one function for preview and export; EasingChecks (endpoints, monotone, overshoot, bezier inverse < 1e-4, fails with the solver weakened), ValueEasingChecks, draft round trip (fails 4 ways without the curve read back). SEEN on the moto 25 Oct: two placement keys with Bounce on the first, exported at 480p and read frame by frame - the shot lands at 2.5 s (36% of the way, where a bounce first lands), springs back at 2.8 s, settles by 4.4 s; the paused preview at 3.0 s agrees. Not seen: the curves on opacity, level, look and mask, and Custom
- [ ] **D6** Live preview audit: every slider previews live
- [~] **E1** Captions on every phone (Whisper on device) - whisper.cpp (MIT, v1.7.5) as libsquishwhisper.so built by .github/workflows/whisper.yml (not yet fetched into jniLibs), model downloaded once through the online gate (Standard 57 MB / Better 181 MB, MIT weights), word times into wordStartsMs, auto language; WhisperTextChecks; system recognizer stays the fast path. Library built on GitHub and in jniLibs (arm64 1.3 MB). SEEN on the moto 25 Oct through debug/WhisperProbeActivity: Standard model, a 9.9 s spoken WAV, all 26 words right with word times (e.g. "rice," 4280-4810 ms), 5.6 s (1.76x real time). Then through the editor: a downloaded model now takes precedence over the system recognizer (it gives real word times, which E2 follows) and the card is offered on every phone; Text → Auto-captions on an added speech sound gave 4 lines, every word right - and showed every line after the first with its word times offset twice (2380 ms into a line at 2310), fixed (WhisperText.startsWithin, WhisperTextChecks fails without it) and seen fixed. Not seen: the download itself through the card (the model was pushed over adb)
- [~] **E2** Caption styles for reels (+ P4 bilingual) - the word being said in colour, a box behind it, popped, karaoke fill, or one word at a time, a page of 1-6 words; on the speech's own word times, evenly on a typed line; 25 caption presets (17 word-timed); chips on Text → Style; one renderer for preview and export. CaptionWordsChecks (fails 2 ways on a broken copy), draft round trip (fails without the codec). SEEN on the moto 25 Oct: Pop 3-at-a-time, Box and Karaoke in the preview, and Pop read frame by frame off a 480p export ("May it loud" → "and proud now", the word lit stepping every 333 ms). Not built: keyword emphasis, an emoji after a word, all lines in one list, bilingual (P4)
- [~] **F1** Smooth slow-mo (= P1) - motion-compensated in-between frames (FlowInterp: MotionEstimator per block, believed only where the move explains the block and where measuring it backwards agrees, median-smoothed; per pixel the nearby vector the two moved frames agree on; the cross-fade where they do not, or where the field holds under half the picture). FlowInterpChecks: a moving square lands within 1 px and comes out about twice as close to the true frame as a cross-fade; a still scene unchanged; a cut cross-faded; a thin line, still or turning, no worse than a cross-fade anywhere (each negative-tested). On the moto 25 Oct: a 0.25x export writes even 30 fps (597 frames in 20 s); the first version TORE testsrc2's turning line into segments in every in-between frame, which the backward check fixed (those stretches now blend, as Blend frames did); a slow pan exported clean. Not seen: footage with fast steady motion (a hand, a car) - none on the phone - to judge motion against blur by eye
- [ ] **G1** Sound-effects library through an export (= P7)
- [~] **G2** Noise reduction under speech (= P2) - Reduce noise on Voice, a switch and a strength (NoiseReducer: STFT, minimum-statistics floor, steady bins above 180 Hz kept, decision-directed Wiener gain, 20 dB floor; NoiseProcessor in every preview sink and export chain). NoiseReduceChecks: SNR 7.8 -> 18.5 dB, voice within 0.4 dB, a clean tone within 0.7 dB; NoiseProcessorChecks against Media3: exact length, off byte for byte, switched mid-stream with no skip. SEEN in a file on the moto 25 Oct (debug/LevelProbeActivity): speech mixed with white noise and 120 Hz hum, exported plain and at 80% - noise floor -26.3 -> -33.2 dBFS, the loudest second -18.0 -> -19.1, so speech stands 14.1 dB over the noise instead of 8.3. Not heard: by ear, for warble, and nothing listened to in the preview
- [ ] **C3** Timeline at the panel's rate
- [ ] **C7** Flows like water (owner's standing bar - see below)
- [ ] **C4** More layers
- [ ] **D3** Keyframes on effects
- [ ] **D4** Group editing
- [ ] **D5** Before/after hold
- [ ] **E3** Text depth
- [ ] **E4** Text mask
- [ ] **E5** Downloadable on-device voices
- [ ] **F2** Effects 21 -> 60+
- [ ] **F3** Transitions 26 -> 50+ (includes P3)
- [ ] **F4** Stickers: GIF (P6) and a designed vector pack
- [ ] **F5** Gradient and pattern backgrounds (= P5)
- [ ] **F6** Blend modes on video overlays (= P8)
- [ ] **F7** Cut-out quality
- [ ] **F8** Tracking and reframe on MediaPipe detection
- [ ] **F9** Remove flicker, reduce video noise
- [ ] **F10** Filmstrip and Split masks
- [ ] **G3** Voice isolation
- [ ] **G4** Audio finishing
- [ ] **G5** In-app camera with teleprompter
- [ ] **H1** Slot templates
- [ ] **H2** AutoCut
- [ ] **C2** Smart render
- [ ] **C5** Proxies where they help
- [ ] **C6** Snappy everywhere
- [ ] **I1** README and Settings tell the truth (= P9)
- [ ] **I2** Done screen and store text state only what A1 measured
- [ ] **Z** Finish: INSHOT_PARITY R sweep, rerun A1, findings written up

### C7 Flows like water (added from the owner's brief, 24 Oct)

"Squish should also be the smoothest video editor ever seen, which even works as
transparent and smooth flowing as water." Made measurable, so it can be ticked:
every gesture, sheet, scroll and screen change on the release build holds the
panel's rate (90 Hz on the moto: p90 frame time under 11.1 ms in
`dumpsys gfxinfo framestats` over a scripted tour of every screen); no input
waits on work (a tap shows its result within one frame, long work shows progress
and never blocks); motion follows the finger and settles with spring physics,
not fixed-duration tweens; nothing pops - sheets, chips, rows and the strip
animate their size and position; no jank over 2 frames anywhere in the tour.
C3, C4, C5 and C6 feed it; C7 is the audit across the whole app that says
whether it holds, with the before/after numbers in docs/BENCHMARKS.md.

---

## Owner decisions already made (do not stop to ask about these)

- YES: on-device models (speech recognition, voices, noise/voice separation,
  segmentation, detection) that the person downloads once with one tap through
  online/Online.kt - fetch only, nothing about the person or their video is sent. Show
  the size first. Bundle a model only if under 10 MB. Check each model's own licence
  (MIT/Apache/BSD/CC0/CC-BY only).
- YES: Apache-2.0/MIT/BSD libraries under 10 MB each (per ABI). Ask me before anything
  GPL (beyond the nextlib already in), bigger, or paid. Candidate runtime for E1, E5,
  G2, G3: sherpa-onnx (Apache-2.0) - one runtime for Whisper speech recognition, voices,
  speech enhancement and source separation; if its native size breaks the 10 MB rule,
  propose ABI splits or a download, and ask.
- NEVER: upload video/audio/photos, accounts, ads, watermark, paywall, GIPHY, server AI
  (generation, object removal, upscaling, voice cloning), photo editor/collage,
  AR/body effects.

## THE PLAN - where CapCut and InShot are ahead, checked against main aff74b8 (24 Oct 2026)

ORDER: A1, B1-B6, C1, D1, D2, D6, E1, E2, F1, G1, G2, C3, C4, D3, D4, D5, E3, E4, E5,
F2-F10, G3, G4, G5, H1, H2, C2, C5, C6, I1, I2, Z.
Reliability first: the first editor to cut a real event on Squish went back to InShot
over missing and failing files (DEVICE_FINDINGS section 58).

A. MEASURE FIRST
A1 Benchmark harness -> docs/BENCHMARKS.md. One fixed test edit: 60 s, 6 phone clips
   1080p30, one 4K clip, a song, a title, a look, 2 transitions, one PiP. On the moto
   (and the S23 if attached), release build (assemblePerf), record:
   - import: pick to playable for 1080p, 4K60, HEVC 10-bit, iPhone Dolby Vision
   - cold start, project open, first frame
   - scrub: gfxinfo framestats median/p90/janky over the same scripted adb swipes
   - playback with 0, 1 and 3 overlays: dropped frames (log ExoPlayer's count)
   - export 1080p30 and 4K30: seconds, x real time, size, Mp4Probe
   - quality: SSIM of 10 export frames against the source (write
     tools/jvm/FrameCompare.kt over frames.ps1 output)
   - heat: dumpsys thermalservice before and after a 4K export
   Same edit and swipes in InShot (and CapCut if installed): never sign in, never open
   their existing projects, delete test exports by MediaStore id. If something cannot be
   measured, write "not measured", never a guess. Every C item quotes before/after.

B. FOOTAGE NEVER FAILS
B1 Footage matrix (extend tools/native/make_test_media.sh plus phone-shot files):
   H.264, HEVC 8/10-bit, HLG, HDR10, Dolby Vision 8.4, VFR phone clips, 120/240 fps
   slow-mo, 4K60, rotated 90/180/270, odd screen-recording sizes (1080x2400, 886x1920),
   WhatsApp-compressed, AVI/FLV/MKV/WebM/MOV/3GP, 6-channel audio, no audio, 3 h, 1 s.
   Each must land, play, scrub, cut and export with A/V in sync and colour right (colour
   tags read by Mp4Probe; a chart clip compared with crop.ps1). Fix every failure. A
   format the phone truly cannot handle says so in one sentence on its tile; nothing
   is ever dropped silently.
B2 Conversion never blocks. Today MediaConversion holds a new project on the loading
   screen (~0.8 min per min of 1080p) and drops the last ~5 frames. Open the editor at
   once on the FFmpeg preview; convert in the background (survives leaving the editor,
   with its notice); only filmstrip/waveform/Freeze/Reverse/Stabilize/Track wait, each
   saying so; drain the decoder so no frames are lost; convert at the size the project
   needs.
B3 VFR and sync: A/V drift at 1, 10 and 60 min on VFR sources, in the preview and the
   export. Zero audible drift; the CFR export holds.
B4 HDR end to end: SDR preview of HLG/HDR10/DV tone-mapped right (not grey, not blown);
   "Keep HDR" export on the S23; ROADMAP section 6 item 3 (EncoderCeiling HDR
   narrowing).
B5 High-frame-rate footage: 120/240 fps clips play at real speed; slowing them is true
   slow motion with no blending (SlowMotion.kt knows the fps); hint "shot at 240 fps -
   slow it up to 8x with no blending".
B6 Export never dies: free-space check against the estimate before starting; thermal
   status listener (PowerManager) - on SEVERE, ease off and tell the person; survives
   background and screen lock; a killed process is reported clearly on the next open (or
   resumes); never "stuck at 99%".

C. FASTER THAN BOTH
C1 Export speed (today 51 s of 1080x1920 took 37 s, about 1.4x real time). Profile with
   perfetto. Try encoder performance parameters (operating rate, priority), skipping
   identity effect passes, decoding at the needed size, fewer texture copies. Target: at
   least InShot's time on the A1 edit with equal or better SSIM per MB.
C2 Smart render: main-track stretches with nothing that changes pixels (same size, codec
   and fps as the output) are copied, not re-encoded; only the GOPs around cuts are
   re-encoded. Mp4Probe proves continuity. If Media3 cannot join the two cleanly, write
   it up and stop at "trim-only exports are copied".
C3 Timeline at the panel's rate: CLAUDE.md says app work ~4 ms, GPU 11-14 ms. Do what it
   names: move the window by layer translation only, cache waveform and filmstrip
   bitmaps, fewer layers. Target: scrub p90 under the frame budget on the release build.
C4 More layers. Today MAX_FOOTAGE_LAYER = 3, MAX_LAYER = 6 (decoder budget). Read the
   real limit (CodecCapabilities.getMaxSupportedInstances); give overlays their 540p
   proxies in the preview; one decoder for repeated uses of one source; when live
   decoding is over budget, pre-render that stretch in the background into a cached
   preview file, as desktop editors do. Target: 6 video overlays playing smoothly on the
   moto; export unchanged.
C5 Proxies where they help: today only above 1920 px. Also 1080p60, HEVC/10-bit,
   high-bitrate and overlay footage on weak decoders. Preview quality Auto/Smooth/Full.
C6 Snappy everywhere: cold start, a 200-clip project, adding 50 photos, filmstrip fill,
   undo of a big edit. Measure, then fix the worst three. No ANR, no frame skip over
   700 ms in logcat.

D. EDITING FEEL
D1 Main clip on the picture: today only overlays and words take drag/pinch/turn
   (OverlayHandles over onPicture). The main-track shot must too: select, drag, pinch,
   turn, snap to centre and edges with a tick, keyed at the playhead when animated, via
   the same TimelineState write path.
D2 Keyframe curves: today KeyframeEasing is Smooth/Linear/Hold. Add Ease in, Ease out,
   Strong in-out, Back, Bounce, Elastic, and a Custom cubic-bezier graph with two
   handles, per key, for placement, opacity, volume, look strength and mask. One function
   for preview and export. JVM: exact endpoints, monotone presets monotone, bezier
   inverse to 1e-4.
D3 Keyframes on effects (InShot shipped this 22 Oct): key a placed effect's Strength and
   its knob (TimedEffect intensity/amount), with diamonds on its row.
D4 Group editing (InShot, Sept 2026): clips selected across rows move, shift, delete,
   duplicate and copy together, keeping relative timing; Group/Ungroup keeps them
   together.
D5 Before/after: press and hold the picture to see the shot without looks, adjust and
   effects, on every colour sheet.
D6 Live preview audit: open every sheet and drag every slider. Each must change the
   picture or sound live at >= 24 fps on the moto. List any that do not, and fix them all
   (the owner's rule: every option previews as it is applied).

E. WORDS
E1 Captions on every phone: today Transcriber uses only Android's on-device recognizer
   (Android 13+, and only where a model is installed). Add Whisper (whisper.cpp or
   sherpa-onnx) with a quantised multilingual model downloaded once (show the size); word
   times into wordStartsMs; auto language; background job with progress; the system
   recognizer stays the fast path where present. Accept: 2-minute English, Hindi and
   Malayalam talking clips captioned on the moto; word timing within 150 ms checked on
   frame steps; report accuracy honestly per language.
E2 Caption styles for reels: today 8 static presets and a word-by-word reveal. Add styles
   driven by wordStartsMs: active-word highlight (colour or box behind the spoken word),
   pop per word, karaoke fill, one word at a time, keyword emphasis (tap a word: colour +
   size), an emoji after a word (picked by hand). 20+ presets, max words per line, edit
   all lines in one list. P4 bilingual captions lives here.
E3 Text depth: curved text (arc), gradient fill (2-3 stops, angle), glow colour and
   strength (today a Boolean), second outline, per-word colour within a line, background
   padding; 30+ animated title templates (today 17). Still one renderer for preview and
   export.
E4 Text mask: video seen through big letters (mask drawn by the text renderer).
E5 Voices: today 5 system voices. Downloadable on-device neural voices (open-licence
   voices only) in the languages E1 covers, auditioned before use.

F. PICTURE
F1 Smooth slow-mo = INSHOT_PARITY P1.
F2 Effects 21 -> 60+ in categories: Light (leaks, film burn, bokeh, flare, glow pulse),
   Particles (snow, rain, sparkles, hearts, confetti, dust), Lens (zoom blur, fisheye,
   kaleidoscope, mirror, split 2/3/4, ripple), Retro (VHS variants, camcorder date, super
   8, 8-bit), Glitch variants, Motion (3D shake, spin). All GLSL, previewed on the
   person's frame, Strength + one knob, keyable (D3).
F3 Transitions 26 -> 50+: P3's geometric pack plus cube, flip, page curl, spin, zoom
   blur, ripple, luma/gradient wipes, glitch and mirror variants.
F4 Stickers: today emoji only. P6 (GIF) plus a designed pack drawn in code - callouts,
   arrows, subscribe/like/bell, frames, highlights, price tags - with in/out/loop
   animation. Vector, so no licence question.
F5 Gradient and pattern backgrounds = P5.
F6 Blend modes on video overlays = P8 (last in F; costly).
F7 Cut-out quality: today MediaPipe selfie_segmenter, person only. Add temporal
   smoothing (no flicker), edge refinement (guided filter) for hair, and "tap to cut out
   anything" with MediaPipe InteractiveSegmenter. Accept: a contact sheet over 2 s of a
   walking person with no mask flicker.
F8 Tracking and reframe: Reframer uses the old android.media.FaceDetector. Move to
   MediaPipe face + object detection so reframe follows people, pets and cars; the
   tracker handles scale and re-finds a lost target.
F9 Clean-up done locally: Remove flicker (luma smoothed over time, for lights and
   timelapses) and Reduce video noise (temporal, through MotionEstimator), each with
   strength, previewed.
F10 Masks: add Filmstrip (bars) and Split to the mask set.

G. SOUND
G1 = P7 (in progress): add five effects from different categories to an edit, export,
   listen.
G2 = P2 noise reduction. If sherpa-onnx lands for E1, also try its speech-enhancement
   model on the same recording; ship the better, measured.
G3 Voice isolation: "Voice only" and "Remove voice", each with a level. Use a model
   (Online gate) if an open one under ~30 MB works; otherwise a spectral mask, labelled
   honestly.
G4 Audio finishing: EQ presets (Voice, Podcast, Bass boost, Phone, Warm) + 5-band;
   compressor/limiter; loudness to -14 LUFS with a true-peak limit (extend Loudness);
   speed with pitch hold on sound rows; tap-to-mark beats.
G5 (lower priority) In-app camera (CameraX): teleprompter, count-in, front/back, 9:16
   guides; teleprompter for voiceover too. CapCut has both.

H. EDITS IN ONE TAP
H1 Slot templates (CapCut's biggest draw): a finished edit with N slots timed to a
   song's beats, with its looks, effects, transitions and titles. The person picks N
   clips, slots fill using "Use the liveliest bit", and the edit opens fully editable.
   20 templates from existing pieces and Squish originals. Extend TemplateChecks to
   slots.
H2 AutoCut: N clips + a song -> a finished reel (Fit shots to the beat + liveliest bit +
   Sound on every cut + a look), 3 styles to choose from.

I. SAY IT
I1 = P9 (README and Settings tell the truth).
I2 The done screen and store text state only what A1 measured (no watermark, no account,
   file facts, speed numbers).

Z. FINISH
INSHOT_PARITY R sweep. Rerun A1 and put the before/after table at the top of
docs/BENCHMARKS.md. Findings to DEVICE_FINDINGS.md; move proven items out of CLAUDE.md's
unverified list.

RULES FOR EVERY ITEM
- JVM check first, watched failing on a copy, then code. Build (JDK 21), install, drive
  on the phone with screenshots, export, Mp4Probe/frames.ps1; NAF 0 for new controls;
  logcat clean of FATAL/ANR. Performance items: before/after from the A1 harness on the
  release build.
- After touching PreviewEngine, VideoProcessor, ExportPlan, TimelineEditor or any shader,
  run the full run_desktop.sh before pushing. Never break a working feature for a new
  one.
- Uncluttered: new options go into existing sheets as chips or rows; no new top-level
  tab without asking me.
- Three honest failed approaches on an item: ship what works behind an honest label (or
  nothing), write why in DEVICE_FINDINGS.md, mark [~] with the reason, move on.
- Out of time or context: commit what works, mark the item, push. Resume with:
  "Carry on docs/EDGE_PLAN.md from the first item not marked [x]."

ONE MORE THING (owner, 24 Oct): Squish should also be the smoothest video editor ever
seen, which even works as transparent and smooth flowing as water. (Item C7 above.)

