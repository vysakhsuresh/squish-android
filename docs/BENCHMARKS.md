# Benchmarks

EDGE_PLAN A1. Every C item quotes its before and after from here. Numbers are
from the release build (`assemblePerf`), measured by `tools/bench/bench.sh`
over the fixed benchmark edit (`tools/bench/fixture.sh`) and the footage made
by `tools/native/make_bench_media.sh` (`.github/workflows/benchmedia.yml`).
"Not measured" means exactly that; nothing here is estimated.

## The benchmark edit

Sixty seconds: six 1080p30 H.264 clips (17 Mb/s, busy generated pictures under
temporal grain, ten seconds each, trimmed to nine) and six seconds of a 4K30 HEVC
clip; the Vivid look on every shot; a Dissolve into shot 2 and a Slide left into
shot 4; a title over the first five seconds; a Squish original under it all; and
0, 1 or 3 picture-in-picture overlays (40%, in corners, eight seconds). The
"plain" edit is shot 1 alone, untouched - for quality.

How each number is taken:

| Measure | How |
|---|---|
| cold start | `am start -W` TotalTime, three runs after `am force-stop`, median |
| project open | SquishPerf `open-editor` (editor shown) and `open … first frame` |
| scrub | `dumpsys gfxinfo` over ten scripted 600 ms swipes on the strip (1-overlay edit) |
| playback | 20 s of play: frames the video renderer dropped (Media3 `onDroppedVideoFrames`, logged as SquishPerf `dropped`), and the screen's own gfxinfo over the same 20 s |
| export | SquishPerf `export`: wall time of the whole render against the edit's length; the file probed with `tools/jvm/Mp4Probe.kt` |
| quality | the plain edit exported at Original / Standard; ten frames of it and of the source (`tools/desktop/frames.ps1`, 960 px wide), luma SSIM by `tools/jvm/FrameCompare.kt` |
| heat | `dumpsys thermalservice` status and skin temperature before and after the export |

The moto g84's panel runs at 90 Hz: a frame has **11.1 ms**. gfxinfo's "janky"
counts frames that missed it.

## Baseline - f12fad2, moto g84 5G, 24 October 2026, 22:40-23:00

| Measure | Result |
|---|---|
| cold start | 383 ms (383, 393, 348) |
| project open | editor shown 195 ms; first frame 913 ms |
| scrub | 495 frames; **40.2% janky**; median 22 ms, p90 48 ms, p95 61 ms |
| playback, 0 overlays | 0 frames dropped by the decoder; screen 23.6% janky, median 15 ms, p90 22 ms |
| playback, 1 overlay | 0 dropped; screen 20.4% janky, median 15 ms, p90 23 ms |
| playback, 3 overlays | 0 dropped; screen 19.8% janky, median 14 ms, p90 23 ms |
| export 1080p30 | 43.7 s for 60 s - **1.37x real time**; 115.1 MB (15.2 Mb/s video, 131 kb/s AAC); 1800 frames at exactly 30.000 fps (33.33 ms x1799, 33.00 ms x1) |
| export 4K30 | not measured: the moto's encoder stops at 1080p (the Export sheet greys 4K) |
| quality | plain 10 s at Standard: **mean SSIM 0.864** (min 0.824, first frame 0.950), 21.7 MB |
| heat | thermal status 0 before and after the 1080p export; skin 29.95 -> 29.66 °C |
| import, pick to playable | not measured yet: the phone dropped off wireless adb before this step |

Reading it:

- **Scrubbing and playback both miss the frame budget** on this edit, by a long
  way while scrubbing. CLAUDE.md's 18 October measurement (app work ~4 ms, GPU
  11-14 ms) was on a lighter edit; this one has a song waveform, a title row and a
  4K source behind a 540p proxy. C3 and C7 start from these numbers.
- **The decoder keeps up**: nothing dropped with three overlays. The jank is in
  drawing, not decoding.
- **SSIM is harsh on this footage by design**: the clips carry heavy random grain
  that no encoder reproduces, so 0.86 is a floor to compare against, not a verdict
  on the encoder. The first frame (a keyframe in both) scores 0.95.
- **Export runs at 1.37x real time** - C1's target is InShot's time on the same
  edit, which needs InShot installed.

## C3, scrubbing - moto g84 5G, 26 October 2026, 20:00-21:35

