package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.media.MediaCompat
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.audio.AudioSyncAnalyzer
import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.BeatMap
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.VoiceRecorder
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.withSplitAllTracks
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.squish.app.editor.*

/**
 * Sound: added tracks and takes, the camera's own audio, each clip's level,
 * fades and voice, auto-sync, and the beats.
 */
internal class AudioEdits(host: EditHost) : EditArea(host) {

    private var syncJob: Job? = null

    // ---- Sound ----------------------------------------------------------------

    /**
     * Adds a sound to the timeline at the playhead. There is no limit: music, a
     * voiceover and a second mic can all sit on the strip at once, overlapping
     * freely, because each one is an ordinary clip rather than a special case.
     */
    fun addAudioTrack(uri: Uri, label: String? = null) {
        viewModelScope.launch {
            val trackDuration = ThumbnailExtractor.probeDurationMs(app, uri)
            // A file with no readable length is refused here, with a reason. It
            // used to become a clip zero milliseconds long: recorded as "Add",
            // selected, invisible on the strip, silently dropped by the export,
            // and one the trim buttons could crash on.
            if (trackDuration <= 0L) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            val name = label ?: displayNameOf(uri) ?: "Audio"

            // Recorded like every other edit, so a track added by mistake is one
            // undo away rather than a select-and-delete.
            record("Add $name") { _state.update { current ->
                // At the playhead, ending with the edit - or backed up to end
                // with it when the playhead is parked on the last second; see
                // EditRules.soundLanding for both. The playhead stays where it
                // is, as it does for every other add: a sound backed up to the
                // end ends under the playhead, on the row the strip shows under
                // the sheet, so it is in sight without moving anything - and an
                // undo of the add leaves the view where it was.
                val landing = EditRules.soundLanding(current.playheadMs, current.trimmedDurationMs, trackDuration, LAST_MOMENT_MS)
                val clip = Clip(
                    kind = ClipKind.Audio,
                    uri = uri,
                    label = name,
                    sourceInMs = 0,
                    sourceOutMs = landing.sourceOutMs,
                    timelineStartMs = landing.timelineStartMs,
                    sourceDurationMs = trackDuration
                )
                current.copy(audioClips = current.audioClips + clip, selectedClipId = clip.id)
            } }
            recomputeEstimate()
            // A readable file can still carry a codec this phone cannot decode; say
            // so now rather than when the export fails on it.
            checkDecodable(uri)
            ensureWaveform(uri)
        }
    }

    /**
     * The waveform of a file, decoded once and cached against the file, not the
     * clip, so splitting a track in two costs nothing and both halves draw
     * immediately. The whole file, as peaks: a long song used to stop drawing
     * at its tenth minute.
     */
    fun ensureWaveform(uri: Uri) {
        if (_state.value.audioWaveforms[uri.toString()] != null) return
        viewModelScope.launch {
            val wave = PcmDecoder.decodePeaks(app, uri) ?: return@launch
            _state.update { it.copy(audioWaveforms = it.audioWaveforms + (uri.toString() to wave)) }
        }
    }

    fun removeAudioClip(clipId: String) = record("Remove sound") {
        _state.update { current ->
            current.copy(
                audioClips = current.audioClips.filterNot { it.id == clipId },
                selectedClipId = if (current.selectedClipId == clipId) null else current.selectedClipId,
                syncStatus = SyncStatus.Idle,
                syncConfidence = 0f
            )
        }
        recomputeEstimate()
    }

    /** Changes one added sound. Not recorded itself: every caller is its own undo step. */
    private fun updateAudioClip(clipId: String, block: (Clip) -> Clip) {
        _state.update { current ->
            current.copy(audioClips = current.audioClips.map { if (it.id == clipId) block(it) else it })
        }
        recomputeEstimate()
    }

    /** Changes one clip of either kind. Not recorded itself, like [updateAudioClip]. */
    private fun updateAnyClip(clipId: String, block: (Clip) -> Clip) {
        _state.update { current ->
            current.copy(
                videoClips = current.videoClips.map { if (it.id == clipId) block(it) else it },
                audioClips = current.audioClips.map { if (it.id == clipId) block(it) else it }
            )
        }
        recomputeEstimate()
    }

