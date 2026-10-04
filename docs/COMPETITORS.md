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

**G1. Blend modes** - **BUILT FOR STILLS, 4 October**, seen on the phone in the
preview and in the file. Ten modes (Multiply, Screen, Overlay, Darken, Lighten,
Hard light, Soft light, Difference, Add) on a photo overlay or a sticker - which
is what light leaks, dust, bokeh, grain and film burns actually are. The preview
runs the *file's own shader* on the shot under the still rather than a Compose
BlendMode: Compose cannot blend against a TextureView, and two implementations
of a blend are two things to disagree. That is also why a blended still covers
the whole frame - inside a shot's own surface the output frame is not yet known.

**A video overlay still cannot be blended**, and that half stays blocked on
Media3 1.11.1. Read out of the bytecode:
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

**G2. LUT import (.cube)** â€” **BUILT, 3 October**, unseen on a device. 3D and 1D
cubes, comments, commas, odd case and a DOMAIN other than 0..1; a bad file says
what is wrong in a sentence. Flattened into a tile atlas for ES2 and sampled
with two reads and a mix, which is the same trilinear `Lut3D.sample` does on the
CPU over the same eight-bit numbers. Under `files/luts/`, named in the draft as
fonts are. It goes on last, after the sliders and the curve.

**G3. Edit by transcript** â€” **BUILT, 3 October**, unseen on a device. Text â†’
"Edit by transcript": tap two words, Delete takes that stretch out of the
picture, the sound and the captions at once, as one undo step. "Take out every
um and uh" does every run in a pass. `withSpanRemoved` and
`Transcript.afterRemoval` are both executed (`SpanRemovalChecks`,
`TranscriptChecks`). A word with no moment of its own is drawn underlined and
takes its whole line, rather than pretending to a precision the data lacks.

**G4. Audio stickers** â€” InShot shipped these recently; cheap and fun.
**BUILT, 4 October**, unseen on a device. Sixteen pairs on the Stickers sheet -
a ta-da with the party popper, a boom with the explosion, a shutter with the
camera - landed at the playhead as one undo step, the emoji on the picture and
the effect on a sound row starting on the sticker's own first frame. The effect
is synthesised the first time it is asked for, as a Squish original is, and its
length is the synth's own, so nothing is probed - which is what lets the two
land in one step. `SoundStickerChecks` runs the list against the synth's own
effects, which is the one way a list like this rots.

**G5. Filters keyframed** â€” **BUILT, 3 October**, unseen. `ValueTrack.Look` is
the third keyed number beside opacity and level, with the same button, the same
playhead rule, diamonds on the strip and the keys in the draft. Only the
strength: thirteen keyable sliders would be a second timeline nobody asked for.
Its other half, **a mask's shape keyed** rather than only tracked, is **BUILT,
4 October**, unseen: a key holds a whole shape, in source time as the track is,
so a trim carries it; the numbers mix between keys, the shape, the mode and the
inversion do not, and a turn goes the short way round. The strip draws the keys
in magenta through the speed curve. `MaskKeyChecks`.

**G6. Curves (tone curve)** â€” **BUILT, 3 October**, unseen on a device.
Master + per-channel, monotone rather than Catmull-Rom (a plain spline
overshoots, and an overshoot in a tone curve is a band that gets darker as the
footage gets brighter). Folded into one 256-entry table that the shader looks up
and `Grade.applyTo` samples, so there is no second copy of the maths.
`ToneCurveChecks` and the curve part of `GradeChecks` execute it. Drafts write
version 14.
Its other half, **lift/gamma/gain wheels**, is **BUILT and DRIVEN, 4 October**:
a disc and a level slider for each of Shadows, Midtones and Highlights, folded
into the same grade pass (one more `if` in the shader, three vec3 uniforms) so
it is still one pass however much grading is on. The dot in the disc and the
three numbers are the same thing both ways (`Wheel.of` and `Wheel.pad`), which
`WheelChecks` executes along with the shader's own three steps, read out of the
`.glsl`. Seen lifting the preview and the exported file by the same amount.

**G7. A "quick fix" row on import** â€” CapCut's AutoCut and Filmora's AI Reel
Maker both answer "I have 30 clips and no time". We have Fit shots to the beat
and auto-reframe; we do not have the one button that puts them together.
**DONE, 4 October**, unseen. "Fit the shots to the song" (3 October) lays them
to the beat; **"Use the liveliest bit"** is the piece that was missing. Each
shot keeps the length it has and slides its window to where most is happening:
frame-to-frame change over forty sampled frames, and loudness, each scaled by
its own peak before they are folded - summed, a silent clip of fast movement
scored below a still one of someone talking. The ends of a file are discounted
rather than ruled out, because a hand settling reads as *movement* and would
otherwise win outright. Entirely local, which is the pitch against CapCut's
AutoCut. `LivelinessChecks` executes the choosing; the decoding half hands over
numbers.

### 2.2 LATER - worth it, not next

**G8. True frame interpolation (optical flow)** â€” Filmora advertises AI Frame
Interpolation. We have frame blending, and `CLAUDE.md` is honest that blending is
not smoothing. A GPU optical-flow pass (block matching, then warp) is feasible
and would make slow motion genuinely smooth. Expensive; do it when the slow
motion is otherwise finished.

**G9. Skin smoothing / retouch** â€” CapCut and InShot both have it and this
audience (product video, vlogs, reels) uses it. MediaPipe's face mesh is already
a dependency. A bilateral blur inside the face mask with one Strength slider is
the whole feature. Deliberately *one* slider - the moment it becomes a beauty
suite we are InShot.

