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
├── editor/        EditorViewModel + one immutable EditorUiState, the toolbar and
│   │              its sheets (ToolRules, ToolBar, ToolSheet), all Compose panels
│   └── edits/     the edits themselves, one class per area (clips, audio, text,
│                  layers, analysis), all through the view model's one state and undo
├── media/         Everything that touches a codec
│   ├── effects/            the look catalogue, grading maths and the two shaders
│   ├── video/              motion estimation, trajectory smoothing, object tracking
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
No SDK phones home and there is no account. Every frame is decoded, composed and
encoded on the device: no footage, no photo and no project ever leaves it.

This used to read "no network permission is requested", and that stopped being
true when the online features arrived - free music, stock footage, fonts and
caption translation. `INTERNET` is in the manifest, with the reason beside it.
What is still an architectural fact rather than a policy is the *shape* of the
permission's use: every request in the app goes through `online/Online.kt`,
whose `get` and `download` throw while the Settings switch is off, and a tool
that needs the internet asks first (`rememberOnlineGate`). Both of those take a
URL and give back bytes - **there is no call in `Online` that sends a body**, so
there is nowhere for a video to go. Settings' privacy card says exactly that
("off until you turn them on, and only fetch"), and a grep is the check:
`Online.open` sets a timeout, a redirect policy and a User-Agent and nothing
else - no `setRequestMethod`, no `doOutput` - so every request is a GET. The one
`outputStream()` in the file writes the bytes that came *back* into a local file.

Google's datatransport, which came in with MediaPipe, is removed in the
manifest. Keep it removed.

### No edit is ever lost
`ProjectAutosave` writes the whole timeline to disk every 1.5 seconds, and the write
is atomic: a temporary file is flushed to the platter with `fsync`, then `rename`d
over the live document. `rename(2)` is atomic, so the saved project is always either
the complete previous version or the complete new version — never a truncated file,
however abruptly Android kills the process. The previous version is kept beside it as
a second parachute, and two snapshots move along every ten minutes — the pending one
is taken from the live file, and ten minutes later it becomes the snapshot — so the
snapshot always holds a version at least ten minutes old and a run of bad saves
cannot roll over every good version there was. The Unfinished list offers it as
"Earlier version"; going back puts the current version in the bin. Tool sessions
keep the same backup and snapshots. "Exported · edited since" compares a
fingerprint of the edit with the one that was rendered, never save times.
Leaving the editor, the app going behind something and the view model being
cleared each flush the edit once more, so nothing falls into the ticker's gap.
On reopening, the edit is *offered* rather than silently applied, because
overwriting what someone just opened is its own kind of data loss.

Nothing deletes a draft. A finished export stamps it as exported and keeps it;
"Start a new project", discarding from the Unfinished list and undoing all the way
back to the untouched clip move it into `projects/trash/` (tool sessions into
`tooldrafts/trash/`), where it is listed under "Recently discarded" for thirty
days and can be put back. Deleting from the bin is the only real delete, and it
asks first. Quick-tool sessions each save into their own slot, named in the
route, so starting a second Stitch never writes over the first.

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
Every built-in Media3 color effect maps RGB to RGB. Chroma key has to produce
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

**The default threshold was tuned against the maths, not by eye.** Neutral colors
— a white shirt, a gray wall, black hair — all sit about 0.33 from digital green in
this space, while a green screen in deep shadow is still within 0.20 of it. The
usable window is therefore about 0.20 to 0.30, and the first draft of this defaulted
to 0.38, which would have deleted the subject's shirt. It now defaults to 0.24, mid
-window, with roughly 0.05 of margin either side.

The same analysis says a **blue** screen has almost no window at all, because denim
sits 0.183 from digital blue and the screen's own shadows reach 0.178. That is
physics rather than a bug, and the panel says so rather than letting you find out
during a shoot.

### Tracked masks, and what a privacy tool has to actually do
A mask that follows a track is where the tracker and the masks pay off together. A
face does not hold still. A privacy mask that cannot follow one is a mask you would
keyframe by hand, for every frame of the shot.

The hook is the same one everything time-varying in this app uses — the shader
program is handed a presentation time per frame, so a tracked mask simply sets a
different center each time. Which clock that time is on differs between the preview
and the export, so the effect is told which: the preview player holds the whole
source file and its times *are* source time, while the export latches its first
frame as the clip's origin. Getting that wrong does not fail loudly, it just puts
the shape on the object at the wrong moment.

Masking gained two modes beyond cutting out, because hiding a face is not the same
operation as compositing one. `Cutout` makes the outside transparent; `Pixelate` and
`Blur` leave the frame **and its alpha** untouched and destroy only what is inside
the shape — hiding a face must not also punch a hole in the picture.

