package shop.whitedns.client.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shop.whitedns.client.model.resolve

class HttpProxyBridgeTest {
    @Test
    fun parseHttpProxyHostPortUsesExplicitPort() {
        assertEquals("example.com" to 8443, parseHttpProxyHostPort("example.com:8443", defaultPort = 443))
    }

    @Test
    fun parseHttpProxyHostPortUsesDefaultPort() {
        assertEquals("example.com" to 443, parseHttpProxyHostPort("example.com", defaultPort = 443))
    }

    @Test
    fun parseHttpProxyHostPortSupportsBracketedIpv6() {
        assertEquals("2001:db8::1" to 443, parseHttpProxyHostPort("[2001:db8::1]:443", defaultPort = 80))
    }

    @Test
    fun parseHttpProxyHostPortRejectsInvalidPort() {
        assertNull(parseHttpProxyHostPort("example.com:99999", defaultPort = 443))
    }
}

class TunnelHttpVerificationTest {
    @Test
    fun socksAcceptanceWithoutRemoteBytesDoesNotVerifyInternetAccess() {
        org.junit.Assert.assertFalse(probeThroughFakeSocks { })
    }

    @Test
    fun fragmentedHttpStatusVerifiesTheRemoteRoute() {
        org.junit.Assert.assertTrue(probeThroughFakeSocks { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 ".toByteArray())
                flush()
                Thread.sleep(20)
                write("302 Found\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        })
    }

    @Test
    fun nonHttpRemoteBytesDoNotVerifyInternetAccess() {
        org.junit.Assert.assertFalse(probeThroughFakeSocks { socket ->
            socket.getOutputStream().write("not an HTTP reply\r\n".toByteArray())
        })
    }

    @Test
    fun timedOutRemoteReadDoesNotVerifyInternetAccess() {
        org.junit.Assert.assertFalse(probeThroughFakeSocks { Thread.sleep(4_300) })
    }

    private fun probeThroughFakeSocks(reply: (java.net.Socket) -> Unit): Boolean {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        assertEquals(5, input.read())
                        val count = input.read()
                        repeat(count) { org.junit.Assert.assertTrue(input.read() >= 0) }
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        // The existing verification endpoint is an IPv4 SOCKS
                        // destination, followed by a complete HTTP HEAD request.
                        repeat(10) { org.junit.Assert.assertTrue(input.read() >= 0) }
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                        output.flush()
                        val request = StringBuilder()
                        while (!request.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            org.junit.Assert.assertTrue(next >= 0)
                            request.append(next.toChar())
                            org.junit.Assert.assertTrue(request.length < 4_096)
                        }
                        reply(socket)
                    }
                }
                val settings = shop.whitedns.client.model.WhiteDnsSettings(
                    listenIp = "127.0.0.1", listenPort = server.localPort.toString(),
                )
                val verified = shop.whitedns.client.runtime.WhiteDnsTrafficWarmup.verifySocksRoute(
                    settings.resolve(),
                )
                peer.get(10, java.util.concurrent.TimeUnit.SECONDS)
                return verified
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
