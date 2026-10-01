package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CyclesTest {
    @Test fun storeUrl() = assertEquals(
        "https://www.spc.noaa.gov/exper/refs/data/2026/10/01/refs-spc_20261001_1800.zarr",
        Cycle(2026, 10, 1, 18).storeUrl,
    )

    @Test fun previousCrossesMonthAndYear() {
        assertEquals(Cycle(2026, 9, 30, 18), Cycle(2026, 10, 1, 0).previous())
        assertEquals(Cycle(2025, 12, 31, 18), Cycle(2026, 1, 1, 0).previous())
        assertEquals(Cycle(2024, 2, 29, 18), Cycle(2024, 3, 1, 0).previous())
    }

    @Test fun latestRoundsDown() = assertEquals(Cycle(2026, 10, 1, 12), Cycle.latestAtOrBefore(2026, 10, 1, 17))

    @Test fun probesNewestFirstAndSkipsMissing() = runTest {
        val have = setOf(Cycle(2026, 10, 1, 12), Cycle(2026, 10, 1, 0)).map { it.storeUrl + "/.zgroup" }.toSet()
        val found = findCycles(Cycle(2026, 10, 1, 18), wanted = 2) { it in have }
        assertEquals(listOf(Cycle(2026, 10, 1, 12), Cycle(2026, 10, 1, 0)), found)
    }

    @Test fun longRangeOnlyAt00And12() {
        assertEquals(listOf(true, false, true, false), listOf(0, 6, 12, 18).map { Cycle(2026, 1, 1, it).hasLongRange })
    }
}
