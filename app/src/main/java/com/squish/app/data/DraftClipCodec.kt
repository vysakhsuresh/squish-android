package com.squish.app.data

import android.net.Uri
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRatio
import com.squish.app.editor.CropRect
import com.squish.app.editor.CropRules
import com.squish.app.media.effects.Adjust
import com.squish.app.media.effects.AdjustField
import com.squish.app.media.effects.HslBand
import com.squish.app.media.effects.HueBand
import com.squish.app.timeline.VoiceEffect
import com.squish.app.media.video.FrameMotion
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.StabilizerMeasurement
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.BackgroundFill
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipAnimation
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskKey
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.ReversedSource
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * A clip as JSON and back: everything that can be set on one shot, sound,
 * photo or overlay.
 *
 * Split out of [DraftCodec] for one reason, and it is the whole reason either
 * split happened: DraftCodec names the editor's own state types, and the file
 * they live in reaches Compose (EffectSpan carries an ImageVector and a
 * Colour), so it cannot be built off a phone. This half names nothing but the
 * timeline, the colour pipeline and the vision types, every one of which is
 * free of Android but for Uri - so it *can*, and
 * tools/jvm/DraftRoundTripChecks.kt encodes a clip with everything on it,
 * decodes it and compares the two, field by field.
 *
 * That is the only check that sees a field whose name and key are both present
 * and whose **value** does not survive. The names are covered by
 * tools/jvm/DraftFieldChecks.kt, which reads both files as text; it passed
 * every day a colour wheel was being clamped to half its range on reading.
 *
 * A clip is where nearly all of a draft's value surface is: its window, level,
 * fades, voice, beats, speed curve, transition, place, opacity, keys, mask,
 * key colour, background, grade, crop, reframe track, stabilizer measurement,
 * mirror and turns. What is *not* here is the edit-wide handful and the lines
 * of words, because TextOverlayItem is declared beside EditorUiState - see
 * docs/ROADMAP.md.
 */
internal object DraftClipCodec {

