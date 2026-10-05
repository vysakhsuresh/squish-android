# Device findings

Results from driving the app on the moto g84 5G (Android 15, Snapdragon, 1080x2400).
Each entry says which build, what was done, and what happened. Fixed entries move
to the bottom with the commit that fixed them.

## Open

### Clock samples 1 s apart read 1.287 then 4.234 (build efbe483)
Noted during the overlay-freeze run above (now under Fixed): two readings of the
clock taken about a second apart were three seconds apart on the timeline, well
before the dissolve at 4.7 s. Nothing in the engine's clock explains a jump there
(the clock is read off the driving player, and its handover is at the cut, not
before it); a screencap takes a second or more itself, so the two samples may
simply have been further apart than they looked. Re-check with a screen recording
of the transport rather than two screenshots.

## Fixed (pending device check)

### Post-merge bug round (over bf6753e) - commits c44c54b to f162c5a
Found by reading the merged B1-B16 code, fixed on the desktop with no phone
attached; the pure parts are executed in `tools/jvm` (Housekeeping, ClipOps,
Lane, FrameRules, PreviewRules), none of it has been seen. What the phone must
check, most important first:

- **The effects library over the canvas (f162c5a).** On Android 13+ (the g84 is
  15) the preview now draws Shake/Punch/Glitch/Invert/Flash/VHS/Blur/Rainbow as
  an AGSL render effect over the whole picture layer instead of in the base
  players' chains. First: that any effect shows at all (a shader that fails to
  compile falls back to the chains - check logcat for a RuntimeShader error).
  Then against an export: a Shake over a PiP shakes the PiP; an Invert over a gap
  and over a song's tail past the last shot is white in both; a padded canvas's
  blurred backdrop is affected in both; with the edit rotated 90 a Shake wobbles
  the same way in both; on a 9:16 crop a Punch zooms about the crop's centre.
  Captions stay unaffected on top. Watch the frame rate with Blur + Split on a
  1080p edit, and that a dissolve under an effect still blends.
- **Stabilizer on the frame (f162c5a).** A stabilized shot, then Rotate 90 on it,
  then a per-clip crop: the preview's correction should run along the same axis
  as the export's, and the zoomed edges stay cut to the picture (no spill over
  letterbox bars). The overlay box outlines the frame, not the shaking picture.
- **Crop no longer rebuilds the surface (f162c5a).** Open Crop on a shot, tap a
  ratio chip, then Free, then flip twice while paused and while playing: no
  black flash, no "Detaching surface timed out" in logcat.
- **Empty main track (c44c54b).** Delete the only shot (and, separately, leave
  only a song and a caption), back out, reopen from the grid: the project opens
  empty with Add media, not with the deleted shot and not "Can't open". Export
  on it says "Nothing on the timeline" rather than writing the source file.
  Reopen any project: its export title in the notification is the file name,
  and the estimate matches what it was before the reopen; a silent source
  reopened does not offer the camera switch. Rename an exported project from
  the grid: the card stays "Exported", not "edited". "Earlier version" shows
  the right cover and a size on the card at once.
- **Selection (7331732).** Select more, pick two sounds, Done selecting, then
  long-press one and drag: both move. Two butted selected overlays on one row:
  long-press the first and drag right a little - the pair moves, and the ghost
  does not snap to the second's old edge. Hold still on a non-lead member's trim
  grip: it trims, it does not lift. Tap a keyframe diamond on a selected clip
  with Select more on: it stays in the set. Add an overlay, Select more, tap a
  shot, Undo: nothing is left looking selected and Delete is not inert. Open the
  Replace sheet on shot A, Undo a step made with shot B selected: the sheet
  closes.
- **Overlay rows stay clear (e579db5).** Slow an overlay with a butted follower
  and another a little further on: none overlap, all three play in the
  preview. Paste 0.5x attributes onto an overlay: the same. Add an effect at the
  playhead past the last shot (song running on): it lands inside the picture.
- **Undo steps (f018fb9).** Strength Reset then drag within a second: two undo
  steps. Drag a slider while Cut out runs: one step for the drag, one for
  "Background".
- **Transport (4dcb1c1).** Add text / a title / a sticker while playing: the
  picture stops, the transport shows Play. Tap Render while playing: playback
  stops. Play the exported file on the done screen, press Home: sound stops.
  While playing, delete an earlier shot (or Undo a Float): the clock keeps
  running smoothly with the picture, no stall or drift.
- **Process death (0d56336).** `am kill` behind the photo picker opened from a
  Squeeze session with a trim moved, pick another video: the new video stays.
  Same for Stitch: new picks land after the saved ones. Editor Replace and
  Relink picked after a kill land once the draft is open. Share a file into
  Squish during a long export, toggle dark mode: the file opens when the export
  ends.

Left as they were, on purpose: the photo overlay's CPU grade still has no grain,
bloom or sharpen (documented in CLAUDE.md); the reframe window over a transition
is still one window for the frame in the preview and one per shot in the file;
Stop mid-export still waits on `Transformer.cancel()` on the main thread, which
is how Media3 is meant to be used - watch for an ANR on Stop over a 4K render.

### Export with an overlay fails: "Asset loader error" (build efbe483, waves 0-1) - commit 79fafe2
Edit: video (VID-...0104.mp4, 200x150, 5.2 s, with audio) + photo clip + Dissolve on
the join + a video overlay (8 s, Blend > Add overlay clip) starting at 0. Export 480p.
Result: `SquishExport: failed: 4 sequences`, `ExportException: Asset loader error`,
caused by `NullPointerException at SequenceAssetLoader.onOutputFormat(SequenceAssetLoader.java:344)`
reached from `ImageAssetLoader.queueBitmapInternal`. The user sees "Export stopped
unexpectedly / Asset loader error".
Cause, from the Media3 1.11.1 source: in the first-asset branch, when the sequence's
declared track types contain AUDIO and the first asset's output is VIDEO (the
transparent PNG filler from `CompositionFactory.filler`), it calls
`checkNotNull(sequenceAssetLoaderListener.onOutputFormat(FORCE_AUDIO_TRACK_FORMAT...))`
and the listener returns null - for as long as the lowest sequence declaring sound
has not yet created the sound exporter. An image loader loses that race to a video
decoder; the same edit without the overlay had one fewer decoder to warm and won it.
Fix: the clock (sequence 0) opens on Media3's own gap, declared with sound whenever
any layer is (`ExportPlan.sequenceTracks`). A gap loader announces both tracks before
it starts and, as the primary for both, makes the sound exporter first and the
picture's second, so no layer can get past its own picture - and ask for sound -
before the exporter exists. Executed in `tools/jvm/ExportPlanChecks.kt` on this edit.
The exception now reads "A layer couldn't get started" with what to change.
Review of the fix found two things the gap brought with it, both fixed (commit
below): a gap's frames come at a fixed 30 fps and the file gets one frame per clock
frame, so every layered export was written at 30 fps whatever the footage - the clock
is one frame of gap and then the transparent still at the edit's rate
(`ExportPlan.clockLeadMs`); and the gap's fixed 44.1 kHz stereo became the mixer's
rate, so 48 kHz camera sound was stepped down under any overlay - the gap item
carries a resampler to the highest rate any sound in the edit has
(`ExportPlan.mixerSampleRate`, `CompositionFactory.clockGap`).
Device check: this edit at 480p completes with the PiP on top and sound throughout;
then the photo first and the video second; then a photo overlay (PNG) row above a
heard video overlay row; then the B5 gate's (1)-(4). Then a 60 fps clip with a PiP:
the file is 60 fps (`ffprobe`/MediaStore), and a 24 or 25 fps clip with a Dissolve
comes out at its own rate with no duplicated frame after the first; and the AAC of a
48 kHz camera clip under an overlay is 48 kHz, as it is without the overlay.

### Playback freezes where the main track ends if an overlay runs longer (build efbe483) - commit 69185aa
Same edit as above: base track ends at 7.700 s (video 5.2 + photo 3.0 - 0.5 dissolve),
the 8 s overlay starting at 0 makes the edit 8.363 s. Press play from 0: the clock
reaches 7.700 and stays there, pause icon still showing (playing), for 6+ seconds;
it never reaches 8.363 and never stops. The overlay keeps drawing its frame.
Cause: `composeBase` looked every moment past the shots up as "the last frame", so
the last shot stayed under the playhead, its player was seeked back onto its out
point every few hundred milliseconds, and the clock, read off that player, went
nowhere. Fix: `PreviewRules.baseTime` does that only when the shots run to the end of
the edit; past them the picture reads "End of picture" under the layer and the clock
runs on wall time to the true end, where it stops. A moment with no shot under it
also lets go of the clock clip, so a parked player's seek is not read as a stall in
a gap. Replayed in `tools/jvm/PreviewRulesChecks.kt`.
Device check: play through; the clock passes 7.700, reaches 8.363 and stops; the
overlay is drawn over "End of picture" between the two; Jump to end shows the same.
Also a gap between two shots: the clock crosses it without holding.

