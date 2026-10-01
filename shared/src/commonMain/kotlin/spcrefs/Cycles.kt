package spcrefs

/** One REFS forecast cycle (init time in UTC). Cycles run at 00 / 06 / 12 / 18 UTC. */
data class Cycle(val year: Int, val month: Int, val day: Int, val hour: Int) {
    val hasLongRange: Boolean get() = hour == 0 || hour == 12   // 24-h and 48-h products

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** e.g. https://www.spc.noaa.gov/exper/refs/data/2026/10/01/refs-spc_20261001_1800.zarr */
    val storeUrl: String get() = storeUrl(StoreKind.REFS)

    /** The store of [kind] for this cycle (GREFS: grefs-4hr_..., and at 00 / 12 UTC also grefs-24hr_...). */
    fun storeUrl(kind: StoreKind): String =
        "$BASE/$year/${two(month)}/${two(day)}/${kind.filePrefix}_$year${two(month)}${two(day)}_${two(hour)}00.zarr"

    val label: String get() = "$year-${two(month)}-${two(day)} ${two(hour)}Z"

    /** The cycle six hours earlier. */
    fun previous(): Cycle {
        var (y, m, d, h) = listOf(year, month, day, hour)
        h -= 6
        if (h < 0) {
            h += 24
            d -= 1
            if (d < 1) {
                m -= 1
                if (m < 1) { m = 12; y -= 1 }
                d = daysIn(y, m)
            }
        }
        return Cycle(y, m, d, h)
    }

    companion object {
        const val BASE = "https://www.spc.noaa.gov/exper/refs/data"

        fun daysIn(year: Int, month: Int): Int = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        }

        /** The latest cycle at or before the given UTC time. */
        fun latestAtOrBefore(year: Int, month: Int, day: Int, hour: Int) =
            Cycle(year, month, day, hour / 6 * 6)
    }
}

/**
 * Finds the newest cycles that exist. The server cannot list folders, so probe `<store>/.zgroup` going back six hours at
 * a time. [exists] is the network check (HTTP 200); it is a parameter so this logic is testable without a network.
 */
suspend fun findCycles(start: Cycle, wanted: Int, maxProbes: Int = 20, exists: suspend (String) -> Boolean): List<Cycle> {
    val found = mutableListOf<Cycle>()
    var cycle = start
    var probes = 0
    while (found.size < wanted && probes < maxProbes) {
        if (exists(cycle.storeUrl + "/.zgroup")) found += cycle
        cycle = cycle.previous()
        probes += 1
    }
    return found
}
