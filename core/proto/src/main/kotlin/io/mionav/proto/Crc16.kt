package io.mionav.proto

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final xor. */
object Crc16 {
    fun of(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): Int =
        update(0xFFFF, bytes, off, len)

    /** Continues a CRC over a non-contiguous region, e.g. header then payload. */
    fun update(seed: Int, bytes: ByteArray, off: Int, len: Int): Int {
        var crc = seed
        var i = off
        val end = off + len
        while (i < end) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
            i++
        }
        return crc
    }
}
