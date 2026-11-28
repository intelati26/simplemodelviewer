package io.github.intelati26.simplemodelview

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import simplemodelview.Contours
import simplemodelview.FrameTarget
import simplemodelview.MrmsCatalog
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style
import simplemodelview.Underlay
import simplemodelview.Units
import simplemodelview.View
import simplemodelview.formatClock
import simplemodelview.mrmsHasHalo
import simplemodelview.mrmsStep
import simplemodelview.styleIn
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget: the newest MRMS composite reflectivity over the sector the MRMS page showed last, on the surface temperature (RTMA, translucent) with the
 * 500 mb height (REFS mean) as lines, and a line of valid times and the temperature range. Refreshes about every half hour and when added; a tap opens the MRMS page.
 */
class SynopticWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.show(context, manager, ids, null, "Loading radar + 500 mb…", "mrms")
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SynopticWidgetWorker>().build())
    }

    override fun onEnabled(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "synoptic_widget", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<SynopticWidgetWorker>(30, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("synoptic_widget")
    }
}

class SynopticWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = WidgetUpdater.ids(applicationContext, SynopticWidgetProvider::class.java)
        if (ids.isEmpty()) return Result.success()
        return try {
            WidgetUpdater.show(applicationContext, manager, ids, render(aspectOf(manager, ids)), null, "mrms")
            Result.success()
        } catch (e: Exception) {
            WidgetUpdater.show(applicationContext, manager, ids, null, "Could not load the radar: ${e.message}", "mrms")
            Result.retry()
        }
    }

    private suspend fun render(aspect: Float?): Bitmap {
        val core = AppContainer.core
        val now = System.currentTimeMillis() / 1000
        val product = MrmsCatalog.byId("MergedReflectivityQCComposite_00.50") ?: MrmsCatalog.default
        val sector = Sector.all.firstOrNull { it.name == AppContainer.prefs.getString("mrms_sector", null) } ?: Sector.all[0]
        val file = AppContainer.mrms.listFiles(product, now).lastOrNull() ?: error("no recent ${product.title} scans")
        val step = mrmsStep(product.cellDegrees, sector.west, sector.east, 1200, dataSaver = false)
        val margin = 0.05
        val frame = AppContainer.mrms.loadFrame(product, file, sector.west - margin, sector.south - margin, sector.east + margin, sector.north + margin, step, FrameTarget.Planned)
        val grid = frame.window.grid
        val picture = Product(product.directory, product.displayTitle, product.group, product.styleIn(Units()), hasMembers = false, timeDimension = null, grid = grid)
        val view = View(sector.name, 0, 0, grid.columns, grid.rows, 1, 1, grid)
        val whole = RectF(0f, 0f, view.pixelWidth.toFloat(), view.pixelHeight.toFloat())

        // the surface temperature (RTMA at or before the scan; NBM when there is none) on the picture's grid, translucent under the radar
        var fill: Overlay? = null
        var range = ""
        var surface = ""
        val pick = try { AppContainer.surface.pick(file.validTime, now) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val temp = pick?.let { p -> try { AppContainer.surface.temperature(p).let { f -> core.reproject(f.values, f.grid, grid) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } }
        if (pick != null && temp != null) {
            val tempStyle = (MrmsCatalog.byId(Underlay.TEMP_STYLE)?.styleIn(Units()) ?: Style()).copy(opacity = Underlay.FILL_OPACITY)
            val argb = IntArray(grid.cells)
            core.render(temp, View("under", 0, 0, grid.columns, grid.rows, 1, 1, grid), tempStyle, IntArray(10), argb)
            fill = Overlay(Bitmap.createBitmap(argb, grid.columns, grid.rows, Bitmap.Config.ARGB_8888), whole, bold = false)
            val lo = temp.filter { !it.isNaN() }.minOrNull(); val hi = temp.filter { !it.isNaN() }.maxOrNull()
            if (lo != null && hi != null) range = " · ${Math.round(lo * 9f / 5f + 32f)}–${Math.round(hi * 9f / 5f + 32f)} F"
            surface = pick.label
        }
        // the 500 mb height of the hour of that analysis, on the same grid
        var lines = emptyList<FieldLines>()
        var upper = ""
        val z = pick?.let { SynopticHeights(AppContainer.repo).at(it.valid, now) }
        if (z != null) {
            val onGrid = core.reproject(z.values, z.grid, grid)
            lines = listOf(FieldLines(onGrid, grid, Style(), Contours.specFor("z500", "m")!!, "z5|${z.run}|${z.valid}|${grid.id}"))
            upper = "500 mb +${z.lead} h"
        }
        val map = MapRenderer(core, AppContainer.lines).render(picture, picture.style, frame.window.values, emptyList(), view, dark = true, contours = false, halo = mrmsHasHalo(product), underlay = fill, fieldLines = lines)
        return caption(cropTo(map, aspect), "${formatClock(file.validTime)}" + (if (surface.isNotEmpty()) " · ${surface.replace("RTMA ", "RTMA\u00A0")}" else "") + range + (if (upper.isNotEmpty()) " · $upper" else ""))
    }

    /** Width over height of the first widget (from its size in dp), or null when the launcher does not say. */
    private fun aspectOf(manager: AppWidgetManager, ids: IntArray): Float? {
        val o = manager.getAppWidgetOptions(ids[0])
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0); val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (w > 0 && h > 0) w.toFloat() / h else null
    }

    /** [map] cut from its middle to width over height [aspect], so it fills the widget with no bands. */
    private fun cropTo(map: Bitmap, aspect: Float?): Bitmap {
        if (aspect == null) return map
        val have = map.width.toFloat() / map.height
        return if (have > aspect) { val w = (map.height * aspect).toInt(); Bitmap.createBitmap(map, (map.width - w) / 2, 0, w, map.height) }
        else { val h = (map.width / aspect).toInt(); Bitmap.createBitmap(map, 0, (map.height - h) / 2, map.width, h) }
    }

    /** [map] scaled to at most 900 pixels wide, with [text] over its bottom edge on a translucent dark band (the picture keeps all the room). */
    private fun caption(map: Bitmap, text: String): Bitmap {
        val width = minOf(map.width, 900)
        val out = (if (map.width > width) Bitmap.createScaledBitmap(map, width, map.height * width / map.width, true) else map).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val size = (width / 34f).coerceAtLeast(12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size }
        while (paint.measureText(text) > width - 12f && paint.textSize > 8f) paint.textSize -= 1f
        val band = paint.textSize * 1.5f
        canvas.drawRect(0f, out.height - band, width.toFloat(), out.height.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })
        canvas.drawText(text, 6f, out.height - band + paint.textSize * 1.12f, paint)
        return out
    }
}