### Export at 4K silently writes 1440x1080 (build efbe483) - commit 26d75df
Edit: 200x150 video + photo + music. Export sheet with 4K selected said
"2880 x 2160, ~50.4 MB". The file written was 1440x1080, 15.2 MB, 8.23 s.
Cause: Media3's `DefaultEncoderFactory` falls back by itself to the nearest size the
phone's H.264 encoder takes, and says nothing. Fix: `EncoderCeiling` asks the same
question the factory asks, the same way; the sheet shows the size that will be
written and says in amber when it is smaller than the size picked; the export is
built at that size and its bitrate spent on it; the history record says the same.
Review of the fix: an answer for a size no longer chosen could land last and stand
in for the chosen size's - tap 1080p then 4K at once and the sheet could promise
4K's asked size with no note. Fixed: an answer is kept only for the size still
chosen (`ProbeGate`, executed in `tools/jvm/ProbeGateChecks.kt`); the size chosen
since has its own answer on the way.
Device check: 4K on this edit says 1440 x 1080 in the summary with the amber note,
the estimate is about 15 MB, and the file matches; 1080p and 720p show no note; a
portrait clip at 4K is asked about the right way round (its note, if any, names the
portrait numbers); tap 1080p then 4K as fast as possible: the summary settles on
1440 x 1080 with the note, never on 2880 x 2160.

### Export sheet chips move under the finger - commit 26d75df
When the "Bigger than the source" warning appeared, the sheet grew upward and every
quality chip shifted ~100 px; a tap aimed at 480p on the previous layout landed on 4K.
Fix: everything that can appear or grow - both notes and the custom size field - sits
above the chips now. The sheet hangs from the bottom, so growth above the chips moves
the heading, not the targets.
Device check: tap 1080p on a 150p source, then 480p at once: the second tap lands on
480p; Custom's field opens above the chips.

### Music "Add" places the track at the playhead even when the playhead is at the end - commit ed7e06c
With the playhead at 7.67 s of an 8.2 s edit, Sound > Add put the song there (0.5 s
of it audible), under the sheet where it could not be seen.
Fix: a sound that does not fit the room left, with under a second of it - where the
playhead parks after a play-through, or a moment short of it - is backed up to end
with the edit, as a title is: a song then covers the picture from the start
(`EditRules.soundLanding`, executed in `tools/jvm/EditRulesChecks.kt`). Elsewhere
it lands at the playhead as before, and a sound that fits - a 0.4 s ding on the last
beat - stays exactly where it was put; the first version of this rule moved every
sound within a second of the end to 0, dings included. The playhead stays where it
is, as for every other add: the song's tail is under the playhead on the sound row
the strip shows under the sheet, and Undo of the add leaves the view where it was
(the first version moved the playhead to 0, which Undo did not put back).
Device check: play through, Sound > Add: the song runs 0 to the end, selected on
the sound row under the sheet, the playhead still at the end; Add with the playhead
at 3 s: at 3 s; a short sound effect added at 7.5 s of an 8.2 s edit: at 7.5 s.

### Adding a title never lets you type - commit ed7e06c
Words > Titles > "BIG NEWS" dropped the preset text on the picture; no keyboard opened
and there was no obvious way to type your own words.
Fix: a title arrives the way Add text does - dropped at the playhead and opened in the
line's Edit sheet with the keyboard up, its sample words selected, so the next thing
typed replaces them. Review found the add and the typing were two undo steps (the
first Undo put "BIG NEWS" back), for Add text as well; now the typing into a line
just added carries the add's step on for as long as its field is open
(`TextEdits.addTitle`, `addCaptionAtPlayhead`), so Undo removes the title, words
and all, as one step - as in CapCut.
Device check: Text > Titles > BIG NEWS: keyboard up, "BIG NEWS" selected in the field;
type "hello": the picture says hello; Done keeps it; Undo removes the title as one
step (the button reads "Undo: Add title"); the same for Add text with words typed;
a title whose words are deleted and then Done is gone, like a blank line.

### "Open with Squish" is ignored when Squish is already running - commit fbe53e2
`am start -a VIEW -d content://media/... -n com.squish.app/.MainActivity` while the
app is open: "intent has been delivered to currently running top-most instance" and
nothing happened.
Fix: MainActivity keeps the last request as state and handles `onNewIntent`; the
activity is single-task, so one copy runs and the editor opens over whatever is
showing. Review found the first version stacked a second editor of a video that was
already open, and the two saved into the same draft slot on their own tickers, each
writing over the other's work; and that a request arriving mid-export covered the
exporting screen, which let the display sleep and dropped the result. Now a video
with an editor open (`OpenEditors`) goes back to that editor, popping whatever is
over it (a done screen, another edit - each saves itself on the way out), and a
request is held while any export runs (`ExportsInFlight`) and acted on the moment
it finishes, over the done screen.
Device check: the `am start` above with the app in the editor of another file: a new
editor opens on the file; back returns to the previous edit with its work intact;
the same command again while its editor is on top: nothing changes; from the
previous edit (back), the command again: the stack pops back to the file's editor
with its work intact rather than opening a second one, and
`run-as com.squish.app ls files/projects` shows one draft for it; export something,
send the command mid-render: the render finishes with its "Saved to your gallery"
screen, and the new editor then opens over it; from the Files app "Open with" while
Squish is in the background.

### Recovery banner length ignores overlays (pre-B1 build) and states the wrong length - commit 7509d0c
Banner said "3 clips, 0:16.563 of edit" for an 8.36 s edit with an 8 s overlay: it
summed the overlay into the length. And "2 clips, 0:23 of edit" for an edit last seen
at 8.2 s (the draft may have been changed on the device since).
Fix: `ProjectSnapshot.totalDurationMs` is where the last picture ends, the same number
as the editor's `trimmedDurationMs` and the header's timecode. The drafts list reads
the same field.
Device check: kill and reopen the overlay edit: the card says 0:08; the header says
0:08.363; the drafts list says the same.

### B6: the recovery banner is clipped by the timeline (build 6e73151) - commit 7509d0c
On opening a clip with a saved edit, the "Your edit of this clip is saved" card sat
between the transport and the timeline; the strip overlapped its lower half so
"Start a new project" was hidden and untappable.
Fix: the card is three lines tall - title, summary, and Continue beside a plain
"Start a new project" action - so it fits its capped share above the strip; the
notices' column scrolls in any case.
Device check: open a clip with a saved edit: the whole card is visible above the
strip, both answers tappable, upright and on the phone's side; at the largest font
size the card scrolls rather than hiding a button.

### Transition badge/Blend panel sits over the action bar; taps land on Undo - commit 0a12aa3 (B6)
With the Blend panel open, the action bar moved up; a tap aimed at a spot that was a
panel control on the previous frame landed on Undo and reverted a transition.
Fix (B6): Undo and Redo live in the header and never move; the Blend panel is gone
(Transition is a sheet on the join's badge); a sheet has a fixed height and opens in
the toolbar's place, so nothing under the finger moves when one opens.
Device check: tap a join's badge, then tap where the toolbar was: nothing is undone.

### A song dragged out past the last shot plays in the preview but not in the file - review of commit 69185aa
With the clock crossing the stretch after the last shot, an 8 s clip with a song
dragged out to 20 s played black with music to 20 s, and the file was 8 s: the
preview promised a stretch the file left out. The edit's length now counts sounds
(`EditorUiState.trimmedDurationMs`, `ProjectSnapshot.totalDurationMs`): the header,
the export sheet, the recovery card and the file all say 20 s, and the file runs
black under the song's tail through the compositor (`ExportPlan.needsCompositing`
and `layers` take the edit's end; executed in `tools/jvm/ExportPlanChecks.kt`), as
every other editor does. A song *added* is still cut to end with the edit, so a file
is never longer than its shots by accident; only a tail dragged out on purpose runs on.
Device check: 8 s clip, add a song, drag its end to 20 s: the header says 0:20, the
sheet says 0:20, the file is 20 s with the picture to 8 s and black with music after;
a title can be placed over the black tail and is in the file; Undo the drag: 0:08
everywhere and the file is 8 s. An HLG clip with a song dragged past it comes out SDR
(every composited export does).

### The Squeeze summary said "Original size" under a 720p chip - review of commit 26d75df
Before the first estimate landed, or for a file whose size the probe could not read,
the summary's fallback was a fixed "Original size". It names the size chosen again
(`OutputSizePicker`).
Device check: open Squeeze, tap 720p at once: the summary never reads "Original size"
with 720p highlighted.

## Checked, not a defect

### Back from a shared-in editor lands in Squish, not in the app that shared
With the activity single-task, a clip shared from Gallery while Squish sits in the
background on an editor opens in Squish's own task with that editor and the dashboard
under it: back goes to the earlier editor, then the dashboard, then out of Squish;
Gallery is where it was left in its own task. This is what CapCut and every
single-task editor do, and it is also what keeps the app to one copy: with the
standard launch mode a VIEW from Files would start a second copy of the whole app in
Files' task, with its own editor saving into the same draft as the first's - the
double-editor data loss above, across two activities instead of two screens. Kept.

### Rotated export at "Original" is labelled 150 x 200 but written 200x150 + rotation tag 90
Source is 200x150. Export sheet says "150 x 200"; the file is 200x150 with
`orientation=90`, which players show as 150x200.
This is Media3's documented behaviour (`Transformer.Builder.setPortraitEncodingEnabled`,
off by default): a portrait output is turned a quarter turn for the encoder, since
landscape encoding is what every encoder is tested on, and the turn is written into
the file for players to undo - exactly how every phone camera stores a portrait
recording (1920x1080 + rotation 90). MediaStore, WhatsApp and Instagram all read the
tag. The sheet's 150 x 200 is what is seen, which is the right thing to promise. The
encoder ceiling (commit 26d75df) asks about the landscape orientation for the same
reason. Nothing to change; if a player is ever found that ignores the tag, portrait
encoding is one builder call away.

