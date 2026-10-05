package com.squish.app.media

/**
 * Which of the backdrop stills on disk to let go of.
 *
 * Every one can be made again from the footage, so the folder is kept to a few
 * dozen - otherwise a session of trimming and reordering leaves a file for
 * every moment ever asked about. The rule is simply "the newest [keep] stay",
 * and the one that matters is [held]: while a plan is being built and played
 * out, nothing goes at all.
 *
 * That last part is the fault this was extracted for. Pruning ran after every
 * write, including the writes the export itself was making, so an export of a
 * padded canvas with more than [keep] stretches deleted the earliest of its
 * own backdrops out from under its own plan - the plan names each file and the
 * encoder opens it minutes later. The defence was a sixty-second grace on the
 * file's own age, which is a wall clock: it does not protect a still made for
 * the same export a minute earlier, nor one the preview made while scrubbing
 * before the export began. So the cap became a silent ceiling on how many
 * shots a blurred canvas could export.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/StillPruneChecks.kt).
 */
object StillPrune {

    /**
     * The names to delete, out of [stills] given as name to last-modified.
     * Nothing while [held]. Otherwise the oldest beyond [keep], and never
     * [keeping] (the file just written, which is wanted whatever its age).
     */
    fun victims(
        stills: List<Pair<String, Long>>,
        keep: Int,
        held: Boolean = false,
        keeping: String? = null
    ): List<String> {
        if (held) return emptyList()
        val candidates = stills.filter { it.first != keeping }
        if (candidates.size <= keep) return emptyList()
        return candidates
            // By age, then by name, so two files written in the same
            // millisecond are not ordered by whatever the directory listing
            // happened to say - a prune that is not a function of its input is
            // a prune that cannot be checked.
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(candidates.size - keep)
            .map { it.first }
    }
}
