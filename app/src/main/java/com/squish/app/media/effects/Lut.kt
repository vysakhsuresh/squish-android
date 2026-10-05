package com.squish.app.media.effects

/**
 * A colour lookup table read from a `.cube` file - the format every grading tool
 * writes and the thing a brand's own look actually arrives as.
 *
 * Held as a cube of [size]^3 entries, three floats each, in the order the format
 * writes them: red fastest, then green, then blue. Quantised to eight bits on
 * the way in because the texture the shader reads is eight bits, so the CPU copy
 * and the GPU are looking at the same numbers rather than numbers a fraction
 * apart - the same reason [ToneCurve] holds its table to a byte.
 */
data class Lut3D(val size: Int, val data: FloatArray) {

    init {
        require(size in MIN_SIZE..MAX_SIZE) { "a cube of $size is outside $MIN_SIZE..$MAX_SIZE" }
        require(data.size == size * size * size * 3) { "a cube of $size needs ${size * size * size * 3} numbers, got ${data.size}" }
    }

    /**
     * The cube at one colour, interpolated between the eight entries round it.
     *
     * The shader does the same thing with two bilinear texture reads and a mix;
     * both are trilinear over the same quantised numbers, so they land in the
     * same place.
     */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        val x = (r.coerceIn(0f, 1f)) * n
        val y = (g.coerceIn(0f, 1f)) * n
        val z = (b.coerceIn(0f, 1f)) * n
        val x0 = x.toInt().coerceIn(0, n); val x1 = (x0 + 1).coerceAtMost(n); val fx = x - x0
        val y0 = y.toInt().coerceIn(0, n); val y1 = (y0 + 1).coerceAtMost(n); val fy = y - y0
        val z0 = z.toInt().coerceIn(0, n); val z1 = (z0 + 1).coerceAtMost(n); val fz = z - z0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            val c00 = at(x0, y0, z0, c) * (1 - fx) + at(x1, y0, z0, c) * fx
            val c10 = at(x0, y1, z0, c) * (1 - fx) + at(x1, y1, z0, c) * fx
            val c01 = at(x0, y0, z1, c) * (1 - fx) + at(x1, y0, z1, c) * fx
            val c11 = at(x0, y1, z1, c) * (1 - fx) + at(x1, y1, z1, c) * fx
            val c0 = c00 * (1 - fy) + c10 * fy
            val c1 = c01 * (1 - fy) + c11 * fy
            out[c] = c0 * (1 - fz) + c1 * fz
        }
        return out
    }

    private fun at(x: Int, y: Int, z: Int, c: Int): Float =
        data[((z * size + y) * size + x) * 3 + c]

    /**
     * The cube flattened for ES2, which has no 3D texture: one tile per blue
     * slice, laid left to right, so the texture is [size]*[size] wide and
     * [size] tall. The shader reads two tiles and mixes between them.
     */
    fun atlas(): FloatArray {
        val out = FloatArray(size * size * size * 3)
        for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
            val src = ((z * size + y) * size + x) * 3
            val dst = (y * (size * size) + z * size + x) * 3
            out[dst] = data[src]; out[dst + 1] = data[src + 1]; out[dst + 2] = data[src + 2]
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Lut3D && other.size == size && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * size + data.contentHashCode()

    companion object {
        /** Cubes smaller than this say nothing; larger than this will not fit an ES2 texture comfortably. */
        const val MIN_SIZE = 2
        const val MAX_SIZE = 64

        /** A cube that changes nothing, for the identity check and for a failed read. */
        fun identity(size: Int = 17): Lut3D {
            val n = size - 1
            val data = FloatArray(size * size * size * 3)
            for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
                val i = ((z * size + y) * size + x) * 3
                data[i] = byte(x.toFloat() / n)
                data[i + 1] = byte(y.toFloat() / n)
                data[i + 2] = byte(z.toFloat() / n)
            }
            return Lut3D(size, data)
        }

        /**
         * Held to eight bits, as the texture the shader reads is: a cube built
         * here and the same cube read from a file have to be the same numbers,
         * or an identity LUT would not quite be an identity.
         */
        fun byte(v: Float): Float = Math.round(v.coerceIn(0f, 1f) * 255f) / 255f
    }
}

/**
 * The cubes this session has read, by the file name a draft stores.
 *
 * A LUT is a megabyte of numbers and a draft is a small JSON file, so the draft
 * keeps the name under `files/luts/` and the cube itself is read once and held
 * here - the same bargain fonts already make. A name with nothing against it is
 * a LUT whose file has gone, and the grade simply leaves the picture alone
 * rather than failing.
 */
object LutStore {
    private val cubes = java.util.concurrent.ConcurrentHashMap<String, Lut3D>()

    /** Where the files live, beside `files/fonts/` and for the same reason. */
    const val DIR = "luts"

    fun get(name: String?): Lut3D? = name?.let { cubes[it] }

    fun put(name: String, lut: Lut3D) { cubes[name] = lut }

    fun forget(name: String) { cubes.remove(name) }

