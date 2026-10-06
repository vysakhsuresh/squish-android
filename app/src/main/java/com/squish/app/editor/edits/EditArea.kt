package com.squish.app.editor.edits

import android.app.Application
import android.net.Uri
import com.squish.app.editor.EditSnapshot
import com.squish.app.editor.EditorUiState
import com.squish.app.editor.UndoStack
import com.squish.app.timeline.TimelineState
import com.squish.app.editor.AudioRules
import com.squish.app.editor.fittedTo
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.withOverlayTransitionsFitted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the editor's view model lends each area of edits: the one state, the undo
 * history, and the handful of steps every edit ends with.
 *
 * The view model was one file of three thousand lines, which meant any two
 * pieces of work on the editor - sound and overlays, say - collided in it. The
 * edits now live in one class per area under this package, all reading and
 * writing the same state through this, so there is still exactly one
 * [EditorUiState] and exactly one way onto the undo stack.
 */
internal interface EditHost {
    val app: Application
    val state: MutableStateFlow<EditorUiState>
    val scope: CoroutineScope
    val history: UndoStack<EditSnapshot>
    fun record(label: String, gesture: String?, holdMs: Long, tag: String?, change: () -> Unit)
    fun recordLate(label: String, edit: (EditSnapshot) -> EditSnapshot, alongside: (EditorUiState) -> EditorUiState)
    fun edited()
    fun publishHistory()
    fun mutateTimeline(block: (TimelineState) -> TimelineState)
    fun recomputeEstimate()
    fun displayNameOf(uri: Uri): String?
    fun checkDecodable(uri: Uri)
    /** Starts a light preview copy of each heavy video file among [uris] that has none yet. */
    fun ensureProxies(uris: Collection<Uri>)
    /** The one-clip selection, as a tap makes it (see the view model's own). */
    fun selectClip(clipId: String?)
    /** [clipId] joins the selection and leads it, while Select more is on (see the view model's own). */
    fun joinSelection(clipId: String)
}

/**
 * One area of edits. The members here are the view model's own, under the names
 * the moved code already used, so a function reads the same wherever it lives.
 */
internal abstract class EditArea(protected val host: EditHost) {
    protected val _state: MutableStateFlow<EditorUiState> get() = host.state
    protected val app: Application get() = host.app
    protected val viewModelScope: CoroutineScope get() = host.scope
    protected val history: UndoStack<EditSnapshot> get() = host.history

    /** See the view model's own: every edit goes through here. */
    protected fun record(
        label: String,
        gesture: String? = null,
        holdMs: Long = UndoStack.COALESCE_MS,
        tag: String? = null,
        change: () -> Unit
    ) = host.record(label, gesture, holdMs, tag, change)

    protected fun recordLate(
        label: String,
        edit: (EditSnapshot) -> EditSnapshot,
        alongside: (EditorUiState) -> EditorUiState = { it }
    ) = host.recordLate(label, edit, alongside)

    /**
     * [mutateTimeline]'s work on a snapshot rather than on the live state, so a
     * result that lands in the background can go through [recordLate] - which
     * wants a pure snapshot-to-snapshot edit - instead of [record], which
     * closes whatever gesture is open and cuts a drag under the finger in two.
     *
     * The same fitting mutateTimeline does: a clip's fades refitted to the
     * window it ends up with, an overlay transition dropped when the join it
     * sat on has gone, and the effects held inside the new end. Without the
     * transitions' fit, a Reverse that made a ramped overlay a step shorter
     * left the next overlay's transition on a join that was gone, to vanish
     * inside some later edit's undo step.
     *
     * It was a private copy in AudioEdits and another in ClipEdits, which is
     * why the two landings that reach the model this way - "To main track" and
     * the subtitle import - were still on [record]: a third copy was worse than
     * the fault.
     */
    protected fun EditSnapshot.withTimeline(block: (TimelineState) -> TimelineState): EditSnapshot {
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

    protected fun edited() = host.edited()
    protected fun publishHistory() = host.publishHistory()
    protected fun mutateTimeline(block: (TimelineState) -> TimelineState) = host.mutateTimeline(block)
    protected fun recomputeEstimate() = host.recomputeEstimate()
    protected fun displayNameOf(uri: Uri): String? = host.displayNameOf(uri)
    protected fun checkDecodable(uri: Uri) = host.checkDecodable(uri)
    protected fun ensureProxies(uris: Collection<Uri>) = host.ensureProxies(uris)

    protected fun dropTextOverlay(id: String) {
        _state.update {
            it.copy(
                textOverlays = it.textOverlays.filterNot { item -> item.id == id },
                selectedClipId = if (it.selectedClipId == id) null else it.selectedClipId
            )
        }
    }

    /** Which of a family of optional arguments were passed, for telling one slider's gesture from another's. */
    protected fun fieldsNamed(vararg fields: Pair<String, Any?>): String =
        fields.filter { it.second != null }.joinToString("+") { it.first }

}

/** The shortest an effect or a placed sound may be. */
internal const val MIN_EFFECT_MS = 100L

/** Long enough for a title to arrive, be read and leave. */
internal const val DEFAULT_TITLE_MS = 3_000L
