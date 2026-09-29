package com.squish.app.timeline

/**
 * Editing a clip's opacity and volume tracks (see [ValueKey]): the same rule
 * placement has in [withOverlayGeometry], written once for both numbers.
 *
 * A slider on a clip with no keys sets the one level. Once the clip has keys,
 * the slider sets the level *at the playhead* - a key there, made or replaced -
 * because on a keyed clip the level is decided by the keys and writing the
 * static field would move the slider and not the sound or the picture. With the
 * playhead off the clip there is no moment to key, so every key moves by the
 * same amount instead, which is what a slider showing the nearer end implies.
 */

/** Which of a clip's two tracks an edit is about. */
enum class ValueTrack { Opacity, Volume }

fun Clip.valueKeys(track: ValueTrack): List<ValueKey> =
    if (track == ValueTrack.Opacity) opacityKeys else volumeKeys

fun Clip.staticValue(track: ValueTrack): Float =
    if (track == ValueTrack.Opacity) opacity else volume

/** The track's value at a moment of the timeline; off the clip, the nearer end. */
fun Clip.valueAt(track: ValueTrack, timelineMs: Long): Float {
    val local = (timelineMs - timelineStartMs).coerceIn(0L, durationMs)
    return valueKeys(track).valueAt(local, staticValue(track))
}

private fun Clip.withKeys(track: ValueTrack, keys: List<ValueKey>): Clip =
    if (track == ValueTrack.Opacity) copy(opacityKeys = keys) else copy(volumeKeys = keys)

private fun Clip.withStatic(track: ValueTrack, value: Float): Clip =
    if (track == ValueTrack.Opacity) copy(opacity = value) else copy(volume = value)

/**
 * The clip with [track] set to [value] at [playheadMs]: the one level with no
 * keys, a key at the playhead on the clip, or the whole track shifted when the
 * playhead is off it. [range] is what the number may be - an opacity is 0..1,
 * a sound's level may go above 1.
 */
fun Clip.withValueAt(
    track: ValueTrack,
    playheadMs: Long,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    toleranceMs: Long = KEY_TOLERANCE_MS
): Clip {
    val wanted = value.coerceIn(range.start, range.endInclusive)
    val keys = valueKeys(track)
    if (keys.isEmpty()) return withStatic(track, wanted)
    val local = playheadMs - timelineStartMs
    val now = valueAt(track, playheadMs)
    if (wanted == now) return this
    return if (local in 0L..durationMs) {
        withKeys(track, keys.upserted(ValueKey(local, wanted, keys.easingAt(local)), toleranceMs))
    } else {
        val delta = wanted - now
        withKeys(track, keys.map { it.copy(value = (it.value + delta).coerceIn(range.start, range.endInclusive)) })
    }
}

/**
 * A key at the playhead on [track], holding the value the clip has there - the
 * keyframe button. On a clip with no keys this is the first key, which pins
 * the current level; a second one elsewhere is what makes it move.
 */
fun Clip.withValueKeyAdded(track: ValueTrack, playheadMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Clip {
    val local = (playheadMs - timelineStartMs).coerceIn(0L, durationMs)
    val keys = valueKeys(track)
    val here = valueAt(track, playheadMs)
    return withKeys(track, keys.upserted(ValueKey(local, here, keys.easingAt(local)), toleranceMs))
}

/**
 * The key nearest the playhead taken off [track]. Taking off the last key leaves
 * the clip at the level it had there, so the picture or the sound does not jump
 * when the track goes.
 */
fun Clip.withValueKeyRemoved(track: ValueTrack, playheadMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Clip {
    val local = playheadMs - timelineStartMs
    val keys = valueKeys(track)
    if (!keys.hasKeyNear(local, toleranceMs)) return this
    val kept = keys.filterNot { kotlin.math.abs(it.atMs - local) <= toleranceMs }
    val settled = valueAt(track, playheadMs)
    return if (kept.isEmpty()) withKeys(track, kept).withStatic(track, settled) else withKeys(track, kept)
}

/** Every key off [track], the clip left at the level its first frame had. */
fun Clip.withValueKeysCleared(track: ValueTrack): Clip {
    if (valueKeys(track).isEmpty()) return this
    val settled = valueAt(track, timelineStartMs)
    return withKeys(track, emptyList()).withStatic(track, settled)
}

/** Whether a key sits under the playhead on [track] - what lights the keyframe button. */
fun Clip.hasValueKeyAt(track: ValueTrack, playheadMs: Long, toleranceMs: Long = KEY_TOLERANCE_MS): Boolean =
    valueKeys(track).hasKeyNear(playheadMs - timelineStartMs, toleranceMs)
