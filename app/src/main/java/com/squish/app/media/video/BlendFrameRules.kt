package com.squish.app.media.video

import com.squish.app.timeline.Clip

/**
 * Which frame of a blended *video* overlay belongs under a moment, and how to
 * get there from the frame in hand (EDGE_PLAN F6).
 *
 * Media3's compositor blends layers source-over with no way in, so a video
 * overlay with a blend mode is decoded by hand instead and handed, frame by
 * frame, to the same shader that blends a still into each shot under it -
 * in the file and in the preview alike. This is that decoder's arithmetic,
 * apart from the decoder, so it can be executed.
 *
 * A frame is "the one for" a moment when it is the one a player would have on
 * screen then: the last at or before it - found going forward as the first
 * frame less than a frame length before the moment.
 */
object BlendFrameRules {

    enum class Step {
        /** The frame in hand is still the one. */
        Hold,
        /** Decode forward from here: it is a little way ahead. */
        Decode,
        /** Seek to the key frame before it: behind us, or too far ahead to decode through. */
        Seek
    }

    /** Past this far ahead a seek to the key frame before it is cheaper than decoding every frame between. */
    const val SEEK_AHEAD_MS = 1_500L

    /** A frame length to assume for a file that does not say its rate. */
    const val DEFAULT_FRAME_MS = 33L

    /** The length of one frame at [fps], or [DEFAULT_FRAME_MS] when the file does not say. */
    fun frameMs(fps: Float): Long =
        if (fps.isFinite() && fps >= 1f) (1000f / fps).toLong().coerceAtLeast(1L) else DEFAULT_FRAME_MS

    /** Whether a frame at [frameAtMs] is the one on screen at [wantedMs], or a later one. */
    fun takes(frameAtMs: Long, wantedMs: Long, frameMs: Long): Boolean = frameAtMs > wantedMs - frameMs

    /**
     * What to do to show [wantedMs] with the frame at [heldMs] in hand (null:
     * nothing decoded yet). Held when it is still the one; decoded forward when
     * the moment is a little ahead; a seek when the moment went back - a scrub,
     * a loop, the next shot under the same overlay starting earlier in it - or
     * jumped far ahead.
     */
    fun step(heldMs: Long?, wantedMs: Long, frameMs: Long): Step {
        if (heldMs == null) return Step.Seek
        // Further ahead than one frame past the moment: the moment went back.
        if (heldMs > wantedMs + frameMs) return Step.Seek
        if (takes(heldMs, wantedMs, frameMs)) return Step.Hold
        return if (wantedMs - heldMs > SEEK_AHEAD_MS) Step.Seek else Step.Decode
    }

    /**
     * The overlay's own source moment under timeline moment [timelineMs], or
     * null where the overlay is not on screen - through its trim and speed,
     * as its player would show it.
     */
    fun sourceMsAt(overlay: Clip, timelineMs: Long): Long? {
        if (timelineMs < overlay.timelineStartMs || timelineMs >= overlay.timelineEndMs) return null
        return overlay.sourceAt(timelineMs).coerceIn(overlay.sourceInMs, overlay.sourceOutMs)
    }

    /**
     * Whether an overlay is blended as a video: an overlay, with a blend mode,
     * and not a still ([isStill], which needs the file's name, is the caller's).
     */
    fun blendsAsVideo(clip: Clip, isStill: Boolean): Boolean =
        clip.isOverlay && !clip.blend.isPlain && !isStill
}
