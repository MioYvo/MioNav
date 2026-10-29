package io.mionav.app

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address the watch can reach. Enumerating interfaces needs no permission; a hotspot usually
 * shows up as 192.168.43.x with the phone on .1.
 */
object LanAddress {

    fun current(): String? = addresses().firstOrNull()

    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}
