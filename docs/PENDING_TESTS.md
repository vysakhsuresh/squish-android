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
| A1 benchmarks | re-baseline after tonight's changes (bench.sh all on the perf build) |
| B1 formats | the HEVC 10-bit, HDR10, HLG and Dolby Vision files through tools/bench/matrix.sh; scrub and cut per file; colour read off each export |
| B3 A/V sync | the song sits a steady 67-106 ms behind the picture in the preview - needs an ear to tell a real lag from reporting |
| B4 HDR | Keep HDR on a phone with an HDR encoder (the S23); Dolby Vision |
| C1 export speed | a release-build timing; InShot on the same edit for comparison |
| C2 smart render | copying stretches of a several-shot edit (not built - Media3 limit) |
| C5 proxies | a real 1080p60, 10-bit or HEVC file taking the Auto proxy path |
| C6 snappy | 50 photos added (`edge.sh photos 50`); the 200-shot scrub (above) |
| C7 smoothness | the whole tour is measured now (bench.sh found Looks under its real name); GPU-bound against 11.1 ms p90 and not met |
| D6 live sliders | Filters (under Vivid) and Mask (once added) now driven: Strength, Across, Up/down all live; the rest of Mask's sliders sit below where the step scrolls |
| E1 captions | Malayalam accuracy past the small Whisper model |
| F1 slow motion | small fast subjects that slow and overlap come out a little narrow (a known limit, 10%) |
| F8 tracking, reframe | auto-reframe on real faces and things (no such footage on the phone) |
| G1, G2 sound | sound effects and noise reduction heard by an ear |

## Housekeeping owed to the phone

- `sh tools/bench/edge.sh <serial> x teardown` - deletes every test export and
  project made on 27-28 Oct by MediaStore id and slot (tonight's renders are
  in its EXPORT_IDS; the 200-shot project in its SLOTS).
- Remove `files/stickers/edge_ball.gif` (the GIF test sticker, by run-as).
- Settings' Online left off (it is off).
- The perf build may be the one installed; put the debug build back before
  anything that needs run-as.
