package simplemodelview

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Lossless byte compression (deflate on Android); the codec below only decides what is worth storing. */
interface Packer {
    fun pack(raw: ByteArray): ByteArray
    fun unpack(packed: ByteArray, rawSize: Int): ByteArray
}

/**
 * One finished UTC day of multi-sensor rainfall for the whole of CONUS, kept small: the grid is cut into strips of rows, and each strip is a quadtree.
 * A 16 x 16 block whose cells are all within the tolerance of one another (0.5 mm or 5 %) is stored as its one mean (to 0.1 mm); a block that is not is
 * split in four, down to single cells. Flat areas (no rain, even light rain) cost almost nothing, and the structure of the heavy rain keeps its
 * full 1 km detail. A day of about 3 MB on the server (a 24 h file) comes to about 1 MB, and is never downloaded again.
 */
class RainDay(
    val columns: Int, val rows: Int,
    /** Where the grid is, as the core reported the decoded window: the centers of the north-west cell, and the cell sizes (degrees). */
    val northLat: Double, val westLon: Double, val dLat: Double, val dLon: Double,
    val strips: List<Strip>,
) {
    /** [rows] file rows from [firstRow] (counted from the north); [bytes] is the strip's coded tile. */
    class Strip(val firstRow: Int, val rows: Int, val bytes: ByteArray)

    fun toBytes(): ByteArray {
        val w = ByteSink()
        w.int(MAGIC); w.int(columns); w.int(rows); w.double(northLat); w.double(westLon); w.double(dLat); w.double(dLon); w.int(strips.size)
        for (s in strips) { w.int(s.firstRow); w.int(s.rows); w.int(s.bytes.size); w.bytes(s.bytes) }
        return w.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x52443031   // "RD01"

        fun fromBytes(data: ByteArray): RainDay {
            val r = ByteSource(data)
            require(r.int() == MAGIC) { "not a rain day" }
            val columns = r.int(); val rows = r.int()
            val north = r.double(); val west = r.double(); val dLat = r.double(); val dLon = r.double()
            val n = r.int()
            val strips = List(n) { val first = r.int(); val h = r.int(); val len = r.int(); Strip(first, h, r.bytes(len)) }
            return RainDay(columns, rows, north, west, dLat, dLon, strips)
        }
    }
}

/** A decoded part of a day, as the core's decoder would have returned it. */
class DayWindow(val values: FloatArray, val columns: Int, val rows: Int, val southLat: Double, val westLon: Double, val dLat: Double, val dLon: Double)

object RainDayCodec {
    const val BLOCK = 16
    const val QUANT_MM = 0.1f
    const val TOLERANCE_MM = 0.5f
    const val TOLERANCE_FRACTION = 0.05f
    private const val MISSING = 65535
    private val levels = intArrayOf(16, 8, 4, 2)

