package io.mionav.pack

import org.json.JSONObject

/**
 * One offline region. Written by `motohud-pack`, read by phone and watch.
 * `style` names the palette the tiles were built for (6-class road set).
 */
data class PackManifest(
    val id: String,
    val name: String,
    val bbox: List<Double>,
    val minZoom: Int,
    val maxZoom: Int,
    val version: String,
    val tiles: Int,
    val bytes: Long,
    val sha256: String,
    val style: String,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("bbox", bbox)
        .put("zoom", listOf(minZoom, maxZoom))
        .put("version", version)
        .put("tiles", tiles)
        .put("bytes", bytes)
        .put("sha256", sha256)
        .put("style", style)
        .toString()

    companion object {
        fun parse(text: String): PackManifest {
            val o = JSONObject(text)
            val zoom = o.getJSONArray("zoom")
            val bbox = o.getJSONArray("bbox")
            return PackManifest(
                id = o.getString("id"),
                name = o.getString("name"),
                bbox = (0 until bbox.length()).map { bbox.getDouble(it) },
                minZoom = zoom.getInt(0),
                maxZoom = zoom.getInt(1),
                version = o.getString("version"),
                tiles = o.getInt("tiles"),
                bytes = o.getLong("bytes"),
                sha256 = o.getString("sha256"),
                style = o.optString("style", "motohud-6class-v1"),
            )
        }
    }
}
