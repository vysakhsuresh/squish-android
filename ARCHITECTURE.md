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
│   ├── effects/            the look catalogue, grading maths and the two shaders
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

### Compositing the preview
The base track previews as **A/B roll**, the same way the exporter builds it. A
transition needs two shots on screen at once and one player shows one, so
consecutive clips are dealt onto two players by index parity - which guarantees any
two overlapping neighbours land on different players. Where they overlap, the
transition is a blend between the two surfaces; each overlay layer gets a player
above them.

The parity rule is copied from `CompositionFactory` deliberately. If the preview and
the exporter disagreed about which shot sits on which roll, a dissolve would preview
one way and render the other.

Every surface is a **TextureView, not a SurfaceView**. A SurfaceView is punched
through the window and composited by the system, so it ignores view alpha,
transforms and clipping outright - on one of those, every dissolve, slide and
picture-in-picture would silently do nothing. A TextureView draws into the view
hierarchy, which is what lets a wipe be a clip rect and a dissolve be an alpha.

Whichever shot is already driving the clock keeps it for the whole transition.
Handing it over mid-blend would step the playhead by however much the two players
happen to differ.

### Chroma key, and the one shader in the app
Every built-in Media3 colour effect maps RGB to RGB. Chroma key has to produce
**per-pixel alpha**, and no combination of Contrast, HslAdjustment, RgbAdjustment
or a 3D LUT can cut a hole in a frame — so unlike the look library, there is no
version of this that avoids a shader. It is the app's only one.

The comparison happens in chroma alone, the UV plane of YCbCr with luma discarded.
A green screen is never evenly lit, and a key that compares full RGB punches holes
in the shadowed folds of the cloth while leaving the hot spots solid. Chroma barely
moves under a lighting change, so one setting holds across the frame. Spill
suppression pulls surviving pixels toward their own luminance in proportion to how
close they still are to the key, which is what removes the green fringe on hair
without touching anything else.

The same `ChromaKeyEffect` instance type runs in the preview and the export — the
preview player is handed it through `setVideoEffects` — so the key you tune is the
key that renders.

**The default threshold was tuned against the maths, not by eye.** Neutral colours
— a white shirt, a grey wall, black hair — all sit about 0.33 from digital green in
this space, while a green screen in deep shadow is still within 0.20 of it. The
usable window is therefore about 0.20 to 0.30, and the first draft of this defaulted
to 0.38, which would have deleted the subject's shirt. It now defaults to 0.24, mid
-window, with roughly 0.05 of margin either side.

The same analysis says a **blue** screen has almost no window at all, because denim
sits 0.183 from digital blue and the screen's own shadows reach 0.178. That is
physics rather than a bug, and the panel says so rather than letting you find out
during a shoot.

### Masking
A second alpha shader rather than a branch inside the key one, so the two chain:
keying writes a matte, masking multiplies into whatever alpha arrived. A clip can be
keyed *and* masked and neither overwrites the other's work.

All four shapes are signed distance functions in one shader. They differ only in how
the distance is measured — once you have a signed distance, feathering, inverting
and compositing are identical for all of them, and writing that four times is four
places for the edges to stop matching.

Rotation and corner rounding happen in **pixel-isotropic units**: the x axis is
scaled by the frame's aspect before the shape is measured. Skip that and a turned
rectangle shears into a rhombus on anything but a square frame, which is every frame
anyone actually shoots. The frame's shape is only known in `configure`, so that is
where the aspect uniform is set.

There are no drag handles on the preview, deliberately. The mask is applied by the
preview's own shader, so the sliders move the finished result live; handles would be
a second, worse representation of something already on screen.

Masks are static per clip for now. The shader can take its uniforms per frame — the
hook is the same one the keyframe system uses — so animated reveals are a UI problem
rather than a rendering one, and are scoped below rather than claimed.

### Keyframes, and the one hook that makes them possible
Media3's per-frame effects are static: `Contrast`, `HslAdjustment` and `AlphaScale`
are handed one value and keep it for the whole clip. `MatrixTransformation` is the
exception — it is asked for a matrix **per presentation time**, which is precisely
the hook an animated transform needs. So scale, position and rotation can move over
time on an API the app already relies on, with no custom shader anywhere.

