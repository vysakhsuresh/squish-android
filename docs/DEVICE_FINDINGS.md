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

### Every exported file carried 400 KB of padding (5 October, 06:50)

Measured, then diagnosed from Media3's own source. Three exports of the same
3-second clip, with the logcat line and `tools/jvm/Mp4Probe.kt`:

| size chosen | sheet said | file was | picture | sound | unaccounted |
|---|---|---|---|---|---|
| Original (716×1274) | ≈114 KB | 540,337 | 240 kbps | — | ~400 KB |
| 1080p | ≈2.4 MB | 1,757,777 | 3,487 kbps | — | ~390 KB |
| 360p | ≈114 KB | 579,440 | 128,791 B | 50,597 B | 400,052 B |

A 360p export came out **bigger than the 716p one**, which is what gave it
away: the overhead is a constant, not a rate. `Mp4Probe` (extended here to
print each track's bytes and codec from stsd and stsz) says the 360p file is
129 KB of avc1 and 51 KB of mp4a - 179 KB of media in a 579 KB file.

The cause is in `Mp4Writer.writeHeader`: Media3's in-app MP4 muxer, which
`Transformer` uses by default, reserves `DEFAULT_MOOV_BOX_SIZE_BYTES =
400_000` bytes after the `ftyp` box so the `moov` can be written at the front.
`maybeWriteMoovAtStart` then writes the moov into that space and fills the
rest with a `free` box, which is never trimmed. Streamable output is what buys
the reserve, and nothing here streams: the file goes to the gallery and into a
share, and Android's own `MediaMuxer` writes its moov at the end anyway.

**Fixed, not seen** (the phone left before the build was installed):
`media/CompactMuxer.kt` builds `InAppMp4Muxer.Factory()
.setAttemptStreamableOutputEnabled(false)` - `DefaultMuxer` is a pure delegate
over that same factory, so nothing else changes - and the export, the proxy
copy and every rendered still now use it. What a device has to answer: that an
export still completes and plays, in the gallery and shared to WhatsApp, and
that a short export is now about the sum of its tracks. The 400 KB was also in
every still under `files/stills/`, so a project of twenty photos was carrying
eight megabytes of it.

Two related things the same session measured, both unsurprising once the
padding is subtracted: the encoder honours a low requested bitrate (240 kbps
delivered against 300 kbps asked) and undershoots a high one (3.5 Mbps against
6.2), so the sheet's estimate is sound - it was the file that was not.

### A remembered 60 fps landed on 30 fps footage (5 October, 06:48)

The export sheet opened on a fresh 30 fps project with **60** lit in the Frame
rate row and the line under it reading "The footage runs at 30 fps. A higher
rate can't add frames; the file keeps the ones it has." The rate came from
`export_defaults`, which carries the last export's choices onto the next new
project - the *size* is capped at the footage's there and the rate was not, so
one 60 chosen for 60 fps footage doubled the bitrate of every project after it
for frames that do not exist. `ExportSettings.defaultOutputFps` now falls back
to Auto, by the same rule as `defaultOutputP`. Seen on the phone: the next
project opened on **Auto**, "Auto keeps the footage's 30 fps."

### Left on the phone, 5 October - **cleared on 7 October, 00:05**

The phone was unplugged mid-test, so this round's scratch did not get cleared.
It has been now, through the app's own Library and card menus, which name each
file in the dialog. Kept here because the *list* is the lesson - a session that
leaves scratch behind has to write down exactly what, by id:

- three test exports in the gallery, MediaStore ids **1001326343**,
  **1001326344** and **1001326345** (`squish_1791168783188.mp4`,
  `squish_1791168932075.mp4`, `squish_1791169046735.mp4`) - by id, never by a
  `LIKE 'squish_%'` pattern, since `_` is a wildcard and would match the
  user's own exports;
- two scratch projects on the dashboard: one made from the reel with a
  "Hello" line on it, and one three-second project from
  `VID-20261003-WA0186.mp4`;
- `adb shell settings put system accelerometer_rotation 1` (rotation was
  locked to portrait for the layout test) and `adb shell svc power stayon
  false`.

### Four suites nothing had run, and the click they were hiding (5 October)

`run.sh` is the sandbox's runner and `run_desktop.sh` the desktop's, and each
keeps its own copy of every suite's file list. Four suites - `animoptions`,
`effectrecipes`, `musicsynth`, `synthtempo` - were in the first and not the
second, so the only machine here that compiles anything had never run them,
and `musicsynth`'s line had not compiled since `MusicSynth.beatMap` started
returning a `BeatMap` from another file. A suite nothing runs is worse than no
suite: it reads as cover.

All four are in both runners now, and `tools/jvm/RunnerChecks.kt` fails if the
two lists part again or if either names a file that is not there.

Run for the first time, `musicsynth` found a real fault: **five sound effects
ended at level** - `sfx-reverse` at 0.61 of full, `sfx-build` at 0.35,
`sfx-riser` at 0.34, `sfx-drumroll` at 0.17, `sfx-subdrop` at 0.11. A sound
still at level on its last sample steps straight to silence, and a step is a
click. It matters most for exactly the three "Sound on every cut" uses, which
are butted against a join: the tick would land on every cut in the edit.
`MusicSynth.release` fades the last three milliseconds of every effect to
nothing (132 samples - it cannot take the swell off a riser), and the suite
now asserts both that the final sample is near zero and that the last half
millisecond is a taper rather than a one-sample notch. Not heard.

### The size estimate left the audio track out (5 October, from the same probes)

With the 400 KB of muxer padding accounted for, the 360p export still did not
add up: the sheet said ≈114 KB and `Mp4Probe` found 128,791 bytes of picture
and **50,597 of sound**. 114 KB is 300 kbps over 3.034 s - the video bitrate
alone. The estimate had set no bits aside for the track.

It was right to think there was no sound: that clip has none, and
`hasAnyAudio` was false. But the file gets an AAC track anyway -
`VideoProcessor` sets `AUDIO_AAC` on every composed export, and the encoder
writes constant 128 kbps frames (133 of them, ~380 bytes each, which is
exactly 128 kbps at 44.1 kHz) with silence in them as readily as with sound.

Two things fixed, both pure arithmetic and executed:

- **The estimate and the size target count the track.** Over three minutes
  that is 2.9 MB a "Fit to 16 MB" had never budgeted, so it overshot by them.
- **`hasAnyAudio` read the lead file's flag alone.** `sourceHasAudio` is taken
  once when the project opens; an edit whose *first* shot was silent and whose
  second was not reported no sound at all. It reads `anyCameraAudio` now, which
  scans the main track - the same trap the Camera sound panel was pulled out of
  earlier, left in place here.

Still open, and better than budgeting for it: **do not write the track when
nothing is heard.** It is 128 kbps of silence in every file a soundless edit
makes. That needs a device, because `BUILD_NOTES.md` records that the base
rolls lean on a sequence declaring sound so gaps and photos get silence - true
when *something* has sound, and the case to prove is when nothing does.

### Worth trying on a device: a trim in the editor could copy the stream

From the same three exports. At Original size the sheet asked the encoder for
300 kbps - the floor - because the source is a 250 kbps WhatsApp clip, and the
encoder delivered 240. So an edit that changed nothing about the picture was
re-encoded a generation down for no gain.

`VideoProcessor.isPlainTrim` exists for exactly this and copies the stream
instead, but it requires `state.videoClips.isEmpty()`, which is the quick
tools' shape and never the editor's: a project opened on one video has one
clip in `videoClips` from the first frame, so `trimOnly` was false on all
three runs (the logcat line says so).

Widening it to "one main shot, nothing else: no overlay, no text, no effect,
no grade, no crop, no turn, Original size, Auto rate, Recommended quality, no
HEVC, full volume, not muted, no added sound" would make a trim in the editor
as cheap and as lossless as a Snip. It is export-path surgery and Media3 falls
back to a full encode by itself when a file cannot be cut that way, so it
wants a device: open a clip, trim it, export, and check the logcat line says
`trimOnly=true optimization=` with a non-zero result and that the file's
bitrate matches the source's.

## Sweep three: the class the user found, hunted for (5 October)

A user dragged the strip right and the playhead went left. That fault was
fixed in the morning; this is the sweep for the *class* of it - a control
whose direction, sign or label contradicts what you see. Twenty-seven hunts
over nine groups of files and three lenses (direction, dead control, label
lies), two adversarial refuters each, both of which had to fail to refute.
Thirty-four candidates, **twenty-six confirmed**, all fixed. None has been
seen on a phone: it left before the sweep finished.

The ones worth remembering:

- **A tracked mask sat mirrored and walked the wrong way.** The tracker's
  `yFraction` is a bitmap row over the frame's height, so 0 is the top; the
  mask shader's y comes from a GL texture coordinate, where +1 is the top,
  and that is also what `centerYFraction` and `MaskOutline.dragged` hold.
  `Mask.centerAt` converted without negating. Track a face in the upper half,
  Pin, and the ellipse jumped to the lower half and climbed as the face
  walked down - in the preview and in the exported file.

- **The hand-drawn crop's corners could not be grabbed at all.** `gripAt`
  returned a corner only when the touch was *not* inside the rectangle, while
  the brackets are drawn inward and both ways into Crop open the window at
  the whole picture, where nothing is outside.

- **"Track from the playhead" started at the clip's first frame.** The picker
  shows the frame under the playhead and asks you to tap the thing to follow
  on it; the run cut its template at the clip's in-point instead, so what it
  followed was whatever was at that spot on the opening frame. That is why
  Track was hard to get a good result from.

- **The overlay box's corner buttons keep their touch zones when they are not
  drawn.** With the keyboard up the four buttons are deliberately hidden -
  they covered the words being typed - and a tap on bare picture a little past
  a corner deleted the line.

- **"Snap to markers and beats" was turning off the cuts too**, against the
  rule written twice in the same file and obeyed by every trim.

- **The Curves square swallowed every drag**, so the tool sheet behind it
  could not be scrolled once the square filled the viewport, and the controls
  below it were unreachable.

- **Snip's two trim handles ate each other** below a 48 dp keep: the start bar
  could not be dragged at all, and pressing it moved the end.

- **A keyframe diamond within 10-20 dp of a selected clip's edge** sat under
  the invisible half of the trim handle, whose gesture is a drag and ignores a
  tap - so the press did nothing whatever.

The rest were sentences: the Mask switch reading backwards in Pixelate and
Blur, Speed quoting a frame rate at a song, Layer saying no other overlay is
on screen while one plainly is, "Stopped - the lines made so far are on the
timeline" counted off stretches rather than lines landed, "1 key - moves
while it plays", "1 lines timed", "1 bars", a split refusal naming the end
the finger was nearer rather than the end that was out of room, and the RGB
split tile drawn as a mirror of the effect it was selling.

As it stands the predicate is sound, which was checked rather than assumed:
with `videoClips` empty there is nothing on an `EditorUiState` that changes a
frame except the trim, the edit-wide rotation, the crop or the padded canvas,
the size and the rate - and `singleFileEffects` covers all four of those, so
requiring it empty is the whole test. The look, the thirteen sliders, the
speed curve and the stabilizer have all been per-clip since B12 and B13, so
there is no edit-wide grade left to miss.

### A search typed in Malayalam matched nothing (5 October)

Found by writing the check for it. `Online.searchTerms` keeps what someone
types inside a Lucene query - letters, digits and spaces, the words joined by
AND - so a search can narrow the licence filter beside it but never escape
its parentheses. The filter was `[^\p{L}\p{N} ]`, and `\p{L}` is *letters*: in
an Indic script the vowel signs and the virama are combining marks, not
letters, so "കല്യാണം" came out as three fragments joined by AND, which matches
nothing. The app read the empty list as "couldn't reach". The same went for
Arabic, Hebrew, Thai and Devanagari, and for any decomposed accent.

`\p{M}` is kept now. The function moved into `online/SearchTerms.kt` so it can
be executed at all - `Online` holds a Context, a connection and a Compose
helper and cannot be compiled off a phone - and `tools/jvm/SearchTermChecks.kt`
asserts the shape, the eight-word cap, null for nothing, that a typed OR or
NOT comes through as a word rather than an operator, and that nothing which
comes out is a character Lucene reads as syntax, Malayalam and Arabic
included.

(The suite writes its non-ASCII as `\u` escapes: `tools/jvm/jc.sh` does not
tell the compiler what charset the sources are in, so a literal in another
script arrives as question marks and fails for a reason that is not the code's.)

### A caption containing "-->" threw its own cue away (5 October)

Found the same way: by writing the suite `SrtFile` had never had. The parser
took any line holding an arrow for a timing line, so a caption reading
"he said --> go" ended the cue it was part of and then set a start of -1 -
and the caption, and every line after it in that cue, vanished with no
message. Imported from a tool that writes dialogue arrows, a file lost
captions silently.

A line is a timing line now only when both sides parse to a moment
(`SrtFile.timingIn`), which is also what the index lookahead asks. Everything
else in that parser held up under the suite: a BOM, CRLF, a lone CR, a dot
before the milliseconds, no indices at all, no blank line between cues, hours
left off, one- and two-digit fractions, a caption whose text is "42", and a
round trip through `format`.

### Three caches that could not remember "nothing" (5 October)

`getOrPut` reads a stored null as absent and calls its lambda again, so a
cache of a nullable thing cannot remember a miss - which is the one answer a
cache of this kind exists to keep:

- **The export's blended stills.** Worse than a miss: `blendEffectFor` had no
  cache at all and was called once per shot the still lies over, so a light
  leak across a reel of twenty-four cuts decoded the same PNG twenty-four
  times and held every copy for the whole render. At 1080x1920 that is about
  eight megabytes each - two hundred alive at once, on a phone also running a
  decoder and an encoder, which is the shape of an out-of-memory failure that
  only shows on a long edit.
- **The preview's blended stills**, which had the cache and the `getOrPut`:
  a still that could not be read was opened again, and warned about, on every
  tick of the clock.
- **`CaptionRenderer.typeface`**, the same: a font imported once and since
  deleted was looked for on disk, and attempted, for every caption of every
  frame of a render.

All three read by `containsKey` now, and `ControlChecks` fails on any
`getOrPut` whose lambda can give null - a planted example confirms it fires.
Every other `getOrPut` in the app stores something that cannot be null.

None of this is visible on screen; it is memory and work. A device would
show it as a long export failing on a mid-range phone where a short one did
not.

## Sweep four: the export, media and data layers (5 October)

The three sweeps above all went over what you can see. This one went under it:
twenty-seven hunts across nine groups of files - the export plan and the
composition, the muxer and the encoder settings, the reverse and still
renderers, the audio processors and the decoders, the trackers and the
stabilizer, the drafts and the sidecars, the thumbnails and the caches - and
three lenses: wrong arithmetic, state left behind, and the preview and the file
disagreeing. Two adversarial refuters per candidate, both of which had to fail
to refute. **Twenty-eight confirmed, about eighteen distinct after the
duplicates were merged**, all fixed. None has been seen on a phone.

What this layer hides, and the UI sweeps could not have found, is that almost
none of it shows as a wrong picture. It shows as a long export failing where a
short one did not, a file that is right but took twenty minutes, a draft that
quietly forgets, a phone that stops handing out picker grants.

The ones worth remembering:

- **Every exported file carried 400 KB of padding.** Media3's in-app MP4 muxer
  reserves `Mp4Writer.DEFAULT_MOOV_BOX_SIZE_BYTES` for a streamable moov and
  leaves the slack as a `free` box when the real table is smaller, which it
  almost always is. The tell was a 360p export coming out *larger* than a 716p
  one. `media/CompactMuxer.kt` turns streamable output off for the three places
  that mux in-app; a short clip is now a fifth of the size it was.

- **An overlay's keyed filter strength read the wrong clock** - the same bug
  fixed on the base track that morning, in the copy of the line that lives in
  `CompositionFactory`, with the comment from before the fix still on it. On a
  2x overlay the look reached its last key half way through and held; the shot
  beside it on the base track with the same keys came out right.

- **The camera's beat grid was stored in the file's time** while everything
  that reads a grid reads timeline moments. Trim five seconds off the head
  shot's head and every dot sat five seconds from the beat it was heard on, so
  "Snap to the beat" snapped to silence.

- **Track and Stabilize measured in the lead file's frame rate**, not the
  clip's. On a 60 fps clip in a 30 fps project only the first half of the
  window was read and every sample was stamped at twice its real moment, so a
  pinned mask followed its subject at half speed and then held.

- **The sync envelope's buckets were whole samples, not lengths of time**, so
  two recordings decimated to different rates ran at different speeds and no
  single lag lined them up: a true three-second offset came back as 3,060 ms.

- **Three caches could not remember "nothing"** (`getOrPut` reads a stored null
  as absent), the worst of them the export's blended stills - no cache at all,
  one decode per shot the still covered, every copy held for the whole render.

- **The last run of a reverse was fed to the end of the file.** Reversing the
  first three seconds of a twenty-minute recording decoded the whole remaining
  twenty minutes and discarded every frame, with the card parked on the sound's
  share of the progress. The file came out right.

- **A full phone was let through the space check.** `free in 1 until needed`
  had a lower bound of 1 to let `freeBytes`'s -1 "could not measure" through,
  and took 0 with it - and 0 is exactly what StatFs reports on a volume full
  down to its root reserve. The render started and died in the muxer.

- **A bin entry that aged out kept its read grants**, so a project binned and
  left a month held its picker grant until uninstall, against the cap the
  phone puts on those.

- **"Earlier version" wiped the export badge for good**, because reverting
  wrote a fresh sidecar without the export stamp and the next save read "no
  export" from it.

- **A panorama's cover decoded sixty-four times too big** - the sample size
  solved against the shorter side - fourteen megabytes of ARGB_8888 for a
  56 dp row, written to the thumbnail cache at that size.

- **What was measured on the footage did not survive Reverse or Replace.** The
  reframe path and the kept stabilizer measurement are both in the file's
  clock, and both were carried through a reverse untouched while the keys
  beside them were mirrored: auto-reframe panned against the picture, and one
  nudge of the Strength slider re-solved from the old clock and wrote
  un-mirrored keys over the mirrored ones. Replace left both on a clip whose
  footage had just been swapped out.

- **The stabilizer's zoom ignored its own turn.** `requiredCrop` measured the
  translation while the same solve rotates the frame by up to 1.5°, so black
  wedges ran along the edges with the card's crop reading lower than what was
  applied. On gimbal footage - residual roll, no translation jitter - the crop
  came out at exactly zero with half a degree still being applied.

The rest: a Blank and a cancelled still render written straight to their final
names, so a write that died part way left a truncated PNG that `exists()` was
happy with; two size ladders that disagreed, one of them saying "1000 KB"; the
export estimate leaving out the AAC track; a chosen rate above the source's
being remembered as a default; `MusicSynth` effects ending on a click; a
`PcmDecoder` that could not be cancelled; and a timecode formatted two
different ways in two places.

**Eight suites were written for this sweep**, most of them for pure functions
that had never had one: `RunnerChecks`, `DraftKeyChecks`, `PrivacyChecks`,
`ShaderUniformChecks`, `ProcessorChecks`, `SearchTermChecks`, `SrtChecks`,
`EnvelopeChecks`, and then `ReverseRunChecks` and `SpaceCheckChecks` for the
last two findings - which meant pulling the arithmetic out into
`media/ReverseRuns.kt` and `media/SpaceCheck.kt`, since neither edge (a full
volume, a file far longer than the window) can be arranged on a phone. Eighty
suites now. Every fix in this sweep was negative-tested against the old code.

### What a device still has to answer from this sweep

Nothing here was seen. In rough order of what would show soonest:

1. **The muxer.** Export the same short edit and check the file is about a
   fifth of what it was, and that it still plays in the gallery, in WhatsApp
   and in a browser (the slack was for streamable output; nothing here needs
   it, but that is the thing being given up).
2. **A reverse of a short window out of a long file.** Trim three seconds off
   the head of a twenty-minute recording and Reverse: it should finish in
   about a second, and the reversed clip's last frames must be there (the
   two-keyframe margin is what protects them).
3. **The beat grid after a head trim.** Find the beat on the camera sound,
   trim five seconds off the head shot, and the dots must still be on the
   music - and Cut on beats must land on it.
4. **Track on a 60 fps clip in a 30 fps project**, and a Track aimed at the
   clip's last frame (which used to throw and say nothing).
5. **Auto-sync a 48 kHz camera track against a 44.1 kHz recording** with a
   known offset, and check the lag it reports.
6. **A stabilized shot reversed, then its Strength nudged** - it must not
   start shaking - and a gimbal shot stabilized, whose edges must be clean.
7. **A long export on this phone** (twenty-odd cuts over a light leak), for
   the blended-still cache: it used to be the shape of an out-of-memory.

## Two joins the reel had and we did not (5 October, afternoon)

Built on the desktop with no phone attached. The reel in Downloads was read a
second time, frame by frame off the file rather than by eye on the phone, and
two of its joins turned out not to be any of the twenty-six kinds we had:
**Burn out** (the old shot turning white and hanging over the new one as a
thinning ghost - the new shot is whole underneath from the first frame) and
**Blur** (a focus pull through the cut). `docs/COMPETITORS.md` §4 has the
reading; the arithmetic is executed in `tools/jvm/ExportPlanChecks.kt` and
negative-tested. "Animate every photo" became "Move every shot" on a track that
is not all photos, which is the other thing that reel does to every one of its
twenty-four shots.

On the phone, in this order:

1. **A Blur join on two main-track shots.** Softest at the cut, sharp either
   side of it. Then export and compare: the preview and the file run the same
   nine taps on the same number, but by different routes - the preview softens
   the *decoded picture* in the surface's effects pass, the file the *finished
   canvas* in TransitionEffect - so on a shot cropped or placed much smaller
   the softness should read a little wider on screen. Note how much.

   **And the question this one really has to answer:** does it read as a
   *defocus*, or as three copies of the picture? The ring is nine taps on a
   3x3 grid - the effects library's own Blur, deliberately, so a placed blur
   and a defocus join are one program on one number - and at the peak reach of
   0.010 those taps are about 11 px apart on a 1080-wide frame. That is
   undersampled: on a high-contrast edge it may treble rather than soften. If
   it does, the fix is a *disc* of thirteen taps at the golden angle on two
   rings instead of the grid - smoother for the same order of cost - and it has
   to be made in all four copies of the kernel at once, or they stop agreeing:
   `squish_fx_es2.glsl`, the AGSL copy of it in `editor/CanvasFx.kt`,
   `squish_transition_es2.glsl` and `squish_premultiply_es2.glsl`. (The library's
   own Blur at full is wider still - 0.021, about 23 px apart - which is now the
   ceiling the rest is held to; it is the widest ring that has ever shipped, so
   it is also the best evidence of what the grid looks like.) Judge the
   library's placed Blur at full strength at the same time; it has the same
   ring and has never been looked at closely either.
2. **A Blur scrubbed through rather than played.** The softness is a GL
   uniform, so it needs a frame drawn to reach the screen; `PreviewEngine
   .remember` asks for a redraw when it changes. Drag the strip slowly across
   the join: the picture must soften and sharpen under the finger, not hold one
   softness until playback starts.
3. **A Blur on an overlay** (one butted after another on its row). An overlay's
   chain ends in the premultiply pass, not the effects pass, so its softness
   goes through a different shader - the same nine taps, written twice. Check a
   *keyed* overlay's hole softens with its edge rather than keeping a hard rim.
4. **A Burn out on two shots.** The new shot whole underneath from the first
   frame, the old one white over it and thinning. Then on an overlay, where it
   is the overlay arriving *out of* the white - which is a special case in
   `ExportPlan.arrival`, since an arrival has no shot leaving to burn.
5. **Both against a file**, and a Burn out over a padded canvas and over a
   keyed overlay: the white must land on the picture and not in the hole.
6. **Move every shot** on a reel of a dozen video shots: twelve slow moves,
   five presets alternating, one undo step, and a second press changing
   nothing. On a track of photos the button still reads "Animate every photo".
7. **The Blur tile on the sheet**, which blurs the thumbnail whole rather than
   per shot and exaggerates the reach to read at that size. Below Android 12 it
   is the dissolve underneath - not this phone, but worth knowing.

## Sweep five: the edit commands, the quick tools, the shell (5 October)

The four sweeps before this went over the UI and then under it, into the export
and media layers. This one went over the layer between: the commands the
toolbar actually calls - `editor/edits/*`, the editor's own view model - and the
two places the editor is not: the quick tools and the dashboard, plus every
network call. Eighteen hunts over six groups and three lenses (wrong
arithmetic, state left behind, two places that must agree and do not), two
adversarial refuters per candidate, both of which had to fail to refute.
**Twenty-two confirmed**, and a handful more confirmed by reading them against
the code here. All fixed, none seen on a phone.

The ones worth remembering:

- **"Take out every um and uh" could cut half a minute of footage.** Fillers
  were merged on their position in the *word list*, and that list is every
  line's words laid end to end - so a line ending "…and um" followed by one
  beginning "uh, so…" merged into a single stretch covering the whole gap
  between them. The suite's own figures: two "um"s twenty-seven seconds apart
  came out as one cut of 29,000 ms, inside one undo step, with a notice reading
  "2 filler words removed".

- **Deleting a word that took nothing out still moved every caption after it.**
  The removal is two halves - the timeline, then the lines - and the second ran
  whether or not the first did anything. `withSpanRemoved` refuses a stretch
  with no clip in it, so a word over a gap slid every caption after it off its
  footage, for good.

- **x2 on a song's beat grid doubled its dots and left the tempo where it was.**
  The new tempo and bar phase were read off the *camera* grid, which is empty
  whenever the grid is on a sound - and `BeatMap.doubled` hands a list that
  short straight back. Twice the dots with every fourth still marked is every
  eighth beat of the new pulse.

- **The camera's beat grid listened to one file and was placed by another's
  trim** - `sourceUri` against `headVideoClip` - which are the same file only
  until a shot is carried to the front.

- **Deleting a song left the playhead past the end of the edit**, because that
  one removal did not go through `mutateTimeline`, which is where the playhead
  comes back and where a deleted clip leaves the Select more set.

- **A freeze of a keyed clip was not the frame that was on screen.** The
  placement and the mask were read at the moment; the opacity and the filter
  strength were taken from the static fields, which on a keyed clip are only
  the fallback the keys replaced.

- **The layer moves converted the resting level and left the key track raw**, in
  both directions - so a keyed shot floated out of a muted edit went from
  silent to fully audible, and a keyed fade carried to the main track went on
  fading where the Opacity sheet is not even offered.

- **Even out volume and Duck under speech both forgot the camera switch is the
  main track's only**, so a picture-in-picture was compared in the wrong domain
  and, with the camera off, ignored entirely while still being heard.

- **Relink carried the old footage's measurements onto the new file** - the
  stabilizer, the person masks, the reframe window, the beat grid, and a
  `reversedFrom` pointing at a render of a file that is gone.

- **Three "go to" buttons landed somewhere else**: a keyframe's, an effect's and
  a line's all snapped, so the key you tapped was no longer the key under the
  playhead and the next slider move wrote a second key beside the first.

- **Snip's trim handles still ate each other**, below the width this morning's
  fix reached. Laying both touch targets inside the kept stretch cannot work
  when the stretch is narrower than two targets; they meet at its middle and
  reach outward now.

- **The stock search read "couldn't reach" for every character but the last**,
  because `runCatching` caught the cancellation of its own effect - the same
  bug the music search carries a comment about having fixed.

- **The dialog people agree to did not mention the one thing it sends of
  theirs.** Translate captions is gated by it and sends the caption's words;
  Settings' card was changed to say so and this copy was not.

- **A Stitch quietly dropped a clip whose file had gone and said it was saved**,
  and **a staged project deleted from the grid was destroyed in place** - no bin
  entry, an Undo that did nothing, and its picker grants held until uninstall.

Eight suites gained assertions for these, and `TrimRules.handleBoxes`,
`ReverseRuns` and `SpaceCheck` were pulled out as pure functions so the
arithmetic could be executed at all. Every fix was negative-tested against the
old code.

### What a device has to answer from this sweep

None of it has been seen. In order:

1. **The filler pass on a real transcript**, with "um" at the end of one line
   and "uh" at the start of the next: two short cuts, not one long one.
2. **Delete a word over a gap** on the main track: nothing moves, including the
   captions after it.
3. **A song's beat grid at x2**: the dots double, the bpm on the card doubles,
   and "Every bar" still falls on the bar.
4. **Delete a song that runs past the last shot**: the playhead comes back.
5. **Freeze mid-fade on a clip with a keyed opacity**: the still matches the
   frame it was cut from.
6. **Float a shot with a keyed level out of an edit with the camera sound off**:
   it stays silent. And To main with a keyed fade: the fade is gone.
7. **Even out volume with the camera at 30% and a PiP on screen**; **Duck under
   speech with the camera off and a talking PiP**.
8. **Relink a stabilized, reframed shot**: the new footage plays plain.
9. **Trim a sixty-second clip down to two in Snip**: both handles still answer.
10. **The stock search, typing quickly**: no "couldn't reach" between letters.

## Sweep six: the model, the preview engine and the shell (5 October, evening)

The sixth and last broad sweep: the layer *under* the commands - the one model
every command edits, the rows and lanes, the preview engine and the Compose
surface it draws on, the editor's own derived state, and the settings and
navigation. Eighteen hunts over six groups and the same three lenses, two
adversarial refuters each.

What this layer costs when it is wrong is not one tool misbehaving but every
tool that reaches it. The ones worth remembering:

- **A stretch taken out over a gap moved the sound further than the picture.**
  The other rows came back by the stretch's own length while the main track
  closed up by what it actually lost - and those differ whenever the stretch
  runs into a gap. Everything after landed early, off the words it was cued to,
  which is the one thing the operation exists to keep together.

- **Taking a stretch out could leave two overlays on one row** - one player in
  the preview, two layers in the file - because a cut refused for leaving a
  sliver under 200 ms left one overlay in place while its neighbour slid back
  into it.

- **Remove silences re-seated sounds onto video rows**, since `isOverlay` is
  `layer > 0` and says nothing about kind, **and shots past the decoder
  ceiling**, since the free-row search was never given the clip's own top row.

- **Changing a blended still's mode or opacity did not reach the preview.** The
  held still was keyed on the two clip *ids*, and an edit makes a new clip with
  the same id; nothing happened on screen until the shot under it changed, and
  then everything did at once.

- **A shot's own shape was read off whichever surface answered first.** With one
  shot covering the playhead both surfaces carry its id, and the idle one has no
  shape - so half the time auto-reframe's focus and the Crop and Mask tools'
  coordinates were measured against the project's shape rather than the clip's.

- **The safe-area guide was drawn on the canvas, not on what the file keeps**,
  so with any crop it promised room the file does not have. It was right while
  nothing was cropped, which is how it passed on 4 October.

- **The export sheet read a padded canvas as cuts-only**, so "Keep HDR" offered
  itself and the codec row locked to HEVC on an edit the render was always going
  to composite and tone-map.

- **A slideshow of photos offered a Camera sound switch that moved nothing**: a
  main-track still is an MP4 this app renders with a silent track, so the test
  for "any shot has camera sound" said yes for an edit with no camera in it.

- **Paste attributes quietly turned a 300% sound down to 100%** (it clamped a
  sound's level to a picture's range) **and was the fourth place a look is set**
  that could leave a strength track behind.

- **On a phone set to German every speed chip read "2,x"** - seven labels
  formatted with the phone's own locale and then trimmed the trailing zeros and
  the point, which cannot see a comma.

- **Snip's trim handles still ate each other** below the width the morning's fix
  reached, which is also where `ControlChecks` turned out to be holding the
  implementation to one particular wrong answer: it passed for as long as the
  bug lived and failed the day it was fixed.

Three checks written during this sweep did not bite when first tried, and
CLAUDE.md now says to break the code again and watch each one fail. Two scratch
files a hunting agent wrote into `tools/jvm` reached commits before being taken
out; `RunnerChecks` is what catches those, and it did.

### What a device has to answer from this sweep

1. **Delete words over a gap** on the main track: the captions after it move by
   what the picture moved, not more.
2. **Blend a still over a shot, then change its mode and its Opacity**: the
   picture changes as you change them, not when the shot under it does.
3. **A portrait clip cut into a landscape edit**: auto-reframe follows its
   subject, and the Crop and Mask tools' handles land where the finger is -
   whichever surface happens to be idle.
4. **The safe-area guide with a 9:16 crop on a landscape edit**: the dashed
   rectangle is inside the picture the file keeps.
5. **An HLG clip on a padded canvas**: the sheet says the file will be
   converted, and the codec row is not locked to HEVC.
6. **A slideshow of photos**: no Camera sound row.
7. **Trim a sixty-second clip to two in Snip**: both handles still answer.
8. **Remove silences on a shot with overlays above it**: no two overlays on one
   row, and no footage above row three.

### Sweep six's own nine, and what they need from a phone

The nine the refuters confirmed (the rest of that sweep's claims were fixed from
their text as they arrived, and are in the section above):

1. **Freezing a shot's opening frame took the dissolve off the cut before it.**
   With no half to cut, the still goes in front of the whole shot - and the
   transition stayed on the shot, so the dissolve the person set on the previous
   cut vanished and a new one ran from the frozen frame into the shot it was cut
   from. *On the phone:* a Dissolve on a join, playhead snapped to that cut,
   Freeze - the dissolve is still on the join and the edit is longer by the
   still and nothing else.
2. **A zoom transition over a placed shot put the picture 195 px off.** The
   preview folded the placement and the transition into one layer as a plain
   sum; the file applies them as two passes, so the transition's scale scales
   the placement's offset too. *On the phone:* Placement across to 0.6, a Zoom
   or Pop in on the join, export and compare the same frame.
3. **A slide under a crop travelled the canvas, not the frame the file keeps.**
   *On the phone:* a 9:16 frame on landscape footage with a Slide on a cut -
   the outgoing shot leaves the picture at the same moment on screen and in the
   file. (What is still open: the transition's scale turns about the output
   frame's centre in the file and the canvas's centre on screen, which differ
   only for an off-centre crop. That needs two layers and a device.)
4. **The picture held across a hard cut snapped out to fill the canvas** - the
   one draw that read the player's size without the file-shape fallback. *On
   the phone:* a portrait shot cut into a landscape edit, played across a cut
   into something heavy enough to hold.
5. **The mask outline did not follow the picture the stabilizer was moving.**
   *On the phone:* Stabilize a shot, then Cutout → Mask on it, and scrub.
6. **A shot's own shape was read off whichever surface answered first.**
7. **The safe-area guide was drawn on the canvas, not on what the file keeps.**
8. **"Sound only" refused an edit whose first clip was silent.** *On the phone:*
   open a project on a clip with no audio track, add one that has sound, Sound
   only → Render.
9. **Settings counted stills that Clear could not take.** *On the phone:* pick a
   Background picture on a padded canvas, export, delete every project, then
   Settings → Storage → Clear "Photos and freezes" - the number goes to nothing.

## Sweep seven: the shared components, the effect wrappers, the audio engine (5 October, night)

The seventh sweep went at the layer under *everything*: the composables every
sheet is built out of, the Media3 effect wrappers, the colour pipeline's CPU
copy, and the audio processors. Fifteen hunts over the same three lenses, two
adversarial refuters each - eighty-seven agents in all.

What this layer costs when it is wrong is not one screen but every screen that
uses it, and - for the audio and colour halves - a difference between what you
hear and see while editing and what comes out of the render. The ones worth
remembering:

- **Every switch in the app said "switch" and never said on or off.** The one
  control was a `Modifier.clickable(role = Role.Switch)`, and `Role.Switch` is
  only the *name* a screen reader gives a node; the on or off after it comes
  from the node's `ToggleableState`, which only `Modifier.toggleable` sets. Nine
  switches go through it - the privacy switch among them - and not one carried
  semantics of its own, so with TalkBack on the colour of the track was the only
  answer to which way a switch was.

- **Enhance made room hiss 1.4x louder for the first three seconds.** The noise
  floor had no time constant downward and the level envelope started at nothing,
  so the floor was dragged to its minimum on the very first sample and
  `INITIAL_FLOOR` never survived one - setting it to 0.9 produced byte-identical
  output. With the floor at the bottom the gate read wide open on room tone and
  the presence lift went on the hiss. Media3 flushes the processor on every seek
  and at the start of every clip, so a montage of short takes never got anything
  but the boost.

- **A clip's level reached the voice from opposite sides in the two places.**
  The export folded it into the mixer's channel matrix, which runs *before* the
  voice; the preview applies it at the player, which is *after*. The saturating
  voices are tanh, so the file and the preview disagreed about the timbre and not
  only the loudness - Megaphone at half level, 18.5% of RMS apart.

- **A photo overlay's vignette and hue wheel were not the file's.** The CPU copy
  of the grade ran the vignette on the byte-clamped colour where the shader
  multiplies the float it is still carrying, so anything lifted past white was
  flattened to 255 and *then* darkened - a grey band in a white wall that is not
  in the file. And the hue wheel and the HSL bands read an unclamped colour where
  the shader reads `rgb2hsv(clamp(c, 0.0, 1.0))`.

- **A curve that turns went brighter than either point.** The monotone fit was
  missing Fritsch-Carlson's sign step, and the circle constraint that followed
  scales a tangent without changing its sign - so a point placed below the one
  before it made the picture brighter than either of them before coming down.

- **A line that shrank to fit never grew back.** Only a change of words reset the
  scale, so a tile's name that shrank at three across stayed small at two across.

- **A preview whose probe failed had no bar to drag**, and **a waveform that
  could not be read waited for ever** - one nullable saying both "not started"
  and "came back with nothing".

- **A preview cap that could only ever make the box shorter.** The quick tools
  ask for 320dp and `heightDp` had already cut the height to 300, so the ask did
  nothing and they have been showing the editor's height all along.

### What a device has to answer from this sweep

Everything above is reasoned, executed on the JVM, and compiled. Nothing has
been heard or seen. In rough order of what would be learnt per minute:

1. **Enhance on a take that opens with room tone.** Record a talking head with a
   second of silence before the first word, set the clip's voice to Enhance, and
   listen to that second: it must be quieter than the untreated clip, not
   hissier. Then cut the clip into three-second pieces, Enhance each, and export
   - no piece is long enough for the old floor to have climbed back, so every one
   of them used to be 1.4x hissier than the source.
2. **Megaphone at half level, preview against file.** Megaphone on a shot, camera
   level to 50%, listen in the preview, export, listen to the file. They must
   now be the same voice. Radio and Telephone are the same test at a fifth of
   the difference.
3. **A photo overlay with Brightness up and a vignette on**, beside the video it
   is over: the bright part of the photo must be as white as the video's, with no
   grey ring where the falloff begins. Measure it off a screencap rather than
   judge it by eye, as the LUT leg of this was measured at dawn on 4 October.
4. **The Curves tool with a point pulled below the one before it** - a highlight
   rolled off. Nothing between the two points may be brighter than the higher of
   them, on screen and in the file.
5. **TalkBack on, Settings open.** Focus "Ticks when snapping": it must say
   "on" or "off". Then the privacy switch, Mute on a clip, Keep HDR.
6. **A quick tool's preview** is 320dp tall now rather than 300. Check the
   controls under it are still on screen on this phone, upright and on its side.
7. **The done screen on a gallery URI** whose duration the retriever will not
   answer: the bar must move and be draggable. A file with no sound track in a
   quick tool must say "No sound could be read from this video." rather than
   sitting on the music glyph.
8. **A tile's name on a two-across row after a one-across**, or the phone turned:
   the words must come back to full size, not stay small.

### The channel count, closed from the source rather than left open

This section first said the preview's voice seeing the source's own channel
count while the export's saw the fold-down was left open for a phone. It is not
open; it was answerable from Media3's 1.11.1 source, which is in the Gradle
cache. `DefaultAudioSink`'s pipeline is trimming, channel mapping, to-16-bit,
then the app's own processors - and `ChannelMappingAudioProcessor` is handed a
null map except on two device workarounds (`MediaCodecAudioRenderer` builds one
only for `codecNeedsDiscardChannelsWorkaround` and the Vorbis layout), so
nothing folded a 5.1 file down before the custom chain: the AudioTrack was
opened with six channels and the HAL mixed them. The export folds down first,
with `ExportPlan.downmixCoefficients`.

So two disagreements, not one. A saturating voice saw six channels on screen and
two in the file; and *with no voice at all* the preview was hearing the phone's
own fold-down while the file had ours. `AudioMixing.processor(1f)` now sits in
front of the voice on both the video and the sound players.

A matrix for every channel count up to 48 came with it, since a count with no
matrix makes `onConfigure` throw - a failed render on a nine-channel file
before, and a sound that would not play at all once the preview used the same
processor. Past 7.1 the first two channels are the stereo pair and the rest are
dropped.

*On the phone*, which is B5's device item (8) with one thing added to it: a
6-channel AAC file at camera level 50% with music, now also with Megaphone on
the shot - the preview and the file must be the same voice, and a 5.1 film's
dialogue must be as present on screen as in the file.

## Sweep eight: the text, vision, online and persistence layers (5 October, night)

The four layers no sweep had been over: the caption model and its renderers,
the vision code (tracking, stabilizing, auto-reframe, segmentation), the online
layer, and everything that writes a draft or a record to disk. Twelve hunts
over the same three lenses, two adversarial refuters each - ninety-two agents.

This sweep found the two worst faults of the day, and both were invisible from
inside the app:

- **Sixteen captions could not be exported at all.** Every caption, sticker and
  shape went into one Media3 `OverlayEffect`, and `OverlayShaderProgram` binds
  them as samplers and refuses more than fifteen in one instance - a
  `checkArgument` that fails the render at its first frame. A speech segment is
  at most 4.2 s, so a minute of auto-captioned talking is fifteen lines and two
  minutes is thirty; an imported film .srt is hundreds. The preview draws them
  on a Compose canvas with no limit and showed every one. The feature the app
  leads with could not be rendered.

- **On an Arabic, Persian, Burmese, Bengali or Nepali phone, "Export subtitles"
  wrote a file nothing can read.** The timestamps went through `format` with no
  locale, and `%02d` emits the locale's own digits - so the timing lines came
  out in Eastern Arabic-Indic or Devanagari numerals, which no subtitle tool
  reads, this app included: its own `STAMP` regex is `\d`, ASCII-only in Java.
  Importing the app's own export found no cues and reported the file unreadable.

And eleven more, each fixed with an executed check or a source one:

- **A caption's eighth word arrived on the seventh's beat.** The Words arrival
  passes a word *count* through a Float fraction and multiplies it back; 7/13
  returns as 7.0000005, whose ceiling is 8. The suite had only ever tested three
  words, where the trip is exact.
- **Editing by transcript could take the editor down.** The chosen run was two
  remembered indices into a list rebuilt whenever the lines changed, so an edit
  from outside the panel left them past the end and `words.slice` threw during
  composition.
- **A caption with a blank line in it lost everything after it**: a blank line
  ends a cue in SubRip, and the writer trimmed only the ends.
- **The tracker's fine pass walked off the place it was refining** - it read
  `bestX + dx` while assigning `bestX += dx` in the same loop, so the 3x3 was
  taken about a moved centre and each frame's search starts from the last one's
  answer.
- **A long shot's auto-reframe lagged its subject by seconds**: the smoothing
  window was sized in samples and the sample count caps at 360, so past ninety
  seconds the window grew with the clip.
- **Four consecutive reframe samples could be one frame** (`OPTION_CLOSEST_SYNC`
  answers with the nearest keyframe), and the duplicates read as "no motion" and
  dragged the crop to the middle of the frame.
- **A pinned overlay pulsed twelve per cent**: `TrackSample.scale` is re-picked
  every frame from three candidates and was multiplied straight into the layer's
  size at every key.
- **A reversed clip's stabilizer keys are one frame early** - the times are
  mirrored without re-pairing them with the motions they belong to. Found, fixed,
  and then **taken back**: the re-pairing needs a frame period, and guessing it
  from the spacing is right only for the evenly spaced times the stabilizer
  itself produces and wrong for any others, and it stops the mirroring being its
  own inverse - which Reverse, and Reverse again, depends on. One frame of a
  smoothed correction is a sub-pixel difference; a mirroring that does not
  round-trip is not. The cost is written down on `mirroredAt` instead, with what
  it would take to have both.
- **A no-derivatives clip was offered as free b-roll.** The licence filter was
  the Archive query alone, and its `*-nd*`/`*-nc*` patterns cannot see the CC
  1.0 codes, which carry no hyphen.
- **Downloaded music could not be got rid of at all** - no storage kind measured
  `files/music/online` and no sweep touched it - and downloaded stock clips were
  counted and unreachable, which is the third time a row has counted a file its
  Clear could not reach.
- **"Keep HDR" with one caption on an HLG clip** asks Media3 to keep HDR and
  then hands it a plain bitmap overlay, which it refuses outright below Android
  14.

Also: a binned quick tool left to age out kept its picker grant for ever; an
export moved to the gallery's Bin was forgotten for good; a single tapped beat
went missing on every save; an edit whose shots had all been deleted reported
the length of the file it was opened on; a resumed quick-tool session that was
emptied left its draft on disk; and a multi-valued Archive title became the
literal text `["A","B"]` on the timeline.

### What a device has to answer from this sweep

1. **Auto-caption two minutes of talking and render it.** That is the sixteen-
   overlay limit; before tonight it failed at the first frame. Then do it again
   with thirty-odd lines and check the captions are all there, in the right
   order and the right one on top where two overlap.
2. **Set the phone to Arabic and export subtitles**, then import the file back.
   The timing lines must be ASCII digits and the cues must come back.
3. **Auto-caption a thirteen-word line and watch the Words arrival** against the
   speech: each word must land on its own word, not a third of a second early.
4. **Open "Edit by transcript", choose a run of words, then undo** something
   from outside the panel until the transcript is shorter. The choice must go
   away; it used to take the editor down.
5. **Track something, pin an overlay to it, and watch the overlay's size.** It
   must not pulse at the frame rate. Then reverse a stabilized clip, nudge
   Strength, and reverse it back: it must be as steady as it started - the keys
   sit one frame earlier than the frames they describe on a reversed clip, which
   is accepted and written down, and what has to hold is that reversing twice
   returns exactly what went in.
6. **Auto-reframe a three-minute handheld shot with no face in it** - the window
   is a length of time now, and the duplicate keyframes are skipped, so the crop
   should follow the subject rather than sitting between it and the middle.
7. **"Keep HDR" on an HLG clip with a caption**: the switch must be off, dim,
   and say why.
8. **Settings → Storage → Clear "Reversed renders, imports and downloads"**
   after downloading a stock clip and a song: the number must go to nothing.

## Sweep nine: the audio analysis, the stills, the drawing layer, the export screens (6 October)

Twenty-two distinct faults, each confirmed by two adversarial refuters, all
fixed and **none seen** - this machine has had no phone attached since 5 October
at about 07:00. Each has an executed JVM assertion or a source assertion in
`tools/jvm/ControlChecks.kt`, and almost all are negative-tested against the old
code.

The ones with the longest reach:

- **The whole waveform layer took the decoder's PCM layout from the container.**
  `PcmDecoder.decodeFrames` read the sample rate and channel count off the
  MediaExtractor's track format, handed the rate to its caller before
  `codec.start()`, and had no `INFO_OUTPUT_FORMAT_CHANGED` branch at all - that
  constant is -2, so it fell straight through `if (outIndex >= 0)` and was
  dropped in silence. HE-AAC's SBR doubles the output rate over the esds's, and
  HE-AACv2's parametric stereo decodes a mono-signalled stream to two channels;
  where they disagree, every length this layer reports is wrong by that factor.
  `decodePeaks` reported a minute of audio as four, so the strip drew the
  waveform against a length four times the file's and showed the first quarter
  of it stretched across the clip; the beat detector was handed audio that slow;
  the auto-sync offset was scaled. `ReverseRenderer` had always re-read all
  three values, so the project's two readers of one file disagreed about what a
  frame is.
- **A talking shot cut out of a long recording had no speech in it.** The speech
  finder's dynamic-range gate measured "loud" as the 95th percentile of every
  frame, which answers "is more than a twentieth of this loud" rather than "is
  there a loud part". Every caller decodes the whole file and applies the clip's
  window afterwards, so a 30 s talking head trimmed out of a fifteen-minute
  recording is 3% of what the segmenter sees: under about 5% the 95th percentile
  *was* room tone, the gate fired, and auto-captions reported no speech found on
  a clip that is nothing but speech - as did Remove silences and Duck under
  speech.
- **An export of a padded canvas deleted its own backdrops.** The blurred stills
  are capped at 48 files and the prune ran after every write, including the
  writes the export itself was making, so an export with more than 49 stretches
  unlinked the earliest of its own backdrops while the plan still named them.
  The whole defence was a sixty-second grace on the file's age, which is a wall
  clock. The cap was a silent ceiling on how many shots a blurred canvas could
  export.
- **Fit to 16 MB could not be met at all past about five minutes, and said
  nothing but "Try again, tighter".** The solved budget was floored at the
  smallest usable bitrate *before* the retry scale was applied, so once the
  floor bit the scale was arithmetically discarded: every retry re-rendered at a
  byte-identical bitrate, published another copy to the gallery, added another
  library row and showed the same card. And `retryFit` cleared the card before
  calling `export()`, which has five refusals that come back without rendering -
  the likeliest being the space check, which the first run's own gallery copy
  has just made more likely - after which the oversize file in the gallery could
  never reach the done screen and "Keep this one" could not be reached at all.
- **A panorama as a photo overlay was decoded whole.** The sample size was
  solved against the picture's short side while the kept size is bounded on the
  long side too, so a 12000x1200 panorama was allocated whole as ARGB_8888 -
  57 MB - for a picture kept at 5.9 MB, with the OutOfMemoryError swallowed by
  the `runCatching` round the decode, so the overlay was silently refused after
  taking the heap down with it.
- **A portrait photo straight off the camera was shown lying on its side**, on
  the project's cover card and in the library, and written to `cache/thumbs` at
  that angle where it outlived the next restart: `previewBitmap` went through
  BitmapFactory, which ignores the orientation tag, while every sibling in the
  same file goes through ImageDecoder, which applies it.
- **Filmstrip tiles showed the wrong moment on a retimed clip.** The tiles
  divide the drawn width equally while their source times divided the *source*
  span equally - the same mapping only at a flat rate. On a Bullet curve the
  eighth of ten tiles asked for 6.8 s of the file where the moment under it is
  5.6.
- **The waveform came off the beat dots on a retimed sound** by the same
  mistake, in the same file: the bars were placed in timeline time and their
  peaks picked in source time, while the dots drawn on top of them have always
  gone through `timelineAtSource`.
- **Dragging the hand-drawn crop's left or top bracket past its opposite pushed
  that edge along** instead of stopping, so an eight-percent-wide window slid
  across the whole frame and parked against the far side. `CropRect.of` clamps
  the low edges first and derives the high ones from them, so right and bottom
  were stopped correctly - which is what made the other two read as working.
- **A filmstrip row lost its asks and never asked again.** The queue's cap
  dropped the oldest, which is right for a scroll and wrong for a first layout:
  four rows of footage compose in one pass and ask for twenty tiles each, so at
  48 the top overlay row's asks were all thrown away and that row drew as bare
  lane colour until a scroll changed its times.
- **"Find the beat" could succeed and say nothing**: the listen covers the first
  six minutes of a file while the grid is read through the clip's window, so a
  sound trimmed to play from 6:10 came back with a full BeatMap of which no dot
  was reachable - and the success path ran, wiping every other sound's grid
  under an undo step while the panel showed the words it had before the tap.
- **A listen that landed after its sound had gone wiped the grid.** Nothing
  matched the target's uri, so every remaining sound took the `else` branch and
  lost the dots it already had; `removeAudioClip` cancels the auto-sync job for
  exactly this reason and nothing cancelled the beat job. Auto-sync had the
  other half of it, writing "Matched" for a clip that is not there whenever the
  clip went by undo or a multi-delete rather than through that function.
- **Remove silences counted pauses it was not going to cut**, and where nothing
  survived the minimum it reported seconds cut over a byte-identical timeline,
  with an undo step filed for it.
- **The thumbnail cache had no way to say "tried this and got nothing"**, so an
  undecodable file was re-probed on every pass of the list inside the one
  process-wide lane; it was also the one picture in the app compressed straight
  to its final path, and a truncated JPEG is not self-healing, because
  `decodeFile` returns a *partial* bitmap rather than null.
- **FilmstripLoader.evictAll left its queue and worker running** on a
  process-lifetime scope, so the worker carried on decoding the closed project's
  tiles back into the cache it had just been told to empty.
- **The encoder ceiling asked the wrong encoders** - the unfiltered list, where
  Media3 takes the hardware ones whenever any exist - so on a phone whose
  software AVC encoder advertises a larger frame than its hardware one the sheet
  promised 4K and the render wrote 1920x1088, which is the exact failure that
  file was written to prevent.
- **An abandoned proxy left its `.part` behind for ever**: a cancellation threw
  past the delete, and a cancellation is the ordinary case, because the only
  thing that cancels it is leaving the editor.
- **The done screen decoded ten minutes of audio to draw ninety bars**, about
  38 MB live at the peak, where `decodePeaks` reads one float per 50 ms - and on
  a tight heap `decodeMono` gives up by design, so the wave silently never
  appeared. The quick tools' preview card and Read aloud's waveform had it too.
- **"Save as GIF" decoded frame zero at full resolution to read two numbers**:
  33 MB as one bitmap for a 4K edit, in a scope that outlives the screen.
- **The strip's speed badge printed 1.25x where the Speed sheet says 1.3x**,
  because it used the lower-level formatter rather than the one the rest of the
  app shares.

### What a device has to answer from sweep nine

1. **Add an HE-AAC or HE-AACv2 .m4a and look at its waveform.** It should be
   drawn to the end of the clip, not the first quarter stretched across it, and
   Find the beat should put dots on the music. (This machine has no such file;
   the fault is that the code had no way to notice the mismatch.)
2. **Auto-caption a 30-second talking head trimmed out of a long recording.**
   Lines, not "no speech found". Then Remove silences on the same shot.
3. **Render a padded-canvas edit with sixty shots on Blur.** It must complete,
   with no black stretches, and the backdrops folder must be back to 48 files
   afterwards.
4. **Fit a ten-minute edit to 16 MB.** The sheet must say it cannot be met and
   what the least is, and the overshoot card must offer only "Keep this one".
   Then fit a one-minute edit to 16 MB, overshoot it, and tap "Try again,
   tighter": the second file must be smaller than the first.
5. **Add a panorama as a photo overlay**, and a portrait photo straight off the
   camera as a new project: the overlay must appear, and the card must be
   upright.
6. **Put a Bullet curve on a shot and look at its tiles**, and on a song look at
   the wave under its beat dots: both must line up with what the playhead shows.
7. **Open an edit with four rows of footage** and leave the strip still: every
   row's filmstrip must fill in without a scroll.
8. **Drag the hand-drawn crop's left bracket right, slowly, past its own right
   edge.** The window must narrow and stop, not run away.

## Sweep ten: undo steps, state after a process death, locale, accessibility (6 October)

Twenty-seven findings, two refuters each, all fixed and none seen. These are
cross-cutting properties rather than one layer, and most of them are invisible
until the one thing that triggers them happens.

- **A photo's longer rendering was lost to any undo.** A main-track photo is a
  short video rendered from the picture, and dragging its tail out renders a
  longer file and swaps it under the clip. The swap went into the live state
  alone, so an undo or a redo of *any* edit made while the render ran put the
  long clip back on top of the short file - and nothing ever asks for the
  longer render again, so the preview held the picture's last frame while the
  clock ran on, for the rest of the session and in the saved draft. It needs no
  race on the undo: an edit made during the render captured the stale pair, and
  undoing it at any later time brought it back.
- **A pick delivered before the draft was read blanked the edit.** Killed behind
  the photo picker, the app comes back and the result is dispatched while the
  launcher's effect commits - before `open()` has read a byte. Replace and
  Relink wait for the edit; Add media, Add overlay, Add sound and Background
  image did not, so the add landed at playhead 0 on an empty timeline, the pick
  vanished when the draft was applied, and the undo step *survived* - so one tap
  on Undo restored the empty snapshot and blanked the whole edit, which the next
  autosave tick wrote over the draft.
- **A caption added and never typed into was kept for good** after a process
  kill: the discard effect fired against the default empty state, found no such
  line, and cleared the handle that pointed at it - so when the draft was
  applied the line came back with nothing left to take it off, and no undo step
  for it either.
- **Every ruler tick read nine characters on an Arabic phone.** `Timecode.format`
  is locale-sensitive (`"...".format()` is `String.format` against the default
  locale), and five call sites took the milliseconds off with the ASCII literal
  `".000"`, which matches nothing under Arabic, Persian, Bengali, Nepali or
  Burmese. The ruler lays a tick label a second with no width given, so the
  labels ran into one another. This is the inverse of 5 October's lesson:
  `uppercase()` with no locale is locale-*independent*, `format()` with no
  locale is not.
- **A whole name in two characters was thrown away.** A picked file's name was
  kept only with three letters, and a name in Chinese, Japanese, Korean or
  Hebrew is routinely two - so a two-character name became "Edit · 6 Oct", and
  the Replace sheet and Track lost the name with it. The codebase had met this
  once already and written it down for captions.
- **An imported font or LUT named in any other script became "font" and
  "font 2".** The stored name was the display name with everything outside
  `[A-Za-z0-9 _-]` deleted - and that stem is what the chip shows.
- **An imported .srt in any encoding but UTF-8 came in full of replacement
  characters.** The timing lines are ASCII, so the cues parsed and the import
  reported success while every accented or non-Latin letter had become U+FFFD.
  The .cube reader had the same fault.
- **Both strips would have been drawn mirrored on an Arabic, Hebrew, Persian or
  Urdu phone.** Everything in them computes a left-origin pixel from a moment
  and places it with `offset`, which is the layout-direction-aware modifier,
  while the playhead and the drag deltas are not - the same class as a control
  that moves against the finger. (The quick trim was the confirmed one; the
  editor's own strip is the same code shape sixteen times over.)
- **Nothing in Squish could be coloured without looking.** Sixty-one colour
  swatches were bare circles whose only content was their fill and whose only
  state was a border colour, and `ColourRow` is the only colour control there
  is. Six picker grids marked a choice with a border and nothing else. The three
  grading wheels were a Canvas inside a Box, which has no node to focus at all,
  so the disc was skipped and its value could neither be read nor changed
  without a drag.
- **A size chip that cannot be used was drawn dead and wired live**, announcing
  "not selected" rather than "disabled" and swallowing the tap; in Squeeze
  neither explanatory note could fire for it, so the dimming was the only signal
  that existed.
- **The control that permanently forgets a saved text style announced itself as
  "multiplication sign"**, sat immediately after the chip it destroys, and has
  no confirmation and no undo.
- **An effect's two carries inside 700 ms became one undo step**, because a
  one-shot command carried a coalescing gesture id and nothing closed it.
- **Four background results were filed with `record` rather than `recordLate`**,
  so a slider under the finger when one landed became two undo steps with the
  result wedged between them, and the drag could not be taken back without
  losing the result. Read aloud was the one the sweep named; the class check
  found three more.
- **A video opened from another app just to look stayed on the grid for good**
  if the process went rather than a back press being pressed - which is what
  swiping the app off recents does. The rule was an in-memory field whose one
  durable record is deleted by the first save, inside the function that sets it.

### What a device has to answer from sweep ten

1. **Drag a main-track photo's tail out to 40 s, add a marker while it renders,
   then undo the marker and redo it.** The photo must still play to 40 s.
2. **Open "Add media", kill the app behind the picker (`am kill`), pick two
   clips.** They must land, or nothing must happen - the edit must not be
   blankable by one tap on Undo afterwards.
3. **Tap "Add text", press Home without typing, `am kill`, reopen.** No caption
   reading "Your text" anywhere.
4. **Set the phone to Arabic and open any project.** The ruler's ticks must read
   four characters, not nine, and the clip chips with them.
5. **Rename a gallery video to a two-character CJK name and start a project from
   it.** The card must show that name.
6. **Import an .srt saved as ANSI from Notepad.** The accented letters must be
   there.
7. **Set the phone to Arabic and open the editor's strip and a quick trim.** Time
   must still run left to right and a drag must move the picture with the finger.
8. **Turn TalkBack on and set a caption's colour, pick a filter, and tilt the
   Shadows wheel.** Each must announce what it is and which one is on.
9. **Open with a video from the gallery, change nothing, swipe the app off
   recents.** The dashboard must not gain a card.

## Nine fixes, each read in the source before it was changed (6 October, late)

No sweep behind these: a list of places to look at, each one read, the arithmetic
done by hand, and nine of seventeen found real. The eight that were not are named
at the end, because "looked at and sound" is worth as much to the next session as
"found and fixed".

- **Importing a hand-edited .cube could take the app down.** A 1D LUT's declared
  length is the file's own number and nothing bounds it. `size1 * 3` overflows
  Int above 715,827,882 and comes out negative, so the `values.size < need`
  guard was false and passed - and reading an entry of a table that holds three
  numbers ran off the end of the list. That is an IndexOutOfBoundsException,
  neither of the two exceptions LutFiles catches, inside a launched coroutine.
  The length is computed in Long now, and LutFiles catches RuntimeException as a
  last net so the next shape nobody thought of in a user-supplied file is a
  message rather than a crash.
- **A failed Reverse leaked its codec, and the failure then compounded.** Two of
  the three codecs were created as `createCodec(...).also { configure(); start() }`,
  so a throw from either call lost the instance - the assignment never happens
  and the caller's `finally` sees null - while a configured hardware session was
  held until the process died. The *next* Reverse then could not get an encoder
  at all. configure really does throw here: a frame size the AVC encoder will
  not take, or no free session because of exactly this leak.
- **A strong colour wheel came back weaker every time the project was opened.**
  A component is `master + the tint`, and the Level slider runs to ±1 while the
  dot reaches the rim, so Wheel.of produces components to ±2 - which the shader
  uses. The draft's decode clamped each to ±1. The file on disk was right the
  whole time, which is why nothing about it looked broken.
- **"Listen to" and the caption language went back to their defaults on every
  reopen.** Neither was written, read, or in ProjectSnapshot at all, so a second
  auto-caption run listened to the camera however the panel had been set and
  Read aloud spoke in the phone's own language.
- **A LUT dragged to strength 0 was forgotten.** The write gate asked
  `!adjust.isIdentity`, which is "does this change the picture" - and a cube at
  strength 0 does not. Reopening lost the cube, and dragging Strength back up
  then did nothing.
- **A clip whose shape failed to probe once never got it again.**
  PreviewEngine.fileAspect kept a set it never removed from, so one failed probe
  - a file briefly unreadable while a relink lands - meant that clip's surface
  and Crop window fell back to the edit's shape for the life of the process,
  which is the exact fault the function was added to fix.
- **A blended still was decoded whole and tagless**, in the preview and in the
  export. The preview samples to the screen now; the export still decodes whole,
  because the file is written at full resolution, but through ImageDecoder so the
  orientation tag is applied there too.
- **A stock thumbnail was decoded whole, one per tile in a scrolling grid** - and
  the Archive's thumbnail for an item is often the item's own full-size
  derivative.
- **`Clip.isGraded` had no callers**, and `StillClips.blank` recycled its frame
  after the compress rather than in a finally.

Read and found sound, so not changed: VoiceRecorder.start (releases the
recorder, the platform effects and the file on every failure path);
ProjectAutosave.revertToEarlier and ToolAutosave.revertToEarlier (bin, write
atomically, restore on failure); ProjectAutosave.save's backup copy and its
sidecar-before-replace order (the order is deliberate and the residual is a
one-save cosmetic mismatch on the card, where the alternative loses the draft
from the list); CanvasBackdrop.fromImage, write and solid; the preview's
StillPictures cache (an LruCache with a real sizeOf); the canvasFx layer's place
inside the frame clip; and every other clamp in the draft decoder, each of which
matches its model's own range - while volume, opacity and the value keys are
deliberately unclamped because a level goes past 1.

### What a device has to answer from these

1. **Pick a LUT, drag Strength to 0, reopen the project, drag Strength up.** The
   cube must still be there.
2. **Tilt a wheel to the rim with Level at 1, reopen.** The grade must not move.
3. **Set "Listen to" to something other than the camera, reopen, auto-caption
   again.** It must listen to what was chosen.
4. **Import a .cube with `LUT_1D_SIZE 800000000` in it.** A message, not a crash.
5. **Reverse a 4K clip on a phone whose encoder stops at 1080**, twice. The
   second attempt must fail the same way as the first, not worse.
6. **Open the stock-footage grid and scroll it.** No growth, no crash.

## On the phone again, after a fortnight of building blind (6 October, night)

The owner plugged the phone back in with two sentences: *"when a new video is
selected, it is starting from mid screen not the left end which is terrible"*
and *"further the play head is not moving"*. Both were true, both were about the
same screen, and neither was the whole of what was wrong there. Working down
`docs/ROADMAP.md` §5 from the top then found an export that could not run at
all.

### 1. The strip opened half way across, and crept

**Seen.** The playhead line was fixed in the *middle* of the strip, so the
window at 0:00 is scrolled half a screen before the start and the edit begins
half way across with nothing to its left. The fit-on-open matched it - the whole
edit laid out in *half* the strip - so an opened video was drawn at two thirds
of the size it needed and crammed into the right-hand half. Measured: 1.9
seconds of playback moved the strip twelve pixels, which is not slow, it is
invisible.

Fixed by moving the line to a quarter of the way across
(`TimelineWindow.PLAYHEAD_FRACTION`) and stopping the fit at thirty seconds of
footage (`TimelineLanes.MAX_FIT_SECONDS`). **Seen after:** the clip starts a
third of the way across the screen instead of past the middle and is drawn half
as big again; the ruler reads 0:00/0:10/0:20/0:30 where it read
0:00/0:30/1:00; playback scrolls about sixteen pixels a second, plainly moving.

**The line is still fixed, and that is deliberate.** Letting it walk again is
what the first version did, and near 0:00 the strip is clamped, so a finger has
nothing to pull and the code moved the *time* instead: dragging right moved the
line left, which is the first thing the owner ever reported about this app. One
gesture cannot mean two opposite things.

**Seen and correct:** a drag to the right still takes the edit backwards
(0:36.9 to 0:25.6), the film following the finger.

### 2. The compact muxer, on the path of every export

**Seen.** A three-second cuts-only export is 183,266 bytes, of which
`tools/jvm/Mp4Probe.kt` accounts for 179,388 bytes of sample data - **97.9% of
the file**. The 400 KB of streamable-moov padding is gone; at this size it had
been two thirds of the file. 91 video frames at exactly 33.33 ms, 30.000 fps
even. Plays in the app's own done screen. Not checked: WhatsApp and Chrome -
sharing to a person is not a thing to do from a test.

### 3. A photo first in a composited export killed it — the gate's first run

**Found, fixed, seen fixed.** A photo, a video, a Dissolve on the join:

    SquishExport failed: 3 sequences
    ExportException: Asset loader error
    Caused by: NullPointerException
      at SequenceAssetLoader.onOutputFormat(:344)
      at ImageAssetLoader.queueBitmapInternal(:205)

Media3 will not start a sequence that declares sound on an asset with only a
picture: it asks its listener for a forced audio consumer and `checkNotNull`s
the answer, while `TransformerInternal` answers null until every *other*
sequence has registered its tracks. An image loader has no file to open, so on a
composited export it always wins that race.

`StillClips` has known this since photos were added - its rendered stills carry
a track of silence and its comment says so. What reopened it was B14 sending a
main-track photo in as the picture it was made from, for sharpness, which has no
sound track at all. `ExportPlan.mustCarrySound` now names the one clip that
opens such a sequence; that clip goes in as its still and every photo after it
keeps its full size.

**Seen after:** the same edit exports (`frames=166 size=309988`), and read frame
by frame off the pulled file the dissolve is there from 2.6 s to 3.0 s, the
photo fading out as the video fades in.

### 4. The rest of the composited gate, and the two new joins

All seen in exported files, read frame by frame with `tools/desktop/frames.ps1`
and `contact_sheet.ps1`:

- **A video overlay at 40%, starting at 2 s, over a dissolve** (§5 step 3, and
  the gap of step 4 on the overlay row): exports, and the PiP is top-right from
  2.0 s, over the dissolve at 2.5 s, gone after 5 s with nothing left behind.
- **Blur** (5 October's first new join): the base picture softens through the
  cut and comes back sharp, 2.6 s to 3.0 s — **and the preview agrees**, which
  is what this one was for: the preview softens the decoded picture and the file
  softens the finished canvas, by different routes. The overlay stays sharp in
  both.
- **Burn out**: the outgoing shot blows out white at 2.6 s and hangs over the
  new one as a thinning ghost to 3.0 s, with the new shot whole underneath from
  its first frame - not hidden, which is what makes it different from the Flash.
  The overlay is not whitened.

Still unseen of these: a Blur on an overlay's *own* transition (the softness
through the premultiply pass), and a Burn out over a padded canvas.

### 5. Reverse, on an eight-hour recording

**Seen, and it is the strongest form of §5 step 5 there is.** The longest file
on the phone is a school annual day: **7 hours 58 minutes**, 360p. It opened in
the editor in under twenty seconds, with the strip drawn at the new thirty-
second fit - which is itself worth noting, because the *old* fit would have laid
eight hours across half a strip.

Split at 2.428 s, the tail deleted, Reverse on what was left: the card said
"Reversing … 9% · it lands on the strip when done" with a Cancel, and it was
done within about fifteen seconds. The render is **525,095 bytes**, and
`Mp4Probe` reads **73 video samples, 2.433 s, 30.000 fps, every frame exactly
33.33 ms**, with 107 AAC samples beside them. 73 frames is exactly what 2.428 s
at 30 fps should be: the run window is bounded and nothing is missing from
either end. Had `media/ReverseRuns.kt` still fed the last run to the end of the
file, this would have decoded the remaining eight hours.

Read off the render: it plays backwards (the person walking in front of the
stage in the original's first frame is in the *last* frame of the reverse).
**No `.part` file left behind.**

### 6. Music, text and a style, added and exported

All first-time sightings; none of B9's or B10's screens had ever been driven.

- **The Sound sheet** is Music · Mic & camera · Sync, and the Music card's four
  categories are a two-by-two grid in sight on a phone - both as B9 decided.
  "Lo-fi Sunset · Chill · 78 BPM · 49s" added from Squish originals, synthesised
  on the phone, and landed as a sound clip with its waveform drawn. With the
  playhead parked on the last moment it was **backed up to end with the edit**,
  which is `EditRules.soundLanding` doing what its comment says.
- **Add text** puts "Your text" in the middle of the picture with the keyboard
  up and **the sample words selected** - typing replaced them rather than
  appending. The tabs are Keyboard · Style · Bubble · Animation, the keyboard
  folds when another tab is picked, and the strip folds away while typing. The
  line landed 0:00.428 → 0:02.428, two seconds ending at the playhead.
- **Style** offers presets, "Save this style", the five Looks with **Outline**
  already on (`TextStyleSpec.NEW_LINE`), eight fonts, "Free fonts online…",
  bold/italic/underline and the three alignments. Picking **Neon** turned the
  words pink and glowing on the picture at once.
- **Exported**: the line is in the file, pink Neon, in the middle, from 0.5 s to
  2.4 s and *not* at 0.0 s - which is the line's own span, so the timing holds
  as well as the drawing.

Still not heard: any of it. Nothing here says whether the music, the camera
sound or a voice effect is right **by ear**; only that the track is there, at
the right length, and that the file carries it.

### 7. Speed, a shape, rotation, Snip and the library

- **Speed.** 0.61x on a 2.428 s shot: the header went to 0:03.980, the sheet
  read "0:02.428 of footage · 0:03.980 on the timeline", and the strip grew a
  **0.61x** badge. The sheet also said **"18 fps out — will step. 40 fps footage
  would not."** and offered "Keep it smooth · Nothing slower than 0.8x — the
  slowest this footage carries". The exported file is **73 samples, 3.989 s,
  18.300 fps, every frame 54.64 ms** - which is exactly 73 source frames
  stretched over 3.98 s. The warning was true to a tenth of a frame, and the
  music ran on to the new end (4.063 s of audio).
- **A Solid shape**, which `CLAUDE.md` listed as unseen. Stickers → Shapes and
  arrows → Star drops an outlined star at the playhead with its four corner
  buttons; its Placement sheet has the eight shapes, an **Outline / Solid**
  toggle and a Line slider. Solid fills it, on screen and **in the exported
  file** (0.8 s, 1.6 s, 2.4 s), beside the Neon text, both gone by 3.2 s where
  their spans end.
- **Rotation.** Portrait → landscape while the editor was open: two panes, the
  picture carried across **not black**, the strip redrawn with the playhead at
  its quarter. Back to portrait: the same. This is B6's "check the picture does
  not go black or stall after rotating".
- **Snip**, the quick trim. It asks for its picker once, shows the filmstrip
  with two handles, a frame button either side of each readout, and "1:15.599
  kept". **A slow drag of the left handle moved it** - to 0:13.992, "1:01.607
  kept", with the preview jumping to the handle moved. That is
  `TrimRules.draggedTo`: before it, each event alone was under half a frame and
  rounded back, so a slow drag never moved at all.
- **The library** lists 44 exports with their lengths and sizes, and it carries
  its own evidence for the compact muxer: the *same* three-second edit reads
  **183 KB (6 October)** against **579 KB and 1.8 MB (5 October)**.

### Still owed to the phone after this session

Nothing here was **heard**. The music, the camera sound, a fade, a voice effect
and a voiceover are all still unjudged by ear, and no amount of probing a file
answers them.

Unseen still, from the lists above: a Blur on an overlay's own transition, a
Burn out over a padded canvas, Track and Stabilize on 60 fps footage in a 30 fps
project, the beat grid after a head trim, a long export over a light leak, and
everything in §5 from step 11 down.

### What this session left on the phone: nothing

Cleared before the session ended, all through the app's own screens rather than
by reaching round them - which is itself worth one line, because each dialog
names the thing it is about and says what is recoverable:

- **Ten exports** out of the gallery through Library → delete: the seven made
  tonight for the gate and the two joins, and the three MediaStore ids
  (1001326343/44/45) owed since 5 October. The dialog names the file and says
  plainly "There is no undo and no bin to fetch it back from."
- **Five projects** binned through the card menu: the eight-hour ASTERIA split,
  the photo-and-video one, and three openings of the same 86-second clip. "They
  move to Recently deleted for 30 days… Your original videos are untouched
  either way."
- **One Snip session** binned the same way.
- `accelerometer_rotation` back to 1.

By id, never by a `LIKE` pattern - `_` is a wildcard, and `sq_%` once matched
every `squish_` export the owner had.

### 8. Three gigabytes of the same few videos (7 October)

**Found by reading Settings → Storage on the owner's phone, and it is the
largest thing this session turned up.** The card said *Reversed renders, imports
and downloads · 3.1 GB*. `files/imports` held 23 files and most of the bytes
were duplicates:

    262624564  22:45   \
    262624564  22:56    >  the same 86-second clip, opened three times
    262624564  23:00   /
    262624564  5 Oct       and again on another day
      8612222  × 5         the same 8.6 MB clip, five times
   1921715029  23:38       one eight-hour recording, copied whole

A share whose grant cannot be persisted **has** to be copied, or the draft dies
with the process - `MediaAccess.importCopy` says why, and it is right. What it
did not do was look to see whether it had already copied that file: every name
was a fresh `UUID`, so every open made another copy. Three of those 262 MB
copies were made in half an hour of testing, by opening the same clip three
times.

Fixed: the copy is named from a digest of the file's own name and length
(`ProjectRules.importCopyName`), so the second open finds the first copy. The
length is checked against the source before a found copy is trusted - a kill
mid-copy leaves something shorter, and it is made again rather than opened - and
the copy is now written to a `.part` beside its name and moved onto it only when
it is whole. Where the size is unknown there is nothing to check against, so it
falls back to a fresh name, as before.

**Seen on the phone:** opening a video wrote
`396ed8c4f1bf493166add119c0ddb527.mp4`; opening the *same* video again wrote
nothing at all - 24 files before and 24 after, same name, same timestamp. Before
the fix that was a 25th file.

`files/imports` on the phone is back to **402 MB from 2.9 GB** - the 2.7 GB this
session's own testing had put there is gone, by name.

One knock-on, written down because it is easy to undo by accident: a copy's name
says nothing about what is in the file, and `ProjectRules.saysSomething` refuses
such a stem as a project's name. It knew the UUID shape; it knows the digest
shape too now, or every project opened from a share would have been called
`396ed8c4f1bf…`.

### 9. The padded canvas, the safe-area guide and the beat grid (7 October)

All first sightings, in a one-hour window on the phone.

- **A beat grid on the camera sound, and a head trim.** Find the beat on an
  86-second recording of a stage: **135 beats, 94.3 BPM, "Some pulse", bar
  starts on beat 3, 34 bars**, with the card saying "Found on the camera sound,
  so there is no clip to draw the dots on". Mark every beat drew 135 lines
  across the whole strip. Trimming 3.7 s off the head of the shot left the card
  reading exactly the same - 135 beats, 94.3 BPM, bar on beat 3, 34 bars -
  which is `AudioRules.chosenInWindow` counting the bar over the *file's* list
  rather than the window's. §5 step 7, as far as a screen can answer it; whether
  the dots are still on the music needs ears.
- **A 9:16 frame on landscape footage.** The picture is cut to 9:16 in the
  preview, and the Export sheet offers **720 × 1280** at Original - B5 gate step
  6's number, from the sheet rather than from a file.
- **The safe-area guide is drawn on the crop, not on the canvas.** With Reels
  chosen, the dashed "Reels · the clear part" rectangle sits inside the 9:16
  frame. That is the sweep-six fix seen: it used to be drawn on the canvas, so
  it was only right while nothing was cropped.
- **Frame → Background → Blur: the padded canvas, B12's biggest unseen item.**
  The landscape shot is kept whole in the middle of the 9:16 frame over a
  blurred copy of itself, on screen **and in the exported file** - 360×640,
  2486 frames at exactly 30.000 fps, every frame 33.33 ms, the backdrop filling
  above and below. Read frame by frame at 2 s, 14.7 s, 27.3 s and 40 s.
- **Filters** draw their swatches from a real frame of the shot (Original,
  Vivid, Punch, Soft, Clean), and applying Vivid reached the picture at once -
  the blurred backdrop with it, so the canvas is graded as one picture.

### 10. Stabilize, Track and a clip's own crop (7 October)

**Stabilize**, §5 step 6's first half, never driven before. On a three-second
clip it measured in about five seconds and the card read **"Shake removed · 90
measurements · Measured 91 frames. Zoomed in 0% to hide the edges the correction
exposes."** - 91 frames and 90 motions is one motion per *pair*, which is the
arithmetic right; and 0% zoom is the honest answer for a screen recording with
no shake in it. Dragging **Strength from 50% to 82% re-solved with no
"Measuring…"** and the card still read 90 measurements, which is B13's "this
clip is solved again from its measurement as the slider moves; other clips keep
theirs", seen.

**Track**, the other half. Tap a thing in the frame, Box size 14%, "Track from
the playhead": **"Followed 91 frames, held on for 100% of them."** No crash, no
silence. (Not yet on 60 fps footage in a 30 fps project, which is what step 6
asks for.)

**A clip's own Crop, inside a 9:16 padded canvas.** The 1:1 chip draws a square
window on the picture with the outside dimmed, *in place* - the canvas stays
9:16 and the shot stays where it is, which is B12's review-round fix ("the Mask
tool edits in place again… a 9:16 frame stays 9:16"). Crop's two Flip buttons
carry the swap icons B16 gave them.

### 11. A template, a mask, and the heart and star shaders (7 October)

- **A template applied**, which `CLAUDE.md` listed as unseen. Looks →
  Templates → Social → **Party** put a neon pink "Let's go!" title on the
  picture and changed the grade in one step, with Undo offering to take it off.
  **In the exported file**: the picture fades up from white at 0.0 s (the
  template's arrival), the title is there at 1.0 s and 2.0 s, and it is gone at
  3.0 s where its span ends. Look, title and animation all reach the file.
- **Mirror** flips the footage and leaves a text overlay the right way round -
  the WhatsApp text in the shot reads backwards while "Let's go!" reads
  forwards, which is the layer order doing what it should.
- **A mask**, never driven. Add a mask drops an ellipse with a dashed outline
  and a centre handle; everything outside it is hidden. The sheet offers Cut out
  / Pixelate / Blur, "Add a key here to animate this", and the six shapes.
- **The heart and the star compile and draw on this phone's ES2 driver** -
  listed in `CLAUDE.md` as needing a device, because a shader that does not
  compile fails asynchronously on the player and the surface then plays plain.
  Both are clean distance-field shapes with soft edges and the outline follows
  them exactly.

### 12. The person mask runs on the phone (7 October)

**Cutout → Background → Remove background**, never driven before. MediaPipe's
vision JNI and TensorFlow Lite loaded (`Created TensorFlow Lite XNNPACK delegate
for CPU`, 246 of 246 nodes delegated), the card counted up - "Finding the
person — 34%" with a Stop - and it finished an 86-second clip in about a minute
without a crash.

What it found on *this* footage is nothing, and the app says why: a wide stage
shot with a dozen small figures is not what a selfie segmenter is for, so
everything read as background and the whole picture came back blurred, under the
line **"Works best with one person facing the camera."** Worth writing down as
the honest result rather than as a pass: the path runs, the model loads, the
progress is real, and on this shot the answer is useless and labelled as such.

Two rules of B16's held at the same time, both by not showing something:
on a main-track shot the sheet offers **only** Background and Chroma key - no
"Cut out", because a hole in the base would show black in the preview and the
backdrop in the file - and **no "Float this clip"**, because this is the only
shot and `OverlayRules.floatsOverAShot` says nothing would be under it.

### 13. The Relink card, seen on a real missing file (7 October)

Not staged: one of the owner's own projects from 29 September opens on a file
that is no longer there, and the editor says so properly - **"Can't open
'VID-20260926-WA0104.mp4' · The file moved, was deleted, or the app lost
permission to read it since you picked it"**, with "Relink, below, puts another
file under every clip that played it, with every cut and setting kept", a
Dismiss, and the clip drawn **hatched with a "Missing" badge** on the strip
while the rest of the edit plays. That is B15's design, working on a case nobody
arranged.

**And one thing it showed by accident, worth writing down.** That project's card
changed from "Edit · 29 Sep" to "Edit · 7 Oct, 5:32 AM" simply by being opened
and left. Opening *another* project - one saved by today's build - and leaving
it did **not** re-date it, which is `ProjectAutosave.save`'s `untouched` rule
working: the save time is kept when the edit fingerprint matches what is on
disk.

So the re-dating is not a fault in that rule; it is what happens the **first
time a project written by an older build is opened by a newer one**. The
fingerprint is of the encoded document, and today's codec encodes differently
(three splits, `putFinite`, thirteen `optDouble` defaults), so the key differs,
the draft is rewritten, and a project whose fallback name is "Edit · <saved
date>" is renamed with it.

Benign in normal use - it happens once per project per format change - but it
means **after any update that touches the codec, every project the owner opens
jumps to the top of the grid with today's date**. If that is not wanted, the
fingerprint would have to be of the *edit* rather than of its encoding.

### 14. The Fast lane driven, and the compact muxer settled (7 October)

**Extract audio was dead and is now seen working.** The fault and its fix are in
the commit; what the phone showed afterwards: the whole clip gives 5.6 s / 95 KB,
and the start handle dragged to 0:01.353 for "0:04.179 kept" gives 4.2 s / 73 KB
- the length measured off the written file, and the done screen's waveform
missing the transient the handle cut off. Which also puts **B15's
`TrimRules.draggedTo` in a file for the first time**: a 1.6-second drag moved the
handle and it stayed, where before the fix each event was under half a frame and
rounded back to nothing.

The screen itself, never driven before: a waveform decoded from the file, the
part outside the kept stretch drawn dim in both the waveform and the filmstrip,
±5 s transport, a scrub bar whose knob sits at the handle, and the frame nudges
either side reading the two edges.

**Squeeze, also never driven.** A 0:22.266, 3.5 MB, 720p portrait clip at 480p:
the sheet greyed 1080p, 1440p and 4K and said why - "this is already a 720p file,
and making the frame bigger can't add detail" - estimated 1.8 MB, and the done
screen reported Frame 480 x 852, Length 22.3 s, Rate 30 fps, Size 1.7 MB, "51%
smaller · was 3.5 MB", saved to Movies > Squish. The estimate was out by 6%.

**And the file settles the compact muxer**, which `CLAUDE.md` put first on the
device list because it is on the path of every export and no file had been
written with it. `Mp4Probe` on the pulled file:

    track soun mp4a: 962 samples, 22.338 s, 365,980 bytes, 131 kbps
    track vide avc1: 664 samples, 22.133 s, 30.000 per second, 1,352,092 bytes
      frame durations: 33.33 ms x664 - shortest 33.33, longest 33.33

1,738,850 bytes in all against 1,718,072 of samples: **20,778 bytes of container,
1.2% of the file.** Media3's streamable-moov reservation is 400 KB flat, which on
this file would have been 23%. The frame durations are also perfectly even over
all 664 frames, so nothing was dropped or doubled on the way through.

Still not answered for the muxer: that such a file plays in WhatsApp and in a
browser (it plays in the gallery and in the app's own done screen, which is a
`VideoPreviewSheet` on the MediaStore URI - so B15's "a MediaStore URI plays in
VideoPreviewSheet" holds).

### 15. Tool sessions, and what a one-tap run leaves behind (7 October)

The sessions list itself, driven for the first time. Each row is a thumbnail,
the tool, the clip count, the age, the length, an "Exported" badge and - where
there is a ten-minute snapshot - "Earlier version · N ago", with a play and a
delete. The delete asks first, naming the tool in the sentence ("This sets aside
the **stitch** you had set up - 2 files, and the settings on them", "the
**squeeze** you had set up - 1 file"), says it keeps it for 30 days and that the
original video is untouched, and the row moves to "Recently deleted" below with
a restore button of its own and an Undo on the snackbar. All of that held.

**What it was missing was Squeeze.** See the commit: a run that changed nothing
before exporting saved no session. Fixed and seen.

**Stitch, driven.** Two clips picked at once; the rows number themselves, show
"0:05 · at 0:00" and "0:03 · at 0:05", and grey the up arrow on the first row
and the down arrow on the last. Moving the first row down renumbers both,
recomputes both "at" times to 0:00 and 0:03, re-greys the arrows for the new
positions, and **changes the player's poster frame to the new first clip** - so
everything downstream of the order followed it. The file is 8.566 s, which is
what the sheet promised to the millisecond, and its first frames are the
reordered clip's.

`Mp4Probe` on it: 257 video samples at 30.004 per second, 371 AAC samples, and
9,115 bytes of container on 813,904 - 1.1%, the compact muxer again. The frame
durations are 33.32/33.33 ms except for four at the join (23.56 x2, 34.00,
52.67, 33.54), which is a sub-frame mismatch between two independently
timestamped sources being absorbed over about four frames rather than frames
being lost - the total is right. Worth keeping as the shape of a healthy join:
a regression here would be a much wider spread.

**And one thing read wrong, then read again.** The Library stayed at 35 through
four tool exports, which looked like tool runs not getting a row at all. They do:
`LibraryScreen` calls `HistoryRepository.forgetDeleted` as it opens, which drops
any row whose gallery file is gone, and the four test files had been deleted by
MediaStore id before the Library was ever opened. 35 to 39 and back to 35.

So nothing is missing - and the detour settles something that was not on any
list: **`forgetDeleted` on a real gallery deletion**, four rows with their files
taken out from under them, all four forgotten, every other row left alone. That
is the function's whole risk, since forgetting a row whose file still exists
loses it for good.

### 16. "Fit to a size" on Squeeze, and what it never said (7 October)

Squeeze offers **"Fit to a size · For a strict upload limit"** with 16 / 25 / 50 /
100 MB chips, and until today had none of B14's machinery behind it: a run that
came out over the limit published the file and reported "Squeezed · N% smaller"
with nothing saying the number had been missed, and a limit that could not be met
at all was only discoverable by spending the encode. See the commit for why - the
card was private to `ExportSheet.kt`, so the editor had it and the tool did not.

**Seen on the phone, the half that costs no encode.** A 2:35:13.280, 1.69 GB film,
Fit to a size on, 16 MB:

> **Fit to a size**
> *This video is too long for 16 MB - the smallest it can be made is about
> 501.8 MB. Trim it, or pick a larger size.*

in amber, before anything is rendered. The size card above it, computed by a
different route (the estimate, not `smallestFittedBytes`), independently reads
**858 x 360 · sized to fit · ≈ 501.8 MB · 70% smaller than the original · was
1.69 GB** - the same 501.8 MB, which is a cross-check on the floor worth more
than either number alone.

Tapping 100 MB re-words the line to "too long for 100 MB" and leaves the floor at
501.8 MB, where it belongs - the floor is the video's, not the target's - and the
three-line sentence lays out without pushing the chips or the button anywhere.

**Still unseen: the overshoot card itself.** It wants a fit that is reachable but
tight enough for the encoder to miss by more than two per cent, which is busy
footage near the five-minute boundary, and a real encode to find out. The arithmetic
behind it is executed (`ExportSettingsChecks`, including the scale-through-the-floor
composition that bit once); the card on this screen is not.

**And the fit itself, rendered.** A 1:26.536, **262.6 MB** clip of a lit stage -
busy footage, a video wall moving behind the singers - fitted to 16 MB. The sheet
said "852 x 480 · sized to fit · ≈ 16.0 MB"; the file came out at

    15,990,560 bytes against a 16,000,000 limit - 9,440 bytes under, 0.06%.

852 x 480, 1:26, 30 fps, 94% smaller than the source. So the overshoot card did
not show, and that is the right answer rather than a gap in the test: the run did
not overshoot. `ExportPresets.solvedBitrateForTargetSize` has been executed on the
JVM since B14 and this is the first time its answer has been put through a real
encoder, on the kind of footage the two-per-cent tolerance exists for. It landed
inside a sixteenth of one per cent.

The card is therefore still unseen, and wants a run that genuinely misses. On this
evidence that is not easy to arrange on purpose, which is worth knowing too.

### 17. Twenty captions in one file (7 October)

**The thing `docs/ROADMAP.md` §5 calls "the single most important thing on this
whole list", done.** Sweep eight found that every caption, sticker and shape went
into one Media3 `OverlayEffect`, and `OverlayShaderProgram` refuses more than
fifteen in one instance - so sixteen or more captions failed the render at its
first frame while the preview showed every one. Two minutes of auto-captioned
talking is thirty lines.

Driven with an **imported .srt rather than the recogniser**, deliberately: the
fault is in the overlay pass, not in speech, and twenty cues by hand is a
deterministic way to cross the limit. A 20-cue file pushed to Downloads, Text →
Import .srt, picked from the file browser.

**The import.** Twenty lines, and the sheet lists them with their exact moments -
0:06.100, 0:07.100, 0:08.100, 0:09.100 against the file's `00:00:06,100` and so
on, the millisecond fraction kept. No "Replace or Add beside" prompt, which is
right: there was nothing to replace. Undo went live, the Translate card appeared,
and the Lines card counted them.

**The render.** 480p, Auto rate, Standard:

    SquishExport: done trimOnly=false optimization=0 video=c2.qti.avc.encoder
    mime=video/avc bitrate=493350 asked=500081 frames=664 size=1744659

No `VideoFrameProcessingException`, no refusal, 664 frames at 30 fps over
22.3 s. **This export was impossible before the fix.**

**And they are in the picture.** Twenty frames pulled at even intervals and laid
in a contact sheet (`tools/desktop`):

| at | shows | | at | shows |
|---|---|---|---|---|
| 1.2 s | Line 2 | | 12.9 s | Line 13 |
| 2.3 s | Line 3 | | 14.1 s | *(none)* |
| 3.5 s | Line 4 | | 15.3 s | Line 16 |
| 4.7 s | Line 5 | | 16.4 s | Line 17 |
| 5.9 s | Line 6 | | 17.6 s | Line 18 |
| 7.0 s | *(none)* | | 18.8 s | Line 19 |
| 8.2 s | Line 9 | | 20.0 s | Line 20 |
| 9.4 s | Line 10 | | 21.1 s | *(none)* |
| 10.6 s | Line 11 | | 22.2 s | *(none)* |
| 11.7 s | Line 12 | | 0.0 s | *(none)* |

**Lines 16 to 20 are all there** - the five past the limit, and the ones whose
presence used to mean no file at all.

Every blank is the sampler landing between cues, not a dropped line: the frame at
7.043 s is 57 ms before cue 8 opens at 7.100, the one at 14.085 s is 15 ms before
cue 15, the two at the end are past cue 20's close at 20.000, and 0.000 is before
cue 1 opens at 0.100. Under two frames in every case, which is also a check on the
timing: the captions start on the millisecond the file asked for.

Still unseen from this area: **auto-captions from the recogniser** (word timings,
the Words arrival landing each word on its own), and an **.srt in a legacy
single-byte encoding**, which `PickedText.decode` exists for. This one was UTF-8.

### 18. A blended still changing under your hand (7 October)

ROADMAP §5 item 14, from sweep six: **changing a blended still's mode or opacity
did not reach the preview** until the shot under it changed, because the held
still was keyed on clip *ids* and an edit makes a new clip with the same id.
Fixed then, unseen until now.

A photo put on an overlay row over a 22 s shot, Opacity sheet open:

- **Blend → Multiply**: the picture changed on the tap. The poster, which had
  been an opaque rectangle in the top-right corner, became a full-frame multiply
  - its whites letting the video through, its dark type staying dark - and a line
  appeared under the chips saying why: *"A blended picture covers the whole frame
  - that is what a light leak or a dust overlay is for. Place it, or turn it,
  with Blend off."* The box's outline stayed where the placement put it, which is
  right: the placement did not change, only what is drawn.
- **Opacity 100% → 49%**, dragged: the multiplied picture faded under the finger,
  the video beneath coming up. Nothing else was touched and the shot never
  changed.

Also seen on the way, unprompted: the overlay's own toolbar row (Back, Split,
Opacity, Layer, Animation, Delete, Placement) with **Split correctly dim** at the
playhead sitting on the overlay's own first frame; and the **Layer** sheet's
empty state, which is better than most - "Row 1 of 1 · Higher rows are drawn over
lower ones · No other overlay is on screen at the same time as this one, so there
is nothing to put it in front of or behind."

### 19. Item 17, and a cause I had got wrong (7 October)

**ROADMAP §5 item 17, done**, and it is the device's answer to a change made
today: on a slideshow of two photos, Sound → **"Mic & camera" offers the Record
card alone** - no Camera sound row, no level slider. That is the case this
morning's `ToolRules.hasCameraAudio` had to keep working while making the
clip-less quick-tool state answer from the lead file. Clips present, none of them
able to carry camera sound, so the answer is no. The executed check holds it over
one to eight clips; the phone agrees.

**And a correction.** §13 above said the owner's 29 September project re-dated
itself because today's codec encodes differently, so the fingerprint moved and
any old project would be re-saved on its first open by a new build. That is
wrong, and the test was free: opening "Edit · 5 Oct, 7:11 AM" - also written by
an older build - and leaving it left it reading **"Edit · 5 Oct, 7:11 AM · 1 d
ago", in the same place in the grid**. Not re-dated, not re-saved, `untouched`
held.

So the trigger is not the codec. It is what is special about the other project:
**its media is missing**, and opening it runs the relink path, where `applyDraft`
re-probes the edit's shape from the first main shot that reads (B15). That
changes the state, the fingerprint moves with it, and the save follows.

The fix committed this morning is unaffected and still worth having - a re-save,
whatever provokes it, may not invent a creation date - but the reason given for
the re-save was a guess dressed as a finding, and the thing that would have
caught it was one tap.

### 20. The playhead moves, and nothing is wasted at the start (7 October, night)

The third report of one thing, and the first two "fixes" were the cause of the
second and third. See the commit for why; this is what the phone showed after it.

- **The clip starts flush at the left edge**, with the ruler's 0:00 under it.
  It used to begin a quarter of a screen in, every time: "the new video added is
  starting after wasting space at start".
- **A drag to the right takes the playhead to the right.** 450 px of drag:
  0:00.000 → **0:13.751**, with the playhead visibly walking across the strip
  from the left edge. Dragging back left: **0:02.060**, and the strip back
  against the left edge with no rubber-band and no overshoot. Dragging further
  left stops dead at 0:00.000.
- **Both halves of the window are real.** At a fit zoom the whole edit is on
  screen and the playhead walks the whole way. Pinched in to about a second and
  a half on screen, the playhead holds at the quarter and the film scrolls under
  it - which is what keeps the rest of a long edit in front of you.
- **Playback agrees**: playing from 0:00 the playhead holds at the quarter, the
  film scrolls, and the preview advances (0:10.499 at four seconds in).
- A pinch at 0:00 leaves the strip against the left edge rather than jumping.

What this cost elsewhere: `slideFor` had been written assuming the playhead is
always at `linePx`, and with the clamp it is not, so the drawn strip would have
been laid a quarter-screen from where a finger finds it. It is phrased on the
window's own scroll now. Executed in WindowChecks over every zoom, viewport and
moment.

### 21. The beat grid at an octave (7 October, night)

ROADMAP §5 item 13, from sweep five: **"x2 on a song's beat grid doubled the dots
and left the tempo and the bar phase where they were."** Fixed then, unseen.

Chill House (a Squish original, declared 120 BPM) on an 11.3 s stretch. **"Find
the beat" read 120.0 BPM exactly** - "23 beats on Chill House · they move with
it", "Strong pulse", "Bar starts on beat 1 · 6 bars". 11.34 s at two beats a
second is 22.7, and six bars of four is 24, so both numbers are right.

**x2 moves all three together:**

| | beats | BPM | bars | phase |
|---|---|---|---|---|
| found | 23 | 120.0 | 6 | beat 1 |
| x2 | **46** | **240.0** | **12** | beat 1 |
| back with ÷2 | 23 | 120.0 | 6 | beat 1 |

So it is a true octave move and a reversible one - ÷2 lands back on the found
grid rather than accumulating. The dots on the clip double and halve with it.

**"Every bar"** lights and thins the dots on the strip while the card still reads
120.0 BPM and 6 bars, which is right: the chip chooses which dots are used, not
what the tempo is.

The explanatory line under the chips is worth keeping as written - "A slow track
with busy hi-hats has two defensible tempos, and two people tapping along will
disagree. If it counted at the wrong level, move it an octave." That is what
÷2 and x2 are for, said without jargon.

**The scrub scale, measured rather than eyeballed.** At the zoom a video opens
at, a 450 px drag took 0:00.000 to 0:13.751 where the ruler reads 25 s across
795 px - 31.8 px/s, so 14.15 s expected, 13.751 measured, inside the snap
distance. Pinched five times deeper, 300 px moved 60 ms and **the playhead landed
exactly on a beat dot at 0:13.424**, the song's beats running 10.924 + 0.5n from
a 120 BPM grid. Both are right, and the second doubles as a check that the beats
are in `scrubTargets` and that the snap lands on the dot rather than near it.

(I doubted this for a while on a bad estimate of an effect clip's length, and the
beat dot settled it. Worth writing down that the landmark to measure a strip
against is the beat grid, not a clip's drawn width.)

**One thing that is not a regression but is worth knowing:** at a very deep zoom
the ruler draws no labels at all, because it lays one a second and less than a
second is on screen. The timecode above the strip still reads, so nothing is
lost, but the strip itself has no time reference once you pinch past about a
second a screen.

**And the fit, after the clamp.** Opening the same 22.266 s clip fresh: the
filmstrip now runs from the strip's left edge to its right edge, with the ruler
reading 0:00 under the first frame and 0:20 near the last. Before the clamp the
fit left a quarter of the strip for what had already played, so the same clip was
drawn into three quarters of the width and the ruler ran past 0:25. The edit is a
third bigger again on screen, and every pixel of the strip is edit.

**A tap on the ruler brings that moment to the playhead** - tapped at about 0:23
on a 0:22.266 edit and the playhead went to 0:22.266, clamped at the end, with
the strip re-laid around it. **The double tap that fits could not be tested over
adb**: every `input tap` and `input motionevent` is its own process, so two of
them land further apart than Compose's double-tap window however they are
batched, and the single tap fires twice instead. The handler is wired
(`Ruler`'s `detectTapGestures(onDoubleTap = ...)` to `fitZoom`); whether a
finger triggers it is unseen, and this is the honest reason why.

### 22. Nine switches that said which way they were and never what they were (7 October, night)

ROADMAP §5 item 22 asked for TalkBack on the Settings screen. Driven instead by
**dumping the accessibility tree**, which is better than listening: it is exact,
it is scriptable, and `uiautomator` has an opinion of its own.

Sweep seven gave `SquishToggleSwitch` a `toggleable` so its node carries a state
- before that every switch in the app announced itself as "switch" and never as
on or off. The tree says that half works: both switches on Settings came back
`checkable="true"` with the right `checked`.

Both were also **`NAF="true"`** - uiautomator's flag for a node that is clickable
with no text and no content description. The words beside a switch are a sibling
`Text`, and sibling text is not merged into a control's node, so a screen reader
landing on one said *"on, switch, double tap to toggle"* and never which setting
it had hold of. Nine switches, none of them named.

`SquishToggleSwitch` takes a `label` now and sets it as the node's content
description; all nine call sites pass the words that are already beside them
("Ticks when snapping", "Camera sound", "Blend frames", "Pitch follows speed",
"Fit to a size", "Snap to markers and beats", "Clip sound" / "Overlay sound", and
the mask inversion row, whose words change with the mode so the name does too).

After, on the phone: **zero NAF nodes on the screen**, and

    content-desc="Ticks when snapping"            checkable=true checked=true
    content-desc="Keep the screen on while editing" checkable=true checked=false

`tools/jvm/ControlChecks.kt` holds it, by balancing the parentheses of each call
rather than by regex - every one of these carries a lambda with calls inside it,
and a pattern that stops at the first `)` reads the arguments as empty and passes
everything. Negative-tested by taking one label off.

**The same dump also clears the other half of item 22 and part of sweep ten's.**
Every ratio, duration, transition and voice chip came back `checkable="true"` with
`checked="true"` on exactly the chosen one - so "six picker grids marking a choice
with a border alone" is fixed where it can be seen, not just where it was written.

### 23. Everything on the editor screen can now be named (7-8 October, night)

Carrying on from §22 with the same method - dump the accessibility tree, read
what `uiautomator` flags - over the editor rather than Settings.

**What was already right**, and is worth recording because it is easy to break:
Undo and Redo name the step they would take (`"Nothing to undo"`, `"Redo: Look"`)
and go `enabled="false"` when there is none; the transport reads "Back one
frame", "Play", "Forward one frame", "Full screen"; the strip's own buttons read
"Add to the video track" and "Split at the playhead", the latter disabled with
the playhead on a clip's edge; and every chip on Looks, Adjust and Settings is
`checkable` with `checked` on exactly the chosen one.

**Three things were not.**

1. **Every slider in the app** came back a bare `SeekBar` with no name, for the
   same reason the switches had none: the label and the readout are sibling
   `Text`s. Thirteen of them in a row on the Adjust tab. `SquishSlider` carries
   both now - and the first attempt at the fix is the lesson, because it went
   into `LabeledSlider` and the Adjust tab has a wrapper of its own, so half the
   app stayed unnamed and the tree said so.
2. **`OptionToggle`** - "Facing the other way", on shapes and stickers - showed
   its state in a tint and was a plain `clickable`, so its node carried no state
   at all.
3. **The picture itself**, the biggest target in the editor, was `clickable` with
   no description and no action label, while the two other `clickable`s in the
   same file already had one.

After: **zero `NAF` nodes on the editor screen**, the Adjust slider reads
`content-desc="Brightness"`, and the preview reads `content-desc="The picture"`
with an action label that follows the transport ("Play" / "Pause").

`tools/jvm/ControlChecks.kt` holds the switches and the sliders over their **call
sites** rather than over the component, so a third wrapper cannot skip it, and
balances each call's parentheses rather than matching a regex - every one of
these carries a lambda with calls inside it, and a pattern that stops at the
first `)` reads the arguments as empty and passes everything.

**The same sweep over the other sheets.** Dumping the tree on each screen in turn
and counting what `uiautomator` flags:

| screen | before | after |
|---|---|---|
| editor | 2 | **0** |
| Settings | 2 | **0** |
| Stickers | 1 | **0** |
| Text | 0 | 0 |
| dashboard | 0 | 0 |
| Sound | 1 | 1 |

What the sweep found beyond the switches, the sliders and the picture:

- **Eight shape tiles** - rectangle, ellipse, triangle, diamond, star, line,
  arrow, double arrow - carried an `onClickLabel` and no name. An action label is
  announced as part of the gesture ("double tap to Rectangle") and leaves the
  tile itself nameless, which for eight tiles told apart only by a drawn glyph is
  eight identical blank buttons. They read their own names now.
- **Every text field in the app**: five `BasicTextField`s and three
  `OutlinedTextField`s, all of them a search box or a number entry whose words
  are a *placeholder*. A placeholder is not a label - it is drawn only while the
  field is empty, so the moment anything is typed the field has no name at all,
  and a placeholder drawn as a sibling `Text` is not the field's own even while
  it shows. The check found two of these that the tree had not: the caption
  editor's own field, which is never empty, and the rename dialog's.

**Still open:** one node on the Sound sheet, an `android.view.View` at the very
bottom of the panel and partly below the fold, which I could not identify without
scrolling it into view. Worth a minute from whoever is next.

### 24. The Frame tool, and the safe-area guide on a crop (8 October)

9:16 on a portrait edit draws its crop window as a violet rectangle over the
picture with thirds guides inside it, and a line appears under the chips naming
where that shape is posted - "TikTok, Reels, Shorts, Stories".

**The safe-area guide is drawn inside the crop, not on the canvas.** Turning on
"TikTok" under "Show where the app's buttons will be" draws a green dashed
rectangle labelled *"TikTok · the clear part"* **within** the 9:16 window, with
the hint "A guide only - it is never in the file." That is sweep six's fault
fixed and seen: it used to be drawn on the canvas, so it was right only while
nothing was cropped.

Also seen on the way: **the Looks sheet's filters reach the preview and the
strip's own thumbnails** - Vivid redrew both - and **Undo clears a filter**
cleanly, leaving Original selected, Redo live and Undo dim.

**And something the testing itself exposed: every "Open with" makes a new
project.** Opening the same video nine times over an evening - which is what
driving the editor from `adb` does - left nine projects on the grid, identical
but for their age. That is the designed behaviour since B15, where a project
became its own id so that two cuts of one clip could both exist, and the
`OpenEditors` rule that used to reopen the editor already on that video went with
it. It is right for two deliberate cuts; it is clutter for a person who opens the
same clip from the gallery twice and does nothing to it either time.

Worth a decision rather than a fix: an "Open with" that finds an **untouched**
project on that same file could reopen it rather than make another, which is the
old `untouched` rule in a new place and would leave the two-cuts case alone.

The multi-select delete handled the clean-up well - "Delete 7 projects? This sets
aside every project selected, with every cut, look and caption on each ... Your
original videos are untouched either way", then "Deleted 7 projects" with an
Undo, which is B15's rule seen.

**Fixed, and seen.** `SquishNavHost` now reuses a project that is still *just a
look* on the same file - `ProjectAutosave.openedJustToLookOn` - rather than
staging another. The flag it reads goes false the moment the edit differs from
the one the project was opened with (`writeMeta`), so an opened-and-edited
project is never returned and the two-cuts case is untouched.

On the phone: the same video opened three times in a row left the grid at **20
projects, the number it started at**. Before the fix the same three opens left
three more cards.

It also settled a question the clean-up had left open. One "Thankyou 400k" card
was ambiguous - mine or the owner's? - so it was left alone. The three opens
*reused* it, which is proof it was opened from outside and never edited: mine.
Deleted on that evidence rather than on a guess.

### 25. A pick delivered to a destroyed editor (8 October)

ROADMAP §5 item 29, and sweep ten's worst finding: **a pick delivered before the
draft was read blanked the whole edit** - killed behind the photo picker, the
result is dispatched while the launcher's effect commits, so the add landed on an
empty timeline, vanished when the draft was applied, and left an undo step whose
"before" was that empty edit, one tap from wiping everything and one autosave
tick from writing it to disk.

**Driven with "Don't keep activities"** rather than `am kill`, which will not take
the app while it is hosting the picker's result receiver - the setting destroys
the activity the moment it stops being visible, which is exactly the condition.
Restored to 0 afterwards.

A 2-clip edit (one shot split at 0:06.365), the video track's **+ → Video or
photo**, the activity destroyed behind the picker, then a photo picked:

- the editor came back with **3 clips · 0:25.266** - both original shots *and*
  the picked photo;
- the photo landed **at the playhead**, between the two shots, selected, with its
  own toolbar (Split correctly dim on a still at its own edge);
- nothing was blanked.

And the half that matters most: **one tap on Undo gave back exactly 2 clips ·
0:22.266**, the split edit with its cut, with Redo live. Not an empty timeline.

The menu behind that + is worth recording too: "Video or photo · Blank · Free
stock video".

### 26. A song past the last shot, and the playhead coming back (8 October)

ROADMAP §5 item 12's second half, from sweep five.

A 22.266 s edit with Lo-fi Sunset added at 0:00 - which lands **trimmed to the
picture**, 0:22, as `EditRules.soundLanding` says it should. Dragging the song's
tail out takes the **edit** to 0:25.257: a song past the last shot makes the file
that long, which is what `BUILD_NOTES` says the compositor does.

**Scrubbing into that stretch shows "End of picture"** on black, with the clock
running on to 0:25.257 and stopping there. That is `PreviewRules.baseTime`
crossing the stretch after the last shot and stopping at the true end - listed as
unseen, now seen, and it says so in words rather than holding a frozen frame.

**Deleting the song brings both back.** The total returns to 0:22.266 and **the
playhead, which was at 0:25.257, comes back to 0:22.266** rather than being left
out past the end of an edit that no longer goes that far. The preview shows the
last frame again.

One thing to be careful of when driving this from `adb`: a drag that starts within
a few pixels of a clip's edge catches the **trim handle**, not the strip, and
trims instead of scrubbing. That is the handle doing its job - it is a target -
but it made one earlier reading of the strip's drag look wrong until the same
gesture started further in and scrubbed exactly as it should.

### 27. The preview going black on a photo dragged longer (8 October)

Dragging a main-track photo's tail out and scrubbing into it left the **picture
black** while the photo sat plainly on the strip with its filmstrip drawn. Not
just past the first rendering's end - black from three seconds in.

**The cause, from logcat**, and it is a Media3 1.11.1 fault reached through our
own code:

    W SquishPreview: player error on base-a: ERROR_CODE_UNSPECIFIED
    ExoPlaybackException: Unexpected runtime error
    Caused by: java.util.NoSuchElementException
      at java.util.AbstractQueue.remove(AbstractQueue.java:117)
      at androidx.media3.effect.ExternalTextureManager.removeAllSurfaceTextureFrames
      at ExternalTextureManager.lambda$releaseAllRegisteredFrames$6

`removeAllSurfaceTextureFrames` calls `AbstractQueue.remove()` on an empty queue,
which throws rather than returning null. It runs while registered frames are
released - which is what swapping the photo's longer rendering into a prepared
player does.

**What made it permanent was ours.** `PreviewEngine`'s error listener drops the
effects chain for two *typed* codes, and this arrives as a bare
`ERROR_CODE_UNSPECIFIED`. So the chain was never dropped, every reload built the
same broken frame processor, and it failed again: **thirteen errors on the one
surface, not one of them dropping the chain**, with the picture black throughout.

The error code carries nothing, so the **stack** is read instead: a throw from
`androidx.media3.effect` is the chain's fault whatever the code says, and the
chain comes off. A picture without its look beats no picture.

**What the re-run shows, and what it does not.** With the fix in: the photo
dragged out to make the edit **1:42.607**, the playhead at **1:21.424** deep
inside it, the picture showing throughout, and the stills folder filling with
renders swapped in live (a 34 MB one landed, another `.part` in flight) - so the
exact condition was repeated several times. **Zero player errors.**

That is the honest limit of it: the fix is *reactive*, so zero errors means the
crash did not recur, not that the fix caught it. What can be said is that the
crash happened and left the surface dead for good, that the code would now take
the chain off and let the picture back, and that repeated live swaps no longer
produce it. Whoever sees `player error` with a `media3.effect` frame again should
find `dropping effects` beside it and a picture on the screen.

### 28. The phone on its side (8 October)

B6 listed the landscape layout as built and unseen. Both halves hold.

**Rotated to landscape while paused**: two panes - the header, the picture and
the transport on the left, the strip with its four track heads, its ruler, Split
and the level-0 toolbar on the right. The picture **carried across**: the frame
that was on screen is still on screen, not black and not stalled.

**Rotated back to portrait while playing**: still playing, at 0:09.593, with the
picture live and advanced past where it was - so the player came across the
rotation without a reload. The strip re-laid with the playhead at its place and
the film scrolled under it.

(`accelerometer_rotation` and `user_rotation` restored afterwards, as was
`always_finish_activities` from §25.)

**Full screen, from the same session.** The picture fills the screen with its own
scrub bar, transport and timecode. Scrubbing there to 0:14.044 and closing it
lands back in the editor **on that frame**, with the strip scrolled to it and
nothing playing - so the frame is kept and no scrub is left running. (The exact
case B6 names, closing *mid-drag*, cannot be driven over adb: the close has to
happen while a finger is down.)

### 29. A title from the grid, to the file (8 October)

The Titles grid renders each preset in its own style (BIG NEWS, Say it here,
Your name, Neon, Big number, Chapter one, Day 1 in Goa, GG). Tapping one:

- it lands **at the playhead**, 0:14.044 → 0:17.044, a three-second title;
- the **keyboard comes up with the sample words selected**, so typing replaces
  them - which it does, and the picture updates letter by letter;
- the Edit sheet's tabs (Keyboard · Style · Bubble · Animation) sit **over** the
  keyboard with the picture above them, and the strip folds away while typing -
  B6's layout, seen;
- the card reads "drag it on the picture, its ends on the strip".

**And it exports.** Sixteen frames pulled from the rendered file at even
intervals: "HELLO" is on the frames at **14.9 s and 16.4 s** and on no other -
not at 13.4 s, not at 17.8 s. Against a placement of 14.044 → 17.044 that is the
span to the frame, in the title's own style, in the lower third where the preview
put it.

### 30. Snip trimmed to a second and a half (8 October)

ROADMAP §5 item 15, from the third sweep: *"in Snip, trim to three seconds and
the **start** bar must still drag."*

Driven tighter than that. A 0:05.532 clip, the **end** handle dragged from the
right edge almost to the left: **0:01.485 kept**, the handle following the whole
way, the kept band redrawn narrow at the left with the rest dimmed, and the
preview jumping to the frame at the handle that moved.

Then the **start** handle, with the two now a finger's width apart: it moved
**0:00.000 → 0:00.363**, the span reading 0:01.122, the end unchanged at
0:01.485, and the preview jumping to the new start's frame.

So both handles answer at a trim tighter than the one the item asks about. That
is B15's review working on two counts at once - the 48 dp targets reaching
*inward* so neither is swallowed when they close up, and `TrimRules.draggedTo`
measuring from where the handle stood plus the whole travel, so a slow drag
moves at all.

### 31. The whole journey, from New project to a file (8 October)

The canonical thing a person does, driven start to finish with fresh picks.

**New project → three photos.** They land end to end, three clips of 0:03 each
(Settings' photo length), 0:09.000 in all, drawn from the strip's left edge, and
the project is called "Edit · 8 Oct" because the filenames say nothing. That is
B15's `loadFresh` doing what it says.

**The join markers are an offer, not a transition.** Settings has Transition set
to Cut, and each join carries a ⧓ marker - which turns out to be the place to
*add* one: tapping it selects the incoming shot and opens the Transition sheet
with nothing chosen. Worth writing down because it reads at first glance like a
transition that Settings said not to apply.

**Dissolve.** The sheet offers Basic / Camera / Glitch / Light with tiles
rendered from the edit's own frames. Picking Dissolve lights the tile, names it
("Dissolve · how shot 2 arrives"), shows a **Length** of 0.5 s - and the header
goes **0:09.000 → 0:08.500** on the spot, because the shots overlap by that much.
The consequence is stated and shown rather than discovered later.

**And it exports.**

    SquishExport: done ... frames=255 size=925433

255 frames at 30 fps is **8.500 s exactly**, the number the header gave. Frames
pulled from the file: clean photo 1 at 2.2 s and 2.6 s, **both necklaces
superimposed at 3.0 s**, clean photo 2 from 3.3 s on. The dissolve is there, in
the right place, through the composited path - which is B5's gate in its
photo-to-photo form.

### 32. Settings, and the storage card (8 October)

**Settings matched what a new project actually did**, which is the point of it:
Frame Original, Photos run for 3 s, Transition Cut, and the project made from
three photos came out three 3 s clips with no transition applied.

**The Export card states what it remembered**: "New projects open on 480p, the
footage's rate, standard quality - as the last export was set", with "Start new
projects at Original again" beside it. B14's remembered defaults, said in
words rather than left to be discovered.

**The storage card.** Seven kinds, each with its size, a plain sentence and a
Clear. The sizes add up: 354 + 391 + 694 + 5 + 14 + 0 + 4 = 1462 MB against the
header's "Using 1.5 GB". **Preview cache at 0 KB has its Clear greyed** - a
control that cannot do anything saying so, which is the convention this codebase
writes down and does not always follow.

Clearing **Thumbnails** took it 4 MB → 0 KB, greyed its own Clear on the spot,
and the dashboard came back with **every cover drawn** - regenerated from the
sources, as its blurb promises. Nothing blank.

**Worth telling the owner:** *Exports kept inside Squish* is **354 MB**. Those
are renders whose copy to the gallery did not land, which the library plays;
clearing takes 354 MB back and removes those rows from the library. And
*Reversed renders, imports and downloads* is **694 MB** - mostly `files/imports`,
which is copies of shared videos. Both are one tap each and neither touches a
project's own files.

### 33. An emptied edit said it was twenty-two seconds long (8 October)

Found by a stray tap, reproduced deliberately. Delete every clip and the editor
showed **two numbers for one thing, at the same time**:

- header: **"0 clips · 0:22.266"**
- transport under it: **0:00.000 / 0:00.000**

`EditorUiState.trimmedDurationMs` falls back to the source file's own window
whenever nothing is laid on the tracks. That fallback is for a project *before
its clips are made* - but an edit whose clips have all been **deleted** looks
exactly the same to it, and reported the length of a file it was no longer
playing.

**And it was worse than a wrong label.** `SquishError.preflight` refuses an
export on `trimmedDurationMs <= 0`. At 22.266 the emptied edit sailed past that
check - so Render would have gone ahead and written twenty-two seconds of
nothing.

`TimelineLanes.timelineLength(laidMs, sourceWindowMs, stillLoading)` keeps the
fallback where it belongs: a project staged and not yet read has no clips either,
and the editor is on its spinner then, so that is what the flag asks. Loaded and
empty is empty.

Seen after: the header reads **"0 clips · 0:00.000"** beside a transport reading
the same, and Render answers with a card that was written long ago and could
never fire until now -

> **Nothing on the timeline**
> Every clip has been trimmed to zero or deleted, so there are no frames to write.
> *Add a clip, or widen a trim handle.*

Executed in `tools/jvm/LaneChecks.kt` over the four cases and the negative
window; negative-tested by taking the `stillLoading` arm out, which fails with
"an emptied edit still reports the length of the file it was opened on".

### 34. The Curves square, and a swipe that has to scroll past it (8 October)

The third sweep's list has *"swipe the Curves square and the sheet behind it must
scroll"*. **It does.** A vertical swipe starting inside the square, away from a
point, scrolls the sheet - verified after a false start where the swipe began a
few pixels above the square, in the tab row, and so moved nothing. (That false
start is worth recording: it looked exactly like the bug.)

**Not a bug, though it reads as one at first.** The square is
`fillMaxWidth().aspectRatio(1f)`, so about 1000 px on a side, inside a sheet
whose scroll area measures 643 px (from the accessibility tree). So the whole
curve is never on screen at once: the identity line appears to leave the top of
its box part-way across, which looks like a curve drawn at the wrong aspect until
you realise you are seeing a window onto a square taller than the window.

**The design point stands even though the code is right:** a tone curve is judged
by its *shape*, and on this phone you cannot see the shadows end and the
highlights end together - reaching the highlights means scrolling the shadows off.
Worth a decision: cap the square's height to what the sheet can show, and let it
be wider than tall, or keep it square and accept the scroll.

ROADMAP §5 item 21 - a point pulled below the one before it, and nothing between
two points brighter than the higher of them - is **not** settled by this. It needs
the curve's shape read off the screen, which wants the square fully visible first.

**And the complement of the "Open with" fix, seen by accident.** Opening a video
from the gallery, looking through several sheets, changing nothing and leaving
left **no project on the grid at all** - the count stayed at the owner's own 19.
`persist`'s `untouched` rule retracts a project whose draft never differed from
its first pick, so a look costs nothing. Together with §(the Open-with fix) that
is the whole of it: a look leaves nothing, a second look reuses the first, and
only an edit makes a project.

### 35. The Speed sheet at a flat rate (8 October)

B16's fix: *"Normal lights at 1x only - a lit chip must be one a tap leaves
alone, and it lit for any flat rate, so a 2x shot read as 'Normal' and a tap on
the lit chip dropped it to 1x, rippling the track."* Verified.

The sheet opens with the rate curve on a **logarithmic** axis (100x / 10x / 1x /
0.1x) - which is what makes the range below 1x usable at all - and the card reads
"Shot 1 · 0:22.266 on the timeline".

Dragging Rate to **1.6x**:
- the clip on the strip shrinks and carries a **"1.6x" badge**;
- its length goes 0:22 to **0:13**, and the edit's total 0:22.266 to 0:13.660;
- the card becomes **"0:22.266 of footage · 0:13.660 on the timeline"** - both
  numbers, which is the pair a retimed clip is confusing without.

And the chips:
- the six preset rates (0.25x … 10x): **none lit**, since 1.6x is not one of them;
- the nine curves (Normal, Montage, Hero, Bullet, Jump cut, Flash in, Flash out,
  Slow in, Slow out): **none lit** - the fix exactly;
- the line beneath: **"One rate, 1.6x. Tap a curve to lay it across the whole
  shot, or Normal for 1x"**, which also says what Normal *would* do, so tapping
  it is a choice rather than a surprise.

### 36. Cutout on a main-track shot, and "Float this clip" (8 October)

B16's claim, verbatim: *"Cutout on a main-track shot offers neither Key green nor
Cut out - a hole in the base shows black in the preview even over a padded
canvas ... but 'Float this clip' (`FloatOffer`), which is
`switchToOverlay(keepPlacement = true)` ... check it lands the shot on an overlay
row selected, full frame where it was, and the Cutout sheet then shows the key
buttons."* **All of it holds, seen.**

On a one-clip edit, Cutout → Chroma key reads:

> Chroma key · Cut a green or blue screen out of this shot
> Nothing is under the video track, so the keyed colour shows black here. Put a
> shot after this one and float this clip over it.

- no Key green, no Key blue, no "Pick the screen from the picture";
- **no Float button either** - `OverlayRules.floatsOverAShot` is false on the only
  shot, and the sentence changes to say what to do about it instead. That is the
  half of the rule that is easiest to get wrong and it is right;
- the panel is headed **"Chroma key"**, not "Green screen" (B16's rename).

Duplicate the shot so a second follows it, and the same panel grows the button
and the other sentence (*"...Float this clip over the next shot for that shot to
show through: it keeps its place and its moves."*). Tapping it:

- the edit goes 2 clips · 0:44.532 → 2 clips · **0:22.266** - the floated shot now
  lies *over* the one that slid under it, which is the whole point;
- the strip hint reads **"Overlay"**, the box is drawn **full frame** (not dropped
  into a corner, which is what the toolbar's To overlay does), with its four
  buttons - Delete, Duplicate, Edit, resize - at the picture's corners;
- the sheet stays open and **now shows Key green / Key blue / Pick the screen from
  the picture**, and its subtitle becomes "...out of this overlay";
- the undo step is named **"Undo: Float"**.

### 37. The playhead, and where a picked photo lands (8 October)

The two things the owner reported by hand. Both are **fixed in the build on the
phone**, seen:

- **The playhead moves, and it moves with the finger.** Drag the strip right and
  time runs forward (0:00.566 → 0:10.754 on one drag); drag left and it runs back,
  stopping at 0:00. The line walks right across the screen as it goes - at this
  zoom the whole edit fits, so there is nothing to scroll and the playhead is what
  moves. It sticks: the readout is the same three dumps later.
- **No wasted space at the start.** At 0:00 the first clip's left edge is at the
  strip's own left edge (x≈105 of 1080, which is the track-head column) and the
  ruler's 0:00 tick is under the playhead. `TimelineWindow.linedOn`'s clamp is
  doing its job.
- **The frame buttons step exactly one frame**, forward and back, on 30 fps
  footage: 0:00.500 → .533 → .566 → .600 → .633 → .666, then back .633 → .600 →
  .566. Symmetric, no drift. (B6's claim, on an untrimmed, unretimed shot; the
  trimmed and retimed cases are still unseen.)

**Where a picked photo lands, which looked wrong and is not.** With the playhead
at 0:10.754 inside a 0:22 shot, "Add to the video track → Video or photo" put the
photo at **0:00**, not under the playhead. That is
`ClipEdits.insertSourcesAtPlayhead`'s documented rule - *"on the nearer cut of the
shot under it, never inside one"* - and 10.754 is nearer the shot's head than its
tail. The strip then shows the video filling the viewport with its head off
screen, which reads as a clip drawn at the wrong width until you scroll back to
0:00 and find the photo sitting there with its handles.

**A slow drag that starts on a clip scrubs; it does not lift it.** An 800 ms drag
across the selected shot moved the playhead 0:14.989 → 0:07.014 and left **no undo
step**. The lift wants a press that stays still.

### 38. The export gate: a photo, a video and a Dissolve (8 October)

B5's device gate, items (1) and (2) - *"the export that failed on 1.5.1 with 'The
preceding MediaItem does not contain any track' must now complete, with the
dissolve visible and sound throughout"*, and *"the same with the photo first and
the video second"*. **Rendered, pulled off the phone and read frame by frame.**

The edit: a picked photo at 0:00 (3 s, Settings' photo length), a 0:22.266 video
after it, **Dissolve 0.5 s** on the join - so the photo is first and the video
second, which is the harder of the two orders. 480p, Auto frame rate, Standard
quality, H.264.

- `SquishExport: done trimOnly=false optimization=0 video=c2.qti.avc.encoder
  mime=video/avc bitrate=496634 asked=500081 frames=743 colour=ColorInfo(BT709,
  Limited range, SDR SMPTE 170M...) size=1959778`. **It completes.**
- The done screen states **480 × 852 · 24.8 s · 30 fps · 2.0 MB**, and "Saved to
  your gallery · Movies > Squish · squish_1791411626295.mp4". The MediaStore row
  is byte-for-byte the same size, and the pull is byte-exact.
- `Mp4Probe`: **743 video samples, 24.766 s, 30.000 per second**, frame durations
  33.33 ms ×742 and 33.00 ms ×1 - no drops, no stutter. Sound runs the whole way:
  1069 AAC samples, 24.822 s, 44.1 kHz (the source's own rate), 131 kbps.
- **The compact muxer, measured for the first time.** Video 1,530,066 B + audio
  406,686 B = 1,936,752 of a 1,959,778 B file: **1.2% container overhead**. The
  400 KB `moov` reservation `media/CompactMuxer.kt` turns off would have been
  **20%** of this file. First item on the unverified list, now off it - and the
  file plays in the gallery and in the done screen's own player.
- **The dissolve is in the file.** Frames 2.3 s → 3.3 s (`tools/desktop/frames.ps1`
  + `contact_sheet.ps1`): pure photo to 2.4 s, the video fading up from 2.5 s,
  the photo nearly gone by 2.9 s, pure video at 3.0 s. A clean half-second
  cross-fade, photo under video, the same way round as the preview drew it.
- The edit's length dropped 0:25.266 → 0:24.766 when the Dissolve went on, which is
  the overlap the sheet says it takes.

Also seen on the way: sizes above 1080p are **greyed** on the Export sheet with
"Sizes above 1080p are beyond this phone's encoder" (B14's `EncoderCeiling`), and
the transition sheet's tiles animate the *outgoing* shot's own picture.

### 39. Three more of B5's export gates, read off the files (8 October)

Same edit as §38 (photo 0-3 s, video after it), driven on from there.

**Gate (3) - a video overlay over the base.** A 0:21 video overlay landed at the
playhead (3.494 s), trimmed back to 0:10 by dragging its tail, drawn by the
preview in the upper right at about 40% of the width. Rendered at 480p
(`frames=743 size=1956885`, 10 s of render) and read at one-second steps:

- 1.0 s and 2.0 s: the photo alone;
- 3.0 s: the base video alone - the overlay has not started;
- **4.0 s through 13.0 s: the PiP, upper right, at the size and place the preview
  drew it**, running its own picture;
- **14.0 s onward: the base alone - nothing left on screen after it ends.**

That is the whole of gate (3), and it is the composited path, so the clock still
and the transparent still are on it. `Mp4Probe`: 743 samples, 24.766 s, 30.000
per second, 33.33 ms ×742 - the same clean clock as the cuts-only render, which
is what `clockLeadMs` is for (a whole-gap clock used to write 30 fps over 60 fps
footage).

**Gate (5), the half that could have been upside down.** B13: *"every vertical
transition (Slide up, Slide down, and any kept band) is the same way up in the
file as in the preview (`Draw.shaderUniforms` turns the draw over for the
texture)"*. Set the join to **Slide up**, parked the playhead at 0:02.766 - inside
the half-second - and screenshotted the preview: the outgoing photo **above**, the
incoming video **below**, both travelling up. Then rendered and pulled the same
moment out of the file: at 2.5 s the photo fills the frame, by 2.6 the video has
appeared along the **bottom**, at 2.8 the photo is squeezed into the top third,
at 3.0 the video fills it. **The same way up.** Not flipped, not mirrored.

**Gate (6) - 9:16 at 720p.** Frame → 9:16, size 720p: the sheet says **720 × 1280**
before rendering, the done screen says 720 × 1280 after, and the frames are
upright and portrait with **no pillars** on the video (the landscape photo at 1 s
is letterboxed, which is what a landscape picture in a 9:16 frame should do).

**One thing to know about every file this writes, not a fault but worth
recording.** MediaStore reports all four exports *landscape* - `width=852
height=480`, `width=1280 height=720` - because the encoder writes the frame
sideways with a 90° rotation tag, which is what Media3 does to stay off encoders
that will not take a portrait surface. Everything that honours the tag (the
gallery, the done screen's own player, Windows' `MediaComposition`) shows it
upright; anything that reads only the track header would show it on its side. The
done screen is right because it measures through `MediaMetadataRetriever`.

### 40. The storage card, and the Clear that looked broken (8 October)

B15's storage card, measured against `du` on the phone - **every row is right**:

| Row | Card | `du -sk` |
|---|---|---|
| Exports kept inside Squish | 354 MB | 346,576 KB (30 private copies, in `getExternalFilesDir/exports`) |
| Photos and freezes | 397 MB | 387,840 KB |
| Reversed renders, imports and downloads | 694 MB | 677,975 KB (reversed + imports + music/online) |
| Voice and speech | 5 MB | 4,692 KB |

And `files/exports/` **inside** the private dir does not exist at all, which is
B15's "exports are stored once" seen: four exports rendered this session each
left the gallery copy and nothing else (`GallerySaver.retire`).

**The sweep keeps exactly what it should.** Before clearing, I listed the 23
files under the three render folders and worked out by hand which a draft names.
Clear then deleted `imports/stock/*` and both files under `reversed/`, and kept
every top-level import - which looked wrong until I noticed my own list had
globbed `files/projects/trash/*.json` while the bin's drafts live one level
deeper, in `trash/<entry>/`. They are named by **binned** drafts, and a binned
draft keeping its files is the rule Restore leans on. `StorageRules` is right;
my reading of it was not.

**What is wrong is what the card says about it.** A row reading 694 MB, tapped,
went to 673 MB and said nothing: 21 MB freed out of 694, with no way to tell
whether the Clear had worked, what was kept, or why. The blurb's "Only ones no
project uses are cleared" is true and answers none of that. The second tap -
nothing left to free - moved the number not at all.

**Fixed:** `StorageRules.clearedLine` (executed in `tools/jvm/ProjectRulesChecks.kt`,
negative-tested three ways), shown under the row that was cleared:

- a cache, which goes whole: **"Freed 21 MB."**
- a kind that keeps what drafts name, with some gone and some held:
  **"Freed 21 MB. 673 MB belongs to projects, 12 of them in the bin."**
- nothing moved at all: **"Nothing to clear - every file here belongs to a
  project, 12 of them in the bin."** - never "Freed 0 MB", which reads as a
  failure.
- everything moved: **"Freed 694 MB."** with no "0 MB belongs to projects" tail.

Seen on the phone: the renders row, already swept, now answers **"Nothing to
clear - every file here belongs to a project, 70 of them in the bin."** Which is
the true story, and it is one nothing in the app could say before: 673 MB of this
phone is held by seventy binned projects (all of them this month's test debris;
`DraftHousekeeping.TRASH_KEEP_MS` lets them go after thirty days).

Negative tests, each watched to fail against a copy of the file with the fault
put back: the bin clause dropped ("did not name the bin"), the
nothing-happened branch dropped ("said it freed something when it freed
nothing"), and `keepsReferenced` ignored ("a cache Clear blamed the bin").

### 41. The text sheet: the tab row's 59 px, and a sample line kept for good (8 October)

B10's Edit sheet, driven for the first time. What held straight away: **Add text
puts "Your text" in the middle of the picture with the keyboard up and the words
selected** (type and they are replaced, not appended), the tabs read **Keyboard ·
Style · Bubble · Animation** and sit over the keyboard, picking any tab but
Keyboard folds the keyboard, the strip folds away while typing and **comes back
when the sheet closes**, and a line added and left blank is gone again after Done
with no undo step. Two faults, both fixed and both seen fixed.

**The tab row jumped 59 px on every tab tapped**, which is the exact thing the
mechanism holding the keyboard's room exists to stop. Off the accessibility tree:
the row's labels at `[…,1017][…,1060]` with the keyboard up and `[…,958][…,999]`
after folding it. The held sheet was taking the keyboard's **own inset**, and
`imePadding()` applies only what the navigation bar does not already cover - so
the number held was a navigation bar too big, the sheet came out half a navigation
bar taller than the one it replaced, and the top edge (where the tabs are) rose by
half of that. 59 × 2 = 118 px = this phone's gesture bar.

Fixed by measuring **the room the keyboard left** rather than the inset it
reported (`SheetRules.heldSheetHeight`), which never looks at an inset and so
cannot be wrong about one. Also taken once the keyboard has **settled** rather
than at the tallest moment it passes through, since Gboard changes height on its
way up. After: `[…,1017][…,1060]` on all four tabs and back again - not one pixel.

*The check*: `tools/jvm/PolishRulesChecks.kt` now asserts the sheet's **top edge**
is the same number typing and holding, over six box heights and seven keyboard
heights. Negative-tested by writing the old inset-based shape back (with a 48 dp
navigation bar in it) and watching five of the cases fail by exactly half a
navigation bar.

**And a sample line kept for good.** Once in five tries, Add text → walk the tabs
→ Done left a caption reading "Your text" in the project - on the strip, in the
draft, and it would have been in every render. The draft had three text overlays
where it should have had one.

The race: `addText` sets the editor's handle on the new line and opens the sheet
from the tap handler, while the line and its selection come back through the view
model's flow a composition later. In between the sheet is open on a state that has
neither, and a rule phrased only as *"the sheet is no longer on this line"* says
**let go** at that moment: it finds no such line, discards nothing, and spends the
handle. Done then has nothing left to take the line off with. The same class as
the comment already sitting above that effect for the loading case - the guard
was there, it just did not cover this gap.

Fixed as `NewLineRules.lettingGo`, which cannot let go of a line that is not there
yet, with the handle in the effect's keys so setting it starts the wait over.
Negative-tested by dropping `lineExists` and watching the two cases that matter
fail. Then six Add text / Done cycles from a cold start: the draft stayed at two
overlays every time.

**Still open from this round:** the preview was **black at 0:00 for a moment
after the text sheet closed**, with the two lines drawn over nothing, and came
back on a scrub. The first clip there is a photo, so it is the still that was not
redrawn rather than a player. Not yet reproduced deliberately.

### 42. Record a voiceover, driven and heard of for the first time (8 October)

B9's Record, which nothing had ever pressed. **Every claim in the batch's
first paragraph holds**, seen:

- the Sound sheet's middle chip is **"Mic & camera"**, and the card under it is
  Record · *"A voiceover over the picture, from the playhead"* with *"Tap to
  start after a count of three. Stop, or the end of the edit, ends the take."*;
- the big red button starts a **count-in: 3 · 3 · 2 · 2 · 1** across five
  screenshots, headed "Get ready…", with **Cancel** beside it;
- then **"Listening - the picture plays silently"**, the take's own clock
  (0:20.300 at one point, which is the mic's count, not the playhead's), a
  **level meter** that moves, and the button turned into a stop square;
- the picture **plays** while it listens, and the strip draws a **"Recording…"
  clip growing on a sound row** - a plain teal box, no label, not selectable;
- **Stop ends the take**: a take stopped after about two seconds wrote
  `files/voice/take-….wav` at **197,164 bytes**, which is 2.2 s of 44.1 kHz
  16-bit mono. (Four earlier takes came out 24.6 s each and had me believing
  Stop was broken; they were all *my* latency - a take left alone runs to the
  end of the edit and ends itself, which is the other half of the same
  sentence, and by the time I pressed "Stop" the button was Record again. The
  file sizes are what settled it, not the screen.)
- it lands as a **"Voiceover" clip with a mic glyph and its waveform drawn**, on
  a sound row, **at the moment it started**, **selected**, with the **playhead
  back at its start**, and the undo step reads **"Undo: Record voiceover"**;
- the card then offers **"Record this take again"** under *"The new take lands
  where 'Voiceover' starts, and that one goes"* - which is B9's rule that this
  is the only way one take replaces another. Plain Record always added a new
  one.

**Not answered here:** the take by ear, in the preview or the file (the room was
silent at 3am, so the waveform is room noise), the mic indicator going out on a
Cancel, and the heavy-project "picture never starts" path.

### 43. Emptying Recently deleted (8 October)

The other half of §40. The storage card now says *"673 MB belongs to projects,
70 of them in the bin"* - and there was nothing to do about it: the bin's only
action is per card, Restore or Delete, so **seventy entries was a hundred and
forty taps**, each behind its own confirm. A phone that is full and an app that
says where the space went and offers no way to get it back is worse than one
that says nothing.

**"Empty it now"** now sits on the Recently deleted heading, in pink beside it.
It asks first, with the count and what goes with it: *"All 79 entries go now,
before their 30 days are up — and with them the stills, imports and takes they
were the last to name, which is what frees the space."* and the usual caution,
*"There is no undo and nowhere to fetch any of it back from. Your original
videos are untouched; the work built on them is not."*

It purges **one entry at a time through the same path** a single card uses
(`HomeViewModel.purgeOne`, lifted out of `purgeDraft`), so a picker grant is
released exactly when no other draft - live or binned - still names its files,
and one unreadable entry does not stop the rest.

**Seen:** the action and its dialog on the phone, with the right count. The
sweep itself was verified through a *single* card rather than the whole bin -
the seventy entries on this phone are the owner's, not mine to destroy - and
that one purge took the bin from 71 to 70 through the refactored `purgeOne`,
which is the function the new one loops.

And the storage line from §40, now with something to say:
**"Freed 14 MB. 2 MB belongs to projects, 70 of them in the bin."** - the four
orphaned voiceover takes from §42 gone, the one a project still names kept.

### 44. The editor on its side, and the look shader on this driver (8 October)

Two of B6's and B12's never-seen claims, both held.

**Two panes on its side.** `user_rotation 1` with the editor open: the header,
the picture and the transport take the left half, the strip with its four track
heads and the level-0 toolbar take the right. Nothing is cut off and the ruler
is readable. **The players carry across**: started playing in landscape, rotated
to portrait (still playing, 0:04.288, picture live), rotated back (still playing,
0:07.373, picture live) - no black frame, no stall, no restart. That is B6's
"the preview is movable content", seen. (The rotation settings were put back to
`accelerometer_rotation 1`, `user_rotation 0` afterwards.)

**The look shader compiles and runs on the moto g84's ES2 driver** - B12's
biggest single risk, since a shader that fails to compile fails *asynchronously*
on the player and the surface then just plays plain, with nothing said:

- **Filters → Vivid** visibly deepens the reds and greens and darkens the
  shadows, at once, on the live picture;
- **Adjust → Wheels** gives Shadows · Midtones · Highlights over a real colour
  wheel with a puck; dragging the Shadows puck toward red turns the dark half of
  the frame red and leaves the highlights alone, which is what a shadows wheel
  is for. So the eight `uHsl` vec3s are bound and read.
- nothing in logcat about a shader, a program or a link.

The eight band swatches carry their names for a screen reader (`content-desc`
"Green", "Cyan", "Blue", "Purple", "Magenta"), which is sweep ten's fix seen.

**One wart worth writing down, not a fault.** A video opened with "Open with",
looked at and left without an edit leaves no project on the grid (§34) - but it
*does* leave an entry in Recently deleted, by the decision written on
`EditorViewModel.onCleared` ("To the bin, not deleted, so it can still be
brought back"). There is nothing to bring back: the rule only fires when
`!history.canUndo`. On this phone that is where several of the "Untitled edit ·
0 clips" entries in the bin came from, and each goes on holding its import copy
for thirty days. Deleting outright instead would have to release the picker
grant in the same breath, which `onCleared` is the wrong place to do, so it is
left as it is and said here.

### 45. A clip's crop under a project frame: the preview and the file (8 October)

B12's merge claim - *"a base shot's export chain is now the preview's layer for
layer"* - on the combination most likely to come apart: **a 1:1 crop on the shot
under a 16:9 project frame**.

**They agree.** The preview at 0:12.023 and the exported frame at 12.1 s are the
same picture: the same band of the source (a leaf, a red sari, the top of the
timeline below it), full width, no pillars, same framing to the pixel. Pulled off
the phone and read with `tools/desktop/frames.ps1`.

**And the rule is not what CLAUDE.md said it was.** That paragraph read *"Frame
16:9 over a 1:1 crop pillarboxes the square rather than cutting a band from it"*.
It cuts a band, and it should: `composeResolution` is the picture's **own framed
shape** when there is no padded canvas, so the 1:1 square cropped out of a
portrait 1080×1920 source is fitted to 1080×1920 - full width, bars above and
below - and the frame's 16:9 is then cut from that canvas, landing *inside* the
square. No pillars are possible. What the review round actually fixed is that the
band comes out of the **canvas** rather than out of the **window** (a 16:9 cut
from a 1:1 window is a different, zoomed band). The sentence is corrected in
CLAUDE.md; the behaviour is right and is now seen.

Also on the way: the Effects library's tiles each show **this clip's own frame**
with the effect on it (Flash bright, VHS and Glitch visibly different), applying
one puts it on its own **effects row** on the strip with a cassette glyph and
starts playback so it can be seen, and **VHS reaches the live picture** -
scanlines, noise and a colour shift. The **Reel template** lays a crop, a look
and a bold "WATCH THIS" title on at once, as one step named "Undo: Template
Reel". **Full screen** fills the screen with a scrub bar; a drag on it goes
0:02.045 → 0:12.023 and closing full screen **keeps the frame**.

### 46. The Track picker showed a different frame from the one you were aiming at (8 October)

**Found on the phone, fixed, and seen fixed.** The Track panel's whole purpose is
in its own comment: *"The picker shows the frame under the playhead with the box
you are about to track drawn on it. That matters more than it sounds - tracking
succeeds or fails almost entirely on what you select."*

It did not show the frame under the playhead. `ThumbnailExtractor.frameAt` seeks
with **`OPTION_CLOSEST_SYNC`** - the nearest *keyframe* - and on a long-GOP file
that is seconds away. Driven:

- playhead **0:00.000**: the picker and the preview agree (0 is a keyframe);
- playhead **0:12.023**: the preview shows a woman in an orange sari among
  leaves; **the picker shows a different shot entirely** - a woman in red by a
  wooden door. You aim at a thing in one picture and the tracker, which starts
  from the playhead's own frame, goes looking for it in another.

Fixed by giving `frameAt` an `exact` flag (`OPTION_CLOSEST`, which decodes
forward from the keyframe) and passing it from `AnalysisEdits.sampleFrame` - the
Track picker and the eyedropper's sample - and from the **transition tiles**,
which had the same hole: the tile is meant to be "the end of the one before, the
start of this one", and a keyframe before a join can be a whole shot earlier.
The default stays the fast seek, which is right for a filmstrip tile, a filter
chip or a blurred backdrop.

After, at **0:20.567** - deliberately not a keyframe - the picker and the preview
are the same frame: same subject, same swing rope, same pose.

*The check*: `tools/jvm/ControlChecks.kt` now refuses a `ThumbnailExtractor.frameAt`
in either of those two files without `exact = true`, with the reason in the
message. Negative-tested by taking the flag off `sampleFrame` and watching it
fail.

**And one I had let rot.** Running `ControlChecks` here turned up that §41's fix
had broken its own guard: the check asserted the exact text of the
`LaunchedEffect(openTool, state.selectedClipId, state.isLoadingSource)` line,
which the fix had to change. It now asserts the effect goes through
`NewLineRules.lettingGo` and tells it both of the things that matter, which is
what the rule is. That is the cost of not running the suites after an editor
change, written down.

### 47. The whole Fast lane was dead, and I killed it (8 October)

**Found on the phone, and it is mine.** Yesterday's fix for "an emptied edit said
it was twenty-two seconds long, and would have exported them" made
`EditorUiState.trimmedDurationMs` fall back to the source's window *only while
the editor is loading*. Every quick tool - **Snip, Squeeze, Extract audio, Save
as GIF** - builds its state with `videoClips = emptyList()` on purpose
(`QuickToolViewModel.editorStateOf`; only a merge has clips), and is never
loading. So all four reported a length of **0**, `SquishError.preflight` refused
them on `trimmedDurationMs <= 0`, and:

> **Snip it** on a video with two handles 6.5 s apart →
> **"Nothing on the timeline. Add a clip, or widen a trim handle."**

Four of the six things on the dashboard's Fast lane, for a day.

The rule needed to tell *"this edit was emptied"* from *"this kind never had
clips"*, and nothing in the state said which. `EditorUiState.clipsAreTheEdit`
does now - true for the editor, `= merging` for a quick tool - and the length
asks `isLoadingSource || !clipsAreTheEdit`.

*The checks*: `LaneChecks` gains the quick-tool case on the rule itself, and -
since the rule was never wrong, the caller was - **`ControlChecks` reads the two
lines that join them**: the `sourceWindowStands = isLoadingSource ||
!clipsAreTheEdit` in `EditorModels.kt` and the `clipsAreTheEdit = merging` in
`QuickToolViewModel.kt`. Negative-tested by breaking each in turn and watching
its own message come back.

**Seen fixed on the phone**: Snip of a 0:22.301 clip, handles at 6.633 and
17.655 → *"Snipped · 854 × 480 · 11.0 s · 30 fps · 890 KB · Movies › Squish"*,
and the size is a stream copy's share of the source's. Extract audio on that →
*"Extracted · 11.1 s · 189 KB · Music › Squish"*.

**What this cost and what it teaches.** Snip and Extract audio were *driven and
seen working* earlier the same night - before this fix landed. A fix made in the
editor broke four screens that share one state class and were not reopened
afterwards. `trimmedDurationMs` is read by the header, the export sheet, the
file's length, the recovery card and the sidecar, and by five screens; a change
to it is a change to all of them.

### 48. The rest of the Fast lane, and Fit to a size (8 October)

After §47's fix, the three tools that were refusing themselves, driven end to
end on the phone:

- **Snip**: handles at 6.633 and 17.655 of a 0:22.301 clip →
  *"Snipped · 854 × 480 · 11.0 s · 30 fps · 890 KB · Movies › Squish"*. The
  frame is the source's own and the size is a stream copy's share of it, so the
  plain path still copies rather than re-encodes. The handles themselves are
  B15's fix seen: a **1500 ms** drag moved the start 0:00.000 → 0:05.544 and a
  **200 ms** drag moved it on to 0:09.933 - the slow drag that used to move
  nothing and the fast one that used to snap back. Moving the end handle put the
  player on it.
- **Extract audio** on that → *"Extracted · 11.1 s · 189 KB · Music › Squish"*,
  an .m4a.
- **Squeeze** of it → *"Squeezed · 640 × 360 · 600 KB · **33% smaller · was
  890 KB**"*, with the before/after pill that B14 says belongs only to Squeeze.
- **Squeeze with Fit to a size**, which had never been run: a 1:15.599, 17.8 MB
  clip at a **16 MB** target came out **478 × 850 · 1:15 · 30 fps ·
  15,954,353 bytes** - 15.95 MB, **under the limit by 0.3%**, and the done
  screen reads "16.0 MB · 11% smaller · was 17.8 MB". A strict upload limit
  solved tight and not missed; the "Keep this one / Try again, tighter" card
  therefore did not show, and remains unseen.
- **Stitch** (which has clips, so §47 never touched it): two files picked at
  once read *"2 clips joined · 1:37.865"* with a Playing order list; the row
  arrows reorder and the positions recompute (0:22 at 0:00, 1:15 at 0:22), and
  Clear empties it.
- And a nice one that was never written down: a tool session whose source has
  since been deleted from the gallery says so on its own card - **"The video
  can't be opened any more — remove this with ✕"** - rather than failing when
  it is opened.

### 49. A walk round the clip toolbar (8 October)

Opening each tool and looking, which is how §46 was found. Nothing else broken;
what is here is what each sheet actually says, so the next session can tell a
changed one from a broken one.

- **Volume**: "Clip sound · This clip's own sound, mixed with the rest", a
  keyframe offer ("Add a key here to animate this"), Level 100%, and **Remove
  silences** with its own sentence ("Cuts the pauses longer than 0.7 s out of
  this shot, keeping a little air round every word").
- **Animation**: In (None · Fade · Zoom in · Zoom out · Slide left · Slide
  right), Out, Loop (None · Pulse · Swing · Bob · Flicker · Drift), then Moves,
  "Across the whole clip, over the arrival and leaving". The heading reads
  "Sitting still — pick an arrival, or a move below".
- **Placement**: Scale 1×, Across +0%, Up / down +0%, with the keyframe offer.
- **Rotate**: turns the picture a quarter **clockwise on screen** - the top of
  the frame goes to the right - and the turned shape is letterboxed inside the
  frame rather than cropped. (B11's claim is "clockwise on screen *and* in the
  file"; the file half still wants an export.)
- **Mirror** on top of that flips it, and the two compose the way B11 says they
  do (the mirror before the turn).
- **Stabilize**: "Measuring – frame 30 of 664 · Measuring… 4%" with a bar, then
  **"Measured 664 frames. Zoomed in 1% to hide the edges the correction
  exposes."** and the button becomes "Measure again". 664 frames of 1080p in
  well under a minute. Dragging **Strength** 50% → 92% re-solved with **no
  "Measuring…"** and the same "Measured 664 frames" line - B13's claim exactly.
  (1% is the right answer here: the footage is a screen recording with no shake,
  and a deliberate pan is what the stabilizer is supposed to leave alone.)
- **Effects**: the library's tiles each carry *this clip's own frame* with the
  effect on it, applying one puts it on its own **effects row** with a cassette
  glyph and starts playback, and VHS reaches the live picture.
- **Templates → Reel**: a crop, a look and a bold "WATCH THIS" title at once, as
  one step named "Undo: Template Reel".

### 50. Music and the beat detector (8 October)

B9's music and beats, driven for the first time.

- The Music card's four categories - **Squish originals · Sound effects · On
  this phone · Starred & recent** - are a **two-by-two grid, all in sight on a
  phone**, which is what B9 asks.
- **Squish originals** lists its tracks with "Chill · 78 BPM · 49s",
  "Happy · 112 BPM · 51s" and so on, each with its own **Add**. Adding one lands
  it on a sound row, selected, with a real waveform drawn, as
  **"Undo: Add Good Vibes"**.
- **Find the beat** on it answers **"42 beats on Good Vibes - they move with
  it"**, **112.0 BPM**, *"Strong pulse"*, *"Bar starts on beat 1"*, **11 bars**,
  over the density chips (Every beat · Every 2 · Every bar) and ÷2 · ×2 · Shift
  bar with the octave explanation under them. The track is *labelled* 112 BPM by
  the card that offered it, and the detector answered 112.0 - and 22.266 s at
  112 BPM is 41.6 beats, so 42 is right too.
- The **dots are drawn on the song**, evenly, along the bottom of the clip under
  its waveform.
- **"Every bar" thins them to one per bar** - ten or eleven across the same
  stretch - so the chip reaches the dots.
- **Slowing the song spreads them.** At 0.5x the clip reads 0:44, the waveform
  is redrawn over the new length, and the bar dots are **twice as far apart**:
  five across the same 22-second window where there were ten. That is B9's
  "beats on the clip carried through its position and speed curve", seen.

Not answered: any of it **by ear**, and whether a cut snaps to a dot.

### 51. Shapes and the mask shader's heart and star (8 October)

- **Stickers → Shapes and arrows** offers Rectangle · Ellipse · Triangle ·
  Diamond · Star · Line · Arrow · Double arrow, each named for a screen reader.
  A **Star** goes on at the playhead as **"Undo: Add star"**, drawn as a red
  **outline** on the picture - which is what the card promises ("outlined, so
  what it marks still shows") and what the whole point of a marker shape is -
  with its own box and the four corner buttons. It lives on the text track.
- **Mask → Add a mask** offers Rectangle · Ellipse · Linear · Mirror · **Heart**
  · **Star** over Cut out · Pixelate · Blur, with a keyframe offer.
  **Both of B12's awkward shapes render on this phone's ES2 driver**: the Heart
  cuts a clean heart out of the picture with a soft, even feathered edge, and
  the Star a clean five-pointed one - each with the dashed outline and its drag
  handle drawn over it. That is the mask shader's own distance field
  (`MaskOutlineChecks`' arithmetic) arriving on the screen.

**Postscript to §47.** Running the whole suite set afterwards - which the §46
lesson says to do and which CLAUDE.md says to do whenever a file on the shared
`$TIMELINE` list is touched - turned up one failure in ninety, and it was this
repo catching me: `DraftFieldChecks` refuses any new `EditorUiState` field that
is in neither the draft nor an explicit transient list. `clipsAreTheEdit` is a
property of the **screen that built the state**, not of the edit, so it goes on
the transient list with that reason written beside it. Negative-tested by taking
it off again.

### 52. The mask in the exported file (8 October)

A **Heart** mask on the shot, rendered at 480p and the file read back: the
exported frame carries **the same heart, the same soft feathered edge and black
outside it** as the preview drew. The preview's mask shader and the export's are
two different programs on two different paths, and on this phone they agree.

With §51 that is B12's mask shape work settled on both sides for the shapes most
likely to come apart - though a mask **tracked** onto something moving, and a
keyed mask on a **retimed** clip, are still unseen.