## Verified on device (build 6e73151, B6)

- New layout renders: header with name (editable), undo/redo, orange Export;
  transport with frame-step and fullscreen; level-0 toolbar Edit · Sound · Text ·
  Stickers · Overlay · Effects · Looks.
- Tapping a clip swaps in the clip toolbar: Back · Split · Speed · Animation ·
  Placement · Mask ... with the hint "drag to move, drag its ends to trim".
- Restored draft keeps music waveform, title and clip colours.
- Note: tapping a clip still moves the playhead (B7 is meant to change that).

## Verified on device (build efbe483)

- Rotate 90 no longer freezes the editor; the picture rotates; playback runs; no
  "Detaching surface timed out" errors.
- Rotated export succeeds.
- Export keeps the project; "Back to editor" returns with undo history intact.
- Video + photo + Dissolve exports (7.72 s = 5.2 + 3.0 - 0.5 overlap).
- "Start a new project" asks first, explains the bin, and moves the old draft to
  files/projects/trash/.
- An edit made 0.8 s before back is on disk (flush on leave).
- Drafts written by older builds open in the new build.
- Video track button menu (Video or photo / Blank) works; photos land as 3 s clips.
- Overlay is drawn without the black letterbox bars it had before (B4).
- Overlay clips get their own track row above the main track in the timeline.
- Video + photo + music exports with both tracks (8.23 s); music fits the edit.
- A "BIG NEWS" title exports burned in at the same place and size as the preview.
- Text track button opens Words; Sound track button opens Sound.
- Unfinished screen: "Exported" badge, "Earlier version · 19 min ago", and a
  "Recently discarded" section (kept 30 days) with restore and delete, all render.
  Small gap: discarded entries show an empty thumbnail box.

## Verified on device, 29 September night (build 66567bd and after)

- New project: multi-select picker (photo + two videos), clips land in picking
  order, a coach mark on first open. Export 720p of the three: 940x718, 16.5 s,
  size matches the sheet's promise; progress card with Stop and "carries on
  with the screen locked"; notification permission asked at first render.
- Overlay: one-tap Overlay button opens a photo/video picker; the overlay lands
  on its own row with a bounding box; drag to move and the corner handle to
  scale both work on the picture. Export with a photo overlay: 16.5 s, 30 fps,
  overlay in the file where and how it sat in the preview (after 7b51427, which
  fixed every composited export with sound failing before it started).
- Text: Add text opens the keyboard at once; fast typing keeps every letter
  (after 66567bd); closing the keyboard gives the room back.
- Sound: Add from Squish originals lands at the playhead, selected, visible on
  its row. Long-press drag moves it and snaps to 0:00. Dragging its end
  extends it past the picture; export runs to the sound's tail (22.5 s) with
  audio and video tracks.
- Timeline rows keep full height on select (after 66567bd).

### Closed: a quarter-turned shot shown sideways (29 Sep, 23:10)
`squish_1790638553740.mp4` is a Rotate-90 export of a landscape clip, made in
an earlier test: its people are sideways in the file itself, and Google Photos
shows them so. Squish's preview and export draw it the same way. Not a defect.

## Verified on device, 29 September late (builds 1b3c998 - f2da944)

- Home cards: the export badge sits on the cover and the name has the line
  to itself; camera and gallery file names show as "Edit · 29 Sep"; the size
  is in the card's menu. The editor's header shows the same name.
- A short text clip on the strip shows a T glyph instead of a blank block.
- Filters and Adjust say "On shot N", and bring the playhead onto the shot.
- A video opened in Squish from another app is named by its own file, not by
  the copy Squish keeps of it.
- A `VerifyError` on opening the editor after an incremental install was a
  stale dex; a clean build cured it (CLAUDE.md).

## Verified on device, 30 September small hours (builds 2876a48 - 3287b05)

Fixed and checked on the phone:
- Sticker: added at the playhead, dragged, scaled; its Delete and Copy
  buttons stay on screen when it is grown to the top; its clip shows the emoji.
- Speed 2.7x: plays at real time over the shorter clip. Speed, Animation and
  Crop name the shot ("Shot 2") and bring the playhead onto it, pausing
  playback first.
- Preview: after playing to the end, opening a sheet no longer leaves the
  picture a third smaller in the bottom-left of its frame (stale surface size).
- Export: a sped-up shot at Auto frame rate wrote 42 fps; now 29. Qualcomm AVC
  in VBR overshot the requested 2.76 Mbps to 4.98 (19.2 MB against a 10.7 MB
  promise); CBR gives 2.08 Mbps, 8.5 MB. The finished screen lays a portrait
  file out without overlapping the cards; the floating back orb is gone.
- Library: made-up names show as the moment ("29 Sep, 10:14 PM"); the thumbnail
  plays; entries for deleted files delete without a loss warning.
- Squeeze 480p: 2.6 MB against ~2.9 promised. Snip: copy-cut (trim
  optimisation), 22.5 s, portrait tag kept. Extract audio: 22.5 s .m4a.
  Stitch: two clips, 15.96 s.
- Stitch session with two clips survives a force-stop and resumes.

Open:
- Squeeze and Snip show a picked file by the picker's number ("1001319364.mp4").
- Squeeze offers 1080p/1440p/4K for a 940x718 source (an upscale in a tool
  for making files smaller).

## Verified on device, 30 September morning (builds 7e22c56 - 001ae99)

