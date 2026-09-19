# Squish

A no-watermark, no-paywall video trimmer, compressor and converter for
Android — built to be simple enough for the WhatsApp-video-too-big problem
and capable enough for a real edit: multi-clip merge, captions, background
music, crop/speed, color adjustments.

This is a single, all-in-one v1.0.0 — everything below shipped together
rather than being staged across versions. Trim, compress and convert are
the sturdy core; the advanced features (color grade, captions, background
music mixing) are real, wired-up code, flagged below by confidence level so
you know exactly what to sanity-check first.

## Stack

- Kotlin + Jetpack Compose (Material 3, dark theme, single Activity)
- [Media3 Transformer](https://developer.android.com/media/media3/transformer)
  for all video processing (trim, compress, crop, speed, color, text
  overlay, background-music mixing, multi-clip concatenation)
- Media3 ExoPlayer for the live preview player
- Navigation Compose for screen flow
- No Room, no Hilt, no DataStore, no image-loading library — history is a
  small JSON file, DI is a couple of constructor calls. Kept the dependency
  surface minimal on purpose so there's less that can go wrong on first
  build.

## Feature map

**Core (high confidence — standard, long-stable Media3/Compose/Android
APIs):**
- Animated splash with a bouncy "squish" logo intro (`splash/SplashScreen.kt`)
- Video picker via the system Photo Picker (no storage permission needed)
- Trim with a real draggable dual-handle filmstrip bound to actual duration
- Quality presets (Small/Medium/High/Original) and "fit to a size"
  (16/25/50 MB, back-computed bitrate)
- Mute, rotate-fix, crop to 9:16 / 1:1 / 16:9, speed 0.5x–2x
- Live before/after size estimate
- Export with a live preview player, then a before/after results screen
- Direct share to WhatsApp / Instagram / Email via `FileProvider`
- Local export history (JSON-backed, on-device only — see Settings copy)
- Multi-clip merge queue (concatenates clips into one export)

**Advanced (real, implemented — verify against your Media3 version on
first build, see below):**
- Brightness / contrast / saturation sliders
- Burned-in text captions with timing
- Background music track, mixed under the video's own audio

## Before you open this in Android Studio

This was built in a sandboxed session whose network proxy blocks
`dl.google.com` (Google's Maven repo), which is where the Android Gradle
Plugin and every AndroidX/Media3 artifact live. **I could not run a Gradle
build here to compile-check this project.** Everything was written
carefully against documented Media3/Compose APIs, and a manual
brace/paren balance pass found no gross syntax errors, but a first real
build is still the first real build.

Lowest-to-highest risk if something doesn't compile on your first sync:

1. **Essentially zero risk:** Compose UI, navigation, theming, the JSON
   history store, ExoPlayer preview, `MediaMetadataRetriever` thumbnails,
   FileProvider sharing, Photo Picker.
2. **Low risk, well-established Transformer APIs:** clipping, mute,
   `Presentation` (resolution + aspect crop), `ScaleAndRotateTransformation`,
   `SpeedChangeEffect` + `SonicAudioProcessor`, `Contrast`/`RgbAdjustment`,
   custom bitrate via `DefaultEncoderFactory`.
3. **Worth a first-build check:** `HslAdjustment.adjustSaturation(...)`'s
   exact signature, the `TextOverlay`/`OverlaySettings` anchor math in
   `media/SquishTextOverlay.kt`, and `Composition`'s multi-sequence audio
   mixing behavior for the background-music track
   (`media/VideoProcessor.kt`). These are real Media3 1.4.x features, just
   ones whose exact method shapes shifted across minor releases — if
   `./gradlew assembleDebug` flags one, paste me the error and I'll fix it
   on the spot; it'll be a small, local signature fix, not a design change.

Also not yet built (intentionally, not oversights — the honest next
tier, not silently dropped): reverse clip (needs frame-by-frame
re-encoding, not a Transformer flag), export progress percentage (v1
shows an indeterminate spinner — Transformer's `getProgress` polling API
is a fast follow), background/queued export via WorkManager, batch
export of multiple files at once.

## Build

```
./gradlew assembleDebug
```

Requires the Android SDK (compileSdk 35) and network access to
`google()`/`mavenCentral()`.

## Brand

Coral `#FF6B4A` primary, teal `#33E0C2` for "your win" moments (savings,
success), dark `#0E0E12` background, yellow/purple as supporting accents.
Type currently falls back to the system sans family
(`ui/theme/Type.kt`) — real Sora/Manrope wiring is a placeholder swap,
noted in that file, waiting on the real brand intro design you mentioned.
