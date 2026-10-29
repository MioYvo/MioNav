package io.mionav.tilepack

import java.io.File

/**
 * The identity of an installed pack, written next to it. Deliberately plain text: this module has
 * no JSON dependency, and the phone is the only side that parses JSON.
 */
object PackSidecar {

    fun write(file: File, identity: PackIdentity) {
        val text = buildString {
            append("id=").append(identity.id).append('\n')
            append("version=").append(identity.version).append('\n')
            append("bytes=").append(identity.bytes).append('\n')
            append("sha256=").append(identity.sha256).append('\n')
            append("tiles=").append(identity.tiles).append('\n')
        }
        file.writeText(text)
    }

    fun read(file: File): PackIdentity? {
        if (!file.isFile) return null
        val values = file.readLines()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toMap()
        val id = values["id"] ?: return null
        val version = values["version"] ?: return null
        val bytes = values["bytes"]?.toLongOrNull() ?: return null
        val sha = values["sha256"] ?: return null
        return PackIdentity(id, version, bytes, sha, values["tiles"]?.toIntOrNull() ?: 0)
    }
}
