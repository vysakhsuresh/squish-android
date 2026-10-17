package com.squish.app.av

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The pictures Android's own decoders refuse, decoded here: camera RAW
 * through LibRaw (CR2, CR3, NEF, ARW, RAF, ORF, RW2, PEF, SRW, DNG and the
 * rest), SVG through AndroidSVG, and everything else FFmpeg reads - TIFF,
 * PSD, OpenEXR, JPEG XL, JPEG 2000, AVIF and HEIF where the phone has no
 * decoder, Targa, PCX, SGI, Sun raster, the Netpbm family, Radiance HDR,
 * QOI, DDS, DPX and more.
 *
 * Every caller tries the platform first (ImageDecoder applies the camera's
 * orientation tag and is what every photo went through before); this is the
 * fallback, and gives the same thing back: an upright ARGB_8888 bitmap,
 * scaled down to the size asked for and never up - except a drawing, which
 * has no size of its own and is drawn at the size asked.
 */
object AvImages {

    private val RAW_EXTENSIONS = setOf(
        "3fr", "ari", "arw", "bay", "braw", "cap", "cr2", "cr3", "crw", "dcr", "dcs", "dng", "drf", "eip", "erf",
        "fff", "gpr", "iiq", "k25", "kdc", "mdc", "mef", "mos", "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx",
        "pxn", "r3d", "raf", "raw", "rw2", "rwl", "rwz", "sr2", "srf", "srw", "x3f"
    )

    private val PICTURE_EXTENSIONS = RAW_EXTENSIONS + setOf(
        "jpg", "jpeg", "jpe", "jfif", "pjpeg", "png", "apng", "gif", "webp", "bmp", "dib", "heic", "heif", "hif",
        "avif", "avifs", "jxl", "tif", "tiff", "psd", "psb", "exr", "tga", "icb", "vda", "vst", "pcx", "sgi", "rgb",
        "rgba", "bw", "ras", "sun", "xbm", "xpm", "xwd", "ppm", "pgm", "pbm", "pam", "pnm", "pfm", "phm", "hdr",
        "qoi", "jp2", "j2k", "j2c", "jpx", "jpf", "jpm", "dds", "ico", "cur", "svg", "svgz", "wbmp", "dpx", "cin",
        "fits", "fit", "fts", "mpo", "jps", "pix", "vbn", "jls", "ljpg", "wdp", "jxr", "hdp"
    )

