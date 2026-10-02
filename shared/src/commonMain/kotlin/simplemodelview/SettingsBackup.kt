package simplemodelview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupPlace(val name: String, val lat: Double, val lon: Double)

/**
 * What "Export settings" writes and "Import settings" reads: the user's own preferences, as readable JSON. Nothing about the data
 * provider's files, and no device identifiers. [format] lets a later version refuse or migrate an old file.
 */
@Serializable
data class SettingsBackup(
    val app: String = APP,
    val format: Int = FORMAT,
    val wifiOnlyLoop: Boolean = false,
    val dataSaver: Boolean = false,
    val cacheMegabytes: Int = 150,
    val contactEmail: String = "",
    val favourites: List<String> = emptyList(),
    val places: List<BackupPlace> = emptyList(),
    val product: String? = null,
    val sector: String? = null,
) {
    companion object {
        const val APP = "simplemodelview"
        const val FORMAT = 1
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(backup: SettingsBackup): String = json.encodeToString(serializer(), backup)

        /**
         * Parses and validates a backup. Null when the text is not JSON, is not a simplemodelview backup, or is from a newer format.
         * Values are cleaned rather than trusted: an invalid email is dropped, the cache size is limited to a sensible range, places
         * outside the globe or with absurd names are skipped, lists are capped.
         */
        fun decode(text: String): SettingsBackup? {
            val raw = try { json.decodeFromString(serializer(), text) } catch (e: Exception) { return null }
            if (raw.app != APP || raw.format > FORMAT || raw.format < 1) return null
            return raw.copy(
                cacheMegabytes = raw.cacheMegabytes.coerceIn(10, 2000),
                contactEmail = raw.contactEmail.trim().takeIf { isValidEmail(it) } ?: "",
                favourites = raw.favourites.filter { it.isNotBlank() && it.length <= 200 }.distinct().take(500),
                places = raw.places.filter { it.name.isNotBlank() && it.name.length <= 100 && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
                    .distinctBy { it.name }.take(200),
                product = raw.product?.takeIf { it.length <= 200 },
                sector = raw.sector?.takeIf { s -> Sector.all.any { it.name == s } },
            )
        }
    }
}
