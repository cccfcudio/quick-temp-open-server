package app.openshelf.share

import java.net.Inet4Address
import java.net.NetworkInterface

object NetInfo {
    fun lanHosts(): List<String> {
        val hosts = ArrayList<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) {
                continue
            }
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress ?: continue
                    if (host != "0.0.0.0") {
                        hosts.add(host)
                    }
                }
            }
        }
        return hosts.distinct().sortedByDescending { privateLan(it) }
    }

    private fun privateLan(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true
        }
        if (!ip.startsWith("172.")) {
            return false
        }
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
