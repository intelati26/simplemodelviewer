package io.github.intelati26.simplemodelview

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import simplemodelview.Place
import simplemodelview.decodePlaces
import simplemodelview.editPlace
import simplemodelview.encodePlaces
import simplemodelview.placeLabel
import simplemodelview.validLatLon

/**
 * The saved places, one list for every page (REFS, MRMS, NBM, GFS, Analysis, Extended): a page sees a change at once, and none keeps a copy of its own to
 * bring up to date. Kept in the app's preferences ("places", one place per line) as before, so a saved list carries over and travels in a settings backup.
 */
class PlacesStore(private val prefs: SharedPreferences) {
    private val _places = MutableStateFlow(decodePlaces(prefs.getString("places", "").orEmpty()))
    val places: StateFlow<List<Place>> = _places

    private fun write(list: List<Place>) {
        prefs.edit().putString("places", encodePlaces(list)).apply()
        _places.value = list
    }

    fun save(name: String, lat: Double, lon: Double) =
        write(_places.value.filter { it.name != name } + Place(name.ifBlank { placeLabel(lat, lon) }, lat, lon))

    fun delete(place: Place) = write(_places.value - place)

    /** [old] with a new name and / or position (names are the keys; its place in the list stays). */
    fun edit(old: Place, name: String, lat: Double, lon: Double) {
        if (validLatLon(lat, lon)) write(editPlace(_places.value, old, Place(name, lat, lon)))
    }

    /** The whole list at once (a settings import). */
    fun replaceAll(list: List<Place>) = write(list)
}
