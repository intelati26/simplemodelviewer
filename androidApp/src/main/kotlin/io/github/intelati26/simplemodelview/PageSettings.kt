package io.github.intelati26.simplemodelview

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import simplemodelview.BackupPlace
import simplemodelview.Place
import simplemodelview.SettingsBackup

/** The settings every page reads, saves, exports and imports the same way (one place; the preference keys are the ones always used). */
object PageSettings {
    fun read(prefs: SharedPreferences): Settings = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(),
        prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    fun save(prefs: SharedPreferences, settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours)
            .putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
    }

    /** What a settings file holds: the settings, favorites and places, the page's product and sector (default: the saved ones), and the display and source choices. */
    fun backup(prefs: SharedPreferences, settings: Settings, favorites: Collection<String>, places: List<Place>, product: String? = prefs.getString("product", null), sector: String? = prefs.getString("sector", null)): SettingsBackup =
        SettingsTransfer.withPrefs(SettingsBackup(
            wifiOnlyLoop = settings.wifiOnlyLoop, dataSaver = settings.dataSaver, cacheMegabytes = settings.cacheMegabytes, contactEmail = settings.contactEmail,
            favorites = favorites.sorted(), places = places.map { BackupPlace(it.name, it.lat, it.lon) }, product = product, sector = sector,
        ), prefs)

    /** Writes [backup] to the file the user chose; the notice to show. */
    suspend fun export(resolver: ContentResolver, uri: Uri, backup: SettingsBackup): String =
        if (writeSettingsFile(resolver, uri, backup)) "Settings exported: ${backup.favorites.size} favorites, ${backup.places.size} places." else "Could not write the settings file."

    /**
     * Reads a settings file the user chose and applies what every page shares: the settings (through [apply], so the page redraws as it should), the favorites
     * and the places. Null (after [notice]) when nothing changed: a rain history file, or not a simplemodelview backup. The page then applies its own part.
     */
    suspend fun import(resolver: ContentResolver, uri: Uri, prefs: SharedPreferences, current: Settings, notice: (String) -> Unit, apply: (Settings) -> Unit): SettingsBackup? {
        RainHistory.importIfArchive(resolver, uri)?.let { note -> notice(note); return null }      // (a rain history file from the export: no restart)
        val b = readSettingsFile(resolver, uri)
        if (b == null) { notice("That file is not a simplemodelview settings file."); return null }
        apply(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).apply()
        AppContainer.places.replaceAll(b.places.map { Place(it.name, it.lat, it.lon) })
        return b
    }

    /** "Settings imported: 3 favorites, 2 places, 4 display and source choices." */
    fun importedNotice(b: SettingsBackup, extras: Int) =
        "Settings imported: ${b.favorites.size} favorites, ${b.places.size} places" + (if (extras > 0) ", $extras display and source choices" else "") + "."
}
