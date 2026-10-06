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
./gradlew lintDebug                     # Android lint. Zero errors is the state to keep it in.
```

**Lint had never been run on this project until 5 October**, and it found ten
errors, two of which were real and one of which was a locale fault of exactly
the class this codebase already knew about: `Typeface.create` handed a flag set
where it takes one of four named styles, a literal byte-order mark sitting in a
Kotlin source file, `createOnDeviceSpeechRecognizer` (API 31) guarded a function
call away from its use at a minimum of 29, and six `SDK_INT >=` branches that
cannot be false at minSdk 29 — one of which had a dead `else` that would have
published a GIF with no `RELATIVE_PATH` and no `IS_PENDING`: not in the
Pictures/Squish folder, and visible to the gallery while it was still being
written. (Said more loudly than that in the commit: the legacy
`EXTERNAL_CONTENT_URI` it also used is the same collection on API 29+, not a
wrong one.) Dead code shaped like a fallback is worse than no fallback: it says
a path exists that does not, and nobody can test what it would have done.
The four `ProduceStateDoesNotAssignValue` errors that remain are suppressed
where they are, with the reason: the check does not see the assignment in
Compose 1.7.2, which was established by writing one of them three different
ways — including a plain `value = local` — and watching lint report every one.

Four of those checkers were written *after* the thing they check for got
through. When a mistake escapes, the question is not only "what was wrong" but
"what would have caught it" — and if the answer is nothing, write the check
before writing the fix.

**And then break the code again and watch the check fail.** Copy the file to a
scratch directory, put the fault back, compile the suite against that copy
(`jc.sh` takes a path, so one file can come from anywhere) and read the failure.
Three checks written on 5 October did not bite: one asserted a symmetric pair of
keyframes that looks the same mirrored or not, one indexed a list that the old
code left one element long and threw instead of failing, and one asserted the
*shape* of an implementation that turned out to be the wrong shape — so it
passed for as long as the bug lived and failed the day it was fixed. A check
nobody has seen fail is a check nobody has tested.

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
- **After any edit to `editor/TimelinePreview.kt`, build with `clean`.** An
  incremental build of it installs and then dies on opening the editor with a
  `VerifyError` on `TimelinePreviewKt` ("copy-cat1 ... PictureTool") - seen
  twice, each time cured by `./gradlew clean assembleDebug`. A release build
  is clean and unaffected; a debug install you then test is not.
- **There are no unit tests.** `./gradlew testDebugUnitTest` succeeds by having
  nothing to run, so it proves nothing. The `tools/jvm` suites are the tests.
- **Python is not installed here**, so the `tools/check_*.py` checkers only run
  in the sandbox. On this machine `assembleDebug` is the check — and it is a
  better one for five of the six, which look for things the compiler finds
  anyway. The sixth, `check_shaders.py`, looks at assets the compiler never
  reads, so it is also a suite now (`tools/jvm/ShaderUniformChecks.kt`) and
  runs on both machines.
- **No `kotlinc` here either, but the `tools/jvm` suites still run:** the
  Kotlin 2.0.20 compiler is in the Gradle cache. With `java` from JBR 21,
  `java -cp <kotlin-compiler-embeddable;kotlin-stdlib;kotlin-reflect;kotlin-script-runtime;kotlin-daemon-embeddable;trove4j;annotations-13.0;kotlinx-coroutines-core-jvm>
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -classpath <kotlin-stdlib> -d <dir> <the suite's files from run.sh>`
  then `java -cp "<dir>;<kotlin-stdlib>" <Suite>Kt`. Every jar is under
  `~/.gradle/caches/modules-2/files-2.1/`. `sh tools/jvm/run_desktop.sh` does
  that for every suite in `run.sh` (about 50 minutes, one line each — and
  `tools/jvm/RunnerChecks.kt` fails if the two runners' lists ever part, which
  they had: four suites were in `run.sh` alone and the desktop had never run
  them). **A change to a file on the runners' shared `$TIMELINE` list is a
  change to every suite that compiles it**, and running only the suite the
  change belongs to proves nothing about the other twenty - one new reference
  from `TimedEffect.kt` broke thirteen of them while each one I ran passed.
  After touching anything on that list, run the lot. And
  `sh tools/jvm/jc.sh <Suite> <files...>` runs one. Suites that need a **jar**
  on the class path are in `sh tools/jvm/run_media3.sh`, which passes it
  through `EXTRA_CP` (every path wants `cygpath -m` first) - the voice effects
  and the level processors against Media3's real `BaseAudioProcessor`, and the
  three draft round trips (a clip's, a line's and the edit's) against org.json
  and kotlin-reflect. Pin kotlin-reflect to
  **2.0.20**, the version `jc.sh` drives the compiler and stdlib at: the cache
  holds fifteen others and the newest of them resolves nothing against a
  2.0.20 stdlib, so every reflection call reads as "unresolved reference",
  which looks exactly like a suite written wrong. `run_desktop.sh` calls
  `run_media3.sh` at its end, because left out of it "every suite passed" meant
  every suite but those, and one had stopped compiling for a day when
  `TimedEffect.kt` grew a `PolishRules` reference that *its* file list did not
  name.
  **Never edit a runner while one is running**: `sh` reads the script at byte
  offsets as it goes, so inserting a line near the top sends the running shell
  into the middle of a later one.
- **An APK older than a source file is not necessarily stale.** Gradle keys its
  tasks on content, not on time, so restoring a file byte-for-byte - which is
  what every negative test here does, `cp` the backup back after watching the
  check fail - leaves the source newer than the APK while the APK already holds
  that exact code. `find app/src/main -newer <apk> -name '*.kt'` is therefore a
  *hint* and not an answer; `git status --short` being empty plus a build that
  reports no work is the answer. The rule it protects is still right: never
  install and test without a build after the last real edit.
- **Measure speed on a release build, never a debug one.** `./gradlew
  assemblePerf` is the release build signed with the debug key; it installs
  over a debug build and keeps the drafts (but `run-as` stops working until a
  debug build goes back on). A debuggable Compose build ran the editor at 48 ms
  a frame where the release ran 14. Then `adb shell dumpsys gfxinfo
  com.squish.app reset`, play or scrub, and `dumpsys gfxinfo com.squish.app`.
  To see *where* main-thread time goes, on a debug build: `am profile start
  --sampling 1000 com.squish.app /data/local/tmp/sq.trace`, act, `am profile
  stop com.squish.app`, pull it, and `tools/jvm/TraceTop.kt` lists the
  heaviest methods (`TraceTopKt sq.trace main 40 "~"` for the app's own).
- **And nothing to *look* at a video with - no ffmpeg, no VLC, and Application
  Control refuses any binary built on the spot.** `tools/desktop/` closes that
  with three PowerShell scripts over Windows' own
  `MediaComposition.GetThumbnailsAsync`: `frames.ps1` pulls frames at even
  intervals as JPEGs, `contact_sheet.ps1` lays them in a grid, `crop.ps1` cuts a
  region out of one. Nothing is installed and nothing is compiled; its README
  has the calls. That is how the reel on 5 October was read frame by frame, and
  it is the only way to see a pixel on this machine.
- **No ffprobe here, but `tools/jvm/Mp4Probe.kt` answers what the device
  checks ask of a file** - each track's sample count, length, rate and the
  spread of its frame durations, from the MP4's own tables. Compile it alone
  the same way and run `java -cp "<dir>;<kotlin-stdlib>" Mp4ProbeKt file.mp4`.
  Pull a gallery file with `MSYS_NO_PATHCONV=1 adb exec-out "content read --uri
  content://media/external/video/media/<id>" > f.mp4` (`exec-out`, not `shell`:
  a pty turns every LF into CRLF and the file comes out bigger and broken).
- **A worktree has no `local.properties`**, so a build there wants
  `ANDROID_HOME=C:\Users\vysak\AppData\Local\Android\Sdk` in the environment.
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

The list below is by batch and is long. `docs/ROADMAP.md` §5 says what to do
**first** when a phone is actually here - thirty-eight things in order of
risk times reach, plus the clean-up this machine owes the phone. Start there,
not here.

Everything in this list is reasoned-about, not seen. Anyone who reaches a device
should work through it and then delete what holds up.

- **The third sweep's twenty-six fixes (5 October).** A sweep for the class of
  fault the user found - a control whose direction, sign or label contradicts
  what you see - over nine groups of files and three lenses, two adversarial
  refuters each. All twenty-six are fixed and none seen. The ones a device
  settles in a minute each: Track a face in the *upper* half of a clip and Pin,
  and the mask must sit on it rather than mirrored below (`Mask.centerAt`); drag
  a corner bracket of the hand-drawn crop and two edges must move; open Crop on
  a shot, scrub to the middle, tap a thing and Track - it must follow *that*
  frame's thing; with the keyboard up, tap bare picture just past a corner of a
  text box and nothing must be deleted; turn "Snap to markers and beats" off and
  a drag of the strip must still hold on a cut with a tick; swipe the Curves
  square and the sheet behind it must scroll; in Snip, trim to three seconds and
  the *start* bar must still drag; and a keyframe diamond a finger's width
  inside a selected clip's edge must park the playhead when tapped. The full
  list is in `docs/DEVICE_FINDINGS.md`.

- **The rest of 5 October, after the phone was unplugged at about 07:00.** All
  of it is executed on the JVM and none of it has been seen or heard: the
  three-millisecond release that stops a sound effect clicking where it meets
  its cut, and the rewrite of the effects already on disk that carries it to
  anyone who is not installing for the first time (`MusicLibrary`'s
  `EFFECT_BUILD`); "Sound on every cut" choosing, per join, the first variant
  short enough not to lie across the shot before it; the AAC track being
  budgeted in the size estimate and the size target whether or not anything is
  heard; `hasAnyAudio` reading the whole main track rather than the lead file;
  the default transition *length* on Settings, which was a stored value with no
  way to set it; and the Frame rate hint no longer saying frames are dropped
  when the rate chosen is the rate the footage runs at. What a device answers:
  that the cut sounds no longer tick, that a fitted export lands under its
  limit now that the track is in the budget, and that the Settings row behaves.

- **The compact muxer (5 October), first on this list because it is on the path
  of every export.** `media/CompactMuxer.kt` turns Media3's streamable output
  off, because the in-app muxer's 400 KB reserved moov space is never trimmed
  and was 69% of a measured three-second export (`docs/DEVICE_FINDINGS.md`).
  The change is one builder call and `DefaultMuxer` is a pure delegate over the
  same factory, but **no file has been written with it**. First thing on a
  device: render anything, check it plays in the gallery and in a share, and
  that `Mp4Probe` now accounts for nearly the whole file. The same factory is
  on the proxy copy and on every rendered still.

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
- **Editing by transcript (3 October).** Built on the desktop; its two pure
  pieces are executed (`tools/jvm/TranscriptChecks.kt`, `SpanRemovalChecks.kt`)
  and **the panel has not been driven** - the test footage on 4 October had no
  speech in it, and a line read aloud by the phone's own voice could not be
  captioned back. What a device still has to answer: auto-caption a talking
  head, open Text → "Edit by transcript", tap two words and Delete, and the
  picture, the sound and the captions all lose that stretch as one undo step;
  "Take out every um and uh" does every run at once; a line typed by hand shows
  underlined and takes its whole line.
- **Fit the shots to the song (4 October).** Built on the desktop; the joins are
  executed (`tools/jvm/BeatSpreadChecks.kt`). Not seen: twenty clips and a
  thirty-second song, every join on a dot and the edit ending with the music.
- **The last five items of `docs/COMPETITORS.md` (4 October, dawn to morning) -
  shapes and arrows, twenty-eight more templates, a mask's shape keyed, "Use the
  liveliest bit" and sound stickers - were built and then driven on the phone**;
  `docs/DEVICE_FINDINGS.md`'s last section says what was seen, including the
  shape and the keyed mask in an exported file. The arithmetic of all five is
  executed on the JVM (`ShapeChecks`, `TemplateChecks`, `MaskKeyChecks`,
  `LivelinessChecks`, `SoundStickerChecks`). What is still unseen from them:
  - a sound sticker's noise **by ear**, in the preview or the file;
  - a **Solid** shape, and a shape turned or on a 9:16 crop;
  - a keyed mask on a **retimed** clip - the strip puts its diamonds through
    `Clip.playedAt`, and only a 2x shot shows whether that is right;
  - what the per-frame uniform reload of a keyed mask costs on four surfaces;
  - "Use the liveliest bit" on a *handheld* shot whose first seconds are a hand
    settling, which is the case the discounted ends exist for;
  - applying one of the twenty-eight new templates.
- **Curves, LUT import, blend modes, keyed filter strength and the safe-area
  guides were all driven on the phone on 4 October** and work in the preview and
  in the file - see `docs/DEVICE_FINDINGS.md`, which also records the three
  faults that round found. The two things left open that morning were both
  closed at dawn on 4 October: a 33-cube imports and swaps correctly (so the ES2
  tile atlas is right at a cube size other than 8), and a photo overlay carrying
  a LUT matches the video beside it - the one screen where the CPU copy and the
  shader meet - measured off a screencap rather than judged by eye, both paths
  swapping red for blue and leaving green alone to within a unit.
- **The Curves tool (3 October).** The arithmetic is executed on the JVM
  (`tools/jvm/ToneCurveChecks.kt` and the curve part of `GradeChecks.kt`), and a
  lifted midpoint was seen to brighten the picture on the phone on 4 October, so
  the table, its texture and the shader's lookup are right on this driver. Still
  unseen: that an S-curve on screen is the S-curve in the **exported file**;
  that a graded photo overlay and the video beside it agree on the same *curve*
  (the LUT leg of that meeting was measured at dawn on 4 October and agrees, but
  the curve is the other leg - a table sampled in Kotlin against a texture
  looked up in the shader); that dragging a point is smooth and does not stall
  the preview; and that a point
  dragged onto its neighbour comes off while the two ends cannot be dragged
  sideways. A draft written at version 14 reads its curve back - seen.
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
  rather than a ten-minute decode held in memory). B9 left volume keyframes and
  ducking under speech unbuilt "if budget remains"; both were built later and
  this line used to say they were not - volume keys are `Clip.volumeKeys`, keyed
  from the two Volume sheets and drawn on the strip (B13), and Duck under speech
  is `timeline/DuckRules.kt` (30 September).

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

- **Batch B11 (clip operations), all of it.** Built on the desktop with no
  phone attached. The decisions - a freeze cut into a shot and the track
  closing round it, a replacement keeping the window and everything on it, a
  reversed render under a clip and the original back (trimmed meanwhile or
  not), the curve mirrored, the preview's and the file's turn being the same
  turn, attributes carried as shapes, and several clips toggled, deleted and
  carried as one - are executed on the JVM (`tools/jvm/ClipOpsChecks.kt`), and
  the toolbar's new tools in `ToolRulesChecks.kt`; nothing has been seen.
  Script in `docs/ROADMAP.md` §4 under B11, plus what only a device answers:
  Rotate on a clip turns it a quarter clockwise on screen *and* in the file
  (`CompositionFactory.turn` passes 270 for one turn, since Media3's degrees
  run the other way - the same disagreement `PreviewBox.screenRotation`
  settles for the edit-wide rotation; if the two disagree, `ExportPlan.turnDegrees`
  is the one number to flip), a turned landscape shot stands pillarboxed in
  both (`TimelinePreview.turnedInside` fits the view by the turned shape, the
  export's Presentation fits the turned frame), and Mirror flips left to right
  in both with the mirror applied before the turn (`ScaleAndRotateTransformation`
  with scale -1 is the one Media3 call this leans on - check it accepts a
  negative scale and does not refuse the frame); a photo overlay turns and
  mirrors too (its `Image` is laid out the same way); the overlay box on a
  turned PiP is the turned shape. Freeze grabs the frame under the playhead
  at full size (`StillClips.freezeFrame`, `OPTION_CLOSEST`, rendered at the
  frame's own size up to 4K and only then at 1080 - check a freeze of 4K
  footage is as sharp as the frames either side of it in the file) and lands
  a 3 s "Freeze" still, selected, with the shot's placement, mask (a tracked
  one standing where the track had it), key, mirror and turn - check the
  frozen picture is the frame that was on screen, not the nearest keyframe,
  and that it is upright for a portrait clip; on an overlay row with no room
  after it, it says so; the still lands where its frame is when it is ready,
  so a trim made while "Preparing" showed does not lose it, and trimmed out
  altogether it says so. Reverse renders the window backwards
  with the platform codecs (`ReverseRenderer`: keyframe runs decoded forward
  and handed to the encoder last frame first, YUV never leaving YUV, a spool
  to disk past a quarter of the heap; the sound reversed in place and
  AAC-encoded first; 10-bit and HDR files refused by their tags or by the
  decoder's first picture) into `files/reversed/` and lands as one step when
  done - beneath a gesture under way, like Stabilize - a card saying how far
  along it is with a Cancel; the stabilizer's keys and a mask's track go
  onto the render's clock mirrored and come back the same way, the person
  masks come off and go back with Reverse again - check the file plays
  backwards with its sound backwards, that a portrait clip comes out the
  right way up (the rotation tag is copied, the pixels are not turned), that
  a clip trimmed while the render ran keeps the frames it showed and a clip
  cut in two meanwhile gets the render on both halves, that Reverse again
  puts the original back at once, that Cancel and leaving the editor
  mid-render leave no `.part` file and say nothing, that a stabilized shot
  reversed is steady, and that a clip over three minutes is refused with the
  message rather than attempted. Replace
  opens the picker, then a sheet with a frame of the new file and "Start at"
  (the old clip's own in-point when the file has that much, which is what its
  Reset goes back to); Done puts it in
  keeping the window, place, speed, keys and settings; a file too short says
  so with both lengths; a photo picked goes straight in as a still; tapping
  another shot while the sheet is up closes it. A photo, blank or freeze on
  a video track is not offered Freeze, Reverse, Replace, Stabilize or Track.
  Copy attributes and Paste attributes carry level, fades, voice, curve
  (refitted to the span), opacity, placement, keys (refitted to the length),
  mask (its shape, not its track), key colour, mirror and turn - a sound
  takes the sound ones, a photo none of the sound ones, and a sound's pasted
  onto a picture changes only its sound; Paste is greyed until something is
  copied. Delete is the fifth tool on every clip's row. Select more turns the
  button orange, reads "Done selecting" while on, and taps on the strip add
  to the set (drawn selected together, no row folded) as does a tap on an
  overlay's box on the picture; pressing it again keeps the set; the row is
  then Select more and Delete only; Delete takes all as one step; a shot
  lifted from among the selected takes them with it in their order (one
  lifted from outside the set moves alone and joins it) and a carried sound
  or overlay slides the selected sounds, overlays and lines together,
  stopping the whole group against a wall on an overlay row. A draft from
  before this build opens as it was (the new fields read as unset); one
  saved by this build writes version 13.

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
  From the merge of B11 and B12 (built, `ClipOpsChecks.kt` extended, nothing
  seen): a clip's own turn comes *before* its crop in both the file
  (`CompositionFactory.turn`, then `ClipCropEffect`, then the edit's
  rotation) and the preview (`ShotFrame`/`OverlayFrame` fit the turned shape,
  the view turns inside it with `turnedInside`, the crop window is cut from
  that), so a crop is a window on the footage as seen - the Crop tool draws
  its window upright over the turned picture, while the mask outline turns
  with the picture, since the mask is cut before the turn in both. Check a
  Rotate 90 plus a 1:1 crop on one shot lines up between the preview and the
  file, and that the mask outline sits on the shape on a turned clip. Freeze
  and Paste attributes carry the look, the sliders and the crop along with
  the mirror and turn (the frame is grabbed from the file plain).

- **Batch B13 (animation, speed and transitions), all of it.** Built on the
  desktop with no phone attached. The arithmetic - a number keyed over a clip
  and the slider keying it, the arrival / leaving / loop over the placement
  keys and through a cut and a trim, every transition compositing to the same
  picture on either roll and to what the preview stacks, the stabilizer solved
  again from its kept measurement, where the blended frames go - is executed
  on the JVM (`tools/jvm/AnimationChecks.kt`, `ExportPlanChecks.kt`,
  `StabilizerChecks.kt`, `FrameBlendChecks.kt`, `RampChecks.kt`); nothing has
  been seen or heard. Script in `docs/ROADMAP.md` §4 under B13, plus what only
  a device answers: the preview now draws every transition from
  `ExportPlan.blend` (the dip to black is the outgoing shot's alpha over the
  black canvas, no veil), so check Dissolve, Dip and Slide still look as they
  did and the twelve new kinds (Dip to white, Slide right/up/down, Push, Wipe
  left, Zoom, Jitter, Flicker, Flash, Glow) against a file, on both rolls of a
  three-shot chain; a keyed opacity and a Fade in on a *main-track* shot
  export through `TransitionEffect`'s `uOpaque` darkening on the cuts-only
  path and as alpha over black on the composited path - the two must look the
  same; an overlay's transition (offered when another overlay ends where it
  starts on its row, marked on the strip like a shot's join, and dropped -
  `withOverlayTransitionsFitted` - when that join goes) plays over its head
  in the preview and the file as one shot's arrival (`ExportPlan.arrival`:
  the dips, the flash, the jitter and the flicker run their second half over
  the whole of it, since there is no old shot to hand the first half to), and
  its tiles show that one shot arriving; the sound's pitch - Media3's
  one-argument speed change was found to pitch with the rate, so the file
  used to disagree with the preview on every retimed clip - is now Media3's
  own `SpeedChangingAudioProcessor(provider, false, shouldMaintainPitch)`,
  which must hold a voice at 0.5x and 2x and through a Hero ramp with no gap
  or click at a tread boundary, and "Pitch follows speed" must tape-pitch
  both the preview (`PlaybackParameters` pitch) and the file; the speed slider
  to 100x exports at 100x and previews at eight (`PreviewRules.playerRate`,
  the sink's ceiling: the strip and the file are the clip's rate, the preview
  runs slower and must not stall); dragging a point on the curve moves it
  under the finger with no sideways slide on a vertical drag, a tap adds one
  at the curve's own rate there, and a swipe that starts off a point scrolls
  the sheet; "Blend frames" on a 0.25x clip has now been rendered and probed
  (3 October evening in `docs/DEVICE_FINDINGS.md`): 1377 even frames at
  30.001 fps against 345 at 7.500 fps with it off, no stall and no
  `VideoFrameProcessingException` - what is still unjudged is whether the
  blended frames *look* like motion blur rather than a cross-fade; a Strength
  drag on a stabilized clip re-solves that clip live with no "Measuring…"
  and leaves every other stabilized clip as it was (the strength is the
  clip's, `Clip.stabilizeStrength`); the effect tiles and
  the transition tiles animate without dropping the sheet's frame rate; the
  keyframe button on Placement, Opacity and the two Volume sheets lights up
  under a key and the slider then keys the playhead, and every opacity and
  level key is a diamond on the strip (violet, cyan) that parks the playhead
  when tapped; Mute on the Clip sound sheet is a switch over the level
  (`Clip.muted`), so a ducked shot mutes and comes back with its duck, the
  slider moved above nothing unmutes, and a draft that wrote Mute as a level
  of nothing still reads muted; an effect's own knob
  (Speed on Shake, Beats on Punch…) reads in the preview and the file;
  every vertical transition (Slide up, Slide down, and any kept band) is the
  same way up in the file as in the preview (`Draw.shaderUniforms` turns the
  draw over for the texture, checked in `ExportPlanChecks.shaderPixel`); a
  Flash or Dip to white on a picture-in-picture whitens the
  picture-in-picture alone in the preview as in the file (the white is
  blended `SrcAtop` inside an offscreen layer - check a keyed overlay's
  hole stays clear through a flash, and that a TextureView inside such a
  layer still draws).
  Not built: mask and filter keyframes - a mask already moves on its Track,
  and per-clip filters arrive with B12, whose files they would key; the
  `ValueKey` track built here is what they would use - and keyframes on a
  line of words or a sticker, which are not clips and have their own
  arrival, leaving and loop from B10.
  From the merge over B11 and B12 (built, `ClipOpsChecks.kt` extended,
  nothing seen): the dip to black is no longer a veil over the canvas but
  each shot's own alpha from `ExportPlan.blend`, so on a padded canvas the
  backdrop shows through a dip as B12 wanted; a transition's slide, zoom,
  cut and white are drawn on the surface *outside* its turn and crop
  (`VideoSurface`'s layer, then `ShotFrame` inside it), as the file applies
  `TransitionEffect` after the turn, the crop and the fit - check a Slide
  on a turned, cropped shot moves the cropped picture whole; the overlay
  row offers Transition beside Filters, Adjust, Crop, Rotate and Mirror;
  Paste attributes carries the arrival, leaving and loop with their
  lengths, the frame blend and the pitch switch (the keyed opacity and
  level tracks stay with their clip, as a mask's track does).

- **Batch B14 (export UX and policy), all of it.** Built on the desktop with
  no phone attached. The arithmetic - the rate a choice gives, what a quality
  step and HEVC do to the bitrate, which sizes the encoder ceiling greys, where
  a remembered default lands, the size a fitted export is solved to, and how a
  missed limit is chased - is executed on the JVM
  (`tools/jvm/ExportSettingsChecks.kt`); nothing has been rendered. Script in
  `docs/ROADMAP.md` §4 under B14, plus what only a device answers: Stop on the
  progress card asks and leaves no file in `exports/`; the export runs under a
  foreground service (`ExportService`, type media processing on Android 15,
  data sync before) - lock the screen during a long 4K export and the
  notification must show the percentage and the file must complete; the
  first Render asks for notifications on Android 13+ and a refusal still
  renders; the fps row has now been probed on both kinds (3 October evening in
  `docs/DEVICE_FINDINGS.md`): 24 and 25 come out exactly even and 60 asked of
  30 fps footage passes every frame; the HEVC toggle appears only
  where `EncoderUtil` finds an encoder, the file is HEVC (`SquishExport: done
  … mime=video/hevc` in logcat) and about a third smaller; sizes above the
  encoder's ceiling are greyed on the sheet (the S23 for the 4K test);
  an HLG source exports SDR by default with sane colours (`HDR_MODE_TONE_MAP_
  HDR_TO_SDR_USING_OPEN_GL`) and "Keep HDR" writes HDR HEVC on a cuts-only
  edit - the toggle is disabled with a note on a layered one, and Media3 falls
  back to converting where no HDR encoder exists rather than failing (read in
  the 1.11.1 bytecode, `VideoEncoderWrapper`); a plain Snip keeps its stream,
  HDR included; Fit to 16 MB on a minute of 4K comes out 720p or below and under
  16 MB, and one that overshoots by more than 2% shows the "Keep this one /
  Try again, tighter" card with the first file already in the gallery; the
  done screen plays the file (a `VideoPreviewSheet` on the private copy) and
  states frame, length, rate and size measured off the file itself, with the
  before/after pill only on Squeeze's; Sound only writes an .m4a to Music and
  its done screen plays it as a wave; a main-track photo exports as the picture
  it was made from (`StillClips.originalImage`, a JPEG beside the still under
  `files/stills/`, at up to 3840 px) - check a 4K export of a 12 MP photo is
  sharp where it used to be 1080p upscaled, that a photo first in a cuts-only
  edit still starts (an image item with no sound track in a sequence declared
  with sound), and that a photo with a mask or a transition still exports as
  it did; the last export's settings are the next new project's defaults
  (SharedPreferences "export_defaults", size capped at the footage's) while a
  reopened draft keeps its own; the codec-mute line reads on the sheet for a
  DTS source. Not built, by choice: a Stop action on the notification (it
  cannot ask first).
  From its review round (built, the arithmetic in `ExportSettingsChecks.kt`
  and `StillRulesChecks.kt`, nothing seen): on Android 15 the service asks
  the framework for the media-processing type directly - androidx.core
  1.13.1's `ServiceCompat` masks that type to NONE, which a targetSdk 35 app
  is refused, so through it the export ran with no notification at all -
  check `SquishExport: could not go foreground` never logs on the S23 and the
  notification stands with the screen locked; the codec probe starts on
  opening the clip and Render waits for it, so a draft's HEVC or Keep HDR is
  written as HEVC even when tapped at once; HEVC and Keep HDR read one
  answer (`effectiveKeepHdr`): on a layered HLG edit both say converted and
  HEVC can be turned off, and the render tone-maps; a photo on the main track
  drags out to ten minutes (`StillRules.MAX_MS`) - lifting the finger past
  the rendering's end renders the still again in half-minute steps
  (`StillClips.extended`, the picture copied beside it) and swaps the file in;
  until it lands, and this is the thing to watch, the preview plays the
  ten-second file to its end and holds its last frame while the clock runs
  on at wall time (`PreviewEngine.tick` drives the clock from a READY,
  playing player only, and `watchStalls` counts BUFFERING and a frozen READY,
  not ENDED) - play across a photo dragged to 40 s straight after the drag,
  and scrub into its second half, and neither may stick; the export writes
  the picture for the clip's whole run either way; with Keep HDR on a
  cuts-only HLG edit that has a photo on the main track, the JPEG goes into
  an HLG graph as an SDR bitmap - a combination Media3's bitmap input may
  refuse (`VideoFrameProcessingException` at the first photo frame) where
  the old SDR clip did not, so check it, with Keep HDR off as the fallback
  that must work; a fitted run that misses is chased at a bitrate the size is
  solved from too, so a second run can step down a size rather than starve
  (`fitOutputPForBitrate`), and the tightened scale lives for that one run;
  back on the "Keep this one / Try again, tighter" card keeps the file and
  lands on the done screen; "Back to editor" from the done screen lands on
  the bare editor; 30 chosen on 60 fps footage at Original size halves the
  estimate and the file (`bitrateForFrame` takes the source rate); Fit to a
  size is not remembered as a default (its MB is); the sheet holds one height
  while its rows show, so a chip tapped stays under the finger when its hint
  grows; a Squeeze asks for notifications too, and the card promises one only
  when it may post; the Quality chips read Lower · Standard · Higher.
  From the merge over B11, B12 and B13 (built, the fit arithmetic in
  `ExportSettingsChecks.kt`, nothing seen): a fitted export on a padded
  canvas (B12) solves its size on the canvas's own frame
  (`EditorUiState.fittedOutputP`), not on the crop the canvas replaces; a
  main-track photo written as an image item carries B12's grade and crop
  and B11's mirror and turn on that item - check a warm, turned photo on
  the main track comes out graded and turned at full size; a freeze (B11)
  is a rendered clip with no picture beside it, so it takes the video path
  as before; the rate chosen on the sheet drops frames after B13's frame
  blend (`speedEffects`, then `frameDrop`), so the blended frames are the
  ones kept.

- **Batch B15 (shell and project management), all of it.** Built on the
  desktop with no phone attached. The decisions - a project's id and the name
  a duplicate takes, where a cover frame is taken, which grants a purge lets
  go of, what a storage clear may take, and where a quick trim's handles may
  land - are executed on the JVM (`tools/jvm/ProjectRulesChecks.kt`,
  `TrimRulesChecks.kt`); nothing of the screens has been seen. Script in
  `docs/ROADMAP.md` §4 under B15, plus what only a device answers: a project
  is its own id now (`EditorUiState.projectId`, the draft's slot, in the
  editor's route), staged on disk before the editor opens
  (`ProjectAutosave.stageStart`) and saved from the moment it is made - the
  recovery banner, "Start a new project", the untouched-clip rule and
  `OpenEditors` are gone with projects keyed by video, so check that a draft
  saved by a build before this one (slot "p" and a hash) still opens from the
  grid and saves back into the same file, and that `am kill` under a fresh
  project with no save yet still opens it on its files; New project takes
  photos and videos together and lays them end to end with Settings' ratio,
  still length and transition on them (`EditorViewModel.loadFresh`), Browse
  files takes document URIs, and Record writes under `files/imports/` through
  the provider (`file_paths.xml`); a share whose grant cannot be kept is
  copied into `files/imports/` when the project opens (`MediaAccess.importCopy`
  - the copy is on the loading spinner, so a gigabyte share sits there a
  while); a file deleted from the gallery is named on opening the project and
  the Relink card puts a picked file under every clip that played it as one
  undo step (`EditorViewModel.relink`; the placeholder clips are the strip's
  ordinary clips drawn black); exports are stored once - the private copy is
  deleted after the gallery copy reads back the same length
  (`GallerySaver.retire`) and the library, the done screen and the detail
  screen play, share and delete the gallery copy (`ExportRecord.mediaUri`,
  `GallerySaver.remove`, which a reinstall's rows refuse) - check a MediaStore
  URI plays in `VideoPreviewSheet` and shares to WhatsApp with the grant
  flag; the dashboard's covers and the library's thumbnails come through
  `ThumbnailCache` (memory, then `cache/thumbs/` JPEGs, one decode at a time)
  so an 80-item library scrolls without re-decoding; the history list is read
  on its writer thread rather than the dashboard's first frame; Settings'
  "Ticks when snapping" is a gated `LocalHapticFeedback` provided at the
  root, so every snap in the strip and the box obeys it without knowing;
  "Keep the screen on while editing" holds the window flag while the editor
  is up; the storage card clears stills, renders, takes and masks only of
  what no draft names (`StorageRules.unreferenced`, the still's `.jpg`
  companion kept with it); backups exclude the drafts and everything an edit
  made for itself; the quick trim is a filmstrip with two handles landing on
  frame boundaries and a frame button either side (`TrimStrip`, on
  `ThumbnailExtractor.extractFrames`) - check a handle follows the finger
  under a snap and the preview jumps to the handle moved; Stitch's row action
  reads "Add clips" and "Open in editor" hands the whole ordered list over as
  one project; a quick tool asks for its picker once per session
  (`rememberSaveable`); the coach marks on the dashboard and the editor show
  once each (SharedPreferences "settings"); the row buttons on the drafts,
  library and merge lists are 48 dp with names, and the tool tiles grow with
  the font rather than clipping.
  From its review round (built, the drag arithmetic in `TrimRulesChecks.kt`,
  nothing seen): a fitted export is measured *before* `GallerySaver.retire`
  deletes the private copy - measured after, an overshoot read as nothing and
  B14's "Keep this one / Try again, tighter" card could never show; the trim
  handles read their callbacks through `rememberUpdatedState` and measure a
  drag from where the handle stood when the finger landed plus the whole
  travel (`TrimRules.draggedTo`) - each event alone was under half a frame
  and rounded back, so a slow drag never moved and a fast one snapped back
  to its start; the bars sit inside the kept stretch with 48 dp targets
  reaching inward, so both are whole at the full range; a missing file's
  clips are hatched with a "Missing" badge on the strip and named on the
  Relink card, a missing sound's Relink opens the file browser on audio, a
  picked photo is rendered to a still first, relinking the source re-probes
  the edit's shape (opened on an unreadable source it was measured against
  0x0 - `applyDraft` now takes the shape from the first main shot that
  reads), and Undo of a Relink brings the card back (`refreshMissingMedia`
  over the files found unreadable this session; the source URI itself is not
  in the snapshot, so it stays on the replacement, which changes nothing the
  eye can see); the dashboard is a `LazyColumn` with the selection bar pinned
  above it, a multi-select Delete's Undo restores every project it binned,
  the card menu offers Preview and "Earlier version" (the ten-minute
  snapshot, which had become unreachable when projects left the drafts
  list), one word - Delete - is used for the bin everywhere, the rename
  dialog is the editor's (`ui/components/RenameDialog.kt`; the sidecar
  carries `name` apart from `title`, so Save with the field blank leaves an
  unnamed project unnamed), a Record's file path is saved state so a take
  survives the camera killing the process, a project staged but not yet
  saved is listed ("not opened yet") rather than lost, and the loading
  screen counts the photos still rendering; clearing exports from Settings
  forgets the library rows that had no gallery copy; a staged project's
  files count for `referencedUris`, so a purge cannot release a grant it is
  about to open; Freeze is 3 s again (Settings' photo length is for photos).

- **Batch B16 (editor polish), all of it.** Built on the desktop with no phone
  attached. The decisions - which curve chip and which move chip a clip is on,
  read back off its points and keys since neither is stored by name, and the
  proxy notice's percentage - are executed on the JVM
  (`tools/jvm/PolishRulesChecks.kt`); nothing has been seen. Script in
  `docs/ROADMAP.md` §4 under B16, plus what only a device answers: the proxy
  notice reads "Building a light preview copy · 37%" and climbs (`ProxyEngine`
  polls `Transformer.getProgress` on the main looper every half second - check
  the percentage moves and the poll stops when the copy lands or the editor is
  left); the Speed sheet's curve chip lights after a tap and goes out when a
  point is dragged, and the line under the chips is the lit chip's own; the
  same for the Moves chips on Animation, and a preset survives a frame trimmed
  off the tail; Normal lights at 1x only - a lit chip must be one a tap leaves
  alone, and it lit for any flat rate, so a 2x shot read as "Normal" and a tap
  on the lit chip dropped it to 1x, rippling the track - and a flat 2x lights
  no chip, the line reading "One rate, 2x…"; the keyframe button and the key
  rows show a vector diamond
  (`KeyframeGlyph`) at the row's size, not the font's "◆"; Crop's Flip buttons
  carry the swap icons; a pinned line or mask on Track shows a tick icon;
  Cutout on a *main-track* shot offers neither Key green nor Cut out - a hole
  in the base shows black in the preview even over a padded canvas, since the
  base surface's chain ends in the effects pass, which writes alpha 1
  (`squish_fx_es2.glsl`), while the file would show the backdrop, so offering
  it there would be a preview the file disagrees with; the person over a
  colour is the Colour fill - but "Float this clip" (`FloatOffer`), which is
  `switchToOverlay(keepPlacement = true)`: the shot keeps its placement and
  its keys and the next shot slides in under it (the toolbar's To overlay
  still lands in the corner, placement reset) - check it lands the shot on an
  overlay row selected, full frame where it was, with its Push in still on it,
  and the Cutout sheet then shows the key buttons; that with every row taken
  the "rows are taken" failure shows instead; and that the button is not
  offered on the last shot or the only shot (`OverlayRules.floatsOverAShot`:
  nothing would be under it - the only shot floated used to empty the video
  track), nor under Blur or Colour; a shot that already carries a key or Cut
  out (pasted, or an old draft) keeps every control - swatch, Pick, the
  sliders, Turn off - with the float offer under them; the panel under the
  Chroma key chip is headed "Chroma key" (it read "Green screen"); the effects
  library's placed-effect cards have no Start here / End here (the strip's
  handles retime an effect, and the card says to close the sheet and tap the
  effect first, since selecting an effect closes a level-0 sheet and the
  handles draw only on the selected effect); the Sync nudges read "−1 frame"
  and "−10 ms", two rows of two so the frame labels do not wrap at the larger
  font sizes; the first-open gesture hint is B15's editor coach mark alone
  (B16 had its own "Getting around" card, dropped in the merge over B15 since
  the two said the same three things - check one card shows, not two); and the
  preview's per-tick work no longer builds a list to find the clock clip or
  re-reads a surface's shader values on a tick where nothing changed
  (`applyLive` returns early on the same clip, loupe state and effects list) -
  watch that a grade, mask, key or effect edit still reaches the picture at
  once, paused and playing, and that the loupe still lifts the key while it is
  up.
- **Two transitions off the reel, read frame by frame (5 October).** Built on
  the desktop with no phone attached; the arithmetic is executed in
  `tools/jvm/ExportPlanChecks.kt` and negative-tested. **Burn out** (the old
  shot turning white and hanging over the new one as a thinning ghost - not the
  Flash, which hides both) and **Blur** (a focus pull through the cut). The
  blur is `ExportPlan.Draw.blur`, the nine taps a fraction of the frame apart -
  the same ring and the same number as the effects library's own Blur. It is a
  uniform on `TransitionEffect` in the file, and in the preview it goes to the
  surface's *own* effects pass rather than to a Compose layer, which cannot
  blur a TextureView without an API-31 RenderEffect. What a device answers: that
  a Blur join softens on screen as it does in the file (the preview softens the
  decoded picture, the file the finished canvas, so on a shot cropped or placed
  much smaller it should read a little wider on screen - by how much?); that a
  Blur *scrubbed* through rather than played moves on the screen at all (the
  softness is a uniform, so `PreviewEngine.remember` asks for a redraw when it
  changes); and that a Burn out over a padded canvas or a keyed overlay whitens
  the picture and not the hole. `docs/COMPETITORS.md` §4 has the reading.
- **Sweep ten, over undo steps, state after a process death, locale and
  accessibility (6 October).** Four cross-cutting properties rather than a
  layer, which is why nothing had looked at them: twenty-seven findings, two
  refuters each. `docs/DEVICE_FINDINGS.md` lists them and ends with the nine
  things a device has to answer. The ones that reach furthest: **a pick
  delivered before the draft was read blanked the whole edit** - killed behind
  the photo picker, the result is dispatched while the launcher's effect
  commits, so the add landed on an empty timeline, vanished when the draft was
  applied, and left an undo step whose "before" was that empty edit, one tap
  from wiping everything and one autosave tick from writing it to disk (Replace
  and Relink already waited through `onceOpened`; the other four did not);
  **a main-track photo's longer rendering was lost to any undo**, because which
  file plays a clip went into the live state alone and nothing ever asks for the
  render again (`UndoStack.amendAll` now writes it into every state the history
  holds); **every ruler tick read nine characters on an Arabic phone**, because
  five call sites took ".000" off a locale-formatted timecode with an ASCII
  literal - the inverse of the trap below, and the reason the pair is worth
  stating twice; **a whole name in two characters was thrown away** (海滩.mp4
  became "Edit · 6 Oct", the same mistake the captions code had already met and
  written down); **an imported .srt in anything but UTF-8 came in full of
  U+FFFD** while reporting success, because the timing lines are ASCII; **both
  strips would have been drawn mirrored on an RTL phone**, every clip and handle
  placed by `offset` against a playhead and drag deltas that are not - the same
  class as the fault that started these sweeps; and **nothing in the app could
  be coloured, or told what it was set to, without looking** - sixty-one
  swatches with no name and no state, six picker grids marking a choice with a
  border alone, and three grading wheels that a screen reader could not focus at
  all.
- **Sweep nine, over the audio analysis, the stills and thumbnails, the Compose
  drawing layer and the export screens (6 October).** Seventy-eight agents;
  twenty-two distinct faults, all fixed and none seen. `docs/DEVICE_FINDINGS.md`
  lists them and ends with the eight things a device has to answer. The ones
  with the longest reach: **the whole waveform layer took the decoder's PCM
  layout from the container** rather than from the decoder, with no
  `INFO_OUTPUT_FORMAT_CHANGED` branch at all (that constant is -2, so it fell
  through `outIndex >= 0` unnoticed) - so on HE-AAC, where SBR doubles the
  output rate, a minute of audio read as four and the strip drew a quarter of
  the file stretched across the clip, while `ReverseRenderer` had always read it
  correctly; **a 30-second talking head trimmed out of a long recording had no
  speech in it**, because the segmenter's gate measured "loud" as the 95th
  percentile of every frame, which asks whether a twentieth of the file is loud
  rather than whether anything is; **an export of a padded canvas with more than
  49 stretches deleted its own backdrops** while its plan still named them;
  **Fit to 16 MB could not be met past about five minutes and said nothing but
  "Try again, tighter"**, which re-rendered the identical file and published
  another copy to the gallery each time; and **a panorama as a photo overlay was
  decoded whole** - 57 MB for a picture kept at 5.9 - with the OutOfMemoryError
  swallowed, so the overlay was silently refused after taking the heap down.
- **Sweep eight, over the text, vision, online and persistence layers
  (5 October, night).** The four layers no sweep had been over. Ninety-two
  agents over twelve hunts; `docs/DEVICE_FINDINGS.md`'s last section lists what
  it found and ends with the eight things a device has to answer. It found the
  two worst faults of the day, both invisible from inside the app: **sixteen
  captions could not be exported at all** (every caption, sticker and shape
  went into one `OverlayEffect`, and `OverlayShaderProgram` refuses more than
  fifteen in one instance - two minutes of auto-captioned talking is thirty
  lines, and the preview showed them all), and **on an Arabic, Persian,
  Burmese, Bengali or Nepali phone "Export subtitles" wrote a file nothing can
  read**, this app included, because `format` with no locale emits that
  locale's digits and the parser's `\d` is ASCII-only. Also worth remembering:
  **the tracker's fine pass read `bestX + dx` while assigning `bestX += dx`**,
  so it walked off the place it was refining and compounded frame to frame;
  **a caption's eighth word arrived on the seventh's beat** because a count was
  laundered through a Float fraction; and **editing by transcript could take the
  editor down** when an edit from outside the panel shortened the word list.
- **Sweep seven, over the shared components, the effect wrappers and the audio
  engine (5 October, night).** The layer under *everything*: the composables
  every sheet is built out of, the Media3 wrappers, the colour pipeline's CPU
  copy and the audio processors. Eighty-seven agents over fifteen hunts.
  `docs/DEVICE_FINDINGS.md`'s last section lists what it found and ends with the
  eight things a device has to answer. The ones that would show first: **every
  switch in the app said "switch" and never said on or off** (`Role.Switch` on a
  `clickable` names the node; only `toggleable` puts a state on it, and all nine
  switches go through the one control); **Enhance made room hiss 1.4x louder for
  the first three seconds** (the noise floor had no time constant downward, so it
  was dragged to its minimum on the first sample and `INITIAL_FLOOR` never
  survived one - and Media3 flushes the processor on every seek and every clip);
  **a clip's level reached the voice from opposite sides in the preview and the
  export**, which for a tanh voice is a difference of timbre, not loudness
  (Megaphone at half level, 18.5% of RMS apart - the fader goes after the insert
  on both sides now); and **a photo overlay's vignette ran on the byte-clamped
  colour** where the shader multiplies the float it is still carrying, so
  anything lifted past white was flattened to 255 and then darkened.
- **Sweep six, over the timeline model, the preview engine and the shell
  (5 October, evening).** The layer under the commands: what every tool reaches.
  `docs/DEVICE_FINDINGS.md`'s last section lists what it found and ends with the
  eight things a device has to answer. The ones that would show first: **a
  stretch taken out over a gap moved the sound further than the picture** (the
  other rows came back by the stretch's length, the main track by what it
  actually lost); **changing a blended still's mode or opacity did not reach the
  preview** until the shot under it changed, because the held still was keyed on
  clip *ids* and an edit makes a new clip with the same id; **the safe-area guide
  was drawn on the canvas rather than on what the file keeps**, so it was right
  only while nothing was cropped; and **on a phone set to German every speed chip
  read "2,x"**.
- **Sweep five, over the edit commands, the quick tools and the shell
  (5 October).** The layer between the screens and the model: what the toolbar
  actually calls. Twenty-two confirmed faults, all fixed, none seen;
  `docs/DEVICE_FINDINGS.md`'s last section lists them and ends with the ten
  things a device has to answer. The worst of them: **"Take out every um and
  uh" could cut half a minute of footage** (fillers were merged by their place
  in the word list, which runs across lines, so two "um"s twenty-seven seconds
  apart became one cut of 29,000 ms inside one undo step); **deleting a word
  that took nothing out still moved every caption after it**; **x2 on a song's
  beat grid doubled the dots and left the tempo and the bar phase where they
  were**; and **a staged project deleted from the grid was destroyed in place**,
  with no bin entry, an Undo that did nothing and its picker grants held until
  uninstall.
- **Sweep four, over the export, media and data layers (5 October).** The three
  sweeps before it went over what you can see; this one went under it, and
  almost none of it shows as a wrong picture - it shows as a long export
  failing where a short one did not, a file that is right but took twenty
  minutes, a draft that quietly forgets. Eighteen distinct faults, all fixed
  and all unseen; `docs/DEVICE_FINDINGS.md`'s last section lists them and ends
  with the seven things a device has to answer, in the order they would show.
  The two biggest: **every exported file carried 400 KB of padding** from
  Media3's streamable-moov reservation (`media/CompactMuxer.kt` turns it off
  for the three places that mux in-app - check a short export is about a fifth
  of what it was and still plays in the gallery, in WhatsApp and in a browser),
  and **the last run of a reverse was fed to the end of the file**, so
  reversing three seconds off the head of a twenty-minute recording decoded the
  whole remaining twenty minutes (`media/ReverseRuns.kt` - check it finishes in
  about a second and the reversed clip's *last* frames are there, which is what
  the two-keyframe margin protects). Both arithmetics, and every other fix in
  the sweep, are executed on the JVM and negative-tested against the old code;
  eighty suites then; ninety files over eighty-eight `run` lines now, the
  two Media3 ones among them, and `RunnerChecks` holds that every file is named
  by a runner and that `run_desktop.sh` calls `run_media3.sh` at its end.
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

- **30 September (day), all of it.** Built on the desktop with no phone
  attached: Enhance voice, Duck under speech, Remove silences, Even out volume,
  Fit shots to the beat, Split screen and Grid, Free stock footage, Translate
  captions, Save as GIF, Read-aloud voices, Auto adjust, and two review rounds
  of fixes (CBR only where the encoder offers it, the preview canvas capped,
  effects alpha only over black in the preview). The logic is executed on the
  JVM (VoiceCleaner, Loudness, Duck, Silence, BeatFit, SplitScreen, Gif,
  AutoAdjust, TranslateChunk suites in `tools/jvm/run.sh`). Most of it was
  driven on the phone the night of 30 September and seven faults were fixed
  and seen fixed - the last section of `docs/DEVICE_FINDINGS.md` says what
  was seen and what was not. Still unseen: Enhance and the read-aloud voices
  by ear, Auto adjust judged by eye, and the file of a Grid or Split screen
  against its preview.

- **1 October, all of it after the phone left at about 07:30.** Seen on the
  phone before that: the Text and Sound sheets' single orange, the white
  Play, the frame-rate rows, Grid and Split exported matching the preview, the
  release build launching and playing at 0.3% janky frames. Built without
  the phone and executed on the JVM: the sliding strip (`TimelineWindow.slideFor`,
  WindowChecks), every new animation, effect, transition, look, track, sound
  effect and voice (AnimationOptionsChecks, EffectRecipeChecks,
  ExportPlanChecks, LookChecks, MusicSynthChecks, VoiceEffectsChecks via
  `tools/jvm/run_media3.sh`), and three review rounds' fixes. Not seen: any
  of the new options on screen or in a file, the 48dp targets, online genres
  and Load more, the strip under a finger. The script is the last section
  of `docs/DEVICE_FINDINGS.md`.

## Conventions worth not rediscovering

- **The network is for online features only.** Every request goes through
  `online/Online.kt` (`Online.get`, `Online.download`), which throws while the
  Settings switch is off; a tool that needs the internet asks first with
  `rememberOnlineGate().request(...)`. Nothing uploads a video, photo or
  project - Settings' privacy card promises that. Google's datatransport
  (from MediaPipe) is removed in the manifest; keep it removed.

- **A tool's `id` is a handle, not a name.** `QuickTool.id` is in the navigation
  route, in the filename of every saved draft, and in the filename of every file
  the tool writes. Display names change freely; ids do not, or drafts people are
  in the middle of are orphaned.
- **Comments say why, not what.** The code says what. Where a line looks odd,
  the comment explains the thing that made it that way — usually a bug. Those
  comments are the only record of a lot of hard-won detail.
- **Media3's `@UnstableApi` wants `androidx.annotation.OptIn`**, not Kotlin's.
  Both compile; only one is right.
- **A draft cannot hold every number, and `optDouble` lies about the ones it
  cannot.** JSON has no NaN and no infinity: `JSONObject.put(String, double)`
  *throws* on one rather than writing something nothing could read. That throw
  came out of `DraftCodec.encode`, which `ProjectAutosave.save` calls **before**
  the try that guards the write, so one non-finite number anywhere in an edit
  took the whole save with it - and every save after it, silently. Every float
  now goes through `data/DraftNumbers.kt`'s `putFinite`, and
  `tools/jvm/DraftRoundTripChecks.kt` fails on a `put(…, x.toDouble())`
  appearing again. The other end of it: **`optDouble("x")` with no second
  argument answers `NaN`, not zero**, so a field read that way comes back NaN
  from any draft written before the field existed. Thirteen were.
- **Anything downstream of the user's rotation measures against
  `EditorUiState.framedWidth`/`framedHeight`**, never `sourceWidth`/`sourceHeight`.
  Three separate bugs came from that one confusion.
- **The sandbox's `kotlinc` log is mostly noise, but not entirely.** "No value
  passed for parameter" is real. It was filtered out as framework noise once, and
  a broken build shipped.
- **`.format()` takes the phone's locale; `.uppercase()` does not.** The two read
  alike and behave oppositely, which cost a round trip on 5 October in both
  directions. `"%02d".format(n)` uses `Locale.getDefault(FORMAT)`, so on Arabic,
  Persian, Burmese, Bengali or Nepali it emits that locale's own digits - which
  is how "Export subtitles" came to write a file the app's own `\d` parser could
  not read. Anything machine-readable wants `String.format(Locale.ROOT, …)`;
  anything on screen is better in the user's. Kotlin's no-argument `uppercase()`
  and `lowercase()`, on the other hand, are locale-*independent* - they are not
  Java's `toUpperCase()` - so the Turkish dotless i cannot bite through them, and
  a sweep for it finds nothing. Executed under `tr-TR` rather than argued:
  `"lut_3d_size".uppercase()` is `LUT_3D_SIZE`, `uppercase(tr)` is
  `LUT_3D_SİZE`. `tools/jvm/LutChecks.kt` keeps that honest for the one place it
  would matter.
- **A locale-formatted string is never cut up by an ASCII literal.** The
  corollary of the entry above, and it cost five call sites: `Timecode.format`
  built "0:03.000" with `.format()` and the callers took the fraction off with
  `.removeSuffix(".000")`, which matches nothing once the digits are Arabic -
  so every ruler tick, clip chip, draft row and library row grew from four
  characters to nine, and the ruler, which lays a label a second with no width
  given, ran into itself. Leave the part out of the format string instead
  (`Timecode.format(ms, withMillis = false)`). The sibling call sites that use
  `substringBefore('.')` were always safe, because the '.' is a literal in the
  format string. `tools/jvm/ControlChecks.kt` refuses the pattern anywhere.
- **A picked text file is read with `PickedText.decode`**, never
  `bufferedReader()` or `decodeToString()`. Both of those are UTF-8 and both
  *replace* what they cannot read, so an .srt in a legacy single-byte encoding -
  Notepad's "ANSI", and most subtitle archives - parsed its ASCII timing lines,
  reported success, and put U+FFFD where every accented letter had been. The
  decoder tries the BOM, then UTF-8 strictly, then the encoding the reader's own
  language suggests.
- **Only what a file name cannot hold is taken out of a name somebody gave.**
  Stripping to `[A-Za-z0-9 _-]` deletes a whole name in Cyrillic, Greek, Arabic,
  Devanagari or CJK, and that stem is what the chip shows - two imported fonts
  became "font" and "font 2". Android's filesystem takes UTF-8; nothing
  downstream ever wanted it.
- **Three letters is a Latin rule.** A whole name in Chinese, Japanese, Korean
  or Hebrew is routinely two characters, so a floor counted in letters throws
  real names away (`ProjectRules.saysSomething`, and the same lesson already
  written down in `TextEdits.translateCaptions`). The floor stays at three where
  it earns its keep - it is what refuses a GoPro's "GH010123" - and one letter
  is enough from a script whose words are that short. Count code points, not
  chars: a surrogate half is not a letter.
- **Time runs left to right.** Both strips compute a left-origin pixel from a
  moment and place it with `offset`, which mirrors in RTL (`absoluteOffset` is
  the one that does not), while the playhead and the drag deltas do not - so on
  an Arabic, Hebrew, Persian or Urdu phone the strip would be drawn against its
  own gestures. `TimelineEditor` and `TrimStrip` hold their subtree to
  `LayoutDirection.Ltr`, which keeps the Rows inside them in order too.
- **A result that lands in the background goes in with `recordLate`.** `record`
  closes whatever gesture is open, so a slider under the finger when a
  measurement, a synthesis or a probe finishes becomes two undo steps with the
  result wedged between them - and the drag cannot be taken back without losing
  the result. And a one-shot command carries **no** gesture id: a gesture id is
  for folding a per-frame stream, and on a command the strip calls once it just
  merges two deliberate actions into one undo.
- **A thing you choose says it is chosen.** `Modifier.selectable`, not
  `clickable`, wherever a border colour or a tint is the only mark of the
  current option - and `enabled` for one that cannot be taken, or the node reads
  "not selected" while drawn dead and swallows the tap. A control whose only
  content is a colour needs a name as well (`ColourName.of`), because a fill
  announces nothing.

## Longer form

`BUILD_NOTES.md` has the Media3 API details and the things most likely to need a
touch. `ARCHITECTURE.md` has the shape of the app.
