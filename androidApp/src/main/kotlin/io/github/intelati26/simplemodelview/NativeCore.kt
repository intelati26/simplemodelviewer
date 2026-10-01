package io.github.intelati26.simplemodelview

import simplemodelview.Core
import simplemodelview.Grid
import simplemodelview.Kind
import simplemodelview.Style
import simplemodelview.View

/** The C++ core over JNI (core/jni/simplemodelview_jni.cpp). */
class NativeCore : Core {
    external override fun inflate(raw: ByteArray): ByteArray
    external override fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int): FloatArray
    private external fun latLonToGridNative(grid: Int, lat: Double, lon: Double): DoubleArray
    private external fun gridToLatLonNative(grid: Int, column: Double, row: Double): DoubleArray
    override fun latLonToGrid(grid: Grid, lat: Double, lon: Double) = latLonToGridNative(grid.id, lat, lon)
    override fun gridToLatLon(grid: Grid, column: Double, row: Double) = gridToLatLonNative(grid.id, column, row)

    private external fun render(
        grid: Int, values: FloatArray, col0: Int, row0: Int, cols: Int, rows: Int, scale: Int, step: Int, kind: Int,
        stopValues: DoubleArray, stopRgb: IntArray, factor: Double, offset: Double, hideBelow: Double,
        memberRgb: IntArray, argb: IntArray,
    )

    override fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray) {
        render(
            view.grid.id, values, view.col0, view.row0, view.cols, view.rows, view.scale, view.step, style.kind.ordinal,
            DoubleArray(style.stops.size) { style.stops[it].value }, IntArray(style.stops.size) { style.stops[it].rgb },
            style.factor, style.offset, style.hideBelow, memberRgb, argb,
        )
    }

    private external fun renderGlobe(
        grid: Int, values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int, domainArgb: Int,
        kind: Int, stopValues: DoubleArray, stopRgb: IntArray, factor: Double, offset: Double, hideBelow: Double,
        memberRgb: IntArray, argb: IntArray,
    )

    override fun renderGlobe(
        grid: Grid, values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int,
        domainArgb: Int, style: Style, memberRgb: IntArray, argb: IntArray,
    ) {
        renderGlobe(
            grid.id, values, width, height, radius, centerLat, centerLon, stride, domainArgb, style.kind.ordinal,
            DoubleArray(style.stops.size) { style.stops[it].value }, IntArray(style.stops.size) { style.stops[it].rgb },
            style.factor, style.offset, style.hideBelow, memberRgb, argb,
        )
    }

    companion object {
        init { System.loadLibrary("simplemodelview_jni") }
    }
}

internal fun Kind.isPaintball() = this == Kind.Paintball
