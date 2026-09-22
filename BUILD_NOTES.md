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
override entirely — captions then render centred instead of lower-third, which
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
were then discarded at render time — every picture-in-picture came out centred.

If the signature differs in the version you resolve, the fallback is one line:
drop `OverlayPlacementEffect(...)` from `CompositionFactory.overlayEffects` and
put back

```kotlin
ScaleAndRotateTransformation.Builder().setScale(clip.scale, clip.scale).build()
```

Layers then render centred, exactly as they did before, and nothing else changes.

## Preview surfaces must stay TextureViews

`TimelinePreview` binds each player with `setVideoTextureView`, not a `PlayerView`.
This is not a style preference. A `SurfaceView` is punched through the window and
composited by the system, so it ignores view alpha, transforms and clipping — swap
these back to `PlayerView` (which defaults to `SurfaceView`) and every dissolve,
slide, wipe and picture-in-picture silently stops working while the code still
looks correct.

## The chroma key shader

`media/effects/ChromaKeyEffect.kt` is the only file in the app that touches
Media3's shader API — `BaseGlShaderProgram`, `GlProgram`, `GlUtil`, `Size`,
`VideoFrameProcessingException`. It was written against the actual 1.5.1 sources
rather than from memory, and its uniforms are cross-checked against the GLSL, but
it is still the largest new API surface in the project.

It is deliberately self-contained. If those signatures differ in the version you
resolve:

1. delete `media/effects/ChromaKeyEffect.kt`
2. delete the two `clip.chromaKey?.let { add(ChromaKeyEffect(it)) }` lines — one in
   `CompositionFactory.overlayEffects`, one in `VideoProcessor.editedClip`
3. delete the `chroma?.let { add(ChromaKeyEffect(it)) }` line in
   `PreviewEngine.applySurfaceEffects`

Everything else builds exactly as before; the panel still stores settings, they
simply stop being applied. The shader assets can stay where they are.

Note that `BaseGlShaderProgram` was called `SingleFrameGlShaderProgram` before
Media3 1.2 — if you ever move the module backwards, that is the rename to make.