    private fun extension(context: Context, uri: Uri): String =
        AvFile.displayName(context, uri)?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /**
     * Whether [uri] is a picture rather than footage. By its MIME type where
     * the provider gives a useful one, else by its name: an EXR or a RAW from
     * a file manager often arrives typed "application/octet-stream".
     */
    fun isPicture(context: Context, uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type != null) {
            if (type.startsWith("image/")) return true
            if (type.startsWith("video/") || type.startsWith("audio/")) return false
        }
        return extension(context, uri) in PICTURE_EXTENSIONS
    }

    /** Whether Android's own decoders read [uri]: its header, at least. */
    fun platformReads(context: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        bounds.outWidth > 0 && bounds.outHeight > 0
    }.getOrDefault(false)

    /** The picture's size the way up it is seen, from whichever decoder reads it; null for none. */
    fun uprightSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val ext = extension(context, uri)
        if (isSvg(context, uri, ext)) return svgSize(context, uri)
        if (ext in RAW_EXTENSIONS) rawSize(context, uri)?.let { return it }
        ffmpegSize(context, uri)?.let { return it }
        return if (ext !in RAW_EXTENSIONS) rawSize(context, uri) else null
    }

    /**
     * [uri] decoded upright, fitted inside [maxLongSide] on its long side and
     * [maxShortSide] on its short one; null when nothing here reads it.
     */
    fun decode(context: Context, uri: Uri, maxLongSide: Int, maxShortSide: Int = Int.MAX_VALUE): Bitmap? {
        val ext = extension(context, uri)
        return runCatching {
            when {
                isSvg(context, uri, ext) -> decodeSvg(context, uri, maxLongSide, maxShortSide)
                ext in RAW_EXTENSIONS -> decodeRaw(context, uri, maxLongSide, maxShortSide)
                    ?: decodeFfmpeg(context, uri, maxLongSide, maxShortSide)
                else -> decodeFfmpeg(context, uri, maxLongSide, maxShortSide)
                    ?: decodeRaw(context, uri, maxLongSide, maxShortSide)
            }
        }.onFailure { android.util.Log.w("SquishAv", "could not decode the picture $uri", it) }.getOrNull()
    }

    /** A small picture for a list or a tile: a RAW's own embedded preview when it has one, else [decode]. */
    fun thumbnail(context: Context, uri: Uri, side: Int): Bitmap? {
        if (extension(context, uri) in RAW_EXTENSIONS) rawThumbnail(context, uri, side)?.let { return it }
        return decode(context, uri, side, side)
    }

    /**
     * A file the rest of the app reads as it reads any photo: [uri] itself
     * when Android's decoders take it, else the picture decoded here and
     * written under [dir] - a PNG where it has transparency, a JPEG otherwise
     * - at up to [maxLongSide]. Null when nothing reads it. What a photo
     * already becomes on its way into a project (StillClips keeps an upright
     * JPEG of every one), so nothing a person sees is a conversion.
     */
    fun readableFile(context: Context, uri: Uri, dir: File, name: String, maxLongSide: Int): Uri? {
        if (platformReads(context, uri)) return uri
        val bitmap = decode(context, uri, maxLongSide) ?: return null
        val png = bitmap.hasAlpha()
        val target = File(dir, "$name.src." + if (png) "png" else "jpg")
        val partial = File(target.absolutePath + ".part")
        return try {
            FileOutputStream(partial).use {
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
            }
            if (partial.length() > 0L && partial.renameTo(target)) Uri.fromFile(target) else null
        } catch (e: Exception) {
            android.util.Log.w("SquishAv", "could not keep a readable copy of $uri", e)
            null
        } finally {
            bitmap.recycle()
            partial.delete()
        }
    }

    // ---- sizing --------------------------------------------------------------

    /** The size [w]x[h] scales to inside both bounds, never up. */
    private fun fit(w: Int, h: Int, maxLong: Int, maxShort: Int): Pair<Int, Int> {
        val long = maxOf(w, h).toDouble()
        val short = minOf(w, h).toDouble()
        val scale = minOf(1.0, maxLong / long, maxShort / short)
        return Math.round(w * scale).toInt().coerceAtLeast(1) to Math.round(h * scale).toInt().coerceAtLeast(1)
    }

    private fun turned(bitmap: Bitmap, degrees: Int, mirror: Boolean = false): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0 && !mirror) return bitmap
        val m = Matrix().apply {
            if (mirror) postScale(-1f, 1f)
            postRotate(d.toFloat())
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    // ---- FFmpeg ----------------------------------------------------------------

    private fun ffmpegSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val info = AvFile.probe(context, uri) ?: return null
        val s = info.video ?: return null
        if (s.width <= 0 || s.height <= 0) return null
        val turn = (s.rotation + exifTurn(context, uri).first) % 180 != 0
        return if (turn) s.height to s.displayWidth else s.displayWidth to s.height
    }

    private fun decodeFfmpeg(context: Context, uri: Uri, maxLong: Int, maxShort: Int): Bitmap? {
        val file = AvFile.open(context, uri) ?: return null
        file.use {
            val s = file.info.video ?: return null
            val decoder = AvDecoder.forStream(file, s.index, threads = 2) ?: return null
            decoder.use {
                val frame = AvDecoder.Frame()
                val r = AvNative.fileDecodeNext(file.nativeHandle, decoder.nativeHandle, s.index, frame.meta)
                if (r != AvNative.OK || frame.width <= 0 || frame.height <= 0) return null
                val shownW = if (s.sarNum > 0 && s.sarDen > 0) Math.round(frame.width * s.sarNum.toDouble() / s.sarDen).toInt() else frame.width
                val (exifDegrees, mirror) = exifTurn(context, uri)
                val degrees = s.rotation + exifDegrees
                // The bounds are for the upright picture; the bitmap is drawn unturned.
                val quarter = degrees % 180 != 0
                val (w, h) = fit(shownW, frame.height, maxLong, maxShort).let { if (quarter) fit(frame.height, shownW, maxLong, maxShort).let { (a, b) -> b to a } else it }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                if (!decoder.toBitmap(bitmap)) {
                    bitmap.recycle()
                    return null
                }
                return turned(bitmap, degrees, mirror)
            }
        }
    }

    /**
     * The orientation tag where the file has one ExifInterface reads (a TIFF's
     * own, a PSD's or a camera's): the clockwise turn and whether it mirrors.
     */
    private fun exifTurn(context: Context, uri: Uri): Pair<Int, Boolean> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
                ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
                ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
                ExifInterface.ORIENTATION_TRANSPOSE -> 270 to true
                ExifInterface.ORIENTATION_TRANSVERSE -> 90 to true
                else -> 0 to false
            }
        }
    }.getOrNull() ?: (0 to false)

    // ---- RAW -------------------------------------------------------------------

    /** LibRaw's flip code as a clockwise turn: 3 half, 5 a quarter anticlockwise, 6 a quarter clockwise. */
    private fun flipDegrees(flip: Int): Int = when (flip) {
        3 -> 180
        5 -> 270
        6 -> 90
        else -> 0
    }

    private inline fun <T> withRaw(context: Context, uri: Uri, block: (Long) -> T?): T? {
        if (!AvNative.loaded) return null
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val raw = AvNative.rawOpen(pfd.detachFd())
        if (raw == 0L) return null
        return try {
            block(raw)
        } finally {
            AvNative.rawClose(raw)
        }
    }

    private fun rawSize(context: Context, uri: Uri): Pair<Int, Int>? = withRaw(context, uri) { raw ->
        val info = AvNative.rawInfo(raw)
        if (info[0] <= 0 || info[1] <= 0) null
        else if (flipDegrees(info[2]) % 180 != 0) info[1] to info[0] else info[0] to info[1]
    }

    private fun decodeRaw(context: Context, uri: Uri, maxLong: Int, maxShort: Int): Bitmap? = withRaw(context, uri) { raw ->
        val info = AvNative.rawInfo(raw)
        if (info[0] <= 0 || info[1] <= 0) return@withRaw null
        val degrees = flipDegrees(info[2])
        val quarter = degrees % 180 != 0
        // Half size whenever it still covers what is asked: no interpolation
        // at all, four times faster, and a 24 MP sensor at half is 3000 px.
        val half = run {
            val hw = info[0] / 2
            val hh = info[1] / 2
            val (fw, fh) = fit(info[0], info[1], maxLong, maxShort)
            hw >= fw && hh >= fh
        }
        val developed = AvNative.rawDevelopedSize(raw, half)
        val (w, h) = fit(developed[0], developed[1], maxLong, maxShort).let {
            if (quarter) fit(developed[1], developed[0], maxLong, maxShort).let { (a, b) -> b to a } else it
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = AvNative.rawDevelop(raw, bitmap, half)
        if (r != 0) {
            bitmap.recycle()
            android.util.Log.w("SquishAv", "LibRaw could not develop $uri ($r)")
            return@withRaw null
        }
        turned(bitmap, degrees)
    }

    private fun rawThumbnail(context: Context, uri: Uri, side: Int): Bitmap? = withRaw(context, uri) { raw ->
        val info = AvNative.rawInfo(raw)
        if (info[3] != 1) return@withRaw null
        val jpeg = AvNative.rawThumbJpeg(raw) ?: return@withRaw null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withRaw null
        // The embedded preview is stored the way the sensor is; LibRaw's flip turns it.
        turned(decoded, flipDegrees(info[2]))
    }

    // ---- SVG -------------------------------------------------------------------

    private fun isSvg(context: Context, uri: Uri, ext: String): Boolean {
        if (ext == "svg" || ext == "svgz") return true
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return type == "image/svg+xml"
    }

    private fun openSvg(context: Context, uri: Uri): com.caverock.androidsvg.SVG? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input: InputStream = BufferedInputStream(raw)
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            com.caverock.androidsvg.SVG.getFromInputStream(if (gzip) GZIPInputStream(input) else input)
        }
    }.getOrNull()

    /** A drawing's own size: its width and height, or its view box, or a square. */
    private fun svgNaturalSize(svg: com.caverock.androidsvg.SVG): Pair<Float, Float> {
        val w = svg.documentWidth
        val h = svg.documentHeight
        if (w > 0f && h > 0f) return w to h
        val box = svg.documentViewBox
        if (box != null && box.width() > 0f && box.height() > 0f) return box.width() to box.height()
        return 1024f to 1024f
    }

    private fun svgSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val svg = openSvg(context, uri) ?: return null
        val (w, h) = svgNaturalSize(svg)
        return Math.round(w) to Math.round(h)
    }

    /** Drawn at the size asked for, since a drawing has none of its own worth keeping to. */
    private fun decodeSvg(context: Context, uri: Uri, maxLong: Int, maxShort: Int): Bitmap? {
        val svg = openSvg(context, uri) ?: return null
        val (nw, nh) = svgNaturalSize(svg)
        val long = maxOf(nw, nh)
        val short = minOf(nw, nh)
        val scale = minOf(maxLong.coerceAtMost(SVG_MAX_SIDE) / long, maxShort.toFloat() / short)
        val w = Math.round(nw * scale).coerceAtLeast(1)
        val h = Math.round(nh * scale).coerceAtLeast(1)
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, nw, nh)
        svg.setDocumentWidth(w.toFloat())
        svg.setDocumentHeight(h.toFloat())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    /** The largest a drawing is drawn: what an export frame needs, and no more. */
    private const val SVG_MAX_SIDE = 3840
}
