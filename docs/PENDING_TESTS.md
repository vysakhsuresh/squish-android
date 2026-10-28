# What is still to be tested - 28 October 2026

Everything below is built, and most of it checked on the JVM, but not yet seen
on the phone (or, for sound, not heard by an ear). In the order to do it when
the phone is back. `docs/EDGE_PLAN.md` holds the evidence for each item.

## First - built on 28 Oct, installed on the phone, not yet looked at

1. **The done screen** (after any export): the two buttons, Back to Squish and
   Back to editor, side by side in a bar pinned to the bottom, visible without
   scrolling; the tick painted on with a brush a moment after the circle pops;
   the share tiles bouncing in one after another, each tilted, bobbing a little,
   squashing under the finger, a coloured glow under each.
2. **A colour on one word of a bent line** (E3): Text, Style, Bend to an arch,
   pick a word under "A word", pick a colour - the word in that colour in the
   preview and in a file. (Before the fix the colour was dropped on a bend.)
3. **Render's preflight off the main thread**: tap Render on a long edit (Edge
   transitions, 52 shots) - no freeze; `adb logcat -s StrictMode` shows no
   SquishError.canRead (seen gone once on 28 Oct; check again with this build).
4. **The tracker on the hidden card** (F8): `edge.sh moving hide` - over the
   gap the card is lost and found again in place (seen); after it the size
   should follow again - only known to fail when the tap is on the bars alone.

## The EDGE_PLAN items still open

| item | what is left to see |
|---|---|
| A1 benchmarks | re-baseline after tonight's changes (bench.sh all on the perf build) |
| B1 formats | the HEVC 10-bit, HDR10, HLG and Dolby Vision files through tools/bench/matrix.sh; scrub and cut per file; colour read off each export |
| B3 A/V sync | the song sits a steady 67-106 ms behind the picture in the preview - needs an ear to tell a real lag from reporting |
| B4 HDR | Keep HDR on a phone with an HDR encoder (the S23); Dolby Vision |
| B5 high frame rate | slowing the 240 fps clip on the Speed sheet, and its hint |
| C1 export speed | a release-build timing; InShot on the same edit for comparison |
| C2 smart render | copying stretches of a several-shot edit (not built - Media3 limit) |
| C5 proxies | a real 1080p60, 10-bit or HEVC file taking the Auto proxy path |
| C6 snappy | undo/redo/add-media timings read off SquishPerf on the phone |
| C7 smoothness | the release-build tour against 11.1 ms p90 is GPU-bound and not met; Look and Export steps (now reachable, bench.sh scrolls the toolbar) |
| D6 live sliders | the 55 sliders not driven one by one; Filters and Mask need a look or shape picked first |
| E1 captions | Malayalam accuracy past the small Whisper model |
| E3 text depth | Comic's edges; a word colour on a straight line; item 2 above |
| F1 slow motion | small fast subjects that slow and overlap come out a little narrow (a known limit, 10%) |
| F8 tracking, reframe | auto-reframe on real faces and things (no such footage on the phone) |
| G1, G2 sound | sound effects and noise reduction heard by an ear |

## Housekeeping owed to the phone

- `sh tools/bench/edge.sh <serial> x teardown` - deletes every test export and
  project made on 27-28 Oct by MediaStore id and slot.
- Remove `files/stickers/edge_ball.gif` (the GIF test sticker, by run-as).
- Settings' Online left off (it is off).
- Screen stay-awake was switched on for testing and switched back off (28 Oct).