- Privacy: the installed app no longer holds INTERNET (it came in with
  MediaPipe's datatransport); Cutout's background removal works without it.
- Frame 1:1 on a 9:16 edit: the kept square now fills the preview (it was a
  small square in the old tall frame); the file is 720x720 / 1080x1080, 29 fps.
- Mask, Ellipse, Cut out, on a main-track shot: was ignored in the preview
  and in the file (alpha dropped at both ends); now the ellipse over black in
  both, seen at 25.4 s of the file.
- Stop mid-export: asks "Stop exporting?", Stop leaves no file and the sheet
  ready to export again.
- A split followed by Home and a real process kill (`am kill`, pid gone) is
  in the draft when the project is reopened.
- Opening Mask, Cutout, Adjust and the other shot tools on a shot the
  playhead is off brings the playhead onto it.

Voiceover (mic granted "While using the app" at the owner's word): the
permission prompt, then the 3-2-1 count-in starts at once; the picture plays
silently with "Listening"; a take grows on a sound row; Stop lands a
"Voiceover" clip with a mic glyph and a waveform, selected, playhead back on
its start; the WAV is under files/voice; the phone's mic indicator goes out;
"Record this take again" is offered.

## Online features, 30 September (build bfdd154)

Off by default (Settings › Online). Seen on the phone:
- Sound › Music › "Free music online" with the switch off offers "Browse free
  music online"; tapping it asks "Free music needs the internet" (what is sent,
  that videos never are, that Settings turns it off); Continue turns it on.
- The list: live Creative Commons results from the Internet Archive with
  artist and licence (no-derivatives and non-commercial ones filtered out).
  "+ Add" downloads the MP3 to files/music/online and lands it on the timeline
  at the playhead, selected.
- Text › Style › "Free fonts online…" opens sixteen Google Fonts; Lobster was
  downloaded (TrueType, files/fonts) and applied to the title in the preview.
- Settings › Online follows the prompt ("On · only what you ask for"); the
  switch turns it off ("Off · Squish works fully offline", pref false).
- MediaPipe's datatransport logger is removed from the manifest; the only
  network use is Online.get / Online.download, which refuse while off.
Left off at the end of testing, the owner's default.

## Seen on the phone, 30 September night (moto g84)

What the 30 September (day) build was checked against, on the phone, with
screenshots and the drafts read back. Items marked "fixed" found a fault that
is fixed in the commits named, and the fix itself was seen on the phone.

1. **Enhance voice** - picked, played 6 s, no error in the log. *Not heard*:
   nothing here could listen to the speaker. Still to check by ear.
2. **Duck under speech** - song under a voiceover dips at 7 keys to 25%, note
   "Turned down under 2 stretches". Pressed again it said "No talking found";
   fixed (a5b8a02) to "Already turned down under all the talking", seen.
3. **Remove silences** - a 20.7 s talking clip with three pauses became four
   pieces, 10.7 s, cut at 2.84 / 6.32-8.60 / 12.14-14.20 / 17.18, no
   transitions added; Undo put the one clip back. It was also offered on a
   main-track photo; fixed (624e372), seen gone.
4. **Even out volume** - two shots of one file brought to 46% and 44%, the
   louder-measured one lower; Undo restored 100%.
5. **Grid and Split screen** - both were wrong on a 1:1 frame over portrait
   footage: Grid's top-left tile sat half off the frame (fixed 959b8bf) and
   Split's half showed a strip a quarter wide beside black (fixed 74e1d99).
   Both seen right after the fix. The file of either is not yet exported.
6. **Free stock footage** - no prompt (online was already on from earlier), a
   grid with thumbnails, a clip downloaded and landed at the playhead as the
   first shot, Undo removed it. The empty search opened on bomb tests, the
   Hindenburg and an execution; fixed (78a916a) to open on b-roll, seen.
7. **Translate** - "BIG NEWS" to Spanish came back "¡Uups!", the service's
   quality-0 guess; fixed (0c913ca) to take its reviewed entry, seen as
   "¡GRANDES NOTICIAS!"; Undo restored the line.
8. **Fit shots to the beat** - 141.4 BPM, 70 beats found; Fit every bar
   shortened shot 1 from 17.433 to 15.775 s and nothing grew; Undo restored.
9. **Save as GIF** - 480x480, 96 frames, exactly 8.000 s by its own delays,
   picture clean; in Pictures/Squish.
10. **Read aloud** - bound to Google's engine, a 0.98 s WAV landed as a sound
    clip under the line; Undo removed it. The voice chips are in Settings; no
    voice other than the default was listened to.
11. **Export** - the WhatsApp preset gave 1080x1080, fit to 16 MB, 12.9 MB,
    no failure. Its frame table (tools/jvm/Mp4Probe.kt) showed a 2.72x shot
    written at 27.2 fps: Media3's frame dropper steps by whole frames. Fixed
    (6fce08c) with a grid dropper; the same export went from 864 frames to
    884, 29.93 fps.
12. Not checked (a 9:16 edit set to 1:1, a tiny custom crop).
13. **Auto adjust** - tapped on a shot with no error, and undone. Not judged
    by eye on a badly lit shot.
14. **GIF after leaving the screen** - left at once with Back; the GIF was
    made and the toast shown.

Also found and fixed: **Animate every photo** never showed on a slideshow (it
looked for photo overlays, f4d915a - seen giving two photos their moves), and
the Export sheet said "33% bigger than the original" on a photo project
(266f459, seen).

Still open: export of Grid and Split screen not yet compared with the
preview. A photo's Volume sheet now says it has no sound (dfe5049) - built,
not seen.

Test files removed by MediaStore id (audio 1001320247; video 1001320254,
1001320269, 1001320286; image 1001320271); the two test projects are in
Recently deleted. Online features left off.

## To check on the phone (built 1 October, day)

Built on the desktop with the phone away; the arithmetic is executed on the
JVM (all suites in `tools/jvm/run.sh`), nothing below has been seen. Test on a
**duplicate** of a project, never the owner's own. Measure speed on the
release build (`./gradlew assemblePerf`), behaviour on either.

Performance (release build, the busiest project: 3 rows, music, voiceover, titles)
1. Play 15 s: `dumpsys gfxinfo` janky (legacy) under 2%, 99th percentile under 25 ms.
2. Scrub the strip back and forth: median frame under 17 ms; clips, ruler,
   waveforms and filmstrips stay exactly under the finger and the playhead -
   no 1 px wobble, no jump every quarter screen (the strip now slides a
   pre-drawn window and redraws it in quarter-screen steps).
3. Zoom in to the frame and out to the whole edit, then scrub: the same.
4. Long-press and carry a clip, trim both ends, tap a keyframe diamond, a
   transition badge, the "close gap" and "Add media" buttons: each lands on
   what is under the finger.
5. Release build launches, opens a draft, exports (R8 was failing before).

Export
6. The 2.72x edit at 30 fps: Mp4Probe says ~888 frames, 30.0 per second.
7. A 1.5x shot at 30: 30 fps, not 22.5. A 0.5x shot: no frames dropped.
8. Grid and Split screen export match the preview (seen once: they did).

Look and feel
9. Text and Sound sheets: Add text / Add from your files are tinted in the
   tool's colour; Done is the only solid orange; Play is white.
10. Every chip is finger-sized and nothing reads "Au…" or cuts a letter: the
    frame-rate rows are 3+3, Speed presets read 0.25x 0.5x 1x 2x.
11. Clear, Remove, Unpin, Turn off, Loop to end, Cancel, Set: easy to hit,
    and nothing beside them pushed off or overlapping.
12. The editor header, transport, Export sheet close and the clip preview's
    scrub bar are easy to hit and nothing overflows on the narrow phone.
13. A duplicated unnamed project is named "Edit · 29 Sep copy".

New options
14. Animation: 16 ins (Pop, Bounce, Drop in, Whip, Swing in, Twirl, Blink…),
    15 outs, 11 loops (Shake, Heartbeat, Sway, Orbit, Rotate) on a PiP and on
    a shot - the same in the file. Camera moves: Sink, Push close, Diagonal,
    Tilt in, Pull wide stay full-frame.
15. Text: Zoom, Drop, Spin, Blink in; Pop, Float up, Zoom, Spin, Blink out;
    Shake, Heartbeat, Swing loops - Spin turns in the file as on screen.
16. Titles: 17 on two rows; Fonts: Light, Thin, Medium, Script, Caps, Typed
    render the same face in the file.
17. Stickers: Travel, Animals, Nature, Sports, Signs, Flags tabs.
18. Effects: 21 - RGB split, Strobe, Earthquake, Heartbeat, TV static, Old
    film, Dream, Negative pulse, Trippy, Sway, Slow zoom out; Slow zoom and
    Blur now act with their knob at the left.
19. Transitions: 25 - Push right/up/down, Whip, Wipe up/down, Zoom out,
    Pop in, Blackout - preview and file agree on a three-shot chain.
20. Music: Squish Originals 22 with mood chips; sound effects 16 (each plays);
    Free music online (turn online on through the prompt): 15 genre chips,
    each fills, Load more adds new tracks; no lectures or marches; switching
    genre mid "Load more" does not mix lists. Online off again at the end.
21. Voices: 14 - Helium, Giant, Telephone, Megaphone, Cave, Wobble, Alien
    heard in the preview and the same in the file (Alien's ring was missing
    from the file before the fix); None leaves the sound exactly as it was.
22. Filters: 50, a Social family (Bright, Insta, Glow, Food, Portrait, Beach,
    Sunset, Matte, Cherry, City, Pastel) and Cyberpunk, Forest, Desert; the
    family chips scroll; the same grade in the file.
23. Templates: 14 - Travel, Birthday, Food, Fitness, Gaming, Love, News,
    Sale each set a frame, look, title and effects in one undo.
24. A new arrival (Pop, Bounce, Swing in, Twirl, Blink) on a PiP fades in the
    file as on screen (it did not before the fix).
25. Grid/Split chip: lit after the tap, dark again once the clip is turned or
    mirrored.

## Seen on the phone, 2 October

- Opening a project: it reopened where its playhead was left (usually the
  end), so every clip sat off to the left of the playhead and the timeline
  read as starting part way along. Fixed (c6d7a94): projects open at 0:00.
  New projects, and photos added with the video track's +, start at 0:00.
- Export of a 4-photo edit with a Twirl arrival, a Whip join and a Spin
  title, read frame by frame in the file: Twirl half-turned at 0.27 s and
  landed by 0.53 s; Whip mid-flight at 2.9 s; the title tilted at 3.6 s,
  near-level at 3.7 s, upright after. 345 frames, 11.5 s, 30 fps.
- Titles on two rows (17); effects grid with the 11 new effects previewed on
  the shot (Dream blurs, Trippy splits the colour).
- Music: mood chips on Squish originals filter (Beats); online genres load,
  a fast Lo-fi then Cinematic tap shows Cinematic with no false error, and
  Load more appends a second page. 'CC PUBLICDOMAIN' fixed to 'Public
  domain' (ed8b157).
- Settings and every other page: the floating back button covered the
  bottom card at rest; it has its own strip now (ed8b157).
- The split hint said 'end' at the start of a clip; fixed (c6d7a94).
- No crash in the log; the editor at about 280 MB.
- The timeline started half a screen in: the strip was always centred on the
  playhead, so at 0:00 the first clip began in the middle. The window now
  clamps at the start (`TimelineWindow.startClamped`, checked in
  `WindowChecks.kt`): 0:00 sits at the left edge and the playhead walks right
  until it reaches the middle. Seen on a 3 s photo, a 4-clip edit and at deep
  zoom.
- Purple filmstrip blocks after a split or a zoom: a tile not yet decoded drew
  as the clip's colour. It now borrows the nearest decoded frame
  (`FilmstripLoader.nearest`); not reproduced over several splits since.
- Effects switched off for the session after two player errors; only a frame
  processor fault drops the chain now, and it is re-armed after 15 s.
- A new line of text or a sticker lands clear of the lines showing with it
  (`TextPlacementRules.freeY`), and the strip scrolls its row into view.
- Opening a video with "Open with" just to look no longer leaves a project
  behind when nothing was changed.
- A voiceover stops at the picture's end; the camera sound panel is hidden
  when the main track holds only photos.
- The transition sheet's Camera tab shows the new tiles (Push right/up/down,
  Whip, Wipe up/down, Zoom out, Pop in) previewed on the shot.
