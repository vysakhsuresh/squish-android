# InShot parity: work order

Written 24 October 2026 from the cloud sandbox, for a desktop CLI session with
the phone attached. **A session that opens this file starts at "Session start"
below and works the checklist from the first item not marked `[x]`.** The list
is written so any session can resume it cold: everything a step needs is here
or in `CLAUDE.md`, which is read first and wins wherever the two disagree.

## Session start (every time, including a resume)

1. `git status --short`. If it shows changes this session did not make, **stop
   and tell the owner** - do not stash, discard or commit someone else's work.
2. `git checkout main && git pull --ff-only origin main`.
3. Read `CLAUDE.md` in full: the desktop section (JDK 21, the moto, tap safety,
   `uiautomator` caveats, never editing during a build) applies to everything
   below and is not repeated here.
4. Baseline before changing anything: `./gradlew clean assembleDebug`, install,
   confirm the phone is free (call state 0, Squish resumed), `adb logcat -G 16M`,
   open the editor on a clip, play, export a 5 s clip. If the baseline fails,
   fixing that is item zero - nothing below is worth building on a broken main.
5. Find the first item below not marked `[x]` and carry on from its first
   unfinished step.

## Checklist

Status marks: `[ ]` not started · `[~]` built, JVM-checked, not yet seen ·
`[x]` seen working on the phone **and** in an exported file. Each mark carries
the commit and one line of evidence, e.g. `[x] a1b2c3d - Mp4Probe: 0.25x span
written at 30.0 fps, distinct frames`. A `[~]` is not "done".

- [ ] **P1** Smooth slow-mo (motion-compensated in-between frames)
- [ ] **P2** Noise reduction under speech (spectral, not just the gate)
- [ ] **P3** Geometric transitions
- [ ] **P4** Bilingual captions
- [ ] **P5** Gradient and pattern canvas backgrounds
- [ ] **P6** Animated stickers (GIF)
- [ ] **P7** A real sound-effects library
- [ ] **P8** Blend modes on video overlays
- [ ] **P9** README and Settings say what the app actually does
- [ ] **R** Extended regression sweep

## How this list was made, and what it is not

InShot's own listing and recent release notes, read via search on 24 October
(the store pages themselves would not load from the sandbox), checked
against `docs/COMPETITORS.md` (3-4 October) and then **against this
repository's code**, item by item - several things the competitor doc had as
gaps are built, and two things it had as built turned out partial. Nothing here
was seen by driving InShot. Before spending a day matching an InShot feature,
spend ten minutes using it in InShot on the phone, if it is installed.

**Already at parity - do not rebuild:** trim/split/merge, speed curves,
reverse, freeze, rotate/mirror, crop and ratios (4:5, 3:4, 2.35:1...), canvas
blur/colour/image, 49 looks, 21 effects, 27 transitions, keyframes, In/Out/
Loop animation for clips and text, text bubbles, stickers (static), shapes,
PiP and layers, chroma key, masks, AI cut-out, smart tracking, stabilisation,
smooth skin, auto captions, translation, text-to-speech, voiceover, voice effects, beat
sync, ducking, templates (42), split screen, photos as clips, Ken Burns motion
presets (11), GIF export, 4K/60.

**Not a gap:** Retouch. InShot's Retouch sends the user to a separate app
(Peachy); it is not an in-editor feature, and the in-editor part it does
have - smoothing skin - Squish has as the Adjust slider "Smooth skin".

**DECIDE - do not build without the owner's go-ahead:**
- GIPHY search. Needs an API key, attribution, and sends the search words to
  GIPHY - a request with the user's words in it, which is more than the
  "fetch only" rule in `COMPETITORS.md` D1 has covered so far.