    /** Every name read this session, for the sheet's list. */
    fun names(): List<String> = cubes.keys.sorted()

    /** Whether one is already read, so the loader can skip it. */
    fun has(name: String): Boolean = cubes.containsKey(name)

    /**
     * A name made unique against what is already read: two files both called
     * "Teal.cube" are two different looks and both have to be keepable.
     */
    fun freeName(display: String): String {
        val stem = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Look" }
        if (!has("$stem.cube")) return "$stem.cube"
        var n = 2
        while (has("$stem $n.cube")) n++
        return "$stem $n.cube"
    }
}

/**
 * Reading a `.cube` file.
 *
 * The format is plain text: a `LUT_3D_SIZE n` line, optional `DOMAIN_MIN` and
 * `DOMAIN_MAX`, comments beginning `#`, a `TITLE`, and then n^3 lines of three
 * numbers. A 1D table (`LUT_1D_SIZE`) is read as well and grown into a cube,
 * because plenty of camera-matching LUTs are 1D and refusing them would be
 * refusing a file that is perfectly usable.
 */
object CubeFile {

    /** What went wrong, for a message a person can act on. */
    class Problem(message: String) : Exception(message)

    fun parse(text: String): Lut3D {
        var size3 = 0
        var size1 = 0
        var domainMin = 0f
        var domainMax = 1f
        val values = ArrayList<Float>(4096)

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE)
            // Kotlin's no-argument `uppercase()` is locale-independent - it is
            // not Java's `toUpperCase()`, which takes the default locale and
            // would turn "lut_3d_size" into "LUT_3D_SİZE" on a Turkish phone
            // and match none of the keywords below. Three of the six have an i
            // in them, so if this is ever changed to `uppercase(someLocale)`
            // the import stops working in Turkish and Azerbaijani.
            // LutChecks parses a cube under five locales for that reason.
            when (parts[0].uppercase()) {
                "TITLE" -> Unit
                "LUT_3D_SIZE" -> size3 = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "LUT_1D_SIZE" -> size1 = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "DOMAIN_MIN" -> domainMin = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                "DOMAIN_MAX" -> domainMax = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> {
                    domainMin = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    domainMax = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
                }
                else -> {
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) { values += r; values += g; values += b }
                    }
                }
            }
        }

        val span = (domainMax - domainMin).takeIf { it > 1e-6f } ?: 1f
        fun scaled(v: Float) = byte(((v - domainMin) / span))

        if (size3 > 0) {
            if (size3 !in Lut3D.MIN_SIZE..Lut3D.MAX_SIZE) throw Problem("That LUT is $size3 across, which is outside the ${Lut3D.MIN_SIZE} to ${Lut3D.MAX_SIZE} this can use.")
            val need = size3 * size3 * size3 * 3
            if (values.size < need) throw Problem("That .cube says it is $size3 across but holds ${values.size / 3} of the ${need / 3} colours.")
            return Lut3D(size3, FloatArray(need) { scaled(values[it]) })
        }
        if (size1 > 0) {
            // A 1D table is *grown* into a cube, so its length is cubed before
            // anything is allocated: a camera-matching LUT of 1024 entries - an
            // ordinary file - asked for 1024³ × 3 floats, which overflows Int to
            // a negative and threw NegativeArraySizeException out of the import,
            // past the two exceptions LutFiles catches, and took the app down.
            // 256 and 512 died the same way on an OutOfMemoryError.
            //
            // A long 1D table is not more detail in the cube, only more samples
            // of the same three curves, so the cube is built at the size this
            // can use and the curves are read across it.
            if (size1 < 2) throw Problem("That .cube says it is $size1 long, which is not a table.")
            val need = size1 * 3
            if (values.size < need) throw Problem("That .cube says it is $size1 long but holds ${values.size / 3} of the $size1 entries.")
            val side = size1.coerceAtMost(Lut3D.MAX_SIZE)
            return fromCurves(side) { i, c ->
                // The entry of the file's own table that this step of the cube
                // lands on; with side == size1 this is i exactly.
                val at = if (side <= 1) 0 else (i.toLong() * (size1 - 1) / (side - 1)).toInt().coerceIn(0, size1 - 1)
                scaled(values[at * 3 + c])
            }
        }
        throw Problem("That file has no LUT_3D_SIZE or LUT_1D_SIZE line, so it is not a .cube.")
    }

    /**
     * A 1D table grown into a cube: each channel through its own curve, which is
     * exactly what a 1D LUT means.
     */
    private fun fromCurves(size: Int, value: (Int, Int) -> Float): Lut3D {
        val n = size - 1
        val data = FloatArray(size * size * size * 3)
        for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
            val i = ((z * size + y) * size + x) * 3
            data[i] = value(x, 0)
            data[i + 1] = value(y, 1)
            data[i + 2] = value(z, 2)
        }
        return Lut3D(size, data)
    }

    private fun byte(v: Float): Float = Lut3D.byte(v)

    private val WHITESPACE = Regex("[\\s,]+")
}
