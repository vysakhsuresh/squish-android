# Build notes

Read this if the first Gradle sync throws a red line. It is a short list, and
every item is a local fix.

## Why this file exists

This project is written in a sandbox with no Android SDK and no reachable
Google Maven mirror, so it has never been through a compiler. Everything here
is written against documented APIs, brace-balanced and symbol-checked, but
"never compiled" is never compiled. The riskier surfaces are deliberately
isolated into single small files so a signature mismatch is a one-file fix
instead of a rewrite.

Paste any compiler error back and it will be fixed directly — the list below is
so you can also fix it yourself in thirty seconds.

## Media3 1.11.1, and what the export leans on

Media3 is at 1.11.1 (Kotlin 2.2.10, compileSdk 36 - both needed by it). The
breaks that came with it, all fixed: `MetadataRetriever` is an instance per
file in the new `media3-inspector` module; `OverlaySettings` is an interface in
`media3-common` with `StaticOverlaySettings.Builder` in `media3-effect`;
`ChannelMixingMatrix.create` is gone (the constructor takes explicit
coefficients); `MediaCodecInfo.isFormatSupported` takes a `Context`; and
sequences declare their tracks with `EditedMediaItemSequence.Builder(setOf(...))`.

The composited export (`CompositionFactory`) depends on exactly these, read in
the 1.11.1 sources: the compositor's primary input is the first sequence with
video and it draws inputs back to front, index 0 on top
(`DefaultCompositorGlProgram.drawFrame`); `getOverlaySettings(inputId, …)` is
keyed by sequence index; a sequence declared with an audio track fills any item
without one with silence. Transitions are drawn into each shot's own pixels and
empty stretches are a transparent still, so nothing else about the compositor
is assumed. Anything that changes those three facts in a later version changes
the stacking of every layered export - check `tools/jvm/ExportPlanChecks.kt`'s
assumptions against the new sources before upgrading.

Two consequences of the clock being the primary, both deliberate. Its first
format sets the file's colour (`VideoSampleExporter`): an sRGB still maps to
SDR BT.709, so every composited export is SDR and HDR clips in it are
tone-mapped on the way in, while the same clips cut end to end keep HDR. And
`LayerSettings` hides input 0, so the clock has to be there however short the
edit (`ExportPlan.pieces` always gives it one stretch; `buildComposited` refuses
to build otherwise). Gaps under `ExportPlan.MIN_GAP_MS` are rounding: dropped in
a layer, where each slip is made good at the next real gap, and allowed in a
cuts-only export only while their sum stays under the same bound.

Overlay rows (B8) lean on two more: an overlay row that has a clip with sound
is declared with an audio track and so relies on the same silence-filling for
its gaps, photos and muted clips; and a photo on an overlay row is handed over
as a PNG image item (`CompositionFactory.stillItem`), relying on Media3's image
input keeping the picture's alpha through to the compositor. If a transparent
logo comes out on black, that is the fact that did not hold.

One more, found on the phone: a sequence declared with sound whose first item
has none makes `SequenceAssetLoader` force a sound track with
`checkNotNull(listener.onOutputFormat(...))`, and the listener answers null
until the lowest sequence declaring sound has made the sound exporter. An image
loader loses that race to a video decoder ("Asset loader error", a
NullPointerException in `SequenceAssetLoader.onOutputFormat`). So the clock
opens on Media3's own gap, declared with sound whenever any layer is
(`ExportPlan.sequenceTracks`): a gap loader announces both tracks before it
starts, and as sequence 0 it makes the sound exporter first and the picture's
second, so no layer can get past its own picture - and ask for sound - before
the exporter exists. Read in the 1.11.1 bytecode: a sequence asks for the
forced sound track only after its own picture consumer was granted, and the
gap loader asks sound-then-picture inside one call, so the order cannot be
lost; a still as the first item would ask picture-then-sound and open a
window. What that leans on: `GapSignalingAssetLoader` outputs audio before
video, the primary for a track is the lowest sequence that registered one, and
the mixer takes its format from the first input registered.

