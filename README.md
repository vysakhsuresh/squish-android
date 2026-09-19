# Squish

A no-watermark, no-paywall video editor for Android, built around the thing
mobile editors are worst at: **precision**.

Trim, compress and convert are the everyday core. The reason to pick Squish
over the dozen apps that already do that is the sync work — lining separately
recorded audio up with picture, and putting a cut exactly on the frame you
meant.

## The headline feature: automatic dual-system sound sync

Shoot on a phone, record sound on a separate mic, and aligning the two is
normally a miserable manual nudge-and-listen loop. Squish does what desktop
tools do:

- Attach the separate track (audio file, or another video whose sound you want)
- Squish decodes both, builds loudness envelopes, and **cross-correlates them to
  find the alignment automatically** — usually under a second, entirely on-device
- The result lands as a millisecond offset with a confidence score, and if the
  match is weak it says so rather than confidently lying
- Fine-tune with **±1 frame / ±10 ms** nudges, or drag the audio lane by hand
- Preview plays video and external audio together at the current offset, with a
  drift watchdog keeping the two players locked

Amplitude envelopes are what make this work across a phone mic and a proper
recorder — the timbre is wildly different, the loudness shape is not.

## Precision editing

- **Frame-accurate everything.** Real frame rate is read off the video track, so
  nudges are correct on 24, 30 and 60 fps clips instead of assuming 30.
- **In/out points with frame nudge and "set to playhead"**, plus timecode that
  shows milliseconds and frame number, not a rounded `0:03`.
- **Markers** you can drop at the playhead, with trim handles snapping to them.
- **Waveform lanes** under the filmstrip — cut between words or on the beat by
  looking, not guessing.
- **Exact seeking** in preview (`SeekParameters.EXACT`), so scrubbing lands on
  the frame rather than the nearest keyframe.

## Everything else

Trim · compress by preset or to a target size (16/25/50 MB) · crop to 9:16, 1:1
or 16:9 · speed 0.5–2x with pitch preserved · mute camera audio independently of
any added track · per-track volume · rotate · brightness/contrast/saturation ·
burned-in captions · multi-clip merge · export straight to the gallery
(Movies/Squish) · direct share to WhatsApp, Instagram, Email · on-device history.

No cloud upload. No watermark. No paywalled resolution.

## Stack

Kotlin · Jetpack Compose (Material 3) · Media3 Transformer for export · Media3
ExoPlayer for preview · MediaCodec/MediaExtractor for audio analysis.

Deliberately small dependency surface: no Room, no Hilt, no DataStore, no image
loader. History is a JSON file; DI is a constructor call.

## Open in Android Studio

1. **File → New → Project from Version Control**
2. URL: `https://github.com/vysakhsuresh/squish-android`, pick a local folder, **Clone**
3. Studio detects the Gradle project and syncs on its own. The first sync
   downloads AGP, Compose and Media3 — expect several minutes and roughly a
   gigabyte, on a connection that can reach `dl.google.com`.
4. If prompted for a missing SDK, accept: this needs **Android SDK 35**
   (compileSdk) and a **JDK 17+** (Studio bundles one — Settings → Build →
   Build Tools → Gradle → Gradle JDK).
5. Press **Run** with a device selected.

Already cloned it? **File → Open** and select the `squish-android` folder
itself — the one with `settings.gradle.kts` in it, not a parent or a subfolder.

**Test on a physical phone, not the emulator.** The whole app is hardware video
encoding; emulator encoders are slow, and some system images fail on H.264
encode outright. A real device also gives you a camera and a mic, which is the
only way to actually try dual-system sync.

## Build

```
./gradlew assembleDebug
```

compileSdk 35, minSdk 29, Android SDK plus `google()`/`mavenCentral()` access.

minSdk is 29 so gallery export can use scoped-storage MediaStore with no legacy
`WRITE_EXTERNAL_STORAGE` path — one storage code path instead of two, and no
runtime storage permission at all.

**Before your first build, read [BUILD_NOTES.md](BUILD_NOTES.md).** This project
has never been through a compiler (the build sandbox has no Android SDK and
cannot reach Google's Maven), so that file lists the handful of Media3 calls
worth checking and their one-line fallbacks.

## Not built yet

Honest list, not silent omissions: reverse clip (needs frame-by-frame
re-encoding, not a Transformer flag), numeric export progress (currently an
indeterminate spinner), background/queued export via WorkManager, batch export,
audio fades and ducking, and keyframed effects.

## Brand

Coral `#FF6B4A` primary, teal `#33E0C2` for audio and for wins (savings,
matches, success), yellow for markers and warnings, dark `#0E0E12` ground. Type
falls back to the system sans (`ui/theme/Type.kt`) until the real brand faces
are wired in.
