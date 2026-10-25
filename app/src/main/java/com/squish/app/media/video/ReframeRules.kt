package com.squish.app.media.video

/**
 * Where auto-reframe aims, decided from what one frame offered - kept apart
 * from the decoding and the detectors so it can be executed on the JVM
 * (tools/jvm/ReframeRulesChecks.kt).
 *
 * Reported on 24 October: "the auto-reframing is not working". On the phone,
 * an eight-second clip of a woman sitting left of centre came back as one
 * sample dead in the middle at the fallback confidence, and the sheet said
 * "The crop follows the subject". Three faults: every sample asked for the
 * nearest *keyframe*, which on a phone or WhatsApp clip is the same frame for
 * seconds, so all but one sample were skipped as repeats; the platform face
 * detector was handed a frame 256 px wide, where a face across a table is
 * twenty-odd pixels and is not found; and nothing between a face and motion
 * could see a person who sits still. A person found by the segmentation model
 * now ranks between the two, and a run that found nothing says so.
 */
object ReframeRules {

    /** A thing a detector found (EDGE_PLAN F8): its middle and size as fractions of the frame, how sure, and what. */
    data class Seen(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val label: String)

    /** How sure a thing the object detector found is, between a person and motion. */
    const val OBJECT = 0.7f

    /** A detection less sure than this is not followed. */
    const val MIN_SCORE = 0.4f

    /**
     * How much each kind of thing is worth following: people first, then
     * animals, then what moves on its own; furniture and food last - a chair
     * is in the frame, it is rarely what the frame is about.
     */
    fun interest(label: String): Float = when (label.lowercase()) {
        "person" -> 1f
        "dog", "cat", "bird", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe" -> 0.85f
        "car", "motorcycle", "bicycle", "bus", "truck", "train", "boat", "airplane", "skateboard", "surfboard", "sports ball", "kite", "frisbee" -> 0.35f
        else -> 0.2f
    }

    /**
     * Which of [seen] to follow, as (x, y): the surest, most interesting and
     * biggest - and, given where the crop was aiming ([previous]), the one
     * nearest it, so two dogs alike do not trade places frame to frame.
     */
    fun pickSubject(seen: List<Seen>, previous: FloatArray?): FloatArray? {
        val best = seen.filter { it.score >= MIN_SCORE }.maxByOrNull { s ->
            val near = previous?.let { p ->
                val dx = s.x - p[0]; val dy = s.y - p[1]
                kotlin.math.exp(-(dx * dx + dy * dy) / 0.05f)
            } ?: 1f
            // Size counts, but by its fourth root: a parked car filling half the frame is still not the dog.
            s.score * interest(s.label) * Math.pow((s.w * s.h).toDouble(), 0.25).toFloat() * (0.25f + near)
        } ?: return null
        return floatArrayOf(best.x, best.y)
    }

    /** The middle of the faces in view, weighted to the bigger (nearer) ones and aimed a little below the eyes; null for none. */
    fun faceCentre(faces: List<Seen>): FloatArray? {
        var sx = 0f; var sy = 0f; var total = 0f
        for (f in faces) {
            if (f.score < MIN_SCORE) continue
            val weight = f.w * f.h
            sx += f.x * weight; sy += f.y * weight; total += weight
        }
        return if (total <= 0f) null else floatArrayOf(sx / total, sy / total)
    }

    /** How sure each kind of reading is, which is what the smoothing weighs. */
    const val FACE = 1f
    const val PERSON = 0.8f
    const val MOTION = 0.6f
    /** Nothing found: the middle, held lightly. */
    const val NOTHING = 0.2f

    /** A person must cover at least this much of the frame to be aimed at - less is a speck the model guessed at. */
    const val MIN_PERSON_COVERAGE = 0.01f

    /**
     * The middle of the person in a [w] x [h] confidence mask (0..255 per
     * cell), weighted by confidence above [threshold], as (x, y, coverage)
     * in fractions; null when too little of the frame is a person.
     */
    fun maskCentre(mask: ByteArray, w: Int, h: Int, threshold: Int = 128): FloatArray? {
        if (w <= 0 || h <= 0 || mask.size < w * h) return null
        var sx = 0.0
        var sy = 0.0
        var total = 0.0
        var cells = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = mask[y * w + x].toInt() and 0xFF
                if (v < threshold) continue
                val weight = (v - threshold + 1).toDouble()
                sx += (x + 0.5) / w * weight
                sy += (y + 0.5) / h * weight
                total += weight
                cells++
            }
        }
        val coverage = cells.toFloat() / (w * h)
        if (total <= 0.0 || coverage < MIN_PERSON_COVERAGE) return null
        return floatArrayOf((sx / total).toFloat(), (sy / total).toFloat(), coverage)
    }

    /**
     * Where to aim for one frame: a face first, a person next, movement after
     * that, and the middle when there is none of them. Each reading is
     * (x, y) in fractions; the answer is (x, y, confidence).
     */
    fun aim(face: FloatArray?, person: FloatArray?, motion: FloatArray?, thing: FloatArray? = null): FloatArray = when {
        face != null -> floatArrayOf(face[0], face[1], FACE)
        person != null -> floatArrayOf(person[0], person[1], PERSON)
        thing != null -> floatArrayOf(thing[0], thing[1], OBJECT)
        motion != null -> floatArrayOf(motion[0], motion[1], MOTION)
        else -> floatArrayOf(0.5f, 0.5f, NOTHING)
    }

    /** Whether a finished track found anything to follow, or is only the middle held. */
    fun foundSubject(track: MotionTrack?): Boolean =
        track != null && track.samples.any { it.confidence > NOTHING + 0.01f }

    /** The sample times for [fromMs]..[toMs]: [perSecond] a second, at most [max], at least two. */
    fun sampleStepMs(fromMs: Long, toMs: Long, perSecond: Float, max: Int): Long {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val count = Math.round(span / 1000f * perSecond).coerceIn(2, max)
        return (span / (count - 1)).coerceAtLeast(1L)
    }
}
