package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.data.SrtCue
import com.squish.app.data.SrtFile
import com.squish.app.media.SquishError
import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.SpeechSegment
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.media.audio.Transcriber
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MIN_CLIP_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import com.squish.app.editor.*

/** Words on the picture: titles, lines, stickers, auto-captions and subtitle files. */
internal class TextEdits(host: EditHost) : EditArea(host) {

    private var captionJob: Job? = null

    /**
     * Where a new title, line or sticker of [lengthMs] goes: from the playhead, and
     * never past the last frame - see [EditRules.placedAt].
     */
    private fun placeNewText(current: EditorUiState, lengthMs: Long): Span =
        EditRules.placedAt(current.playheadMs, lengthMs, current.trimmedDurationMs, MIN_CLIP_MS)

    /**
     * A blank line starting at the playhead. Spanning the whole clip - which is what
     * this used to do - is never what anyone wants from a caption.
     */
    fun addCaptionAtPlayhead(): String {
        val id = UUID.randomUUID().toString()
        record("Add line", tag = addLineTag(id)) { addBlankLine(id) }
        return id
    }

    /**
     * The line Add text made, let go of without a word typed in it, taken back
     * off: a blank line draws nothing, so it sat on the strip as an empty bar to
     * be found and deleted by hand. While adding it is still the last step it is
     * taken out of the history too, as if it had never been added; after other
     * steps it goes as a step of its own, so undo can still bring it back.
     */
    fun discardIfBlank(id: String) {
        val item = _state.value.textOverlays.firstOrNull { it.id == id && !it.sticker } ?: return
        if (item.text.isNotBlank()) return
        val tag = addLineTag(id)
        if (history.undoTag == tag) {
            history.drop(tag)
            dropTextOverlay(id)
            publishHistory()
        } else {
            removeTextOverlay(id)
        }
    }

    private fun addLineTag(id: String) = "Add line $id"