- Mask kept after a heavy session; a mask added while paused shows at once.
  Grid chip lighting correct.
Not yet seen: the voices by ear, the new filters and templates on a file,
48dp targets on every sheet. Left on the phone: the test project
"Edit · 2 Oct" (5 clips) - delete it at the next session.

## Seen on the phone, 2 October (evening)

- Filters: Polaroid and VHS reach the shot at once; a ten-letter name read
  "Disposabl" at the phone's 1.15 font - names now shrink to fit their tile.
- Template Vlog: the warm look, the Slow zoom and the "Today's vlog" lower
  third land together, replacing the previous template's title. The title
  sits on the 4:3 canvas below a wide photo, so its 66% black box vanishes
  over the letterbox - the letters are whole, in the preview and the file
  alike (exported 960x720, 11.5 s, 30 fps). Not a fault.
- The done screen's cover was the file's first frame: black for an edit that
  twirls or fades in. Now a third of the way in, as the library takes it.
- A project opened through "Open with" lost its dashboard cover once the
  grant lapsed (the app holds no video permission); the card now falls back
  to the first clip it can still read. Seen: the 29 Sep two-clip project
  shows its photo.
- Picking an arrival, leaving or loop - on a shot or on a line - plays it once
  and stops, by the playhead rather than a timer (the seek and start ate half
  of a 0.4 s arrival). Seen on Zoom out (shot) and Type (line).
