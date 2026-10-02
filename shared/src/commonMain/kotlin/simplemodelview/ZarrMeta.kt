package simplemodelview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val GRID_COLUMNS = 1799   // the REFS grid (kept for the common case)
const val GRID_ROWS = 1059

/**
 * A grid the core can draw on: SPC's two fixed grids ([REFS], [GREFS]) or a regular latitude / longitude window the core registered for an
 * MRMS frame (id from 2). [id] matches the core's grid ids.
 */
data class Grid(val id: Int, val columns: Int, val rows: Int) {
    val cells get() = columns * rows

    /** A latitude / longitude window (MRMS) rather than one of SPC's grids. */
    val isWindow get() = id >= FIRST_WINDOW

    companion object {
        const val FIRST_WINDOW = 2
        val REFS = Grid(0, 1799, 1059)      // 3 km
        val GREFS = Grid(1, 185, 129)       // 40.6 km (NCEP grid 212): the calibrated probability fields (GREFS) and thunder probability (REFSCT)
        val entries = listOf(REFS, GREFS)

        /** The grid whose (rows, columns) are the last two entries of an array shape, or null. */
        fun matching(shape: List<Int>): Grid? =
            if (shape.size < 3) null else entries.firstOrNull { shape[shape.size - 1] == it.columns && shape[shape.size - 2] == it.rows }
    }
}

/** One Zarr store of a cycle. Arrays of the secondary stores are keyed "<prefix><array>" so names do not collide with REFS's. */
enum class StoreKind(val prefix: String, val filePrefix: String) {
    REFS("", "refs-spc"),
    GREFS4("grefs-4hr/", "grefs-4hr"),
    GREFS24("grefs-24hr/", "grefs-24hr"),
    REFSCT1("refsct-1hr/", "refsct-1hr"),           // thunder probability: 1-h, 4-h and remainder-of-convective-day periods
    REFSCT4("refsct-4hr/", "refsct-4hr"),
    REFSCT_FULL("refsct-full/", "refsct-full"),
}

data class ArrayInfo(
    val name: String,         // key in the cycle's array map: the array's path, prefixed for secondary stores
    val kind: StoreKind = StoreKind.REFS,
    val shape: List<Int>,
    val chunks: List<Int>,
    val dtype: String,        // "<f2", "<f4", "<i4", "|u1", "<U9", ...
    val compressed: Boolean,  // blosc
    val fillValue: Double?,   // null when absent; NaN for "NaN"
    val dims: List<String>,
    val units: String,
) {
    /** The array's path inside its store. */
    val path: String get() = name.removePrefix(kind.prefix)

    /** The grid this field is on: (time, y, x) or (member, time, y, x). Null for coordinates and other arrays. */
    val grid: Grid? get() = Grid.matching(shape)

    val isGridField: Boolean get() = grid != null
}

/** Reads the consolidated Zarr v2 metadata (`.zmetadata`). Throws [IllegalStateException] with a reason on an unexpected layout. */
fun parseZMetadata(text: String, kind: StoreKind = StoreKind.REFS): Map<String, ArrayInfo> {
    val root = Json.parseToJsonElement(text).jsonObject
    val metadata = root["metadata"] as? JsonObject ?: error("the Zarr metadata has no \"metadata\" section")
    val out = LinkedHashMap<String, ArrayInfo>()
    for ((key, value) in metadata) {
        if (!key.endsWith("/.zarray")) continue
        val path = key.removeSuffix("/.zarray")
        val name = kind.prefix + path
        val z = value.jsonObject
        val compressor = z["compressor"]
        val compressorId = (compressor as? JsonObject)?.get("id")?.jsonPrimitive?.content
        if (compressor is JsonObject && compressorId != "blosc") error("array $path uses an unsupported compressor ($compressorId)")
        val fill = z["fill_value"] as? JsonPrimitive
        val attrs = metadata["$path/.zattrs"] as? JsonObject
        out[name] = ArrayInfo(
            name = name,
            kind = kind,
            shape = z["shape"]!!.jsonArray.ints(),
            chunks = z["chunks"]!!.jsonArray.ints(),
            dtype = z["dtype"]!!.jsonPrimitive.content,
            compressed = compressorId == "blosc",
            fillValue = when {
                fill == null -> null
                fill.content == "NaN" -> Double.NaN
                else -> fill.doubleOrNull
            },
            dims = (attrs?.get("_ARRAY_DIMENSIONS") as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            units = attrs?.get("units")?.jsonPrimitive?.content ?: "",
        )
    }
    return out
}

private fun JsonArray.ints() = map { it.jsonPrimitive.intOrNull ?: 0 }

/** Chunk file name: indices joined with "." ("3.0.0", "2.5.0.0"). */
fun chunkKey(index: List<Int>): String = index.joinToString(".")
