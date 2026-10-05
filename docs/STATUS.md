# Status, 29 September 2026

> **A snapshot of that date, kept as one.** The batch table and the two
> "seen"/"not seen" sections below are six days and nine sweeps out of date -
> plenty has been driven on the phone since (30 September, 1 October, 4 October
> and 5 October), and plenty more has been built that is not in the table at
> all. For where things actually stand, in the order a session needs them:
> `CLAUDE.md`'s "What is currently unverified on a device", then
> `docs/ROADMAP.md` §5 for what to do first with a phone in hand, then
> `docs/DEVICE_FINDINGS.md` (newest last) for what each round found. The
> "Known, left on purpose" list at the end of this file *is* still accurate.

## Done

The whole roadmap in `docs/ROADMAP.md` is built and merged into `main`: all sixteen
batches, then a cross-batch review that confirmed 36 bugs and fixed 33 of them. The
build is green. Each batch was reviewed by two independent reviewers (one for
correctness, one for user experience and regressions) and their findings were fixed
before it merged.

| Batch | What it changed |
|---|---|
| B1 | Nothing ordinary loses work: exporting keeps the project, discarded drafts go to a 30-day bin, leaving saves at once, earlier versions kept |
| B2 | A main track where clips stay butted together; cuts and trims keep animation |
| B3 | Every edit is one undo step with a truthful label |
| B4 | Preview never rebuilds a player mid-play (fixed the rotate freeze), overlays drawn like the export |
| B5 | Export rebuilt on Media3 1.11: transitions, picture-in-picture, gaps, photos, crop-aware sizes |
| B6 | New editor layout: header with undo/redo and Export, full-height preview, two-level toolbar (main, and a clip toolbar when something is selected), tool sheets |
| B7 | Timeline: fixed centre playhead, drag to scrub, long-press to reorder, snapping, stacked rows |
| B8 | Overlays done properly: own rows, bounding box on the picture with drag, pinch, rotate |
| B9 | Sound: voiceover recording, fades, voice effects, beats, volume |
| B10 | Text and stickers: keyboard opens when text is added, styles, animations |
| B11 | Clip tools: duplicate, replace, freeze frame, reverse, mirror, rotate, extract audio, multi-select |
| B12 | Frame and colour: per-clip filters and adjust, crop, canvas and background |
| B13 | Animation, speed curves, transitions, effects |
| B14 | Export: background export with a notification, stop, honest sizes and errors |
| B15 | Home and projects: named projects, project list, first-run help |
| B16 | Polish across the editor |

## Seen working on the phone

Only what was on the phone during the night of 28-29 September, on the builds of
that time (waves 0-2): rotate, rotated export, video + photo + dissolve export, music
export with sound, title burned into the export, exporting keeps the project, the
draft bin and earlier versions, save-on-leave, overlays on their own row, the new
editor layout and clip toolbar. Details in `docs/DEVICE_FINDINGS.md`.

## Not yet seen on the phone

Everything from B7 onwards and the integration fixes. They build and their pure logic
is executed on the JVM, but nothing proves them like the device. The first things to
check are in `docs/DEVICE_FINDINGS.md` (newest entries first) and in each batch's
test script in `docs/ROADMAP.md` section 4. Highest risk:

- The effects shader in the preview was ported to AGSL by hand; if it fails to
  compile the preview falls back to per-shot effects. Check logcat.
- Export with an overlay, and a timeline whose overlay runs past the main track.
- Stop during a long export may block the screen while Media3 cancels.

## Known, left on purpose

- Grain, bloom and sharpen on a photo overlay show in the export but not the preview.
- Auto-reframe during a transition uses one crop window in the preview, one per shot
  in the export.
- 10-bit HEVC (film rips) cannot be decoded on the moto g84; the app says so.
