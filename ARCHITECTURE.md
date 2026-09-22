# Squish — architecture and roadmap

This document exists so the next person to open the repo — or the next version of
me — does not have to re-derive why the app is built the way it is, and does not
mistake a plan for a feature.

## 1. The stack, and why it is not being replaced

**Kotlin · Jetpack Compose · Media3 (Transformer + ExoPlayer) · MediaCodec.**

A rewrite in React Native, Tauri or WebAssembly has been proposed and rejected.
The reasoning, in one table, because this question will come back:

| Stack | Who does the encoding | Consequence for a 4K60 export |
| --- | --- | --- |
| **Media3 / MediaCodec (current)** | The phone's dedicated video encoder block | Realtime or faster, low battery cost |
| React Native + FFmpeg-mobile | CPU, in software | Roughly 10–20× slower, thermal throttling, flat battery |
| Tauri | No Android video pipeline at all | Does not exist as an option |
| FFmpeg-WASM | Single browser thread, ~2 GB memory ceiling | Cannot open the file |

Every performance goal anyone has asked for here — hardware acceleration, no
stuttering on 4K60, no frozen UI — *is* the thing MediaCodec gives and the thing
every alternative takes away. The decision is therefore closed, and reopening it
needs a new argument, not a new framework.

The second reason is narrower and just as real: the export pipeline, the
multi-track timeline and the audio-sync engine took ten rounds of compile fixes to
get standing. Throwing them away costs all of that and buys a slower encoder.

## 2. Layout

```
app/src/main/java/com/squish/app/
├── timeline/      Clip, TimelineState and the pure functions that transform them
│                  (split, trim, move, ripple, transitions, layers)
├── editor/        EditorViewModel + one immutable EditorUiState, all Compose panels
├── media/         Everything that touches a codec
│   ├── VideoProcessor      export orchestration
│   ├── CompositionFactory  A/B-roll sequences, transitions, overlay geometry
│   ├── ProxyEngine         background 540p proxies for smooth scrubbing
│   ├── SquishError         every failure the user can hit, in their language
│   └── audio/              PCM decode, waveforms, cross-correlation sync
├── data/          HistoryRepository, ProjectAutosave — plain JSON, no database
├── tools/         Standalone Compress / Trim / Extract audio / Merge
└── ui/            Theme, typography, shared components
```

The shape of it: **the timeline package is pure**, with no Android imports beyond
`Uri`, so every edit operation is a function from state to state and can be reasoned
about (and tested) without a device. The view model holds exactly one
`StateFlow<EditorUiState>`; nothing is mirrored into a second place, which is what
stops the preview and the export from ever disagreeing about what the edit is.

## 3. How playback works, and why it was rebuilt

The first version previewed the edit as an ExoPlayer **playlist**: the clips glued
end to end in order. A playlist has no notion of *when* a clip sits or that empty
space can exist between two of them. Two things followed, and both were reported
from real use:

- moving a clip changed nothing about when it played, because the playlist only
  knows order, not position;
- the reported playhead was "how far into the playlist we are", which stops being
  the same number as "where we are on the timeline" the moment anything is dragged
  — so the playhead wandered through empty space.

`PreviewEngine` inverts the relationship. **Timeline time is the authority**, and
everything else is slaved to it:

- the covering clip is looked up *by time*, every tick, so a clip that moves plays
  at its new position immediately;
- the video player holds the whole source file, never a clipped window, so its
  position *is* source time and converts to timeline time by one addition — and a
  split costs a seek rather than a reload, because the file is already open;
- empty space is a real state: the picture goes black and the clock keeps running,
  because that is what the exported file does there;
- each added sound is an independent player positioned against the same clock,
  which is what makes any number of overlapping tracks work at all.

The clock itself prefers the picture's own: while a clip is playing, the position
is derived from the video player, so the playhead can never disagree with the
frame on screen. Wall time carries it across gaps, and a decoder stall holds the
clock rather than letting sound run ahead of picture.

### The audio stutter, specifically

The old preview re-seeked the audio player whenever it drifted 45 ms from the
video. Every seek forces a re-buffer, a re-buffer causes more drift, and that
drift triggers the next seek — a feedback loop that sounds exactly like audio
breaking up, and that only quietens once the file is warm in the page cache.
That is the whole explanation for "it plays properly on the fourth or fifth try".

