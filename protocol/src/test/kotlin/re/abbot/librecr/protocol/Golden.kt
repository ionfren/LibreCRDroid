package re.abbot.librecr.protocol

import org.json.JSONArray
import org.json.JSONObject

/** Loads the Swift-exported golden vectors from test resources. */
object Golden {
    val root: JSONObject by lazy {
        val stream = Golden::class.java.getResourceAsStream("/golden/librecr_vectors.json")
            ?: error("missing /golden/librecr_vectors.json")
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    fun arr(key: String): JSONArray = root.getJSONArray(key)
    fun obj(key: String): JSONObject = root.getJSONObject(key)
}

/** uint32-as-Int read (JSON stores them as non-negative Longs). */
fun JSONObject.u32(key: String): Int = getLong(key).toInt()

fun JSONObject.bytes(key: String): ByteArray = hexToBytes(getString(key))
