# Device findings

Results from driving the app on the moto g84 5G (Android 15, Snapdragon, 1080x2400).
Each entry says which build, what was done, and what happened. Fixed entries move
to the bottom with the commit that fixed them.

## Open

### Export with an overlay fails: "Asset loader error" (build efbe483, waves 0-1)
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
and the listener returns null. So a sequence declared with audio must never begin
with an image item. The same edit without the overlay (video + photo + Dissolve)
exported correctly (7.72 s, 640x480), so it depends on which roll starts with a
filler. Fix so that no sequence can start with an image while declaring audio:
e.g. a filler that is a short silent *video* (rendered once, cached) made invisible
with AlphaScale(0), or Media3's own `addGap` where it is supported for that
sequence's track types. Also map this exception to a specific message.

### Playback freezes where the main track ends if an overlay runs longer (build efbe483)
Same edit as above: base track ends at 7.700 s (video 5.2 + photo 3.0 - 0.5 dissolve),
the 8 s overlay starting at 0 makes the edit 8.363 s. Press play from 0: the clock
reaches 7.700 and stays there, pause icon still showing (playing), for 6+ seconds;
it never reaches 8.363 and never stops. The overlay keeps drawing its frame. The
preview clock must keep running over the stretch where only an overlay (or only
audio, or only a caption) exists, and stop at the true end.
Also noted in the same run: clock samples 1 s apart read 1.287 then 4.234 - check
whether the clock jumps at the dissolve.

### Export at 4K silently writes 1440x1080 (build efbe483)
Edit: 200x150 video + photo + music. Export sheet with 4K selected says
"2880 x 2160, ~50.4 MB". The file written is 1440x1080, 15.2 MB, 8.23 s (audio and
video present, otherwise fine). Either the encoder refused 4K and something fell
back without telling the user, or the size decision and the summary disagree. The
sheet must show what will actually be written, and a fallback must be announced.

### Export sheet chips move under the finger
When the "Bigger than the source" warning appears, the sheet grows upward and every
quality chip shifts ~100 px; a tap aimed at 480p on the previous layout landed on
4K. Chips should not move when a hint appears (reserve the space, or put the hint
below the chips).

### Music "Add" places the track at the playhead even when the playhead is at the end
With the playhead at 7.67 s of an 8.2 s edit, Sound > Add put the song there (0.5 s
of it audible). CapCut adds music at the playhead too, but shows it; here it is easy
to miss because the panel covers the timeline. Consider: add at 0 when the playhead
is within the last second, or scroll the timeline to show where it landed.

### Adding a title never lets you type
Words > Titles > "BIG NEWS" drops the preset text on the picture; no keyboard opens
and there is no obvious way to type your own words. CapCut: Text > Add text opens
the keyboard at once with the new text selected. (B10's keyboard-first text.)

### Rotated export at "Original" is labelled 150 x 200 but written 200x150 + rotation tag 90
Source is 200x150. Export sheet says "150 x 200"; the file is 200x150 with
`orientation=90`, which players show as 150x200. Probably correct, but confirm the
encoder output is what B5 intended (pixels rotated vs tag), and that other players
(WhatsApp, Instagram upload) respect the tag.

### "Open with Squish" is ignored when Squish is already running
`am start -a VIEW -d content://media/... -n com.squish.app/.MainActivity` while the
app is open: "intent has been delivered to currently running top-most instance" and
nothing happens. MainActivity needs onNewIntent handling.

### Recovery banner length ignores overlays/trims correctly? (pre-B1 build)
Banner said "3 clips, 0:16.563 of edit" for an 8.36 s edit with an 8 s overlay: it
summed the overlay into the length. Re-check on the current build.

### Transition badge/Blend panel sits over the action bar; taps land on Undo
With the Blend panel open, the action bar moves up; a tap aimed at a spot that was a
panel control on the previous frame landed on Undo and reverted a transition. Layout
shifts under the finger (B6 IA work should remove the moving bar).

### B6: the recovery banner is clipped by the timeline (build 6e73151)
On opening a clip with a saved edit, the "Your edit of this clip is saved" card
sits between the transport and the timeline; the strip overlaps its lower half so
"Start a new project" is hidden and untappable. The card must fit, or sit over
the strip as a proper dialog/sheet.

### Recovery card states the wrong length
"2 clips, 0:23 of edit" for an edit that was 8.2 s when last touched by the
tester (the draft had since been changed on the device to 23.8 s by a person using
the phone at 05:02-05:04, so 0:23 may be right here; the earlier 16.6-for-8.4 case
stands). Recheck: the card's length must equal the timeline's duration.

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
