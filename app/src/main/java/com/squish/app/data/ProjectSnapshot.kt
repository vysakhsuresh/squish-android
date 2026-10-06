package com.squish.app.data

import android.net.Uri
import com.squish.app.editor.BeatProgress
import com.squish.app.editor.CanvasBackground
import com.squish.app.editor.CaptionSource
import com.squish.app.editor.CropAspect
import com.squish.app.editor.CropRect
import com.squish.app.editor.TextOverlayItem
import com.squish.app.editor.TimedEffect
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.timeline.Clip

/*
 * What a draft holds, and what a project is started from.
 *
 * Lifted out of ProjectAutosave.kt as a pure move. ProjectSnapshot is the
 * *declared contract* of a draft - a setting of the edit that reaches
 * EditorUiState and not this list is one nobody decided to keep - and it was
 * declared inside the file that needs a Context while every function that fills
 * it had already moved out. tools/jvm/DraftFieldChecks.kt had been calling that
 * odd for two cuts of the codec; now the contract sits where the codec is.
 *
 * Nothing here touches Android but Uri.
 */

/** A recovered edit, ready to be poured back into the editor. */
data class ProjectSnapshot(
    val sourceUri: Uri,
    val savedAtMillis: Long,
    val clipCount: Int,
    val clips: List<Clip>,
    val audioClips: List<Clip>,
    val textOverlays: List<TextOverlayItem>,
    val effects: List<TimedEffect>,
    val markers: List<Long>,
    val playheadMs: Long,
    val outputP: Int,
    val fitToSize: Boolean,
    val targetSizeMb: Int,
    val audioOnly: Boolean,
    val muteOriginal: Boolean,
    val originalVolume: Float,
    val rotationDegrees: Int,
    val cropAspect: CropAspect,
    val cropRect: CropRect,
    val snapToMarkers: Boolean,
    val stabilizeStrength: Float,
    val beats: BeatProgress,
    val canvasBackground: CanvasBackground,
    val pixelsPerSecond: Float,
    /** What the project was named, if it was; see [EditorUiState.projectName]. */
    val name: String? = null,
    // The export sheet's other choices; defaults for every draft written before the rows existed.
    val outputFps: Int = ExportSettings.SOURCE_FPS,
    val quality: ExportQuality = ExportQuality.Recommended,
    val hevc: Boolean = false,
    val keepHdr: Boolean = false,
    /** What auto-captions listen to, and in which language; see the encoder. */
    val captionSource: CaptionSource = CaptionSource.Camera,
    val captionLanguage: String? = null
) {
    /**
     * How long the edit runs: where its last picture or sound ends, overlays
     * included - the same number as EditorUiState.trimmedDurationMs once
     * anything has been laid down. It used to be the clips' lengths added up,
     * which counted an overlay on top of the shots it sits over: the card
     * offered 0:16 of edit for an 8 s video with an 8 s overlay, a length the
     * timeline could never show.
     *
     * With nothing laid down the two differ on purpose: this is nothing, which
     * is the honest length of an empty edit, while the state falls back to the
     * source file's own window because the strip has to be drawn over
     * something. They used to differ when only the *pictures* were gone, which
     * was not on purpose - a five-second edit of one song reported the thirty
     * seconds of the video it had been opened on, six times out in the card,
     * the header, the export sheet and the size estimate alike.
     */
    val totalDurationMs: Long
        get() = maxOf(clips.maxOfOrNull { it.timelineEndMs } ?: 0L, audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L)
}

/**
 * What a project not yet saved is to be made from: the files picked for it,
 * in order, and whether they must be copied in first (ProjectAutosave.stageStart).
 */
/**
 * The files a new project starts from. [openedFromOutside]: handed over by
 * "Open with" or a share rather than made on the dashboard - left without a
 * single edit, such a project is not kept (EditorViewModel.onCleared).
 */
data class ProjectStart(val uris: List<Uri>, val copyIn: Boolean = false, val openedFromOutside: Boolean = false)
