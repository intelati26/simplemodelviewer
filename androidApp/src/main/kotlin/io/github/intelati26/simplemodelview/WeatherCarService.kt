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
        override fun onCreateScreen(intent: Intent): Screen = RadarCarScreen(carContext)
    }
}
