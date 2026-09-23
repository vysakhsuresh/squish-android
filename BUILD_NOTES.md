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

## Things that are NOT uncertain

Plain Android framework, no Media3 involved, so these either work or have real
bugs rather than API mismatches: the PCM decoder, waveform builder, sync
cross-correlation, frame-rate probe, thumbnail extraction, MediaStore gallery
publishing, the JSON history store, and every Compose screen.

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

`media/effects/ChromaKeyEffect.kt` and `media/effects/MaskEffect.kt` are the only
files in the app that touch Media3's shader API — `BaseGlShaderProgram`, `GlProgram`, `GlUtil`, `Size`,
`VideoFrameProcessingException`. It was written against the actual 1.5.1 sources
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
python3 tools/check_modifier_imports.py      # Modifier extensions used unimported
python3 tools/check_nesting.py               # a declaration swallowed by a stray brace
python3 tools/check_shaders.py               # a shader and its Kotlin disagreeing about uniforms
KOTLINC=<path>/kotlinc tools/jvm/run.sh      # runs the speed and grade maths for real
```

`check_unresolved.py` drops member accesses, names the file imports and names the
file declares, leaving bare references to things that do not exist. Known
unresolvable names live in `tools/unresolved_baseline.txt`; regenerate it with
`--write-baseline` after a dependency change.

`check_modifier_imports.py` exists because a missing extension import shows up as
an unresolved *member*, which the first script deliberately ignores. It learns each
extension's expected import from the rest of the codebase and inspects only chains
rooted at `Modifier`, so ordinary properties like `list.size` cannot be mistaken
for one.

The pure-Kotlin files — the look catalog, the keyframe and tracking maths, the SRT
parser — have no Android dependencies, so they compile and run for real. Several
bugs were caught that way: a negated motion estimate, a crop budget measured
against the wrong axis, an SRT index swallowed into the previous caption.
