package io.github.intelati26.simplemodelview

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import simplemodelview.BackupPrefs
import simplemodelview.SettingsBackup

/** What a settings file carries besides the named fields: the display and behavior choices and the sources, read from and written to the app's preferences. */
internal object SettingsTransfer {
    private fun collect(prefs: SharedPreferences, specs: Map<String, BackupPrefs.Spec>): Map<String, JsonElement> {
        val all = prefs.all
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, spec) in specs) {
            val v = all[key] ?: continue                      // only what has been chosen: the rest stays at the defaults of the app that reads the file
            if (key == "gfs_product" && v == simplemodelview.GfsHistory.SINCE_ID) continue
            val element: JsonElement = when (spec.kind) {
                BackupPrefs.Kind.BOOL -> (v as? Boolean)?.let { JsonPrimitive(it) }
                BackupPrefs.Kind.INT -> (v as? Int)?.let { JsonPrimitive(it) }
                else -> (v as? String)?.let { JsonPrimitive(it) }
            } ?: continue
            out[key] = element
        }
        return out
    }

    /** [backup] with the display choices ("ui") and the sources filled in from [prefs]. */
    fun withPrefs(backup: SettingsBackup, prefs: SharedPreferences) =
        backup.copy(ui = collect(prefs, BackupPrefs.ui), sources = collect(prefs, BackupPrefs.sources))

    /** Writes the display choices and sources of [b] to [prefs]; how many were written. They are read when the app starts, so the app restarts to take them up. */
    fun apply(prefs: SharedPreferences, b: SettingsBackup): Int {
        val edit = prefs.edit()
        var n = 0
        for ((key, v) in b.ui + b.sources) {
            val p = v as? JsonPrimitive ?: continue
            val spec = BackupPrefs.ui[key] ?: BackupPrefs.sources[key] ?: continue
            when (spec.kind) {
                BackupPrefs.Kind.BOOL -> p.content.toBooleanStrictOrNull()?.let { edit.putBoolean(key, it); n += 1 }
                BackupPrefs.Kind.INT -> p.content.toIntOrNull()?.let { edit.putInt(key, it); n += 1 }
                else -> { edit.putString(key, p.content); n += 1 }
            }
        }
        edit.commit()
        return n
    }

    /** Starts the app again (a short alarm opens it after this process ends), so the imported choices are read from the start. */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 400, pending)
        Runtime.getRuntime().exit(0)
    }
}
