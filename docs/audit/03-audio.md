# Audio: music library, device music, sound effects, voiceover, extract audio, per-clip volume, fades, beats, noise reduction, voice effects, ducking, waveforms, audio trim/split

Squish's audio model is structurally stronger than CapCut's in one respect (every sound is an ordinary timeline clip, unlimited overlapping tracks, auto-sync and beat detection on-device), but the feature surface a CapCut user expects around each clip is mostly missing: no voiceover recording at all (no RECORD_AUDIO in the manifest), no fade in/out, no per-clip volume or mute on video clips (Clip.volume is serialized for video clips but never applied), no gain above 100%, no sound-effects library, no noise reduction, no extract-audio from a clip on the strip, no beat dots on the clip, and overlay clips are always silent. The voice changer is a single project-wide setting that only touches the camera audio. Concrete code defects found: the preview pitch-shifts added music when Chipmunk/Deep is selected while the export does not (PreviewEngine.setSpeed pushes liveVoice.pitch to every audio player); every tick of the Level slider or of an audio drag re-seeks every sound player because setTimeline always calls primeAudio; the export's "silent pad" for a music cue is a slice of the source video's own audio, so a cue placed later than the source file is long starts early on export; a speed-ramped audio clip is clipped to its played length instead of its source span; AudioMixing only registers 1- and 2-channel matrices so any 5.1 film rip fails to export at any volume below 100%; auto-sync ignores the head clip's own trim; none of the audio-panel edits (trim, level, place, nudge, reset, auto-sync) are undoable; the beat grid is not saved in the draft; "Cut on the beat" razors the music itself into fragments; and the Sound panel buries the selected track's controls under a 60-row music browser. The strip's "split every track under the playhead" rule also chops the music bed with every video cut, which CapCut users will read as a bug.

## gap · critical · L — No voiceover recording

**Detail:** There is no RECORD_AUDIO permission, no recorder and no 'Record' entry anywhere; the panel copy says 'Music, voiceover, or a separate mic' but a voiceover must be recorded in another app and imported. This is the single most-used audio feature after music for the vlog/tutorial audience.

**CapCut:** Audio > Record: a big red button; press-and-hold (or tap to start/stop) records from the playhead while the timeline plays back muted-or-not, a 3-2-1 countdown option, the take lands as an audio clip at the playhead with a mic icon, re-record replaces it. NoiseSuppressor/AGC on capture.

**Evidence:** AndroidManifest.xml (permissions: POST_NOTIFICATIONS, READ_MEDIA_AUDIO, READ_EXTERNAL_STORAGE only); grep for MediaRecorder/AudioRecord/RECORD_AUDIO returns nothing; AudioPanel.kt:124.

**Files:** app/src/main/AndroidManifest.xml; app/src/main/java/com/squish/app/editor/AudioPanel.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/media/audio/VoiceRecorder.kt (new)

## gap · high · M — No fade in / fade out on audio clips

**Detail:** Clip has no fade fields, no processor applies one, and the strip draws none. Every music bed that is trimmed to the video end stops dead; every voiceover starts with a click. Preview support is cheap because syncAudio already writes player.volume per tick (fade = computed volume); export needs a small time-based gain BaseAudioProcessor placed after the ramp processor; the strip needs the two ramp triangles; autosave needs two fields.

**CapCut:** Select an audio clip > Fade: two sliders, Fade in and Fade out (0-10 s), the clip shows the ramps as shaded triangles at each end; also available on main-track clip audio.

**Evidence:** grep fadeIn|fadeOut hits only Compose animation imports in ExportSheet.kt:5-6; TimelineModels.kt:35-93 (Clip fields); PreviewEngine.kt:888 (volume written per tick).

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/media/audio/FadeProcessor.kt (new); app/src/main/java/com/squish/app/timeline/TimelineEditor.kt; app/src/main/java/com/squish/app/data/ProjectAutosave.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## gap · high · M — No per-clip volume or mute for video clips, and no gain above 100%

**Detail:** Level and mute for camera audio are project-wide (originalVolume/muteOriginal). Clip.volume exists and is serialized for video clips but is ignored: export builds base audio with state.originalVolume and preview sets baseA/baseB volume from originalVolume. A talking-head shot next to a loud B-roll shot cannot be balanced. AudioMixing clamps to 0..1 so a quiet phone recording cannot be boosted, and ChannelMixingMatrix.scaleBy accepts >1 fine.

**CapCut:** Every clip on every track has Volume 0-1000% (with a 100% detent) and a Mute; the main track header has a speaker icon to mute all original audio at once.

**Evidence:** VideoProcessor.kt:354 (buildAudioProcessors(clip, state.originalVolume, ...)), :349 (removeAudio from global muteOriginal); PreviewEngine.kt:389-390 (base volume global), :816-853 (no per-clip volume on surface sync); AudioMixing.kt:20-25 (coerceIn(0,1), null above 0.999); EditorViewModel.kt:442-443 (audio clips only).

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/media/AudioMixing.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · medium · M — Overlay (picture-in-picture) clips are always silent

**Detail:** Both preview and export strip an overlay's audio unconditionally. A reaction cam or a second angle laid over the base track loses its sound with no way to keep it, and the user's own note ('have you considered how an overlay should work') points here.

**CapCut:** An overlay clip keeps its audio, with the same Volume/Mute/Fade tools as any clip.

**Evidence:** VideoProcessor.kt:349 (setRemoveAudio(clip.isOverlay || ...)), :353 (empty audio processors for overlays); PreviewEngine.kt:329-339 (overlayPlayer volume = 0f).

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/editor/TransitionPanel.kt

## gap · medium · M — No 'Extract audio' from a clip on the timeline

