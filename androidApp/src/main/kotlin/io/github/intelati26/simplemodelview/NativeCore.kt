package io.github.intelati26.simplemodelview

import simplemodelview.Core
import simplemodelview.Grid
import simplemodelview.Kind
import simplemodelview.MrmsWindow
import simplemodelview.Pool
import simplemodelview.Style
import simplemodelview.View

/** The C++ core over JNI (core/jni/simplemodelview_jni.cpp). */
class NativeCore : Core {
    external override fun inflate(raw: ByteArray): ByteArray
    external override fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int): FloatArray
    private external fun decodeMrmsNative(
        file: ByteArray, west: Double, south: Double, east: Double, north: Double, step: Int, pool: Int, missing: Double, info: DoubleArray,
    ): FloatArray

    override fun decodeMrms(
        file: ByteArray, west: Double, south: Double, east: Double, north: Double, step: Int, pool: Pool, missingAtOrBelow: Double,
    ): MrmsWindow {
        val info = DoubleArray(5)
        val values = decodeMrmsNative(file, west, south, east, north, step, pool.ordinal, missingAtOrBelow, info)
        return MrmsWindow(Grid(info[0].toInt(), info[1].toInt(), info[2].toInt()), values, info[3].toLong(), info[4])
    }

    private external fun planLambertBoxNative(west: Double, south: Double, east: Double, north: Double, cell: Double): IntArray
    private external fun planLambertCropNative(base: Int, colMin: Double, rowMin: Double, colMax: Double, rowMax: Double, cell: Double): IntArray
    private external fun reprojectNative(values: FloatArray, srcGrid: Int, dstGrid: Int, dstCount: Int): FloatArray

    override fun planLambertBox(west: Double, south: Double, east: Double, north: Double, cellMeters: Double): Grid =
        planLambertBoxNative(west, south, east, north, cellMeters).let { Grid(it[0], it[1], it[2]) }

    override fun planLambertCrop(base: Grid, colMin: Double, rowMin: Double, colMax: Double, rowMax: Double, cellMeters: Double): Grid? =
        planLambertCropNative(base.id, colMin, rowMin, colMax, rowMax, cellMeters).let { if (it[0] < 0) null else Grid(it[0], it[1], it[2]) }

    override fun reproject(values: FloatArray, from: Grid, to: Grid): FloatArray = reprojectNative(values, from.id, to.id, to.cells)

    private external fun latLonToGridNative(grid: Int, lat: Double, lon: Double): DoubleArray
    private external fun gridToLatLonNative(grid: Int, column: Double, row: Double): DoubleArray
    override fun latLonToGrid(grid: Grid, lat: Double, lon: Double) = latLonToGridNative(grid.id, lat, lon)
    override fun gridToLatLon(grid: Grid, column: Double, row: Double) = gridToLatLonNative(grid.id, column, row)

    private external fun render(
        grid: Int, values: FloatArray, col0: Int, row0: Int, cols: Int, rows: Int, scale: Int, step: Int, kind: Int,
        stopValues: DoubleArray, stopRgb: IntArray, factor: Double, offset: Double, hideBelow: Double, hideAbove: Double, opacity: Double,
        memberRgb: IntArray, argb: IntArray,
    )

    /** The style's stops as the core takes them: the color below the first level first, the color above the top last (or a ceiling when there is none). */
    private class CoreStops(style: Style) {
        val values: DoubleArray
        val rgb: IntArray
        val hideAbove: Double
        init {
            val v = ArrayList<Double>(); val c = ArrayList<Int>()
            style.under?.let { v += -1e30; c += it }
            for (stop in style.stops) { v += stop.value; c += stop.rgb }
            var ceiling = 1e30
            val top = style.top
            if (top != null) { val over = style.over; if (over != null) { v += top; c += over } else ceiling = top }
            values = v.toDoubleArray(); rgb = c.toIntArray(); hideAbove = ceiling
        }
    }

    override fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray) {
        val stops = CoreStops(style)
        render(
            view.grid.id, values, view.col0, view.row0, view.cols, view.rows, view.scale, view.step, style.kind.ordinal,
            stops.values, stops.rgb, style.factor, style.offset, style.hideBelow, stops.hideAbove, style.opacity, memberRgb, argb,
        )
    }

    private external fun renderGlobe(
        grid: Int, values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int, domainArgb: Int,
        kind: Int, stopValues: DoubleArray, stopRgb: IntArray, factor: Double, offset: Double, hideBelow: Double, hideAbove: Double, opacity: Double,
        memberRgb: IntArray, argb: IntArray,
    )

    override fun renderGlobe(
        grid: Grid, values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int,
        domainArgb: Int, style: Style, memberRgb: IntArray, argb: IntArray,
    ) {
        val stops = CoreStops(style)
        renderGlobe(
            grid.id, values, width, height, radius, centerLat, centerLon, stride, domainArgb, style.kind.ordinal,
            stops.values, stops.rgb, style.factor, style.offset, style.hideBelow, stops.hideAbove, style.opacity, memberRgb, argb,
        )
    }

    companion object {
        init { System.loadLibrary("simplemodelview_jni") }
    }
}

internal fun Kind.isPaintball() = this == Kind.Paintball
