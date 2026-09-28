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
- **Rotate 90° freezing the editor.** A corrective seek ran thirty times a second
  with no regard for whether the player's position meant anything, and a pipeline
  rebuild makes it read zero — so each seek interrupted the rebuild that would
  have stopped the next one. The loop is closed. Whether it was *the* freeze is
  unconfirmed; a logcat around the moment of rotating would settle it.
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