    /** One strip's values ([columns] x [rows], row 0 = its south row; NaN = missing) as the coded tile. */
    fun encodeTile(values: FloatArray, columns: Int, rows: Int, packer: Packer): ByteArray {
        val pc = (columns + BLOCK - 1) / BLOCK * BLOCK
        val pr = (rows + BLOCK - 1) / BLOCK * BLOCK
        val cells = FloatArray(pc * pr) { Float.NaN }
        for (y in 0 until rows) values.copyInto(cells, y * pc, y * columns, y * columns + columns)

        val flags = BitSink()
        val vals = ArrayList<Int>()
        var parentSplit: BooleanArray? = null        // per block of the level above: split (so this level's block is visited)
        var parentWidth = 0
        for (s in levels) {
            val bw = pc / s; val bh = pr / s
            val split = BooleanArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                if (parentSplit != null && !parentSplit[(by / 2) * parentWidth + bx / 2]) continue
                var n = 0; var sum = 0.0; var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (y in by * s until by * s + s) {
                    val row = y * pc
                    for (x in bx * s until bx * s + s) {
                        val v = cells[row + x]
                        if (v.isNaN()) continue
                        n += 1; sum += v
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                val mean = if (n > 0) sum / n else 0.0
                when {
                    n == 0 -> { flags.bit(false); vals += MISSING }
                    n == s * s && hi - lo <= max(TOLERANCE_MM, TOLERANCE_FRACTION * abs(mean).toFloat()) -> { flags.bit(false); vals += quantize(mean) }
                    else -> { flags.bit(true); split[by * bw + bx] = true }
                }
            }
            parentSplit = split; parentWidth = bw
        }
        // single cells of every 2 x 2 block that was split, block by block
        val bw = pc / 2
        for (by in 0 until pr / 2) for (bx in 0 until bw) {
            if (!parentSplit!![by * bw + bx]) continue
            for (dy in 0..1) for (dx in 0..1) {
                val v = cells[(by * 2 + dy) * pc + bx * 2 + dx]
                vals += if (v.isNaN()) MISSING else quantize(v.toDouble())
            }
        }
        val flagBytes = flags.toByteArray()
        val valueBytes = ByteArray(vals.size * 2)       // all the low bytes, then all the high bytes (deflate finds more to share that way)
        for ((i, v) in vals.withIndex()) { valueBytes[i] = (v and 255).toByte(); valueBytes[vals.size + i] = (v shr 8).toByte() }
        val f = packer.pack(flagBytes); val p = packer.pack(valueBytes)
        val w = ByteSink()
        w.int(columns); w.int(rows); w.int(flagBytes.size); w.int(f.size); w.int(valueBytes.size); w.int(p.size); w.bytes(f); w.bytes(p)
        return w.toByteArray()
    }

    /** The strip's values (row 0 = its south row), NaN where nothing was recorded. */
    fun decodeTile(data: ByteArray, packer: Packer): FloatArray {
        val r = ByteSource(data)
        val columns = r.int(); val rows = r.int()
        val flagSize = r.int(); val flagPacked = r.int(); val valueSize = r.int(); val valuePacked = r.int()
        val flagBytes = packer.unpack(r.bytes(flagPacked), flagSize)
        val valueBytes = packer.unpack(r.bytes(valuePacked), valueSize)
        val count = valueSize / 2
        var nextValue = 0
        fun value(): Float {
            val q = (valueBytes[nextValue].toInt() and 255) or ((valueBytes[count + nextValue].toInt() and 255) shl 8)
            nextValue += 1
            return if (q == MISSING) Float.NaN else q * QUANT_MM
        }
        val bits = BitSource(flagBytes)
        val pc = (columns + BLOCK - 1) / BLOCK * BLOCK
        val pr = (rows + BLOCK - 1) / BLOCK * BLOCK
        val cells = FloatArray(pc * pr)
        var parentSplit: BooleanArray? = null
        var parentWidth = 0
        for (s in levels) {
            val bw = pc / s; val bh = pr / s
            val split = BooleanArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                if (parentSplit != null && !parentSplit[(by / 2) * parentWidth + bx / 2]) continue
                if (bits.next()) { split[by * bw + bx] = true; continue }
                val v = value()
                for (y in by * s until by * s + s) cells.fill(v, y * pc + bx * s, y * pc + bx * s + s)
            }
            parentSplit = split; parentWidth = bw
        }
        val bw = pc / 2
        for (by in 0 until pr / 2) for (bx in 0 until bw) {
            if (!parentSplit!![by * bw + bx]) continue
            for (dy in 0..1) for (dx in 0..1) cells[(by * 2 + dy) * pc + bx * 2 + dx] = value()
        }
        if (pc == columns) return if (pr == rows) cells else cells.copyOf(columns * rows)
        val out = FloatArray(columns * rows)
        for (y in 0 until rows) cells.copyInto(out, y * columns, y * pc, y * pc + columns)
        return out
    }

    private fun quantize(v: Double): Int = (v / QUANT_MM).roundToInt().coerceIn(0, MISSING - 1)
}

/** Cutting a day into the strips it is stored as, and reading a window of it back the way the core's decoder returns one. */
object RainDayFile {
    const val STRIP_ROWS = 880          // a multiple of 16, a quarter of CONUS (3500 rows) or so: a strip is about 25 MB as floats
    const val COLUMNS = 7000
    const val ROWS = 3500
    const val NORTH_CENTER = 54.995
    const val CELL = 0.01

    /** Reads the day from a (gzip) MRMS file, a strip of rows at a time at the file's own cells. */
    fun build(core: Core, file: ByteArray, packer: Packer, missingAtOrBelow: Double): RainDay {
        val strips = ArrayList<RainDay.Strip>()
        var north = 0.0; var west = 0.0
        var first = 0
        while (first < ROWS) {
            val h = minOf(STRIP_ROWS, ROWS - first)
            val topLat = NORTH_CENTER - first * CELL
            val bottomLat = NORTH_CENTER - (first + h - 1) * CELL
            val window = core.decodeMrms(file, -130.2, bottomLat, -59.8, topLat, 1, Pool.Mean, missingAtOrBelow)
            require(window.grid.columns == COLUMNS && window.grid.rows == h) { "not a CONUS multi-sensor total" }
            if (first == 0) {
                val nw = core.gridToLatLon(window.grid, 0.0, (h - 1).toDouble())
                north = nw[0]; west = nw[1]
            }
            strips += RainDay.Strip(first, h, RainDayCodec.encodeTile(window.values, COLUMNS, h, packer))
            first += h
        }
        return RainDay(COLUMNS, ROWS, north, west, CELL, CELL, strips)
    }