`bench.sh scrubfs` on "Bench n1" (eight shots, a song, a title, Vivid on shot 1),
the release-speed build (`assemblePerf`), ten 600 ms swipes, over wireless adb.
"p90" is the whole frame's 90th percentile; "GPU" is gpu+render's p90; "twice"
is the share of frame gaps where a vsync was shown twice.

| build | p90 ms | GPU p90 ms | frames | twice |
|---|---|---|---|---|
| 06:01 build, before any C3 change | 67 | 40 | 341 | 41% |
| 09:09: strip and preview recompose less | 69-75 | 38-43 | 479-496 | 46-50% |
| + filmstrip and ruler drawn by one Canvas | 65-68 | 42-43 | 506-540 | 42-49% |
| `debug.squish.nofx`: the preview's effect chain off (an experiment) | 35 | 16 | 710 | 32% |
| 3088d07: a pass at its identity hands its frame on undrawn | 39-47 | 18-19 | 572-637 | 39-46% |
| the same build, `debug.squish.noskip` 1 (skipping off) | 69 | 42 | 500 | 50% |

Reading it:

- **The recomposition work of the morning moved nothing on the phone.** Counted
  after it (`debug.squish.counts`), the strip recomposes about ten times a second
  under a scrub and the preview about eight - composition was no longer the cost.
