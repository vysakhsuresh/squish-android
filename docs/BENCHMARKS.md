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

## Competitors

Not measured: neither InShot nor CapCut is installed on the moto (checked 24
October). The harness drives Squish only; a competitor run will be by hand over
the same footage and the same 60 s edit, and recorded here with how it was done.
