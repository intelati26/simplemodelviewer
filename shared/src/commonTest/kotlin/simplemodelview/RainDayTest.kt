package simplemodelview

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RainDayTest {
    private object Plain : Packer {
        override fun pack(raw: ByteArray) = raw
        override fun unpack(packed: ByteArray, rawSize: Int) = packed
    }

    /** Dry, with a smooth wet area, a sharp small cell, light drizzle and a stretch with no coverage. */
    private fun field(columns: Int, rows: Int) = FloatArray(columns * rows) { i ->
        val x = i % columns; val y = i / columns
        when {
            y < 10 && x > columns - 30 -> Float.NaN
            x in 40..90 && y in 30..70 -> 20f + 8f * sin(x / 5.0).toFloat() * sin(y / 7.0).toFloat()
            x in 120..124 && y in 20..24 -> 60f + (x + y) % 3
            x in 150..220 && y in 50..60 -> 0.4f
            else -> 0f
        }
    }

    @Test
    fun aTileComesBackWithinTheTolerance() {
        val columns = 250; val rows = 90                      // neither a multiple of 16
        val v = field(columns, rows)
        val coded = RainDayCodec.encodeTile(v, columns, rows, Plain)
        val back = RainDayCodec.decodeTile(coded, Plain)
        assertEquals(v.size, back.size)
        for (i in v.indices) {
            if (v[i].isNaN()) { assertTrue(back[i].isNaN(), "missing stays missing at $i"); continue }
            val allowed = maxOf(RainDayCodec.TOLERANCE_MM, RainDayCodec.TOLERANCE_FRACTION * v[i]) + RainDayCodec.QUANT_MM
            assertTrue(abs(back[i] - v[i]) <= allowed + 0.001f, "cell $i: ${v[i]} -> ${back[i]}")
        }
        assertTrue(coded.size < v.size * 4 / 8, "much smaller than the floats (${coded.size} bytes)")
    }

    @Test
    fun anEmptyTileIsTiny() {
        val coded = RainDayCodec.encodeTile(FloatArray(160 * 160), 160, 160, Plain)
        assertTrue(coded.size < 400, "${coded.size}")
        assertTrue(RainDayCodec.decodeTile(coded, Plain).all { it == 0f })
    }

    @Test
    fun theFileSurvivesItsBytes() {
        val tile = RainDayCodec.encodeTile(field(64, 48), 64, 48, Plain)
        val day = RainDay(64, 48, 40.0, -100.0, 0.01, 0.01, listOf(RainDay.Strip(0, 48, tile)))
        val back = RainDay.fromBytes(day.toBytes())
        assertEquals(64, back.columns); assertEquals(40.0, back.northLat); assertEquals(-100.0, back.westLon)
        assertEquals(1, back.strips.size); assertTrue(tile.contentEquals(back.strips[0].bytes))
    }

    @Test
    fun aWindowIsTheMeanOfItsCells() {
        // two strips of a 64 x 48 day (rows 0..31 north, 32..47 south); cells are 0.01 degrees, the north-west cell is at (40.0, -100.0)
        val columns = 64; val rows = 48
        val v = FloatArray(columns * rows) { i -> (i % columns) * 0.3f + (i / columns) * 0.2f }
        fun strip(first: Int, h: Int): RainDay.Strip {
            // tile row 0 = the strip's south row
            val tile = FloatArray(columns * h) { i -> v[(first + h - 1 - i / columns) * columns + i % columns] }
            return RainDay.Strip(first, h, RainDayCodec.encodeTile(tile, columns, h, Plain))
        }
        val day = RainDay(columns, rows, 40.0, -100.0, 0.01, 0.01, listOf(strip(0, 32), strip(32, 16)))
        // the box from column 8 to 39, rows 4 to 43 (counted from the north), every 4th cell
        val w = RainDayFile.window(day, Plain, -100.0 + 0.08, 40.0 - 0.43, -100.0 + 0.39, 40.0 - 0.04, 4)
        assertEquals(8, w.columns); assertEquals(10, w.rows)
        for (oy in 0 until w.rows) for (ox in 0 until w.columns) {
            var sum = 0.0
            for (dy in 0..3) for (dx in 0..3) sum += v[(4 + (w.rows - 1 - oy) * 4 + dy) * columns + 8 + ox * 4 + dx]
            val expected = sum / 16
            assertTrue(abs(w.values[oy * w.columns + ox] - expected) <= 1.0, "($ox,$oy): ${w.values[oy * w.columns + ox]} vs $expected")
        }
        assertEquals(0.04, w.dLat, 1e-9)
        assertEquals(-100.0 + 0.015 + 0.08, w.westLon, 1e-9)          // the center of the first 4 x 4 block
        assertEquals(40.0 - 0.04 - 0.015 - 9 * 0.04, w.southLat, 1e-9)
    }
}
