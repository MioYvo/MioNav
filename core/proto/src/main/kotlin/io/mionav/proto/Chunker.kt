package io.mionav.proto

/** Splits payloads that do not fit the negotiated MTU, and puts them back together. */
object Chunker {
    const val PREFIX = 4

    /** [maxPayload] is the largest payload a single frame may carry: mtu - 3 - Frame.HEADER. */
    fun chunk(frame: Frame, maxPayload: Int): List<Frame> {
        require(maxPayload > PREFIX + 1) { "maxPayload too small: $maxPayload" }
        val body = maxPayload - PREFIX
        if (frame.payload.size <= body) return listOf(frame)

        val total = (frame.payload.size + body - 1) / body
        require(total <= 0xFF) { "message too large to chunk: ${frame.payload.size} bytes" }

        return (0 until total).map { index ->
            val from = index * body
            val to = minOf(from + body, frame.payload.size)
            val out = ByteArray(PREFIX + (to - from))
            Le.putU16(out, 0, frame.seq)
            out[2] = index.toByte()
            out[3] = total.toByte()
            frame.payload.copyInto(out, PREFIX, from, to)
            Frame(frame.type, frame.seq, frame.flags or Flags.CHUNKED, out)
        }
    }
}

class Reassembler(
    private val timeoutMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Partial(
        val type: MsgType,
        val flags: Int,
        val seq: Int,
        val total: Int,
        val parts: Array<ByteArray?>,
        var updatedAt: Long,
    )

    private val pending = HashMap<Int, Partial>()

    fun accept(frame: Frame): Frame? {
        expire()
        if (frame.flags and Flags.CHUNKED == 0) return frame

        val p = frame.payload
        if (p.size < Chunker.PREFIX) throw ProtocolException("short chunk")
        val msgId = Le.u16(p, 0)
        val index = Le.u8(p, 2)
        val total = Le.u8(p, 3)
        if (total == 0 || index >= total) throw ProtocolException("bad chunk $index/$total")

        val partial = pending[msgId] ?: Partial(
            frame.type, frame.flags and Flags.CHUNKED.inv(), frame.seq, total,
            arrayOfNulls(total), clock(),
        ).also { pending[msgId] = it }

        if (partial.total != total) throw ProtocolException("chunk count changed")
        partial.parts[index] = p.copyOfRange(Chunker.PREFIX, p.size)
        partial.updatedAt = clock()

        if (partial.parts.any { it == null }) return null

        pending.remove(msgId)
        var size = 0
        for (part in partial.parts) size += part!!.size
        val merged = ByteArray(size)
        var at = 0
        for (part in partial.parts) {
            part!!.copyInto(merged, at)
            at += part.size
        }
        return Frame(partial.type, partial.seq, partial.flags, merged)
    }

    fun pendingCount(): Int = pending.size

    fun reset() = pending.clear()

    private fun expire() {
        val now = clock()
        pending.entries.removeAll { now - it.value.updatedAt > timeoutMs }
    }
}
