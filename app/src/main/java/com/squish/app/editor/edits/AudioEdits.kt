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
import com.squish.app.timeline.DuckRules
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.timeline.SilenceRules
import com.squish.app.timeline.withSilencesRemoved
import com.squish.app.media.audio.Loudness
import com.squish.app.media.audio.MonoPcm
import com.squish.app.timeline.withShotsFittedToBeats
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.removedOnTimeline
import com.squish.app.timeline.shiftedPast
import com.squish.app.timeline.withOverlayTransitionsFitted
import com.squish.app.editor.fittedTo
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ValueTrack
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.withSplitAllTracks
import com.squish.app.timeline.withValueAt
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
            // Fades that fit the shorter clip; see AudioRules.withFittedFades.
            AudioRules.withFittedFades(clip.copy(sourceInMs = start, sourceOutMs = endMs.coerceIn(start + MIN_CLIP_MS, limit)))
        }
    }

    fun placeAudioAtPlayhead(clipId: String) = record("Move sound") {
        val playhead = _state.value.playheadMs
        updateAudioClip(clipId) { it.copy(timelineStartMs = playhead) }
    }

    /**
     * A sound's level, up to four times its own (AudioRules.MAX_SOUND_GAIN).
     * Through the track's rule (ValueTracks): a key at the playhead once the
     * sound has any, the one level until then.
     */
    fun setAudioClipVolume(clipId: String, volume: Float) = record("Level", gesture = "Level $clipId") {
        val playhead = _state.value.playheadMs
        updateAudioClip(clipId) { it.withValueAt(ValueTrack.Volume, playhead, volume, 0f..AudioRules.MAX_SOUND_GAIN) }
    }

    /** Level's Reset: the sound at its own level, its keys gone, as one step. */
    fun resetAudioClipVolume(clipId: String) = record("Level") {
        updateAudioClip(clipId) { it.copy(volume = 1f, volumeKeys = emptyList()) }
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
     * The voice on every clip of the selected one's kind - every shot and
     * overlay for a shot, every added sound for a sound - as one step: the
     * sheet's "Apply to all" (ROADMAP §2 item 7). The one edit-wide switch
     * this replaced took one tap; per clip it took one tap per shot, and a
     * shot added afterwards spoke normally until it was done too.
     */
    fun setVoiceForAll(clipId: String, effect: VoiceEffect) {
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == clipId } ?: return
        record(if (clip.kind == ClipKind.Audio) "Voice on every sound" else "Voice on every shot") {
            _state.update { s ->
                if (clip.kind == ClipKind.Audio) s.copy(audioClips = s.audioClips.map { it.copy(voice = effect) })
                else s.copy(videoClips = s.videoClips.map { if (it.isStillPicture) it else it.copy(voice = effect) })
            }
            recomputeEstimate()
        }
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
        // At the level the shot was heard at a moment ago - under the camera
        // level for a main-track shot - not its raw slider, which at a camera
        // level of 30% gave a sound three times louder than the shot had been.
        val heard = OverlayRules.effectiveVolume(clip, current.muteOriginal, current.originalVolume)
        val extraction = AudioRules.extracted(clip.copy(uri = uri), UUID.randomUUID().toString(), heardAt = heard)
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
    private val stopRequested = AtomicBoolean(false)

    /**
     * A take the mic has closed but the strip does not have yet. Stopping and
     * landing are two steps on two threads, and the editor can be cleared
     * between them: the coroutine's stop hands the take here, and whichever of
     * the coroutine and [finishRecordingNow] comes next takes it, once. Both
     * halves hold this object's lock, as does the recorder's own stop, so a
     * stop from the clearing editor waits for a stop already under way and
     * then finds the take here rather than nothing.
     */
    private var unlanded: Pair<VoiceRecorder.Take, RecordingState>? = null

    /**
     * Records a take over the timeline: a three-second count-in, then the
     * picture plays - silently, so the speaker is not in the take - while the
     * mic listens, until [stopVoiceover], the end of the edit, or the app being
     * put away. The take lands as a sound clip at the moment it started, as
     * CapCut's does, and is left selected.
     *
     * Always a new take at the playhead: CapCut's Record adds. Doing a take
     * again is a deliberate act - [replaceSelected], from the panel's own
     * "Record this take again" - because the take just made is the selected
     * one, and Record for the next sentence used to replace it silently, at
     * the old take's start, whatever the playhead had been moved to.
     *
     * Started at the playhead; from the last second of the edit there is
     * nothing to record over, so it starts from the top, which is what
     * pressing play there does too. Nothing on the picture at all is refused:
     * with nothing to play over, the transport never started and a take of
     * whatever length the wait allowed landed by itself.
     */
    fun startVoiceover(replaceSelected: Boolean = false) {
        val current = _state.value
        if (current.recording.active || current.pictureEndMs <= 0L) return
        val replaces = if (!replaceSelected) null
        else current.audioClips.firstOrNull { it.id == current.selectedClipId && it.isVoiceover }
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
        viewModelScope.launch {
            // Cancel is read every tick, not once a second: read only at the
            // top of each second, a Cancel on the "1" fell through to the mic
            // opening, the picture starting and a failed take being reported.
            for (n in COUNT_IN downTo 1) {
                _state.update { it.copy(recording = it.recording.copy(countdown = n)) }
                repeat((1_000L / METER_MS).toInt()) {
                    delay(METER_MS)
                    if (stopRequested.get()) {
                        _state.update { it.copy(recording = RecordingState()) }
                        return@launch
                    }
                }
            }
            val opened = withContext(Dispatchers.IO) { recorder.start(VoiceRecorder.dir(app)) }
            if (!opened) {
                _state.update { it.copy(recording = RecordingState(failed = true)) }
                return@launch
            }
            // Cancelled in the moment the mic was opening: closed again, and
            // the sliver it heard is not a take.
            if (stopRequested.get()) {
                withContext(Dispatchers.IO) { recorder.stop()?.file?.delete() }
                _state.update { it.copy(recording = RecordingState()) }
                return@launch
            }
            _state.update { it.copy(recording = it.recording.copy(phase = RecordingState.Phase.Recording, countdown = 0)) }
            requestPlay()

            // Until the picture stops - the end of the edit, Home, a tap on it -
            // or Stop. A moment's rebuffering is not the picture stopping, so a
            // stop has to hold for a few ticks. A transport that never starts
            // - a source still loading, a request the preview never saw -
            // does not end the take on its own: it used to, after five
            // seconds, and landed a clip cut mid-sentence with nothing to say
            // why. The take goes on without the picture and the panel says so.
            var started = false
            var stoppedFor = 0
            var waited = 0L
            while (isActive && !stopRequested.get()) {
                delay(METER_MS)
                waited += METER_MS
                if (_state.value.isPlaying) {
                    started = true
                    stoppedFor = 0
                } else if (started && ++stoppedFor >= STOP_TICKS) {
                    break
                }
                val stalled = !started && waited > START_TIMEOUT_MS
                _state.update {
                    it.copy(recording = it.recording.copy(level = recorder.level, recordedMs = recorder.recordedMs, pictureStalled = stalled))
                }
            }
            finishTake()
        }
    }

    /** Ends the take; it lands on the strip once the file is closed. During the count-in, cancels it. */
    fun stopVoiceover() {
        stopRequested.set(true)
    }

    private suspend fun finishTake() {
        requestPause()
        val rec = _state.value.recording
        _state.update { it.copy(recording = it.recording.copy(phase = RecordingState.Phase.Saving, level = 0f)) }
        // Off the main thread (the mic's thread is joined), and not cancelled
        // with this job: the editor being cleared while the file is closing
        // used to drop the take on the floor, on disk but on no strip and in
        // no draft. Held in [unlanded] for whoever gets to it first.
        withContext(NonCancellable + Dispatchers.IO) { holdTake(rec) }
        val held = claimTake()
        if (held == null) {
            // Nothing heard - or the clearing editor landed it already.
            if (_state.value.recording.active) _state.update { it.copy(recording = RecordingState(failed = true)) }
            return
        }
        landTake(held.first, held.second)
    }

    /** Closes the mic and keeps what it heard for [claimTake]; nothing heard keeps nothing. */
    @Synchronized
    private fun holdTake(rec: RecordingState) {
        val take = recorder.stop() ?: return
        unlanded = take to rec
    }

    @Synchronized
    private fun claimTake(): Pair<VoiceRecorder.Take, RecordingState>? = unlanded.also { unlanded = null }

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
     *
     * In every phase, not only while recording: the scope is already
     * cancelled here, so a mic that the coroutine was opening at that moment
     * would stay open with nobody to close it, and a take it was closing would
     * be finished and then dropped. The stop waits its turn behind either.
     */
    fun finishRecordingNow() {
        val rec = _state.value.recording
        if (!rec.active) return
        stopRequested.set(true)
        holdTake(rec)
        val held = claimTake()
        if (held == null) {
            _state.update { it.copy(recording = RecordingState()) }
            return
        }
        landTake(held.first, held.second)
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
     * onto the camera-audio grid when no sound is there - or when that grid
     * is the one found, whatever sound the playhead is over, since a tap
     * onto a sound used to move the grid there and throw the camera's away.
     * Not doubled onto a beat already within a few frames (AudioRules.withBeat).
     */
    fun addBeatAtPlayhead() {
        val current = _state.value
        val at = current.playheadMs
        val cameraGrid = current.beats.clipId == null && current.beats.beatsMs.isNotEmpty()
        val covering = if (cameraGrid) emptyList() else current.audioClips.filter { at >= it.timelineStartMs && at < it.timelineEndMs }
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
                    else s.copy(beats = s.beats.copy(finished = true, clipId = target.id, clipLabel = target.label))
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

    /** Each main-track shot shortened to end on a beat of the chosen grid (withShotsFittedToBeats), as one step. */
    fun fitShotsToBeats() {
        val beats = _state.value.beatGrid
        if (beats.isEmpty()) return
        record("Fit to the beat") {
            mutateTimeline { it.withShotsFittedToBeats(beats) }
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

        /** How long a take waits for the picture to start before saying it has not. */
        const val START_TIMEOUT_MS = 5_000L

        /** Ticks the picture must be stopped for before the take ends with it: half a second, past any rebuffering. */
        const val STOP_TICKS = 5
    }

    // ---- Listening to files: auto-duck, even out, remove silences --------------------

    /**
     * Each file among [clips] decoded once, as far as the furthest of them
     * plays and no further, at 16 kHz mono - capped at LISTEN_MAX_MS: a shot
     * from minute forty of an hour-long recording asked for a decode the size
     * of the heap. A file past the cap, or that cannot be read, is handed on
     * as null, and so are the clips on it.
     */
    private suspend fun listenTo(clips: List<Clip>, each: suspend (Uri, MonoPcm?) -> Unit) {
        clips.filter { it.uri != null }.groupBy { it.uri!! }.forEach { (uri, onIt) ->
            val until = onIt.maxOf { it.sourceOutMs } + 1_000L
            val pcm = if (until > LISTEN_MAX_MS) null
            else PcmDecoder.decodeMono(app, uri, targetSampleRate = LISTEN_RATE, maxDurationMs = until)
            each(uri, pcm)
        }
    }

    /**
     * A timeline operation on a recorded state (recordLate), for a result
     * landing from the background: filed as its own step without splitting a
     * gesture under way, as auto-sync and the beat finder file theirs.
     */
    private fun EditSnapshot.withTimeline(block: (TimelineState) -> TimelineState): EditSnapshot {
        // The same fitting mutateTimeline does: fades, overlay transitions, effects.
        val next = block(TimelineState(clips = videoClips + audioClips, selectedClipId = selectedClipId))
            .let { t -> t.copy(clips = t.clips.map(AudioRules::withFittedFades)) }
            .withOverlayTransitionsFitted()
        val video = next.clips.filter { it.kind == ClipKind.Video }
        return copy(
            videoClips = video,
            audioClips = next.clips.filter { it.kind == ClipKind.Audio },
            selectedClipId = next.selectedClipId,
            effects = effects.fittedTo(video.maxOfOrNull { it.timelineEndMs } ?: 0L)
        )
    }

    /**
     * Turns the sound [clipId] down under every stretch of talking on the
     * timeline - voiceovers, lines read aloud, and the camera sound of the
     * shots - and back up between, over the level it already has (DuckRules).
     * One undo step. [onDone] is told how many dips were made: 0 when no speech
     * was found under it, or it is already down under all of it.
     */
    fun duckUnderSpeech(clipId: String, onDone: (Int) -> Unit) {
        val music = _state.value.audioClips.firstOrNull { it.id == clipId } ?: return onDone(0)
        viewModelScope.launch {
            val state = _state.value
            val sources = (state.audioClips.filter { it.id != clipId && (it.isVoiceover || it.uri?.path?.contains("/speech/") == true) } +
                (if (state.muteOriginal) emptyList() else state.videoClips.filter { it.isHeard && it.isFootage }))
                .distinctBy { it.id }
                .filter { it.timelineEndMs > music.timelineStartMs && it.timelineStartMs < music.timelineEndMs }
            val found = HashMap<Uri, List<LongRange>>()
            listenTo(sources) { uri, pcm ->
                found[uri] = if (pcm == null) emptyList()
                else withContext(Dispatchers.Default) { SpeechSegmenter.segment(pcm).map { it.startMs..it.endMs } }
            }
            val speech = sources.flatMap { clip -> DuckRules.onTimeline(clip, found[clip.uri] ?: emptyList()) }
            val current = _state.value.audioClips.firstOrNull { it.id == clipId } ?: return@launch onDone(0)
            if (DuckRules.keys(current, speech).isEmpty()) {
                // Talking under it, and it is already down under all of it: say so (-1).
                val under = speech.any { it.last > current.timelineStartMs && it.first < current.timelineEndMs }
                return@launch onDone(if (under) -1 else 0)
            }
            recordLate("Duck under speech", edit = { snapshot ->
                snapshot.copy(audioClips = snapshot.audioClips.map { c ->
                    if (c.id != clipId) c
                    else DuckRules.keys(c, speech).takeIf { it.isNotEmpty() }?.let { keys -> c.copy(volumeKeys = keys) } ?: c
                })
            })
            onDone(DuckRules.merged(speech.filter { it.last > current.timelineStartMs && it.first < current.timelineEndMs }).size)
        }
    }

    /**
     * Every shot's level set so the loud ones sound as loud as the quieter ones
     * (Loudness), what each is heard at measured as its raw loudness times the
     * level it has, and levels only ever brought down. Shots with keyed levels
     * are left, as are photos, silent shots and files that cannot be read.
     * One undo step; [onDone] says how many shots changed.
     */
    fun evenOutVolume(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val shots = _state.value.videoClips.filter {
                // Footage only: a photo on the main track is a rendered MP4 with a silent track, not the PNG isStill reads.
                it.isHeard && it.volumeKeys.isEmpty() && it.uri != null && it.isFootage
            }
            if (shots.size < 2) return@launch onDone(0)
            // Per shot, not per file: each file's sound is let go before the next is read.
            val loudness = HashMap<String, Float>()
            listenTo(shots) { uri, pcm ->
                if (pcm != null) withContext(Dispatchers.Default) {
                    shots.filter { it.uri == uri }.forEach { clip ->
                        val from = (clip.sourceInMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.samples.size)
                        val to = (clip.sourceOutMs * pcm.sampleRate / 1000L).toInt().coerceIn(from, pcm.samples.size)
                        loudness[clip.id] = Loudness.of(pcm.samples.copyOfRange(from, to), pcm.sampleRate)
                    }
                }
            }
            val levels = Loudness.levels(shots.map { loudness[it.id] ?: 0f }, shots.map { it.volume })
            val byId = shots.zip(levels)
                .filter { (clip, level) -> kotlin.math.abs(clip.volume - level) > 0.02f }
                .associate { (clip, level) -> clip.id to level }
            if (byId.isEmpty()) return@launch onDone(0)
            val measured = shots.associateBy { it.id }
            recordLate("Even out volume", edit = { snapshot ->
                snapshot.copy(videoClips = snapshot.videoClips.map { c ->
                    // Only a shot still as it was measured: a level changed meanwhile is the person's.
                    val level = byId[c.id]
                    val was = measured[c.id]
                    if (level != null && was != null && c.volume == was.volume && c.volumeKeys.isEmpty()) c.copy(volume = level) else c
                })
            })
            onDone(byId.size)
        }
    }

    /**
     * Takes the quiet stretches out of main-track shot [clipId]: listens to its
     * own sound, keeps what has talking in it (SilenceRules), and cuts it into
     * those pieces back to back, the words, sounds and overlays after it moving
     * back with the footage they were over. One undo step. [onDone] is told
     * how many milliseconds of the file were removed - 0 when nothing was,
     * including when the shot changed while it was being listened to.
     */
    fun removeSilences(clipId: String, onDone: (Long) -> Unit) {
        val clip = _state.value.videoClips.firstOrNull { it.id == clipId && it.isMain && it.isFootage } ?: return onDone(0L)
        viewModelScope.launch {
            var speech: List<LongRange>? = null
            listenTo(listOf(clip)) { _, pcm ->
                if (pcm != null) speech = withContext(Dispatchers.Default) { SpeechSegmenter.segment(pcm).map { it.startMs..it.endMs } }
            }
            val found = speech ?: return@launch onDone(0L)
            val current = _state.value.videoClips.firstOrNull { it.id == clipId } ?: return@launch onDone(0L)
            // Listened to for the window it had: a shot trimmed or floated
            // meanwhile is not the one the answer is for.
            fun same(c: Clip?) = c != null && c.isMain && c.sourceInMs == clip.sourceInMs && c.sourceOutMs == clip.sourceOutMs &&
                c.uri == clip.uri && c.isReversed == clip.isReversed
            if (!same(current)) return@launch onDone(0L)
            val kept = SilenceRules.keptWindows(found, current.sourceInMs, current.sourceOutMs)
            val removed = if (kept.isEmpty()) 0L else SilenceRules.removedMs(kept, current.sourceInMs, current.sourceOutMs)
            if (removed <= 0L) return@launch onDone(0L)
            recordLate("Remove silences", edit = { snapshot ->
                // Worked out on the state it lands in, not the screen: under a gesture
                // still moving the two differ, and the shifts must be the recorded ones.
                val here = snapshot.videoClips.firstOrNull { it.id == clipId }
                if (!same(here)) return@recordLate snapshot
                val at = here!!.timelineStartMs
                val gone = removedOnTimeline(here, kept.filter { it.last - it.first >= MIN_CLIP_MS })
                snapshot.copy(
                    // Effects on the words after the shot move back with them, as the lines do.
                    effects = snapshot.effects.map { e ->
                        if (e.startMs < at) e else {
                            val start = shiftedPast(e.startMs, gone)
                            e.copy(startMs = start, endMs = start + (e.endMs - e.startMs))
                        }
                    }
                ).withTimeline { it.withSilencesRemoved(clipId, kept) }.copy(
                    textOverlays = snapshot.textOverlays.map { line ->
                        if (line.startMs < at) line else {
                            val start = shiftedPast(line.startMs, gone)
                            line.copy(startMs = start, endMs = start + (line.endMs - line.startMs))
                        }
                    }
                )
            })
            // Shorter now: a playhead past the new end comes back to it.
            _state.update { s -> if (s.playheadMs > s.timelineDurationMs) s.copy(playheadMs = s.timelineDurationMs, scrubNonce = s.scrubNonce + 1) else s }
            onDone(removed)
        }
    }
}

/** 16 kHz mono: what the speech finder and a loudness meter need. */
private const val LISTEN_RATE = 16_000

/** Past half an hour into a file it is not listened to: that decode would be the size of the heap. */
private const val LISTEN_MAX_MS = 30 * 60_000L
