# Beating InShot, CapCut and Filmora

Written 3 October 2026.

## What this is, and what it is not

The three apps' own published feature lists were read on 3 October 2026 - the
Filmora Android feature page, the InShot store listing and reviews of it, and
CapCut's own resource pages. **Nothing here was seen by driving those apps.**
Where a competitor claim is marketing language ("10,000+ effects", "AI Media
Analysis") it is repeated as marketing language, not as a thing we know works.
Anything we decide to match on the strength of a marketing page should be
checked against the real app first, because half of what is listed on those
pages is a server round-trip with a credit meter on it.

What Squish has was read out of this repository, not remembered.

## The opinion, before the list

**Do not chase parity.** The list below has about sixty items in it. If we build
all sixty we will have built InShot, and InShot already exists. Three things are
worth holding onto while reading it:

1. **Our edge is trust and finish, not count.** "No watermark, no upload,
   nothing held back" is on the done screen, and it is true - `online/Online.kt`
   is the only way out to the network and nothing sends a video through it.
   CapCut and Filmora both answer to a credit meter and a cloud. That is a
   market position, and every feature that needs a server erodes it.
2. **Our second edge is that the hard parts are correct.** The export arithmetic,
   the timing, the colour maths and the crop geometry are executed on the JVM
   and checked. Today a probe found the 24 fps export writing a variable-rate
   file; it is fixed and measured. Nobody else in this bracket is doing that,
   and it is why a Squish export can be trusted. Spending the next month on
   sixty small features will stop that happening.
3. **"No clutter" is a feature we already shipped.** The toolbar levels, one
   orange, the sheets that fold - that work is done and it is the reason the app
   is usable. A pile of AI buttons bolted onto the dashboard throws it away.

So: build the fifteen or so items in "Build next", skip most of the rest on
purpose, and keep the skips written down so the next session does not quietly
re-open them.

---

## 1. Where Squish already stands

### Parity - built, no action

These are on at least one competitor's headline list and we have them:

| Them | Us |
|---|---|
| Multi-track timeline, PiP, layers | overlay rows, `MAX_FOOTAGE_LAYER`, B7/B8 |
| Keyframes (position, scale, opacity) | `ValueKey`, placement/opacity/volume keys, B13 |
| Speed curves, speed ramp | Speed sheet curve, Hero/Montage presets, `RampChecks` |
| Reverse, freeze, rotate, mirror, replace | B11, all of it |
| Chroma key / green screen | `ChromaKey.kt`, now executed against its own shader |
| Background removal (person) | MediaPipe cut-out, `files/segments` |
| Motion tracking | `ObjectTracker`, Track tool, pins masks/overlays/lines |
| Stabilisation | `Stabilizer`, per-clip Strength |
| Auto captions + word timings | `wordStartsMs`, `TextTiming` |
| Caption translation | `TranslateChunk` |
| Text to speech / read aloud | `files/speech/`, TTS bind in the manifest |
| Voice changer | `VoiceEffect`, per clip, checked against real Media3 |
| Audio ducking | `DuckRules.kt` |
| Silence removal | `SilenceRules.kt` |
| Loudness / even out volume | `Loudness` |
| Beat detection and beat sync | `AudioRules.chosenInWindow`, `BeatFit` |
| Transitions (24), filters (49), effects (21) | catalogues, all local |
| Templates | 14, local |
| Split screen / 2x2 grid collage | `SplitScreen.kt` |
| Auto reframe | `FrameRules.subjectOnCanvas`, per shot |
| Canvas / background / blur backdrop | `CanvasBackdrop`, B12 |
| Masks (shapes, feathered, tracked) | `MaskOutline`, heart and star included |
| GIF export | `Gif` |
| Stock footage | online, behind the gate |
| 4K, 60 fps, HEVC, HDR (HLG) export | B14, encoder ceiling respected |
| Markers, snapping | general markers + beats, with ticks |
| Group/multi-select edit | "Select more" (InShot shipped this recently; we had it) |

### Ahead - things we have that they do not advertise

- **No watermark, no upload, no account, no credits.** Filmora and CapCut both
  meter their AI. InShot watermarks some exports.
- **An export you can trust.** Constant frame rate, measured; the done screen
  states frame, length, rate and size read back off the file itself; "Saved to
  your gallery" only when it was.
- **Fit to a size** with a real solve and a retry card. None of the three
  advertise an upload-limit fit.
- **Recovery and drafts**: ten-minute snapshots, a bin with 30 days, relink of
  deleted media, staged projects that survive `am kill`.
- **Thirteen colour sliders and eight HSL bands** on a phone, folded into one
  grade pass. InShot's colour tools are thinner than this.
- **Honesty in the UI**: the slow-motion panel says "7.5 fps out - too few
  frames to read as motion"; the fps row says "a higher rate can't add frames".
  Nobody else tells you when a setting will not help.

---

## 2. The gaps

Verdicts: **BUILD** (worth it), **LATER** (worth it, not now), **SKIP** (on
purpose, reason given), **DECIDE** (needs your call, usually because it costs
money or a promise).

### 2.1 BUILD NEXT - cheap, visible, and they all have it

**G1. Blend modes on overlays** — **BLOCKED, 3 October.** CapCut, InShot and
Filmora all have them and we have opacity only, so this is still the biggest
hole: light leaks, dust, bokeh, smoke and film-burn overlays are all sold on the
assumption of Screen or Add.

It cannot be done honestly on Media3 1.11.1. Read out of the bytecode:
`androidx.media3.common.OverlaySettings` offers alpha, the two frame anchors,
scale, rotation and an HDR luminance multiplier - and no blend mode;
`VideoCompositorSettings` offers an output size and those settings per input;
and `Transformer.Builder` has no hook to put a different compositor in. Media3
blends the layers source-over and that is the end of it. We could make a blend
mode look right in the *preview* in an afternoon, and it would be wrong in the
file - which is the one thing this codebase does not do.

The way through, when it is worth the work: stop using the compositor for a
blended overlay and put the overlay on the *base* clip's own chain as a second
texture, fed by a decoder we drive ourselves (the machinery `ReverseRenderer`
already has) so one shader sees both pictures and can blend them with any
formula. That is a week, not an afternoon, and it should be costed against
G3/G2/G6 rather than assumed.
Minutest level:
- `Clip.blendMode` enum: Normal, Multiply, Screen, Overlay, Soft light, Lighten,
  Darken, Add, Difference. Nine is the set every competitor ships.
- One shader (`squish_blend_es2.glsl` already exists for transitions - extend or
  add a sibling) applied to the overlay layer before compositing.
- Preview: the overlay's `graphicsLayer` takes a `BlendMode` on the Compose side;
  check that Compose's `BlendMode.Screen` and the GL one agree on a mid grey -
  that comparison belongs in a JVM check with both formulas written once.
- Export: the overlay's effect chain gains the blend pass. Watch the premultiply
  ordering - our chain already ends in a premultiply for keyed overlays.
- UI: a chip row on the Blend sheet (which already exists), not a new sheet.
- Draft: version bump, old drafts read Normal.
- Risk: medium. The preview/file agreement is the thing to check, and it is
  checkable.

**G2. LUT import (.cube)** — CapCut and Filmora both take LUTs; InShot does not.
We have 49 looks and no way to bring one in. For the product-video audience this
is the difference between "nice filters" and "my brand's grade".
Minutest level:
- A `.cube` parser (1D and 3D, sizes 17/25/33/64), in `media/effects/Lut.kt`,
  pure and JVM-checkable against a known identity cube.
- Store under `files/luts/`, named in the draft by file as fonts already are.
- A 3D texture sampled in the look shader - ES2 has no `sampler3D`, so pack the
  cube into a 2D atlas and interpolate, which is the standard trick and must be
  checked against the CPU copy on a dozen colours.
- `Grade.applyTo` gains the same LUT path so photo overlays and swatches agree.
- UI: "Import a LUT" at the end of the Filters row, with Strength.
- Risk: medium-high (the atlas interpolation), but entirely checkable on the JVM.

**G3. Edit by transcript** — CapCut and Filmora both ship text-based editing. We
are *most of the way there already and do not expose it*: auto-captions give word
timings (`wordStartsMs`), `TextTiming` maps them through trims and cuts. The
missing piece is a screen that shows the transcript and deletes the footage under
a deleted word.
Minutest level:
- A transcript sheet listing words with their times for the selected shot or the
  whole edit.
- Select a word range → a Delete that cuts the clip at those times and closes the
  gap (the magnetic model already does the cut and close).
- Strike-through rather than removal, with an undo step per edit.
- A "remove filler words" pass (um, uh, like) on top of the same machinery -
  CapCut sells this and ours would run on the phone.
- Risk: low-medium. All the arithmetic already exists and is checked.

**G4. Audio stickers** — InShot shipped these recently; cheap and fun.
- A sticker carries an optional sound that lands as a sound clip at its start.
- Reuse the sticker picker and the sound-clip landing from Read aloud.
- Risk: low.

**G5. Masks and filters keyframed** — Filmora advertises keyframing "colour and
effect"; CapCut keyframes filters. The roadmap already names this as the natural
next use of `ValueKey` and says it is not built.
- A `ValueKey` track on a clip's filter strength, and on each Adjust slider worth
  animating (exposure, saturation, temperature at minimum).
- A mask's shape keyed, not just tracked.
- Risk: low-medium. The track type and the strip's diamonds exist.

**G6. Curves (tone curve)** — **BUILT, 3 October**, unseen on a device.
Master + per-channel, monotone rather than Catmull-Rom (a plain spline
overshoots, and an overshoot in a tone curve is a band that gets darker as the
footage gets brighter). Folded into one 256-entry table that the shader looks up
and `Grade.applyTo` samples, so there is no second copy of the maths.
`ToneCurveChecks` and the curve part of `GradeChecks` execute it. Drafts write
version 14.
Still open from this item: **lift/gamma/gain wheels**, which would fold into the
same grade pass and are the other half of what a colourist expects.

**G7. A "quick fix" row on import** — CapCut's AutoCut and Filmora's AI Reel
Maker both answer "I have 30 clips and no time". We have Fit shots to the beat
and auto-reframe; we do not have the one button that puts them together.
- One action: trim each clip to its liveliest N seconds (we already measure
  loudness and motion), lay them to the beat of a chosen track, apply a look.
- Entirely local, which is the pitch: "no upload" against CapCut's AutoCut.
- Risk: medium, but every part exists.

### 2.2 LATER - worth it, not next

**G8. True frame interpolation (optical flow)** — Filmora advertises AI Frame
Interpolation. We have frame blending, and `CLAUDE.md` is honest that blending is
not smoothing. A GPU optical-flow pass (block matching, then warp) is feasible
and would make slow motion genuinely smooth. Expensive; do it when the slow
motion is otherwise finished.

**G9. Skin smoothing / retouch** — CapCut and InShot both have it and this
audience (product video, vlogs, reels) uses it. MediaPipe's face mesh is already
a dependency. A bilateral blur inside the face mask with one Strength slider is
the whole feature. Deliberately *one* slider - the moment it becomes a beauty
suite we are InShot.

**G10. Vocal / music separation** — Filmora has AI Vocal Remover. On-device
separation (a small Demucs-style model) is possible but heavy. Only worth it if
users ask.

**G11. More templates, local** — 14 against CapCut's thousands. We will never win
on count, and should not try, but 40 good ones made from our own looks and
animations is a weekend and raises the floor for a new user.

**G12. Shape and arrow annotations** — all three have basic shapes. We have text
and stickers; a line, arrow, rectangle and circle with the same box controls is
small and often asked for in product video.

**G13. Export presets per platform with safe areas** — we have WhatsApp / Email /
Reels / YouTube chips. Adding the *safe-area overlay* (where TikTok's UI covers
the frame) is a small drawing job and a real differentiator for reels.

### 2.3 SKIP - on purpose

Each of these is on a competitor's list. Each is a deliberate no, and the reason
matters more than the item.

- **AI text-to-video, script-to-video, idea-to-video, AI storyboard, AI
  copywriting** (Filmora). These are a server, a model bill and a credit meter.
  They are the opposite of our promise. **Skip permanently.**
- **AI object remover / generative inpainting** (CapCut, Filmora). Same reason.
  Person cut-out we do locally and that is the honest subset.
- **AI video enhancer / upscaling** (Filmora). Server-bound, and the results at
  phone resolution are mostly marketing.
- **Voice cloning** (Filmora). Server, and a consent problem we do not want.
- **AI music generator, AI sound-effect generator** (Filmora). We have Squish
  originals with known tempos, which is better than generated loops nobody can
  licence clearly.
- **Animated charts** (Filmora). Wrong app.
- **Screen recorder** (Filmora). The OS has one. Not an editor's job.
- **Multi-camera / angle sync** (Filmora desktop). Not a phone workflow.
- **AR filters, body effects, hair glow** (CapCut, InShot). This is where InShot
  and CapCut spend their clutter budget. Skin smoothing (G9) is the one piece
  with real demand; the rest is a different product.
- **Photo collage suite** (InShot is half a photo editor). We have Grid and Split
  screen for video. A collage builder is a second app.
- **An asset marketplace** (CapCut, Filmora). Content operations, licensing,
  moderation, a CDN bill. No.
- **Cloud projects / desktop handoff.** Contradicts "nothing uploads".
- **Pro subscription gating of existing features.** Whatever the business model
  becomes, the things that work today should keep working.

### 2.4 DECIDE - your call, not mine

- ~~**D1. Do we ever ship a server?**~~ **Answered, 3 October: no.** There is no
  server business. The internet is for *fetching* things - music, stock footage,
  templates when they exist, and LUT or sound packs if they are added - and
  nothing is ever sent. That is a content library behind `online/Online.kt`, not
  a server, and 2.3's first half stays skipped on that basis. It is worth saying
  in the Settings privacy card in those words.
- ~~**D2. Who is the user?**~~ **Answered, 3 October: everyone editing video** -
  reels, short films, films. So the ordering is the serious-tool one: colour
  (curves, LUTs), transcript editing, annotations and safe areas before anything
  cosmetic, and the AR/beauty cluster stays skipped.
- **D3. Is 60 fps preview worth the work?** None of the three manage it reliably
  on mid-range phones. We run the editor at 14 ms a frame on a release build.
  Pushing to a locked 60 fps preview with four surfaces is a real differentiator
  for "feels better than CapCut", and it is invisible on a feature matrix.

---

## 3. If you want an order

~~1. **G1 blend modes**~~ - blocked by Media3's compositor; see the item.
~~3. **G6 curves**~~ - **done on 3 October**, waiting on a device.

1. **G3 edit by transcript** - most of it is already built and unexposed; the
   biggest win per hour in the list.
2. **G2 LUT import** - the same table the curve now uploads, read from a .cube,
   which makes it markedly cheaper than it was before the curve existed.
5. **G5 filter and mask keyframes** - closes the roadmap's own loose end.
6. **G7 quick assemble** - the answer to AutoCut, entirely local.
7. **G4 audio stickers, G12 annotations, G13 safe areas** - small, in any order.
8. Then revisit **G8 optical flow** and **G9 skin smoothing** with D2 answered.

Everything above is reasoned from the repository and from the three apps'
published material. None of it has been tried; each item's real cost will move
once someone starts. The first one to start is G1, and the thing that will
decide whether it is right is a JVM check that the Compose blend and the GL
blend agree on a grey.

**Sources for the competitor claims** (read 3 October 2026):
[Filmora Android features](https://filmora.wondershare.com/filmora-ai-video-editing-app-android/),
[Filmora mobile keyframes](https://filmora.wondershare.com/guide-mobile/add-keyframe.html),
[InShot on Google Play](https://play.google.com/store/apps/details?id=com.camerasideas.instashot),
[InShot review](https://vidpros.com/inshot-review/),
[CapCut AutoCut](https://www.capcut.com/resource/capcut-autocut),
[CapCut on Wikipedia](https://en.wikipedia.org/wiki/CapCut).
