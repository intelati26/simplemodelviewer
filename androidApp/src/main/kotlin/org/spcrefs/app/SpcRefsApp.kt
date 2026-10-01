package org.spcrefs.app

import android.app.Application
import android.content.Context
import spcrefs.RefsRepository

/** One HTTP client (and its disk cache), core and repository for the whole process: the UI and the alert worker share them. */
class SpcRefsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

object AppContainer {
    private lateinit var app: Application
    fun init(application: Application) { app = application }

    val prefs by lazy { app.getSharedPreferences("spcrefs", Context.MODE_PRIVATE) }
    val core by lazy { NativeCore() }
    val lines by lazy { MapLines(app, core) }
    val repo by lazy { RefsRepository(OkHttpHttp(app, prefs.getInt("cache_mb", 150).toLong()), core) }   // a new cache size applies on the next launch
}
