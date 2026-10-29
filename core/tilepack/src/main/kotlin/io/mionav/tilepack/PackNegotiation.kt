package io.mionav.tilepack

enum class PackDecision {
    /** Both sides already hold the same pack: keep the port closed. */
    UpToDate,

    /** The watch is missing the pack or holds a different one. */
    Serve,

    /** The phone has nothing to hand out. */
    NoLocalPack,
}

/**
 * The one rule that decides whether the phone opens its HTTP server. Pure on purpose: it runs on
 * every BLE connection and is the only thing standing between "no port open" and "port open".
 */
object PackNegotiation {
    fun decide(local: PackIdentity?, remote: PackIdentity?): PackDecision = when {
        local == null -> PackDecision.NoLocalPack
        remote != null && local.samePack(remote) -> PackDecision.UpToDate
        else -> PackDecision.Serve
    }
}