That bounds the feature honestly. Transform keyframes cover the moves people
actually reach for — a push-in, a drift, a settle, an animated picture-in-picture.
Keyframed *opacity* or *colour* would need a different mechanism (a time-varying
alpha effect, or `RgbMatrix`) and are not in this round.

`ClipTransformEffect` derives its own time origin from the first presentation time
it is handed, rather than assuming one. Media3 has offered both item-relative and
composition-relative presentation times across versions, and an animation anchored
to the wrong origin would not fail loudly — it would simply play at the wrong
moment, or be over before the clip appeared. Each clip gets its own instance and
frames arrive in order, so the first time seen *is* that clip's zero.

Keyframe times are measured from the clip's start **on the timeline**, so moving a
clip carries its animation with it — which is what "this shot pushes in over its
three seconds" means to an editor. Outside the first and last key the animation
holds rather than extrapolating; a value that keeps racing past the last key you set
is never what you meant. The list is kept sorted on insert, so evaluation on the
render thread never sorts and allocates nothing beyond its result.

### The effects library, and why it is not a shader
`Looks` describes each grade as three moves - per-channel gain, contrast,
saturation - because Media3 gives exactly those as built-in, hardware-backed
effects. Between them they cover the grades people actually reach for: a channel
gain *is* a colour cast, and contrast against saturation is the whole distance
between Vivid and Faded.

Writing custom GLSL would buy grain, vignette and halation, and would cost a
shader pipeline that cannot be verified anywhere but on a device. Real 3D LUTs
(`SingleColorLut`) are the right next step and are on the table above. This is the
version of the idea that ships working today.

Two things keep it honest. The look and the manual sliders are **folded into one
grade** before reaching the GPU, so grading costs three shader passes no matter how
much of it is going on, rather than six. And the filter chips are painted by
running that same grade over a reference ramp - the identical arithmetic the
shaders do - so a chip cannot drift away from what the look actually does. A
hand-picked swatch colour is a drawing of a promise; it starts lying the moment a
look is retuned.

The reference ramp is deliberately not near-white at the top. A bright reference
clips to flat white under any contrast boost, and the first version of this had
Vivid, Punch, Sepia and Neon all rendering an identical white band - a filter row
that told you nothing. Only Noir and Bleach clip now, which is truthful, because
crushing is what those two are for.

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
- Transitions (dissolve, dip to black, slide, wipe) via A/B-roll compositing,
  previewed live
- Layered compositing: picture-in-picture with opacity, scale and position,
  composited live in the preview as well as at export
- Unlimited audio tracks: music, voiceover and a second mic at once, overlapping
  freely, each trimmed, cut, moved and levelled like any other clip
- Timeline-driven preview with its own transport, black gaps and multi-track sound
- Automatic dual-system audio sync by RMS-envelope cross-correlation
- Speed, rotation, crop with live framing guides
- Text overlays with timing, colour, size and position
- Chroma key with spill suppression, sampled from your own frame, live in preview
- Shape masks — rectangle, ellipse, linear, mirror — feathered, rotatable,
  invertible, composing with the key rather than replacing it
- Keyframed motion: scale, position and rotation over time, with smooth, linear
  and hold easing, six one-tap presets, and live preview
- Colour: brightness, contrast, saturation
- Effects library: 16 graded looks across three families, with a strength dial,
  previewed live and previewed honestly on the chips
- Export presets, fit-to-size bitrate solving, gallery publishing
- Quick tools: compress, trim, extract audio, merge
- Atomic auto-save and crash recovery
- Background proxy generation
- Typed, human-readable errors

## 6. Not built — and honestly scoped

Listed in the order they would actually be worth doing. None of these are small;
claiming otherwise would be the fastest way to lose trust in this document.

| Feature | Real cost | Note |
| --- | --- | --- |
| True 3D LUTs and custom shaders | ~1 week | Grain, vignette, halation and .cube import, on top of the look library below. |
| Per-clip looks | 2-3 days | The grade is currently the whole timeline; Clip would carry its own. |
| Keyframed opacity and colour | 3-4 days | Needs a time-varying alpha effect and RgbMatrix; the transform hook does not cover them. |
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
