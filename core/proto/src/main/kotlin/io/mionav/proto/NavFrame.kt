package io.mionav.proto

/**
 * Fixed 48-byte navigation state, sent at 1 Hz.
 *
 * It is state, not events: a lost packet is replaced by the next one, so NAV never asks for an ACK.
 */
class NavFrame(
    val iconType: Int = 0,
    val flags: Int = 0,
    val stepDistM: Int = 0,
    val remainDist10m: Int = 0,
    val remainTimeMin: Int = 0,
    val speedKmh: Int = 0,
    val limitKmh: Int = 0,
    val svcDist100m: Int = 0,
    val roadId: Int = 0,
    val nextRoadId: Int = 0,
    val laneUsable: Int = 0,
    val laneRec: Int = 0,
    val cameraDistM: Int = 0,
    val cameraType: Int = 0,
    val crossFlag: Int = 0,
    val etaMin: Int = 0,
    val sceneVersion: Int = 0,
    val tsMs: Long = 0,
    /** Current fix, WGS84 degrees * 1e7. Zero means "no fix yet". */
    val latE7: Int = 0,
    val lonE7: Int = 0,
    /** Travel direction, degrees * 100, clockwise from north. The watch may override it with its compass. */
    val bearingDeg100: Int = 0,
) {
    init {
        require(iconType in 0..0xFF)
        require(flags in 0..0xFF)
        require(stepDistM in 0..0xFFFF)
        require(remainDist10m in 0..0xFFFF)
        require(remainTimeMin in 0..0xFFFF)
        require(speedKmh in 0..0xFF)
        require(limitKmh in 0..0xFF)
        require(svcDist100m in 0..0xFFFF)
        require(roadId in 0..0xFFFF)
        require(nextRoadId in 0..0xFFFF)
        require(laneUsable in 0..0xFF)
        require(laneRec in 0..0xFF)
        require(cameraDistM in 0..0xFFFF)
        require(cameraType in 0..0xFF)
        require(crossFlag in 0..0xFF)
        require(etaMin in 0..0xFFFF)
        require(sceneVersion in 0..0xFFFF)
        require(bearingDeg100 in 0..35_999)
    }

    fun encode(): ByteArray {
        val b = ByteArray(SIZE)
        Le.putU8(b, 0, iconType)
        Le.putU8(b, 1, flags)
        Le.putU16(b, 2, stepDistM)
        Le.putU16(b, 4, remainDist10m)
        Le.putU16(b, 6, remainTimeMin)
        Le.putU8(b, 8, speedKmh)
        Le.putU8(b, 9, limitKmh)
        Le.putU16(b, 10, svcDist100m)
        Le.putU16(b, 12, roadId)
        Le.putU16(b, 14, nextRoadId)
        Le.putU8(b, 16, laneUsable)
        Le.putU8(b, 17, laneRec)
        Le.putU16(b, 18, cameraDistM)
        Le.putU8(b, 20, cameraType)
        Le.putU8(b, 21, crossFlag)
        Le.putU16(b, 22, etaMin)
        Le.putU16(b, 24, sceneVersion)
        Le.putU32(b, 26, tsMs and 0xFFFF_FFFFL)
        // 30..47 used to be reserved; the position and bearing now live here, so SIZE stays 48.
        Le.putI32(b, 30, latE7)
        Le.putI32(b, 34, lonE7)
        Le.putU16(b, 38, bearingDeg100)
        return b
    }

    override fun equals(other: Any?): Boolean = other is NavFrame && other.encode().contentEquals(encode())

    override fun hashCode(): Int = encode().contentHashCode()

    override fun toString(): String =
        "NavFrame(icon=$iconType step=$stepDistM remain=${remainDist10m * 10}m " +
            "limit=$limitKmh svc=${svcDist100m * 100}m road=$roadId)"

    companion object {
        const val SIZE = 48

        fun decode(b: ByteArray): NavFrame {
            if (b.size < SIZE) throw ProtocolException("short nav frame: ${b.size}")
            return NavFrame(
                iconType = Le.u8(b, 0),
                flags = Le.u8(b, 1),
                stepDistM = Le.u16(b, 2),
                remainDist10m = Le.u16(b, 4),
                remainTimeMin = Le.u16(b, 6),
                speedKmh = Le.u8(b, 8),
                limitKmh = Le.u8(b, 9),
                svcDist100m = Le.u16(b, 10),
                roadId = Le.u16(b, 12),
                nextRoadId = Le.u16(b, 14),
                laneUsable = Le.u8(b, 16),
                laneRec = Le.u8(b, 17),
                cameraDistM = Le.u16(b, 18),
                cameraType = Le.u8(b, 20),
                crossFlag = Le.u8(b, 21),
                etaMin = Le.u16(b, 22),
                sceneVersion = Le.u16(b, 24),
                tsMs = Le.u32(b, 26),
                latE7 = Le.i32(b, 30),
                lonE7 = Le.i32(b, 34),
                bearingDeg100 = Le.u16(b, 38),
            )
        }
    }
}

object NavFlags {
    const val NAVIGATING = 0x01
    const val GPS_WEAK = 0x02
    const val RECALCULATING = 0x04
    const val ARRIVED = 0x08
    const val HAS_SERVICE_AREA = 0x10
    const val HAS_LANES = 0x20
}
