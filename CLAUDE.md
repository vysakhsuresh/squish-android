# Working on Squish

Read this first. It exists because every session starts with no memory of the
last one, including sessions that ran on a different machine — most of this
codebase was written from a desktop CLI by a session that cannot be asked
anything now, and the session before that read its own commits as a stranger's.

## Where you are matters, and it decides what "verified" is allowed to mean

There are two places this work happens, and they can do different things.

**The cloud sandbox (claude.ai/code).** No Android SDK, no emulator, no device.
`kotlinc` alone is available, and it reports every Compose and Media3 reference
as unresolved, so its output is thousands of lines of cascade with the occasional
real mistake buried in it. The checkers below exist to find those. What this
place *cannot* do is compile the project, run it, or look at a pixel.

**The desktop CLI.** A gradle wrapper is checked in, so `./gradlew assembleDebug`
is a real compile that catches everything the sandbox cannot. If a device or
emulator is attached, the app can be installed and driven.

The rule that follows: **never write "verified" for something this session could
not observe.** In the sandbox, "the arithmetic is checked and it compiles
structurally" is the honest ceiling, and it should be said in those words. Two
bugs shipped in one round because "verified" was used for both — a distorted
rotated export and a freeze — and neither was anything the sandbox could have
seen. Say which kind of confidence you have, every time.

## Verifying, in order of what it costs

```
# Always, wherever you are:
kotlinc -nowarn -d /dev/null $(find app/src/main/java -name '*.kt') > log 2>&1
python3 tools/check_unresolved.py log   # a renamed thing with a caller left behind
python3 tools/check_calls.py log        # a signature changed, a caller left behind
python3 tools/check_nesting.py          # a declaration swallowed by a stray brace
python3 tools/check_modifier_imports.py # a Modifier extension used unimported
python3 tools/check_dependencies.py     # a library imported but never declared
python3 tools/check_shaders.py          # a shader and its Kotlin disagreeing
KOTLINC=<path>/kotlinc sh tools/jvm/run.sh   # the arithmetic, executed for real

# On the desktop, additionally — and this is the one that settles things:
./gradlew assembleDebug
```

Four of those checkers were written *after* the thing they check for got
through. When a mistake escapes, the question is not only "what was wrong" but
"what would have caught it" — and if the answer is nothing, write the check
before writing the fix.

The `tools/jvm` suites are the strongest tool here by a distance. Anything that
can be separated from the framework — speed curves, crop geometry, colour
grading, timeline windowing, frame budgets — is compiled and **executed** on the
JVM against real numbers. Several bugs were found that way that no amount of
reading would have produced. When adding logic, ask whether it can be a pure
function first; if it can, it can be checked.

## On the desktop (Windows, Git Bash) — what it took to find out

- **Build with JDK 21, not Android Studio's bundled one.** Studio ships JBR 25,
  and Gradle 8.9 cannot run on it; the only symptom is a bare "25.0.3".
  `JAVA_HOME=$HOME/.jdks/jbr-21.0.11 ./gradlew assembleDebug`.
- **There are no unit tests.** `./gradlew testDebugUnitTest` succeeds by having
  nothing to run, so it proves nothing. The `tools/jvm` suites are the tests.
- **Python is not installed here**, so the `tools/check_*.py` checkers only run
  in the sandbox. On this machine `assembleDebug` is the check.
- **The device is the user's own phone** (moto g84 5G, serial ZY32J8HF2S),
  driven with `C:/Users/vysak/AppData/Local/Android/Sdk/platform-tools/adb`.
  Before any taps, confirm it is free: `dumpsys telephony.registry | grep
  mCallState` is 0 and Squish is the resumed activity. A call once came in mid
  test and the taps landed in Messages. Never delete the user's media; clean up
  test files you add, by MediaStore id, not by pattern — `LIKE 'sq_%'` matched
  every `squish_` export too, because `_` is a wildcard.
- **Git Bash rewrites `/data/...` and `/sdcard/...` paths** into Windows ones.
  Prefix `adb push`/`adb shell` with `MSYS_NO_PATHCONV=1`.
- **Two-finger gestures:** `sendevent` is refused by SELinux and `input` is one
  finger only. A monkey script does it: a file containing
  `type= raw events` / `count= 1` / `speed= 1.0` / `start data >>` /
  `PinchZoom(x1a,y,x1b,y,x2a,y,x2b,y,15)`, run with
  `monkey -p com.squish.app -f /data/local/tmp/<file> 1`.