    /**
     * Which slice of the audio file plays, in source time.
     *
     * Bounded so it cannot throw: with a file shorter than the minimum clip, or
     * one whose length was never read, the old bounds crossed and `coerceIn`
     * threw on a tap of the trim buttons.
     */
    fun setAudioTrim(clipId: String, startMs: Long, endMs: Long) = record("Trim sound") {
        updateAudioClip(clipId) { clip ->
            val limit = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else maxOf(endMs, clip.sourceOutMs)
            if (limit < MIN_CLIP_MS) return@updateAudioClip clip
            val start = startMs.coerceIn(0L, limit - MIN_CLIP_MS)
            clip.copy(sourceInMs = start, sourceOutMs = endMs.coerceIn(start + MIN_CLIP_MS, limit))
        }
    }

    fun placeAudioAtPlayhead(clipId: String) = record("Move sound") {
        val playhead = _state.value.playheadMs
        updateAudioClip(clipId) { it.copy(timelineStartMs = playhead) }
    }

    /** A sound's level, up to four times its own (AudioRules.MAX_SOUND_GAIN). */
    fun setAudioClipVolume(clipId: String, volume: Float) = record("Level", gesture = "Level $clipId") {
        updateAudioClip(clipId) { it.copy(volume = volume.coerceIn(0f, AudioRules.MAX_SOUND_GAIN)) }
    }

    /**
     * A sound's fade in, in played milliseconds. The two fades share the clip
     * (AudioRules.fades): the one being set keeps its value and the other
     * gives way.
     */
    fun setFadeIn(clipId: String, ms: Long) = record("Fade in", gesture = "Fade in $clipId") {
        updateAnyClip(clipId) { clip ->
            val (fadeIn, fadeOut) = AudioRules.fades(clip.durationMs, ms, clip.fadeOutMs, changedIn = true)
            clip.copy(fadeInMs = fadeIn, fadeOutMs = fadeOut)
        }
    }

    fun setFadeOut(clipId: String, ms: Long) = record("Fade out", gesture = "Fade out $clipId") {
        updateAnyClip(clipId) { clip ->
            val (fadeIn, fadeOut) = AudioRules.fades(clip.durationMs, clip.fadeInMs, ms, changedIn = false)
            clip.copy(fadeInMs = fadeIn, fadeOutMs = fadeOut)
        }
    }

    /** Fade's Reset: both fades off, as one step. */
    fun clearFades(clipId: String) = record("Fade") {
        updateAnyClip(clipId) { it.copy(fadeInMs = 0L, fadeOutMs = 0L) }
    }

    /**
     * A voice effect on one clip's own sound - a shot's, an overlay's or an
     * added sound's. It was one setting for the whole edit, on the camera
     * sound only.
     */
    fun setClipVoice(clipId: String, effect: VoiceEffect) = record("Voice") {
        updateAnyClip(clipId) { it.copy(voice = effect) }
    }

    /**
     * A shot's sound as a clip of its own on the sound rows, and the shot
     * silenced (AudioRules.extracted). Refused, with a reason, for a picture
     * with no sound to take: a photo, or footage the background check has
     * found silent.
     */
    fun extractAudio(clipId: String) {
        val current = _state.value
        val clip = current.videoClips.firstOrNull { it.id == clipId } ?: return
        val uri = clip.uri ?: current.sourceUri ?: return
        val silent = clip.isStillPicture ||
            MediaCompat.cached(uri)?.hasAudio == false ||
            (uri == current.sourceUri && !current.sourceHasAudio)
        if (silent) {
            _state.update { it.copy(failure = SquishError.NoSoundToExtract(clip.label)) }
            return
        }
        val extraction = AudioRules.extracted(clip.copy(uri = uri), UUID.randomUUID().toString())
        record("Extract audio") {
            _state.update { s ->
                s.copy(
                    videoClips = s.videoClips.map { if (it.id == clipId) extraction.muted else it },
                    audioClips = s.audioClips + extraction.sound,
                    selectedClipId = extraction.sound.id
                )
            }
        }
        recomputeEstimate()
        ensureWaveform(uri)
    }

