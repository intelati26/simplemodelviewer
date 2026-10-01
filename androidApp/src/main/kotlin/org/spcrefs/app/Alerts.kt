package org.spcrefs.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import spcrefs.AlertRule
import spcrefs.decodeRules
import spcrefs.evaluate
import spcrefs.message
import spcrefs.pointSeries
import java.util.concurrent.TimeUnit

/**
 * Alerts without a push server: WorkManager wakes up about hourly, asks whether a newer cycle exists and, only then, reads the
 * next 24 hours at each saved rule's place (one chunk per forecast hour). One check per cycle per rule.
 */
object AlertScheduler {
    private const val NAME = "alerts"

    fun sync(context: Context, hasRules: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!hasRules) { wm.cancelUniqueWork(NAME); return }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build())
    }

    /** Runs a check now, whether or not the cycle was checked before. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<AlertWorker>().setInputData(Data.Builder().putBoolean("force", true).build()).build()
        )
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = AppContainer.prefs
        val repo = AppContainer.repo
        val rules = decodeRules(prefs.getString("rules", "").orEmpty())
        if (rules.isEmpty()) return Result.success()
        val force = inputData.getBoolean("force", false)
        return try {
            val cycle = repo.findCycles(System.currentTimeMillis() / 1000, wanted = 1).firstOrNull() ?: return Result.retry()
            val todo = rules.filter { force || prefs.getString("alert_last_" + it.key, null) != cycle.label }
            if (todo.isEmpty()) return Result.success()
            val store = repo.open(cycle)
            var hits = 0
            for (rule in todo) {
                val product = store.products.firstOrNull { it.id == rule.productId } ?: continue
                val series = repo.pointSeries(store, product, 0, rule.lat, rule.lon, AppContainer.core, maxHours = 24)
                prefs.edit().putString("alert_last_" + rule.key, cycle.label).apply()
                val hit = rule.evaluate(series) ?: continue
                hits += 1
                Notifier.notify(applicationContext, rule, rule.message(hit, series, cycle))
            }
            prefs.edit().putString("alert_status", "Checked run ${cycle.label}: ${todo.size} rule(s), $hits alert(s)").apply()
            Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("alert_status", "Last check failed: ${e.message}").apply()
            Result.retry()
        }
    }
}

object Notifier {
    private const val CHANNEL = "alerts"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, rule: AlertRule, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Forecast alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A saved place reaches the level you chose in a new SPC REFS run. Experimental guidance, not a warning."
        })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("SPC REFS: ${rule.placeName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(rule.key.hashCode(), notification)
    }
}