**Both obscure modes were rendered and looked at, and both were wrong first time.**
The blur topped out at a 3% radius, through which a face came through perfectly
recognisable — which is worse than no blur at all, because it looks like the job was
done. It now reaches 14%, and uses twenty-four taps on a golden-angle spiral rather
than two rings of six: at a large radius, rings put every tap at the same few angles
and read as a smear, where a spiral fills the disc. The pixelation took one pixel per
block, so a dark eye either vanished or became a solid black square; it now averages
nine samples across the block, which reads as a censor rather than a glitch.

### Motion tracking
Same shape as stabilization — an analysis that writes into machinery already built —
but a different matching problem, and the difference decides the algorithm.

Stabilization compares whole consecutive frames, which are lit identically, so an
absolute-difference score is fine. A tracked object walks through shadow and
sunlight, and a plain difference score would follow the lighting rather than the
object. `ObjectTracker` therefore uses **zero-mean normalized cross-correlation**:
subtracting each patch's own mean and dividing by its own spread makes the score
care about pattern instead of brightness. Against synthetic frames it holds to 1.4
pixels while the scene dims to 55%.

The template is **nudged** toward what it currently sees rather than replaced or
frozen. Frozen loses the object the moment it turns; replaced lets the template
wander onto the background a little each frame until it is tracking nothing at all.
A slow blend, anchored back toward the original selection, keeps up with real change
and takes a long time to drift — and a poor match is never learned from, which is
how a tracker eats background.

A lost object is **not chased**. Below the confidence floor the last good position is
held, so the tracker can pick the object up again when it reappears; following a bad
match walks the template onto the background and never recovers.

What the track drives is a separate decision from measuring it. A caption carries the
track directly — `TextOverlay.getOverlaySettings` is asked per presentation time, so
a pinned caption simply reports a different anchor each frame. A layer gets ordinary
**keyframes**, because unlike stabilization this *is* an edit and you should be able
to nudge it afterward.

Three clocks meet here — source time, timeline time and a layer's own local time —
and a sign error in any conversion would put every pin at the wrong moment while
looking entirely plausible. The chain is verified to round-trip losslessly.

Closing a gap this exposed: captions were **export-only** until now, so a tracked one
could not be seen following anything until after a render. They are applied to the
preview surfaces too, shifted into each clip's own clock so they appear at the right
moment over a clip that has been trimmed or moved.

### Stabilization as analysis, not rendering
The app already applies a time-varying transform per frame, in the preview and the
export alike. So stabilization needed no new rendering at all: it measures the shake
and writes a corrective **keyframe track**, which the existing `ClipTransformEffect`
then applies. The feature is arithmetic dropped into machinery that was already
there.

The idea underneath is one observation: a shaky shot is an intended camera path with
noise added. Integrate the frame-to-frame motion and you have the path the camera
actually took; smooth that path and you have the path it meant to take; the
difference is what to undo. A pan survives because it is in both, and only the
jitter cancels. Simply canceling *all* motion is the classic way to make
stabilization look worse than none — it locks the frame rigid and turns a deliberate
pan into a stutter as the correction saturates against the crop.

Motion is estimated by matching each frame against the last across a range of
offsets on a 96-pixel-wide luma grid, refined to sub-pixel by fitting a parabola
through the best score and its neighbours. Real optical flow wants OpenCV, a 30 MB
native dependency; for undoing handheld shake — a whole-frame movement — a global
match is the right model anyway, and feature tracking would mostly be a more
expensive way to compute the same number. Roll comes from matching the two halves of
the frame separately: if the left drifts down while the right drifts up, the camera
rolled.

The measured track is kept **separate from the user's keyframes** and composed at
evaluation time, so an edit never destroys an analysis and an analysis never
destroys an edit. It is keyed by *source* time rather than clip time, because the
correction belongs to a frame of the file — trimming the head of a clip must not
slide the whole correction out of step with the picture it was measured from.

The correction is bounded by the crop and scaled down uniformly when it would
overshoot, rather than clipped per frame: a clipped correction puts a kink in the
path, and a kink is exactly the jolt being removed.

### Captions: two halves, honestly separated
Captioning is two jobs, and only one of them is hard to do on a phone.

**Finding the speech** is the half that eats an afternoon — typing a sentence takes
seconds, finding the exact frame someone starts and stops talking, forty times
over, does not. `SpeechSegmenter` does it on the PCM the app already decodes for
waveforms and A/V sync: energy framing with hysteresis, a threshold derived from the
recording's own statistics rather than a constant, gaps under ~220 ms merged because
that is a pause between words rather than between sentences, and long runs split so
no caption is unreadable. No model, no download, no network. This always works.

**Turning it into words** needs a speech model. There are three ways to get one and
only one of them is acceptable here:

| Approach | Why not |
| --- | --- |
| Cloud API | The app has no network permission and a written promise that nothing leaves the device. Disqualified by design. |
| Bundled Whisper/Vosk | 40–50 MB of weights plus a native dependency. Defensible as a product decision; not something to add unverified. |
| **On-device `SpeechRecognizer`** | No new dependency, no app-size cost, never leaves the phone. Needs Android 13 and a model the user has installed. |

