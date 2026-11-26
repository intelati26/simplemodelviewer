package simplemodelview

import kotlin.math.abs

/** A saved place (west-negative longitude). */
data class Place(val name: String, val lat: Double, val lon: Double)

/** One place per line: "name|lat|lon". Names may not contain '|' or newlines (they are replaced). */
fun encodePlaces(places: List<Place>): String =
    places.joinToString("\n") { "${it.name.replace('|', '/').replace('\n', ' ')}|${it.lat}|${it.lon}" }

fun decodePlaces(text: String): List<Place> = text.lines().mapNotNull { line ->
    val parts = line.split('|')
    if (parts.size != 3) return@mapNotNull null
    val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
    val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
    Place(parts[0], lat, lon)
}

/** "39.10N 97.20W" */
fun placeLabel(lat: Double, lon: Double) =
    "${fixed(abs(lat), 2)}${if (lat >= 0) "N" else "S"} ${fixed(abs(lon), 2)}${if (lon < 0) "W" else "E"}"

/** Whether a latitude / longitude is on the globe (west-negative longitudes). */
fun validLatLon(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

/**
 * [old] replaced by [edited] where it stands in the list. Names are the keys, so another place that already has the new name is dropped (the edit takes its
 * place); a blank name becomes the coordinates as a label.
 */
fun editPlace(places: List<Place>, old: Place, edited: Place): List<Place> {
    val named = edited.copy(name = edited.name.trim().ifBlank { placeLabel(edited.lat, edited.lon) })
    val at = places.indexOf(old)
    val others = places.filter { it != old && it.name != named.name }
    return if (at < 0) others + named else others.toMutableList().also { it.add(places.take(at).count { p -> p != old && p.name != named.name }, named) }
}
