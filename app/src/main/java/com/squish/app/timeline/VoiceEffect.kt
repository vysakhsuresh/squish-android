package com.squish.app.timeline

/**
 * A voice effect on one clip's own sound. Pitch effects shift the voice without
 * changing its timing; the rest are processed by VoiceProcessor.
 *
 * On the clip, not the edit: it was one setting for the whole project, applied
 * to every main-track shot and never to an added sound, so a B-roll shot added
 * later spoke like a robot and a voiceover recorded elsewhere could not be
 * changed at all. Here beside the clip's other fields so the timeline package
 * - which runs on the JVM without Compose - can carry it.
 */
enum class VoiceEffect(val label: String, val pitch: Float = 1f) {
    None("None"),
    Chipmunk("Chipmunk", pitch = 1.6f),
    Deep("Deep", pitch = 0.72f),
    Robot("Robot"),
    Echo("Echo"),
    Radio("Radio")
}
