package io.github.intelati26.simplemodelview

import kotlinx.coroutines.CancellationException
import simplemodelview.Cycle
import simplemodelview.CycleStore
import simplemodelview.Grid
import simplemodelview.RefsRepository
import simplemodelview.epochSeconds

/** The 500 mb height of one REFS time step: its values (m) on the run's own grid, and the run it came from ("2026-11-28 00Z"). */
class SynopticHeight(val values: FloatArray, val grid: Grid, val run: String, val lead: Int, val valid: Long)

/**
 * The 500 mb height under the MRMS radar: SPC's REFS (the RRFS ensemble) mean for the hour nearest a surface analysis, from the newest run that has it. A run that has
 * only just started has no chunk for the hour yet, so the next older one is used. Each hour is one chunk (about 1 MB); stores and cycle lists are kept.
 */
class SynopticHeights(private val repo: RefsRepository) {
    private var cycles: List<Cycle> = emptyList()
    private var cyclesAt = 0L
    private val stores = LinkedHashMap<Cycle, CycleStore>()

    private suspend fun cyclesNow(now: Long): List<Cycle> {
        if (cycles.isEmpty() || now - cyclesAt > 600) { cycles = repo.findCycles(now, 6); cyclesAt = now }
        return cycles
    }

    /** The height at [valid] (epoch seconds), or null when no run has the hour (or there is no network). */
    suspend fun at(valid: Long, now: Long): SynopticHeight? {
        val list = try { cyclesNow(now) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        for (c in list.filter { it.epochSeconds <= valid }.take(3)) {
            val store = stores[c] ?: (try { repo.open(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }).also { stores[c] = it; while (stores.size > 4) stores.remove(stores.keys.first()) }
            val product = store.products.firstOrNull { it.id == "z500_mean" } ?: continue
            val times = try { repo.validTimes(store, product) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val step = times.indices.minByOrNull { Math.abs(times[it] - valid) } ?: continue
            if (Math.abs(times[step] - valid) > 1800) continue
            val frame = try { repo.readFrameTimed(store, product, step, 0) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            if (frame.fileTime == null) continue
            return SynopticHeight(frame.values, product.grid, c.label, ((times[step] - c.epochSeconds) / 3600).toInt(), times[step])
        }
        return null
    }
}
