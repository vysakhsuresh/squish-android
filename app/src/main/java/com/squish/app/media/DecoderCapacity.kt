package com.squish.app.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * The phone's H.264 decoder throughput in 16 x 16 blocks a second, read from
 * the codec list (EDGE_PLAN C4) - the figure [DecoderBudget] budgets with.
 * H.264 because nearly everything a phone shoots or a proxy is written in is,
 * and the hardware decoder's figure is the one that runs out; a phone that
 * states none answers 0, and the old three rows stand.
 *
 * The block rate itself is not public, but the frame rate a size supports is
 * worked out from it: the moto's 489,600 blocks a second is its 60 fps at
 * 1920 x 1080 (8,160 blocks a frame), which is what this reads back.
 */
object DecoderCapacity {
    private const val FULL_HD_BLOCKS = 120L * 68

    val blocksPerSecond: Long by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.isHardwareAccelerated && !it.name.contains("secure", ignoreCase = true) }
                .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
                .maxOfOrNull { info ->
                    val video = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    val fps = runCatching { video.getSupportedFrameRatesFor(1920, 1080).upper }.getOrDefault(0.0)
                    (fps * FULL_HD_BLOCKS).toLong()
                } ?: 0L
        }.getOrDefault(0L)
    }
}
