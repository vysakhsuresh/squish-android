package com.squish.app.editor

import com.squish.app.timeline.Transform

/**
 * The moves people actually reach for, as a pair of endpoints across the clip.
 *
 * A pan always carries a little scale with it. Drifting a picture sideways at its
 * natural size would slide the frame edge into shot; the extra 12-15% is the
 * headroom the movement travels through, which is the same reason a camera
 * operator frames loose before a whip pan.
 */
enum class MotionPreset(val label: String, val hint: String) {
    PushIn("Push in", "Slowly closes on the subject"),
    PullOut("Pull out", "Opens up and reveals"),
    PanRight("Pan right", "Drifts across, left to right"),
    PanLeft("Pan left", "Drifts across, right to left"),
    RiseUp("Rise", "Lifts gently up the frame"),
    Settle("Settle", "Lands from a slight tilt");

    fun endpoints(): Pair<Transform, Transform> = when (this) {
        PushIn -> Transform(scale = 1f) to Transform(scale = 1.18f)
        PullOut -> Transform(scale = 1.18f) to Transform(scale = 1f)
        PanRight -> Transform(scale = 1.15f, offsetXFraction = -0.1f) to
            Transform(scale = 1.15f, offsetXFraction = 0.1f)
        PanLeft -> Transform(scale = 1.15f, offsetXFraction = 0.1f) to
            Transform(scale = 1.15f, offsetXFraction = -0.1f)
        RiseUp -> Transform(scale = 1.12f, offsetYFraction = 0.08f) to
            Transform(scale = 1.12f, offsetYFraction = -0.08f)
        Settle -> Transform(scale = 1.1f, rotationDegrees = -2.5f) to Transform(scale = 1f)
    }
}
