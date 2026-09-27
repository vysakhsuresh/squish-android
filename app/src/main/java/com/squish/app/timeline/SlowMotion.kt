package com.squish.app.timeline

/**
 * What slowing a clip down actually does to its frame rate, and when that starts
 * to show.
 *
 * Slowing footage down does not create frames. Media3's speed change stretches
 * the timestamps it was given, so a thirty-frame second played at a quarter speed
 * is thirty frames spread over four seconds - seven and a half a second, each one
 * held on screen for a seventh of a second. That is the stepping: not a fault in
 * the renderer, but the arithmetic of having no more pictures than were shot.
 *
 * Every editor faces this and there are exactly three answers. Shoot faster, so
 * there are frames to spare. Blend neighbouring frames into each other, which
 * trades stepping for motion blur. Or invent the frames in between by working out
 * where everything moved - what CapCut calls smooth slow motion and everyone else
 * calls optical flow, and which is a great deal of work on a phone.
 *
 * The first of those costs nothing and is the one nobody is told about, so this
 * exists to say it: what the frame rate will be, whether that will show, and how
 * far the footage in hand can actually be slowed.
 */
object SlowMotion {

    /**
     * Below this, held frames are visible as steps rather than read as motion.
     *
     * Cinema has run at 24 for a century, so it is not an arbitrary line - it is
     * about where the eye stops integrating successive frames into movement and
     * starts seeing them arrive.
     */
    const val SMOOTH_FPS = 24f

    /** Below this it is not slow motion any more, it is a slideshow. */
    const val FLOOR_FPS = 12f

    /** How many frames a second the output carries at this speed. */
    fun effectiveFps(sourceFps: Float, speed: Float): Float {
        if (sourceFps <= 0f || speed <= 0f) return 0f
        return sourceFps * speed
    }

    /** Whether the result will read as movement rather than as a series of stills. */
    fun isSmooth(sourceFps: Float, speed: Float): Boolean =
        effectiveFps(sourceFps, speed) >= SMOOTH_FPS

    /**
     * The slowest this footage goes while still looking like motion.
     *
     * The honest number, and the useful one: at thirty frames a second it is
     * four-fifths speed, which is barely slow motion at all - and that is the
     * point. Shot at a hundred and twenty it is a fifth, which is the shot people
     * are imagining when they reach for the slider.
     */
    fun smoothestSpeed(sourceFps: Float): Float {
        if (sourceFps <= 0f) return 1f
        return (SMOOTH_FPS / sourceFps).coerceIn(SpeedRamp.MIN_SPEED, 1f)
    }

    /** What to shoot at to get this slow and still look like motion. */
    fun fpsNeededFor(speed: Float): Int {
        if (speed <= 0f) return 0
        return kotlin.math.ceil(SMOOTH_FPS / speed).toInt()
    }

    /** How the result of a speed will read, for a panel to say so plainly. */
    enum class Verdict { Smooth, Stepped, Slideshow }

    fun verdict(sourceFps: Float, speed: Float): Verdict {
        val fps = effectiveFps(sourceFps, speed)
        return when {
            fps >= SMOOTH_FPS -> Verdict.Smooth
            fps >= FLOOR_FPS -> Verdict.Stepped
            else -> Verdict.Slideshow
        }
    }

    /**
     * One line saying what this speed will look like and why.
     *
     * Names the number rather than hedging. "This may appear less smooth" tells
     * nobody anything; "8 fps - shot at 30, a quarter speed cannot be smoother"
     * tells them what happened and what would have prevented it.
     */
    fun advice(sourceFps: Float, speed: Float): String {
        if (sourceFps <= 0f) return ""
        val fps = effectiveFps(sourceFps, speed)
        val rounded = if (fps >= 10f) fps.toInt().toString() else "%.1f".format(fps)
        return when (verdict(sourceFps, speed)) {
            Verdict.Smooth -> "$rounded fps out — smooth"
            Verdict.Stepped ->
                "$rounded fps out — will step. ${fpsNeededFor(speed)} fps footage would not."
            Verdict.Slideshow ->
                "$rounded fps out — too few frames to read as motion. " +
                    "${fpsNeededFor(speed)} fps footage would carry it."
        }
    }
}