- **Logcat is small and the media server floods it.** `adb logcat -G 16M` before
  reproducing anything, or the app's own lines are gone by the time you look.
- **Drafts can be read on the device** because the build is debuggable:
  `adb shell run-as com.squish.app cat files/projects/<slot>.json`. Back a draft
  up the same way before an experiment that might overwrite it.
- **Files can be opened directly**, without the photo picker:
  `am start -a android.intent.action.VIEW -d content://media/external/video/media/<id> -t <mime> --grant-read-uri-permission -n com.squish.app/.MainActivity`.
  (A `SEND` from `am` carries no read grant for its extra; that failure is the
  test, not the app.)
- **This phone decodes no HEVC 10-bit (Main 10)**, hardware or software. Film
  rips in that format are expected to fail on it; `MediaCompat` says so to the
  user. A second attached phone (Samsung S23 Ultra, R5CWA381NEE) should decode it.
- **Working agreement:** when the build passes, commit and push to `main`
  without asking.

## What is currently unverified on a device

Everything in this list is reasoned-about, not seen. Anyone who reaches a device
should work through it and then delete what holds up.

- **Rotate 90° then export.** Two places computed the output size and only one
  knew about the rotation, so the chain asked a 1920x1080 frame to fit a
  720x1280 box. The arithmetic is fixed and checked (`tools/jvm/FramingChecks.kt`);
  what nobody has seen is the resulting file.
- **Rotate 90° freezing the editor.** Seen on the phone: the picture shrank to a
  corner, the clock stuck at zero, and logcat repeated "Detaching surface timed
  out" from `clearVideoTextureView` on the main thread every two seconds. The
  rotation was an effect in every player's chain, so rotating stopped,
  re-chained and reloaded each player; the pipeline wedged, and the stall reload
  then let go of the surface on the main thread, which waits on the stuck
  playback thread. Batch B4 took rotation, crop and captions out of the players
  (the view is turned on screen; no chain is ever changed after a player is
  made) and the reload no longer touches the surface. Built, not yet seen.
- **Batch B4 of the roadmap (preview engine), all of it.** Built with no phone
  attached; `docs/ROADMAP.md` §4 has the script. The decisions are executed on
  the JVM (`tools/jvm/PreviewRulesChecks.kt`, `PreviewChecks.kt`); what only a
  device answers: that `onRenderedFirstFrame` fires after a seek with an effect
  chain (a two-tick READY fallback covers it if not), that a turned TextureView
  resizes without a stall, that the pass-through mask and key shaders cost
  nothing visible on four surfaces, the rotation direction against an export,
  and that a paused frame comes back after Home. Also from its review round:
  that a keyed or masked overlay now shows clean holes over the base (its chain
  ends in a premultiply pass; the base views went back to opaque because the
  effects pass already writes alpha 1); that a hard cut into a shot that is not
  ready holds the outgoing shot's *last* frame (reseeked there) for at most 2 s
  and never after a jump; that a first-frame callback left over from an earlier
  seek no longer opens the gate (it relies on ExoPlayer masking the state to
  BUFFERING on seek); and that a playhead drag settles exactly the moment the
  finger lifts.
- **Slow motion below about 24fps out.** Stepping is arithmetic, not a fault:
  slowing footage does not create frames. The panel now says so with the number.
  True smoothing needs frame blending or optical flow and has not been built.
- **A hand-drawn crop in the preview.** It reached the export before it reached
  the preview; now it does both, unseen.
- **Batch B1 of the roadmap (data safety and exit paths), all of it.** Built and
  compiled on the desktop with no phone attached. The device script is in
  `docs/ROADMAP.md` §4 under B1: export keeps the project and "Back to editor"
  returns to the same edit; "Start a new project" asks and moves the old draft
  into `files/projects/trash/`; a cut made within a second of pressing back is
  on disk; the first edit under the recovery banner retires the offer; the
  banner is modal after `am kill`; the custom crop and the beat grid survive a
  kill; back mid-export asks "Stop exporting?" and leaves no file in
  `exports/`; "Open with" a document URI loads; each Stitch gets its own draft;
  any change under the inline banner, recorded or not, retires it and undoing
  back to the bare clip brings it back; "edited since" goes by content, not by
  save time; a quick-tool session started from the dashboard comes back after
  a kill; the drafts list offers the ten-minute snapshot as "Earlier version".
  The snapshot rotation and the all-or-nothing bin moves are executed on the
  JVM (`tools/jvm/HousekeepingChecks.kt`); their use on Android storage is not.
  Two things only a device can answer: whether `SavedStateHandle` comes back
  set after `am kill` (that is what makes the banner modal), and whether
  `keepScreenOn` holds through a long render.