    /**
     * A sound repeated to the end of the picture (AudioRules.loopToFit), as one
     * step. Nothing happens - and nothing is recorded - when there is no room.
     */
    fun loopToEnd(clipId: String) {
        val current = _state.value
        val clip = current.audioClips.firstOrNull { it.id == clipId } ?: return
        val loop = AudioRules.loopToFit(clip, current.pictureEndMs, MIN_CLIP_MS) { UUID.randomUUID().toString() }
        if (loop.copies.isEmpty()) return
        record("Loop to the end") {
            _state.update { s ->
                s.copy(audioClips = s.audioClips.map { if (it.id == clipId) loop.first else it } + loop.copies)
            }
        }
        recomputeEstimate()
    }

    /**
     * Slides a sound against the picture. Sliding left past the start of the edit
     * is impossible, so the remainder is spent entering the file later instead -
     * which is the same thing to the ear and keeps the nudge from stalling at zero.
     *
     * Each tap is its own step: five nudges and one undo steps back one nudge.
     */
    fun nudgeAudioOffset(clipId: String, deltaMs: Long) = record("Nudge sound") {
        updateAudioClip(clipId) { clip ->
            val proposed = clip.timelineStartMs + deltaMs
            if (proposed >= 0) {
                clip.copy(timelineStartMs = proposed)
            } else {
                clip.copy(
                    timelineStartMs = 0,
                    sourceInMs = (clip.sourceInMs - proposed)
                        .coerceAtMost((clip.sourceOutMs - MIN_CLIP_MS).coerceAtLeast(clip.sourceInMs))
                )
            }
        }
    }

    fun nudgeAudioOffsetFrames(clipId: String, frames: Int) =
        nudgeAudioOffset(clipId, frames * _state.value.frameMs)

    /**
     * The sound back to the top of its file at the top of the timeline - all of
     * it, from its first second.
     *
     * Not "no offset against the head shot's file": with the first shot trimmed
     * five seconds in, that entered the sound five seconds in too, and a music
     * bed lost its opening to a button called Reset. Lining the sound up with
     * the picture is what Auto-sync and the nudges are for; the readout above
     * says truthfully where this leaves it.
     */
    fun resetAudioAlignment(clipId: String) {
        record("Reset alignment") {
            updateAudioClip(clipId) { clip ->
                val placed = EditRules.syncPlacement(0L, 0L, clip.sourceOutMs, clip.sourceDurationMs, MIN_CLIP_MS)
                clip.copy(
                    sourceInMs = placed.sourceInMs,
                    timelineStartMs = placed.timelineStartMs,
                    sourceOutMs = placed.sourceOutMs
                )
            }
        }
        _state.update { it.copy(syncStatus = SyncStatus.Idle, syncConfidence = 0f) }
    }

    fun setOriginalVolume(volume: Float) = record("Camera level", gesture = "Camera level") {
        _state.update { it.copy(originalVolume = volume.coerceIn(0f, 1f)) }
    }

    /** Volume's Reset for a shot: the camera's sound on, at full level, as one step. */
    fun resetCameraSound() = record("Camera audio") {
        _state.update { it.copy(muteOriginal = false, originalVolume = 1f) }
        recomputeEstimate()
    }

