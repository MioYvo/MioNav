package io.mionav.proto

class ProtocolException(message: String) : Exception(message)

enum class MsgType(val code: Int) {
    HELLO(0x01),
    NAV(0x02),
    STR(0x03),
    SCENE(0x04),
    ACK(0x05),
    HINT(0x06),
    BYE(0x07),
    ROUTE(0x08);

    companion object {
        fun of(code: Int): MsgType =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown message type 0x%02x".format(code))
    }
}

object Flags {
    /** Payload starts with msgId:u16, index:u8, total:u8. */
    const val CHUNKED = 0x01
    const val ACK_REQUIRED = 0x02
}

/**
 * Wire layout, 11 byte header:
 *
 * ```
 * 0  magic0 0x4D
 * 1  magic1 0x48
 * 2  version
 * 3  type
 * 4  flags
 * 5  seq     u16
 * 7  len     u16  payload length
 * 9  crc16   u16  over type..len then payload (header bytes 3..8, then 11..)
 * ```
 */
class Frame(
    val type: MsgType,
    val seq: Int,
    val flags: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(seq in 0..0xFFFF) { "seq out of range: $seq" }
        require(flags in 0..0xFF) { "flags out of range: $flags" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        out[0] = MAGIC0.toByte()
        out[1] = MAGIC1.toByte()
        out[2] = VERSION.toByte()
        out[3] = type.code.toByte()
        out[4] = flags.toByte()
        Le.putU16(out, 5, seq)
        Le.putU16(out, 7, payload.size)
        payload.copyInto(out, HEADER)
        val crc = Crc16.update(Crc16.of(out, 3, 6), out, HEADER, payload.size)
        Le.putU16(out, 9, crc)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.seq == seq &&
            other.flags == flags && other.payload.contentEquals(payload)

    override fun hashCode(): Int =
        ((type.code * 31 + seq) * 31 + flags) * 31 + payload.contentHashCode()

    companion object {
        const val MAGIC0 = 0x4D
        const val MAGIC1 = 0x48
        const val VERSION = 1
        const val HEADER = 11
        const val MAX_PAYLOAD = 0xFFFF

        fun decode(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Frame {
            if (len < HEADER) throw ProtocolException("short frame: $len bytes")
            if (Le.u8(buf, off) != MAGIC0 || Le.u8(buf, off + 1) != MAGIC1) {
                throw ProtocolException("bad magic")
            }
            val version = Le.u8(buf, off + 2)
            if (version != VERSION) throw ProtocolException("unsupported version $version")
            val type = MsgType.of(Le.u8(buf, off + 3))
            val flags = Le.u8(buf, off + 4)
            val seq = Le.u16(buf, off + 5)
            val payloadLen = Le.u16(buf, off + 7)
            if (payloadLen != len - HEADER) {
                throw ProtocolException("length mismatch: header $payloadLen, actual ${len - HEADER}")
            }
            val expected = Le.u16(buf, off + 9)
            val actual = Crc16.update(
                Crc16.of(buf, off + 3, 6), buf, off + HEADER, payloadLen,
            )
            if (expected != actual) throw ProtocolException("crc mismatch")
            return Frame(type, seq, flags, buf.copyOfRange(off + HEADER, off + len))
        }

        fun tryDecode(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Frame? =
            try {
                decode(buf, off, len)
            } catch (_: ProtocolException) {
                null
            }
    }
}