    internal fun encodeClip(clip: Clip): JSONObject = JSONObject().apply {
        put("id", clip.id)
        put("uri", clip.uri?.toString() ?: JSONObject.NULL)
        put("label", clip.label)
        put("sourceInMs", clip.sourceInMs)
        put("sourceOutMs", clip.sourceOutMs)
        put("timelineStartMs", clip.timelineStartMs)
        put("sourceDurationMs", clip.sourceDurationMs)
        putFinite("volume", clip.volume)
        if (clip.muted) put("muted", true)
        if (clip.fadeInMs > 0L) put("fadeInMs", clip.fadeInMs)
        if (clip.fadeOutMs > 0L) put("fadeOutMs", clip.fadeOutMs)
        if (clip.voice != VoiceEffect.None) put("voice", clip.voice.name)
        if (clip.beats.isNotEmpty()) put("beats", JSONArray().apply { clip.beats.forEach { put(it) } })
        put(
            "speedPoints",
            JSONArray().apply {
                clip.speedRamp.ordered.forEach { point ->
                    put(JSONObject().apply {
                        put("atMs", point.atMs)
                        putFinite("speed", point.speed)
                    })
                }
            }
        )
        put("transitionType", clip.transitionIn.type.name)
        put("transitionMs", clip.transitionIn.durationMs)
        put("layer", clip.layer)
        putFinite("opacity", clip.opacity)
        putFinite("scale", clip.scale)
        putFinite("offsetXFraction", clip.offsetXFraction)
        putFinite("offsetYFraction", clip.offsetYFraction)
        putFinite("rotation", clip.rotation)
        put("keyframes", JSONArray().apply { clip.keyframes.forEach { put(encodeKeyframe(it)) } })
        put("stabilizer", JSONArray().apply { clip.stabilizer.forEach { put(encodeKeyframe(it)) } })
        // The measurement as columns rather than an object per frame: a long
        // shot has thousands, and this is written on every autosave.
        clip.stabilizerMeasurement?.takeIf { !it.isEmpty }?.let { m ->
            put("stabilizerMeasurement", JSONObject().apply {
                put("width", m.analysisWidth)
                put("height", m.analysisHeight)
                put("times", JSONArray(m.timesMs))
                put("dx", JSONArray().apply { m.motions.forEach { putFinite(it.dx) } })
                put("dy", JSONArray().apply { m.motions.forEach { putFinite(it.dy) } })
                put("rotation", JSONArray().apply { m.motions.forEach { putFinite(it.rotationDegrees) } })
                put("confidence", JSONArray().apply { m.motions.forEach { putFinite(it.confidence) } })
            })
        }
        clip.stabilizeStrength?.let { putFinite("stabilizeStrength", it) }
        if (clip.opacityKeys.isNotEmpty()) put("opacityKeys", JSONArray().apply { clip.opacityKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.blend != com.squish.app.timeline.LayerBlend.Normal) put("blend", clip.blend.name)
        if (clip.lookKeys.isNotEmpty()) put("lookKeys", JSONArray().apply { clip.lookKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.volumeKeys.isNotEmpty()) put("volumeKeys", JSONArray().apply { clip.volumeKeys.forEach { put(encodeValueKey(it)) } })
        if (clip.arrival != ClipArrival.None) put("arrival", clip.arrival.name)
        if (clip.leaving != ClipLeaving.None) put("leaving", clip.leaving.name)
        if (clip.loop != ClipLoop.None) put("loop", clip.loop.name)
        put("arrivalMs", clip.arrivalMs)
        put("leavingMs", clip.leavingMs)
        put("loopMs", clip.loopMs)
        if (clip.frameBlend) put("frameBlend", true)
        if (clip.pitchFollowsSpeed) put("pitchFollowsSpeed", true)
        clip.mask?.let { m ->
            put("mask", JSONObject().apply {
                putMaskShape(m)
                // The shape keyed. Written only when it is, so a mask that
                // stands still reads exactly as it always did.
                if (m.keys.isNotEmpty()) {
                    put("keys", JSONArray().apply {
                        m.keys.forEach { key ->
                            put(JSONObject().apply {
                                put("atMs", key.atMs)
                                put("easing", key.easing.name)
                                putMaskShape(key.mask)
                            })
                        }
                    })
                }
                m.track?.let { t ->
                    put("track", JSONArray().apply {
                        t.samples.forEach { sample ->
                            put(JSONObject().apply {
                                put("atMs", sample.atMs)
                                putFinite("x", sample.xFraction)
                                putFinite("y", sample.yFraction)
                                putFinite("scale", sample.scale)
                                putFinite("confidence", sample.confidence)
                            })
                        }
                    })
                }
            })
        }
        clip.background?.let { bg ->
            put("background", JSONObject().apply {
                put("maskFile", bg.maskFile)
                put("fill", bg.fill.name)
                put("colorArgb", bg.colorArgb)
            })
        }
        clip.chromaKey?.let { key ->
            put("chromaKey", JSONObject().apply {
                put("keyColorArgb", key.keyColorArgb)
                putFinite("similarity", key.similarity)
                putFinite("smoothness", key.smoothness)
                putFinite("spill", key.spill)
            })
        }
        // Written only when set, so an older build reading the draft sees
        // nothing it does not know.
        if (clip.mirrored) put("mirrored", true)
        if (clip.quarterTurns != 0) put("quarterTurns", clip.quarterTurns)
        clip.reversedFrom?.let { from ->
            put("reversedFrom", JSONObject().apply {
                put("uri", from.uri?.toString() ?: JSONObject.NULL)
                put("sourceInMs", from.sourceInMs)
                put("sourceOutMs", from.sourceOutMs)
                put("durationMs", from.durationMs)
                // The person masks measured on the original, kept for Reverse
                // again (see Clip.reversed). Written as the clip's own are.
                from.background?.let { bg ->
                    put("background", JSONObject().apply {
                        put("maskFile", bg.maskFile)
                        put("fill", bg.fill.name)
                        put("colorArgb", bg.colorArgb)
                    })
                }
            })
        }
        // Each only when it means something, so a clip with none keeps the
        // document it had, and its edit key with it.
        clip.lookId?.let { put("lookId", it) }
        if (clip.lookIntensity != 1f) putFinite("lookIntensity", clip.lookIntensity)
        // worthKeeping, not !isIdentity: a LUT at strength 0 changes nothing
        // and is still a choice, and leaving it out lost the cube on reopen.
        if (clip.adjust.worthKeeping) put("adjust", encodeAdjust(clip.adjust))
        // A crop that only holds a shape chip - the window still the whole
        // frame - is kept too: the chip is what the next drag of a corner is
        // held to, and it read Free after a reload.
        clip.crop?.takeIf { !it.isIdentity || it.ratio != CropRatio.Free }?.let { crop ->
            put("crop", JSONObject().apply {
                putFinite("left", crop.rect.left)
                putFinite("top", crop.rect.top)
                putFinite("right", crop.rect.right)
                putFinite("bottom", crop.rect.bottom)
                putFinite("straighten", crop.straightenDegrees)
                put("flipH", crop.flipHorizontal)
                put("flipV", crop.flipVertical)
                put("ratio", crop.ratio.name)
            })
        }
        clip.reframe?.let { put("reframe", encodeTrack(it)) }
    }

    private fun encodeAdjust(adjust: Adjust): JSONObject = JSONObject().apply {
        AdjustField.entries.forEach { field ->
            val v = field.of(adjust)
            if (v != 0f) putFinite(field.name, v)
        }
        // The LUT by name only - the cube itself is a megabyte and lives under
        // files/luts/, read back by LutStore when the project opens.
        adjust.lutFile?.let {
            put("lut", it)
            putFinite("lutStrength", adjust.lutStrength)
        }
        // Only the channels that were drawn on, and only their points: a curve
        // is four straight lines until someone moves one.
        if (!adjust.curve.isIdentity) {
            put("curve", JSONObject().apply {
                listOf("m" to adjust.curve.master, "r" to adjust.curve.red, "g" to adjust.curve.green, "b" to adjust.curve.blue)
                    .forEach { (key, curve) ->
                        if (!curve.isIdentity) put(key, JSONArray().apply {
                            curve.points.forEach { p -> put(JSONArray().apply { putFinite(p.x); putFinite(p.y) }) }
                        })
                    }
            })
        }
        // The three wheels, written only when one has been moved - so a draft
        // of a graded shot that never touched them reads exactly as it did.
        if (!adjust.wheels.isIdentity) {
            put("wheels", JSONObject().apply {
                listOf("lift" to adjust.wheels.lift, "gamma" to adjust.wheels.gamma, "gain" to adjust.wheels.gain)
                    .forEach { (key, wheel) ->
                        if (!wheel.isIdentity) put(key, JSONArray().apply {
                            putFinite(wheel.r); putFinite(wheel.g); putFinite(wheel.b)
                        })
                    }
            })
        }
        if (adjust.hsl.any { !it.isIdentity }) {
            put("hsl", JSONArray().apply {
                adjust.hsl.forEach { band ->
                    put(JSONObject().apply {
                        putFinite("h", band.hue)
                        putFinite("s", band.saturation)
                        putFinite("l", band.luminance)
                    })
                }
            })
        }
    }

    private fun decodeAdjust(json: JSONObject?): Adjust {
        if (json == null) return Adjust.NONE
        var adjust = Adjust()
        AdjustField.entries.forEach { field ->
            if (json.has(field.name)) adjust = field.set(adjust, json.optDouble(field.name, 0.0).toFloat())
        }
        json.optString("lut").takeIf { it.isNotEmpty() }?.let { name ->
            adjust = adjust.copy(
                lutFile = name,
                lutStrength = json.optDouble("lutStrength", 1.0).toFloat().coerceIn(0f, 1f)
            )
        }
        json.optJSONObject("curve")?.let { c ->
            fun curveOf(key: String): com.squish.app.media.effects.Curve {
                val array = c.optJSONArray(key) ?: return com.squish.app.media.effects.Curve()
                val points = (0 until array.length()).mapNotNull { i ->
                    array.optJSONArray(i)?.let { p ->
                        com.squish.app.media.effects.CurvePoint(
                            p.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f),
                            p.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f)
                        )
                    }
                }
                // A curve written with fewer than two points cannot be drawn;
                // a straight one is the honest reading of it. Sorted on the way
                // in, so what is stored is canonical from the next save on -
                // everything that reads a curve reads it through Curve.ordered,
                // but a file is a file and this is where it stops mattering.
                return if (points.size >= 2) com.squish.app.media.effects.Curve(points.sortedBy { it.x })
                else com.squish.app.media.effects.Curve()
            }
            adjust = adjust.copy(curve = com.squish.app.media.effects.ToneCurve(
                master = curveOf("m"), red = curveOf("r"), green = curveOf("g"), blue = curveOf("b")
            ))
        }
        json.optJSONObject("wheels")?.let { w ->
            fun wheelOf(key: String): com.squish.app.media.effects.Wheel {
                val a = w.optJSONArray(key) ?: return com.squish.app.media.effects.Wheel.NONE
                // To Wheel.COMPONENT_REACH, not to 1. A wheel's component is
                // `master + the tint`, and the Level slider runs to 1 while the
                // dot reaches the rim, so Wheel.of produces components to 2 -
                // which the shader uses, scaled by LIFT_REACH and GAIN_REACH.
                // Clamping the read to 1 therefore made a strong wheel come
                // back weaker every time the project was opened, while the
                // draft on disk held the right number all along.
                val reach = com.squish.app.media.effects.Wheel.COMPONENT_REACH
                return com.squish.app.media.effects.Wheel(
                    a.optDouble(0, 0.0).toFloat().coerceIn(-reach, reach),
                    a.optDouble(1, 0.0).toFloat().coerceIn(-reach, reach),
                    a.optDouble(2, 0.0).toFloat().coerceIn(-reach, reach)
                )
            }
            adjust = adjust.copy(
                wheels = com.squish.app.media.effects.ColorWheels(
                    lift = wheelOf("lift"), gamma = wheelOf("gamma"), gain = wheelOf("gain")
                )
            )
        }
        val bands = json.optJSONArray("hsl")?.let { array ->
            List(HueBand.entries.size) { i ->
                array.optJSONObject(i)?.let { o ->
                    HslBand(
                        hue = o.optDouble("h", 0.0).toFloat().coerceIn(-1f, 1f),
                        saturation = o.optDouble("s", 0.0).toFloat().coerceIn(-1f, 1f),
                        luminance = o.optDouble("l", 0.0).toFloat().coerceIn(-1f, 1f)
                    )
                } ?: HslBand()
            }
        }
        return if (bands != null) adjust.copy(hsl = bands) else adjust
    }

