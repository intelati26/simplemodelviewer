package org.spcrefs.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import spcrefs.Sector
import spcrefs.View
import spcrefs.epochSeconds
import spcrefs.formatUtc
import spcrefs.withAutoStops
import java.util.concurrent.TimeUnit

/** Home-screen widget: the newest picture of the product and sector the app showed last. Refreshes about hourly and when added. */
class RefsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.show(context, manager, ids, null, "Loading SPC REFS...")
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
    }

    override fun onEnabled(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "widget", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<WidgetWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("widget")
    }
}

object WidgetUpdater {
    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RefsWidgetProvider::class.java))

    fun show(context: Context, manager: AppWidgetManager, ids: IntArray, picture: Bitmap?, message: String?) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            if (picture != null) views.setImageViewBitmap(R.id.widget_image, picture)
            views.setTextViewText(R.id.widget_text, message ?: "")
            views.setViewVisibility(R.id.widget_text, if (message == null) android.view.View.GONE else android.view.View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = WidgetUpdater.ids(applicationContext)
        if (ids.isEmpty()) return Result.success()
        return try {
            val picture = render()
            WidgetUpdater.show(applicationContext, manager, ids, picture, null)
            Result.success()
        } catch (e: Exception) {
            WidgetUpdater.show(applicationContext, manager, ids, null, "Could not load SPC REFS: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun render(): Bitmap {
        val prefs = AppContainer.prefs
        val repo = AppContainer.repo
        val core = AppContainer.core
        val cycle = repo.findCycles(System.currentTimeMillis() / 1000, wanted = 1).firstOrNull() ?: error("no REFS run found")
        val store = repo.open(cycle)
        val wanted = prefs.getString("product", null)
        val product = store.products.firstOrNull { it.id == wanted } ?: store.products.first()
        val times = repo.validTimes(store, product)
        val now = System.currentTimeMillis() / 1000
        val t = times.indexOfFirst { it >= now }.let { if (it < 0) times.lastIndex.coerceAtLeast(0) else it }
        val values = repo.readFrame(store, product, t, 0)
        val style = withAutoStops(product.style, values)
        val sector = Sector.all.firstOrNull { it.name == prefs.getString("sector", null) } ?: Sector.all[0]
        val view = if (sector.name == "CONUS") View.CONUS else sector.view(core)
        val renderer = MapRenderer(core, AppContainer.lines)
        val map = renderer.render(product, style, values, repo.members(store), view, dark = true)
        val hour = if (times.isEmpty()) 0 else (times[t] - cycle.epochSeconds) / 3600
        val header = "Run ${cycle.label}   Valid ${if (times.isEmpty()) "" else formatUtc(times[t])}  (f${hour.toString().padStart(2, '0')})"
        var picture = renderer.shareImage(map, product, style, header, repo.members(store))
        if (picture.width > 900) picture = Bitmap.createScaledBitmap(picture, 900, picture.height * 900 / picture.width, true)
        return picture
    }
}