- **The GPU was.** The render thread spent most of each frame in `queueBuffer`
  waiting on it, and every preview frame went through seven full-frame passes
  (HDR lift, key, background, mask, look, blended still, effects) whatever they
  had to do. Taking the chain off halved the p90; handing a frame past every pass
  at its identity gets most of that back with the passes still there for any
  clip that uses them (`PassStage`, checked against a model of Media3's chain).
- **What is left is the main thread**, about 25-35 ms at p90, and the largest
  single part of it is accessibility: with a service on (this phone has one),
  Compose walks the whole layout tree on every semantics change, about 6 ms a
  walk and 1.6 s over the ten swipes. The timecode under the picture changes on
  every frame of a scrub, which is enough to set it off.

**How far one number moves over a night.** The same build and the same
swipes read p90 39-47 ms at 21:30 on a phone just picked up and 57-65 ms
from about 01:00 on, with the phone cool (thermal status 0) and on charge -
so a scrub figure is only compared against another taken in the same
session, back to back (`debug.squish.noskip` and the like exist for that).

## C6, a long edit - moto g84 5G, 27 October 2026, 01:35-02:20

"Edge 200 shots" (`edge.sh many 200`: 200 half-second shots cut from the six
benchmark clips, 1:40), release-speed build, six 600 ms swipes over wireless adb.

| | |
|---|---|
| opening it | editor shown in 195 ms, first frame at 985 ms |
| scrub, before (ed7e8d4's parent) | p90 82 ms, main thread p90 70, ClipView recomposed up to 342 times a second |
| scrub, after, thumbnails already in | p90 47-50 ms, main thread p90 38-40, ClipView 1-66 a second |

Found on the way: every clip on screen recomposed whenever its lane did,
because ClipView took its span as a `LongRange`, which the Compose compiler
holds unstable and compares by identity (`app_perf-composables.txt` says
which parameters are unstable - turn the reports on with a `composeCompiler {
reportsDestination = ... }` block for one build). And at the fitted zoom the
join badges, one every 24 dp over 26 dp shots, left a shot nowhere to be
tapped. Both fixed in ed7e8d4.

## C3 and C7 - moto g84 5G, 28 October 2026, 02:00-02:45

Release-speed build (assemblePerf), "Bench n1", over wireless adb, framestats
(`bench.sh scrubfs` and `tour`), all in one session.

**The strip cleared from accessibility while it moved (C3, 27 Oct) made the
scrub slower, and was taken out.** Back to back, `debug.squish.keepsemantics`
switching it:

| scrub, 10 swipes | whole frame median / p90 | app's part median / p90 |
|---|---|---|
| cleared while moving (keepsemantics 0), three runs | 26.9 / 64.3, 25.7 / 57.8, 24.5 / 55.1 | 15.1 / 48.7, 12.1 / 42.3, 12.1 / 39.7 |
| left alone (keepsemantics 1), two runs | 21.5 / 54.7, 20.8 / 54.8 | 8.6 / 37.2, 8.5 / 42.4 |
| the feature removed (a later build) | 23.4 / 57.2 | 9.6 / 37.7 |

The C7 tour, each step on its own (whole frame median / p90; the app's part
median / p90; GPU and render median):

| step | whole | app | GPU |
|---|---|---|---|
| home grid scroll | 15.4 / 21.0 | 4.3 / 7.8 | 11.3 |
| Settings scroll | 12.1 / 14.5 | 4.9 / 6.4 | 8.0 |
| open Edit | 18.5 / 26.2 | 2.4 / 7.8 | 16.2 |
| open Sound / its scroll | 14.2 / 22.3; 14.3 / 18.9 | 3.4 / 8.1; 5.2 / 8.6 | 11.0; 9.2 |
| open Text / its scroll | 17.9 / 43.4; 15.3 / 20.3 | 1.8 / 26.8; 5.4 / 6.9 | 15.6; 9.9 |
| open Stickers / its scroll | 11.0 / 155.4; 14.7 / 19.5 | 2.4 / 13.9; 5.0 / 8.2 | 8.3; 10.3 |
| open Overlay / its scroll | 6.9 / 10.4; 12.9 / 19.9 | 1.8 / 2.4; 3.7 / 7.5 | 5.0; 9.6 |
| open Effects / its scroll | 21.2 / 21.9; 13.4 / 22.4 | 2.8 / 4.5; 1.8 / 7.7 | 17.8; 11.5 |

So the bar (p90 under 11.1 ms everywhere) is not met, and what stands between
is the GPU, not the app: the app's part is 2-5 ms at the median nearly
everywhere, and even Settings, the plainest screen, spends 8 ms of GPU a
frame. Two spikes are the app's: a 155 ms frame opening Stickers (its design tiles
painted on the main thread - painted off it since cd9ae12, the step then measured
p90 25.3, p95 35.2) and an app p90
of 27 ms opening Text. The Effects sheet's tiles, each its effect drawn live,
hold the GPU at 18 ms a frame for as long as it is open. Look and Export were
off the toolbar's screen and the tour skipped them.

## Re-baseline - 2d8e488, moto g84 5G, perf build, 29 October 2026, 02:10-02:45

`bench.sh measure` on the release-speed build, then cold start re-run twice.

| Measure | Result | Against 24 Oct |
|---|---|---|
| cold start | 331 ms and 374 ms (432 on the first run after the install) | 383 |
| project open | editor shown 1130 ms on the first run after the install; the 200-shot edit's editor showed in 173 ms on this build earlier the same night | 195 |
| scrub (10 swipes) | 688 frames; **13.1% janky**; median 11 ms, p90 21 ms, p95 26 ms | 40.2%; 22 / 48 / 61 |
| playback, 0 overlays | 0 dropped; 26.7% janky, median 12 ms, p90 16 ms | 23.6%; 15 / 22 |
| playback, 1 overlay | 0 dropped; 31.7% janky, median 12 ms, p90 17 ms | 20.4%; 15 / 23 |
| playback, 3 overlays | 0 dropped; 24.8% janky, median 11 ms, p90 15 ms | 19.8%; 14 / 23 |
| export 1080p30 | 43.7 s for 60 s, 1.37x; 1800 frames, 33.33 ms x1799 | the same |
| quality | SSIM 0.882 - but the plain edit at Original is now copied, not encoded (589 ms, 17x, 22.3 MB, the source's size), so this is frames.ps1's own imprecision on identical pictures, not the encoder; the step needs a size that forces an encode to mean what it did | SSIM 0.864 (an encode) |
| import, pick to playable | activity 450 ms; first frame 948 ms | not measured |

The scrub went from 40% of frames late to 13% (C3's work and tonight's kept
timeline lists). Playback's median and p90 are down too, while its janky share
is about level - at 30 fps footage on a 90 Hz panel most "janky" frames are
the video's own cadence. On the 200-shot edit (`edge.sh many 200`, zoomed out),
scrubbing went from a median 26 ms of app work a frame (p90 62) to 9-11 (p90
24-26): TimelineState's lists kept per clip list, then clips under 8 dp drawn
as bars (ThinClips). Fifty photos added: 161 s, then 66 s (C6, still slow).
## Competitors

Not measured: neither InShot nor CapCut is installed on the moto (checked 24
October). The harness drives Squish only; a competitor run will be by hand over
the same footage and the same 60 s edit, and recorded here with how it was done.
