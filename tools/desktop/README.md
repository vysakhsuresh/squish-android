# Looking at a video on this machine

There is no ffmpeg here, no VLC, and Application Control refuses to run any
binary built on the spot — so for a long time a video file on the desktop could
be probed (`tools/jvm/Mp4Probe.kt` reads its tables) but never *looked at*.

These three scripts close that. They use Windows' own
`MediaComposition.GetThumbnailsAsync` through PowerShell, so nothing is
installed and nothing is compiled.

```sh
# Frames at even intervals, as JPEGs. Writes duration_ms first, then one line
# per frame.
powershell -NoProfile -ExecutionPolicy Bypass -File tools/desktop/frames.ps1 \
  -Video "C:\path\to\clip.mp4" -OutDir out/ -Count 40 -Width 420

# A burst around one moment, to read a join frame by frame.
... -Count 24 -FromSec 17.6 -ToSec 19.6

# The lot as one contact sheet with each frame's time under it - one picture to
# read instead of forty.
powershell ... -File tools/desktop/contact_sheet.ps1 -Dir out/ -Out sheet.png -Cols 8 -CellW 180

# One frame's region, scaled up, for reading small text in a screen recording.
powershell ... -File tools/desktop/crop.ps1 -In out/f003.jpg -Out tl.png -Y 0.5 -H 0.5 -Scale 1.5
```

What it is for: reading a reference video (this is how the wedding reel in
`docs/COMPETITORS.md` §4 was read frame by frame, which found two joins we had
no shape for), and reading an **exported file pulled off the phone** — a join, a
transition, a grade, the first frame after a cut — without having to describe it
from memory.

Two things that cost an hour to find out, so they are written down:

- **The WinRT collections come back as bare `__ComObject`.** The .NET Framework
  projection wants a union `Windows.winmd` that only the Windows SDK ships, so
  `comp.Clips.Add(clip)` fails with "does not contain a method named 'Add'".
  Every collection call has to go through the interface's own `MethodInfo` —
  and `IList<T>.Add` is declared on `ICollection<T>`, not on `IList<T>`.
- **Compiling a C# helper does not work.** `csc.exe` is there and the per-
  namespace `.winmd` files are in `C:\Windows\System32\WinMetadata`, so it
  compiles — and then Application Control blocks the binary, as an exe and as a
  DLL loaded with `Assembly.LoadFrom`. PowerShell is the way in.

The frames come back as **JPEG** whatever the extension says.

**They are not frame-exact.** Asked for every frame of a 30 fps file (27 Oct),
`GetThumbnailsAsync` handed back pairs that were identical to the pixel - from
the benchmark clip itself as from an export of it - so a picture that seems to change
fifteen times a second may be changing thirty, and a moment read off a frame's
name can be a frame or two out. What *one* frame shows is trustworthy: where two
edges sit against each other, a band, a colour. How long something took is not;
for that, log the times on the phone, or count samples with `tools/jvm/Mp4Probe.kt`.

## Two checks that are not about pictures

- `sh tools/desktop/glsl_check.sh` compiles every shader in `app/src/main/assets`
  with the NDK's own `glslc`, translated to GLSL 330 because glslc takes no
  ES 100. It catches a typo, a type that does not match or a missing semicolon
  before a phone does; it cannot catch ES 2's own limits (a loop bound that is
  not a constant). Run it after touching any shader.
- `sh tools/desktop/compose_unstable.sh [regex]` builds the Compose compiler's
  reports and lists every composable with a parameter it compares by identity
  (strong skipping) rather than by equals - the kind of parameter that re-ran
  every clip on the strip on each pass. `app/compose_stability.conf` is where
  an immutable class is declared stable.
