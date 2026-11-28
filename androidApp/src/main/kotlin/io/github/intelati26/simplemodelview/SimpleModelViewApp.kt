package io.github.intelati26.simplemodelview

import java.io.File
import android.app.Application
import android.content.Context
import simplemodelview.AlertsRepository
import simplemodelview.GfsRepository
import simplemodelview.LightningCastRepository
import simplemodelview.ProbSevereRepository
import simplemodelview.MrmsRepository
import simplemodelview.NbmRepository
import simplemodelview.NbmTextRepository
import simplemodelview.NwsPointClient
import simplemodelview.OutlookRepository
import simplemodelview.RefsRepository

/** One HTTP client (and its disk cache), core and repository for the whole process: the UI and the alert worker share them. */
class SimpleModelViewApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        Timings.logging = AppContainer.prefs.getBoolean("log_timings", false)
        // the favorites used to be saved under the British spelling of the key: carry them over once
        AppContainer.prefs.let { p ->
            if (p.contains("favourites")) {
                val old = p.getStringSet("favourites", emptySet()).orEmpty()
                p.edit().apply { if (!p.contains("favorites")) putStringSet("favorites", old); remove("favourites") }.apply()
            }
        }
        // a first launch has no saved places: Norman, OK is the one to start with, so the Extended page has a forecast to show (deleting it, or every place, is kept)
        AppContainer.prefs.let { p ->
            if (!p.contains("places")) p.edit().putString("places", simplemodelview.encodePlaces(listOf(simplemodelview.Place("Norman, OK", 35.2220, -97.4395)))).apply()
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

    /** Loops start at the frame shown (and run the chosen number of steps after it) instead of at the page's usual start. One choice for every page. */
    val loopFromShown by lazy { kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("loop_from_shown", false)) }
    fun setLoopFromShown(on: Boolean) { prefs.edit().putBoolean("loop_from_shown", on).apply(); loopFromShown.value = on }
    val core by lazy { NativeCore() }

    /** The size chosen for videos and compare pictures: one choice for every page. */
    var videoSize: simplemodelview.VideoSize
        get() = simplemodelview.VideoSize.byName(prefs.getString("video_size", null))
        set(value) { prefs.edit().putString("video_size", value.name).apply() }

    /** Whether the page should ask for a contact e-mail now: not when one is set, and at most once in 30 days (the asking is remembered). */
    fun contactPromptDue(email: String): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("contact_asked", 0L)
        if (simplemodelview.isValidEmail(email) || last != 0L && now - last < 30L * 24 * 3600 * 1000) return false
        prefs.edit().putLong("contact_asked", now).apply()
        return true
    }

    /** True when the connection is metered (loops set to Wi-Fi only do not download then). */
    fun isMetered(): Boolean = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered

    /** The loop videos being made in the background (see [RenderManager]). */
    val renders by lazy { RenderManager(app) }
    val lines by lazy { MapLines(app, core) }
    val places by lazy { PlacesStore(prefs) }
    val placeSearch by lazy { simplemodelview.PlaceSearch(SearchHttp()) }
    val http by lazy { OkHttpHttp(app, prefs.getInt("cache_mb", 150).toLong()) { prefs.getString("contact_email", "").orEmpty() } }   // a new cache size applies on the next launch
    val repo by lazy { RefsRepository(http, core) }
    val mrms by lazy { MrmsRepository(http, core) }
    val gribStore by lazy { DiskGribStore(app, File(app.cacheDir, "grib"), capBytes = { prefs.getInt("cache_mb", 150).toLong() * 1024 * 1024 }) }
    /** The finished rainfall days (see [RainDayStore]). */
    val rainDays by lazy {
        // app storage, not the cache folder: Android does not empty it when space is short (the days that were in the cache folder move over)
        val dir = File(app.filesDir, "rain-days")
        val old = File(app.cacheDir, "rain-days")
        if (old.isDirectory) {
            dir.mkdirs()
            old.listFiles { f -> f.name.endsWith(".rd") }?.forEach { f -> if (!f.renameTo(File(dir, f.name))) f.delete() }
            old.deleteRecursively()
        }
        RainDayStore(dir)
    }
    val nbm by lazy { NbmRepository(http, core, gribStore) }
    val gfs by lazy { GfsRepository(http, core, gribStore) }
    val nbmText by lazy { NbmTextRepository(http) }
    val nws by lazy { NwsPointClient(http) }
    val bufkit by lazy { simplemodelview.BufkitStations(http, nws) { System.currentTimeMillis() / 1000 } }
    val outlook by lazy { OutlookModel(OutlookRepository(http), prefs) }
    val probSevere by lazy { ProbSevereModel(ProbSevereRepository(http) { prefs.getString("probsevere_url", null)?.takeIf { it.isNotBlank() } }, prefs) }
    val lightning by lazy { LightningCastModel(LightningCastRepository(http) { prefs.getString("lightningcast_url", null)?.takeIf { it.isNotBlank() } }, prefs) }
    val alerts by lazy { AlertsRepository(http) }
    /** The surface analysis under the MRMS radar: RTMA (every 15 minutes), NBM when there is none; its messages are kept with the other GRIB messages. */
    /** The surface analysis page's files (RTMA, URMA); their messages are kept with the other GRIB messages. */
    val analysis by lazy { simplemodelview.AnalysisRepository(http, core, gribStore) }
    /** METAR reports from the Aviation Weather Center (the bulk file is gzipped). */
    val metars by lazy { simplemodelview.MetarRepository(http) { bytes -> java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() } } }
    val surface by lazy { simplemodelview.SurfaceRepository(http, core, gribStore) }
}