Now a sound is seeked **once**, on the way into its range, and then left to run on
its own clock. The correction threshold is 400 ms and exists only to absorb a
scrub; two media clocks at 1x drift by a few milliseconds a minute, so during
playback it never fires.

## 4. The three guarantees

These are what the app is actually competing on. They are implemented, not planned.

### Nothing is ever uploaded
No network permission is requested, no SDK phones home, and there is no account.
Every frame is decoded, composed and encoded on the device. This is not a privacy
policy; it is an architectural fact you can verify from the manifest.

### No edit is ever lost
`ProjectAutosave` writes the whole timeline to disk every 1.5 seconds, and the write
is atomic: a temporary file is flushed to the platter with `fsync`, then `rename`d
over the live document. `rename(2)` is atomic, so the saved project is always either
the complete previous version or the complete new version — never a truncated file,
however abruptly Android kills the process. The previous version is kept beside it as
a second parachute. On reopening, the edit is *offered* rather than silently applied,
because overwriting what someone just opened is its own kind of data loss.

### Failure is explained, never swallowed
`SquishError` names every failure a user can hit and carries three things: what
happened, why, and the one action that fixes it. Media3's numeric codes are mapped
by exact constant where the distinction changes the advice and by thousands-band
otherwise, so a library upgrade cannot silently change which sentence is shown.
Space, permissions, missing audio and absurd resolutions are checked *before*
encoding starts, so a doomed export fails in a second rather than two minutes.

### (And the thing that makes heavy footage usable)
`ProxyEngine` builds a 540p stand-in for anything above 1080p, in the background,
while editing continues. The preview player uses the proxy; the export pipeline
never sees it and always reads the camera original. The proxy is a straight
transcode sharing the source timebase, so every trim point still lands on the same
frame. Proxies live in the cache directory — derived data, safe for Android to
evict, rebuilt on demand.

## 5. Built

- Multi-track timeline: split, trim, move, delete, close gaps, zoom
- Frame-accurate precision trim driven by the clip's real frame rate
- Transitions (dissolve, dip to black, slide, wipe) via A/B-roll compositing
- Layered compositing: picture-in-picture with opacity, scale and position
- Unlimited audio tracks: music, voiceover and a second mic at once, overlapping
  freely, each trimmed, cut, moved and levelled like any other clip
- Timeline-driven preview with its own transport, black gaps and multi-track sound
- Automatic dual-system audio sync by RMS-envelope cross-correlation
- Speed, rotation, crop with live framing guides
- Text overlays with timing, colour, size and position
- Colour: brightness, contrast, saturation
- Export presets, fit-to-size bitrate solving, gallery publishing
- Quick tools: compress, trim, extract audio, merge
- Atomic auto-save and crash recovery
- Background proxy generation
- Typed, human-readable errors

## 6. Not built — and honestly scoped

Listed in the order they would actually be worth doing. None of these are small;
claiming otherwise would be the fastest way to lose trust in this document.

One limitation worth stating plainly: the preview shows the **base video track**.
Layer compositing (picture-in-picture) and transitions are applied at export, not
in the preview, so a dissolve is something you currently set up and then render to
see. Compositing them live needs a second player surface per layer and is the next
structural piece of work.

| Feature | Real cost | Note |
| --- | --- | --- |
| Live preview of overlays and transitions | ~1 week | A player surface per layer, composited in the preview. |
| Effects / filter library (LUTs) | Days | Highest value per hour. Media3 supports custom GL effects directly. |
| Keyframes for existing parameters | 1–2 weeks | Needs an interpolation model on every animatable property, plus timeline UI. |
| Audio beat detection | Days | Onset detection on the PCM data we already decode for waveforms. |
| Chroma key | 1–2 weeks | A GL shader is a day; spill suppression and edge matting are the rest. |
| Masking | 2–3 weeks | Shape masks, feathering, and per-mask keyframing. |
| Auto-captions | 1–2 months | On-device ASR model, bundled weights, per-language packs, app-size budget. |
| Text to speech | Weeks | Android's TTS engine gets a usable first version; a good one does not. |
| Stabilization | 1–2 months | Optical flow, trajectory smoothing, crop compensation. |
| Motion tracking | 2–3 months | The hardest item here by a wide margin. |

The "one release, all features" goal stands. What cannot stand is the idea that
this list is a single session's work — it is a roadmap of roughly a year for one
person, and pretending otherwise would just move the disappointment later.