- Chip rows open with the lit chip in sight (a line's Spin exit was off the
  row's end, so nothing looked chosen). Seen.
- Effects sheet labels fit at 1.15; the template's Slow zoom is listed under
  "On the video" with its sliders.
- Built, not seen (the phone left): a line or sticker picked on the strip
  away from the playhead brings the playhead to it, past its arrival.

## Seen on the phone, 3 October (after midnight)

- A photo overlay landed at the playhead, selected with its box; dragged,
  resized by its corner (which scales and turns, as CapCut's does), and one
  Undo took the resize back. Opacity 52% shows live and in the file.
- Exported with the overlay at 52% bottom-left: the file has it at the same
  place and size as the preview, to within a percent of the frame.
- **Rotate on an overlay, against an export** (B11's open question): a quarter
  clockwise on screen and the same way in the file, at the same place.
- An edit of three files with a cut, a delete, a Dissolve then Dip to black,
  and a Shake: 444 evenly spaced frames at 30 fps, 14.8 s, sound throughout.
- Fixed and seen: two join badges lay on top of each other across a narrow
  shot (one shows now); a title tile clipped "Day 1 in Goa" (samples now
  shrink to fit, FitText); picking a transition or adding an effect now plays
  it once; the transition panel's icon was the crop glyph; an effect added
  from the library landed on a fifth row out of sight - the strip now
  scrolls a just-added effect, sound or line into view.
- **Rotate 90° then export** and **Rotate 90° freezing the editor** (the two
  oldest items in CLAUDE.md) hold up: Rotate all 90° turned the preview
  clockwise, played straight after with no "Detaching surface timed out",
  and exported at 720x960 undistorted, turned the same way. Taken off the
  unverified list.
- 9:16 at 1080p: the sheet greyed everything above 864p, because the
  encoder ceiling was measured on the cropped pixels (404 wide, asked
  3848x2160). Now one function sizes both (`EditorUiState.resolutionAt`);
  1080p is offered and the file is 1080x1920 (1920x1080 with a 90° tag).
- A music track lands cut to the picture's length; its fades draw on the
  clip, and the file has sound for its whole 14.86 s. The fade's loudness in
  the file was not measured (no AAC decoder here) - still by ear.
- Typing a title updates the picture as each key lands; the box grows with
  the words.
- Stickers: search matches every word typed and more names ("fire" found
  only the flame; now also fireworks, sparkler, boom, chilli; "diwali" finds
  the diya). Picking from a search closes the keyboard (it held the picture
  shrunk to a corner). A sixth sticker landed exactly on the fifth; stickers
  now spread to the left and right thirds (`TextPlacementRules.freeSpot`,
  EditRulesChecks).
- A photo on the main track was offered Speed (a curve over one picture),
  Volume, Voice and Extract audio; no longer (ToolRulesChecks).
- Leaving and reopening the project kept every overlay, sticker, effect,
  title and the song.
- Dashboard: a duplicate's name read "Edit · 2 Oct co…"; card titles shrink
  a little first. Dialogs quoted names with straight quotes drawn as ”…”;
  curly quotes now.
- Library: 21 of 55 rows were exports deleted from the gallery, greyed and
  counted for good; forgotten now (`HistoryRepository.forgetDeleted`, only
  when the gallery row is gone). Extract-audio files read "Video · 26 Sep"
  with a film icon; now "Sound · 26 Sep" with a note.
- Squeeze 940x718 → 480p: 628x480, 70% smaller, 22.5 s; 720p and up greyed
  (never bigger than the source). Snip 7.75–25.70 s: 18.0 s, 720x1280.
- Tool sessions screen: headed "Unfinished" over sessions marked Exported
  (now "Tool sessions"); deleted projects had blank thumbnails (covers now);
  "deleted … ago" was cut off (the save time dropped, and two lines).
- The phone on its side: two panes, the picture carried across, plays with
  no "Detaching surface" stall; back upright the same. (Rotation settings
  were restored afterwards.)
- Settings' "What's inside" said sixteen looks; it now counts the app's own
  lists (49 filters, 21 effects, 24 transitions, 14 templates).
- Stitch rows were the same name twice ("Video · 29 Sep"); each now shows a
  frame with its number on the corner, and "0:08 · at 0:00".
- Extract audio on a video with no sound (a GIF-made WhatsApp clip, and
  exports of it) drew floor-height bars that read as loading; it now says
  the video is silent. A video with sound still draws its wave. The done
  and library screens of a sound export show its wave, not a lone note.
- Delete, Undo (back, selected) and Redo on a sticker.
- Tool sessions: a dashboard tool tile reopens its last session on purpose;
  four test Stitch sessions left from the picker were removed by name.
- Freeze cut a 3 s still in at the playhead and selected it; Reverse showed
  "Reversing … 9%" with Cancel and landed the backwards shot (277 frames,
  even 33.33 ms); Reverse again put the original back at once. Copy and
  Paste attributes carried a Polaroid look from a video shot to a photo.
- **Every base shot was measured as the edit's shape.** With an effect chain
  the player reports 0x0, so a portrait photo in a 4:3 edit had its Crop
  window over the black bars beside it. The engine now reads each file's
  shape (`PreviewEngine.fileAspect`). Seen: the window hugs the photo, 1:1
  is a square inside it, and an export with that crop matches the preview.

## Seen on the phone, 3 October (afternoon)

- Hero speed curve exports cleanly: 642 even frames at 30 fps, 21.4 s, as
  the timeline says. The curve graph now has a 0.1x / 1x / 10x / 100x scale.
- Keyframed overlay: a key at 5.5 s and one at 6.3 s, the picture moving
  between them, kept through leaving and reopening.
- Fixed and seen: two same-day projects both read "Edit · 2 Oct" (the start
  time is added, `ProjectRules.distinctTitles`); Placement's heading showed
  a crop-like icon; auto-captions on music made blank cards (held back, and
  "no words in it - music or noise" said); a duplicated line, sticker,
  overlay or effect was selected off screen (the playhead goes to the copy);
  white letters under Neon glowed white and vanished on a light picture
  (Neon pink now); the box's four buttons covered the words while typing
  (they stand aside on a small picture); stickers stacked on one spot could
  not be reached (a second tap goes down the stack); picking a voice gave
  no sign (two seconds play); a GIF note said "Making…" after it was made;
  the export detail's back button sat on the red Delete; the rename hint was
  the gallery file name.
- **Find the beat on a Squish original was wrong on 15 of 22 tracks**
  (SynthTempoChecks, which renders each and runs the real detector):
  Lo-fi Sunset at 156 for 78, others at 4/3 of their tempo. The originals'
  beats now come from the track itself (`MusicSynth.beatMap`). The detector
  itself was not changed - an attempt to prefer the plain beat over a
  triplet reading changed nothing, and the octave/4:3 confusion on other
  music is still there; ÷2 and ×2 on the Beats card are the way out.
- Checked and right: Read aloud lands a speech clip; Speech bubble with its
  tail; Auto adjust lifts a dark photo; full-screen scrub bar; frame steps
  (+3 then -1); Fit shots to the beat; GIF 360x640 from a 360p export.

## Seen on the phone, 3 October (evening)

The four fixes written after the afternoon round, each of which said "built;
not yet seen on the phone", have now been seen on it.

- **A project opened and left unchanged keeps its saved time and its place.**
  Opened the top card ("Edit · 2 Oct, 11:36 PM · 4 clips · 1 h ago"), changed
  nothing, pressed back: the card still reads the same time, the same "1 h
  ago", in the same slot, with its "Exported · edited" badge.
- **A shown moment lets go when playback is paused before its end.** Picked
  Zoom in on Shot 1, dragged "In takes" to 2.1 s, picked the chip again to
  replay it, paused at 0:01.538 - before the 2.1 s end - then pressed Play:
  the playhead ran on to 0:07.672 and past, still playing. Before the fix the
  waiting moment would have paused it at 2.1 s.
- **Extract audio names the sound after the shot.** The sound landed as
  "Shot 1 sound" on the strip and in the row's hint, not
  "1001323287.mp4 sound", with the shot muted under it.
- **Replace and Track name a clip only when its file name says something.**
  Replace with a gallery file headed "Replace with the clip you picked"
  (with "Start at 0:02.294", the old clip's own in-point); Track's pin list
  read "Pin the overlay at 0:04 to it", with the stickers below it named by
  their own letters.
- Also seen in passing, and right: the tracker followed 277 frames and said
  "held on for 4% of them" in yellow with the advice to tighten the box (a
  shell on moving sunglasses - a fair verdict, not a fault); Track adds no
  undo step of its own; the Replace sheet's back dismisses it without
  applying.

### Blend frames, rendered and probed - the first time

B13's riskiest unseen thing: `FrameBlendEffect` is the one shader program in
the app that emits more frames than it takes, and nothing had ever run it on a
GPU. A one-clip project (11.527 s of 30 fps footage) at 0.25x, exported twice
at 360p, settles it. `tools/jvm/Mp4Probe.kt` on both files:

| | frames | rate | durations |
|---|---|---|---|
| Blend frames **on** | 1377 | 30.001 fps | 33.33 ms x1376, 32.00 ms x1 |
| Blend frames **off** | 345 | 7.500 fps | 133.33 ms x344, 132.00 ms x1 |

So the blend emits four frames per input, at the edit's own rate, with nothing
dropped and nothing out of order; off, the file keeps only the frames the
footage has. Neither run stalled and no `VideoFrameProcessingException` was
logged - the whole 46 s render took under twelve seconds either way
(`SquishExport: done … frames=1377 … bitrate=297245 asked=300000`, and
`frames=345 … bitrate=279920`). The done screen read the rate off the file
itself: **30 fps** for the blended one, **8 fps** for the stepped one.

The Speed sheet's own warning showed with it and is right: "7.5 fps out - too
few frames to read as motion. 96 fps footage would carry it", with "Keep it
smooth - nothing slower than 0.8x, the slowest this footage carries" under it.
That retires CLAUDE.md's "slow motion below about 24fps out" entry, which had
also said frame blending was not built; it is, and now it is seen.

Not judged by eye: whether the blended frames *look* like motion blur rather
than a cross-fade. The frame counts prove the mechanism, not the picture.

### Seen around that export, and right

- The export sheet: "Sizes above 1188p are beyond this phone's encoder", with
  1440p and 4K greyed and 1080p offered; the Quality chips read
  Lower · Standard · Higher; the frame-rate row's Auto says "Auto keeps the
  footage's 30 fps"; the estimate read "≈ 2.5 MB, was 1.9 MB".
- Back from the done screen lands on the bare editor (B14).
- The Library's Delete asks first, says the gallery copy is the only one Squish
  keeps and that there is no undo; the count went 37 → 35 and both files were
  gone from `Movies/Squish` afterwards.
- A project card's menu: Rename, Duplicate, Preview, "Earlier version · 15 min
  ago", Select, Delete, and "Uses 1 KB on this phone" under them; its Delete
  says the project moves to Recently deleted for 30 days and the original
  videos are untouched.

### The frame-rate rows, probed - and a fault in them

B14 asked for the fps row to be checked on both export kinds with a probe.
One clip of 21.442 s of 30 fps footage, exported at 360p, measured with
`tools/jvm/Mp4Probe.kt`:

| asked | path | frames | measured | frame durations |
|---|---|---|---|---|
| 24 | cuts-only | 514 | 24.019 fps | **33.33 ms x385, 66.67 ms x128** |
| 50 | cuts-only | 642 | 30.000 fps | 33.33 ms, even |
| 60 | cuts-only | 642 | 30.000 fps | 33.33 ms, even |
| 25 | layered (a photo overlay) | 536 | 25.008 fps | 40.00 ms, even |

50 and 60 are right: asking above the footage's rate passes every frame and
declares the rate it really has, as the code's own comment promises.

**24 was not.** The file was the right rate *on average* and the right length,
but no frame in it lasted a 24th of a second: it alternated 33 ms and 67 ms,
because `FrameGrid` chose which frames to keep on an even grid and then let
them into the file carrying the footage's own stamps. The layered path came
out exactly even in the same edit, because the compositor draws its own
frames - so the two halves of one chip disagreed, and the "24 or 25 for a
film look" the sheet offers was variable-rate.

Fixed: a kept frame now goes in on its slot (`FrameGrid.slotUs`). A first
attempt at this was wrong and `FrameGridChecks` caught it before the phone
did - footage *slower* than the rate asked for keeps every frame, and pulling
those onto a faster grid runs the shot quicker than it was cut, so 24 fps
footage asked at 30 came out sped up. The grid is only used where the footage
has frames to spare, which the export knows (`spedUp || state.fps >= rate`);
everything else keeps its own stamps.

Rendered again on the phone after the fix, same project, same settings:

- **24 fps: 514 frames, 24.000 fps, 41.67 ms x483 and 41.66 ms x31** (the
  split is the 90 kHz timescale, 3750 ticks against 3749). Same frame count,
  same length, sound untouched at 21.502 s.
- **60 fps: 642 frames, 30.000 fps, byte-for-byte the same size as before** -
  the path that was already right is untouched.

### The rest of the export sheet (B14), on an 11.5 s clip

- **HEVC.** The toggle wrote `video=c2.qti.hevc.encoder mime=video/hevc`. At
  720p it asked 771,028 bps against H.264's 1,186,197 - exactly the 0.65 of
  `HEVC_BITRATE_SCALE` - and the files were 1.61 MB against 2.18 MB: 35% off
  the picture, 26% off the whole file, since the sound is the same in both.
  "About a third smaller" is fair for the picture. **At 360p the toggle changes
  nothing** and both files came out 1.0 MB, because the recommended bitrate
  times 0.65 is already under `MIN_VIDEO_BPS` (300 kbps) and both clamp to the
  floor. That is the arithmetic working, not a fault - worth knowing before
  someone tests the toggle at a small size and reports it broken.
- **Fit to a size.** Chips 16 / 25 / 50 / 100 MB. At 16 MB the sheet solved the
  clip up to "960 × 720 · sized to fit" and promised ≈ 16.0 MB; the render asked
  10,976,363 bps, the encoder spent 5,753,960 on this very flat footage, and the
  file landed at **8.8 MB, under the cap**. No overshoot, so the "Keep this one /
  Try again, tighter" card rightly did not show (still unseen).
- While Fit is on the HEVC row changes its words to **"Sharper file (HEVC) - The
  same size, with a better picture in it"**, and back to "Smaller file (HEVC) -
  About a third smaller at the same quality" when it is off. Under a size cap
  the codec buys quality rather than size, and the sheet says so.
- **Sound only** wrote `squish_….m4a` to **Music > Squish** (590 KB, the log
  `video=null mime=null frames=0`); the done screen is a waveform with Length
  and Size only - no Frame or Rate columns, Copy to Files but no Save as GIF.
- **The last export's settings are the next new project's defaults**: a fresh
  project opened on 360p and 60 fps, which is what the run before it used.
- The fps hint reads "The footage runs at 30 fps. A higher rate can't add
  frames; the file keeps the ones it has" on 50 and 60, and "Frames are dropped
  to reach it. 24 or 25 for a film look; 30 for a smaller file" on 24.
- The done screen reports the **file's** rate, not the chip's: 60 asked of 30 fps
  footage says 30 fps on it, which is what the file has.

### The 48 dp targets, measured rather than eyed

"The 48dp targets" had sat unseen since 1 October because looking at a
screenshot cannot settle them. `uiautomator dump` can: every node with
`clickable="true"`, its bounds in pixels, against 120 px (48 dp at this
phone's density of 400). The script is in the session scratch, and the method
is worth keeping - it is a few seconds per screen and it does not guess.

Two real misses, both now fixed:

- **The strip's track heads were 42 dp wide.** The heads fill their column and
  the column (`GUTTER`) was 42 dp. Now 48; the only other thing that reads
  `GUTTER` is the column itself, and the strip lays out as before.
- **The strip's Split / Duplicate / Delete tiles were 46 dp wide.** They are
  drawn 38 dp and the press spreads into the gap between them, so the 8 dp gap
  left each target 2 dp short. The gap is 10 dp now.

Everything else measured clean: the dashboard (19 nodes), the editor at level 0
and with a clip selected, the Volume sheet (20 nodes) and the Export sheet (23).

## Seen on the phone, 4 October (early morning)

Six things built since the afternoon, driven on the phone. **Three faults found
and fixed and seen fixed**; everything else holds.

- **The Curves tool works.** A point tapped above the diagonal lifted the
  midtones and the picture brightened at once - so the 256-entry table, its
  texture upload and the shader's lookup are all right on this phone's ES2
  driver. Undone afterwards, and the draft confirmed clean, writing version 14.
- **LUT import works, in the preview and in the file.** A `.cube` that swaps red
  and blue, pushed to Downloads and imported through the file browser: the food
  turned blue on screen while the strip's own plain tiles stayed warm - the
  control that shows it is the grade and not the decode - and the exported file
  was blue too. The LUT survived leaving and reopening the project, and the
  card's chips read None / RedBlueSwap.
- **Blend modes work, after two faults.** A photo overlay set to Screen lightens
  the shot, and **the exported file is the same picture**.
  - *Fault 1:* setting a blend did nothing. `applyLive` short-circuits on "this
    surface's clip has not changed", and a blend set on an *overlay* leaves the
    shot under it untouched. It compares the blended-still list now.
  - *Fault 2:* the still came out upside down - `GLUtils.texImage2D` uploads a
    bitmap first-row-at-the-top and GL's v runs from the bottom.
- **A keyed filter strength is read at the right moment in the file.** Noir keyed
  from nothing at the start to full at the end: the exported file begins in
  colour and ends black and white. That settles the one real risk in it - the
  export's item clock counting from the item's own start, as the preview's does.
  - *Fault 3, and the worst of the three:* scrubbing with a keyed look left the
    picture **black**, with `reloading base-a: no progress` repeating until the
    engine dropped the effect chain and the shot played plain. The blended-still
    check beside it asked `was?.underId != clip.id`, and with no blended still
    `was` is null, so that is true on **every tick** - it asked for a fresh frame
    thirty times a second and swamped the decoder. It had never shown because
    `applyLive` used to return early; a keyed look is the first thing that makes
    it run every tick. Compared as one key now: the picture holds and not one
    "no progress" line.
- **Safe-area guides work.** TikTok's bands dim all four edges with a dashed
  teal rectangle round the clear part and the label inside it.
- Not driven: **editing by transcript**, which wants a clip with speech on it -
  the test footage was a canteen. Its two pure pieces are executed
  (`SpanRemovalChecks`, `TranscriptChecks`); the panel has not been seen.

Everything made for the test was removed: three exports, the scratch project,
and the test `.cube` from both Downloads and `files/luts/`. The dashboard is
back to 14 projects and 35 exports.

**Next on the phone**, when it comes back (3 October evening, the phone left at
about 17:30). Nothing built today is waiting on it - the frame-rate fix and both
48 dp fixes were driven and measured before it went - so this is the older list,
in the order it is worth spending a charge on:

1. The "Keep this one / Try again, tighter" card (B14). Today's fit landed under
   its limit on flat footage, so the overshoot path has still never run. Busy
   footage, a tight limit - 16 MB on a minute of something moving.
2. Whether a blended slow-motion file *looks* like motion blur rather than a
   cross-fade. The frame counts are settled; the picture is not.
3. The read-aloud voices and Enhance voice by ear, and Auto adjust by eye - the
   three things left that no measurement can answer.
4. A Grid or Split screen file against its preview.
5. Keep HDR, which needs an HLG clip, and a 4K export, which needs the S23.

**A warning for anyone repeating this:** a node clipped by the edge of a
scrolling container reports its *visible* bounds, so it reads as undersized.
Three of the first readings were that - a card half off the bottom of the
dashboard, the toolbar chip cut by the right edge of the screen, the last
switch under the Render button. Scroll it into view and measure again before
believing it. One change was written against such a false reading (the toggle
switch) and taken out again once a scrolled dump showed the switches were
always 48 dp.

## Seen on the phone, 4 October (dawn) - the CPU grade against the shader

The one screen where the two grading paths meet: a photo on an **overlay** row
is drawn by Compose from a bitmap graded pixel by pixel on the CPU
(`Look.applyTo`, through `StillPictures.graded`), while everything else goes
through the player's shader. They are written from one description, but nothing
had ever put them side by side.

A scratch project with a photo on the main track and a second photo as a small
overlay over it, and the same 33³ red/blue-swap `.cube` applied to each in turn,
measured off a screencap rather than judged by eye:

| | red | green | blue |
|---|---|---|---|
| base (shader), LUT off | 136 | 142 | 153 |
| base (shader), LUT on | 152 | 142 | 137 |
| overlay (CPU), LUT off | 162 | 166 | 173 |
| overlay (CPU), LUT on | 173 | 166 | 162 |

Both swap, and both leave green alone, to within a unit. Three single blocks of
the overlay read the same way - 200/138/80 became 80/136/198, and 125/166/204
became 202/164/124. So the CPU copy and the shader agree on a LUT, which is the
newest and least-shared part of the grade, and the open item CLAUDE.md carried
("whether a photo overlay carrying a LUT matches the video beside it") is
closed.

It also settles, at a cube size other than 8, that the ES2 tile atlas is laid
out and sampled correctly: a 33³ cube is 33 tiles of 33x33, and a red/blue swap
is the one transform that shows a transposed atlas immediately.

Everything made for it was removed: the scratch project (binned by name through
the card's own Delete), and the `.cube` from both `/sdcard/Download` and
`files/luts/`, which is empty again.

## Seen on the phone, 4 October (morning) - the last five of COMPETITORS

Everything built between 05:00 and 06:20 driven on the moto g84, in the preview
and - for the two that could disagree - in an exported file.

- **Shapes and arrows.** The Shapes card sits above the stickers and draws its
  eight tiles from `ShapeGeometry` itself, so a tile is the shape that lands: the
  star fills its box and points up, the arrow's head is solid and points the way
  the tile shows. An arrow landed on the picture outlined in red with the
  sticker's own box and its four corner buttons; its sheet is the Placement slot
  with the shape chips, Outline/Solid (absent on an arrow, which has no inside),
  Line and Width above the usual sliders. Changing Arrow to Ellipse kept the
  place and took the new shape's own width.
  **In the file:** the red ellipse is there at 1.4 s, over the finished frame -
  over the black bars as well as the picture - at the size and place the preview
  had it, with the rocket sticker beside it. The frame at 0 is *blank* of it,
  which is the Pop arrival starting at alpha 0 and not a fault; reading that
  frame first cost twenty minutes of chasing a bug that was not there.
- **Sound stickers.** Riser: the rocket landed on the picture and a 2.5 s sound
  clip on a sound row at the same moment, and **one undo took both off**. Redo
  put both back. The export sheet counted "1 sound" and the file carries it.
  Not judged by ear.
- **A mask's shape keyed.** The Mask sheet's keyframe button reads "Add a key
  here to animate this", then "Key here · tap to remove · 1 key" with a Clear
  link. A small ellipse keyed at 0, the Width slider dragged to 128% at 7.3 s
  made a second key - and the sliders from then on read the shape *at the
  playhead*. Scrubbing between them grows the shape smoothly; the magenta
  diamonds sit on the strip at the frames they belong to.
  **In the file:** small at 0, wide at 8.1 s, growing between. The preview and
  the file agree.
- **Use the liveliest bit.** Watched a 22 s file, finished in about ten seconds,
  and the draft says exactly what it should: shot 1 (17.9 s of a 22.1 s file)
  moved from `sourceInMs` 0 to 1659 with its length unchanged to the
  millisecond; shot 2, which is its whole file and has no room, was left alone.
  - *Fixed while testing:* it was only drawn inside the branch that shows once a
    beat grid exists, so nobody who had not already tapped "Find the beat" could
    see it at all. It is below the branch now, on the Sync chip, always.
- **Templates.** Five shelves - Social, Film, Life, Retro, Work - each with its
  own tiles; the Film shelf's four read apart by colour (Trailer grey, Thriller
  teal, Western tan, Noir silver).

A draft written by this build reopened with all of it: the shape, the sticker,
the keyed mask with its diamonds, and the sound.

Everything made for the test was removed: the scratch project (binned by name),
its export (by MediaStore id), and the screen-on override.

## Seen on the phone, 4 October (06:50) - the colour wheels

Lift, gamma and gain, the other half of G6. Built, then driven within the hour.

- The **Wheels** chip is last on Adjust, after Curves, and opens three chips -
  Shadows, Midtones, Highlights - a disc and a level slider.
- The disc's hues are where the three axes actually put them: red up, green at
  seven o'clock, blue at five. *Drawn the obvious way they were not*: a sweep
  gradient starts at three o'clock and runs clockwise while the axes are
  measured anticlockwise, so evenly spaced colours put red on the left and a
  finger dragged at what looked like red warmed nothing. The stops are placed
  by hand now and the ring reads correctly.
- **The shader compiles on this phone's ES2 driver** - which is the thing a
  JVM check cannot answer, and a shader that does not compile fails
  asynchronously and plays the shot plain. Shadows at about +72% lifted a
  near-black region of the preview from 11/18/29 to 67/72/80.
- **In the file:** the same region of the exported file reads 71/74/84. The
  preview and the file agree to a few units, which is the done screen's own
  scaling.

Not seen: the pad dragged into a colour (the disc's upper half sits above the
sheet's fold at that scroll position, so only the level was driven), and gamma
and gain by eye.

## Seen on the phone, 5 October - the inverted strip, and what sweeping for it found

A user opened a video, dragged the strip to the right, and the playhead
walked **left**. They were right, and the cause was a design decision with a
comment explaining itself: the window was clamped so it never scrolled more
than 14 dp before 0:00, so near the start the edit began at the strip's left
edge and the line walked right to the middle. While the line walks the strip
does not move - and a timeline short enough to fit the screen never scrolls at
all, so that is the whole of a short edit. One gesture, two opposite meanings.

**Fixed, and seen fixed.** The lead is half a viewport now; the line sits in
the middle at every moment. On the phone: drag left goes forward, drag right
goes back, 300 px of finger is 10.4 s at 26 px/s, and the line does not shift.
`WindowChecks` asserted the old walk and now asserts the line is within a pixel
of the middle at four zooms and three screen widths.

Then that class of fault - a control whose direction, sign or label contradicts
what you see - was swept for with four agents and two adversarial refuters each.
Twelve of fourteen candidates survived. Eleven are fixed (the twelfth is a
duplicate); three were driven on the phone this session:

- **The Sync nudges moved the number above them the wrong way.** "+10 ms"
  pressed three times now reads **+30 ms (+1f)**; it used to read −30.
- **The Mask sheet's "Up / down" ran opposite** to every other slider of that
  name. Dragged right, the ellipse now moves **down** the picture.
- **The transition badge swallowed the trim handles.** It is composed after
  every clip in the same Box and sits exactly on the join, which is exactly
  where the two clips' handles are; on the main track the head handle of every
  shot but the first could not be grabbed. The selected clip rises above the
  badges now - pressed and dragged at a join, the trim registers an undo step.

Not driven, fixed on the reasoning in the commit: the keyed-mask drag, the
curve point that handed the gesture to its neighbour, Placement's Reset
shrinking a shape, Adjust's Reset throwing away an imported LUT, the camera
panel reading the first file's audio flag, the Sync sheet showing the last
sound's result, the stale keyframe tap, and the crop's unclosed undo step.

### Sound on every cut (5 October)

Read off a Final Cut timeline (see `docs/COMPETITORS.md` §4). Five cuts made in
a 22 s clip, one tap: **"5 sounds laid, one on each cut"**, and on the strip
five sound clips each *ending* on its join, alternating long-short-medium -
reverse whoosh, swish, cloth, reverse whoosh, swish. Not judged by ear.

### Smooth skin's shader compiles (5 October)

The one thing only a device answers about it: a look shader that does not
compile fails on the player asynchronously and the surface then plays *plain*.
With Smooth skin at 93% and Brightness dragged to +59%, a band of faces went
from 124/103/102 to 177/156/155 - the grade reached the picture, so the shader
carrying the new `skinWeight` and `surfaceBlur` functions and the `uSmooth`
branch built and ran on this phone's ES2 driver.

Not measured: whether the smoothing visibly softens skin. The test picture was
a group photo whose faces are about thirty pixels across in the preview, and
the blur's radius is a share of the frame, so at that size it is under a pixel
on a face. It wants a close-up and a side-by-side export.

### The second sweep's wording and readout fixes, driven (5 October, 06:00)

Twenty-one findings of the second sweep were fixed; five of them were then
driven on a scratch project of a photo and a video, cut into three clips.

- **An effect's own knob reads in its own unit.** Zoom punch's "Beats per
  second" read **2/s** at the middle of its range and **3.4/s** near the top.
  It used to read 50% and 97% - a percentage of nothing, under a label that
  names a unit. `EffectKind.parameterReadout` carries the two per-second
  formatters, and `FxParams` now takes its rate from the same two functions,
  so the number over the slider cannot drift from the one the shader uses.
- **"Even out volume across clips"**, not "across shots": it measures every
  piece of footage that is heard, a picture-in-picture included. With one of
  three clips moved it says **"Brought 1 clip down to match the rest"** -
  singular, through `countOf`. The button is also gone now when there is only
  one piece of *footage*: a photo and a video used to offer it, and it had
  nothing to compare.
- **Sound on every cut** on two joins: **"2 sounds laid, one on each cut"**,
  and both drawn on the sound row, each ending on its join. Where the plan
  skips a join - two cuts within 320 ms share one sound, and it stops at
  sixty - the card now says "n sounds laid, across m cuts" instead.
- **Auto-reframe on a 9:16 crop** of three shots: "The crop follows the
  subject in each shot." The claim is counted now rather than taken from an
  `any{}`, so a shot added after the run reads "in 2 of 3 shots" instead of
  claiming all of them.
- **The Adjust chip row carries the chosen chip to the middle.** Scrolled to
  the end and tapped, "Smooth skin" animated from the right edge into the
  centre of the row with its slider under it. Picked up again after a trip
  through Filters and Templates, it comes back to the chip it was on.

### Smooth skin, seen (5 October)

On a close-up of a face filling a third of the preview, Smooth skin at 100%
visibly softens the skin and the saree beside it while the dark background
and the fine text of the screen recording under it are untouched - the blur
is weighted by the skin locus, not applied to the frame. Judged by eye in the
preview at about 300 px wide; still unseen in an exported file, and still
unjudged against a competitor's.

### The dot at the end of every slider (5 October)

Material 3 draws a "stop indicator" on the inactive half of a slider's track -
a filled dot in the active colour at the far end. Every slider in Squish had
one, and on the full-screen scrub bar it read as a **marker sitting at 0:22**
that nothing had put there and nothing would move. The six places that drew a
slider now go through `ui/components/SquishSlider.kt`, whose track is drawn
with `drawStopIndicator = null`.

Seen on the phone: the scrub bar is a plain track with the white thumb at
0:00 and no dot; dragged right it goes to 0:12.803 with the orange behind it;
and the Brightness slider on Looks → Adjust has lost its teal dot too.