    private fun encodeTrack(track: MotionTrack): JSONArray = JSONArray().apply {
        track.samples.forEach { s ->
            put(JSONObject().apply {
                put("atMs", s.atMs)
                putFinite("x", s.xFraction)
                putFinite("y", s.yFraction)
                putFinite("scale", s.scale)
                putFinite("confidence", s.confidence)
            })
        }
    }

    internal fun decodeTrack(array: JSONArray?): MotionTrack? {
        if (array == null) return null
        return MotionTrack(
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { o ->
                    TrackSample(
                        atMs = o.optLong("atMs"),
                        xFraction = o.optDouble("x", 0.5).toFloat(),
                        yFraction = o.optDouble("y", 0.5).toFloat(),
                        scale = o.optDouble("scale", 1.0).toFloat(),
                        confidence = o.optDouble("confidence", 1.0).toFloat()
                    )
                }
            }
        ).takeIf { !it.isEmpty }
    }

    private fun encodeKeyframe(key: Keyframe): JSONObject = JSONObject().apply {
        put("atMs", key.atMs)
        putFinite("scale", key.transform.scale)
        putFinite("offsetXFraction", key.transform.offsetXFraction)
        putFinite("offsetYFraction", key.transform.offsetYFraction)
        putFinite("rotationDegrees", key.transform.rotationDegrees)
        put("easing", key.easing.name)
    }

    private fun encodeValueKey(key: ValueKey): JSONObject = JSONObject().apply {
        put("atMs", key.atMs)
        putFinite("value", key.value)
        put("easing", key.easing.name)
    }

    private fun decodeValueKeys(array: JSONArray?): List<ValueKey> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ValueKey(
                atMs = o.optLong("atMs"),
                value = o.optDouble("value", 1.0).toFloat(),
                easing = enumOrNull<KeyframeEasing>(o.optString("easing")) ?: KeyframeEasing.Smooth
            )
        }.sortedBy { it.atMs }
    }

    private fun decodeMeasurement(json: JSONObject?): StabilizerMeasurement? {
        if (json == null) return null
        val times = json.optJSONArray("times") ?: return null
        val dx = json.optJSONArray("dx") ?: return null
        val dy = json.optJSONArray("dy") ?: return null
        val rotation = json.optJSONArray("rotation") ?: return null
        val confidence = json.optJSONArray("confidence") ?: return null
        val n = minOf(times.length(), dx.length(), dy.length(), rotation.length(), confidence.length())
        val measurement = StabilizerMeasurement(
            analysisWidth = json.optInt("width"),
            analysisHeight = json.optInt("height"),
            timesMs = (0 until n).map { times.optLong(it) },
            motions = (0 until n).map {
                FrameMotion(
                    dx = dx.optDouble(it, 0.0).toFloat(),
                    dy = dy.optDouble(it, 0.0).toFloat(),
                    rotationDegrees = rotation.optDouble(it, 0.0).toFloat(),
                    confidence = confidence.optDouble(it, 1.0).toFloat()
                )
            }
        )
        return measurement.takeIf { !it.isEmpty }
    }

    /**
     * A clip's speed curve.
     *
     * Falls back to the single "speed" number a project saved before ramps existed
     * would carry, read as a flat curve. A recovery offer that silently dropped
     * someone's speed change would be worse than not offering one.
     */
    private fun decodeRamp(json: JSONObject): SpeedRamp {
        val array = json.optJSONArray("speedPoints")
        if (array != null && array.length() > 0) {
            val points = (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                SpeedPoint(entry.optLong("atMs"), entry.optDouble("speed", 1.0).toFloat())
            }
            if (points.isNotEmpty()) return SpeedRamp(points)
        }
        val legacy = json.optDouble("speed", 1.0).toFloat()
        return if (legacy == 1f) SpeedRamp() else SpeedRamp.flat(legacy)
    }

    internal fun decodeClip(json: JSONObject?, kind: ClipKind): Clip? {
        if (json == null) return null
        // In order and never negative: optLong answers zero for a key that is
        // not there, so a half-written object can hand back an out-point before
        // its in-point, and everything that clamps a moment into the window
        // calls coerceIn(in, out), which throws on an inverted range rather
        // than returning anything. See ProjectRules.window.
        val (readInMs, readOutMs) = ProjectRules.window(
            json.optLong("sourceInMs"),
            json.optLong("sourceOutMs")
        )
        return Clip(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            kind = kind,
            uri = json.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
            label = json.optString("label", "Clip"),
            sourceInMs = readInMs,
            sourceOutMs = readOutMs,
            timelineStartMs = json.optLong("timelineStartMs"),
            sourceDurationMs = json.optLong("sourceDurationMs"),
            volume = json.optDouble("volume", 1.0).toFloat(),
            muted = json.optBoolean("muted", false),
            fadeInMs = json.optLong("fadeInMs").coerceAtLeast(0L),
            fadeOutMs = json.optLong("fadeOutMs").coerceAtLeast(0L),
            voice = enumOrNull<VoiceEffect>(json.optString("voice")) ?: VoiceEffect.None,
            beats = json.optJSONArray("beats")?.let { array ->
                (0 until array.length()).map { i -> array.optLong(i) }
            }.orEmpty().sorted(),
            speedRamp = decodeRamp(json),
            transitionIn = Transition(
                type = enumOrNull<TransitionType>(json.optString("transitionType")) ?: TransitionType.None,
                durationMs = json.optLong("transitionMs", 500L)
            ),
            layer = json.optInt("layer"),
            opacity = json.optDouble("opacity", 1.0).toFloat(),
            scale = json.optDouble("scale", 1.0).toFloat(),
            offsetXFraction = json.optDouble("offsetXFraction", 0.0).toFloat(),
            offsetYFraction = json.optDouble("offsetYFraction", 0.0).toFloat(),
            rotation = json.optDouble("rotation", 0.0).toFloat(),
            // Sorted on the way in: evaluation on the render thread trusts the
            // order and does not sort, so a hand-edited file cannot break it.
            keyframes = json.optJSONArray("keyframes")?.let { array ->
                (0 until array.length()).mapNotNull { i -> decodeKeyframe(array.optJSONObject(i)) }
            }.orEmpty().sortedBy { it.atMs },
            stabilizer = json.optJSONArray("stabilizer")?.let { array ->
                (0 until array.length()).mapNotNull { i -> decodeKeyframe(array.optJSONObject(i)) }
            }.orEmpty().sortedBy { it.atMs },
            stabilizerMeasurement = decodeMeasurement(json.optJSONObject("stabilizerMeasurement")),
            // Absent on a draft from before the strength was the clip's: the
            // edit's one strength then, which the sheet falls back to.
            stabilizeStrength = if (json.has("stabilizeStrength")) json.optDouble("stabilizeStrength", 0.5).toFloat().coerceIn(0f, 1f) else null,
            opacityKeys = decodeValueKeys(json.optJSONArray("opacityKeys")),
            blend = runCatching { com.squish.app.timeline.LayerBlend.valueOf(json.optString("blend", "Normal")) }.getOrDefault(com.squish.app.timeline.LayerBlend.Normal),
            lookKeys = decodeValueKeys(json.optJSONArray("lookKeys")),
            volumeKeys = decodeValueKeys(json.optJSONArray("volumeKeys")),
            arrival = enumOrNull<ClipArrival>(json.optString("arrival")) ?: ClipArrival.None,
            leaving = enumOrNull<ClipLeaving>(json.optString("leaving")) ?: ClipLeaving.None,
            loop = enumOrNull<ClipLoop>(json.optString("loop")) ?: ClipLoop.None,
            arrivalMs = json.optLong("arrivalMs", ClipAnimation.DEFAULT_IN_MS),
            leavingMs = json.optLong("leavingMs", ClipAnimation.DEFAULT_OUT_MS),
            loopMs = json.optLong("loopMs", ClipAnimation.DEFAULT_LOOP_MS),
            frameBlend = json.optBoolean("frameBlend", false),
            pitchFollowsSpeed = json.optBoolean("pitchFollowsSpeed", false),
            background = json.optJSONObject("background")?.let(::decodeBackground),
            chromaKey = json.optJSONObject("chromaKey")?.let { k ->
                ChromaKey(
                    keyColorArgb = k.optInt("keyColorArgb", ChromaKey.STANDARD_GREEN),
                    similarity = k.optDouble("similarity", 0.38).toFloat(),
                    smoothness = k.optDouble("smoothness", 0.1).toFloat(),
                    spill = k.optDouble("spill", 0.12).toFloat()
                )
            },
            lookId = json.optString("lookId").takeIf { it.isNotBlank() && it != "null" },
            lookIntensity = json.optDouble("lookIntensity", 1.0).toFloat().coerceIn(0f, 1f),
            adjust = decodeAdjust(json.optJSONObject("adjust")),
            crop = json.optJSONObject("crop")?.let { c ->
                ClipCrop(
                    rect = CropRect.of(
                        left = c.optDouble("left", 0.0).toFloat(),
                        top = c.optDouble("top", 0.0).toFloat(),
                        right = c.optDouble("right", 1.0).toFloat(),
                        bottom = c.optDouble("bottom", 1.0).toFloat()
                    ),
                    straightenDegrees = c.optDouble("straighten", 0.0).toFloat()
                        .coerceIn(-CropRules.MAX_STRAIGHTEN_DEGREES, CropRules.MAX_STRAIGHTEN_DEGREES),
                    flipHorizontal = c.optBoolean("flipH", false),
                    flipVertical = c.optBoolean("flipV", false),
                    ratio = enumOrNull<CropRatio>(c.optString("ratio")) ?: CropRatio.Free
                ).takeIf { !it.isIdentity || it.ratio != CropRatio.Free }
            },
            reframe = decodeTrack(json.optJSONArray("reframe")),
            mask = json.optJSONObject("mask")?.let { m ->
                maskShapeOf(m).copy(
                    keys = m.optJSONArray("keys")?.let { array ->
                        (0 until array.length()).mapNotNull { i ->
                            array.optJSONObject(i)?.let { k ->
                                MaskKey(
                                    atMs = k.optLong("atMs"),
                                    mask = maskShapeOf(k),
                                    easing = enumOrNull<KeyframeEasing>(k.optString("easing")) ?: KeyframeEasing.Smooth
                                )
                            }
                        }.sortedBy { it.atMs }
                    }.orEmpty(),
                    track = m.optJSONArray("track")?.let { array ->
                        MotionTrack(
                            (0 until array.length()).mapNotNull { i ->
                                array.optJSONObject(i)?.let { o ->
                                    TrackSample(
                                        atMs = o.optLong("atMs"),
                                        xFraction = o.optDouble("x", 0.5).toFloat(),
                                        yFraction = o.optDouble("y", 0.5).toFloat(),
                                        scale = o.optDouble("scale", 1.0).toFloat(),
                                        confidence = o.optDouble("confidence", 1.0).toFloat()
                                    )
                                }
                            }
                        ).takeIf { !it.isEmpty }
                    }
                )
            },
            mirrored = json.optBoolean("mirrored"),
            quarterTurns = (json.optInt("quarterTurns") % 4 + 4) % 4,
            reversedFrom = json.optJSONObject("reversedFrom")?.let { r ->
                // The window Reverse again puts back, read the same way: in
                // order and never negative (ProjectRules.window).
                val (wasInMs, wasOutMs) = ProjectRules.window(
                    r.optLong("sourceInMs"),
                    r.optLong("sourceOutMs")
                )
                ReversedSource(
                    uri = r.optString("uri").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
                    sourceInMs = wasInMs,
                    sourceOutMs = wasOutMs,
                    durationMs = r.optLong("durationMs"),
                    background = r.optJSONObject("background")?.let(::decodeBackground)
                )
            }
        )
    }

    private fun decodeBackground(b: JSONObject): BackgroundRemoval? =
        b.optString("maskFile").takeIf { it.isNotBlank() }?.let { path ->
            BackgroundRemoval(
                maskFile = path,
                fill = enumOrNull<BackgroundFill>(b.optString("fill")) ?: BackgroundFill.Blur,
                colorArgb = b.optInt("colorArgb", 0xFF101828.toInt())
            )
        }

    private fun decodeKeyframe(json: JSONObject?): Keyframe? {
        if (json == null) return null
        return Keyframe(
            atMs = json.optLong("atMs"),
            transform = Transform(
                scale = json.optDouble("scale", 1.0).toFloat(),
                offsetXFraction = json.optDouble("offsetXFraction", 0.0).toFloat(),
                offsetYFraction = json.optDouble("offsetYFraction", 0.0).toFloat(),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat()
            ),
            easing = enumOrNull<KeyframeEasing>(json.optString("easing")) ?: KeyframeEasing.Smooth
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    /**
     * A mask's shape, apart from what it is following and the keys it moves on.
     *
     * Written once for the mask itself and once per shape key, so the two can
     * never drift: a field added to one and not the other would read back as
     * its default on every key, which is a mask that snaps between shapes.
     */
    private fun JSONObject.putMaskShape(m: Mask) {
        put("shape", m.shape.name)
        putFinite("centerXFraction", m.centerXFraction)
        putFinite("centerYFraction", m.centerYFraction)
        putFinite("widthFraction", m.widthFraction)
        putFinite("heightFraction", m.heightFraction)
        putFinite("rotationDegrees", m.rotationDegrees)
        putFinite("feather", m.feather)
        putFinite("cornerRadius", m.cornerRadius)
        put("inverted", m.inverted)
        put("mode", m.mode.name)
        putFinite("strength", m.strength)
    }

    private fun maskShapeOf(m: JSONObject): Mask = Mask(
        shape = enumOrNull<MaskShape>(m.optString("shape")) ?: MaskShape.Ellipse,
        centerXFraction = m.optDouble("centerXFraction", 0.0).toFloat(),
        centerYFraction = m.optDouble("centerYFraction", 0.0).toFloat(),
        widthFraction = m.optDouble("widthFraction", 0.6).toFloat(),
        heightFraction = m.optDouble("heightFraction", 0.6).toFloat(),
        rotationDegrees = m.optDouble("rotationDegrees", 0.0).toFloat(),
        feather = m.optDouble("feather", 0.04).toFloat(),
        cornerRadius = m.optDouble("cornerRadius", 0.0).toFloat(),
        inverted = m.optBoolean("inverted"),
        mode = enumOrNull<MaskMode>(m.optString("mode")) ?: MaskMode.Cutout,
        strength = m.optDouble("strength", 0.5).toFloat()
    )
}
