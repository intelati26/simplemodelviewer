package io.github.intelati26.simplemodelview

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The Android Auto (and Android Automotive) entry point: a points-of-interest app in the Car App Library, the category every Android Auto lists and that may
 * draw its own map surface (the radar) with a pane beside it. It was a weather-category app first; that category is in beta and the host may hide it. See
 * docs/android-auto.md.
 */
class RadarCarService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        CarLog.add(this, "Android Auto started the car service")
    }

    // any host while the app is a debug build (the Desktop Head Unit); in a release build only the hosts the library lists as Google's
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        init { CarLog.add(this@RadarCarService, "Session opened by " + (hostInfo?.packageName ?: "an unknown host")) }

        // the map template needs car API level 7; an older Android Auto would hide the whole app silently if the manifest demanded it, so the manifest asks
        // for level 1 and this says what is wrong instead
        override fun onCreateScreen(intent: Intent): Screen {
            val level = carContext.carAppApiLevel
            CarLog.add(this@RadarCarService, "Screen asked for: car API level $level, " + if (level >= RADAR_API_LEVEL) "radar" else "too old for the radar")
            // the car probe build has no map permissions: it only says that Android Auto found it
            if (carContext.checkSelfPermission("androidx.car.app.MAP_TEMPLATES") != PackageManager.PERMISSION_GRANTED)
                return CarInfoScreen(carContext, "Car probe: Android Auto lists and starts this app (car API level $level). The map permissions are what it objects to in the radar app.")
            return if (level >= RADAR_API_LEVEL) RadarCarScreen(carContext) else CarInfoScreen(carContext, "Update Android Auto: the radar needs car API level $RADAR_API_LEVEL, this host has $level.")
        }
    }

    private companion object { const val RADAR_API_LEVEL = 7 }
}

internal class CarInfoScreen(carContext: androidx.car.app.CarContext, private val text: String) : Screen(carContext) {
    override fun onGetTemplate(): androidx.car.app.model.Template = androidx.car.app.model.PaneTemplate.Builder(
        androidx.car.app.model.Pane.Builder().addRow(androidx.car.app.model.Row.Builder().setTitle(text).build()).build(),
    ).setHeader(androidx.car.app.model.Header.Builder().setTitle("simplemodelview").setStartHeaderAction(androidx.car.app.model.Action.APP_ICON).build()).build()
}

/**
 * What the phone can say about the car app, for Settings: whether the system finds the car service and in which category, whether the car permissions are
 * granted, and which Android Auto is installed. Android Auto logs nothing when it hides an app, so this is what the phone side shows.
 */
@Suppress("DEPRECATION")
internal fun carAppStatus(context: Context): String {
    val pm = context.packageManager
    val found = pm.queryIntentServices(Intent("androidx.car.app.CarAppService").setPackage(context.packageName), PackageManager.GET_RESOLVED_FILTER)
    val service = found.firstOrNull()
    val categories = service?.filter?.categoriesIterator()?.asSequence()?.map { it.removePrefix("androidx.car.app.category.") }?.joinToString(", ").orEmpty()
    val permissions = listOf("androidx.car.app.MAP_TEMPLATES", "androidx.car.app.ACCESS_SURFACE")
        .joinToString(", ") { it.substringAfterLast('.') + if (context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) " yes" else " NO" }
    val auto = try { pm.getPackageInfo("com.google.android.projection.gearhead", 0).versionName ?: "?" } catch (e: Exception) { null }
    val log = CarLog.read(context)
    return "Car service: " + (if (service == null) "NOT FOUND" else "found ($categories)") +
        "\nPermissions: $permissions" +
        "\nAndroid Auto: " + (auto ?: "not installed (or not visible)") +
        "\nCar log: " + (if (log.isBlank()) "Android Auto has never started the car app on this phone (it is hidden before it is asked for)" else "\n$log")
}

/**
 * What happened between Android Auto and the car app, kept on the phone (Android Auto itself logs nothing a phone can read): when the host started the
 * service, opened a session, asked for a screen, and any template it could not be given. The last fifteen lines, shown in Settings.
 */
internal object CarLog {
    private const val KEY = "car_log"

    private fun prefs(context: Context) = context.getSharedPreferences("simplemodelview", Context.MODE_PRIVATE)

    fun add(context: Context, line: String) {
        val stamp = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (prefs(context).getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() } + "$stamp  $line").takeLast(15)
        prefs(context).edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): String = prefs(context).getString(KEY, "").orEmpty()
}