- Photo editor and collage (two of InShot's three home tabs). Skipped on
  purpose in `COMPETITORS.md` 2.3; a second app.
- AI style effects and AR/body effects. Mostly server-side; skipped in 2.3.

---

## P1 Smooth slow-mo

**InShot:** "Smooth slow-mo", on its listing as a headline AI feature. **The
owner asked for this directly** after slowed clips came out stepped.

**Squish today:** `media/video/FrameBlendEffect.kt` already *generates* frames
between source frames inside the effect chain (`FrameBlendPlan` places them,
`tools/jvm/FrameBlendChecks.kt` executes that), but each one is a cross-fade
of its two neighbours - motion blur, not motion. `media/video/MotionEstimator`
already measures motion (block matching, used by the stabiliser and tracker).

**Build:** keep the plan and the frame generation; replace the cross-fade with
motion-compensated interpolation. Per pair of source frames: estimate a coarse
motion field (blocks of 16-32 px on downscaled luma; reuse `MotionEstimator`
rather than writing a second one), smooth it (median over neighbours, so one
bad block does not tear), upload it as a small texture, and for an in-between
frame at mix `t` sample the earlier frame displaced by `t·v` and the later
frame by `-(1-t)·v`, then blend by `t`. Where the two warped samples disagree
badly (occlusion), fall back toward the plain cross-fade for that pixel rather
than smearing. One more shader in `assets/`; its uniforms must satisfy
`ShaderUniformChecks`.

**Where it shows:** the Speed sheet's existing smoothness line and toggle. Say
which it is doing - "Smooth (motion)" vs "Blend" - and keep blend as the
fallback for footage the estimator cannot follow (the estimator reports how
much of the frame it held).

**JVM check first** (`tools/jvm/FlowInterpChecks.kt`, on both runners -
`RunnerChecks` fails otherwise): a synthetic square moving 12 px/frame on a
textured background; the interpolated frame at t=0.5 must place the square at
+6 px within 1 px, and must score closer to the true midpoint frame than the
cross-fade does (sum of absolute differences). A static scene must come out
unchanged. A cut between the two frames (no correspondence) must fall back to
the cross-fade, not invent motion.

**Acceptance on the phone:** record or pick a 30 fps clip with steady motion
(a hand moving across, a car passing). 0.25x, Smooth on, export. `Mp4Probe`:
the slowed span writes at the project rate with no duplicated timestamps.
`tools/desktop/frames.ps1` + `contact_sheet.ps1` over one second of it: the
moving subject advances in even steps across consecutive frames, no doubled
edges (that is blending), no torn blocks. Preview and file agree by eye.
Release build (`assemblePerf`): `dumpsys gfxinfo` while playing the slowed
clip - record janky-frame percentage before and after.

**Time box:** if motion interpolation cannot meet the acceptance after three
honest approaches, ship it off by default, label it truthfully, record what
failed in `docs/DEVICE_FINDINGS.md`, and move on to P2. Blending must keep
working either way.

## P2 Noise reduction under speech

**InShot/CapCut:** "Reduce noise" for clip and track audio.

**Squish today:** `media/audio/VoiceCleaner.kt` ("Enhance voice") high-passes
rumble and gates hiss *between* words. Noise *under* the voice - a fan, traffic,
AC hum - goes straight through.

**Build:** a spectral suppressor beside it, not replacing it: STFT (reuse
`media/audio/Fft.kt`), a per-bin noise profile learned from the quietest frames
(minimum statistics, updated slowly), a Wiener-style gain per bin with a floor
so it never mutes, and gain smoothing across time and frequency so it does not
"warble" (musical noise). Overlap-add back. As a Media3 `BaseAudioProcessor`
like the voice effects, so the preview and the export run the same code. One
"Reduce noise" switch plus a strength on the clip's sound tool and on audio
tracks, next to Enhance voice.

**JVM check first** (`NoiseReduceChecks.kt`; the processor half on
`run_media3.sh` against real Media3, as `VoiceEffectsChecks` is): speech-like
signal (harmonic tones gated like syllables) plus white noise plus a 120 Hz
hum. Measured SNR must rise by at least 8 dB; the speech band's energy must
stay within 2 dB; sample count in equals sample count out exactly; no sample
exceeds full scale; silence stays near silence; a pure tone with no noise is
left within 1 dB.

**Acceptance on the phone:** record ten seconds of speech next to a fan or
running tap. Export with and without. Pull both and compare the noise floor
between words and under words (the probe already reads levels; extend
`Mp4Probe` or a sibling if it does not). Listen on the phone too - the
owner's ear is the final check for warbling.

## P3 Geometric transitions

**InShot:** shipped a geometric transition pack. **Squish today:** 27
transitions in `TransitionType` (`timeline/TimelineModels.kt`), none of them
shapes.

**Build:** Iris (circle open), Iris close, Diamond, Star, Heart, Clock wipe,
Blinds (horizontal), Blinds (vertical), Doors open, Split. Each is a reveal
mask in `squish_transition_es2.glsl` with a soft edge. Write each mask once
in Kotlin as the reference the shader is checked against, the way
`ShaderUniformChecks` and the look checks read the `.glsl`.

**JVM check first:** per transition, coverage at progress 0 is 0 and at 1 is 1
over a grid of pixels; per pixel it never decreases as progress rises; the
heart and star are symmetric left-right; the soft edge is never wider than its
setting. Add to the transition picker with thumbnails drawn from the same mask.

**Acceptance:** apply each between two contrasting clips, export, contact sheet
across the transition: the shape opens as named, centred, with no hard
aliasing, and the preview matches.

## P4 Bilingual captions

**InShot:** auto-captions with bilingual support. **Squish today:** captions
translate (`editor/edits/TextEdits.kt`, `TextSheet.kt`), but the translation
*replaces* the original.

**Build:** a caption line can keep its original text alongside the
translation; a "Show both" switch draws the original above and the translation
below, smaller, as one caption block - through the export's own renderer, as
captions already are, so there is still one drawing. Draft format version bump;
old drafts read as one language.

**JVM check first:** the two-line block stays inside the chosen safe area for
every ratio; word timing (`wordStartsMs`) still drives the original line's
highlight; removing a span by transcript removes it from both lines.

**Acceptance:** auto-caption a clip, translate, Show both, export, frames:
both lines present, neither clipped, timing unchanged.

## P5 Gradient and pattern canvas backgrounds

**InShot:** background can be blur, colour, gradient, pattern or a picture.
**Squish today:** `CanvasFill` (`editor/FrameRules.kt`) has Fill, Colour, Blur,
Image.

**Build:** `CanvasFill.Gradient` (two colours, angle) and `CanvasFill.Pattern`
(dots, stripes, grid, checks, waves - procedural, with two colours and a
scale). Drawn by the same code for the preview and the export; a bitmap built
once per setting is fine, a second implementation is not. Draft version bump.

**JVM check first:** gradient endpoints land on the canvas edges at every angle;
patterns tile with no seam at the canvas edge for every ratio; colours round-
trip through the draft.

**Acceptance:** each fill on a 9:16 canvas with 16:9 footage, exported, pulled,
and compared with the preview screenshot.

## P6 Animated stickers (GIF)

**InShot:** animated stickers in the sticker library. **Squish today:** stickers
are static; GIF is export-only (`media/gif/GifEncoder.kt`, `GifChecks.kt`).

**Build:** a pure-Kotlin GIF decoder (LZW, disposal methods, transparency,
per-frame delays) - pure so it runs on the JVM. A GIF placed as a sticker plays
its frames by presentation time in the preview and in the export, loops, and
takes the sticker's box, opacity and animations. Import from the phone's files
and photos. Plus a built-in pack of a dozen animated stickers made the way the
sound originals are - generated by the app, so there is no licence question.

**JVM check first:** round-trip through the existing `GifEncoder` - encode a
known sequence, decode it, frames and delays equal; disposal-to-background and
disposal-to-previous each rendered correctly on a crafted file; a malformed
file fails with a sentence, never a crash; frame lookup by time wraps for a
loop.

**Acceptance:** a GIF from the phone on a clip; export; frames show it
animating at its own rate, transparency intact, no black box behind it.

## P7 A real sound-effects library

**InShot:** a large, categorised sound-effects library. **Squish today:** 16
audio stickers (`editor/SoundStickers.kt`), synthesised.

**Build:** about 60 effects in categories (whooshes and swipes, pops and clicks,
impacts, risers and drops, dings and notifications, UI, cartoon) from the same
synth, so every one is ours. A browser on the Sound tool with categories,
tap-to-hear, and add-at-playhead. If the synth cannot make something convincing
(applause, laughter), leave it out rather than ship a bad one.

**JVM check first:** extend `SoundStickerChecks`: every effect renders, length
within its declared bounds, peak normalised to the same level, no clipping, no
DC offset, and no two effects identical.

**Acceptance:** add five from different categories, export, listen; levels sit
together with no one effect much louder than the others.

## P8 Blend modes on video overlays

**InShot:** blend modes on picture-in-picture video. **Squish today:**
`timeline/LayerBlend.kt` blends *stills* only. `COMPETITORS.md` G1 explains why
video is blocked: Media3's compositor blends source-over and offers no hook.

**Build:** the route G1 costs at about a week - take a blended *video* overlay
out of the compositor and feed it to the base clip's own effect chain as a
second texture from a decoder driven by hand (`media/ReverseRenderer.kt` has
that machinery), so one shader sees both pictures and applies `LayerBlend`'s
formulas (`squish_layer_blend_es2.glsl`, already checked by
`LayerBlendChecks`). The preview must use the same shader, not a Compose
`BlendMode`.

**Acceptance:** a Screen and a Multiply video overlay over a clip; pull a frame
from the export and check a region against the formula on the two source
frames (`crop.ps1`); the preview matches. Seek, scrub and play across the
overlay's start and end with logcat clean. Release build gfxinfo before and
after - this is the item most likely to cost frame time.

Do this last: highest cost, highest risk, and nothing above depends on it.

## P9 Say what the app actually does

`README.md` still says "No network permission is requested" - the manifest has
had INTERNET since the online music, fonts and stock arrived (`online/Online.kt`
gates every request and nothing uploads). It also lists "16 graded looks",
"speed 0.5-2x", tool names from before the renames, and features from a
much smaller app. Rewrite the feature section against the code, and make the
privacy line say what is true: fetches music, fonts and stock footage when
the person turns it on; never sends a video. Check the Settings privacy card
says the same thing in the same words.

## R Extended regression sweep

After P1 and again after P8 (the two risky items), and once at the end:

1. `sh tools/jvm/run_desktop.sh` - the whole JVM suite, about 50 minutes. Run it
   while doing device work; do not edit source while it or a Gradle build runs.
2. `./gradlew clean assembleDebug` and `assemblePerf`; install each in turn.
3. Footage matrix, each opened, edited lightly (cut, a look, text) and
   exported, then probed: portrait 30 fps, landscape 60 fps, one over 10
   minutes, one with no audio track, a photo-only project, and the
   test media set (`tools/native/make_test_media.sh`, built by the `testmedia`
   workflow) through `AvSelfTestActivity`. Push test files to the
   app's own folder and delete them by MediaStore id afterwards, never by
   pattern.
4. Every item in `CLAUDE.md` "What is currently unverified on a device" that
   the new work touches - preview, export, speed, transitions, captions, canvas,
   stickers, audio.
5. `uiautomator dump` on every new sheet: NAF count 0 for the new controls,
   each switch and slider named.
6. `adb logcat -d | grep -E "AndroidRuntime|ANR|FATAL|SquishPreview"` after
   each session of taps: nothing new.
7. If the Samsung (S23 Ultra) is attached, export P1, P3, P6 and P8 on it too -
   a different encoder is where odd failures live.
8. Write what was seen into `docs/DEVICE_FINDINGS.md` (newest last), move
   anything now proven out of `CLAUDE.md`'s unverified list, and tick **R**.

## Rules for this whole list

- **Check before code.** For each item, write the JVM check first and watch it
  fail against the unchanged code (on a scratch copy, per `CLAUDE.md` - never
  `sed` a live source while anything is building), then make it pass.
- **One item, one push.** When the build passes and the item's checks pass,
  commit and push to `main` (the working agreement in `CLAUDE.md`), update this
  checklist in the same commit, and say in the message what was *seen* and
  what was only *checked*.
- **Words for confidence.** "Checked" means executed on the JVM. "Seen" means
  observed on the phone or read off an exported file. Never write "verified"
  for something that was only built.
- **Phone safety** as `CLAUDE.md` has it: confirm no call before taps,
  screenshot between steps after any dialog, never delete the owner's media,
  never open the owner's projects to test in them - make a new one.
- **Stop and ask the owner** if: main does not build at the start; an item needs
  a new permission, a new network request, a new dependency over a few MB, or a
  paid service; or a DECIDE item turns out to be needed for a P item.
- **Out of time or context?** Commit what is working, leave the item `[~]` or
  `[ ]` with a line saying where it stopped, push. The next session resumes
  from here.
