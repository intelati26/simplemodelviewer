package io.github.intelati26.simplemodelview

import android.content.SharedPreferences
import simplemodelview.formatClock

/**
 * How much has come over the network in the last 24 hours, in hourly buckets (so the oldest hour drops out as time passes), and the limit the user set in Settings
 * (0 = none). Only bytes that really came over the network count: a file served from the HTTP cache or the GRIB subset store costs nothing.
 */
class DataBudget(private val prefs: SharedPreferences, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val hours = HashMap<Long, Long>()      // hour (epoch / 3600) -> bytes
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        for ((key, value) in prefs.all) if (key.startsWith(PREFIX) && value is Long) key.removePrefix(PREFIX).toLongOrNull()?.let { hours[it] = value }
    }

    @Synchronized fun add(bytes: Long) {
        if (bytes <= 0) return
        load()
        val hour = clock() / 3600
        hours[hour] = (hours[hour] ?: 0L) + bytes
        val editor = prefs.edit().putLong(PREFIX + hour, hours.getValue(hour))
        for (old in hours.keys.filter { it < hour - 24 }) { hours.remove(old); editor.remove(PREFIX + old) }
        editor.apply()
    }

    /** Bytes used in the last 24 hours (this hour and the 23 before it, and the part of the one before that is counted whole: a slightly pessimistic window). */
    @Synchronized fun usedBytes(): Long {
        load()
        val hour = clock() / 3600
        return hours.filterKeys { it >= hour - 24 }.values.sum()
    }

    /** The limit in bytes, or 0 for none. */
    fun limitBytes(): Long = prefs.getInt("daily_limit_mb", 0).toLong() * 1_000_000L

    fun exceeded(): Boolean = limitBytes() > 0 && usedBytes() >= limitBytes()

    /** "Daily data limit reached: 1000 MB used in the last 24 hours. Room comes back from 14:00Z." */
    fun message(): String {
        val hour = clock() / 3600
        val oldest = hours.filterKeys { it >= hour - 24 }.keys.minOrNull()
        val back = oldest?.let { " Room comes back from ${formatClock((it + 25) * 3600)}." } ?: ""
        return "Daily data limit reached: ${usedBytes() / 1_000_000} MB used in the last 24 hours (limit ${limitBytes() / 1_000_000} MB).$back Cached frames still show; raise the limit in Settings."
    }

    private companion object { const val PREFIX = "bud_" }
}