    /**
     * Lines a sound up with the picture by listening to both.
     *
     * Against the head shot of the main track - its file, and where that file
     * sits on the timeline. It used to listen to whichever file was opened first
     * and place the sound as if that file started at timeline zero untrimmed, so
     * after a trim and "close gaps", or with the shot dragged along, the "matched"
     * sound played early or late by exactly that much.
     */
    fun runAutoSync(clipId: String) {
        val current = _state.value
        val head = current.headVideoClip
        val videoUri = head?.uri ?: current.sourceUri ?: return
        val clip = current.audioClips.firstOrNull { it.id == clipId } ?: return
        val audioUri = clip.uri ?: return

        syncJob?.cancel()
        _state.update { it.copy(syncStatus = SyncStatus.Analyzing) }

        syncJob = viewModelScope.launch {
            val result = AudioSyncAnalyzer.detectOffset(app, videoUri, audioUri)
            if (result == null || result.confidence < MIN_SYNC_CONFIDENCE) {
                _state.update {
                    it.copy(syncStatus = SyncStatus.NoMatch, syncConfidence = result?.confidence ?: 0f)
                }
            } else {
                // Measured when the answer lands, not when the question was asked:
                // the head shot may have been trimmed while this was listening.
                val headDelta = _state.value.headPictureDeltaMs
                recordLate(
                    "Auto-sync",
                    edit = { snapshot ->
                        snapshot.copy(audioClips = snapshot.audioClips.map { existing ->
                            if (existing.id != clipId) existing else {
                                val placed = EditRules.syncPlacement(
                                    result.offsetMs, headDelta, existing.sourceOutMs, existing.sourceDurationMs, MIN_CLIP_MS
                                )
                                existing.copy(
                                    sourceInMs = placed.sourceInMs,
                                    timelineStartMs = placed.timelineStartMs,
                                    sourceOutMs = placed.sourceOutMs
                                )
                            }
                        })
                    },
                    alongside = { it.copy(syncStatus = SyncStatus.Matched, syncConfidence = result.confidence) }
                )
            }
        }
    }

    fun setMuteOriginal(muted: Boolean) = record("Camera audio") {
        _state.update { it.copy(muteOriginal = muted) }
        recomputeEstimate()
    }

    // ---- The transport ----------------------------------------------------------

    /**
     * Asks the preview to pause - a song about to be auditioned must not play
     * over the edit - or to play. The preview answers each request once; see
     * TransportRequest.
     */
    fun requestPause() = requestTransport(play = false)
    fun requestPlay() = requestTransport(play = true)

    private fun requestTransport(play: Boolean) = _state.update {
        it.copy(transportRequest = TransportRequest(play, (it.transportRequest?.nonce ?: 0L) + 1))
    }

    // ---- Voiceover ----------------------------------------------------------------

    private val recorder = VoiceRecorder()
    private var recordJob: Job? = null
    private val stopRequested = AtomicBoolean(false)

    /**
     * Records a take over the timeline: a three-second count-in, then the
     * picture plays - silently, so the speaker is not in the take - while the
     * mic listens, until [stopVoiceover], the end of the edit, or the app being
     * put away. The take lands as a sound clip at the moment it started, as
     * CapCut's does. With a take selected, the new one replaces it.
     *
     * Started at the playhead; from the last second of the edit there is
     * nothing to record over, so it starts from the top, which is what
     * pressing play there does too.
     */
    fun startVoiceover() {
        val current = _state.value
        if (current.recording.active) return
        val replaces = current.audioClips.firstOrNull { it.id == current.selectedClipId && it.isVoiceover }
        var startMs = (replaces?.timelineStartMs ?: current.playheadMs).coerceAtLeast(0L)
        if (startMs >= current.trimmedDurationMs - MIN_CLIP_MS) startMs = 0L
        requestPause()
        stopRequested.set(false)
        _state.update {
            it.copy(
                playheadMs = startMs,
                scrubNonce = it.scrubNonce + 1,
                selectedClipId = null,
                recording = RecordingState(RecordingState.Phase.Countdown, COUNT_IN, startMs, replacesId = replaces?.id)
            )
        }
        recordJob = viewModelScope.launch {
            for (n in COUNT_IN downTo 1) {
                if (stopRequested.get()) {
                    _state.update { it.copy(recording = RecordingState()) }
                    return@launch
                }
                _state.update { it.copy(recording = it.recording.copy(countdown = n)) }
                delay(1_000)
            }
            val opened = withContext(Dispatchers.IO) { recorder.start(VoiceRecorder.dir(app)) }
            if (!opened) {
                _state.update { it.copy(recording = RecordingState(failed = true)) }
                return@launch
            }
            _state.update { it.copy(recording = it.recording.copy(phase = RecordingState.Phase.Recording, countdown = 0)) }
            requestPlay()

            // Until the picture stops - the end of the edit, Home, a tap on it -
            // or Stop. A transport that never started is given a few seconds
            // and then the take is kept as it is.
            var started = false
            var waited = 0L
            while (isActive && !stopRequested.get()) {
                delay(METER_MS)
                waited += METER_MS
                val playing = _state.value.isPlaying
                if (playing) started = true
                else if (started || waited > START_TIMEOUT_MS) break
                val level = recorder.level
                _state.update { it.copy(recording = it.recording.copy(level = level)) }
            }
            finishTake()
        }
    }