    /**
     * The [west]..[east] x [south]..[north] part of [day] with every [step]-th cell (the cells of a block averaged, a missing one left out): the cells and the
     * grid of the core's own decode of a file, so it can stand in for one. Throws when the box is not within the day.
     */
    fun window(day: RainDay, packer: Packer, west: Double, south: Double, east: Double, north: Double, step: Int): DayWindow {
        val st = max(step, 1)
        fun colOf(lon: Double): Double { var d = lon - day.westLon + 0.0; while (d < -180.0) d += 360.0; while (d > 180.0) d -= 360.0; return d / day.dLon + 0.0 }
        fun rowOf(lat: Double) = (day.northLat - lat) / day.dLat
        val clampedX = { v: Double -> floor(v + 0.5).toLong().coerceIn(0L, (day.columns - 1).toLong()).toInt() }
        val clampedY = { v: Double -> floor(v + 0.5).toLong().coerceIn(0L, (day.rows - 1).toLong()).toInt() }
        require(!(colOf(east) < -0.5 || colOf(west) > day.columns - 0.5 || rowOf(south) < -0.5 || rowOf(north) > day.rows - 0.5)) { "the requested area is outside the day" }
        val x0 = clampedX(colOf(west)); val x1 = clampedX(colOf(east))
        val y0 = clampedY(rowOf(north)); val y1 = clampedY(rowOf(south))
        val outCols = (x1 - x0 + 1) / st
        val outRows = (y1 - y0 + 1) / st
        require(x1 >= x0 && y1 >= y0 && outCols >= 1 && outRows >= 1) { "the requested area is outside the day" }
        val acc = FloatArray(outCols * outRows)
        val count = ShortArray(outCols * outRows)
        val lastRow = y0 + outRows * st
        for (strip in day.strips) {
            val top = strip.firstRow; val bottom = strip.firstRow + strip.rows - 1
            if (bottom < y0 || top >= lastRow) continue
            val tile = RainDayCodec.decodeTile(strip.bytes, packer)
            for (y in max(top, y0)..minOf(bottom, lastRow - 1)) {
                val dest = (outRows - 1 - (y - y0) / st) * outCols
                val src = (bottom - y) * day.columns
                for (ox in 0 until outCols) for (sx in 0 until st) {
                    val v = tile[src + x0 + ox * st + sx]
                    if (v.isNaN()) continue
                    acc[dest + ox] += v
                    count[dest + ox] = (count[dest + ox] + 1).toShort()
                }
            }
        }
        val values = FloatArray(outCols * outRows) { if (count[it] > 0) acc[it] / count[it] else Float.NaN }
        val dLat = day.dLat * st; val dLon = day.dLon * st
        val westCenter = day.westLon + (x0 + (st - 1) / 2.0) * day.dLon
        val northCenter = day.northLat - (y0 + (st - 1) / 2.0) * day.dLat
        return DayWindow(values, outCols, outRows, northCenter - (outRows - 1) * dLat, westCenter, dLat, dLon)
    }
}

// ---- small byte helpers (the multiplatform library has no ByteBuffer) ----

internal class ByteSink {
    private var buf = ByteArray(1024)
    private var n = 0
    private fun room(k: Int) { if (n + k > buf.size) buf = buf.copyOf(max(buf.size * 2, n + k)) }
    fun int(v: Int) { room(4); for (i in 0..3) buf[n++] = (v ushr (8 * i)).toByte() }
    fun double(v: Double) { val b = v.toBits(); room(8); for (i in 0..7) buf[n++] = (b ushr (8 * i)).toByte() }
    fun bytes(b: ByteArray) { room(b.size); b.copyInto(buf, n); n += b.size }
    fun toByteArray(): ByteArray = buf.copyOf(n)
}

internal class ByteSource(private val buf: ByteArray) {
    private var at = 0
    fun int(): Int { var v = 0; for (i in 0..3) v = v or ((buf[at++].toInt() and 255) shl (8 * i)); return v }
    fun double(): Double { var b = 0L; for (i in 0..7) b = b or ((buf[at++].toLong() and 255L) shl (8 * i)); return Double.fromBits(b) }
    fun bytes(len: Int): ByteArray { val out = buf.copyOfRange(at, at + len); at += len; return out }
}

internal class BitSink {
    private var buf = ByteArray(1024)
    private var bits = 0
    fun bit(v: Boolean) {
        if (bits / 8 >= buf.size) buf = buf.copyOf(buf.size * 2)
        if (v) buf[bits / 8] = (buf[bits / 8].toInt() or (1 shl (bits % 8))).toByte()
        bits += 1
    }
    fun toByteArray(): ByteArray = buf.copyOf((bits + 7) / 8)
}

internal class BitSource(private val buf: ByteArray) {
    private var at = 0
    fun next(): Boolean { val v = (buf[at / 8].toInt() shr (at % 8)) and 1; at += 1; return v == 1 }
}
