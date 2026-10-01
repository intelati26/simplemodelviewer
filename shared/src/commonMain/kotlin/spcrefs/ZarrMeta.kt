package spcrefs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val GRID_COLUMNS = 1799
const val GRID_ROWS = 1059

data class ArrayInfo(
    val name: String,
    val shape: List<Int>,
    val chunks: List<Int>,
    val dtype: String,        // "<f2", "<f4", "<i4", "|u1", "<U9", ...
    val compressed: Boolean,  // blosc
    val fillValue: Double?,   // null when absent; NaN for "NaN"
    val dims: List<String>,
    val units: String,
) {
    /** A field on the CONUS grid: (time, y, x) or (member, time, y, x). */
    val isGridField: Boolean
        get() = shape.size >= 3 && shape[shape.size - 1] == GRID_COLUMNS && shape[shape.size - 2] == GRID_ROWS
}

/** Reads the consolidated Zarr v2 metadata (`.zmetadata`). Throws [IllegalStateException] with a reason on an unexpected layout. */
fun parseZMetadata(text: String): Map<String, ArrayInfo> {
    val root = Json.parseToJsonElement(text).jsonObject
    val metadata = root["metadata"] as? JsonObject ?: error("the Zarr metadata has no \"metadata\" section")
    val out = LinkedHashMap<String, ArrayInfo>()
    for ((key, value) in metadata) {
        if (!key.endsWith("/.zarray")) continue
        val name = key.removeSuffix("/.zarray")
        val z = value.jsonObject
        val compressor = z["compressor"]
        val compressorId = (compressor as? JsonObject)?.get("id")?.jsonPrimitive?.content
        if (compressor is JsonObject && compressorId != "blosc") error("array $name uses an unsupported compressor ($compressorId)")
        val fill = z["fill_value"] as? JsonPrimitive
        val attrs = metadata["$name/.zattrs"] as? JsonObject
        out[name] = ArrayInfo(
            name = name,
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
