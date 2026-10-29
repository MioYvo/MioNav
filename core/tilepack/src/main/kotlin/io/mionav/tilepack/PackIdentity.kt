package io.mionav.tilepack

/**
 * What both ends need to know about a pack to decide whether they agree. Deliberately small:
 * the watch reads these four values from HTTP headers, so nothing here needs a JSON parser.
 */
data class PackIdentity(
    val id: String,
    val version: String,
    val bytes: Long,
    val sha256: String,
    val tiles: Int = 0,
) {
    /** Four fields, not the whole record: a tile count that differs is not worth a redownload. */
    fun samePack(other: PackIdentity): Boolean =
        id == other.id && version == other.version && bytes == other.bytes && sha256 == other.sha256
}
