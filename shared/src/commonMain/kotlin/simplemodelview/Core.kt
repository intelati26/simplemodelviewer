package simplemodelview

/** A window of the grid: cells from (col0, row0) at the south-west, drawn `scale` pixels per cell. Row 0 is the south row. */
data class View(
    val name: String, val col0: Int, val row0: Int, val cols: Int, val rows: Int, val scale: Int, val step: Int = 1, val grid: Grid = Grid.REFS,
) {
    val pixelWidth get() = cols * scale / step
    val pixelHeight get() = rows * scale / step

    /** The same window with every [n]-th cell drawn (data saver: ~4x less memory for n = 2). Only whole-grid views are decimated. */
    fun decimated(n: Int) = if (n > 1 && scale == 1) copy(step = n) else this

    companion object {
        val CONUS = View("CONUS", 0, 0, GRID_COLUMNS, GRID_ROWS, 1)

        /** The whole of a grid. The coarse GREFS grid is drawn 8 pixels per cell (and smoothed) so it fills a screen. */
        fun whole(grid: Grid) = if (grid == Grid.REFS) CONUS else View("Whole grid", 0, 0, grid.columns, grid.rows, 8, 1, grid)
    }
}

/** How MRMS cells are combined when a window is decimated: the maximum of the valid cells, their mean, or the most common value (category codes). */
enum class Pool { Max, Mean, Mode }

/** A decoded MRMS window: [values] fill [grid] (row 0 = south, NaN where there is no value). */
class MrmsWindow(val grid: Grid, val values: FloatArray, val validTime: Long, val sourceCellDegrees: Double)

/** The native core (C++: blosc decode, grids, MRMS GRIB2 decode, rasteriser). Implemented over JNI on Android and cinterop on iOS. */
interface Core {
    /** Blosc-decompresses to raw bytes; throws on a malformed buffer. */
    fun inflate(raw: ByteArray): ByteArray

    /** Raw or blosc chunk -> floats in C order. Throws on a malformed buffer or unsupported dtype. */
    fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int): FloatArray

    /**
     * Decodes a (gzip-compressed) MRMS GRIB2 file's part inside the box (west-negative degrees), every [step]-th cell in each direction
     * (cells combined by [pool]); stored values at or below [missingAtOrBelow] become NaN. Throws on a damaged / unsupported file.
     */
    fun decodeMrms(file: ByteArray, west: Double, south: Double, east: Double, north: Double, step: Int, pool: Pool, missingAtOrBelow: Double): MrmsWindow

    /** Fractional grid position (column from the west, row from the south) of a latitude / longitude. Returns [column, row]. */
    fun latLonToGrid(grid: Grid, lat: Double, lon: Double): DoubleArray

    /** Latitude / longitude of a fractional grid position. Returns [lat, lon]. */
    fun gridToLatLon(grid: Grid, column: Double, row: Double): DoubleArray

    /**
     * Renders the grid values into [argb] (view.pixelWidth x view.pixelHeight, north up, 0xAARRGGBB, transparent where nothing
     * is shown). [memberRgb] is the paintball colour per member bit.
     */
    fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray)

    /**
     * Renders the data onto an orthographic globe ([width] x [height], sphere of [radius] pixels centred in the buffer, looking
     * down on [centerLat] / [centerLon]). Pixels outside the REFS grid stay 0; inside it with nothing to show they get
     * [domainArgb]. [stride] > 1 draws coarse blocks (a fast preview while dragging).
     */
    fun renderGlobe(
        grid: Grid, values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int,
        domainArgb: Int, style: Style, memberRgb: IntArray, argb: IntArray,
    )
}

/** A latitude / longitude box (west-negative longitudes). */
data class Sector(val name: String, val west: Double, val south: Double, val east: Double, val north: Double) {
    /** The grid window around the box, drawn 4 x 4 pixels per 3 km REFS cell (16 per 42 km GREFS cell) and smoothed. The box bends on the Lambert grid, so walk its outline. */
    fun view(core: Core, grid: Grid = Grid.REFS, pixelsPerCell: Int = if (grid == Grid.REFS) 4 else 16): View {
        var minCol = 1e9; var maxCol = -1e9; var minRow = 1e9; var maxRow = -1e9
        for (i in 0..40) {
            val t = i / 40.0
            val lon = west + (east - west) * t
            val lat = south + (north - south) * t
            for ((la, lo) in listOf(south to lon, north to lon, lat to west, lat to east)) {
                val (c, r) = core.latLonToGrid(grid, la, lo)
                minCol = minOf(minCol, c); maxCol = maxOf(maxCol, c)
                minRow = minOf(minRow, r); maxRow = maxOf(maxRow, r)
            }
        }
        val col0 = kotlin.math.floor(minCol).toInt().coerceIn(0, grid.columns - 2)
        val row0 = kotlin.math.floor(minRow).toInt().coerceIn(0, grid.rows - 2)
        val col1 = kotlin.math.ceil(maxCol).toInt().coerceIn(col0 + 1, grid.columns - 1)
        val row1 = kotlin.math.ceil(maxRow).toInt().coerceIn(row0 + 1, grid.rows - 1)
        return View(name, col0, row0, col1 - col0 + 1, row1 - row0 + 1, pixelsPerCell.coerceIn(1, 32), 1, grid)
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