Two more from the gap, both handled in `CompositionFactory`. A gap's blank
frames come at a fixed 30 fps (`SequenceAssetLoader.insertBlankFrames`) and
the compositor writes one output frame per primary frame, so a clock that was
all gap wrote every layered export at 30 fps; the clock is one frame of gap
(`ExportPlan.clockLeadMs`) and then the transparent still at the edit's own
rate. And the gap's fixed format is 44.1 kHz stereo, which became the mixer's
rate, so 48 kHz camera sound was stepped down under any overlay; the gap item
is built by hand - `EditedMediaItem.isGap` knows a gap by the media id
`androidx-media3-GapMediaItem`, which `addGap` sets and which is not public -
so it can carry a `SonicAudioProcessor` to the highest rate any sound in the
edit has (`ExportPlan.mixerSampleRate`, from `MediaCompat`'s reports). If a
later Media3 renames the id, the export fails on its first item at once
rather than quietly changing.

Photos and blanks are rendered with a silent AAC track; if that render fails,
they are rendered again picture-only, as before 1.11 (`StillClips.render`).

The sound tools (B9) lean on the order of a clip's audio processors and on
one fact about each side. `buildAudioProcessors` is the fold-down, then the
voice, then the gain, then the speed change, then `FadeProcessor`: the fade counts the
frames it writes and turns them into played milliseconds, so it has to sit
after `SpeedChangingAudioProcessor`, where the stream runs at the strip's
clock - before it, a song at half speed would fade over half the seconds the
wedge shows. The level is *after* the voice on purpose, and on both sides:
the saturating voices are `tanh`, so where the level goes is a question of
timbre and not only of loudness, and the preview can only apply it after -
a player's own volume sits past the sink's processors. Which means the
mixer's matrix carries no gain any more; it runs first only because it is
also the fold-down to stereo, and the voice should hear the channels the
file will have. Read against the 1.11.1 source: `ChannelMixingAudioProcessor
.onConfigure` returns `AudioFormat.NOT_SET` when its matrix `isIdentity()`,
so at unity gain on a stereo source the mixer makes itself inactive and
costs nothing, while a 6-channel source still folds down through it.
Both `FadeProcessor` and the preview's `GainProcessor` take
16-bit PCM only, as `VoiceProcessor` always has: Transformer decodes to
16-bit and `DefaultAudioSink` hands its processors 16-bit while float output
is off (the default). A gain past 1 clips to the sample range wherever it is
applied - `ChannelMixingAudioProcessor` constrains its sums, which is the
path a clip with no voice still takes, and `GainProcessor` clamps to the
short range, which is the path a clip with one takes in the file and every
sound takes in the preview - so a level that distorts in the file distorts
the same in the preview. The preview's boost
and voice are read from an `AtomicReference` per player on every buffer,
never a rebuilt sink; a Media3 that stops calling `queueInput` on an
inactive-looking processor would take the boost with it (`GainProcessor` is
always active; `FadeProcessor` is only built when a fade is set).

Animation, speed and transitions (B13) lean on four more things, each read in
the 1.11.1 bytecode. `SpeedChangingAudioProcessor.updateSpeed` calls
`setPitch(newSpeed)` as well as `setSpeed` unless the processor was made with
`shouldMaintainPitch`, so the one-argument constructor - which passes
`(false, false)` - retimes like a tape: the file's sound went up an octave at
double speed while the preview held it. The three-argument constructor is
`(SpeedProvider, areInputTimestampsAdjusted, shouldMaintainPitch)`, and
`VideoProcessor.buildAudioProcessors` passes `!clip.pitchFollowsSpeed` as the
third, so the pitch is held or let go by Media3's own walk of the segments
(a hand-rolled copy of it, `KeepPitchSpeedProcessor`, carried the held path
until that flag was found). The preview's `ExoPlayer` is asked for the same
choice through `PlaybackParameters` pitch, but never for more than
`DefaultAudioSink.MAX_PLAYBACK_SPEED` (8, and the same for pitch): above it the
sink held the sound at eight while the video renderer was asked for a hundred,
so `PreviewRules.playerRate` caps what a player is asked for and the preview of
a 100x clip runs at eight - the file is the clip's own rate. `FrameBlendEffect`
is a `GlShaderProgram` written from the interface, not a `BaseGlShaderProgram`:
it hands the chain several output frames per input, on textures it makes
itself with `GlUtil.createTexture` and `GlObjectsProvider.createBuffersForTexture`,
and signals `onReadyToAcceptInputFrame` only when every one of them is back -
what it leans on is that `ChainingGlShaderProgramListener` queues any number of
output frames and that the final program renders each at its own timestamp.
Every per-frame fade, an overlay's transition and the arrivals go through
`TransitionEffect` on the clip's own chain; on the one-sequence export, where
no compositor reads an alpha, it darkens towards black instead (`uOpaque`).
And `Effect.getDurationAfterEffectApplied` is left at its default on the blend,
since it changes no frame's time.

The export sheet's policy (B14) leans on four more, each read in the 1.11.1
bytecode rather than seen. `Composition.Builder.setHdrMode` set to
`HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` is the default now, and
`HDR_MODE_KEEP_HDR` only for "Keep HDR" and for a plain cut (where the stream
is copied); with KEEP_HDR and no encoder that takes HDR for the codec asked
for, `VideoEncoderWrapper.getRequestedOutputMimeTypeAndHdrModeAfterFallback`
falls back to converting rather than throwing, which is what lets the toggle
be offered without a failure path. `FrameDropEffect.createDefaultFrameDropEffect`
only drops frames; a rate above the footage's passes every frame through, and
a layered export gets its rate from the clock still instead
(`CompositionFactory.filler`'s frame rate, which is now the sheet's choice).
`EncoderUtil.getSupportedResolution` for the 4K frame of the edit's shape is
read as the encoder's ceiling (`EncoderCeiling.ceilingShortEdge`), asked per
codec since an HEVC encoder can stop elsewhere. And a main-track photo is
handed over as a JPEG image item (`StillClips.originalImage`) in a sequence
declared with sound - the same silence-filling the base rolls lean on, now on
the cuts-only sequence too, where the image may be the first item. With
"Keep HDR" on such a sequence that opens on an HLG clip, that JPEG is an SDR
bitmap fed into an HLG graph; whether Media3's bitmap input takes that is not
read anywhere, and it is the one B14 combination with no fallback path
written for it (Keep HDR off converts everything and is known to work).

Two things about the service that carries the export. `ServiceCompat
.startForeground` in androidx.core 1.13.1 masks the type against the set it
knows, which ends at Android 14's, so Android 15's media-processing type
reached the framework as NONE and was refused; `ExportService` calls
`Service.startForeground` itself from SDK 35. Raising androidx.core past a
release that knows `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` would let the
compat call back, but nothing depends on it. And the type is declared in the
manifest as `mediaProcessing|dataSync`, one for each side of 35.

**The muxer, and the four hundred kilobytes it reserves.** `Transformer`'s
default is `DefaultMuxer`, a pure delegate over `InAppMp4Muxer`, whose
`Mp4Writer.writeHeader` reserves `DEFAULT_MOOV_BOX_SIZE_BYTES = 400_000` bytes
of `free` space after the `ftyp` so the `moov` can be written at the front.
`maybeWriteMoovAtStart` writes the moov into that space and leaves the rest as
a `free` box, which is never trimmed - so every file carried 400 KB of padding,
69% of a measured three-second export. `media/CompactMuxer.kt` passes
`InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false)`, which sends
the moov after the mdat and truncates the file to what it used. The export, the
proxy copy and every rendered still use it - which is all three
`Transformer.Builder` sites there are. If a future Media3 trims the reserve
itself, this can go.

Read against the 1.11.1 sources in the Gradle cache (5 October), since every
file the app writes depends on it: `DefaultMuxer` holds an `InAppMp4Muxer`
and forwards `addTrack`, `writeSampleData`, `addMetadataEntry` and `close`
with nothing added, so swapping the factory changes nothing but the reserve.
`Mp4Writer.writeHeader` writes the 400 KB `free` box only under
`canWriteMoovAtStart`, which is that flag, and the moov-at-end path ends in
`muxerOutput.truncate(newMoovLocation + moovBytesNeeded)` - so the file really
is cut to what it used, rather than padded at the other end.
`InAppMp4Muxer.Factory.supportsWritingNegativeTimestampsInEditList()` returns
`true`, which is what the trim optimisation the export enables
(`experimentalSetTrimOptimizationEnabled`) requires of a muxer factory, so
that still applies. And `Transformer`'s own metrics collector tests for
`InAppMp4Muxer.Factory` before `DefaultMuxer.Factory`, so the muxer name it
reports on Android 15 is right rather than merely unchanged.

**The audio track is written whether or not anything is heard.** `AUDIO_AAC` is
set on every composed export, and Media3's `DefaultEncoderFactory
.DEFAULT_AUDIO_BITRATE` is `128 * 1024`, which the AAC encoder spends on
silence as readily as on sound - 133 frames of about 380 bytes in a
three-second export of a clip with none. So `ExportPresets.AUDIO_BITRATE_BPS`
is that same `128 * 1024`, and the estimate and the size target count the track
unconditionally. Not declaring audio when nothing has sound would be better and
is untried; `EditorUiState.estimatedExportBytes` says so.

The sections below were written against 1.4/1.5 and are kept for the history;
where they name a call that no longer exists (`ChannelMixingMatrix.create`,
`HslAdjustment` for saturation), that call is no longer used.

## Ranked by likelihood of needing a touch

### 1. `EditedMediaItemSequence` construction — `media/VideoProcessor.kt`

```kotlin
val videoSequence = EditedMediaItemSequence(ImmutableList.copyOf(videoItems))
```

The list constructor is the long-standing form and should be right for
Media3 1.4.1. Newer Media3 (1.5+) prefers a builder. If the constructor is
reported as private or missing, swap to:

```kotlin
val videoSequence = EditedMediaItemSequence.Builder().addItems(videoItems).build()
```

### 2. `Composition.Builder` argument shape — `media/VideoProcessor.kt`

```kotlin
Composition.Builder(ImmutableList.copyOf(sequences)).build()
```

If it wants varargs instead: `Composition.Builder(*sequences.toTypedArray())`.

### 3. Per-track volume — `media/AudioMixing.kt`

`ChannelMixingMatrix.create(n, n).scaleBy(v)` is the least battle-tested call
in the app. The whole file is 20 lines and returns a nullable processor, so if
it does not resolve, make `gain()` return `null` and everything else keeps
working — you lose independent camera/external volume, nothing more.

### 4. Saturation — `media/VideoProcessor.kt`

```kotlin
effects.add(HslAdjustment.Builder().adjustSaturation(state.saturation * 100f).build())
```

If `adjustSaturation` has a different name or range, delete these two lines and
hide the saturation slider. Brightness and contrast are separate and unaffected.

### 5. Caption positioning — `media/SquishTextOverlay.kt`

`OverlaySettings.Builder().setBackgroundFrameAnchor(...)` moved around across
Media3 minor versions. If it will not resolve, delete the `getOverlaySettings`
override entirely — captions then render centered instead of lower-third, which
is cosmetic. Only `getText` is required.

**Fifteen overlays to a pass, and that is not negotiable.**
`OverlayShaderProgram` binds each overlay as a sampler and its constructor is

```java
checkArgument(overlays.size() <= MAX_OVERLAY_SAMPLERS,   // 15
  "OverlayShaderProgram does not support more than 15 SDR overlays in the same instance.")
```

so one `OverlayEffect` holding sixteen fails the render at its first frame.
`ExportPlan.overlayGroups(count)` chunks them and `compositionEffects` adds one
`OverlayEffect` per chunk; the passes run in order and each pass draws its own
list in order, which is what keeps the drawing order. The limit counts
*samplers*, not moments, so two captions that never share a frame still take two
of them — there is no cleverer packing than `ceil(n / 15)`.

And **an overlay in an HDR graph is a different thing again**: `findHdrTypes`
treats a `TextOverlay` as text and anything else that is a `BitmapOverlay` —
which `SquishTextOverlay` is — as an Ultra HDR bitmap behind
`checkState(SDK_INT >= 34)`. So Keep HDR with any caption on the edit fails
outright below Android 14 and runs the gainmap path on a bitmap with no gainmap
above it. `EditorUiState.canKeepHdr` is false when `textOverlays` is not empty,
and the sheet reads the same answer the render does.

### 6. `SpeedChangeEffect` — `media/VideoProcessor.kt`

Deprecated in 1.11.1 and still the thing that retimes the *picture* (the sound
moved to `SpeedChangingAudioProcessor` in B13, for the pitch). It compiles with a
warning and works; the replacement is `Effects` with a speed-adjusted
presentation-time provider, which changes the clock every keyframe and every
timed effect is read against, so it is not a swap to make without a phone. Named
here so the warning is not read as rot: five warnings is the whole build, and
these two are it.

## Things that are NOT uncertain

Plain Android framework, no Media3 involved, so these either work or have real
bugs rather than API mismatches: the PCM decoder, waveform builder, sync
cross-correlation, frame-rate probe, thumbnail extraction, MediaStore gallery
publishing, the JSON history store, and every Compose screen.

## "Cannot access class androidx.compose.runtime.internal.ComposableFunction1"

If this appears, read past it - the errors under it are all consequences and
none of them are real:

```
Cannot access class 'androidx.compose.runtime.internal.ComposableFunction1'.
Argument type mismatch: actual '() -> Unit', expected 'ComposableFunction1<RowScope, Unit>'
Unresolved reference 'weight'
Unresolved reference 'padding'
```

`ComposableFunction1` is how the Compose compiler spells a `@Composable` lambda
that takes one argument - which is every `Row { }`, because its content slot is
`@Composable RowScope.() -> Unit`. When the compiler cannot find that class, no
`Row` can form its content type, so its trailing lambda "mismatches", so nothing
inside has a `RowScope` receiver, so `Modifier.weight` is unresolved. One missing
class, four kinds of error, none of them where the problem is.

It means the Compose **runtime** on the compile classpath is older than the
Compose **compiler** being used. Which side is stale:

```
./gradlew :app:dependencies --configuration debugCompileClasspath | grep compose
```

- If Gradle builds fine and only the IDE shows these, the IDE is analysing with
  its own bundled Kotlin plugin, which is newer than `kotlin` in
  `libs.versions.toml`. Raise the project to match it.
- If Gradle fails too, raise `composeBom` until the runtime is new enough for
  the `kotlin` version in use, and raise them together.

Whichever way: `materialIconsExtended` is pinned on purpose. It was deprecated at
Compose 1.7 and is not published past 1.7.8, so a BOM from 2025 onward leaves it
with no version and the build fails somewhere nobody edited.

## First run checklist

1. `./gradlew assembleDebug`
2. Install, open a clip, confirm the filmstrip renders at the right width
   (this was a real bug: pixels were being passed as `dp`).
3. Drag both trim handles twice each — the second drag used to compute from
   stale values.
4. Attach a separate audio track and press Auto-sync. On a clip with a clap or
   any sharp transient, expect a match above ~40% confidence within a second or
   two. A flat, noisy pair legitimately returns "no clear match" rather than a
   confident wrong answer.
5. Export, then check Movies/Squish in the gallery.

## MatrixTransformation (overlay placement)

`media/OverlayPlacementEffect.kt` is the one new Media3 interface in the app. It
exists because `ScaleAndRotateTransformation` can scale but cannot translate, so
the editor's "Across" and "Up / down" sliders moved a layer in the preview and
were then discarded at render time — every picture-in-picture came out centered.

If the signature differs in the version you resolve, the fallback is one line:
drop `OverlayPlacementEffect(...)` from `CompositionFactory.overlayEffects` and
put back

```kotlin
ScaleAndRotateTransformation.Builder().setScale(clip.scale, clip.scale).build()
```

Layers then render centered, exactly as they did before, and nothing else changes.

## Preview surfaces must stay TextureViews

`TimelinePreview` binds each player with `setVideoTextureView`, not a `PlayerView`.
This is not a style preference. A `SurfaceView` is punched through the window and
composited by the system, so it ignores view alpha, transforms and clipping — swap
these back to `PlayerView` (which defaults to `SurfaceView`) and every dissolve,
slide, wipe and picture-in-picture silently stops working while the code still
looks correct.

## The two custom shaders

`media/effects/ChromaKeyEffect.kt` and `media/effects/MaskEffect.kt` were the first
files in the app to touch Media3's shader API — `BaseGlShaderProgram`, `GlProgram`, `GlUtil`, `Size`,
`VideoFrameProcessingException` (the transition, fx, background, premultiply and frame-blend
effects followed the same shape). It was written against the actual 1.5.1 sources
rather than from memory, and its uniforms are cross-checked against the GLSL, but
it is still the largest new API surface in the project.

They are deliberately self-contained, and they share the same three call sites. If
those signatures differ in the version you resolve, for each of the two effects:

1. delete the effect file
2. delete its `?.let { add(...) }` line in `CompositionFactory.overlayEffects`
3. delete its `?.let { add(...) }` line in `VideoProcessor.editedClip`
4. delete its `?.let { add(...) }` line in `PreviewEngine.applySurfaceEffects`

Everything else builds exactly as before; the panels still store settings, they
simply stop being applied. The shader assets can stay where they are.

Both shaders write **straight** (non-premultiplied) alpha, matching the convention
Media3's own `AlphaScale` uses — which is the path already working in this app for
overlay opacity. If edges fringe on a device, that convention is the first thing to
check.

Note that `BaseGlShaderProgram` was called `SingleFrameGlShaderProgram` before
Media3 1.2 — if you ever move the module backwards, that is the rename to make.

### The one blur, in four files

A softening anywhere in the app is `uBlur`: a 3x3 ring of taps that far apart as
a **fraction of the frame**, averaged — so the look does not depend on the
resolution. Four files hold a copy, because they run in four places and not
because they do four things:

| file | what it softens |
|---|---|
| `assets/squish_fx_es2.glsl` | the effects library's own Blur, per surface in the preview and once on the finished frame in the export |
| `editor/CanvasFx.kt` (AGSL) | the same library pass drawn over the whole composed canvas, Android 13 and up |
| `assets/squish_transition_es2.glsl` | a **Blur** join on a base shot, in the file |
| `assets/squish_premultiply_es2.glsl` | the same join on an overlay, whose chain ends here rather than in the effects pass |

They must stay identical: a placed Blur, a defocus on a shot and a defocus on an
overlay are meant to look the same, on screen and in the file.
`tools/jvm/ShaderUniformChecks.kt` holds every shader and its Kotlin to the same
*uniforms*, which is a different question — it never looked at the loop, and
`tools/check_shaders.py` does not either. So `tools/jvm/ControlChecks.kt` reads
all four and holds that each takes a −1..1 box of nine taps, divides by nine,
leaves early at the same `uBlur <= 0.0001`, and **clamps every tap into the
frame** — at the tap in the three GLSL copies, inside `texel` in the AGSL one,
each checked where it keeps it. That last one is why the check exists:
`squish_fx_es2.glsl` did not clamp, so what a tap at the frame's edge returned
was the sampler's wrap mode rather than the shader's own decision.

If the ring is ever changed — nine taps undersample at the reach a transition
uses, which is the open question in `docs/DEVICE_FINDINGS.md` — it is changed in
all four at once, and `MAX_BLUR` is the library's own maximum (`0.012 × 1.75`,
the Blur effect at full strength with its knob right over), so a Defocus join
peaking at `BLUR_PEAK` sits well inside it.

## On-device speech recognition

`media/audio/Transcriber.kt` reaches into a rarely-used corner of the framework:
feeding a `SpeechRecognizer` from a pipe rather than the microphone, via the
`android.speech.extra.AUDIO_SOURCE` family of extras (Android 12+), with the
recognizer itself created by `createOnDeviceSpeechRecognizer` (Android 13+).

Those extras are written as their **documented string names** rather than the
`RecognizerIntent` constants. They mean exactly the same thing to the recognizer,
and a string literal cannot fail to resolve against a platform version — which is
worth having for the one part of the app reaching somewhere this obscure.

It is best-effort by design. Every failure path — no on-device model, an
unsupported language, a pipe that will not open, a recognizer error — returns null,
and the caller turns that into an empty caption card with correct timings. Nothing
about captioning breaks if this whole file never succeeds on a given device.

**It never falls back to `createSpeechRecognizer`**, which is free to send audio to
a server. That would break the app's central promise, so the on-device path is the
only path.

## Checking work without an Android SDK

The development sandbox has no Android SDK and cannot reach Google's Maven, so
`kotlinc` there reports every Compose and Media3 reference as unresolved. A real
mistake — a renamed function, a missing import — looks identical to that noise,
which is how three calls to a renamed `mutateVideoTrack` and a missing
`Dispatchers` import both survived several apparently clean runs.

Two scripts separate signal from noise. Neither replaces building the project;
they catch the class of error that a sandbox build cannot.

```
kotlinc -nowarn -d /dev/null $(find app/src/main/java -name '*.kt') > log 2>&1
python3 tools/check_unresolved.py log        # renamed / misspelled references
python3 tools/check_calls.py log             # a signature changed, a caller left behind
python3 tools/check_modifier_imports.py      # Modifier extensions used unimported
python3 tools/check_nesting.py               # a declaration swallowed by a stray brace
python3 tools/check_shaders.py               # a shader and its Kotlin disagreeing about uniforms
python3 tools/check_dependencies.py          # a library imported but never declared,
                                             #   and a Media3 file that has not opted in

The spellchecker's vocabulary lives in `config/dictionaries/` — see the README
there if the IDE is still reporting playhead and luma as typos.
KOTLINC=<path>/kotlinc tools/jvm/run.sh      # runs the speed and grade maths for real
```

`check_unresolved.py` drops member accesses, names the file imports and names the
file declares, leaving bare references to things that do not exist. Known
unresolvable names live in `tools/unresolved_baseline.txt`; regenerate it with
`--write-baseline` after a dependency change.

`check_calls.py` reads the three arity errors out of the same log: an argument
missing, an argument too many, a name that is not a parameter. It exists because
adding a parameter and missing one of the callers is invisible to everything else
here — the file parses, every name resolves, the imports are all present — and
because the error was already in the log when `startProxy` gained a `durationMs`
and the recovery path did not. It was being filtered out with the thousands of
unresolved-reference lines, on the assumption that everything in that log was
noise. Argument *type* mismatches were tried here too and are pure cascade, so
they are not looked at.

`check_modifier_imports.py` exists because a missing extension import shows up as
an unresolved *member*, which the first script deliberately ignores. It learns each
extension's expected import from the rest of the codebase and inspects only chains
rooted at `Modifier`, so ordinary properties like `list.size` cannot be mistaken
for one.

The pure-Kotlin files — the look catalog, the keyframe and tracking maths, the SRT
parser — have no Android dependencies, so they compile and run for real. Several
bugs were caught that way: a negated motion estimate, a crop budget measured
against the wrong axis, an SRT index swallowed into the previous caption.