    private fun addBlankLine(id: String) {
        val span = placeNewText(_state.value, DEFAULT_CAPTION_MS)
        val item = TextOverlayItem(
            id = id,
            text = "",
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = android.graphics.Color.WHITE
        )
        // Selected, so the toolbar is the line's own and Edit has something to open on.
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id) }
    }

    /**
     * A title at the playhead, styled by [preset]: its text, face, look, colour,
     * place on the frame and motion, all at once. It is an ordinary caption from
     * then on - every part of it can be changed afterwards.
     */
    fun addTitle(preset: TitlePreset) = record("Add title") {
        val span = placeNewText(_state.value, DEFAULT_TITLE_MS)
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = preset.sample,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = preset.colorArgb,
            yFraction = preset.yFraction,
            sizeSp = preset.sizeSp,
            font = preset.font,
            look = preset.look,
            motion = preset.motion
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id) }
    }

    /**
     * A sticker at the playhead, in the middle of the picture, popping in. It
     * stays for [DEFAULT_TITLE_MS] and can be moved, sized and retimed from there.
     */
    fun addSticker(emoji: String) = record("Add sticker") {
        val span = placeNewText(_state.value, DEFAULT_TITLE_MS)
        val item = TextOverlayItem(
            id = UUID.randomUUID().toString(),
            text = emoji,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = android.graphics.Color.WHITE,
            xFraction = 0.5f,
            yFraction = 0.5f,
            sizeSp = 64,
            look = TextLook.Plain,
            motion = TextMotion.Pop,
            sticker = true
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id) }
    }

    /**
     * Changes how one caption looks or moves. The text and timing are left alone.
     * [dragging] is a slider - size, position - whose frames are one step; a tap
     * on a chip is a step of its own.
     */
    fun restyleCaption(id: String, change: (TextOverlayItem) -> TextOverlayItem, dragging: Boolean = false) =
        record("Text style", gesture = if (dragging) "Style $id" else null) {
            _state.update { current ->
                current.copy(textOverlays = current.textOverlays.map { if (it.id == id) change(it) else it })
            }
        }

    /** Takes one caption or sticker off, as an undo step - from the panels and the strip alike. */
    fun removeTextOverlay(id: String) = record("Remove text") { dropTextOverlay(id) }

    // ---- Captions ---------------------------------------------------------------

    /**
     * Finds every stretch of speech and makes a caption for each, transcribing where
     * the device can.
     *
     * The two halves are deliberately independent. Segmentation runs on the PCM the
     * app already decodes and always works; recognition needs an on-device model
     * that not every phone has. When recognition is unavailable you still get every
     * caption card sitting on exactly the right frames, which is the half that takes
     * the time - typing the words is quick once the timing is done for you.
     */
    fun generateCaptions() {
        val current = _state.value
        if (current.captions.running) return
        // Every shot of the main track, from its own file, over the part of that
        // file it plays. It used to listen to the first file opened, from its top,
        // and write the file's times straight onto the timeline - so a trimmed,
        // moved or retimed shot got its captions early or late, a second file got
        // none, and speech trimmed away still got a card.
        val shots = current.videoClips.filter { it.layer == 0 && it.uri != null }.sortedBy { it.timelineStartMs }
        if (shots.isEmpty()) return

        captionJob?.cancel()
        val run = CaptionRun("Auto-caption ${UUID.randomUUID()}")
        captionRun = run
        _state.update {
            it.copy(captions = CaptionProgress(running = true, stage = "Listening to the audio"))
        }
        // The run's undo step opens now, where the run began, and every line
        // that lands is folded into it - see [landAutoCaption] - so one undo takes
        // the whole run back, whatever was edited while it ran. Nothing is
        // removed yet: the previous run's lines go when the first new line
        // arrives. Taking them here, before a single byte was decoded, meant a
        // pass that found no sound or was stopped straight away left forty
        // hand-corrected lines gone and nothing in their place.
        history.record("Auto-caption", _state.value.editSnapshot, System.currentTimeMillis(), tag = run.tag)
        publishHistory()

        captionJob = viewModelScope.launch {
            try {
                captionPass(run, shots)
            } finally {
                settleCaptionRun(run)
            }
        }
    }

    /** The listening and transcribing of one auto-caption run, lines landing as they are done. */
    private suspend fun captionPass(run: CaptionRun, shots: List<Clip>) {
        // One decode and one pass of the segmenter per file, however many
        // shots are cut from it.
        val decoded = HashMap<Uri, MonoPcm?>()
        val found = HashMap<Uri, List<SpeechSegment>>()
        val planned = ArrayList<PlannedCaption>()
        var anySound = false
        for (shot in shots) {
            val uri = shot.uri ?: continue
            // 16 kHz mono is what speech recognisers expect, and it is plenty
            // for finding utterance boundaries.
            val pcm = if (decoded.containsKey(uri)) decoded[uri] else PcmDecoder.decodeMono(
                app, uri,
                targetSampleRate = 16_000,
                maxDurationMs = 30 * 60_000L
            ).also { decoded[uri] = it }
            if (pcm == null) continue
            anySound = true
            val segments = found[uri] ?: withContext(Dispatchers.Default) { SpeechSegmenter.segment(pcm) }
                .also { found[uri] = it }
            EditRules.speechInWindow(
                segments.map { Span(it.startMs, it.endMs) },
                shot.sourceInMs, shot.sourceOutMs, MIN_CAPTION_MS
            ).forEach { planned += PlannedCaption(shot.id, pcm, SpeechSegment(it.startMs, it.endMs)) }
        }

        if (!anySound) {
            // Said in the panel, as what it is. The failure card this used to
            // raise talked about audio-only exports.
            _state.update { it.copy(captions = CaptionProgress(finished = true, noAudio = true)) }
            return
        }
        if (planned.isEmpty()) {
            _state.update { it.copy(captions = CaptionProgress(finished = true, total = 0)) }
            return
        }

        val canTranscribe = Transcriber.isAvailable(app)
        _state.update {
            it.copy(
                captions = CaptionProgress(
                    running = true,
                    stage = if (canTranscribe) "Transcribing" else "Timing the captions",
                    total = planned.size,
                    recognitionAvailable = canTranscribe
                )
            )
        }

        val language = Locale.getDefault().toLanguageTag()
        var transcribed = 0
        var made = 0

        // Each line lands on the timeline as soon as it is done, rather than all
        // of them at the end. So stopping part-way keeps what was already made,
        // and a long clip shows its captions arriving instead of a spinner.
        planned.forEachIndexed { index, plan ->
            val words = if (canTranscribe) {
                Transcriber.transcribe(app, plan.pcm, plan.segment, language)
            } else null
            // A recogniser that ignores cancellation can still hand back words
            // after Stop; they must not land.
            currentCoroutineContext().ensureActive()
            if (!words.isNullOrBlank()) transcribed++
            if (landAutoCaption(run, plan, words?.takeIf { it.isNotBlank() } ?: "")) made++
            _state.update { it.copy(captions = it.captions.copy(transcribed = transcribed, done = index + 1)) }
        }

        _state.update {
            it.copy(
                captions = CaptionProgress(
                    finished = true,
                    total = made,
                    transcribed = transcribed,
                    done = planned.size,
                    recognitionAvailable = canTranscribe
                )
            )
        }
    }

    /**
     * One auto-caption run: the tag its undo step carries, and whether any line
     * has landed yet - the first one is what retires the previous run's lines,
     * and a run that lands none leaves no step behind.
     */
    private class CaptionRun(val tag: String) {
        var landed = false
    }

    /** The run in progress, or null once it has finished or been stopped. */
    private var captionRun: CaptionRun? = null

    /**
     * After a run, whichever way it ended. A run that made nothing - no sound, no
     * speech, stopped before its first line - changed nothing, and an "Undo:
     * Auto-caption" that does nothing is not left on the button.
     */
    private fun settleCaptionRun(run: CaptionRun) {
        if (!run.landed) {
            history.drop(run.tag)
            publishHistory()
        }
        if (captionRun === run) captionRun = null
    }

    /** One line of speech to caption: which shot carries it, and where it is in that shot's file. */
    private class PlannedCaption(val clipId: String, val pcm: MonoPcm, val segment: SpeechSegment)

    /**
     * Puts one auto-caption on the timeline, where its words are heard.
     *
     * Mapped through the shot as it is *now*: a shot trimmed or moved while the
     * run was listening carries its speech with it, and speech it has since
     * trimmed away is dropped.
     *
     * The line is part of the run's undo step, however much has been edited since
     * the run began - [UndoStack.amend] writes it into every state recorded after
     * that step. It used to be pushed on top like an edit, which closed whatever
     * the person was doing each time a line landed: typing a correction while
     * lines arrived became a dozen alternating steps, and a sixty-line run
     * became sixty steps that pushed the one holding the previous run's lines
     * out of the history.
     *
     * The first line to land takes the previous run's lines away, so a second
     * run replaces the first instead of stacking a copy of every line on it -
     * and only once there is something to replace them with.
     */
    private fun landAutoCaption(run: CaptionRun, plan: PlannedCaption, text: String): Boolean {
        if (captionRun !== run) return false
        val shot = _state.value.videoClips.firstOrNull { it.id == plan.clipId } ?: return false
        val start = maxOf(plan.segment.startMs, shot.sourceInMs)
        val end = minOf(plan.segment.endMs, shot.sourceOutMs)
        if (end - start < MIN_CAPTION_MS) return false
        val line = TextOverlayItem(
            id = AUTO_CAPTION_PREFIX + UUID.randomUUID(),
            text = text,
            startMs = shot.timelineAtSource(start),
            endMs = shot.timelineAtSource(end),
            colorArgb = android.graphics.Color.WHITE
        )
        if (line.endMs <= line.startMs) return false
        val replacing = !run.landed
        val land = { lines: List<TextOverlayItem> ->
            (if (replacing) lines.filterNot { it.isAutoCaption } else lines) + line
        }
        if (!history.amend(run.tag) { it.copy(textOverlays = land(it.textOverlays)) }) return false
        run.landed = true
        _state.update { current ->
            val kept = land(current.textOverlays)
            // A selected line of the previous run has gone; nothing is selected in its place.
            val selectionGone = current.textOverlays.any { it.id == current.selectedClipId } &&
                kept.none { it.id == current.selectedClipId }
            current.copy(
                textOverlays = kept,
                selectedClipId = if (selectionGone) null else current.selectedClipId
            )
        }
        edited()
        return true
    }

    /**
     * Stops auto-captioning where it has got to.
     *
     * There was no way to do this, and on a long clip - or a phone whose recogniser
     * went quiet - the panel said "Listening" for as long as the editor stayed
     * open. The lines already made stay on the timeline; only the rest are dropped.
     */
    fun stopCaptions() {
        if (!_state.value.captions.running) return
        captionJob?.cancel()
        captionJob = null
        // Nothing more lands from here, even from a recogniser that does not
        // notice it was cancelled.
        captionRun = null
        _state.update {
            val progress = it.captions
            it.copy(
                captions = CaptionProgress(
                    finished = true,
                    stopped = true,
                    total = progress.done,
                    transcribed = progress.transcribed,
                    done = progress.done,
                    recognitionAvailable = progress.recognitionAvailable
                )
            )
        }
    }

    /**
     * Called before an undo. Undoing the run's own step stops the run - there is
     * no step left for its lines to join; see the view model's undo.
     */
    fun stopIfUndoingRun() {
        if (captionRun?.let { history.undoTag == it.tag } == true) stopCaptions()
    }

    /**
     * The words of one caption, typed. A run of typing in one line is one undo
     * step - pauses between words included - and leaving the field ends it.
     */
    fun updateCaptionText(id: String, text: String) =
        record("Caption text", gesture = typingGesture(id), holdMs = TYPING_HOLD_MS) {
            _state.update { current ->
                current.copy(
                    textOverlays = current.textOverlays.map {
                        if (it.id == id) it.copy(text = text) else it
                    }
                )
            }
        }

    /**
     * Every caption, gone - asked first by the panel, and one undo away after.
     * It used to be neither: one tap on "Clear all" and forty hand-corrected lines
     * were gone, with the next autosave writing the empty list over the draft.
     */
    fun clearCaptions() {
        if (_state.value.captions.running) stopCaptions()
        record("Clear captions") {
            // Stickers are not captions, and clearing the words should not take them.
            _state.update { it.copy(textOverlays = it.textOverlays.filter { o -> o.sticker }, captions = CaptionProgress()) }
        }
    }

    /** Brings in a transcript made anywhere else. */
    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (raw == null) {
                _state.update { it.copy(failure = SquishError.FileUnreadable()) }
                return@launch
            }
            val cues = SrtFile.parse(raw)
            if (cues.isEmpty()) {
                _state.update { it.copy(failure = SquishError.CaptionsUnreadable()) }
                return@launch
            }
            record("Import subtitles") {
                _state.update { current ->
                    current.copy(
                        textOverlays = current.textOverlays + cues.map { cue ->
                            TextOverlayItem(
                                id = UUID.randomUUID().toString(),
                                text = cue.text,
                                startMs = cue.startMs,
                                endMs = cue.endMs,
                                colorArgb = android.graphics.Color.WHITE
                            )
                        },
                        // An auto-caption run in progress keeps its own progress line.
                        captions = if (current.captions.running) current.captions
                        else CaptionProgress(finished = true, imported = cues.size)
                    )
                }
            }
        }
    }

    /** Writes the captions out so they can be used anywhere else. */
    fun exportSrt(target: Uri, onDone: (Boolean) -> Unit) {
        val cues = _state.value.textOverlays
            .filterNot { it.sticker }
            .sortedBy { it.startMs }
            .map { SrtCue(it.startMs, it.endMs, it.text) }
            .filter { it.text.isNotBlank() }

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(target)?.use {
                        it.write(SrtFile.format(cues).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    /**
     * The caption's text field lost focus. Only that caption's typing ends: the
     * panel may be showing a dozen rows, and a row that never had the cursor has
     * no business closing a drag or a run of typing somewhere else.
     */
    fun endCaptionTyping(id: String) = history.endGesture(typingGesture(id))

    private fun typingGesture(id: String) = "Text $id"

    private companion object {
        const val DEFAULT_CAPTION_MS = 2_000L

        /** Shorter than this, a stretch of speech is a fragment of a word, not a line. */
        const val MIN_CAPTION_MS = 150L

        /**
         * How long typing in one caption may pause and still be the same undo step.
         * Long enough for a pause between words, short enough that coming back to
         * the line later is a new edit.
         */
        const val TYPING_HOLD_MS = 5_000L
    }
}
