package io.github.intelati26.simplemodelview

import android.app.Application
import android.content.Context
import simplemodelview.AlertsRepository
import simplemodelview.MrmsRepository
import simplemodelview.RefsRepository

/** One HTTP client (and its disk cache), core and repository for the whole process: the UI and the alert worker share them. */
class SimpleModelViewApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        // the favorites used to be saved under the British spelling of the key: carry them over once
        AppContainer.prefs.let { p ->
            if (p.contains("favourites")) {
                val old = p.getStringSet("favourites", emptySet()).orEmpty()
                p.edit().apply { if (!p.contains("favorites")) putStringSet("favorites", old); remove("favourites") }.apply()
            }
        }
        // location-based alerts were removed: drop any job a development build scheduled
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork("alerts")
        AppContainer.prefs.edit().remove("rules").remove("alert_status").apply()
    }
}

object AppContainer {
    private lateinit var app: Application
    fun init(application: Application) { app = application }

    val prefs by lazy { app.getSharedPreferences("simplemodelview", Context.MODE_PRIVATE) }
    val core by lazy { NativeCore() }
    val lines by lazy { MapLines(app, core) }
    private val http by lazy { OkHttpHttp(app, prefs.getInt("cache_mb", 150).toLong()) { prefs.getString("contact_email", "").orEmpty() } }   // a new cache size applies on the next launch
    val repo by lazy { RefsRepository(http, core) }
    val mrms by lazy { MrmsRepository(http, core) }
    val alerts by lazy { AlertsRepository(http) }
}
