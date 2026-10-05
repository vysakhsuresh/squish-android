package com.squish.app.media

/**
 * Whether there is room for an export, apart from the measuring.
 *
 * Pure, so it is executed on the JVM (tools/jvm/SpaceCheckChecks.kt) - which is
 * the only way the edges of it get looked at, since the interesting readings
 * (a full volume, an unreadable one) cannot be arranged on a phone.
 */
object SpaceCheck {

    /**
     * Two copies of the estimate - the render and the gallery copy made from
     * it - with a tenth over each for an encoder that overshoots its bitrate.
     * It was 1.6, for one copy, while every export was written twice.
     */
    const val SPACE_HEADROOM = 2.2

    /** Below this even a short export is not worth starting. */
    const val MIN_SPACE_BYTES = 40L * 1_000_000

    /** What the volume must have free for an export of about [estimatedBytes]. */
    fun needed(estimatedBytes: Long): Long =
        (estimatedBytes * SPACE_HEADROOM).toLong().coerceAtLeast(MIN_SPACE_BYTES)

    /**
     * Whether to refuse the export. [free] is what the volume reports, with -1
     * for "could not be measured" - an unknown is let through, since refusing
     * every export on a phone whose StatFs throws would be worse than a late
     * failure on the few that genuinely have no room.
     *
     * A measured nothing is *not* an unknown, and this used to read
     * `free in 1 until needed`, which let 0 through beside the sentinel. Zero is
     * exactly what StatFs reports to an app on an ext4 or f2fs volume full down
     * to its root reserve, so the one case the check exists for was the one it
     * skipped: the render started and died in the muxer minutes later with
     * "Couldn't write the finished file", while a phone with a single byte free
     * was told plainly that there was no room.
     */
    fun isShort(free: Long, needed: Long): Boolean = free >= 0 && free < needed
}
