package com.squish.app.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Numbers into a draft, where a draft cannot hold every number.
 *
 * JSON has no NaN and no infinity, and `JSONObject.put(String, double)` throws
 * on one rather than writing something nothing could read back. That throw used
 * to come out of `DraftCodec.encode`, which `ProjectAutosave.save` calls
 * *before* the try that guards the write - so one non-finite number anywhere in
 * an edit took the whole save with it, and every save after it for as long as
 * the number was there, with nothing on screen to say so. The app's third
 * promise is that no edit is ever lost; a codec that can throw on a number
 * cannot keep it.
 *
 * And a NaN reaching a clip is not hypothetical. Gesture arithmetic divides by
 * a measured width and a width is zero for one frame on every layout, which is
 * why `CropRect.of` answers the same hazard for a crop window - it is just that
 * a crop is one of twenty places a fraction is set.
 *
 * The two shapes differ on purpose:
 *
 *  - On an object, the key is **left out**, so the value reads back as whatever
 *    default the decoder already declares for it (`optDouble("scale", 1.0)`,
 *    `optDouble("offsetXFraction")`). Nothing has to be invented here and
 *    nothing can disagree with the other side.
 *  - In an array, a zero is **written**, because a dropped element shifts every
 *    one after it: the stabilizer's measurement is four parallel columns, a
 *    curve point is [x, y] and a colour wheel is [r, g, b].
 */

/** A float onto an object, or nothing at all - see this file's note. */
internal fun JSONObject.putFinite(name: String, value: Float): JSONObject {
    if (value.isFinite()) put(name, value.toDouble())
    return this
}

/** A float into an array, or a zero in its place - see this file's note. */
internal fun JSONArray.putFinite(value: Float): JSONArray =
    put(if (value.isFinite()) value.toDouble() else 0.0)
