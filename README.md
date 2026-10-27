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
  half of captioning that actually takes an afternoon — then transcribes it on the
  phone, with Whisper (a one-time download, its size said first) or the phone's
  own on-device recognition. Nothing is uploaded. Import and export .srt to use
  any transcript you like.
- **Shape masks.** Rectangle, ellipse, linear, mirror, heart, star, filmstrip and
  split, each feathered,
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
- **Quick tools** — Squeeze, Snip, Rip and Stitch. One job each, one tap
  from home, and every one also lives inside the editor. Each quick tool offers
  "open in the full editor" so simple work can grow up without starting over.

**The editor is laid out like the editors people already know.** A header with
the project's name, undo, redo and Export; the picture; the timeline, always on
screen; and one row of tools that changes with the selection - sound, text,
stickers, overlays and effects to add with nothing selected, the clip's own tools
(split, speed, volume, animation, mask, cutout...) with a clip selected. A tool
opens as a sheet in the toolbar's place, and the timeline stays above it.

## Everything else

**Looks, effects and transitions.** 49 graded looks with a strength
dial, curves, LUT import and thirteen adjustment sliders, applied live in the
preview by the same shaders that render the file. 61 timed effects on seven
shelves and 51 transitions on seven tabs, each previewed on a moving tile.
Blend modes on photo and video overlays.

**Templates.** Forty-two one-tap styles (a look, a frame, a title and effects),
twenty slot templates that cut your own shots to a Squish song's beats, and
AutoCut - every shot once, cut to a song at one of three paces.

**Sound.** 37 original songs and 76 sound effects made on the phone, music from
the phone, a voiceover recorder, voice effects, Reduce noise, Voice only and
Remove voice (a small speech model on the phone), EQ presets, a compressor and
"Bring to -14 LUFS", fades, ducking under speech and volume keyframes.

**Text.** Styled captions and stickers, auto-captions by Whisper on the phone
(a one-time download), editing by transcript, .srt in and out, read-aloud.

**Camera.** Squish's own camera with a 3-2-1 count-in, a 9:16 guide and a
teleprompter.

**Speed.** 0.1x to 100x, curves and ramps, pitch held or following the speed,
frame blending and motion-compensated slow motion.

**Export.** Original size up to 4K where the phone's encoder allows, H.264 or
HEVC, a frame rate and a quality step, fit to a size (16, 25 or 50 MB), Keep
HDR, sound only, GIF. A shot left as it came in is copied rather than encoded.
Straight to the gallery (Movies/Squish), with direct share.

**Quick tools.** Squeeze (smaller), Snip (cut), Rip (sound out) and Stitch
(join), one job each from the dashboard, each able to open in the full editor.

Every format FFmpeg reads plays, cuts and exports, through Squish's own build of
it behind the phone's decoders.

No watermark. No paywalled resolution. No account.

## Three promises, implemented

These are the reasons to pick Squish over the big names, and they are code, not
marketing copy. [ARCHITECTURE.md](ARCHITECTURE.md) has the detail.

**Your footage never leaves the phone.** Nothing in Squish uploads a video, a
photo, a sound or a project. It does hold the internet permission, for online
features that are off until you turn them on and that only fetch: free music,
stock footage and fonts, caption translation, and the speech models for
captions (each says its size before it downloads). Every request goes through
one gate (`online/Online.kt`) that refuses while the switch in Settings is off.
No analytics SDK is included, and there is no account.

**No edit is ever lost.** The whole timeline is written to disk every 1.5 seconds,
atomically — a temp file is `fsync`ed, then `rename`d over the live document, so
the saved project is always a complete version — never a truncated one, however
abruptly Android kills the app. A ten-minute snapshot is kept as an earlier
version, and a deleted project goes to a bin first.

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
ExoPlayer for preview · Squish's own FFmpeg build behind the phone's decoders ·
MediaPipe and LiteRT (TensorFlow Lite) for the on-device models · CameraX for the camera.

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

Honest list, not silent omissions: copying stretches of a several-shot edit
without re-encoding them (only an edit of one untouched shot is copied today),
more than three rows of video overlays playing at once, downloadable read-aloud
voices, and a teleprompter for the voiceover recorder. `docs/EDGE_PLAN.md` is
the working list, with what has been seen on a phone and what has not.

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
