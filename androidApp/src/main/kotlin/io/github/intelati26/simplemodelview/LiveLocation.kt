package io.github.intelati26.simplemodelview

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import simplemodelview.Place

/**
 * The device's position while "My location" is on: the platform's location manager (GPS and network providers), no extra libraries. A fix is
 * only asked for every half minute or 300 m, which is plenty for a crosshair on a weather map and easy on the battery.
 */
object LiveLocation {
    const val NAME = "My location"

    private val _fix = MutableStateFlow<Place?>(null)
    val fix: StateFlow<Place?> = _fix
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var manager: LocationManager? = null

    private val listener = LocationListener { location: Location -> _fix.value = Place(NAME, location.latitude, location.longitude) }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_active.value || !hasPermission(context)) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm
        _active.value = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(provider)) continue
            try {
                lm.getLastKnownLocation(provider)?.let { if (_fix.value == null) listener.onLocationChanged(it) }
                lm.requestLocationUpdates(provider, 30_000L, 300f, listener, Looper.getMainLooper())
            } catch (_: Exception) { }
        }
    }

    /** One position now, for reading the map where you are without turning "My location" on: the newest last-known fix, else the next one a provider gives (about 20 seconds at most). */
    @SuppressLint("MissingPermission")
    fun once(context: Context, onResult: (Place?) -> Unit) {
        if (!hasPermission(context)) { onResult(null); return }
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run { onResult(null); return }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) { onResult(Place(NAME, last.latitude, last.longitude)); return }
        if (providers.isEmpty()) { onResult(last?.let { Place(NAME, it.latitude, it.longitude) }); return }
        var done = false
        val handler = android.os.Handler(Looper.getMainLooper())
        val once = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (!done) { done = true; lm.removeUpdates(this); onResult(Place(NAME, location.latitude, location.longitude)) } }
            @Deprecated("old callback") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in providers) try { lm.requestLocationUpdates(p, 0L, 0f, once, Looper.getMainLooper()) } catch (_: Exception) { }
        handler.postDelayed({ if (!done) { done = true; lm.removeUpdates(once); onResult(last?.let { Place(NAME, it.latitude, it.longitude) }) } }, 20_000L)
    }

    fun stop() {
        manager?.removeUpdates(listener)
        manager = null
        _active.value = false
        _fix.value = null
    }
}

/** The "My location (live)" switch of the Places dialog: [on], and [set] (asks for the permission the first time). */
class LiveControl(val on: Boolean, val set: (Boolean) -> Unit)

/** The switch and its effect: while it is on, every new fix goes to [onFix] (which puts the crosshair there). */
@Composable
fun rememberLiveControl(onFix: (Place) -> Unit): LiveControl {
    val context = LocalContext.current
    val active by LiveLocation.active.collectAsStateWithLifecycle()
    val fix by LiveLocation.fix.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) LiveLocation.start(context)
    }
    LaunchedEffect(fix) { fix?.let(onFix) }
    return LiveControl(active) { on ->
        if (!on) LiveLocation.stop()
        else if (LiveLocation.hasPermission(context)) LiveLocation.start(context)
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
