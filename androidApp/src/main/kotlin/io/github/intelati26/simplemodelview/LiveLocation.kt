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
