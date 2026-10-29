package shop.whitedns.client.runtime

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class LocalProxyEndpointTest {
    @Test
    fun wildcardAndBracketedAddressesUseTheMatchingFamily() {
        assertEquals("127.0.0.1", localProxyHost(""))
        assertEquals("127.0.0.1", localProxyHost("0.0.0.0"))
        assertEquals("::1", localProxyHost(" [::] "))
        assertEquals("::1", localProxyHost("[::1]"))
        assertEquals("192.0.2.10", localProxyHost(" 192.0.2.10 "))
    }

    @Test
    fun readinessUsesTheConfiguredIpv4AddressAndDetectsClosure() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.2"))
        val port = server.localPort
        try {
            assertTrue(canConnectToLocalProxy("127.0.0.2", port))
            assertFalse(canConnectToLocalProxy("127.0.0.1", port))
        } finally {
            server.close()
        }
        assertFalse(canConnectToLocalProxy("127.0.0.2", port))
    }

    @Test
    fun readinessReachesIpv6WithoutFallingBackToIpv4() {
        ServerSocket(0, 8, InetAddress.getByName("::1")).use { server ->
            assertTrue(canConnectToLocalProxy("::1", server.localPort))
            assertTrue(canConnectToLocalProxy("[::1]", server.localPort))
            assertTrue(canConnectToLocalProxy("::", server.localPort))
            assertFalse(canConnectToLocalProxy("127.0.0.1", server.localPort))
        }
    }

    @Test
    fun invalidPortsCannotBecomeReady() {
        assertFalse(canConnectToLocalProxy("127.0.0.1", 0))
        assertFalse(canConnectToLocalProxy("::1", 65_536))
    }
}
