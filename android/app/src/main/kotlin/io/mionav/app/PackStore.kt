package io.mionav.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One pack per directory: `region.mbtiles` plus `manifest.json`, matching design §12.5.
 * Importing is the only write path; everything else reads.
 */
class PackEntry(
    val dir: File,
    val tiles: File,
    val manifestFile: File,
    val manifest: io.mionav.pack.PackManifest,
) {
    val identity: io.mionav.tilepack.PackIdentity =
        io.mionav.tilepack.PackIdentity(manifest.id, manifest.version, manifest.bytes, manifest.sha256, manifest.tiles)
}

class PackStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.filesDir, "packs")

    fun current(): PackEntry? =
        (root.listFiles() ?: emptyArray())
            .mapNotNull { dir -> entry(dir) }
            .maxByOrNull { it.manifest.version }

    fun entry(dir: File): PackEntry? {
        val tiles = File(dir, "region.mbtiles")
        val manifestFile = File(dir, "manifest.json")
        if (!tiles.isFile || !manifestFile.isFile) return null
        val manifest = runCatching { io.mionav.pack.PackManifest.parse(manifestFile.readText()) }.getOrNull() ?: return null
        return PackEntry(dir, tiles, manifestFile, manifest)
    }

    /**
     * Copies a picked `.mbtiles` in and writes a manifest when the file arrives without one, so a
     * bare MBTiles download is enough for the user.
     */
    fun import(uri: Uri, displayName: String?): Result<PackEntry> = runCatching {
        val name = (displayName ?: "region.mbtiles").substringBeforeLast('.').ifBlank { "region" }
        val id = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "region" }
        val dir = File(root, id).apply { mkdirs() }

        val staging = File(dir, "region.mbtiles.import")
        val input = resolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source -> staging.outputStream().use { sink -> source.copyTo(sink, 64 * 1024) } }
        require(staging.length() > 0) { "所选文件是空的" }

        val bytes = staging.length()
        val sha = io.mionav.tilepack.PackVerifier.sha256(staging)
        val tiles = io.mionav.pack.TileStore.count(staging.absolutePath)
        require(tiles > 0) { "文件里没有瓦片，可能不是 MBTiles" }

        val metadata = io.mionav.pack.TileStore.metadataOf(staging.absolutePath)
        val bbox = parseBounds(metadata["bounds"])
        val minZoom = metadata["minzoom"]?.toIntOrNull() ?: 0
        val maxZoom = metadata["maxzoom"]?.toIntOrNull() ?: 22
        val version = metadata["version"]?.takeIf { it.matches(Regex("[0-9.\\-]{4,}")) }
            ?: SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())

        val manifest = io.mionav.pack.PackManifest(
            id = id,
            name = name,
            bbox = bbox,
            minZoom = minZoom,
            maxZoom = maxZoom,
            version = version,
            tiles = tiles,
            bytes = bytes,
            sha256 = sha,
            style = "motohud-6class-v1",
        )
        File(dir, "manifest.json").writeText(manifest.toJson())

        val target = File(dir, "region.mbtiles")
        if (target.exists()) target.delete()
        require(staging.renameTo(target)) { "无法把导入的文件放到 packs/$id" }

        entry(dir) ?: error("导入后读不回 manifest")
    }

    private fun parseBounds(text: String?): List<Double> {
        val parts = text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
        return if (parts != null && parts.size == 4) parts else listOf(-180.0, -85.0, 180.0, 85.0)
    }
}
