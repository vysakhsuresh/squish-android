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
