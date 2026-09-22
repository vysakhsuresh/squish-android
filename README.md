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

- Attach as many tracks as you need (audio files, or other videos whose sound you
  want) — they can overlap, and each one syncs independently
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
- **Face and plate hiding.** Track a face, pin a pixelate or blur to it, and it
  follows. Leaves the frame intact and destroys only what is inside the shape —
  and the strength range is set by what it takes to actually make a face
  unrecognisable, not by what looks blurry in a thumbnail.
- **Motion tracking.** Tap what you want followed, and pin a caption or a layer to
  it. Matches on pattern rather than brightness, so it holds through a lighting
  change, and it tells you how much of the shot it actually held on for instead of
  quietly drifting onto the background.
- **Stabilization.** Measures how the camera actually moved, smooths that path and
  pushes each frame back onto it — so a deliberate pan survives and only the jitter
  comes out. Crops in by exactly as much as the correction needs and no more, and
  composes with your own keyframes rather than overwriting them.
- **Auto-captions.** Finds every line of speech and times it to the frame — the
  half of captioning that actually takes an afternoon — then transcribes it where
  the device has on-device speech recognition. Nothing is uploaded, so if your
  phone cannot do it locally, it does not happen; you get perfectly timed cards to
  type into instead. Import and export .srt to use any transcript you like.
- **Shape masks.** Rectangle, ellipse, linear and mirror, each feathered,
  rotatable and invertible. Rotation is aspect-corrected, so a turned rectangle
  stays a rectangle instead of shearing. Composes with the chroma key rather than
  replacing it, and applied by the preview's own shader so the sliders move the
  finished result.
- **Chroma key with spill suppression.** Tap your actual screen in the frame to
  sample it — screen paint and cloth vary far too much for a canned color to
  work. Keys in chroma only, so it holds through uneven lighting, and pulls the
  screen's color back out of hair and shoulders. Live in the preview.
- **Keyframed motion.** Push in, drift across, settle from a tilt — scale,
  position and rotation animate over a clip with smooth, linear or hold easing.
  Six one-tap presets to start from, auto-keying once a clip is animated, and
  keyframe markers on the strip so an animated shot reads as animated.
- **Transitions and layers preview live.** A dissolve dissolves, a wipe wipes, a
  picture-in-picture sits where you put it — on screen, before you render, because
  the preview composites A/B roll the same way the exporter does.
- **The preview plays the timeline, not a playlist.** Move a clip and it plays
  where you put it; leave a gap and the picture goes black there, exactly as the
  exported file will. The playhead is derived from the picture's own clock, so it
  can never disagree with the frame on screen. Drag the ruler to scrub, tap the
  picture to play.

## Layout

**Dashboard, not a timeline.** The home screen is the full set of doors, so a
person who just needs a smaller file never meets an editor:

- **Video editor** — the full suite
- **Quick tools** — Compress, Trim, Extract audio, Merge. One job each, one tap
  from home, and every one also lives inside the editor. Each quick tool offers
  "open in the full editor" so simple work can grow up without starting over.

**The editor shows one tool at a time.** Preview and timeline stay pinned; a
bottom rail switches between Trim, Crop, Speed, Audio, Text, Color and Export.
Everything used to be stacked in a single endless scroll, which made even trim
and crop hard to find.

## Everything else

**16 graded looks** in three families — Essentials, Film, Mood — each with a
strength dial, applied live in the preview by the same code that renders them, so
what you see is what gets written. The filter chips are painted by running each
grade over a reference ramp with the identical arithmetic the shaders use, so a
chip can never advertise something the look does not do.

Trim · compress by preset or to a target size (16/25/50 MB) · crop to 9:16, 1:1
or 16:9 · speed 0.5–2x with pitch preserved · mute camera audio independently of
any added track · per-track volume · rotate · brightness/contrast/saturation ·
burned-in captions · multi-clip merge · export straight to the gallery
(Movies/Squish) · direct share to WhatsApp, Instagram, Email · on-device history.

No cloud upload. No watermark. No paywalled resolution.

## Three promises, implemented

These are the reasons to pick Squish over the big names, and they are code, not
marketing copy. [ARCHITECTURE.md](ARCHITECTURE.md) has the detail.

**Nothing leaves your device.** No network permission is requested, no SDK phones
home, there is no account and no upload step. Every frame is decoded, composed
and encoded here. Verify it from the manifest.

**No edit is ever lost.** The whole timeline is written to disk every 1.5 seconds,
atomically — a temp file is `fsync`ed, then `rename`d over the live document, so
the saved project is always a complete version — never a truncated one, however
abruptly Android kills the app. Reopen and the edit is offered back, with the
previous version kept alongside as a second parachute.

**Failures are explained.** Every error carries what happened, why, and the one
action that fixes it — "This phone can't decode that clip (HEVC)", not "Export
failed". Disk space, permissions, missing audio and impossible resolutions are
checked *before* encoding starts, so a doomed export fails in a second instead
of two minutes.

**And heavy footage stays smooth.** Anything above 1080p gets a 540p stand-in
built in the background while you keep working. The preview plays the proxy; the
export pipeline never sees it and always reads the camera original, so nothing
about the finished file is degraded.

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

**[BUILD_NOTES.md](BUILD_NOTES.md)** lists the Media3 calls worth knowing about
and their one-line fallbacks. Note that the development sandbox has no Android
SDK and cannot reach Google's Maven, so changes made there are parse-checked
against the Kotlin compiler but compiled for the first time on your machine.

## Not built yet

Honest list, not silent omissions: hand-keyframed mask shapes,
true 3D LUTs, per-clip looks, keyframed opacity and color (the transform hook
Media3 gives does not cover those), and a bundled speech model so transcription
works on every device rather than only those with on-device recognition — all scoped with
real timescales in [ARCHITECTURE.md](ARCHITECTURE.md). Plus reverse clip (needs
frame-by-frame re-encoding, not a Transformer flag), numeric export progress
(currently an indeterminate spinner), background/queued export via WorkManager,
batch export, and audio fades and ducking.

## Brand

The mark is a **play triangle cut clean through** — play for video, the cut
because this is an editor and not a player. Both halves are real geometry, rounded
on every corner, and it holds up under a circle mask down to 36px.

It is drawn, never loaded: the launcher icon, the launch animation and the in-app
mark are all generated from the same 108-unit path, so they cannot drift apart,
and there is not a single bitmap in the app.

Color is a corner-to-corner sweep — cyan `#3DE0C0` → blue `#4A7BFF` → violet
`#8B5CF6` → magenta `#F0477F` — laid across the *visible* window of the icon
rather than the full canvas, because a launcher only ever shows the middle two
thirds and a sweep across the whole square has both its ends masked off. The app
itself sits on deep navy `#0A0E2D`, and the same hues carry meaning in the editor:
violet is video, cyan is audio, amber is text and markers, magenta is destructive.

Type is **Space Grotesk**, inherited from Layerlink — the same four weight files
(400/500/600/700), bundled rather than fetched so bold is the real bold face.
One family throughout; hierarchy comes from weight and size, which keeps the
interface quiet enough to put a video in front of. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the font license.

**One entrance, not two.** The launch animation is the system splash: the mark
squashes into place on the brand ground while the dashboard loads behind it, and
that is the only intro. The app previously played this *and* a second in-app
splash showing the same artwork, which is why a cold start looked like it was
introducing itself twice.
