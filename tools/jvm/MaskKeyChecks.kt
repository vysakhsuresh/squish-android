import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskKey
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.hasShapeKeyNear
import com.squish.app.timeline.upserted
import com.squish.app.timeline.withShapeAt
import com.squish.app.timeline.withShapeKeyAdded
import com.squish.app.timeline.withShapeKeyRemoved
import kotlin.math.abs
import kotlin.system.exitProcess

// A mask's shape keyed: the other half of G5, and the half a track cannot do.
// A key holds a whole shape, so what has to hold is that the numbers mix, the
// shape and the mode do not, and nothing a key carries can carry keys of its
// own - a shape inside a shape inside a shape is a draft that never finishes
// writing.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, msg: String) { if (abs(a - b) > 1e-4f) problems += "$msg: $a vs $b" }

private fun circle(w: Float, x: Float = 0f) = Mask(shape = MaskShape.Ellipse, widthFraction = w, heightFraction = w, centerXFraction = x)

fun main() {
    // --- No keys: the shape itself, at every moment. -------------------------
    run {
        val plain = circle(0.6f)
        check(!plain.isKeyed, "an unkeyed mask said it was keyed")
        listOf(-1_000L, 0L, 500L, 10_000L).forEach {
            check(plain.at(it) === plain, "an unkeyed mask made a new shape at $it")
        }
    }

    // --- One key: that shape, everywhere. ------------------------------------
    run {
        val keyed = circle(0.6f).copy(keys = listOf(MaskKey(1_000, circle(0.2f))))
        check(keyed.isKeyed, "a keyed mask said it was not")
        listOf(0L, 1_000L, 9_000L).forEach {
            near(keyed.at(it).widthFraction, 0.2f, "one key did not hold at $it")
        }
    }

    // --- Two keys: held outside, mixed between. ------------------------------
    run {
        val keyed = circle(0.2f).copy(
            keys = listOf(
                MaskKey(1_000, circle(0.2f), KeyframeEasing.Linear),
                MaskKey(5_000, circle(1.0f), KeyframeEasing.Linear)
            )
        )
        near(keyed.at(0).widthFraction, 0.2f, "before the first key")
        near(keyed.at(1_000).widthFraction, 0.2f, "on the first key")
        near(keyed.at(3_000).widthFraction, 0.6f, "half way between")
        near(keyed.at(5_000).widthFraction, 1.0f, "on the last key")
        near(keyed.at(60_000).widthFraction, 1.0f, "after the last key")
        // Monotone between them - a shape that grows must not shrink on the way.
        var last = -1f
        for (t in 1_000..5_000 step 100) {
            val w = keyed.at(t.toLong()).widthFraction
            check(w >= last - 1e-5f, "the circle shrank at $t: $w after $last")
            last = w
        }
    }

    // --- Smooth easing starts and ends slowly, and still lands on its keys. ---
    run {
        val keyed = circle(0f).copy(
            keys = listOf(MaskKey(0, circle(0f), KeyframeEasing.Smooth), MaskKey(1_000, circle(1f)))
        )
        near(keyed.at(0).widthFraction, 0f, "smooth did not start on its key")
        near(keyed.at(1_000).widthFraction, 1f, "smooth did not land on its key")
        near(keyed.at(500).widthFraction, 0.5f, "smooth is not symmetric in the middle")
        check(keyed.at(100).widthFraction < 0.1f, "smooth did not start slowly")
        check(keyed.at(900).widthFraction > 0.9f, "smooth did not end slowly")
    }

    // --- Hold jumps rather than mixing. --------------------------------------
    run {
        val keyed = circle(0.2f).copy(
            keys = listOf(MaskKey(0, circle(0.2f), KeyframeEasing.Hold), MaskKey(1_000, circle(1f)))
        )
        near(keyed.at(999).widthFraction, 0.2f, "Hold mixed before its next key")
        near(keyed.at(1_000).widthFraction, 1f, "Hold did not jump on its next key")
    }

    // --- The shape, the mode and the inversion do not mix. -------------------
    run {
        val a = Mask(shape = MaskShape.Heart, mode = MaskMode.Blur, inverted = true)
        val b = Mask(shape = MaskShape.Star, mode = MaskMode.Cutout, inverted = false)
        val keyed = a.copy(keys = listOf(MaskKey(0, a, KeyframeEasing.Linear), MaskKey(1_000, b)))
        val middle = keyed.at(500)
        check(middle.shape == MaskShape.Heart, "the shape was mixed into a ${middle.shape}")
        check(middle.mode == MaskMode.Blur, "the mode changed half way")
        check(middle.inverted, "the inversion changed half way")
        check(keyed.at(1_000).shape == MaskShape.Star, "the second key's shape never arrived")
    }

    // --- A turn goes the short way round. ------------------------------------
    run {
        val keyed = Mask(rotationDegrees = 170f).copy(
            keys = listOf(
                MaskKey(0, Mask(rotationDegrees = 170f), KeyframeEasing.Linear),
                MaskKey(1_000, Mask(rotationDegrees = -170f))
            )
        )
        val middle = keyed.at(500).rotationDegrees
        // Twenty degrees forward through 180, not three hundred and forty back.
        check(abs(abs(middle) - 180f) < 1f, "a turn from 170 to -170 passed through $middle")
    }

    // --- A key never carries keys or a track of its own. ---------------------
    run {
        val keyed = circle(0.6f).copy(keys = listOf(MaskKey(0, circle(0.2f))))
        check(keyed.at(0).keys.isEmpty(), "the shape at a moment carried keys")
        check(keyed.keyed().keys.isEmpty(), "keyed() left keys on")
        val added = keyed.withShapeKeyAdded(2_000)
        check(added.keys.all { it.mask.keys.isEmpty() }, "a key was given keys of its own")
        check(added.keys.all { it.mask.track == null }, "a key was given a track of its own")
    }

    // --- The keyframe button: add, find, remove. -----------------------------
    run {
        val plain = circle(0.6f)
        val one = plain.withShapeKeyAdded(1_000)
        check(one.keys.size == 1, "the button made ${one.keys.size} keys")
        near(one.at(1_000).widthFraction, 0.6f, "the first key did not hold the shape it was made from")
        check(one.keys.hasShapeKeyNear(1_000), "the key it just made was not found")
        check(one.keys.hasShapeKeyNear(1_010), "a key 10 ms away was not found")
        check(!one.keys.hasShapeKeyNear(2_000), "a key a second away was found")

        val two = one.withShapeAt(4_000, circle(1.2f))
        check(two.keys.size == 2, "setting the shape elsewhere gave ${two.keys.size} keys")
        near(two.at(4_000).widthFraction, 1.2f, "the shape set at 4 s is not there")
        near(two.at(1_000).widthFraction, 0.6f, "setting one key moved another")

        // Replaced, not added, when one is already under the playhead.
        val again = two.withShapeAt(4_010, circle(0.3f))
        check(again.keys.size == 2, "a key 10 ms away was added rather than replaced")
        near(again.at(4_010).widthFraction, 0.3f, "the replacement did not take")

        val back = again.withShapeKeyRemoved(1_000)
        check(back.keys.size == 1, "removing a key left ${back.keys.size}")
        // The last one off leaves the shape it held, not the shape from before.
        val none = back.withShapeKeyRemoved(4_010)
        check(none.keys.isEmpty(), "the last key would not come off")
        near(none.widthFraction, 0.3f, "taking the last key off moved the picture")
        check(none.withShapeKeyRemoved(9_000) === none, "removing a key that is not there made a new mask")
    }

    // --- An unkeyed mask's slider sets the one shape, not a key. -------------
    run {
        val plain = circle(0.6f)
        val set = plain.withShapeAt(2_000, circle(0.9f))
        check(set.keys.isEmpty(), "setting the shape on an unkeyed mask made a key")
        near(set.widthFraction, 0.9f, "the one shape was not set")
    }

    // --- Keys stay sorted however they arrive. -------------------------------
    run {
        var keys = emptyList<MaskKey>()
        listOf(5_000L, 1_000L, 3_000L, 0L, 4_000L).forEach { keys = keys.upserted(MaskKey(it, circle(0.5f))) }
        check(keys.map { it.atMs } == keys.map { it.atMs }.sorted(), "the keys came out unsorted: ${keys.map { it.atMs }}")
        check(keys.size == 5, "five keys became ${keys.size}")
    }

    println("mask keys: a shape keyed, mixed and held")
    if (problems.isEmpty()) println("PASS - the numbers mix, the shape does not, and no key carries keys")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
