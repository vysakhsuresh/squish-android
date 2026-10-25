import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.ReframeRules
import com.squish.app.media.video.TrackSample
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Auto-reframe's decisions (media/video/ReframeRules.kt): where a person mask
 * puts the aim, which reading wins, and whether a run found anything at all -
 * the last being the one that let the sheet say "The crop follows the
 * subject" over a track that was only the middle (24 October).
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- EDGE_PLAN F8: choosing among what the detectors saw. ---
    run {
        val person = ReframeRules.Seen(0.3f, 0.5f, 0.2f, 0.6f, 0.8f, "person")
        val chair = ReframeRules.Seen(0.7f, 0.6f, 0.4f, 0.5f, 0.9f, "chair")
        val pick = ReframeRules.pickSubject(listOf(chair, person), previous = null)
        check(pick != null && abs(pick[0] - 0.3f) < 1e-4f, "a bigger, surer chair was followed over a person: ${pick?.toList()}")
        // A dog is followed when there is no person, a car when there is no dog.
        val dog = ReframeRules.Seen(0.6f, 0.7f, 0.15f, 0.15f, 0.7f, "dog")
        val car = ReframeRules.Seen(0.2f, 0.5f, 0.5f, 0.3f, 0.9f, "car")
        check(ReframeRules.pickSubject(listOf(car, dog), null)?.get(0) == 0.6f, "a car was followed over a dog")
        check(ReframeRules.pickSubject(listOf(car, chair), null)?.get(0) == 0.2f, "a chair was followed over a car")
        // Two dogs alike: the one it was following, not the other.
        val left = ReframeRules.Seen(0.25f, 0.5f, 0.15f, 0.15f, 0.7f, "dog")
        val right = ReframeRules.Seen(0.75f, 0.5f, 0.16f, 0.16f, 0.72f, "dog")
        check(ReframeRules.pickSubject(listOf(right, left), previous = floatArrayOf(0.27f, 0.5f))?.get(0) == 0.25f, "the crop jumped to the other dog")
        // Guesses and nothing: nothing.
        check(ReframeRules.pickSubject(listOf(person.copy(score = 0.2f)), null) == null, "a 20% guess was followed")
        check(ReframeRules.pickSubject(emptyList(), null) == null, "nothing seen picked something")
        // An object ranks under a person found by the cut-out model and over motion.
        val aimed = ReframeRules.aim(null, null, motion = floatArrayOf(0.1f, 0.1f), thing = floatArrayOf(0.6f, 0.7f))
        check(aimed[0] == 0.6f && aimed[2] == ReframeRules.OBJECT, "an object did not outrank motion: ${aimed.toList()}")
        check(ReframeRules.aim(null, floatArrayOf(0.4f, 0.4f, 0.2f), motion = null, thing = floatArrayOf(0.6f, 0.7f))[2] == ReframeRules.PERSON, "an object outranked a person")
        // The faces' middle, weighted to the bigger.
        val faces = ReframeRules.faceCentre(listOf(ReframeRules.Seen(0.2f, 0.3f, 0.1f, 0.1f, 0.9f, "face"), ReframeRules.Seen(0.8f, 0.3f, 0.3f, 0.3f, 0.9f, "face")))
        check(faces != null && faces[0] > 0.7f, "the faces' middle is not weighted to the near one: ${faces?.toList()}")
    }

    // A person standing in the left third of a 64 x 36 frame.
    val w = 64
    val h = 36
    val mask = ByteArray(w * h)
    for (y in 8 until 34) for (x in 10 until 22) mask[y * w + x] = 230.toByte()
    val c = ReframeRules.maskCentre(mask, w, h)
    check(c != null, "a person a quarter of the frame tall was not found")
    if (c != null) {
        check(abs(c[0] - 16f / 64) < 0.02f, "the person's middle is at x ${c[0]}, not ${16f / 64}")
        check(abs(c[1] - 21f / 36) < 0.03f, "the person's middle is at y ${c[1]}, not ${21f / 36}")
        check(c[2] > 0.1f && c[2] < 0.2f, "coverage ${c[2]} for a 12 x 26 person in 64 x 36")
    }
    // Nothing, and a speck.
    check(ReframeRules.maskCentre(ByteArray(w * h), w, h) == null, "an empty mask was aimed at")
    val speck = ByteArray(w * h).also { it[5 * w + 60] = 255.toByte() }
    check(ReframeRules.maskCentre(speck, w, h) == null, "one cell of guessed person was aimed at")
    // Below the threshold is not a person.
    val faint = ByteArray(w * h) { 100 }
    check(ReframeRules.maskCentre(faint, w, h) == null, "a whole frame at 100/255 was taken for a person")
    check(ReframeRules.maskCentre(mask, w, h + 1) == null, "a mask shorter than its size was read past its end")

    // Which reading wins.
    val face = floatArrayOf(0.3f, 0.4f)
    val person = floatArrayOf(0.2f, 0.6f)
    val motion = floatArrayOf(0.8f, 0.5f)
    check(ReframeRules.aim(face, person, motion).contentEquals(floatArrayOf(0.3f, 0.4f, ReframeRules.FACE)), "a face did not win")
    check(ReframeRules.aim(null, person, motion).contentEquals(floatArrayOf(0.2f, 0.6f, ReframeRules.PERSON)), "a person did not beat motion")
    check(ReframeRules.aim(null, null, motion).contentEquals(floatArrayOf(0.8f, 0.5f, ReframeRules.MOTION)), "motion was not used")
    check(ReframeRules.aim(null, null, null).contentEquals(floatArrayOf(0.5f, 0.5f, ReframeRules.NOTHING)), "nothing did not hold the middle")
    check(ReframeRules.PERSON > ReframeRules.MOTION && ReframeRules.FACE > ReframeRules.PERSON, "the confidences are out of order")

    // Found or not: the claim on the sheet.
    val middle = MotionTrack(listOf(TrackSample(0, 0.5f, 0.5f, confidence = ReframeRules.NOTHING)))
    check(!ReframeRules.foundSubject(middle), "a track of only the middle counted as following something")
    check(!ReframeRules.foundSubject(null), "no track counted as following something")
    val seen = MotionTrack(listOf(
        TrackSample(0, 0.5f, 0.5f, confidence = ReframeRules.NOTHING),
        TrackSample(250, 0.3f, 0.5f, confidence = ReframeRules.PERSON)
    ))
    check(ReframeRules.foundSubject(seen), "a track with a person in it did not count")

    // Sampling: an eight-second clip at four a second is 32 samples a quarter second apart.
    check(ReframeRules.sampleStepMs(0, 8_000, 4f, 360) == 258L, "8 s at 4/s steps ${ReframeRules.sampleStepMs(0, 8_000, 4f, 360)} ms")
    check(ReframeRules.sampleStepMs(0, 600_000, 4f, 360) >= 1_600L, "ten minutes is not capped at 360 samples")
    check(ReframeRules.sampleStepMs(0, 0, 4f, 360) >= 1L, "an empty window stepped by nothing")

    if (problems.isEmpty()) println("ReframeRulesChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
