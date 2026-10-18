package com.squish.app.media

import android.graphics.ImageFormat
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageWriter
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Puts a software-decoded frame - three planes of 8-bit Y'CbCr - onto a
 * Surface as a YUV image, stamped with the time it is for.
 *
 * This is the bridge between FFmpeg and Media3's export pipeline. Transformer
 * hands a video decoder a Surface and expects frames to arrive on it each
 * carrying its presentation time and each the size of the picture. MediaCodec
 * does both itself; nothing else that writes to a Surface from Java does
 * either by default:
 *
 * - **The size.** The Surface's consumer is the frame processor's
 *   SurfaceTexture, and Transformer never sets its default buffer size (in
 *   1.11.1 `ExternalTextureManager.setInputFrameInfo` only does so for
 *   ExoPlayer's automatic re-registration), because MediaCodec sets the
 *   buffer geometry from the producer's side. An EGL window surface on that
 *   Surface therefore comes out 1x1 and stays 1x1: the first version of this
 *   drew through EGL, and a nine-minute conversion on the phone came out as
 *   one flat colour per frame, read back off the buffer as 1x1 (15 October).
 *   `ImageWriter.Builder.setWidthAndHeight` is the one public way to set the
 *   producer's size, which is why this needs API 33 and the software video
 *   path is not offered below it (SoftwareDecoders.decodes).
 * - **The time.** `Image.setTimestamp` is read by the consumer as the frame's
 *   presentation time, which is how the frame processor matches what it
 *   sees to the frames it was told to expect.
 *
 * The planes go in as YUV_420_888 with the DataSpace the frame was encoded
 * in (YuvDataSpace), and the GPU turns them to RGB when the frame processor
 * samples them - the same path a camera preview takes. One instance per
 * decoder, used from the thread the decoder is driven on.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class YuvImagePainter(private val surface: Surface, private val maxImages: Int = MAX_IMAGES) {

    private var writer: ImageWriter? = null
    private var width = 0
    private var height = 0
    private var choice: YuvDataSpace.Choice? = null

    /**
     * Queues one frame. [planes] and [strides] are the decoder's (Y, U, V),
     * each plane positioned at its start; [width] and [height] the picture
     * inside the strides. Throws when the writer refuses, which the caller
     * turns into a decoding failure.
     */
    fun paint(
        planes: Array<ByteBuffer>,
        strides: IntArray,
        width: Int,
        height: Int,
        choice: YuvDataSpace.Choice,
        presentationTimeUs: Long
    ) {
        val out = writer?.takeIf { this.width == width && this.height == height && this.choice == choice }
            ?: open(width, height, choice)
        val image = out.dequeueInputImage()
        try {
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            when (image.planes.size) {
                3 -> {
                    copy(planes[0], strides[0], width, height, image.planes[0])
                    copy(planes[1], strides[1], chromaWidth, chromaHeight, image.planes[1])
                    copy(planes[2], strides[2], chromaWidth, chromaHeight, image.planes[2])
                }
                // A writer that gives RGBA whatever was asked. The moto did
                // until setHardwareBufferFormat was added below (setImageFormat
                // alone left it at the surface's own RGBA_8888, format 1, one
                // plane); kept for a phone where that is still the answer.
                1 -> convertToRgba(planes, strides, width, height, choice, image.planes[0])
                else -> throw IllegalStateException(
                    "the image writer gave a format ${image.format} image with ${image.planes.size} plane(s)"
                )
            }
            image.timestamp = presentationTimeUs * 1000L
        } catch (e: Exception) {
            image.close()
            throw e
        }
        out.queueInputImage(image)
    }

    /**
     * Queues one frame of [width]x[height] that [fill] writes into the
     * dequeued image itself - three planes, or one RGBA plane on a writer
     * that gives only that - stamped [timestampNs]. Squish's FFmpeg writes
     * straight from its decoded frame this way (AvVideoOutput), with no copy
     * of its own in between.
     */
    fun paintWith(width: Int, height: Int, choice: YuvDataSpace.Choice, timestampNs: Long, fill: (Image) -> Unit) {
        val out = writer?.takeIf { this.width == width && this.height == height && this.choice == choice }
            ?: open(width, height, choice)
        val image = out.dequeueInputImage()
        try {
            fill(image)
            image.timestamp = timestampNs
        } catch (e: Exception) {
            image.close()
            throw e
        }
        out.queueInputImage(image)
    }

    private fun open(width: Int, height: Int, choice: YuvDataSpace.Choice): ImageWriter {
        writer?.close()
        val made = ImageWriter.Builder(surface)
            .setWidthAndHeight(width, height)
            .setImageFormat(ImageFormat.YUV_420_888)
            // Both, not the first alone: on the moto (Android 15) a builder
            // given only setImageFormat handed back RGBA_8888 images - the
            // surface's own format - and with the HAL format named as well it
            // gave YUV_420_888 (format 35, three planes). The log line below
            // says which this phone did.
            .setHardwareBufferFormat(HardwareBuffer.YCBCR_420_888)
            .setMaxImages(maxImages)
            .setDataSpace(dataSpace(choice))
            // No usage asked for: the writer's own is CPU writes and the
            // consumer adds the GPU's.
            .build()
        writer = made
        this.width = width
        this.height = height
        this.choice = choice
        rgbaRow = null
        android.util.Log.i("SquishYuv", "image writer ${width}x$height $choice format ${made.format}")
        return made
    }

    private var rgbaRow: IntArray? = null

    /**
     * The three planes into one RGBA plane, on the CPU, in 16.16 fixed point
     * with the matrix the frame was encoded in - the same arithmetic the GPU
     * would have done on a YUV image, for a writer that only gives RGBA.
     * A frame of 1080p is two million pixels; the decode this follows costs
     * more, so it is kept simple rather than threaded.
     */
    private fun convertToRgba(
        planes: Array<ByteBuffer>,
        strides: IntArray,
        width: Int,
        height: Int,
        choice: YuvDataSpace.Choice,
        dst: Image.Plane
    ) {
        check(dst.pixelStride == 4) { "an RGBA plane with a pixel stride of ${dst.pixelStride}" }
        val (kr, kb) = when (choice.standard) {
            YuvDataSpace.Standard.BT601 -> 0.299 to 0.114
            YuvDataSpace.Standard.BT709 -> 0.2126 to 0.0722
            YuvDataSpace.Standard.BT2020 -> 0.2627 to 0.0593
        }
        val kg = 1.0 - kr - kb
        val full = choice.range == YuvDataSpace.Range.FULL
        val yScale = if (full) 1.0 else 255.0 / 219.0
        val cScale = if (full) 1.0 else 255.0 / 224.0
        val yOffset = if (full) 0 else 16
        fun fixed(v: Double) = Math.round(v * 65536.0).toInt()
        val ys = fixed(yScale)
        val rv = fixed(2.0 * (1.0 - kr) * cScale)
        val gu = fixed(-2.0 * kb * (1.0 - kb) / kg * cScale)
        val gv = fixed(-2.0 * kr * (1.0 - kr) / kg * cScale)
        val bu = fixed(2.0 * (1.0 - kb) * cScale)
        val half = 1 shl 15

        val y = planes[0].duplicate()
        val u = planes[1].duplicate()
        val v = planes[2].duplicate()
        val yStride = strides[0]
        val uStride = strides[1]
        val vStride = strides[2]
        val out = dst.buffer.duplicate().order(java.nio.ByteOrder.nativeOrder())
        val rowStride = dst.rowStride
        val row = rgbaRow?.takeIf { it.size == width } ?: IntArray(width).also { rgbaRow = it }
        for (r in 0 until height) {
            val yBase = r * yStride
            val cBase = (r / 2)
            val uBase = cBase * uStride
            val vBase = cBase * vStride
            for (c in 0 until width) {
                val yy = ((y.get(yBase + c).toInt() and 255) - yOffset) * ys + half
                val uu = (u.get(uBase + (c shr 1)).toInt() and 255) - 128
                val vv = (v.get(vBase + (c shr 1)).toInt() and 255) - 128
                val red = clamp((yy + rv * vv) shr 16)
                val green = clamp((yy + gu * uu + gv * vv) shr 16)
                val blue = clamp((yy + bu * uu) shr 16)
                // Bytes R, G, B, A in memory, which little-endian reads as A<<24.
                row[c] = (0xFF shl 24) or (blue shl 16) or (green shl 8) or red
            }
            out.position(r * rowStride)
            out.asIntBuffer().put(row)
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * One plane into the image's, row by row - the two row strides differ,
     * and a chroma plane may be interleaved (a pixel stride of two, which is
     * how most phones lay 4:2:0 out), in which case it goes byte by byte.
     */
    private fun copy(src: ByteBuffer, srcStride: Int, columns: Int, rows: Int, dst: Image.Plane) {
        val from = src.duplicate()
        val into = dst.buffer
        val rowStride = dst.rowStride
        val pixelStride = dst.pixelStride
        if (pixelStride == 1) {
            for (r in 0 until rows) {
                from.limit(r * srcStride + columns)
                from.position(r * srcStride)
                into.position(r * rowStride)
                into.put(from)
            }
        } else {
            val row = ByteArray(columns)
            for (r in 0 until rows) {
                from.limit(r * srcStride + columns)
                from.position(r * srcStride)
                from.get(row, 0, columns)
                val base = r * rowStride
                for (c in 0 until columns) into.put(base + c * pixelStride, row[c])
            }
        }
    }

    private fun dataSpace(choice: YuvDataSpace.Choice): Int = DataSpace.pack(
        when (choice.standard) {
            YuvDataSpace.Standard.BT601 -> DataSpace.STANDARD_BT601_625
            YuvDataSpace.Standard.BT709 -> DataSpace.STANDARD_BT709
            YuvDataSpace.Standard.BT2020 -> DataSpace.STANDARD_BT2020
        },
        when (choice.transfer) {
            YuvDataSpace.Transfer.SDR -> DataSpace.TRANSFER_SMPTE_170M
            YuvDataSpace.Transfer.HLG -> DataSpace.TRANSFER_HLG
            YuvDataSpace.Transfer.PQ -> DataSpace.TRANSFER_ST2084
        },
        when (choice.range) {
            YuvDataSpace.Range.LIMITED -> DataSpace.RANGE_LIMITED
            YuvDataSpace.Range.FULL -> DataSpace.RANGE_FULL
        }
    )

    /** Lets the writer go. Safe without a frame ever queued, and more than once. */
    fun release() {
        writer?.close()
        writer = null
    }

    private companion object {
        /** Images in flight before dequeueing waits on the consumer. */
        const val MAX_IMAGES = 3
    }
}
