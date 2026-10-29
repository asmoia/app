package shop.whitedns.client.runtime

import java.net.InetSocketAddress
import java.net.Socket

// Wildcard bind addresses need a connectable loopback in the same family.
internal fun localProxyHost(listenIp: String): String =
    when (val host = listenIp.trim().removeSurrounding("[", "]")) {
        "", "0.0.0.0" -> "127.0.0.1"
        "::" -> "::1"
        else -> host
    }

internal fun canConnectToLocalProxy(listenIp: String, port: Int): Boolean {
    if (port !in 1..65535) return false
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(localProxyHost(listenIp), port), 300)
        }
        true
    }.getOrDefault(false)
}
