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
