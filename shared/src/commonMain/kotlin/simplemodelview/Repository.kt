package simplemodelview

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One opened cycle: its arrays, the displayable products and the small coordinate arrays (times, members). */
class CycleStore(
    val cycle: Cycle,
    val arrays: Map<String, ArrayInfo>,
    val products: List<Product>,
)

class RefsException(message: String) : Exception(message)

/** Reads SPC's REFS Zarr stores over [http], decoding with [core]. No state beyond small per-cycle caches. */
class RefsRepository(private val http: Http, private val core: Core) {
    private val validTimes = HashMap<String, List<Long>>()
    private val memberNames = HashMap<String, List<String>>()

    /** The newest cycles that exist (probes `.zgroup`, newest first). [nowEpochSeconds] is the current UTC time. */
    suspend fun findCycles(nowEpochSeconds: Long, wanted: Int = 8): List<Cycle> {
        val days = nowEpochSeconds / 86400
        val z = days + 719468
        val era = z / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
        val hour = (nowEpochSeconds % 86400 / 3600).toInt()
        return findCycles(Cycle.latestAtOrBefore(y, m, d, hour), wanted) { http.exists(it) }
    }

    suspend fun open(cycle: Cycle): CycleStore {
        val result = http.get(cycle.storeUrl + "/.zmetadata")
        if (result.status != 200 || result.body.isEmpty())
            throw RefsException("no Zarr metadata for ${cycle.label} (HTTP ${result.status})")
        val arrays = try {
            parseZMetadata(result.body.decodeToString()).toMutableMap()
        } catch (e: IllegalStateException) {
            throw RefsException("unknown layout for ${cycle.label}: ${e.message}")
        } catch (e: Exception) {
            throw RefsException("the metadata for ${cycle.label} could not be read: ${e.message}")
        }
        // GREFS probabilities live in their own stores; a cycle without them (or a server hiccup) simply has no GREFS products
        for (kind in listOf(StoreKind.GREFS4) + if (cycle.hasLongRange) listOf(StoreKind.GREFS24) else emptyList()) {
            val extra = try { http.get(cycle.storeUrl(kind) + "/.zmetadata") } catch (e: Exception) { null }
            if (extra != null && extra.status == 200 && extra.body.isNotEmpty()) {
                try { arrays += parseZMetadata(extra.body.decodeToString(), kind) } catch (_: Exception) { }
            }
        }
        val products = Catalogue.build(arrays.values)
        if (products.isEmpty()) throw RefsException("no products found in ${cycle.label}")
        return CycleStore(cycle, arrays, products)
    }

    /** Downloads and decodes one frame: field values on the grid (NaN where the server has no chunk). */
    suspend fun readFrame(store: CycleStore, product: Product, timeIndex: Int, memberIndex: Int): FloatArray {
        val array = store.arrays.getValue(product.id)
        val index = if (product.hasMembers) listOf(memberIndex, timeIndex, 0, 0) else listOf(timeIndex, 0, 0)
        val cells = product.grid.cells
        val raw = fetchChunk(store.cycle, array, index) ?: return FloatArray(cells) { Float.NaN }
        return try {
            core.decodeFloats(raw, array.dtype, array.compressed, cells)
        } catch (e: Exception) {
            throw RefsException("could not decode ${product.id}: ${e.message}")
        }
    }

    /** Downloads a frame's chunk without decoding it, so an HTTP cache has it for the loop. */
    suspend fun prefetchFrame(store: CycleStore, product: Product, timeIndex: Int, memberIndex: Int) {
        val array = store.arrays.getValue(product.id)
        fetchChunk(store.cycle, array, if (product.hasMembers) listOf(memberIndex, timeIndex, 0, 0) else listOf(timeIndex, 0, 0))
    }

    /** Valid times (epoch seconds, NaN-free) of a product's time dimension; cached per cycle. */
    suspend fun validTimes(store: CycleStore, product: Product): List<Long> {
        val name = product.timeDimension ?: return emptyList()
        val array = store.arrays[name] ?: return emptyList()
        val key = store.cycle.storeUrl(array.kind) + "/" + name
        validTimes[key]?.let { return it }
        val chunk = maxOf(1, array.chunks.firstOrNull() ?: 1)
        val count = array.shape.firstOrNull() ?: 0
        // every time value is its own tiny file: fetch them a few at a time instead of one after another
        val gate = Semaphore(6)
        val chunks = (0 until (count + chunk - 1) / chunk)
        val values = coroutineScope {
            chunks.map { i ->
                async {
                    gate.withPermit {
                        val raw = fetchChunk(store.cycle, array, listOf(i)) ?: throw RefsException("no time chunk $name/$i")
                        val bytes = if (array.compressed) core.inflate(raw) else raw
                        (0 until minOf(chunk, count - i * chunk)).map { k -> readTime(bytes, k * 8, array.dtype) }
                    }
                }
            }.awaitAll().flatten()
        }
        validTimes[key] = values
        return values
    }

    /** Member names (paintball bit order) for 4-D products; empty when the store has none. */
    suspend fun members(store: CycleStore): List<String> {
        val array = store.arrays["member"] ?: return emptyList()
        val key = store.cycle.storeUrl
        memberNames[key]?.let { return it }
        val raw = fetchChunk(store.cycle, array, listOf(0)) ?: return emptyList()
        val bytes = if (array.compressed) core.inflate(raw) else raw
        val chars = array.dtype.drop(2).toIntOrNull() ?: return emptyList()
        val names = (0 until array.shape[0]).map { m ->
            val sb = StringBuilder()
            for (c in 0 until chars) {
                val at = (m * chars + c) * 4
                if (at + 4 > bytes.size) break
                val code = u32(bytes, at)
                if (code == 0) break
                sb.append(if (code < 128) code.toChar() else '?')
            }
            sb.toString()
        }
        memberNames[key] = names
        return names
    }

    /** Chunk bytes, or null for a chunk the server never wrote (404 / 403: treated as the fill value). */
    private suspend fun fetchChunk(cycle: Cycle, array: ArrayInfo, index: List<Int>): ByteArray? {
        val url = "${cycle.storeUrl(array.kind)}/${array.path}/${chunkKey(index)}"
        var result = http.get(url)
        if (result.status != 200 && result.status != 404 && result.status != 403) result = http.get(url)   // one retry
        return when (result.status) {
            200 -> if (result.body.isEmpty()) throw RefsException("empty chunk ${array.name}/${chunkKey(index)}") else result.body
            404, 403 -> null
            else -> throw RefsException("chunk ${array.name}/${chunkKey(index)} could not be downloaded (HTTP ${result.status})")
        }
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)

    private fun readTime(b: ByteArray, at: Int, dtype: String): Long {
        var bits = 0L
        for (k in 7 downTo 0) bits = (bits shl 8) or (b[at + k].toLong() and 255)
        return if (dtype.contains("f")) Double.fromBits(bits).toLong() else bits
    }
}

