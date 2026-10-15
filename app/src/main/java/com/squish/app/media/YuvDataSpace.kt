package com.squish.app.media

/**
 * Which colour space a software-decoded frame is handed to the export in.
 *
 * FFmpeg hands the app its frames as planar 8-bit 4:2:0 and says which
 * matrix they were encoded with (VideoDecoderOutputBuffer's colorspace, when
 * the stream said); the frames go onto the export's surface as YUV images
 * carrying an Android DataSpace, and the GPU turns them to RGB from that.
 * Say BT.601 for a BT.709 frame and every red carries a cast; say limited for
 * a full-range frame and black is grey; say SDR for an HLG frame and the
 * tone-mapper has nothing to map. So the choice is one pure function,
 * executed in tools/jvm/ConversionChecks.kt, and YuvImagePainter only
 * translates the answer into the platform's constants.
 */
object YuvDataSpace {

    enum class Standard { BT601, BT709, BT2020 }

    enum class Transfer { SDR, HLG, PQ }

    enum class Range { LIMITED, FULL }

    data class Choice(val standard: Standard, val transfer: Transfer, val range: Range)

    /**
     * [colorspace] is the decoder's word for the matrix - 1 for BT.601, 2 for
     * BT.709, 3 for BT.2020, anything else unknown - and [height] the
     * picture's, for the rule every player falls back on: HD and above is
     * 709, less is 601. An HDR [transfer] with no matrix said is BT.2020,
     * since that is the only matrix HLG and PQ are ever written in.
     */
    fun choose(colorspace: Int, height: Int, transfer: Transfer, fullRange: Boolean): Choice {
        val standard = when (colorspace) {
            1 -> Standard.BT601
            2 -> Standard.BT709
            3 -> Standard.BT2020
            else -> when {
                transfer != Transfer.SDR -> Standard.BT2020
                height >= 720 -> Standard.BT709
                else -> Standard.BT601
            }
        }
        return Choice(standard, transfer, if (fullRange) Range.FULL else Range.LIMITED)
    }
}