So Squish uses the third, and says plainly when it is unavailable. You still get
every caption card on exactly the right frames, which is the expensive half.

**SRT import and export** is the escape hatch, and it matters more than it looks:
on-device recognition is not available everywhere and is not equally good in every
language, so being able to bring a transcript in from whatever tool you trust is the
difference between captions being a feature and captions being a dead end. The
parser is deliberately forgiving — BOMs, CRLF, dots for commas, missing indices,
missing blank lines — because a parser that rejects real files is technically
correct and practically useless.

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
Keyframed *opacity* or *color* would need a different mechanism (a time-varying
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
gain *is* a color cast, and contrast against saturation is the whole distance
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
hand-picked swatch color is a drawing of a promise; it starts lying the moment a
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
  freely, each trimmed, cut, moved and leveled like any other clip
- Timeline-driven preview with its own transport, black gaps and multi-track sound
- Automatic dual-system audio sync by RMS-envelope cross-correlation
- Speed, rotation, crop with live framing guides
- Auto-captions: speech detection and timing on-device, transcription where the
  device supports it, SRT import and export, and an inline caption editor
- Chroma key with spill suppression, sampled from your own frame, live in preview
- Shape masks — rectangle, ellipse, linear, mirror — feathered, rotatable,
  invertible, composing with the key rather than replacing it
- Motion tracking: pin a caption, a layer or a privacy mask to something moving
- Face and plate hiding: a tracked pixelate or blur that follows the subject
- Stabilization: global motion estimation, trajectory smoothing and automatic crop
- Keyframed motion: scale, position and rotation over time, with smooth, linear
  and hold easing, six one-tap presets, and live preview
- Color: brightness, contrast, saturation
- Effects library: 16 graded looks across three families, with a strength dial,
  previewed live and previewed honestly on the chips
- Export presets, fit-to-size bitrate solving, gallery publishing
- Quick tools: compress, trim, extract audio, merge
- Atomic auto-save and crash recovery
- Background proxy generation
- Typed, human-readable errors

## 6. Not built

**This section used to be a table of eleven things to build, with costs from
two days to three months and a closing line about it being a year's work. Every
one of the eleven is built.** The table stood unchanged while they were built
underneath it, which made this file say that features anyone can see in the
code do not exist — the exact rot the first page of CLAUDE.md is about. Where
each went, so nobody has to find out the way I did:

| It said | Where it is |
| --- | --- |
| True 3D LUTs and custom shaders | `media/effects/Lut.kt`, `.cube` import, the tile atlas in `squish_look_es2.glsl`; grain, vignette and halation are sliders on Adjust |
| Per-clip looks | `Clip.lookId` and `Clip.adjust`; the whole-timeline grade is gone (B12) |
| Keyframed opacity and colour | `ValueTrack.Opacity` and `ValueTrack.Look`, keyed from the sheets and drawn on the strip (B13) |
| Keyframes for existing parameters | `timeline/ValueTracks.kt` and `Keyframe`, with smooth, linear and hold easing |
| Audio beat detection | `media/audio/BeatDetector.kt` — onset envelope, Ellis tempo scoring, dynamic-programming tracking |
| Chroma key | `squish_chroma_key_es2.glsl` with spill suppression, sampled by the loupe |
| Masking | `timeline/Mask.kt` and `squish_mask_es2.glsl`: six shapes on a distance field, feathered, keyed on a track |
| Auto-captions | `media/audio/Transcriber.kt` on the platform recogniser, `SpeechSegmenter` for the timings, SRT both ways |
| Text to speech | `media/audio/Tts.kt`, landed as a sound clip under `files/speech/` |
| Stabilization | `media/video/MotionEstimator.kt`, `TrajectorySmoother.kt`, `StabilizerSolve.kt` |
| Motion tracking | `media/video/ObjectTracker.kt` (ZNCC template tracking) and `TrackRunner.kt` |

What is genuinely not built, as of 5 October:

- **Keyframes on a mask's shape and on a clip's filter.** A mask already moves
  on its Track and a filter is per clip; the `ValueKey` track is what they would
  key, so neither is hard — they were left out of B13 on purpose, not missed.
- **Keyframes on a line of words or a sticker.** Those are not clips; they have
  their own arrival, leaving and loop from B10, and giving them a key track
  means deciding how the two interact.
- **A Stop action on the export's notification.** Declined: it cannot ask first,
  and a render thrown away by a mis-tap on a lock screen is worse than walking
  back into the app.

The "one release, all features" goal stands, and the honest caution the old
section ended on is still worth keeping: the list above took far longer than the
table's estimates, and nothing on this page should be read as a promise about
how long the next thing will take.
