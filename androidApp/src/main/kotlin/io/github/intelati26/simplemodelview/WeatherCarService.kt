package io.github.intelati26.simplemodelview

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The Android Auto (and Android Automotive) entry point: a weather app in the Car App Library's weather category. The category is in beta: apps can go
 * to Play's testing tracks, production release comes later. See docs/android-auto.md.
 */
class WeatherCarService : CarAppService() {
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

/**
 * A test entry in the points-of-interest category, which every Android Auto offers (the weather category is in beta and may not be listed). If this shows in the
 * car's launcher and the weather entry does not, the weather category is what the host is hiding. It shows the host's car API level.
 */
class DiagnosticCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            CarInfoScreen(carContext, "The car app starts. Host car API level ${carContext.carAppApiLevel}; the radar needs 7. If the weather entry is missing, the host hides that category.")
    }
}

internal class CarInfoScreen(carContext: androidx.car.app.CarContext, private val text: String) : Screen(carContext) {
    override fun onGetTemplate(): androidx.car.app.model.Template = androidx.car.app.model.PaneTemplate.Builder(
        androidx.car.app.model.Pane.Builder().addRow(androidx.car.app.model.Row.Builder().setTitle(text).build()).build(),
    ).setHeader(androidx.car.app.model.Header.Builder().setTitle("simplemodelview").setStartHeaderAction(androidx.car.app.model.Action.APP_ICON).build()).build()
}
