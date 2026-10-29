package io.mionav.proto

data class StringEntry(val id: Int, val text: String)

/**
 * Road names travel as ids. The table is append-only within a session, so a delta is
 * "everything after the id the watch already knows".
 */
class StringTable {
    private val byText = LinkedHashMap<String, Int>()
    private val byId = ArrayList<String>()

    fun intern(text: String): Int {
        if (text.isEmpty()) return 0
        byText[text]?.let { return it }
        val id = byId.size + 1
        byId += text
        byText[text] = id
        return id
    }

    fun lookup(id: Int): String? = byId.getOrNull(id - 1)

    fun entriesAfter(lastKnownId: Int): List<StringEntry> =
        (lastKnownId until byId.size).map { StringEntry(it + 1, byId[it]) }

    val size: Int get() = byId.size

    companion object {
        const val MAX_TEXT = 120

        fun encode(entries: List<StringEntry>): ByteArray {
            require(entries.size <= 0xFF)
            var size = 1
            val payload = ArrayList<ByteArray>(entries.size)
            for (e in entries) {
                val utf8 = e.text.toByteArray(Charsets.UTF_8)
                require(utf8.size in 1..MAX_TEXT) { "bad text length ${utf8.size}" }
                payload += utf8
                size += 3 + utf8.size
            }
            val b = ByteArray(size)
            Le.putU8(b, 0, entries.size)
            var at = 1
            for ((e, utf8) in entries.zip(payload)) {
                Le.putU16(b, at, e.id); at += 2
                Le.putU8(b, at++, utf8.size)
                utf8.copyInto(b, at); at += utf8.size
            }
            return b
        }

        fun decode(b: ByteArray): List<StringEntry> {
            if (b.isEmpty()) throw ProtocolException("empty string table")
            val count = Le.u8(b, 0)
            var at = 1
            val out = ArrayList<StringEntry>(count)
            repeat(count) {
                if (at + 3 > b.size) throw ProtocolException("truncated string entry")
                val id = Le.u16(b, at); at += 2
                val len = Le.u8(b, at++)
                if (len !in 1..MAX_TEXT) throw ProtocolException("bad text length $len")
                if (at + len > b.size) throw ProtocolException("truncated string body")
                out += StringEntry(id, String(b, at, len, Charsets.UTF_8))
                at += len
            }
            if (at != b.size) throw ProtocolException("trailing bytes in string table")
            return out
        }
    }
}
