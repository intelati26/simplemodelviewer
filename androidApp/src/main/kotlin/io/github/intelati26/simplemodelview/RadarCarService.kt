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
    // any host while the app is a debug build (the Desktop Head Unit); in a release build only the hosts the library lists as Google's
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        // the map template needs car API level 7; an older Android Auto would hide the whole app silently if the manifest demanded it, so the manifest asks
        // for level 1 and this says what is wrong instead
        override fun onCreateScreen(intent: Intent): Screen =
            if (carContext.carAppApiLevel >= RADAR_API_LEVEL) RadarCarScreen(carContext) else CarInfoScreen(carContext, "Update Android Auto: the radar needs car API level $RADAR_API_LEVEL, this host has ${carContext.carAppApiLevel}.")
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
    return "Car service: " + (if (service == null) "NOT FOUND" else "found ($categories)") +
        "\nPermissions: $permissions" +
        "\nAndroid Auto: " + (auto ?: "not installed (or not visible)")
}