    /** Ends the take; it lands on the strip once the file is closed. */
    fun stopVoiceover() {
        stopRequested.set(true)
    }

    private suspend fun finishTake() {
        requestPause()
        val rec = _state.value.recording
        _state.update { it.copy(recording = it.recording.copy(phase = RecordingState.Phase.Saving, level = 0f)) }
        val take = withContext(Dispatchers.IO) { recorder.stop() }
        recordJob = null
        if (take == null) {
            _state.update { it.copy(recording = RecordingState(failed = true)) }
            return
        }
        landTake(take, rec)
    }

    /** The take onto the sound rows, at the moment it started, as one step. */
    private fun landTake(take: VoiceRecorder.Take, rec: RecordingState) {
        val uri = Uri.fromFile(take.file)
        val clip = Clip(
            kind = ClipKind.Audio,
            uri = uri,
            label = "Voiceover",
            sourceInMs = 0L,
            sourceOutMs = take.durationMs,
            timelineStartMs = rec.startMs,
            sourceDurationMs = take.durationMs
        )
        record("Record voiceover") {
            _state.update { s ->
                s.copy(
                    audioClips = s.audioClips.filterNot { it.id == rec.replacesId } + clip,
                    selectedClipId = clip.id,
                    // Back on the take's first word, ready to hear it.
                    playheadMs = rec.startMs,
                    scrubNonce = s.scrubNonce + 1,
                    recording = RecordingState()
                )
            }
        }
        _state.update { it.copy(recording = RecordingState()) }
        recomputeEstimate()
        ensureWaveform(uri)
    }

    /**
     * The editor is being cleared under a take: the mic is closed and what it
     * heard so far is put on the strip, on this thread, so the save that
     * follows keeps it. Nothing recorded means nothing added.
     */
    fun finishRecordingNow() {
        val rec = _state.value.recording
        if (rec.phase != RecordingState.Phase.Recording) {
            if (rec.active) _state.update { it.copy(recording = RecordingState()) }
            return
        }
        stopRequested.set(true)
        val take = recorder.stop()
        if (take == null) {
            _state.update { it.copy(recording = RecordingState()) }
            return
        }
        landTake(take, rec)
    }

    // ---- Beat detection ---------------------------------------------------------

    private var beatJob: Job? = null

