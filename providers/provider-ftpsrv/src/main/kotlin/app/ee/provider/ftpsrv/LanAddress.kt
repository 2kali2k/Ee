package app.ee.provider.ftpsrv

import java.net.Inet4Address
import java.net.NetworkInterface

/** Best-effort local IPv4 for LAN share URLs. Pure JVM — unit-testable. */
object LanAddress {

    fun localIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrNull()?.toList()
            ?: return null
        for (iface in interfaces) {
            val up = runCatching { iface.isUp }.getOrDefault(false)
            if (!up) continue
            if (iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
