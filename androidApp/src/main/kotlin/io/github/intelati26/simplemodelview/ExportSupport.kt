package io.github.intelati26.simplemodelview

import simplemodelview.Core
import simplemodelview.Grid
import simplemodelview.View
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import simplemodelview.SettingsBackup
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What an export reads of a page, taken once when it is asked for (the page's state, its view and visible part, the globe's size, the grid shown): a loop
 * follows this, not what the page is changed to meanwhile.
 */
class ExportCtx<S>(val st: S, val view: View?, val viewport: FloatArray, val globeW: Int, val globeH: Int, val grid: Grid? = null)

/** The grid window an export covers: a meso sector, the part on screen ([viewport] of [base]), or the whole of [base]; drawn at the export's resolution. */
internal fun exportWindow(core: Core, area: ShareArea, grid: Grid, base: View, viewport: FloatArray): View {
    val window = when (area) {
        is ShareArea.Meso -> area.sector.view(core, grid, 1)
        ShareArea.Screen -> {
            val (l, t, r, b) = viewport.toList()
            val col0 = (base.col0 + floor(l * base.cols).toInt()).coerceIn(0, grid.columns - 2)
            val row0 = (base.row0 + floor((1f - b) * base.rows).toInt()).coerceIn(0, grid.rows - 2)
            val col1 = (base.col0 + ceil(r * base.cols).toInt() - 1).coerceIn(col0 + 1, grid.columns - 1)
            val row1 = (base.row0 + ceil((1f - t) * base.rows).toInt() - 1).coerceIn(row0 + 1, grid.rows - 1)
            View("Screen area", col0, row0, col1 - col0 + 1, row1 - row0 + 1, 1, 1, grid)
        }
        else -> base.copy(scale = 1, step = 1)
    }
    return window.copy(scale = exportScale(window.cols, window.rows), step = 1)
}

/** Whole pixels per cell for an export: about 1800 px wide, at most [maxPerCell] per cell, under about 8 million pixels. */
internal fun exportScale(cols: Int, rows: Int, maxPerCell: Int = 6): Int {
    var s = ceil(1800.0 / cols).toInt().coerceIn(1, maxPerCell)
    while (s > 1 && cols.toLong() * s * rows * s > 8_000_000L) s -= 1
    return s
}

/** Writes the picture as a PNG to [file] and returns the file. */
internal fun Bitmap.savePng(file: File): File = file.also { f -> f.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) } }

/** The visible part of a picture as fractions (left, top, right, bottom), kept inside the picture. */
internal fun clampedViewport(left: Float, top: Float, right: Float, bottom: Float) =
    floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))

/** Writes the settings backup as JSON to the file the user chose; false when it could not be written. */
internal suspend fun writeSettingsFile(resolver: ContentResolver, uri: Uri, backup: SettingsBackup): Boolean {
    val text = SettingsBackup.encode(backup)
    return withContext(Dispatchers.IO) { try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false } }
}

/** Reads a settings backup the user chose (null when the file is not one). */
internal suspend fun readSettingsFile(resolver: ContentResolver, uri: Uri): SettingsBackup? {
    val text = withContext(Dispatchers.IO) { try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null } }
    return text?.let { SettingsBackup.decode(it) }
}

/** The folder an export is written to (emptied: only the latest export is kept there). */
internal fun exportDir(context: android.content.Context): File = File(context.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
