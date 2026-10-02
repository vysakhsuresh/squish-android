package com.squish.app.editor.edits

import android.net.Uri
import com.squish.app.data.SrtCue
import com.squish.app.data.SrtFile
import com.squish.app.media.CustomFonts
import com.squish.app.media.SquishError
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.audio.MonoPcm
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.media.audio.SpeechSegment
import com.squish.app.media.audio.SpeechSegmenter
import com.squish.app.media.audio.Transcriber
import com.squish.app.media.audio.Tts
import com.squish.app.media.audio.WaveformBuilder
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_CLIP_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import com.squish.app.editor.*

/** Words on the picture: titles, lines, stickers, auto-captions and subtitle files. */
internal class TextEdits(host: EditHost) : EditArea(host) {

    private var captionJob: Job? = null

    init {
        // The fonts brought in live with the app's files; the renderer, which
        // has no context of its own, finds them here - in the export too, which
        // runs in this process.
        CustomFonts.install(File(app.filesDir, FONTS_DIR))
    }

    /**
     * Where a new title, line or sticker of [lengthMs] goes: from the playhead, and
     * never past the last frame - see [EditRules.placedAt].
     */
    private fun placeNewText(current: EditorUiState, lengthMs: Long): Span =
        EditRules.placedAt(current.playheadMs, lengthMs, current.trimmedDurationMs, MIN_CLIP_MS)

    /**
     * A line at the playhead, in the middle of the picture, with sample words for
     * the keyboard to replace - the way CapCut adds text: something is on the
     * picture at once, and typing writes over it. A blank line drew nothing and
     * so had no box to take hold of.
     *
     * Recorded as the start of the typing that follows ([updateCaptionText]
     * carries it on), so adding a line and putting words in it is one undo step,
     * as in every other editor. Recorded apart, the first Undo after typing
     * emptied the line instead of removing it. The step stays open until the
     * field closes ([endCaptionTyping]) or anything else is done: however long
     * the words take to come, they are part of the add.
     */
    fun addCaptionAtPlayhead(): String {
        val id = UUID.randomUUID().toString()
        record("Add text", gesture = typingGesture(id), holdMs = NEW_LINE_HOLD_MS, tag = addLineTag(id)) { addNewLine(id) }
        return id
    }