    /**
     * Finds the pulse of whichever sound the edit is built around.
     *
     * Prefers an added track - if someone has dropped music onto the timeline,
     * that is the thing they are cutting to - and falls back to the camera audio,
     * which is right for a performance shot with the music in the room.
     */
    fun detectBeats() {
        val current = _state.value
        if (current.beats.running) return

        val target = current.audioClips.firstOrNull { it.id == current.selectedClipId }
            ?: current.beatClip
            ?: current.audioClips.firstOrNull()
        val uri = target?.uri ?: current.sourceUri ?: return
        val label = target?.label ?: "the camera audio"

        beatJob?.cancel()
        // The grid already found stays until there is a new one. Wiping it at the
        // start meant a failed second listen lost the first answer, with nothing
        // for undo to bring back.
        _state.update { it.copy(beats = it.beats.copy(running = true, failed = false, listeningTo = label)) }

        beatJob = viewModelScope.launch {
            val pcm = PcmDecoder.decodeMono(
                context = app,
                uri = uri,
                targetSampleRate = BEAT_ANALYSIS_RATE,
                maxDurationMs = BEAT_MAX_ANALYSIS_MS
            )
            if (pcm == null) {
                _state.update { it.copy(beats = it.beats.copy(running = false, failed = true, listeningTo = label)) }
                return@launch
            }

            val map = withContext(Dispatchers.Default) {
                BeatDetector.detect(pcm.samples, pcm.sampleRate)
            }
            if (map.isEmpty) {
                _state.update { it.copy(beats = it.beats.copy(running = false, failed = true, listeningTo = label)) }
                return@launch
            }

            // A step of its own: the grid is what "Snap to the beat" and "Cut on
            // the beat" act on, and undo puts the previous one back. The beats
            // go onto the sound in its file's time - onto every clip of that
            // file, so a song already cut in two carries them on both halves -
            // and off every other sound: one grid at a time, as the card says.
            val every = _state.value.beats.every
            val found = BeatProgress(
                finished = true,
                bpm = map.bpm,
                confidence = map.confidence,
                beatsMs = if (target == null) map.beatsMs else emptyList(),
                downbeatOffset = map.downbeatOffset,
                clipLabel = label,
                clipId = target?.id,
                every = every
            )
            recordLate(
                "Find the beat",
                edit = { snapshot ->
                    snapshot.copy(
                        beats = found,
                        audioClips = snapshot.audioClips.map { clip ->
                            if (target != null && clip.uri == target.uri) clip.copy(beats = map.beatsMs)
                            else clip.copy(beats = emptyList())
                        }
                    )
                },
                alongside = { it.copy(beats = found) }
            )
        }
    }

    /**
     * A beat dropped by ear at the playhead, while listening: onto the sound
     * under the playhead, in its file's time, so it travels with the sound;
     * onto the camera-audio grid when no sound is there. Not doubled onto a
     * beat already within a few frames (AudioRules.withBeat).
     */
    fun addBeatAtPlayhead() {
        val current = _state.value
        val at = current.playheadMs
        val covering = current.audioClips.filter { at >= it.timelineStartMs && at < it.timelineEndMs }
        val target = covering.firstOrNull { it.id == current.beats.clipId }
            ?: covering.firstOrNull { it.id == current.selectedClipId }
            ?: covering.firstOrNull { it.beats.isNotEmpty() }
            ?: covering.firstOrNull()
        record("Add beat") {
            if (target != null) {
                val source = target.sourceAt(at)
                updateAudioClip(target.id) { it.copy(beats = AudioRules.withBeat(it.beats, source)) }
                _state.update { s ->
                    if (s.beats.clipId != null) s
                    else s.copy(beats = s.beats.copy(finished = true, clipId = target.id, clipLabel = target.label, beatsMs = emptyList()))
                }
            } else {
                _state.update { s ->
                    s.copy(beats = s.beats.copy(finished = true, beatsMs = AudioRules.withBeat(s.beats.beatsMs, at)))
                }
            }
        }
    }

    /** The density the dots are drawn at and the cuts land on: every beat, every second, or every bar. */
    fun setBeatDensity(every: Int) = record("Beat density") {
        _state.update { it.copy(beats = it.beats.copy(every = every.coerceIn(1, 4))) }
    }

    /** The same pulse counted twice as fast, or half as fast - on the clip, or on the camera grid. */
    fun scaleBeats(faster: Boolean) = record("Beat tempo") {
        _state.update { current ->
            val beats = current.beats
            if (!current.hasBeatGrid) return@update current
            val scaledGrid = BeatMap(beats.beatsMs, beats.bpm, beats.confidence, beats.downbeatOffset).let { if (faster) it.doubled() else it.halved() }
            current.copy(
                beats = beats.copy(
                    beatsMs = scaledGrid.beatsMs,
                    bpm = scaledGrid.bpm,
                    downbeatOffset = scaledGrid.downbeatOffset
                ),
                audioClips = current.audioClips.map { clip ->
                    if (clip.beats.isEmpty()) clip
                    else clip.copy(beats = BeatMap(clip.beats, beats.bpm, beats.confidence, beats.downbeatOffset).let { if (faster) it.doubled() else it.halved() }.beatsMs)
                }
            )
        }
    }

