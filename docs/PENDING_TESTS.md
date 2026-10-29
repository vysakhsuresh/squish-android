# What is still to be tested - 28 October 2026, night

Everything below is built, and most of it checked on the JVM, but not yet seen
on the phone (or, for sound, not heard by an ear). In the order to do it when
the phone is back. `docs/EDGE_PLAN.md` holds the evidence for each item.

## Seen on the moto the evening of 28 Oct (was this list's top)

- **The done screen:** both buttons in a bar pinned to the bottom, seen without
  scrolling, and Back to Squish lands on the home screen. The tick looked like a
  small dark blob, so it is now bigger, white, and lighter where the brush lands
  (BrushTickChecks fails on the old shape). The share tiles bobbed for ever,
  which kept the screen drawing and uiautomator never saw it idle; they now bob
  three times and settle (ControlChecks refuses an endless animation there).
- **A colour on one word of a bent line:** "good" in green on an arched line,
  in the preview and in a 480p file.
- **Render's preflight off the main thread:** StrictMode showed five small reads
  left during a 52-shot render (the exports folder, its mkdirs, the file's
  length). Moved off, in the editor and the quick tools; 0 violations now.
- **The tracker on the hidden card** (debug/TrackProbeActivity, run on the export):
  within 3% of the card's real size before and after the gap, held still over
  it, found in place at 5.0 s.
- **A pinned line that grows:** its box and handles wrap the grown letters, and a
  tap on the grown edge selects it. A line pinned in an older draft (no
  trackGrows) follows but keeps its size.
- **Bent-line colours, paged and with an emoji and in Arabic:** FOUND - an emoji
  after a word made the last word lose its middle letters ("w d"): the arc was
  measured on the bare text. Fixed (measured on what is laid on it) and seen in
  the preview and a file: "five" green on its page of three, "bold" green after
  the emoji, the Arabic line whole and in order.
- **The Effects tiles at 20 steps a second:** they still move (two screenshots
  0.4 s apart differ by 14-15 levels over the tiles, 0 over the paused picture).
  GPU cost while opening the sheet is not lower (p90 17.6 ms against 18).
- **Shrink-to-fit labels in one step:** the Text sheet's title samples fit at
  once; opening Text is now app p90 9.6 ms (was 27).
- **C6:** undo and redo 0-1 ms each; cold start 318 ms median (A1 was 383); a
  200-shot project opens its editor in 173 ms, first picture at 908 ms.

## Still to do on the phone

1. **Scrubbing a 200-shot edit** (`edge.sh many 200`, zoomed out so about 60
   shots are on screen): the app's own work was a median 26 ms a frame (p90 62).
   Two fixes, both seen: TimelineState's lists kept per clip list (15-18 ms),
   then clips under 8 dp drawn as bars (ThinClips) - 9-11 ms median, p90 24-26.
   Still over the 11.1 ms p90 bar; what is left is spread across the strip and
   the preview (`TRACE_UNDER=<method>` in TraceTop lists one method's callees).
2. **The Export sheet's opening:** 19 ms of GPU a frame for its half-second
   slide-up (a full-height sheet and scrim over the live editor).

## The EDGE_PLAN items still open

| item | what is left to see |
|---|---|
| A1 benchmarks | re-baselined 29 Oct (docs/BENCHMARKS.md); done: open timed warm (157-199 ms, first frame 854-900), quality at 720p SSIM 0.864 |
| B1 formats | HDR10, HLG and 10-bit HEVC done through matrix.sh (29 Oct). matrix.sh now scrubs and cuts each file before its export (seen on a plain file: scrubbed to 0:04, cut, 2 clips, exported); on the HDR files uiautomator never read the screen while their preview copy was made, so their cut is unread. Left: Dolby Vision (no test file), the cut on the HDR files |
| B3 A/V sync | the song sits a steady 67-106 ms behind the picture in the preview - needs an ear to tell a real lag from reporting |
| B4 HDR | Keep HDR on a phone with an HDR encoder (the S23); Dolby Vision |
| C1 export speed | release build re-timed 29 Oct: 60 s at 1080p in 43.7 s (1.37x), the same as A1 - the encoder is the limit; InShot on the same edit not compared |
| C2 smart render | copying stretches of a several-shot edit (not built - Media3 limit) |
| C5 proxies | the Auto path seen on a generated 10-bit HEVC file (29 Oct); left: camera footage, and overlay footage on weak decoders |
| C6 snappy | FOUND: 50 photos took 161 s to add (each a 10 s, 30 fps clip for a 3 s slide) and the media grid stopped at 30 picks without a word. Now: renders the landing length + 2 s (StillRules.firstRenderMs), the grid takes 100 and says when full; then each photo rendered half a second first and lengthened behind the editor one at a time (the encoder takes one still at a time anyway): a new project of 50 photos opens in 11.3 s on the perf build, six added in 2.8 s, an export made while they lengthen waits and comes out whole (150 s, 4500 even frames, every photo in it) |
| C7 smoothness | the whole tour is measured now (bench.sh found Looks under its real name); GPU-bound against 11.1 ms p90 and not met |
| D6 live sliders | 32 of 58 seen live (29 Oct: all 14 of Adjust, Filters' Strength, all 6 of Mask, a line's Size, Width and Bend, a bubble's Opacity and Padding); left: Animation timings (not on a paused picture), stickers, sound sliders, effect knobs |
| E1 captions | Malayalam accuracy past the small Whisper model |
| F1 slow motion | small fast subjects that slow and overlap come out a little narrow (a known limit, 10%) |
| F8 tracking, reframe | auto-reframe on real faces and things (no such footage on the phone) |
| G1, G2 sound | sound effects and noise reduction heard by an ear |

## Housekeeping - done 29 Oct, 04:20

- `edge.sh teardown`: 51 test renders, 63 pushed test files and 20 test
  projects deleted by id and slot; six more test projects from the night
  (four 50-photo projects, two benchmark imports) deleted by slot after
  their titles were checked; the GIF test sticker removed; Settings' storage
  card cleared 363 MB of photo renders no project uses (533 MB -> 170 MB).
  The bench projects and clips from 24 Oct stay, as before.
- Settings' Online is off. The media permissions are as the owner set them
  (READ_MEDIA_VIDEO had been revoked by matrix.sh and was granted back).
- The debug build is installed, with everything above.
