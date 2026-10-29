package io.mionav.proto

/**
 * Connection-time exchange. The watch says which offline pack it holds; the phone answers with a
 * URL when — and only when — that pack is out of date.
 *
 * Both directions use this layout. Empty strings mean "nothing to say":
 * a watch without a pack sends no id/version, a phone that will not serve sends no url.
 */
class HelloFrame(
    val flags: Int = 0,
    val packId: String = "",
    val packVersion: String = "",
    val packBytes: Long = 0,
    val packTiles: Int = 0,
    val packSha: String = "",
    val url: String = "",
) {
    init {
        require(flags in 0..0xFF)
        require(url.length <= MAX_URL)
        require(packId.length <= MAX_ID)
        require(packVersion.length <= MAX_VERSION)
        require(packSha.length <= MAX_SHA)
        require(packBytes in 0..0xFFFF_FFFFL)
        require(packTiles >= 0)
    }

    val hasPack: Boolean
        get() = packId.isNotEmpty() && packVersion.isNotEmpty()

    fun encode(): ByteArray {
        val id = packId.toByteArray(Charsets.UTF_8)
        val version = packVersion.toByteArray(Charsets.UTF_8)
        val sha = packSha.toByteArray(Charsets.UTF_8)
        val link = url.toByteArray(Charsets.UTF_8)
        val size = 2 + 1 + id.size + 1 + version.size + 4 + 4 + 1 + sha.size + 1 + link.size
        val b = ByteArray(size)
        var at = 0
        Le.putU8(b, at, VERSION)
        at += 1
        Le.putU8(b, at, flags)
        at += 1
        Le.putU8(b, at, id.size)
        at += 1
        id.copyInto(b, at)
        at += id.size
        Le.putU8(b, at, version.size)
        at += 1
        version.copyInto(b, at)
        at += version.size
        Le.putU32(b, at, packBytes)
        at += 4
        Le.putU32(b, at, packTiles.toLong() and 0xFFFF_FFFFL)
        at += 4
        Le.putU8(b, at, sha.size)
        at += 1
        sha.copyInto(b, at)
        at += sha.size
        Le.putU8(b, at, link.size)
        at += 1
        link.copyInto(b, at)
        return b
    }

    override fun equals(other: Any?): Boolean = other is HelloFrame && other.encode().contentEquals(encode())

    override fun hashCode(): Int = encode().contentHashCode()

    override fun toString(): String =
        "HelloFrame(flags=$flags pack=$packId/$packVersion ${packBytes}B url=${if (url.isEmpty()) "-" else url})"

    companion object {
        const val VERSION = 1
        const val MAX_ID = 63
        const val MAX_VERSION = 31
        const val MAX_URL = 127
        const val MAX_SHA = 64

        const val FLAG_HAS_PACK = 0x01
        const val FLAG_SERVING = 0x02

        /** Null on any malformed or unknown-version payload: HELLO is optional, never fatal. */
        fun tryDecode(b: ByteArray): HelloFrame? = try {
            decode(b)
        } catch (_: Exception) {
            null
        }

        fun decode(b: ByteArray): HelloFrame {
            if (b.size < 3) throw ProtocolException("short hello: ${b.size}")
            val version = Le.u8(b, 0)
            if (version != VERSION) throw ProtocolException("unknown hello version $version")
            val flags = Le.u8(b, 1)
            var at = 2

            fun readString(limit: Int): String {
                val length = Le.u8(b, at)
                at += 1
                if (length > limit) throw ProtocolException("hello field too long: $length")
                if (at + length > b.size) throw ProtocolException("hello truncated")
                val text = String(b, at, length, Charsets.UTF_8)
                at += length
                return text
            }

            val packId = readString(MAX_ID)
            val packVersion = readString(MAX_VERSION)
            if (at + 8 > b.size) throw ProtocolException("hello truncated at pack stats")
            val packBytes = Le.u32(b, at)
            at += 4
            val packTiles = Le.u32(b, at).toInt()
            at += 4
            val packSha = readString(MAX_SHA)
            val url = readString(MAX_URL)
            return HelloFrame(flags, packId, packVersion, packBytes, packTiles, packSha, url)
        }
    }
}