    /** Moves which beat counts as the one on the bar. */
    fun nudgeDownbeat() = record("Shift bar") {
        _state.update { current ->
            current.copy(beats = current.beats.copy(downbeatOffset = (current.beats.downbeatOffset + 1).mod(4)))
        }
    }

    fun clearBeats() = record("Clear beat") {
        beatJob?.cancel()
        _state.update { it.copy(beats = BeatProgress(), audioClips = it.audioClips.map { c -> c.copy(beats = emptyList()) }) }
    }

    /**
     * Drops a marker on every chosen beat, so every edit that already snaps now
     * snaps to the music: dragging a clip, setting an in point, moving a caption.
     *
     * Merged with the markers already there. It used to replace them, so a marker
     * put by hand on the one frame that mattered went the moment the grid was
     * snapped to. Markers sitting on the grid are the ones an earlier snap made,
     * and those are replaced - so "every bar" after "every beat" thins them out.
     */
    fun markBeats() = record("Mark beats") {
        _state.update { current ->
            val chosen = current.beatGrid
            if (chosen.isEmpty()) current
            else current.copy(
                markers = EditRules.mergedBeatMarkers(current.markers, current.allBeats, chosen, current.frameMs),
                snapToMarkers = true
            )
        }
    }

    /**
     * Cuts the main video track on every chosen beat.
     *
     * The picture only. It used to razor every track under each beat - the song
     * included, into one piece per bar, after which finding the beat again heard
     * only the first piece. Overlays and sounds stay whole, which is what cutting
     * a montage to a song means.
     *
     * Applied back to front. Each cut renumbers the clips after it, so working
     * forwards would have every subsequent position measured against a timeline
     * that had already changed underneath it.
     */
    fun cutOnBeats() {
        val current = _state.value
        val cuts = current.beatGrid
            .filter { it > 0 }
            .sortedDescending()
        if (cuts.isEmpty()) return

        // One step for the whole run, not one per cut. Cutting a track on forty
        // beats and then pressing undo forty times is not undo.
        record("Cut on the beat") {
            // The main track only. The song being cut to is on the strip too, and
            // razoring it on every beat left a hundred pieces for the next nudge to
            // break; overlays stay whole for the same reason. The all-tracks split
            // is the one that re-lays the magnetic track and carries keyframes over.
            cuts.forEach { at ->
                mutateTimeline { timeline ->
                    timeline.copy(playheadMs = at).withSplitAllTracks { it.isMain }
                }
            }
            _state.update { it.copy(selectedClipId = null) }
        }
    }

    /**
     * The rate the beat analysis runs at, and how much of a track it will listen to.
     *
     * 8kHz is plenty: everything that marks a beat - kick, snare, hat - has ample
     * energy below 4kHz, and halving the rate halves both the decode and the
     * hundreds of thousands of butterflies the FFT does over a long track.
     *
     * Six minutes covers any song. Beyond that the tempo has almost certainly
     * moved anyway, and the array would be twenty megabytes of floats.
     */
    private val BEAT_ANALYSIS_RATE = 8_000
    private val BEAT_MAX_ANALYSIS_MS = 6 * 60 * 1000L

    private companion object {
        const val MIN_SYNC_CONFIDENCE = 0.28f

        /** With less room than this left, a sound that does not fit it is backed up to end with the edit; see EditRules.soundLanding. */
        const val LAST_MOMENT_MS = 1_000L

        /** The count-in before a take, in seconds: CapCut's three. */
        const val COUNT_IN = 3

        /** How often the mic's level reaches the meter, and the transport is checked. */
        const val METER_MS = 100L

        /** How long a take waits for the picture to start before it is kept as it is. */
        const val START_TIMEOUT_MS = 5_000L
    }
}