**G10. Vocal / music separation** â€” Filmora has AI Vocal Remover. On-device
separation (a small Demucs-style model) is possible but heavy. Only worth it if
users ask.

**G11. More templates, local** â€” 14 against CapCut's thousands. We will never win
on count, and should not try, but 40 good ones made from our own looks and
animations is a weekend and raises the floor for a new user. **BUILT, 4
October**, unseen: 42, on five shelves - Social, Film, Life, Retro, Work -
because 42 in one list is a scroll nobody finishes. `TemplateChecks`.

**G12. Shape and arrow annotations** â€” all three have basic shapes. We have text
and stickers; a line, arrow, rectangle and circle with the same box controls is
small and often asked for in product video. **BUILT, 4 October**, unseen: eight
shapes, outlined in red by default, with the Shapes card above the stickers and
the sticker's own box on the picture. Its one trap - drawing a shape for the
preview and again for the renderer - is avoided twice over: the shape is points
in a box (`editor/Annotation.kt`, `ShapeChecks`), and the preview already paints
its captions with the *export's* renderer, so one description is one drawing in
both. The picker's tiles are drawn from the same points, so a tile is the shape
that lands.

**G13. Safe-area guides** â€” **BUILT, 3 October**, unseen. Chips on the Frame
sheet dim where TikTok, Reels or Shorts put their own buttons and caption, with
an "Anywhere" that is worked out as the worst edge of the three. A guide only;
nothing reaches the file. None of the three show you this.

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

Done on 3-4 October: **G6 curves**, **G2 LUT import**, **G3 edit by
transcript**, **G5 filter keyframes**, **G13 safe-area guides**, **G1 blend
modes on stills**, and the half of **G7** that matters most - "Fit the shots to
the song". All but the transcript and the song fit were driven on the phone on
4 October and work in the preview and in the file; see `docs/DEVICE_FINDINGS.md`
for the three faults that round turned up.

**G1 blend modes** turned out to be half possible: a *still* can be blended,
because the preview and the file can both run the same shader on the shot under
it. A *video* overlay still cannot - its layers only meet inside Media3's
compositor. See the item.

**Done on 4 October (dawn to morning), the whole of what was left on this
list**, built on the desktop and executed on the JVM, none of it seen on a
phone yet:

- **G12 shape and arrow annotations.** Eight shapes - rectangle, ellipse,
  triangle, diamond, star, line, arrow, double arrow - outlined in red by
  default, solid if you want. The trap this item was flagged with, drawing a
  shape twice, is avoided twice over: the shape is points in a box
  (`editor/Annotation.kt`, `ShapeChecks`), and the preview already paints its
  captions with the *export's* renderer, so one description is one drawing in
  both. A shape is a caption with no words, carried as a sticker, so everything
  that already leaves stickers out of words leaves shapes out too.
- **G11 more templates**: 42 against 14, on five shelves (Social, Film, Life,
  Retro, Work), all made of looks, title presets and effects that already
  exist. `TemplateChecks` reads the table as text and checks every look id,
  preset and effect against the files that declare them.
- **G5's other half: a mask's shape keyed.** A key holds a whole shape, in
  source time as the track is, mixed between keys and held outside them; the
  shape, the mode and the inversion do not mix. `MaskKeyChecks`.
- **The rest of G7**: "Use the liveliest bit" slides each shot's window to
  where most is happening - frame-to-frame change and loudness, each scaled by
  its own peak, the ends discounted because a hand settling reads as movement.
  `LivelinessChecks`.
- **G4 audio stickers**: sixteen emoji-and-noise pairs landed in one step.
  `SoundStickerChecks`.

What is left, in the order it is worth doing:

1. ~~**Drive all five on a phone.**~~ **Done, 4 October morning** - see
   `docs/DEVICE_FINDINGS.md`. The shape and the keyed mask were checked in an
   exported file as well as on screen; what is still unseen of them is listed
   in `CLAUDE.md`.
2. ~~**G6's other half**: lift/gamma/gain wheels.~~ **Done, 4 October 06:50**,
   and driven - see the item and `docs/DEVICE_FINDINGS.md`.
3. Then **G9 skin smoothing**: one Strength slider, a bilateral blur inside the
   face mask MediaPipe already finds. The cheapest of what is left and the one
   with real demand in this audience.
4. **G8 optical flow**, which is what would make slow motion genuinely smooth.
5. **G1 for video overlays**, if the week of compositor work is judged worth it.

The five built on 3-4 October were all reasoned and executed, and **none of them
has been seen on a phone**. `CLAUDE.md` lists what the device has to answer for
each; the one with a real risk is the keyed filter strength, where the export's
item clock could put a faded look at the wrong moment while the preview looks
right.

**Sources for the competitor claims** (read 3 October 2026):
[Filmora Android features](https://filmora.wondershare.com/filmora-ai-video-editing-app-android/),
[Filmora mobile keyframes](https://filmora.wondershare.com/guide-mobile/add-keyframe.html),
[InShot on Google Play](https://play.google.com/store/apps/details?id=com.camerasideas.instashot),
[InShot review](https://vidpros.com/inshot-review/),
[CapCut AutoCut](https://www.capcut.com/resource/capcut-autocut),
[CapCut on Wikipedia](https://en.wikipedia.org/wiki/CapCut).
