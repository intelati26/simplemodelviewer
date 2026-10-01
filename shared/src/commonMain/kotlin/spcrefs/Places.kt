package spcrefs

import kotlin.math.abs
import kotlin.math.roundToInt

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
