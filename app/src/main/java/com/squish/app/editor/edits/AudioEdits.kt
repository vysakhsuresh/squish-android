package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.audio.AudioSyncAnalyzer
import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.BeatMap
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.WaveformBuilder
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.withSplitAllTracks
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.squish.app.editor.*

/** Sound: added tracks, the camera's own audio, the voice, auto-sync, and the beat grid. */
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

            // Cached against the file, not the clip, so splitting a track in two
            // costs nothing and both halves draw immediately.
            if (_state.value.audioWaveforms[uri.toString()] == null) {
                val pcm = PcmDecoder.decodeMono(app, uri, maxDurationMs = 10 * 60_000L)
                if (pcm != null) {
                    val wave = WaveformBuilder.build(pcm)
                    _state.update { it.copy(audioWaveforms = it.audioWaveforms + (uri.toString() to wave)) }
                }
            }
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

    fun setAudioClipVolume(clipId: String, volume: Float) = record("Level", gesture = "Level $clipId") {
        updateAudioClip(clipId) { it.copy(volume = volume.coerceIn(0f, 1f)) }
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

    fun setVoiceEffect(effect: VoiceEffect) = record("Voice") {
        _state.update { it.copy(voiceEffect = effect) }
    }

    fun setMuteOriginal(muted: Boolean) = record("Camera audio") {
        _state.update { it.copy(muteOriginal = muted) }
        recomputeEstimate()
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
            // the beat" act on, and undo puts the previous one back.
            val found = map.toProgress(target, label)
            recordLate(
                "Find the beat",
                edit = { it.copy(beats = found) },
                alongside = { it.copy(beats = found) }
            )
        }
    }

    /**
     * Beat times moved out of the analysed clip's source clock and onto the
     * timeline.
     *
     * The decoder always starts at the top of the file, so a beat's time is a
     * source time; a music cue dragged to start ten seconds in has its beats ten
     * seconds later than the analysis says. Mapping through the clip also puts
     * them through its speed curve, which is the only way a ramped music bed's
     * beats land where they are heard.
     */
    private fun BeatMap.toProgress(clip: Clip?, label: String): BeatProgress {
        val onTimeline = if (clip == null) beatsMs else beatsMs.mapNotNull { at ->
            val source = clip.sourceInMs + at
            if (source < clip.sourceInMs || source > clip.sourceOutMs) null
            else clip.timelineAtSource(source)
        }
        return BeatProgress(
            finished = true,
            bpm = bpm,
            confidence = confidence,
            beatsMs = onTimeline,
            downbeatOffset = downbeatOffset,
            clipLabel = label
        )
    }

    /** The same pulse counted twice as fast, or half as fast. */
    fun scaleBeats(faster: Boolean) = record("Beat tempo") {
        _state.update { current ->
            val beats = current.beats
            if (!beats.hasBeats) return@update current
            val map = BeatMap(beats.beatsMs, beats.bpm, beats.confidence, beats.downbeatOffset)
            val scaled = if (faster) map.doubled() else map.halved()
            current.copy(
                beats = beats.copy(
                    beatsMs = scaled.beatsMs,
                    bpm = scaled.bpm,
                    downbeatOffset = scaled.downbeatOffset
                )
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
        _state.update { it.copy(beats = BeatProgress()) }
    }

    /**
     * Drops a marker on every nth beat, so every edit that already snaps now snaps
     * to the music: dragging a clip, setting an in point, moving a caption.
     *
     * Merged with the markers already there. It used to replace them, so a marker
     * put by hand on the one frame that mattered went the moment the grid was
     * snapped to. Markers sitting on the grid are the ones an earlier snap made,
     * and those are replaced - so "every bar" after "every beat" thins them out.
     */
    fun markBeats(everyN: Int) = record("Mark beats") {
        _state.update { current ->
            val chosen = current.beats.every(everyN)
            if (chosen.isEmpty()) current
            else current.copy(
                markers = EditRules.mergedBeatMarkers(current.markers, current.beats.beatsMs, chosen, current.frameMs),
                snapToMarkers = true
            )
        }
    }

    /**
     * Cuts the main video track on every nth beat.
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
    fun cutOnBeats(everyN: Int) {
        val current = _state.value
        val cuts = current.beats.every(everyN)
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
    }
}