- **Batch B2 (timeline model and strip mechanics).** The model is executed on
  the JVM (`tools/jvm/MagneticChecks.kt`, including the per-event drag and trim
  streams the strip sends); nothing of it has been seen on a phone. Script in
  `docs/ROADMAP.md` §4 under B2, plus (since B7 a clip is carried by a long
  press, not dragged): an overlay carried into another on its own row stops
  against it there; the Blend sliders on an animated
  overlay stay where they are put; Cut on beats leaves the song whole; a draft
  saved with gaps on the main track keeps them through a trim or a cut.
- **Batch B5 (the export pipeline on Media3 1.11.1), all of it.** Built on the
  desktop with no phone attached; the stacking, transition, keyframe-clock,
  sizing, sound-slice and fold-down arithmetic is executed on the JVM
  (`tools/jvm/ExportPlanChecks.kt`, `FramingChecks.kt`). Nothing has been
  rendered. The device gate, in this order, before anything is built on it:
  (1) video + photo with a Dissolve on the join, 480p - the export that failed
  on 1.5.1 with "The preceding MediaItem does not contain any track" must now
  complete, with the dissolve visible and sound throughout; (2) the same with the
  photo first and the video second, and with a newly added photo (rendered with
  a silent AAC track) and an old one from a draft (no sound track at all);
  (3) video + photo + a video overlay starting at 2 s at 40% top-right - the PiP
  above the base, at the size and place the preview shows, and nothing left on
  screen after it ends; (4) a gap between two clips and a clip starting at 3 s -
  black in the gap, no stray square in the middle (the empty stretches are a
  transparent 16 px PNG, `files/stills/clear_16.png`); (5) Slide and Wipe both
  ways round (in a three-clip chain the first join's incoming shot is on the
  lower roll, the second's on the upper); (6) 9:16 crop at 720p is 720x1280 with no pillars; (7) a
  song at 20 s on an 8 s clip + 30 s photo starts at 20 s; (8) a 6-channel AAC
  file at camera level 50%, with music; (9) Push in at 0.5x completes at the
  clip's end; (10) captions over a gap and over a PiP appear once; (11) the
  Saving to gallery stage shows and "Saved to your gallery" only when it was;
  (12) an HDR (HLG) clip exported plain keeps HDR and with a Dissolve comes out
  SDR, tone-mapped rather than failed - a composited export is always SDR,
  because its first input is the clock still; (13) back during "Saving to
  gallery" says it can't be stopped and offers only OK, and a change made during
  the render is in the draft after a kill during the copy.
  If (1)-(4) fail, the log line `SquishExport failed: N sequences` and the
  exception under it say which assumption in `BUILD_NOTES.md` ("what the export
  leans on") did not hold.
- **Batch B6 (the editor's layout), all of it.** Built on the desktop with no
  phone attached. The toolbar decisions (levels, which sheet survives a change of
  selection, back order, which shot Cut opens, project names) are executed on the
  JVM (`tools/jvm/ToolRulesChecks.kt`), and Duplicate's model in
  `MagneticChecks.kt`; nothing of the screen has been seen. Script in
  `docs/ROADMAP.md` §4 under B6, plus: the colours (primary is orange now, blue
  is effects and looks, Delete and failures red); Add text, and Edit on a
  line, put the keyboard up with the cursor in the field, and the sheet sits on
  the keyboard with the picture above it (the editor sets the window to
  adjustResize while it is up and pads for the IME; the strip and notices fold
  away while typing and come back scrolled where they were) - also check the
  Export sheet's custom size field and the music search there; a line added and
  left blank is gone again after Done, and Undo then offers the step before it;
  the strip compresses to its ruler and the selected row under a sheet, and the
  notices (failure, banner, proxy) have their own capped, scrolling place above
  it, so neither can push the other or the sheet's Done off screen, upright or on
  its side; the frame buttons step exactly one frame each press, forward and
  back, on a trimmed and on a retimed shot (the arithmetic is executed in
  `tools/jvm/TimecodeChecks.kt`, assuming an exact seek shows the first frame at
  or after the position); the full-screen bar lands where the thumb is, with no
  snapping; Placement's Reset on an animated clip leaves it still, full frame for
  a shot and in the corner for an overlay; the name in the header cannot be
  tapped while the "your edit is saved" banner stands; the phone on its side lays out in two panes and
  carries the same players across (the preview is movable content - check the
  picture does not go black or stall after rotating, playing and paused);
  full screen and back keep the frame, and its scrub bar leaves no scrub
  running when full screen closes mid-drag; a tap on the picture with something
  selected deselects, otherwise plays; a tap on bare track deselects; a double
  tap on the ruler fits; To
  overlay with every row taken says so; a rename alone keeps a draft, and the
  drafts list shows the name.
- **Batch B7 (the strip: fixed playhead, carrying, snapping, rows), all of it.**
  Built on the desktop with no phone attached. The arithmetic - rows for things
  that overlap, snapping, trim edges that follow the finger on retimed clips,
  where a carried shot lands, one gap closed at a time, a retime keeping its
  joins, the centred window - is executed on the JVM (`tools/jvm/LaneChecks.kt`);
  nothing of the screen has been seen. Script in `docs/ROADMAP.md` §4 under B7,
  plus: a fling of the strip scrubs until it stops and the preview settles on the
  frame it stops at; the strip does not jump back a frame when the finger lifts
  (it holds until the playhead catches up, at most 150 ms); a long press on a
  selected clip's handle trims rather than lifts; a press that lifts and lets go
  without moving changes nothing (no undo step); a clip carried to either side
  scrolls the strip and the playhead with it, and lands where it is dropped; a
  sound carried below the last sound row gets a row of its own, and one carried
  onto a taken overlay layer stays on its own layer; the rows scroll inside the
  strip past four with the ruler fixed, the track heads staying beside their
  rows; picking a clip up on a folded row does not unfold rows under the finger;
  a line of words left blank reads "Empty text"; the grey stretch beside a
  dragged handle shows how much footage is left; a tap on a keyframe diamond puts
  the playhead on it; "Add media" on an empty main track opens the picker; a gap
  in an old draft is drawn, and "Close gap" on it closes that one only; a tail
  trim snaps to a beat with a tick, not to the shots that follow it; playback
  pauses when the strip is dragged, and also when a clip is picked up.
  From its review round (built, the arithmetic in `LaneChecks.kt`, nothing
  seen): pressing a trim handle while playing pauses and the strip holds still
  under it; a sound trimmed over its neighbour keeps following the finger (the
  rows are held until the finger lifts, then it moves row) and leaves no grey
  stretch or snap line behind; the head handle of a main-track shot follows the
  finger with the shots after it, snaps to a beat with a tick, and the track
  closes up on release; dragging the strip near a cut holds the line on the cut
  with a tick, the picture shows that same frame, and nothing jumps on release;
  a keyframe tap lands exactly on the key; the picture does not change size when
  something is selected or let go (the rows are a fixed height, fewer than four
  on a short phone so Split and its hint stay on screen); an empty edit shows
  the overlay row with its add button; Blank, and Video or photo from the video
  track's button or "Add media", go in at the playhead and are selected; a
  sound dropped onto a taken row is drawn, while carried, on the row it will
  land on, and the one already there stays; a line carried past the end of the
  picture is drawn where it will stop; long-pressing a sound with a sheet open
  keeps the strip at one row until it is dropped; carried to the top or bottom
  of the rows, they scroll; slowing a song carries the sound effect butted after
  it on another row; deleting the last shot with the playhead at the end brings
  the playhead back to the new end.

- **Batch B8 (overlays), all of it.** Built on the desktop with no phone
  attached. The decisions - landing length, the main track and back, the box's
  copy, per-clip levels and the old-draft move, and the box itself (hit test,
  drag, pinch, corner handle, snapping, readout) - are executed on the JVM
  (`tools/jvm/OverlayChecks.kt`), including that the box's corners are exactly
  where `ExportPlan.placementMatrix` draws the layer. Script in
  `docs/ROADMAP.md` §4 under B8, plus: overlays are now laid out in the frame the
  export keeps, so with a 9:16 or hand-drawn crop a PiP sits inside the crop on
  screen as in the file (it used to be placed against the uncropped picture);
  a photo overlay is a PNG under `files/stills/` drawn by Compose, not a player -
  check its transparency, that it exports through Media3's image input with its
  alpha (the one path here that leans on that), and its filmstrip tile; an
  overlay row declared with sound fills its photos, gaps and muted clips with
  silence (the BUILD_NOTES fact B5 leans on for base rolls, now on overlay rows
  too); overlay audio is heard in the preview at its Volume and not under the
  camera switch, and a shot's Volume is under the camera level; an overlay row
  with nothing within 5 s lets its decoder go and reloads before its next clip
  (watch for a late first frame); three rows of footage at once over a
  transition (five video decoders) is the budget `MAX_FOOTAGE_LAYER` assumes -
  rows 4 to 6 take photos only; a draft from before this build opens with its
  overlays at Volume 0 and its shots at the old camera level; the hand-drawn
  crop is only editable on the Frame sheet (the overlay box stands aside there)
  and is drawn, untouchable, everywhere else.
  From its review round: the box's buttons sit just past its corners and the
  inside is always the body - pinch an overlay to about 20% and it still drags
  with one finger, and a tap on its middle selects rather than deletes; the
  buttons of an overlay hanging off a 9:16 crop, over the bars or the
  letterbox, can be pressed (the box's layer covers the whole preview now); the
  fourth button is Edit (opens Placement), not Reset; a tap on an overlay while
  playing pauses and shows its box; the overlay row's head opens the picker;
  Send back / Bring forward swap two overlapping overlays and never leave an
  empty lane; a PiP moved To main under a camera mute says it is silent there;
  a photo overlay longer than a minute asks before To main renders it.

- **Batch B9 (the audio suite), all of it.** Built on the desktop with no phone
  attached. The arithmetic - a fade's level at a moment and the two fades
  sharing a clip, the gain split between a player and its processor, beats on
  the clip carried through its position and speed curve, loop-to-fit's copies,
  and what Extract audio detaches - is executed on the JVM
  (`tools/jvm/AudioRulesChecks.kt`), and the toolbar's new tools in
  `ToolRulesChecks.kt`; nothing has been heard. Script in `docs/ROADMAP.md` §4
  under B9, plus what only a device answers: Record asks for the mic on the
  first press, counts 3-2-1, plays the picture *silently* (every level is
  held at nothing so the speaker stays out of the take), meters the mic, and
  Stop - or the end of the edit, or Home - lands a "Voiceover" clip with a mic
  glyph at the moment it started, selected, with the playhead back on its
  first word; Record always adds a new take at the playhead, and "Record
  this take again" under it (shown with a take selected) is the only way one
  replaces another; while it records the strip shows a "Recording…" clip
  growing on a sound row (drawn only - a tap on it selects nothing); Cancel on
  the "1" of the count-in cancels, with no mic opened and no failure shown;
  Record is dim with nothing on the picture; a picture that never starts
  (heavy project still preparing) leaves the take running with a yellow line
  saying so, and Stop lands it at its start; a moment's rebuffering mid-take
  does not end it (half a second stopped does); allowing the mic on the first
  press starts the count-in at once; the WAV under `files/voice/` plays in
  the preview and is in the file; a take under way when the editor is left is
  on the strip afterwards - including Back pressed while "Closing the file…"
  shows, and Back on the last second of the count-in leaving the mic closed
  (the phone's mic indicator goes out). A fade's wedges are drawn on the clip
  and heard in the preview (`AudioRules.fadeGain` per tick) and in the file
  (`FadeProcessor`, after the speed change in the processor chain so it runs
  on played time - the one placement this leans on); fades set on a long
  song follow it down when it is trimmed, sped up or cut (every timeline
  change passes `AudioRules.withFittedFades`), so a 6 s sting cut from a
  song with 8 s fades plays at full level in its middle and the Fade sliders
  show the fitted values; a
  sound at 300% is louder in the preview (`GainProcessor` in front of its
  sink) and the same in the file (`AudioMixing` up to 4x), clipping the same
  way; Robot on a take alone leaves the song alone in both (the voice is the
  clip's own, in every surface's and every sound player's sink - the global
  voice is gone, and an old draft's one setting lands on its main-track
  shots), and the Voice sheet's "Apply to all shots" / "Apply to all sounds"
  puts one voice on every clip of that kind as one undo step; Extract audio
  on a shot gives a sound clip under it at the level the shot was *heard* at
  (its Volume under the camera level - 30% camera and 100% shot gives a 30%
  sound), voice, speed and fades, mutes the shot, and refuses a photo or a
  silent file with a message; "Find the beat" puts dots on the song that move
  when it is dragged and spread when it is slowed, "Add beat" while listening
  drops one at the playhead (onto the camera grid, not the sound under the
  playhead, when the camera grid is the one found), the density chips are a
  toggle the dots, Mark and Cut all read, "Every bar" stays on the detector's
  downbeat after the head is trimmed by a beat and on both halves of a cut
  song (the bar is counted over the file's list, `AudioRules.chosenInWindow`,
  and the taller bar lines and "(n) bars" come from `barGrid`), a drag snaps
  to the dots, and "Snap to markers and beats" on the Sync chip turns that
  off for drags and trims too (cuts and the playhead still snap); the Sound
  sheet's middle chip is "Mic & camera"; the Music card's four categories are
  a two-by-two grid, all in sight on a phone; auditioning a song pauses
  the editor (`TransportRequest`, answered once by the preview) and takes
  audio focus, the editor playing stops the audition, so does a chip switch
  and Home; "Loop to end" beside a song on the Music chip's list, and Loop to
  the end of the picture on the Sync sheet, butt copies to the picture's end
  with the fade out on the last; the Beats card under the Beats tool has no
  Clear link (the sheet's Reset is the one place), and a grid found on the
  camera sound says on the card that it shows on the strip once marked; the
  starred and recent lists survive a restart (SharedPreferences "music"); a
  refused permission offers Settings; the camera switch is gone on a silent
  clip; a long song's
  waveform is drawn to its end (`PcmDecoder.decodePeaks`, one float per 50 ms
  rather than a ten-minute decode held in memory). Not built, by the plan's
  own "if budget remains": volume keyframes and ducking under speech.

- **Batch B10 (text and stickers), all of it.** Built on the desktop with no
  phone attached. The decisions - the looks as presets over the explicit
  fields, an arrival, a leaving and a loop each with its length, the letter
  and word reveals, the box round the letters and the letters back from the
  box (a pinch to 2x is 56 from 28, measured from the gesture's start), and
  where the segmenter hears words begin - are executed on the JVM
  (`tools/jvm/TextChecks.kt`); nothing of the screen has been seen. Script in
  `docs/ROADMAP.md` §4 under B10, plus: Add text puts "Your text" in the
  middle of the picture with the keyboard up and the words selected, and a
  line let go of still saying that is taken off again (the text track's head
  adds one the same way); adding a line, title or sticker pauses playback;
  the Edit sheet's tabs (Keyboard · Style · Bubble · Animation) sit over the
  keyboard, the keyboard folds when a tab other than Keyboard is picked and
  the sheet takes the keyboard's room so the tab row does not move (it holds
  the keyboard's last measured height - check the strip comes back when the
  sheet closes), and back on the Keyboard tab the cursor is at the end, the
  sample words selected only the first time; a double tap on a
  line on the picture opens its keyboard; the text box's corners are Delete,
  a copy nudged beside it, Edit (keyboard; Placement for a sticker) and the
  resize handle, and a small sticker does not grow when touched (its own
  limits, not the overlay's); a line's turn is drawn by the preview's
  `rotate` and written by Media3's `setRotationDegrees` negated - check the
  direction against an export, as B8 did for overlays; a flipped sticker is
  mirrored in the bitmap itself so the file agrees; the shadow is a blurred
  copy under the stroke (a shadow layer drew over it); a Band bubble is the
  frame's full width and a Speech bubble has a tail, and a Band's box is its
  letters, not the band (`CaptionRenderer.paint`), so its corners stay on the
  picture; the eyedropper reads the
  window through `PixelCopy` (a 5 px patch averaged) - check it returns the
  picture's colour, not the box's or the crop's dim - and is given up by
  back, Done, a change of tab or of selection; the Style tab's Reset puts the
  outline back (`TextStyleSpec.NEW_LINE`); "Save this style" names the first
  free "Style N" and says so; a font imported as .ttf
  lands under `files/fonts/` and a draft names it by file; Apply to all on
  Style carries the style alone (not the place) to every line of words but
  not to stickers, and Animation has its own Apply to all; auto-captions
  listen to the camera unless "Listen to" says otherwise, never to a line
  read aloud, and land still with their word timings kept: the Words arrival
  is a choice on Animation, landing each word on the segmenter's dips
  (`wordStartsMs`, mapped through the shot's clock); retyping a line clears
  the timings, a head trim moves them with the start and a cut between words
  gives each half its own words (`TextTiming`, executed in `TextChecks.kt`); a
  voiceover's lines follow it when it is dragged; SRT import over existing
  lines asks Replace or Add beside; Read aloud greys its button and shows a
  card while the voice is made, keeps the line selected when the sound lands,
  and makes a WAV under
  `files/speech/` landed as a sound clip at the line's start - the
  manifest's `<queries>` for `TTS_SERVICE` is what lets the engine bind on
  Android 11+, and whether Media3 plays and exports the WAV is the same
  question B9's voiceover asks; a draft from before this build opens with its
  looks read back through `TextStyleJson` (Outline as a stroke, Box as a
  bubble) and every animated line leaving by a fade, as it did.

- **Batch B12 (frame and colour), all of it.** Built on the desktop with no
  phone attached. The decisions - the thirteen sliders folding with the look
  into one grade (brightness an offset now, exposure and temperature into the
  gains, the hue wheel meeting itself, an HSL band's reach), a clip's crop as
  the preview's layers and the export's shader (`CropRules.windowPoint` and
  `sourcePoint` are inverses), the zoom that keeps a straightened picture
  covering its window, a shape chip's hold on the window, the padded canvas's
  size and where the picture sits on it, which shot's reframe track is read at
  a moment, and every mask shape's edge on the shader's own distance field -
  are executed on the JVM (`tools/jvm/GradeChecks.kt`, `ClipCropChecks.kt`,
  `FrameRulesChecks.kt`, `MaskOutlineChecks.kt`), and the toolbar's three new
  clip tools in `ToolRulesChecks.kt`; nothing has been seen. Script in
  `docs/ROADMAP.md` §4 under B12, plus what only a device answers: the look
  shader with its new uniforms (eight `uHsl` vec3s, `uTexel` for sharpening)
  compiles on the phone's ES2 driver - a shader that does not fails on the
  player asynchronously and the surface then plays plain (PreviewEngine's
  error listener) - and the mask shader's heart and star; a clip's crop on the
  preview (two `graphicsLayer`s inside the turned view, `ClipCropped`) lines
  up with the file (`ClipCropEffect`), on a shot, an overlay and a photo
  overlay, with the edit rotated, with a straighten and a flip; the crop
  window and the mask outline drawn inside the turned view take touches in
  the picture's own coordinates; a padded canvas (Frame → Background) is
  1080x1920 for a landscape 1080p shot on 9:16, the picture fitted whole over
  the colour, the blurred still or the chosen picture, in the preview and in
  the file - the backdrop is a *still* per shot, from its middle frame
  (`CanvasBackdrop` says why not a live blur), so the preview and the file
  agree by sharing one PNG; Media3's `Presentation` scale-to-fit pads the base
  rolls with transparent pixels (the same fact B8's PiP fit leans on), or the
  backdrop is hidden behind black bars; the loupe (`EyedropperLayer` dragged)
  reads the green screen's own colour, since the key is held off while it is
  up (`PreviewEngine.setKeyPreview`), and a 9-pixel patch averaged keys
  cleaner than one pixel did; auto-reframe now analyses every shot and each
  follows its own subject; "Filters" and "Adjust" on level 0's Looks work on
  the shot under the playhead, named on the sheet, with Apply to all; person
  masks no draft names are swept when the editor closes and when a bin entry
  is purged (a binned draft keeps its masks, so Restore brings its cut-out
  back - the roadmap's "delete the draft: files/segments is empty" holds
  after a purge, not a discard); a draft from before this build opens with
  its one look and sliders on every shot and its reframe track on the shots
  of the first file (`brightness` was a gain then and is an offset now, so
  the old number is converted to the offset that keeps mid-grey where it was,
  `Adjust.brightnessFromLegacyGain`; the far ends of the range drift a
  little).
  From its review round (built, the arithmetic in `FrameRulesChecks.kt` and
  `GradeChecks.kt`, nothing seen): a base shot's export chain is now the
  preview's layer for layer - graded on its own frame, its crop window cut,
  turned, fitted whole onto the compose canvas (`composeResolution`), *then*
  placed and *then* the frame's ratio or rectangle cut from that canvas - so
  a 1:1 crop plus Placement 1.6x grows past the fitted square in the file as
  on screen, and Frame 16:9 over a 1:1 crop pillarboxes the square rather
  than cutting a band from it; the pass count is unchanged, since Media3
  folds consecutive matrix transformations into one program; auto-reframe's
  window follows `FrameRules.subjectOnCanvas` in both (the subject carried
  through the crop, the quarter turn and the placement - the export probes
  each reframed file's shape for it) - check step 4's export against the
  preview with a crop and a placement on the reframed shot, and with Rotate
  90°; the Mask tool edits in place again (a PiP in the corner stays in the
  corner with its outline on it, a 9:16 frame stays 9:16), the outline layer
  walking the surface's own geometry (`ShotFrame`, `OverlayFrame`) so a drag
  moves the shape under the finger through the placement's scale; base
  surfaces are laid out at their decoded shape inside the picture's frame
  (`SurfaceDraw.aspect`), so a portrait clip cut into a landscape edit is
  pillarboxed as the file has it and its Crop window is measured on its own
  picture (the sheet probes each clip's file for the chip hold); the blurred
  backdrop is one still per shot from its middle on a one-second grid, made
  one at a time under a lock (a trim drag no longer opens a decoder per
  pointer event), pruned to 48 files, kept up across a cut and over a gap
  (the shot before the gap, from the very start, and on to the end - the
  file lays the same stretches), made from a photo's pixels for a main-track
  photo and falling back to the colour when a frame cannot be read, in both;
  a gap on a padded canvas shows the backdrop, not a black card; picking a
  Background no longer switches the ratio to 9:16 - with Original or Custom
  the chip says a shape on Ratio is needed and nothing is padded; level 0's
  Looks holds its shot while playback runs and re-picks on a scrub or a
  selection; Adjust is one chip row (thirteen sliders, then the eight wheel
  colours) over one control, Apply to all under it; a photo overlay has
  Filters and Adjust, graded on the CPU in the preview (`Grade.applyTo` on
  the pixels, vignette included, grain and bloom not) and through its image
  item in the file - check the two agree on a warm look and a vignette; a
  crop whose only content is a shape chip survives a reload; a cancelled
  crop drag closes its undo step.

- **The fixes to `docs/DEVICE_FINDINGS.md`'s open items, all of them, and the
  review of them.** Built on the desktop with no phone attached; each entry
  there under "Fixed (pending device check)" says what to do on the phone. The
  ones that lean on something only a device shows: the clock as one frame of
  Media3 gap declaring sound and then the transparent still at the edit's rate
  (`ExportPlan.pieces`, `clockLeadMs`, executed in
  `tools/jvm/ExportPlanChecks.kt`) - the failing overlay export must complete,
  the gap's black 16 px frame must not show (it is hidden as the still is, by
  `LayerSettings`), the file's frame rate must be the footage's (60 fps under a
  PiP stays 60; a whole-gap clock wrote 30), and the AAC must be at the
  camera's 48 kHz (the gap item carries a resampler to the highest rate any
  sound has; `BUILD_NOTES.md` says what that leans on); a song dragged out
  past the last shot makes the file that long, black under it, through the
  compositor; the encoder ceiling (`EncoderCeiling`) must agree with what the
  file comes out at, on 4K and on a portrait clip, and tapping two sizes
  before the first answers must leave the sheet on the second's answer
  (`ProbeGate`, executed in `tools/jvm/ProbeGateChecks.kt`); a title opening
  with its sample words selected, and Undo after typing removing it as one
  step; "Open with" reaching the running app through `onNewIntent` with the
  activity single-task, going back to an editor already open on that video
  (`OpenEditors`) rather than stacking a second one on the same draft, and
  waiting for an export to finish (`ExportsInFlight`); the preview clock
  crossing the stretch after the last shot (`PreviewRules.baseTime`) and
  stopping at the true end.

## Conventions worth not rediscovering

- **A tool's `id` is a handle, not a name.** `QuickTool.id` is in the navigation
  route, in the filename of every saved draft, and in the filename of every file
  the tool writes. Display names change freely; ids do not, or drafts people are
  in the middle of are orphaned.
- **Comments say why, not what.** The code says what. Where a line looks odd,
  the comment explains the thing that made it that way — usually a bug. Those
  comments are the only record of a lot of hard-won detail.
- **Media3's `@UnstableApi` wants `androidx.annotation.OptIn`**, not Kotlin's.
  Both compile; only one is right.
- **Anything downstream of the user's rotation measures against
  `EditorUiState.framedWidth`/`framedHeight`**, never `sourceWidth`/`sourceHeight`.
  Three separate bugs came from that one confusion.
- **The sandbox's `kotlinc` log is mostly noise, but not entirely.** "No value
  passed for parameter" is real. It was filtered out as framework noise once, and
  a broken build shipped.

## Longer form

`BUILD_NOTES.md` has the Media3 API details and the things most likely to need a
touch. `ARCHITECTURE.md` has the shape of the app.
