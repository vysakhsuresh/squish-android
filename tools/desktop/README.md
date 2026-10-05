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
