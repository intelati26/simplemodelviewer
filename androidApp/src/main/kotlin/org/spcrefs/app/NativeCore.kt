package org.spcrefs.app

import spcrefs.Core
import spcrefs.Kind
import spcrefs.Style
import spcrefs.View

/** The C++ core over JNI (core/jni/spcrefs_jni.cpp). */
class NativeCore : Core {
    external override fun inflate(raw: ByteArray): ByteArray
    external override fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int): FloatArray
    external override fun latLonToGrid(lat: Double, lon: Double): DoubleArray
    external override fun gridToLatLon(column: Double, row: Double): DoubleArray

    private external fun render(
        values: FloatArray, col0: Int, row0: Int, cols: Int, rows: Int, scale: Int, kind: Int,
        stopValues: DoubleArray, stopRgb: IntArray, factor: Double, offset: Double, hideBelow: Double,
        memberRgb: IntArray, argb: IntArray,
    )

    override fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray) {
        render(
            values, view.col0, view.row0, view.cols, view.rows, view.scale, style.kind.ordinal,
            DoubleArray(style.stops.size) { style.stops[it].value }, IntArray(style.stops.size) { style.stops[it].rgb },
            style.factor, style.offset, style.hideBelow, memberRgb, argb,
        )
    }

    companion object {
        init { System.loadLibrary("spcrefs_jni") }
    }
}

internal fun Kind.isPaintball() = this == Kind.Paintball
