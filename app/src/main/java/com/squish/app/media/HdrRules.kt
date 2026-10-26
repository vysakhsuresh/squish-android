package com.squish.app.media

/**
 * Which tone map an HDR picture goes through on its way to SDR (EDGE_PLAN B4),
 * kept free of the framework so tools/jvm/HdrRulesChecks.kt runs it.
 *
 * An HDR10 (PQ) file Squish's FFmpeg decodes - every 10-bit HEVC on a phone
 * with no Main 10 decoder, the moto among them - reached Media3 labelled PQ and
 * came out of its OpenGL tone map with the red, magenta and blue bars black
 * and the rest blown: in the light preview copy, so in the preview, and in the
 * file (27 Oct, read off the copy). FFmpeg's decoder has a tone map of its own
 * (ToneMap, the one the filmstrip's pictures are right through), so such a
 * file is tone-mapped there and handed on as SDR BT.709. HLG through the same
 * path stays with Media3's tone map, where it was seen right with the lift
 * fitted to it, and anything the phone's own decoders read is left alone.
 */
object HdrRules {

    /** One clip's picture, as MediaCompat's report has it. */
    data class Clip(val hdr: Boolean, val hlg: Boolean, val software: Boolean)

    enum class Mode {
        /** Keep HDR asked for: nothing is tone-mapped. */
        KEEP,
        /** Media3's OpenGL tone map, as before. */
        OPEN_GL,
        /** The decoder tone-maps (Transformer's MediaCodec mode): FFmpeg's own, for PQ. */
        DECODER
    }

    /** Whether [clip]'s frames are tone-mapped in Squish's FFmpeg decoder. */
    fun decoderToneMaps(clip: Clip): Boolean = clip.hdr && !clip.hlg && clip.software

    /**
     * The mode for an export or a copy of [clips]. A composition has one mode,
     * so the decoder's tone map is used only when every HDR clip in it is one
     * that wants it; with any other HDR clip beside them, Media3's, as before.
     */
    fun mode(clips: List<Clip>, keepHdr: Boolean): Mode {
        if (keepHdr) return Mode.KEEP
        val hdr = clips.filter { it.hdr }
        if (hdr.isEmpty()) return Mode.OPEN_GL
        return if (hdr.all { decoderToneMaps(it) }) Mode.DECODER else Mode.OPEN_GL
    }
}
