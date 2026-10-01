package spcrefs

/** A window of the grid: cells from (col0, row0) at the south-west, drawn `scale` pixels per cell. Row 0 is the south row. */
data class View(val name: String, val col0: Int, val row0: Int, val cols: Int, val rows: Int, val scale: Int, val step: Int = 1) {
    val pixelWidth get() = cols * scale / step
    val pixelHeight get() = rows * scale / step

    /** The same window with every [n]-th cell drawn (data saver: ~4x less memory for n = 2). Only whole-grid views are decimated. */
    fun decimated(n: Int) = if (n > 1 && scale == 1) copy(step = n) else this

    companion object {
        val CONUS = View("CONUS", 0, 0, GRID_COLUMNS, GRID_ROWS, 1)
    }
}

/** The native core (C++: blosc decode, Lambert grid, rasteriser). Implemented over JNI on Android and cinterop on iOS. */
interface Core {
    /** Blosc-decompresses to raw bytes; throws on a malformed buffer. */
    fun inflate(raw: ByteArray): ByteArray

    /** Raw or blosc chunk -> floats in C order. Throws on a malformed buffer or unsupported dtype. */
    fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int): FloatArray

    /** Fractional grid position (column from the west, row from the south) of a latitude / longitude. Returns [column, row]. */
    fun latLonToGrid(lat: Double, lon: Double): DoubleArray

    /** Latitude / longitude of a fractional grid position. Returns [lat, lon]. */
    fun gridToLatLon(column: Double, row: Double): DoubleArray

    /**
     * Renders the grid values into [argb] (view.pixelWidth x view.pixelHeight, north up, 0xAARRGGBB, transparent where nothing
     * is shown). [memberRgb] is the paintball colour per member bit.
     */
    fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray)
}

/** A latitude / longitude box (west-negative longitudes). */
data class Sector(val name: String, val west: Double, val south: Double, val east: Double, val north: Double) {
    /** The grid window around the box, drawn 4 x 4 pixels per 3 km cell and smoothed. The box bends on the Lambert grid, so walk its outline. */
    fun view(core: Core, pixelsPerCell: Int = 4): View {
        var minCol = 1e9; var maxCol = -1e9; var minRow = 1e9; var maxRow = -1e9
        for (i in 0..40) {
            val t = i / 40.0
            val lon = west + (east - west) * t
            val lat = south + (north - south) * t
            for ((la, lo) in listOf(south to lon, north to lon, lat to west, lat to east)) {
                val (c, r) = core.latLonToGrid(la, lo)
                minCol = minOf(minCol, c); maxCol = maxOf(maxCol, c)
                minRow = minOf(minRow, r); maxRow = maxOf(maxRow, r)
            }
        }
        val col0 = kotlin.math.floor(minCol).toInt().coerceIn(0, GRID_COLUMNS - 2)
        val row0 = kotlin.math.floor(minRow).toInt().coerceIn(0, GRID_ROWS - 2)
        val col1 = kotlin.math.ceil(maxCol).toInt().coerceIn(col0 + 1, GRID_COLUMNS - 1)
        val row1 = kotlin.math.ceil(maxRow).toInt().coerceIn(row0 + 1, GRID_ROWS - 1)
        return View(name, col0, row0, col1 - col0 + 1, row1 - row0 + 1, pixelsPerCell.coerceIn(1, 8))
    }

    companion object {
        val all = listOf(
            Sector("CONUS", -125.0, 24.0, -66.5, 50.0),
            Sector("Midwest", -98.1, 34.4, -84.9, 43.6),
            Sector("North Central", -103.1, 40.0, -89.9, 49.3),
            Sector("Central", -102.9, 33.0, -89.7, 42.2),
            Sector("South Central", -103.3, 27.2, -90.1, 36.4),
            Sector("Northeast", -83.7, 39.1, -70.5, 48.3),
            Sector("Central East", -88.5, 32.3, -75.3, 41.5),
            Sector("Southeast", -92.2, 26.4, -79.0, 35.6),
            Sector("Southwest", -119.1, 31.2, -105.9, 40.4),
            Sector("Northwest", -119.3, 40.0, -106.1, 49.2),
            Sector("Great Lakes", -92.5, 39.4, -79.3, 48.6),
            Sector("Intermountain West", -117.2, 36.4, -104.0, 45.6),
        )
    }
}