    /**
     * The line Add text made, let go of with nothing said - its words blank, or
     * still the sample it came with - taken back off: a line that only says
     * "Your text" is not one anybody meant. While adding it is still the last
     * step it is taken out of the history too, as if it had never been added;
     * after other steps it goes as a step of its own, so undo can still bring
     * it back. A title keeps its sample words: "BIG NEWS" left alone is a choice.
     */
    fun discardIfBlank(id: String) {
        val item = _state.value.textOverlays.firstOrNull { it.id == id && !it.sticker } ?: return
        if (item.text.isNotBlank() && item.text != NEW_TEXT_SAMPLE) return
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

    private fun addNewLine(id: String) {
        val span = placeNewText(_state.value, DEFAULT_CAPTION_MS)
        val item = TextOverlayItem(
            id = id,
            text = NEW_TEXT_SAMPLE,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = android.graphics.Color.WHITE,
            xFraction = 0.5f,
            yFraction = com.squish.app.editor.TextPlacementRules.freeY(
                _state.value.textOverlays.filter { !it.sticker && it.startMs < span.endMs && it.endMs > span.startMs }.map { it.yFraction }
            )
        )
        // Selected, so the toolbar is the line's own and Edit has something to open on.
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id).stilled() }
    }

    /**
     * Paused: a line is typed and styled against the picture, and its box only
     * shows while the picture is still. Added while playing, a title with a
     * Pop arrival was invisible on the frame it landed on and the playhead
     * left it behind before its words were typed.
     *
     * Asked of the engine, as every pause from the edit is (transportRequest):
     * flipping the flag alone left the picture playing under the keyboard and
     * the flag false while the transport showed Pause, so a sound audition
     * would not stop and a voiceover take read the edit as stopped and ended.
     */
    private fun EditorUiState.stilled(): EditorUiState = if (!isPlaying) this else copy(
        isPlaying = false,
        transportRequest = TransportRequest(play = false, nonce = (transportRequest?.nonce ?: 0L) + 1)
    )

    /**
     * A title at the playhead, styled by [preset]: its text, face, look, colour,
     * place on the frame and motion, all at once. It is an ordinary caption from
     * then on - every part of it can be changed afterwards. Returns its id, so
     * the editor can open it for typing with the sample words selected.
     *
     * One step with the words typed over the sample, as [addCaptionAtPlayhead]
     * is: Undo after typing used to bring the preset's "BIG NEWS" back, a title
     * nobody asked for, and take a second press to remove. And tagged like a
     * line, so a title whose words are cleared and let go of is taken back off.
     */
    fun addTitle(preset: TitlePreset): String {
        val id = UUID.randomUUID().toString()
        record("Add title", gesture = typingGesture(id), holdMs = NEW_LINE_HOLD_MS, tag = addLineTag(id)) { addTitle(id, preset) }
        return id
    }

    private fun addTitle(id: String, preset: TitlePreset) {
        val span = placeNewText(_state.value, DEFAULT_TITLE_MS)
        val item = TextOverlayItem(
            id = id,
            text = preset.sample,
            startMs = span.startMs,
            endMs = span.endMs,
            colorArgb = preset.colorArgb
        ).styledBy(preset)
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id).stilled() }
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
            sizeSp = STICKER_SIZE_SP,
            stroke = TextStroke.NONE,
            motion = TextMotion.Pop,
            motionOut = TextExit.Fade,
            sticker = true
        )
        _state.update { it.copy(textOverlays = it.textOverlays + item, selectedClipId = item.id).stilled() }
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

    /**
     * A line moved, sized or turned by the box on the picture: the whole gesture
     * is one step. A line riding a track is let off it - a finger putting it
     * somewhere means there, not wherever the track goes next.
     */
    fun placeText(id: String, at: TextPlacement) = record("Move text", gesture = "Place $id") {
        _state.update { current ->
            current.copy(textOverlays = current.textOverlays.map { if (it.id == id) it.placed(at).copy(track = null) else it })
        }
    }

    fun setOpacity(id: String, opacity: Float) = record("Text opacity", gesture = "Opacity $id") {
        _state.update { current ->
            current.copy(textOverlays = current.textOverlays.map { if (it.id == id) it.copy(opacity = opacity.coerceIn(0f, 1f)) else it })
        }
    }

    /** Mirrored left to right: a sticker facing the other way. */
    fun flip(id: String) = record("Flip") {
        _state.update { current ->
            current.copy(textOverlays = current.textOverlays.map { if (it.id == id) it.copy(flipped = !it.flipped) else it })
        }
    }

    /**
     * The box's Duplicate: a copy at the same moment, nudged down and to the
     * right so both can be seen, and selected. The toolbar's Duplicate puts the
     * copy after the original in time; on the picture, a copy you cannot see is
     * no copy.
     */
    fun duplicateInPlace(id: String) {
        val item = _state.value.textOverlays.firstOrNull { it.id == id } ?: return
        val copy = item.copy(
            id = UUID.randomUUID().toString(),
            xFraction = (item.xFraction + COPY_NUDGE).coerceAtMost(0.98f),
            yFraction = (item.yFraction + COPY_NUDGE).coerceAtMost(0.98f),
            track = null
        )
        record("Duplicate") {
            _state.update { it.copy(textOverlays = it.textOverlays + copy, selectedClipId = copy.id) }
        }
    }

    /** One line's style put on it - a preset, a saved style, a look. Words, timing and place stay its own. */
    fun applyStyle(id: String, style: TextStyleSpec, label: String = "Text style") = record(label) {
        _state.update { current ->
            current.copy(textOverlays = current.textOverlays.map { if (it.id == id) it.withStyle(style) else it })
        }
    }

    /**
     * This line's style on every other line of words - the style alone, as
     * CapCut's is: it carried the place and the turn too, so giving forty
     * subtitles a centred title's face put every one of them in the middle of
     * the picture at the title's angle. Stickers keep their own: a caption's
     * face on a sticker is an outlined emoji. One undo step for the forty lines.
     */
    fun applyStyleToAll(id: String) {
        val from = _state.value.textOverlays.firstOrNull { it.id == id } ?: return
        record("Apply style to all") {
            _state.update { current ->
                current.copy(
                    textOverlays = current.textOverlays.map { line ->
                        if (line.id == id || line.sticker != from.sticker) line else line.withStyle(from.style)
                    }
                )
            }
        }
    }

    /**
     * This line's arrival, leaving and loop on every other line of the same
     * kind. Forty auto-captions asked to land word by word, or asked to stop,
     * were forty visits to the Animation tab.
     */
    fun applyMotionToAll(id: String) {
        val from = _state.value.textOverlays.firstOrNull { it.id == id } ?: return
        record("Apply animation to all") {
            _state.update { current ->
                current.copy(
                    textOverlays = current.textOverlays.map { line ->
                        if (line.id == id || line.sticker != from.sticker) line else line.withMotion(from.motionSpec)
                    }
                )
            }
        }
    }

    /** Remembers this line's style for [pasteStyle]. Not an edit: nothing on the picture changes. */
    fun copyStyle(id: String) {
        val item = _state.value.textOverlays.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(styleClipboard = item.style) }
    }

    fun pasteStyle(id: String) {
        val style = _state.value.styleClipboard ?: return
        applyStyle(id, style, "Paste style")
    }

    /**
     * A font file brought in and, when [onto] names a line, put on it at once.
     * The copy is made off the main thread; a file that is not a font is
     * refused with a card rather than drawn as the default face.
     */
    fun importFont(uri: Uri, onto: String?, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { CustomFonts.import(app, uri) }
            if (name == null) {
                _state.update { it.copy(failure = SquishError.FontUnreadable()) }
            } else if (onto != null) {
                restyleCaption(onto, { it.copy(fontFile = name) })
            }
            onDone(name)
        }
    }

    /** Takes one caption or sticker off, as an undo step - from the panels and the strip alike. */
    fun removeTextOverlay(id: String) = record("Remove text") { dropTextOverlay(id) }

    // ---- Reading aloud ------------------------------------------------------------

    /**
     * The line read aloud by the phone's own voice, landing as a sound clip
     * where the line starts - so a caption typed for a silent clip can be heard
     * as well as read. Made in the background: the toolbar's button is greyed
     * and a card says the voice is being made while [EditorUiState.speakingId]
     * is set, and a phone without a voice gets a card. The line stays selected
     * when the sound lands - selecting the sound closed the Edit sheet under
     * whoever was still styling the line.
     */
    fun speak(id: String) {
        val item = _state.value.textOverlays.firstOrNull { it.id == id } ?: return
        if (!canSpeak(_state.value, id)) return
        _state.update { it.copy(speakingId = id) }
        viewModelScope.launch {
            val dir = File(app.filesDir, SPEECH_DIR).apply { mkdirs() }
            val file = File(dir, "speech-${UUID.randomUUID()}.wav")
            val ok = Tts.synthesize(app, item.text, _state.value.captionLanguage, file, com.squish.app.settings.Preferences.speechVoice(app))
            val uri = Uri.fromFile(file)
            val duration = if (ok) ThumbnailExtractor.probeDurationMs(app, uri) else 0L
            if (!ok || duration <= 0L) {
                file.delete()
                _state.update { it.copy(speakingId = null, failure = SquishError.SpeechUnavailable()) }
                return@launch
            }
            val name = "Speech · " + item.text.replace('\n', ' ').take(24)
            record("Read aloud") {
                _state.update { current ->
                    // Where the line is now, not where it was when the voice was asked for.
                    val line = current.textOverlays.firstOrNull { it.id == id } ?: item
                    val clip = Clip(
                        kind = ClipKind.Audio,
                        uri = uri,
                        label = name,
                        sourceInMs = 0,
                        sourceOutMs = duration,
                        timelineStartMs = line.startMs,
                        sourceDurationMs = duration
                    )
                    current.copy(audioClips = current.audioClips + clip, speakingId = null)
                }
            }
            _state.update { it.copy(speakingId = null) }
            recomputeEstimate()
            // Cached against the file, as every sound's is, so the clip draws at once.
            val pcm = PcmDecoder.decodeMono(app, uri, maxDurationMs = 10 * 60_000L)
            if (pcm != null) {
                val wave = WaveformBuilder.build(pcm)
                _state.update { it.copy(audioWaveforms = it.audioWaveforms + (uri.toString() to wave)) }
            }
        }
    }

    // ---- Captions ---------------------------------------------------------------

    /** Settings for the next run, kept with the edit. Not undo steps: nothing on the picture changes. */
    fun setCaptionSource(source: CaptionSource) = _state.update { it.copy(captionSource = source) }

    fun setCaptionLanguage(tag: String?) = _state.update { it.copy(captionLanguage = tag) }

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
        // Every shot of the main track and every added sound - a voiceover, a
        // second mic - from its own file, over the part of that file it plays,
        // as the source setting says. It used to listen to the first file
        // opened, from its top, and write the file's times straight onto the
        // timeline - so a trimmed, moved or retimed shot got its captions early
        // or late, a second file got none, and speech trimmed away still got a card.
        // Never a line read aloud: that sound was made from a caption, and
        // listening to it landed a second copy of the line exactly under the first.
        val source = current.captionSource
        val shots = if (source == CaptionSource.Sounds) emptyList() else current.videoClips.filter { it.layer == 0 && it.uri != null }
        val sounds = if (source == CaptionSource.Camera) emptyList() else current.audioClips.filter { it.uri != null && !isSpokenLine(it) }
        val listened = (shots + sounds).sortedBy { it.timelineStartMs }
        if (listened.isEmpty()) return

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
                captionPass(run, listened)
            } finally {
                settleCaptionRun(run)
            }
        }
    }

    /** The listening and transcribing of one auto-caption run, lines landing as they are done. */
    private suspend fun captionPass(run: CaptionRun, listened: List<Clip>) {
        // One decode and one pass of the segmenter per file, however many
        // shots are cut from it.
        val decoded = HashMap<Uri, MonoPcm?>()
        val found = HashMap<Uri, List<SpeechSegment>>()
        val planned = ArrayList<PlannedCaption>()
        var anySound = false
        for (clip in listened) {
            val uri = clip.uri ?: continue
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
                clip.sourceInMs, clip.sourceOutMs, MIN_CAPTION_MS
            ).forEach { planned += PlannedCaption(clip.id, pcm, SpeechSegment(it.startMs, it.endMs)) }
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

        val language = _state.value.captionLanguage ?: Locale.getDefault().toLanguageTag()
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
            val text = words?.takeIf { it.isNotBlank() } ?: ""
            if (text.isNotEmpty()) transcribed++
            // Where each word begins, from the sound, so the Words arrival lands them on the speech.
            val count = text.split(' ').count { it.isNotBlank() }
            val starts = if (count > 1) withContext(Dispatchers.Default) { SpeechSegmenter.wordStarts(plan.pcm, plan.segment, count) } else emptyList()
            if (landAutoCaption(run, plan, text, starts)) made++
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

    /** One line of speech to caption: which clip carries it, and where it is in that clip's file. */
    private class PlannedCaption(val clipId: String, val pcm: MonoPcm, val segment: SpeechSegment)

    /**
     * Puts one auto-caption on the timeline, where its words are heard.
     *
     * Mapped through the clip as it is *now*: a shot trimmed or moved while the
     * run was listening carries its speech with it, and speech it has since
     * trimmed away is dropped. A sound is a clip like a shot here, so a
     * voiceover's lines follow it when it is dragged too.
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
    private fun landAutoCaption(run: CaptionRun, plan: PlannedCaption, text: String, wordStarts: List<Long>): Boolean {
        if (captionRun !== run) return false
        val current = _state.value
        val clip = (current.videoClips + current.audioClips).firstOrNull { it.id == plan.clipId } ?: return false
        val start = maxOf(plan.segment.startMs, clip.sourceInMs)
        val end = minOf(plan.segment.endMs, clip.sourceOutMs)
        if (end - start < MIN_CAPTION_MS) return false
        val lineStart = clip.timelineAtSource(start)
        val line = TextOverlayItem(
            id = AUTO_CAPTION_PREFIX + UUID.randomUUID(),
            text = text,
            startMs = lineStart,
            endMs = clip.timelineAtSource(end),
            colorArgb = android.graphics.Color.WHITE,
            // Through the clip's own clock, as the line's ends are, so a word
            // on a slowed shot lands where it is heard. Kept for the Words
            // arrival, which is a choice on the Animation tab (with Apply to
            // all for the rest): every line landing word by word stuttered
            // through the whole edit, and CapCut's captions land still.
            wordStartsMs = wordStarts.map { (clip.timelineAtSource(plan.segment.startMs + it) - lineStart).coerceAtLeast(0L) }
        )
        if (line.endMs <= line.startMs) return false
        val replacing = !run.landed
        val land = { lines: List<TextOverlayItem> ->
            (if (replacing) lines.filterNot { it.isAutoCaption } else lines) + line
        }
        if (!history.amend(run.tag) { it.copy(textOverlays = land(it.textOverlays)) }) return false
        run.landed = true
        _state.update { state ->
            val kept = land(state.textOverlays)
            // A selected line of the previous run has gone; nothing is selected in its place.
            val selectionGone = state.textOverlays.any { it.id == state.selectedClipId } &&
                kept.none { it.id == state.selectedClipId }
            state.copy(
                textOverlays = kept,
                selectedClipId = if (selectionGone) null else state.selectedClipId
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
     * Typing into a line just added carries the add's own step on, under its
     * name; see [addCaptionAtPlayhead].
     */
    /**
     * Every line of words in another language, online (OnlineTranslate), as one
     * undo step; stickers - an emoji or two - are left. [onDone] gets how many
     * lines were translated, or -1 when the service could not be reached.
     */
    fun translateCaptions(from: String, to: String, onDone: (translated: Int, of: Int) -> Unit) {
        val lines = _state.value.textOverlays.filter { item ->
            val t = item.text.trim()
            t.isNotEmpty() && t.codePointCount(0, t.length) > 2
        }
        if (lines.isEmpty()) return onDone(0, 0)
        viewModelScope.launch {
            // Line by line; one the service refuses is left in its own words and
            // the rest still go, and the sheet says how many did.
            val done = HashMap<String, Pair<String, String>>()
            for (line in lines) {
                val out = runCatching { com.squish.app.online.OnlineTranslate.translate(app, line.text, from, to) }.getOrNull()
                if (out != null) done[line.id] = line.text to out
            }
            if (done.isEmpty()) return@launch onDone(0, lines.size)
            recordLate("Translate captions", edit = { snapshot ->
                snapshot.copy(textOverlays = snapshot.textOverlays.map { item ->
                    val (sent, got) = done[item.id] ?: return@map item
                    // Only a line still saying what was sent: one retyped meanwhile is the person's.
                    // New words are not the words the segmenter timed.
                    if (item.text == sent) item.copy(text = got, wordStartsMs = emptyList()) else item
                })
            })
            onDone(done.size, lines.size)
        }
    }

    fun updateCaptionText(id: String, text: String) =
        record(typingLabel(id), gesture = typingGesture(id), holdMs = TYPING_HOLD_MS) {
            _state.update { current ->
                current.copy(
                    textOverlays = current.textOverlays.map {
                        // Retyped words are not the words the segmenter timed.
                        if (it.id == id) it.copy(text = text, wordStartsMs = emptyList()) else it
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

    /**
     * Brings in a transcript made anywhere else - beside the lines already there,
     * or, with [replace], in their place, which the panel asks about first.
     */
    fun importSrt(uri: Uri, replace: Boolean = false) {
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
            record(if (replace) "Replace subtitles" else "Import subtitles") {
                _state.update { current ->
                    val kept = if (replace) current.textOverlays.filter { it.sticker } else current.textOverlays
                    current.copy(
                        textOverlays = kept + cues.map { cue ->
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

    /**
     * What a run of typing in [id] is called: the add it continues, when the
     * step on top is this line's own add and still open to it (the step keeps
     * the latest label it is given, so the add's has to be given again), and
     * otherwise a change of words.
     */
    private fun typingLabel(id: String): String {
        val continuesAdd = history.undoTag == addLineTag(id) && history.continues(typingGesture(id), System.currentTimeMillis())
        return if (continuesAdd) history.undoLabel ?: "Caption text" else "Caption text"
    }

    /** A sound clip that is a line read aloud: its file is under [SPEECH_DIR]. */
    private fun isSpokenLine(clip: Clip): Boolean {
        val path = clip.uri?.takeIf { it.scheme == "file" }?.path ?: return false
        return File(path).parentFile == File(app.filesDir, SPEECH_DIR)
    }

    companion object {
        /** Whether Read aloud has anything to do for [id]: a line with words, and no voice already being made. */
        fun canSpeak(state: EditorUiState, id: String): Boolean {
            val item = state.textOverlays.firstOrNull { it.id == id } ?: return false
            return !item.sticker && item.text.isNotBlank() && state.speakingId == null
        }

        /** What a new line says until it is typed over; a line still saying it when let go of is taken off. */
        const val NEW_TEXT_SAMPLE = "Your text"

        /** The size a sticker lands at, which Placement's Reset goes back to. */
        const val STICKER_SIZE_SP = 64

        /** Under the app's files: fonts brought in, and lines read aloud. */
        const val FONTS_DIR = "fonts"
        const val SPEECH_DIR = "speech"

        private const val DEFAULT_CAPTION_MS = 2_000L

        /** Shorter than this, a stretch of speech is a fragment of a word, not a line. */
        private const val MIN_CAPTION_MS = 150L

        /** How far a copy made from the box's corner sits from the original, as a share of the frame. */
        private const val COPY_NUDGE = 0.06f

        /**
         * How long typing in one caption may pause and still be the same undo step.
         * Long enough for a pause between words, short enough that coming back to
         * the line later is a new edit.
         */
        private const val TYPING_HOLD_MS = 5_000L

        /**
         * How long a line just added waits for its words: as long as its field
         * is open. Reading the picture before typing takes longer than a pause
         * between words, and the field closing ends the step in any case.
         */
        private const val NEW_LINE_HOLD_MS = Long.MAX_VALUE / 2
    }
}