**Detail:** The only routes are the standalone 'Rip' quick tool (writes an .m4a to Music/Squish) and the Sound picker accepting video/* (which adds the whole file as a second decode, see the placeholder-surface bug). There is no way to detach a timeline clip's own sound into the audio lane so it can be slid, trimmed or cut independently, or to mute the picture and keep the sound.

**CapCut:** Clip menu > 'Extract audio' creates an audio clip with the same in/out under the video and mutes the video clip; Audio > Extracted lists sound pulled from gallery videos.

**Evidence:** QuickTool.kt:50-56 (Rip); EditorScreen.kt:419 (picker mimes); no extract/detach function in EditorViewModel (grep extract).

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · medium · L — No sound-effects library

**Detail:** Only 8 synthesized music styles and the phone's music. A whoosh, pop, click, riser, ding or crowd bed has to be imported from elsewhere. Given the app's no-network promise, a procedural SFX set in the MusicSynth style (noise bursts, sine sweeps, filtered impulses) with categories and a tap-to-audition list would be licence-free and a few hundred lines.

**CapCut:** Audio > Effects: categorised sound-effects library (Transitions, Funny, Ambient, Game...), search, favourites, tap to audition, + to add at the playhead.

**Evidence:** MusicSynth.kt:62-111 (eight music styles only); MusicPanel.kt:93-96 (two tabs: originals, phone).

**Files:** app/src/main/java/com/squish/app/media/audio/MusicSynth.kt; app/src/main/java/com/squish/app/media/audio/MusicLibrary.kt; app/src/main/java/com/squish/app/editor/MusicPanel.kt

## gap · medium · M — Music library: short originals, no loop-to-fit, no favourites/recent, 60-row cap

**Detail:** Originals run 30-60 s and simply stop under a longer video; there is no 'loop to end' or 'fill' action, so extending music is duplicating the clip by hand. The phone browser lists newest-first, shows at most 60 of the 300 fetched, has no favourites, no recently-used, no artwork, and a permanently-denied permission leaves a button that does nothing (no Settings deep link).

**CapCut:** Vast library with Recommended/Favourites/Recent tabs, TikTok sounds, import from files/link; a song can be dragged out beyond its length to loop.

**Evidence:** MusicSynth.kt:64-110 (seconds = bars*4*60/bpm, 30-60 s); MusicPanel.kt:215 (list.take(60)), :161-173 (permission state), MusicLibrary.kt:62 (DATE_ADDED DESC), :84 (MAX_RESULTS 300).

**Files:** app/src/main/java/com/squish/app/editor/MusicPanel.kt; app/src/main/java/com/squish/app/media/audio/MusicLibrary.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## gap · medium · M — Voice changer is one global setting on camera audio only; nothing on added tracks

**Detail:** EditorUiState.voiceEffect applies to every base video clip at once (a B-roll shot added later gets the same Robot voice) and never to an added audio clip, so a voiceover imported from a recorder cannot be changed. The card is hidden when the source has no audio even though a later-added clip might. Six presets, no strength.

**CapCut:** Voice changer is per selected clip (video or audio), with a dozen-plus presets and live audition.

**Evidence:** EditorModels.kt:296 (global field); AudioPanel.kt:58 (gated on sourceHasAudio); VideoProcessor.kt:354 vs :475; PreviewEngine.kt:240-248 (VoiceProcessor only in base players' sink), :308-325 (audio players have none).

**Files:** app/src/main/java/com/squish/app/editor/EditorModels.kt; app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## gap · medium · L — No noise reduction

**Detail:** Nothing in the pipeline attenuates hiss or room tone; the app has the PCM decode and FFT (Fft.kt) already, so a spectral-gate BaseAudioProcessor (noise profile from the quietest 5% of frames, per-band attenuation) is feasible on-device; at minimum NoiseSuppressor/AGC should be enabled when voiceover recording arrives.

**CapCut:** Select a clip > 'Reduce noise' toggle (and newer 'Enhance voice').

**Evidence:** grep -i noise in media/audio returns only synth noise generators; Fft.kt exists for the beat detector.

**Files:** app/src/main/java/com/squish/app/media/audio/NoiseGateProcessor.kt (new); app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## gap · medium · M — Beats live on the ruler, not on the clip; no manual tap-to-add beat; lost when the clip moves

**Detail:** Detected beats become global markers (or bar ticks on the ruler) in timeline time; moving or trimming the song afterwards leaves them behind (acknowledged in EditorModels.kt). There is no way to tap beats in by ear while listening, which is how most people mark a drop. The four-beat 'Shift bar' cycles blind.

**CapCut:** Select an audio clip > Beats: 'Auto generate' (Beat 1 / Beat 2 density) draws yellow dots on the clip that travel with it; 'Add beat' drops a dot at the playhead while listening; clips snap to the dots.

**Evidence:** EditorModels.kt:167-175 (timeline-time beats, documented trade-off); EditorViewModel.kt:687-693 (markers); EditorScreen.kt:339 (barMarkers on ruler).

**Files:** app/src/main/java/com/squish/app/editor/EditorModels.kt; app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt; app/src/main/java/com/squish/app/editor/BeatPanel.kt; app/src/main/java/com/squish/app/data/ProjectAutosave.kt

## gap · low · L — No volume keyframes on audio clips

**Detail:** Volume is one number per clip; a music bed cannot dip under a line of dialogue and come back, except by splitting the clip and setting three levels (and there are no fades to hide the steps).

**CapCut:** Volume keyframes: tap the diamond at the playhead with the Volume slider open; the clip draws the gain curve.

**Evidence:** TimelineModels.kt:44 (volume: Float); Keyframe.kt covers transform only (ARCHITECTURE.md 'Keyframed opacity and color' row).

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## gap · low · M — No automatic ducking of music under speech (a chance to beat CapCut)

**Detail:** SpeechSegmenter already finds speech spans on the camera audio for captions; the same spans could drive a music-under-voice dip (e.g. -12 dB with 200 ms ramps) as generated fades/keyframes. CapCut mobile has no ducking, so this is a differentiator rather than parity.

**CapCut:** Not available on mobile; desktop has 'Auto-duck' style volume automation via keyframes only.

**Evidence:** SpeechSegmenter.kt (speech spans on decoded PCM); no consumer outside captions.

**Files:** app/src/main/java/com/squish/app/media/audio/SpeechSegmenter.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## ux · high · M — Selecting an audio clip does not surface a clip toolbar; its controls sit at the bottom of a long form under the music browser

**Detail:** AudioPanel stacks Voice, Music (chips + up to 60 rows), Beat, Camera audio, Added tracks, then the selected track's waveform/Level, Trim, Place and Align cards. Tapping a song on the strip to lower its level means opening Sound and scrolling past the entire library. CapCut's contextual bottom bar is the single biggest ease-of-use difference in this area.

**CapCut:** Tap an audio clip: bottom bar becomes Volume, Fade, Split, Delete, Speed, Voice changer, Beats, Reduce noise, Copy; tap empty audio track: '+ Add audio'.

**Evidence:** AudioPanel.kt:56-160 (card order), :162-287 (selected-track cards last); EditorScreen.kt:416-420 (one panel for the whole tab).

**Files:** app/src/main/java/com/squish/app/editor/AudioPanel.kt; app/src/main/java/com/squish/app/editor/EditorScreen.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · high · S — 'Cut' splits every track under the playhead, so each video cut fragments the music bed

**Detail:** The action bar's own tooltip says 'Cut splits every track under the playhead'. The consequence is that a 30-cut montage leaves the song in 30 pieces: deleting a shot leaves an orphan fragment, dragging the song is 30 drags, per-fragment levels drift, and beat re-detection covers only the first fragment. Expected: split the selected clip; with nothing selected, split the base video only (or all, with the tooltip).

**CapCut:** Split acts on the selected clip; with nothing selected it splits the main-track clip under the playhead only.

**Evidence:** TimelineModels.kt:326-359 (victims = every clip spanning cut); EditorViewModel.kt:1930; TimelineEditor.kt:1631, :1730.

**Files:** app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## ux · low · S — 'Trim the track' In/Out buttons step 100 ms and duplicate what the strip edges already do

**Detail:** Eight taps to move an in point by under a second, with no scrubbable waveform and no audition; the strip handles trim by drag with the waveform visible. Either remove the card or replace it with a draggable waveform range that plays the boundary.

**CapCut:** No such panel; trim by dragging clip edges with the waveform, or split.

**Evidence:** AudioPanel.kt:189-213 (AudioPointRow +-100 ms), :192 (subtitle admits it is the same thing the clip's edges do).

**Files:** app/src/main/java/com/squish/app/editor/AudioPanel.kt

## ux · low · S — Align card's readout labels a content trim as a sync offset, and the 8 s auto-sync limit is unstated

**Detail:** The big teal number is sourceInMs - timelineStartMs; after trimming the head of a song by 30 s it reads '+30.0s' as if the track were 30 s out of sync. Auto-sync only searches +-8 s of lag and 45 s of audio, but 'No clear match' never says so, so a recorder started 15 s early reads as a bad recording.

**CapCut:** Not applicable (no auto-sync).

**Evidence:** AudioPanel.kt:253-268; AudioSyncAnalyzer.kt:30,37 (ANALYSIS_WINDOW_MS, maxLagMs=8000); AudioPanel.kt:343 (NoMatch copy).

**Files:** app/src/main/java/com/squish/app/editor/AudioPanel.kt; app/src/main/java/com/squish/app/media/audio/AudioSyncAnalyzer.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## ux · low · S — Beat action chips look like toggles but are one-shot buttons

**Detail:** 'Every beat / Every 2 / Every bar' are SelectableChip(selected=false) so they read as an unselected segmented control; tapping fires the action immediately with no confirmation, and nothing shows which density is now marked.

**CapCut:** Beat density is a real toggle (Beat 1 / Beat 2) whose current state is shown on the clip's dots.

**Evidence:** BeatPanel.kt:251-261.

**Files:** app/src/main/java/com/squish/app/editor/BeatPanel.kt

## ux · medium · M — Overlapping sounds are piled on a single 54 dp lane, hiding waveforms

**Detail:** Every added sound shares one lane; overlaps are stacked shortest-on-top with a '2/3' cycle badge, so a voiceover over music hides the music's waveform and its grips under the voiceover for that stretch. Both waveforms matter when cutting to speech and beat at once.

**CapCut:** Each overlapping audio clip gets its own row under the main track; rows appear and collapse automatically.

**Evidence:** TimelineEditor.kt:92 (LANE_HEIGHT 54.dp), :551-556 (one Lane for all audioClips), :863-870 (stacked semantics).

**Files:** app/src/main/java/com/squish/app/timeline/TimelineEditor.kt; app/src/main/java/com/squish/app/editor/EditorModels.kt

## ux · low · S — Camera audio switch is shown and toggles muteOriginal even when the clip has no audio

**Detail:** With sourceHasAudio false the card says 'This clip has no audio track' but still offers a live switch that records a 'Camera audio' undo step and changes muteOriginal, which then affects clips added later.

**CapCut:** Mute is per clip; a silent clip shows a muted icon and no toggle.

**Evidence:** AudioPanel.kt:102-119.

**Files:** app/src/main/java/com/squish/app/editor/AudioPanel.kt

## ux · low · S — The standalone extract tool is called 'Rip' and lives outside the editor

**Detail:** A user looking for 'extract audio' (CapCut's and every gallery app's wording) will not find 'Rip' among the quick tools, and the editor's Sound tab never points to it.

**CapCut:** 'Extract audio' in the clip menu and 'Extracted' in the Audio picker.

**Evidence:** QuickTool.kt:50-56 (title 'Rip', action 'Rip the sound'); QuickToolScreen.kt:192 (blurb).

**Files:** app/src/main/java/com/squish/app/tools/QuickTool.kt; app/src/main/java/com/squish/app/tools/QuickToolScreen.kt

## bug · high · S — Preview pitch-shifts added music under Chipmunk/Deep; export does not

**Detail:** setSpeed() writes PlaybackParameters(speed, liveVoice.get().pitch) for whichever player it is handed, and syncAudio calls it for every added-sound player. Selecting Chipmunk therefore plays the music bed at 1.6x pitch in the preview. The export applies voiceProcessors only to base video items (editedClip) and builds the music item with buildAudioProcessors(clip, clip.volume) with voice defaulted to None, so the rendered file has normal-pitch music. The preview lies about the finished file.

**CapCut:** Voice changer is a per-clip tool; the preview and export match by construction.

**Evidence:** PreviewEngine.kt:423-431 setSpeed writes PlaybackParameters(safe, liveVoice.get().pitch) for any key; :226-233 setVoice pushes pitch to baseA/baseB and calls appliedSpeed.clear() (:229), which forces setSpeed to re-run for every added-sound player on the next tick; :878 and :889 syncAudio calls setSpeed(clip.id, audioPlayers[clip.id], ...). Music players come from newAudioPlayer (:308-320) with default renderers, so only the pitch-based voices (Chipmunk 1.6f, Deep 0.72f; TextStyle.kt:140-147) leak into music, not Robot/Echo/Radio. VideoProcessor.kt:475 builds the music item with buildAudioProcessors(clip, clip.volume), voice defaulting to VoiceEffect.None (:587), so voiceProcessors returns emptyList (:611-612); only base clips get state.voiceEffect (:354, :397). Fix: in setSpeed take the pitch from a per-key rule (base players only) or pass pitch explicitly, e.g. setSpeed(key, player, wanted, pitch = 1f) from syncAudio.

**Files:** app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/media/VideoProcessor.kt

## bug · high · S — Dragging the Level slider (or an audio clip) re-seeks every sound player on every tick

**Detail:** clip.volume and timelineStartMs are part of editSignature, so every slider tick or drag tick re-runs engine.setTimeline, which unconditionally calls primeAudio(positionMs) -> player.seekTo(...) on every added sound. During playback that is a seek per frame of gesture: the decoder flushes and re-buffers each time, which is exactly the feedback loop ARCHITECTURE.md describes as 'audio breaking up'. Volume is already pushed live in syncAudio (player.volume = clip.volume every tick), so it need not be in the signature at all; a move only needs a re-prime when the clip covering the playhead actually changed.

**CapCut:** Volume slider adjusts live with no audible interruption.

**Evidence:** Slider: AudioPanel.kt:186 (LabeledSlider "Level" -> viewModel.setAudioClipVolume), EditorPanels.kt:352-354 (lambda is Slider.onValueChange, per pointer move), EditorViewModel.kt:442-443 and 423-428 (updateAudioClip commits to _state immediately). Drag: TimelineEditor.kt:1096-1103 (latestMove per drag delta >= 1 ms) -> EditorViewModel.kt:1915 moveClip -> mutateTimeline. Both change state.audioClips, passed at EditorScreen.kt:263. TimelinePreview.kt:148 (':${it.volume}' and '@${it.timelineStartMs}' in editSignature) and 160-165 (LaunchedEffect(editSignature) -> engine.setTimeline). PreviewEngine.kt:396-399 (setTimeline -> reconcileAudioPlayers + primeAudio(positionMs), unconditional), 612-618 (primeAudio seeks every audio player with no already-parked/playing check), 888 (syncAudio already applies player.volume = clip.volume every 33 ms tick; TICK at TimelinePreview.kt:403). Context: ARCHITECTURE.md:89-93 and PreviewEngine.kt:893-897 / AUDIO_RESYNC_MS=400 at :1016 explain why seeking a playing audio player is the "breaking up" loop. Nuance: ExoPlayer drops a seek to the same current ms while READY/BUFFERING (relied on at PreviewEngine.kt:591-599), so the defect is effectively playback-only: while paused the re-prime is a no-op; while playing, engine positionMs (wall clock) and player.currentPosition differ by some ms, so each tick's seek is real and flushes the decoder. Fix touches TimelinePreview.kt (drop volume from signature; treat a pure timelineStartMs change of the clip under the playhead separately) and PreviewEngine.kt (make primeAudio skip players that are playing and already within AUDIO_RESYNC_MS, or only re-prime when the covering clip changed).

**Files:** app/src/main/java/com/squish/app/editor/TimelinePreview.kt; app/src/main/java/com/squish/app/editor/PreviewEngine.kt

## bug · high · M — Export pads a music cue with a slice of the source video's audio, so a cue placed past the source's length starts early

**Detail:** To start a sound at timelineStartMs the export prepends a zero-gain slice of state.sourceUri from headSourceIn to headSourceIn+padMs. If the source file is shorter than that (an 8 s clip followed by other clips or a still, music placed at 20 s), the clipping window is clamped at the file's end, the pad is shorter than asked, and the music starts early by the difference. Two further inconsistencies: headSourceIn is read from track.first() while the slice is cut from state.sourceUri, which may be a different file once clips are reordered or the original deleted; and when the source has no audio track the pad is skipped entirely and the cue starts at zero (the panel only warns when that track is selected). CompositionFactory already uses EditedMediaItemSequence.Builder.addGap for video; an audio-only sequence with a leading gap plus experimentalSetForceAudioTrack removes the hack and both failure modes.

**CapCut:** Audio clips sit at any position; export honours it exactly.

**Evidence:** VideoProcessor.kt:138 reads `track.firstOrNull()?.sourceInMs ?: state.trimStartMs` (list order, not sorted by timelineStartMs, so it need not even be the clip that plays first). VideoProcessor.kt:430-431 (padMs zeroed when !sourceHasAudio), :433-438 (slice bounded only by timelineDuration and the cue's own source length, never by the pad source's length), :442-458 (pad item = state.sourceUri clipped headSourceIn..headSourceIn+padMs, video removed, zero-gain processors from silentProcessors at :490-493). EditorModels.kt:393-395 (trimmedDurationMs = max timelineEndMs, so the timeline grows past the source when clips are appended); EditorViewModel.kt:159 (sourceUri fixed at load), :1143-1178 (addVideoClips/addBlankClip append videos, photo stills and blanks), :1915 and :1935-1944 (moveClip/withClipRemoved can change which clip is first without touching sourceUri). CompositionFactory.kt:83,87,98 (addGap already used for video rolls and overlays); VideoProcessor.kt:301-302 (needsForcedAudio is already true whenever audioClips is non-empty, so a leading gap on an audio-only sequence is permitted). AudioPanel.kt:237-243 (warning shown only while that cue is selected). Media3 1.5.1 per gradle/libs.versions.toml:14; ClippingMediaSource clamps an end position beyond the period duration, which is what shortens the pad. Scenario: 8 s source, append a still or second video so the timeline reaches 30 s, place music at 20 s; export plays it at ~8 s.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt; app/src/main/java/com/squish/app/media/CompositionFactory.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## bug · medium · S — A speed-ramped audio clip is clipped to its played length, not its source span, on export

**Detail:** buildAudioSequence sets the MediaItem clipping end to sourceInMs + sliceMs where sliceMs starts from clip.durationMs (played length after the ramp), then applies SpeedChangingAudioProcessor on top. A 60 s span at 2x has durationMs 30 s; the clip window becomes 30 s of source, sped 2x, giving 15 s of output instead of 30 s. The video path (editedClip) correctly uses sourceInMs..sourceOutMs. The room-after-pad limit should be converted through the ramp rather than applied in source time.

**CapCut:** Audio speed change keeps the clip's full content at the new rate.

**Evidence:** app/src/main/java/com/squish/app/media/VideoProcessor.kt:434 (sliceMs = clip.durationMs.coerceAtMost(roomAfterPad) - durationMs is played time, see timeline/TimelineModels.kt:105 and timeline/SpeedRamp.kt:121-127), :436 (source-time cap against sourceDurationMs - sourceInMs, ignoring sourceOutMs), :465 (setEndPositionMs(clip.sourceInMs + sliceMs) treats the played length as source time), :475 and :587-593 (SpeedChangingAudioProcessor built from speedRamp.segments(clip.sourceSpanMs) applied on top). Compare :317-321 (editedClip clips sourceInMs..sourceOutMs). Reachability: editor/EditorViewModel.kt:728-730 speedTargetClip includes audioClips, so a selected song can be retimed from SpeedPanel. Scenario: add a 60 s song at timeline 0 on a 60 s video, select it, Speed 2x (timeline shows 30 s), export: song ends at 15 s. Secondary consequence: at 0.5x a trimmed song plays past its trim-out point because the cap is sourceDurationMs, not sourceOutMs.

**Files:** app/src/main/java/com/squish/app/media/VideoProcessor.kt

## bug · medium · S — AudioMixing registers 1- and 2-channel matrices only, so 5.1 sources fail to export at any level below 100%

**Detail:** ChannelMixingAudioProcessor throws UnhandledAudioFormatException for an input channel count with no matrix. gain() only puts matrices for 1 and 2 channels, so a 6-channel AAC/AC-3/E-AC-3 film rip (a use case CLAUDE.md and the VIEW intent filter explicitly target) fails the moment Camera level is below 1.0, muteOriginal is used with an added track, or a music cue needs the zero-gain pad (silentProcessors). The error surfaces as a generic export failure.

**CapCut:** Volume works on any source it can decode.

**Evidence:** 1. Cleanest repro is the single-source case: open a file with 6-channel AAC, drag Camera level below 100%, export -> ExportException ERROR_CODE_AUDIO_PROCESSING_FAILED ("No mixing matrix for input channel count"), shown as AudioProcessingFailed. Path: VideoProcessor.kt:354/:397 -> AudioMixing.kt:23-27 -> media3 ChannelMixingAudioProcessor.onConfigure.

2. The muteOriginal claim is imprecise: muteOriginal sets setRemoveAudio(true) on the video items (VideoProcessor.kt:349, :392), so the 5.1 track never enters the pipeline there. It only bites via the pad: buildAudioSequence (VideoProcessor.kt:442-457) borrows a slice of state.sourceUri's own 5.1 audio at gain(0f) whenever the added track starts after 0 ms and state.sourceHasAudio is true. That pad is the trigger in the "add music at 5 s" scenario.

3. The proposed fix ("register matrices for 1..8") is insufficient for the headline scenario. With a 5.1 source registered first, AudioGraph's mixer format becomes 6-channel; the stereo music input then goes through AudioGraphInput.configureProcessing, which only adds Media3's own channel-count changer when the required output is 1 or 2 channels, and DefaultAudioMixer.addSource (media3-transformer 1.5.1 line 191) calls ChannelMixingMatrix.create(2, 6), which throws UnsupportedOperationException ("Default channel mixing coefficients for 2->6 are not yet implemented") - Media3 1.5.1 cannot mix stereo into a 6-channel bed at all. Registering 1..8 identity matrices fixes only the single-source level case and the pad. A complete fix is for Squish to downmix any >2-channel input to stereo itself (a custom 6->2 / 8->2 ChannelMixingMatrix with explicit coefficients, applied first in every audio processor list, including at 100% volume when other sequences exist), so every AudioGraph input is 1 or 2 channels before Media3's mixer sees it. Files: app/src/main/java/com/squish/app/media/AudioMixing.kt, app/src/main/java/com/squish/app/media/VideoProcessor.kt (buildAudioProcessors, gainOnly, silentProcessors).

4. Codec caveat: the app ships no software AC-3/E-AC-3 decoder (no ffmpeg extension), so on devices without a hardware Dolby decoder those rips fail earlier at decode (ERROR_CODE_DECODING_FORMAT_UNSUPPORTED). The reliable reproduction is 6-channel AAC, or AC-3/E-AC-3 on a device that decodes it.

**Files:** app/src/main/java/com/squish/app/media/AudioMixing.kt; app/src/main/java/com/squish/app/media/VideoProcessor.kt

## bug · medium · S — Auto-sync ignores the head video clip's own trim and position

**Detail:** The analyzer measures the offset between the two files' own clocks. runAutoSync then writes sourceInMs = max(offset,0), timelineStartMs = max(-offset,0), which is only right if the video clip sits at timeline 0 with sourceIn 0. With the head clip trimmed by S ms or placed at P ms, the audio lands (S - P) ms out. The Align card's readout (sourceInMs - timelineStartMs) has the same blind spot, and reads a plain content trim as a sync offset.

**CapCut:** CapCut has no auto-sync; this is a Squish advantage that should be right.

**Evidence:** EditorViewModel.kt:474-506 runAutoSync applies AudioSyncAnalyzer's file-vs-file offset as sourceInMs=max(offset,0)/timelineStartMs=max(-offset,0) without adding the head video clip's own (sourceInMs - timelineStartMs). Audio is placed in timeline time by PreviewEngine.kt:868-880 and VideoProcessor.kt:430-471, so the placement is only right when the head clip's delta is 0. AudioPanel.kt:253 readout (sourceInMs - timelineStartMs) shares the blind spot. Corrected scenario: the reviewer's "trim 5 s off the head" does NOT fail, because withClipTrimmed (TimelineModels.kt:295-320) moves timelineStartMs with the trim, leaving delta 0. Failing scenarios: (1) trim 5 s off the head, then tap "Close gaps" (rippleVideo, TimelineModels.kt:187-204 sets timelineStartMs=0 while sourceIn stays 5000), add the external recording, Auto-sync -> Matched, audio plays 5 s late in preview and export, readout shows the file offset rather than the true alignment; (2) drag the head clip right by P ms (withClipMoved, TimelineModels.kt:288-292) then Auto-sync -> audio P ms early; (3) a speed ramp on the head clip -> playedShift != source shift, same class of error. Also: runAutoSync analyses state.sourceUri (the first-loaded file) rather than videoClips.first().uri, so with several videos it can sync against a file that is not the head clip. Fix: in runAutoSync compute head = videoClips.filter{!isOverlay}.minBy{timelineStartMs}, use its uri, and place with delta = offset + (head.sourceInMs - head.timelineStartMs) (also ramp-aware); make the AudioPanel readout subtract the same head delta. Files: EditorViewModel.kt, AudioPanel.kt.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/editor/AudioPanel.kt

## bug · medium · S — None of the audio-panel edits are undoable

**Detail:** setAudioTrim, placeAudioAtPlayhead, setAudioClipVolume, nudgeAudioOffset, nudgeAudioOffsetFrames, resetAudioAlignment and the auto-sync result all go through updateAudioClip without record(), so Undo after any of them reverses whatever came before (e.g. 'Undo: Add song' removes the track instead of restoring its level). Contradicts the guarantee stated at addAudioTrack ('one undo away'). scaleBeats/nudgeDownbeat/clearBeats are likewise unrecorded.

**CapCut:** Every audio adjustment is an undo step.

**Evidence:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt:423-428 `updateAudioClip` does `_state.update` + `recomputeEstimate()` with no `record()`. Callers with no record of their own: setAudioTrim :431-435, placeAudioAtPlayhead :437-440, setAudioClipVolume :442-443, nudgeAudioOffset :450-460, nudgeAudioOffsetFrames :462-463, resetAudioAlignment :465-468, runAutoSync success branch :493-500. Compare recorded siblings: addAudioTrack :376-378 (comment promises "one undo away"), removeAudioClip :411, setOriginalVolume :470. Undo mechanics: record() :1968-1972 stores the pre-change EditSnapshot; undo() :1994-1999 pops it and calls `restoring()`; EditSnapshot carries `audioClips` (EditorModels.kt:239-258; restoring :498-500), so popping the "Add <name>" entry drops the track. UI call sites with no wrapper: AudioPanel.kt:186 (Level slider), :199-206 (trim nudges), :234 (place at playhead), :270-273 (offset nudges), :279 (auto-sync), :284 (reset). Beats: scaleBeats :662-674, nudgeDownbeat :677-679, clearBeats :681 are plain `_state.update` (BeatPanel.kt:61,105,110,115), whereas markBeats :687 is recorded. Correction to the claim: `beats` is not in EditSnapshot (EditorModels.kt:239-258), so making the beats functions undoable requires adding `beats` to EditSnapshot, `editSnapshot` (:475-497) and `restoring()` (:498+) in EditorModels.kt, not just wrapping them in record(). Files a fix touches: EditorViewModel.kt (wrap the seven audio mutators; for the slider use a stable label such as "Level" so UndoStack coalescing groups a drag), EditorModels.kt (beats in snapshot).

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · low · S — Beat grid is lost on process death; markers survive only if 'Snap to the beat' was pressed

**Detail:** ProjectAutosave encodes markers but has no key for BeatProgress (bpm, beatsMs, downbeatOffset, clipLabel). After recovery the Beat card is back to 'Find the beat', the ruler's bar markers (barMarkers = beats.every(4)) disappear, and the 30 s+ analysis has to be rerun.

**CapCut:** Beats belong to the audio clip and persist with the project.

**Evidence:** ProjectAutosave.kt:246 encodes "markers"; encode() at :225-260 and decode() at :406-440 have no key for state.beats, and ProjectSnapshot (:620-645) has no beats field (grep for beats|BeatProgress|bpm|downbeat in the file returns nothing). EditorViewModel.kt:2230-2261 rebuilds state from the snapshot with markers = snapshot.markers and nothing for beats, so beats defaults to BeatProgress() (EditorModels.kt:176-185, :326). BeatPanel.kt:49-51 then shows "Find the pulse and cut to it" with the "Find the beat" button (:152), and EditorScreen.kt:339 passes barMarkers = state.beats.every(4) = empty. Markers survive only if markBeats() (EditorViewModel.kt:686-693, the "Snap to the beat" section at BeatPanel.kt:128) was used. Also: ProjectSnapshot.isTrivial (ProjectAutosave.kt:652-668) ignores beats, so a beats-only project is not even offered for recovery. Drop the "30 s+" figure; the analysis duration is not established in code.

**Files:** app/src/main/java/com/squish/app/data/ProjectAutosave.kt; app/src/main/java/com/squish/app/editor/EditorModels.kt

## bug · medium · S — 'Cut on the beat' razors the music bed itself, and later beat detection then sees only the first fragment

**Detail:** cutOnBeats runs withSplitAtPlayhead, which splits every clip spanning the cut regardless of kind, so the song is cut into as many pieces as the video. The BeatPanel copy promises 'Razors the whole video track'. On the next detectBeats the target is the first audio fragment and toProgress filters beats to that fragment's source window, so the grid covers only the first bar or two.

**CapCut:** Match-cut / beat cut affects the selected video clip only; the audio clip stays whole.

**Evidence:** EditorViewModel.kt:2008-2019 (mutateTimeline builds TimelineState with clips = videoClips + audioClips and writes both kinds back); TimelineModels.kt:328 (victims = clips.filter { it.spans(cut) && it.sourceSpanMs > MIN_CLIP_MS * 2 }, no kind filter; KDoc 323-324 says it cuts every track); EditorViewModel.kt:702-716 (cutOnBeats loops withSplitAtPlayhead per beat, then selectedClipId = null); EditorViewModel.kt:603-606 (detectBeats falls back to audioClips.firstOrNull(), the first fragment after cutting); EditorViewModel.kt:646-650 (toProgress drops beats outside the fragment's sourceInMs..sourceOutMs); BeatPanel.kt:137-138 (copy promises only the video track is razored). Scenario: song + 3 shots, Cut on the beat every bar: the song is split at every bar into one fragment per bar; a second 'Find the beat' analyses the first fragment and keeps only beats inside its source window, so the grid covers a single bar.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/timeline/TimelineModels.kt; app/src/main/java/com/squish/app/editor/BeatPanel.kt

## bug · low · S — markBeats wipes hand-placed markers

**Detail:** 'Snap to the beat' replaces the whole marker list with the beat list rather than merging, so any marker the editor placed by hand (addMarkerAtPlayhead) is silently removed.

**CapCut:** Beat markers and manual markers are separate; neither erases the other.

**Evidence:** EditorViewModel.kt:687-693 — `markBeats` builds `beats` from `current.beats.every(everyN)` only and assigns `markers = beats.sorted().distinct()`, never unioning with `current.markers`. Contrast `addMarkerAtPlayhead` at :341-347, which does `current.markers + position`. Mitigations: wrapped in `record("Mark beats")` (undoable, see `record` at :1968-1972), and no-op if the beat list is empty (:690). The BeatPanel.kt:126-132 copy says "Drops a marker on every beat", giving no hint that existing markers are replaced. Severity: medium (data loss of user work, but one undo away). Effort: S — change line 691 to `markers = (current.markers + beats).sorted().distinct()`.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · S — Adding an unreadable audio file creates a zero-length clip with no error

**Detail:** addAudioTrack takes probeDurationMs (0 on failure) and builds a Clip with sourceOutMs = 0, records 'Add <name>', selects it and starts a waveform decode. Nothing on screen explains the invisible clip; setAudioTrim's limit logic then uses endMs as its own limit. The added file is never passed to checkDecodable either, so an audio codec the phone cannot decode is only discovered when the export fails with a generic code.

**CapCut:** Unsupported files are refused at import with a message.

**Evidence:** app/src/main/java/com/squish/app/media/ThumbnailExtractor.kt:84-94 (probeDurationMs returns 0L on failure). app/src/main/java/com/squish/app/editor/EditorViewModel.kt:371-396 (trackDuration 0 -> out = 0 -> Clip with sourceOutMs = 0 and sourceDurationMs = 0, recorded as "Add <name>" and selected; no failure set, no checkDecodable), compare :202-206 (openVideo sets SquishError.FileUnreadable when durationMs <= 0 and calls checkDecodable). :431-435 (setAudioTrim: limit = endMs when sourceDurationMs == 0). :1236-1244 checkDecodable, called only at :206, :1227, :1269, :2208 - never for audio adds. app/src/main/java/com/squish/app/media/SquishError.kt:156-166 (preflight's `sources` = videoClips + sourceUri; audioClips' URIs never checked against MediaCompat). app/src/main/java/com/squish/app/editor/EditorScreen.kt:419 (picker accepts audio/* and video/*). app/src/main/java/com/squish/app/timeline/TimelineEditor.kt:1021-1025 with TimelineWindow.kt:109-116 (zero-length clip draws at 0dp width). Correction: the unreadable zero-length clip does NOT fail the export - app/src/main/java/com/squish/app/media/VideoProcessor.kt:429-436 computes sliceMs = 0 and returns null, silently dropping it; the export-time failure only applies to a readable file with an undecodable audio codec, which is mapped by SquishError.from (BAND_DECODING -> UnsupportedCodec, BAND_AUDIO -> AudioProcessingFailed) rather than caught by preflight. Two scenarios: (1) pick a file MediaMetadataRetriever rejects -> "Add <name>" in undo, nothing visible on the strip, clip silently omitted from export; (2) pick e.g. an MKA with DTS audio -> clip added and drawn normally, no warning, export fails.

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/media/SquishError.kt

## bug · medium · S — A video file added as an audio track decodes its video frames in the preview

**Detail:** The Sound picker accepts video/*, and newAudioPlayer never disables the video track type. ExoPlayer decodes video into a placeholder surface when no surface is attached, so a 4K clip used purely for its sound costs a hardware decoder and battery while playing, and competes with the two base players and overlays for codec instances (the app already documents EncoderUnavailable-style contention).

**CapCut:** 'Extracted' audio is a real audio-only asset; no video decoding.

**Evidence:** EditorScreen.kt:145-152 (OpenDocument result -> viewModel.addAudioTrack(uri)) and :419 (arrayOf("audio/*", "video/*")); EditorViewModel.kt:371-396 (addAudioTrack stores the raw URI, no audio extraction); PreviewEngine.kt:308-325 (newAudioPlayer: default RenderersFactory and DefaultTrackSelector, no trackSelectionParameters / setTrackTypeDisabled) and :918-931 (reconcileAudioPlayers: setMediaItem(MediaItem.fromUri(uri)) + prepare() on the full container, no surface ever attached). Media3 1.5.1 (gradle/libs.versions.toml:14): MediaCodecVideoRenderer decodes into a PlaceholderSurface when no surface is set, so the video track of a video-as-sound clip occupies a hardware decoder from the moment the track is added. Note: SquishError.EncoderUnavailable (media/SquishError.kt:57,216,222) documents export-time encoder failures, not preview decoder contention; cite it only as evidence that MediaCodec resource pressure already bites this app. Fix: in newAudioPlayer set trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build(), or supply a RenderersFactory with audio renderers only.

**Files:** app/src/main/java/com/squish/app/editor/PreviewEngine.kt; app/src/main/java/com/squish/app/editor/EditorScreen.kt

## bug · low · S — videoWaveform is decoded on every open and recovery but never drawn

**Detail:** load() and acceptRecovery() run PcmDecoder.decodeMono over the first 60 s of the source (default maxDurationMs) and build a Waveform into state.videoWaveform, but no composable reads it (TimelineEditor draws waveforms only for ClipKind.Audio). It is a second full audio decode per open for nothing; either draw it on the base clip (useful for cutting to speech) or drop it and keep only the hasAudio confirmation.

**CapCut:** Main-track clips show thumbnails; audio waveforms live on audio clips only.

**Evidence:** EditorViewModel.kt:214-224 (load) and :2197-2207 (acceptRecovery): PcmDecoder.decodeMono(getApplication(), uri) with default maxDurationMs = 60_000L (PcmDecoder.kt:26-30), result used for sourceHasAudio = it.sourceHasAudio || pcm != null and videoWaveform = pcm?.let { WaveformBuilder.build(it) }. EditorModels.kt:348: val videoWaveform: Waveform? = null. Repo-wide grep for "videoWaveform": only those three sites; no reader. TimelineEditor.kt:1113: waveform drawn only when clip.kind == ClipKind.Audio. Correction: this is the only PCM decode of the source at open - checkDecodable() uses MediaCompat.check (MediaCompat.kt:51-78), which is a MetadataRetriever probe, not a decode - so it is not a "second" decode, and it is capped at 60 s, not full-length. The decode itself still serves the sourceHasAudio confirmation; only the WaveformBuilder.build call and the retained videoWaveform field are dead. Fix: either draw it on ClipKind.Video base clips in TimelineEditor (reusing the Audio branch), or drop WaveformBuilder.build and the field and keep decodeMono solely for hasAudio (or replace it with the cheaper container answer already obtained by probe).

**Files:** app/src/main/java/com/squish/app/editor/EditorViewModel.kt; app/src/main/java/com/squish/app/editor/EditorModels.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt

## bug · low · M — Waveform resolution is fixed at 480 buckets per file, so zoomed-in views are blocky and >10-minute files repeat the last bucket

**Detail:** WaveformBuilder.build always produces 480 peaks regardless of duration (a 4-minute song = 500 ms per bucket, a 10-minute decode = 1.25 s), while the strip samples one bar every 3 dp at any zoom, so at beat-cutting zoom levels the waveform is a staircase that cannot show where a transient is. For a file longer than the 10-minute decode cap the index computation runs past lastIndex and is coerced, painting the final bucket across the remainder.

**CapCut:** Waveform is drawn at a fixed time-per-pixel resolution and re-rendered as you zoom.

**Evidence:** Waveform.kt:11 (buckets = 480 default; all timeline callers use the default: EditorViewModel.kt:222, 404, 2205, 2218). TimelineEditor.kt:1117-1123 (bars = width / 3dp; index = atMs / waveform.durationMs * peaks.size; coerceIn(0, lastIndex)). TimelineModels.kt:179 (ZOOM_MAX = 2000 px/s, so sub-second-per-screen zoom is reachable). EditorViewModel.kt:402 and 2218 (maxDurationMs = 10 * 60_000L). PcmDecoder.kt:14 (MonoPcm.durationMs = samples.size * 1000 / sampleRate, i.e. the decoded portion, not the file length) and PcmDecoder.kt:58,96 (sample cap from maxDurationMs) - this is why Waveform.durationMs is 10 min for a longer file and every position past 10 min clamps to the final bucket. Scenario: 4-minute song at 200 px/s (2 s across a ~400 dp screen): ~130 bars but only 4 distinct peak values. 15-minute podcast trimmed to full length: minutes 10-15 draw as a flat repeat of bucket 479.

**Files:** app/src/main/java/com/squish/app/media/audio/Waveform.kt; app/src/main/java/com/squish/app/timeline/TimelineEditor.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt

## bug · medium · S — Music preview player plays on top of the timeline, and keeps playing in the background

**Detail:** MusicPanel's own ExoPlayer starts on tap without pausing the preview engine or asking for audio focus, so if the timeline is playing you hear both. It is only released when the panel leaves composition, so switching apps mid-listen keeps the song playing. Switching chips (Originals <-> On this phone) leaves a track playing with no row showing it.

**CapCut:** Tapping a song in the library previews it solo; the editor is paused and the preview stops when you leave.

**Evidence:** MusicPanel.kt:69-70 (private ExoPlayer, released only on dispose), :74-84 (listen() plays without touching the transport), :94-95 (chip onClick only sets tab; playingKey kept, player not paused), :108 vs :219 (row keys differ per tab so the playing row disappears after a switch). EditorScreen.kt:261-314 and :324-326 (TimelinePreview stays composed while the Sound panel is open). EditorScreen.kt:286 + TimelinePreview.kt:185 (setPlaying IS called, but only as an engine->state mirror; nothing feeds state.isPlaying back into the engine). PreviewEngine.kt:555-564 (the only real pause; engine is owned inside TimelinePreview, TimelinePreview.kt:209/:277, unreachable from MusicPanel/ViewModel). No AudioFocus/setAudioAttributes anywhere under app/src/main/java; PreviewEngine.kt:250-262 and :308-325 build players without audio attributes. No LifecycleEventEffect/onPause/onStop anywhere; MainActivity.kt:14-29 has only onCreate. Scenario: tap picture to play (TimelinePreview.kt:209), open Sound, tap a song: both play; press Home: song keeps playing; switch chip: song keeps playing, no row highlighted.

**Files:** app/src/main/java/com/squish/app/editor/MusicPanel.kt; app/src/main/java/com/squish/app/editor/EditorViewModel.kt
