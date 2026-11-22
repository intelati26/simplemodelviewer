package simplemodelview

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class BackupPlace(val name: String, val lat: Double, val lon: Double)

/**
 * The preferences a backup carries besides the named ones: the display and behavior choices ("ui") and where data comes from ("sources": the placefile links and
 * the GFS crop switch). Each key has a kind and, for numbers, a range; anything else in a file is dropped when it is read.
 */
object BackupPrefs {
    enum class Kind { BOOL, INT, TEXT, LINK }

    class Spec(val kind: Kind, val min: Int = 0, val max: Int = 0)

    private fun bool() = Spec(Kind.BOOL)
    private fun int(min: Int, max: Int) = Spec(Kind.INT, min, max)
    private fun text() = Spec(Kind.TEXT)

    /** How the app looks and behaves: units, barbs, contours, counties, loops, each page's product, sector, level and color. */
    val ui: Map<String, Spec> = linkedMapOf(
        "barb_density" to int(1, 100), "contours" to bool(), "counties" to int(0, 2), "minimal_ui" to bool(), "options_in_export" to bool(), "show_timings" to bool(), "log_timings" to bool(),
        "u_celsius" to bool(), "u_km" to bool(), "u_metric_length" to bool(), "video_size" to text(), "loop_from_shown" to bool(),
        "loop_frames" to int(0, 1000), "mrms_loop_frames" to int(0, 1000), "gfs_loop_hours" to int(-1000, 1000), "nbm_loop_hours" to int(-1000, 1000), "gfs_history_days" to int(1, 2000),
        "page" to text(), "product" to text(), "sector" to text(), "member_name" to text(), "wind_color" to text(), "wind_level" to int(10, 1000),
        "mrms_product" to text(), "mrms_sector" to text(), "mrms_overlays" to text(), "mrms_alerts" to text(), "overlay_hail" to text(), "overlay_rotation" to text(), "outlook_type" to text(), "mrms_under_t" to bool(), "mrms_under_td" to bool(), "mrms_stations" to bool(),
        "gfs_product" to text(), "gfs_sector" to text(), "gfs_level" to int(10, 1000), "gfs_color" to text(), "nbm_product" to text(), "nbm_sector" to text(), "analysis_product" to text(), "analysis_sector" to text(),
    )

    /** Where data comes from: the pasted placefile links (https only) and whether each is on, and the GFS crop switch (NOMADS cropped, else whole AWS files). */
    val sources: Map<String, Spec> = linkedMapOf(
        "lightningcast_url" to Spec(Kind.LINK), "lightningcast" to bool(), "probsevere_url" to Spec(Kind.LINK), "probsevere" to bool(), "gfs_crop" to bool(),
    )

    private fun clean(spec: Spec, value: JsonElement): JsonElement? {
        val p = value as? JsonPrimitive ?: return null
        return when (spec.kind) {
            Kind.BOOL -> p.booleanOrNull?.let { JsonPrimitive(it) }
            Kind.INT -> p.intOrNull?.takeIf { it in spec.min..spec.max }?.let { JsonPrimitive(it) }
            Kind.TEXT -> if (p.isString && p.content.length <= 600) JsonPrimitive(p.content) else null
            Kind.LINK -> if (p.isString && p.content.length <= 600 && (p.content.isEmpty() || (p.content.startsWith("https://") && ' ' !in p.content))) JsonPrimitive(p.content) else null
        }
    }

    /** The entries of [map] that are allowed by [specs] and valid for their kind. */
    fun clean(specs: Map<String, Spec>, map: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, spec) in specs) map[key]?.let { v -> clean(spec, v)?.let { out[key] = it } }
        return out
    }
}

/**
 * What "Export settings" writes and "Import settings" reads: the user's own preferences, as readable JSON. Nothing about the data
 * provider's files, and no device identifiers. [format] lets a later version refuse or migrate an old file.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SettingsBackup(
    val app: String = APP,
    val format: Int = FORMAT,
    val wifiOnlyLoop: Boolean = false,
    val dataSaver: Boolean = false,
    val cacheMegabytes: Int = 150,
    val contactEmail: String = "",
    @JsonNames("favourites")        // (files written before the spelling change)
    val favorites: List<String> = emptyList(),
    val places: List<BackupPlace> = emptyList(),
    val product: String? = null,
    val sector: String? = null,
    val ui: Map<String, JsonElement> = emptyMap(),          // (format 2) display and behavior choices, see [BackupPrefs.ui]
    val sources: Map<String, JsonElement> = emptyMap(),     // (format 2) where data comes from, see [BackupPrefs.sources]
) {
    companion object {
        const val APP = "simplemodelview"
        const val FORMAT = 2
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
                favorites = raw.favorites.filter { it.isNotBlank() && it.length <= 200 }.distinct().take(500),
                places = raw.places.filter { it.name.isNotBlank() && it.name.length <= 100 && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
                    .distinctBy { it.name }.take(200),
                product = raw.product?.takeIf { it.length <= 200 },
                sector = raw.sector?.takeIf { s -> Sector.all.any { it.name == s } },
                ui = BackupPrefs.clean(BackupPrefs.ui, raw.ui),
                sources = BackupPrefs.clean(BackupPrefs.sources, raw.sources),
            )
        }
    }
}
