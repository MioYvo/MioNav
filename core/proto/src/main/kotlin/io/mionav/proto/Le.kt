package io.mionav.proto

/** Little-endian reads and writes. All wire formats in this module are little-endian. */
internal object Le {
    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)

    fun i16(b: ByteArray, off: Int): Short = u16(b, off).toShort()

    fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)

    fun i32(b: ByteArray, off: Int): Int = u16(b, off) or (u16(b, off + 2) shl 16)

    fun putI32(b: ByteArray, off: Int, v: Int) = putU32(b, off, v.toLong() and 0xFFFF_FFFFL)

    fun putU8(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
    }

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putI16(b: ByteArray, off: Int, v: Short) = putU16(b, off, v.toInt() and 0xFFFF)

    fun putU32(b: ByteArray, off: Int, v: Long) {
        putU16(b, off, (v and 0xFFFF).toInt())
        putU16(b, off + 2, ((v ushr 16) and 0xFFFF).toInt())
    }
}
