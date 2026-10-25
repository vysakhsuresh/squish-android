package com.squish.app.media

import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind

/**
 * What an export may copy rather than encode (EDGE_PLAN C2).
 *
 * A shot is copyable when nothing about it changes a pixel or a sample: it is
 * the clip a file becomes when it is added and left alone, cut to a window.
 * Rather than list every field that can change a picture - a list that falls
 * behind the model the day a field is added - the shot is rebuilt from its
 * identity and window alone, every other field at its default, and compared:
 * a new field is a change until it is at its default.
 *
 * Only one such shot, alone on the edit, is copied today. Media3's trim
 * optimisation re-encodes the few frames before the first key frame and copies
 * the rest, but only for a composition of one item; two copied shots joined
 * need their parameter sets to agree across the join, and a re-encoded GOP at a
 * cut does not carry the source's - one MP4 track has one sample description.
 */
object SmartRender {

    fun plainShot(clip: Clip): Boolean =
        clip.kind == ClipKind.Video && !clip.isOverlay && clip.uri != null &&
            clip == Clip(
                id = clip.id,
                kind = clip.kind,
                uri = clip.uri,
                label = clip.label,
                sourceInMs = clip.sourceInMs,
                sourceOutMs = clip.sourceOutMs,
                timelineStartMs = clip.timelineStartMs,
                sourceDurationMs = clip.sourceDurationMs,
                // Where the beats are changes nothing anyone sees or hears.
                beats = clip.beats
            )

    /** The one shot an edit of [videoClips] can be copied from, or null. */
    fun copyableShot(videoClips: List<Clip>): Clip? =
        videoClips.singleOrNull()?.takeIf { plainShot(it) && it.timelineStartMs == 0L }
}
