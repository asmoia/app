package shop.whitedns.client.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionsTest {
    private val url = "https://panel.example.com/sub/private-token"
    private fun link(id: String = "account-1", key: Int = 7, name: String = "Subscription account"): String {
        val keyText = Base64.getEncoder().encodeToString(ByteArray(32) { key.toByte() })
        val json = JSONObject().put("schema", "whitedns.profile").put("version", 1)
            .put("profile", JSONObject().put("id", id).put("name", name)
                .put("server", JSONObject().put("domain", "user.soar.example.org").put("public_key", keyText))
                .put("resolvers", org.json.JSONArray(listOf("1.1.1.1", "8.8.8.8"))))
        return "soar://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }

    @Test fun acceptsPlainAndBase64AndStandardMetadata() {
        val plain = link() + "\n"
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray())
        val document = parseSubscription(url, encoded, mapOf(
            "Subscription-Userinfo" to "upload=123; download=456; total=10737418240; expire=2000000000",
            "Profile-Update-Interval" to "12",
        ), nowMillis = 99)
        assertEquals(parseSubscription(url, plain).entries, document.entries)
        assertEquals(123, document.source.upload)
        assertEquals(456, document.source.download)
        assertEquals(2_000_000_000_000, document.source.expiresAt)
        assertEquals(99, document.source.updatedAt)
        assertEquals("account-1", document.entries.single().remoteId)
    }

    @Test fun refreshUpdatesKeysWithoutDuplicatesAndPreservesLocalPreferences() {
        val manual = ConnectionProfile("manual", "My manual profile", customServerDomain = "manual.example.org")
        val original = WhiteDnsSettings(connectionProfiles = listOf(manual), connectionMode = "vpn")
            .applySubscription(parseSubscription(url, link()), selectImported = true)
        val imported = original.selectedConnectionProfile()
        val withOverride = original.copy(
            connectionProfiles = original.connectionProfiles.map { if (it.id == imported.id) it.copy(resolverProfileId = "personal-dns") else it },
            resolverProfiles = original.resolverProfiles + ResolverProfile("personal-dns", "My resolvers", "9.9.9.9"),
            themeMode = "light", languageCode = "fa",
        ).syncSelectedConnectionProfileFields()
        val next = withOverride.applySubscription(parseSubscription(url, link(key = 8)))
        assertEquals(2, next.connectionProfiles.size)
        assertEquals(imported.id, next.selectedConnectionProfileId)
        assertEquals("personal-dns", next.selectedConnectionProfile().resolverProfileId)
        assertEquals("9.9.9.9", next.resolverText)
        assertEquals("vpn", next.connectionMode)
        assertEquals("light", next.themeMode)
        assertEquals("fa", next.languageCode)
        assertEquals(withOverride.connectionProfiles.first { it.id == manual.id }, next.connectionProfiles.first { it.id == manual.id })
        assertNotEquals(imported.customServerEncryptionKey, next.selectedConnectionProfile().customServerEncryptionKey)
        val again = next.applySubscription(parseSubscription(url, link(key = 8)))
        assertEquals(2, again.connectionProfiles.size)
        assertEquals(again.resolverProfiles.size, next.resolverProfiles.size)
        assertFalse(again.exportStormDnsProfileLink().contains("https://"))
    }

    @Test fun preservesOtherSelectionAndRemovesOnlyMissingSubscriptionProfiles() {
        val first = WhiteDnsSettings().applySubscription(parseSubscription(url, link("a") + "\n" + link("b")))
        val local = first.connectionProfiles.first { it.subscription == null }
        val next = first.copy(selectedConnectionProfileId = local.id).applySubscription(parseSubscription(url, link("b")))
        assertEquals(local.id, next.selectedConnectionProfileId)
        assertEquals(listOf("b"), next.connectionProfiles.mapNotNull { it.subscription?.remoteId })
        assertEquals(1, next.resolverProfiles.size)
    }

    @Test fun rejectsInvalidAndPartialDocumentsWithoutChangingExistingSettings() {
        val current = WhiteDnsSettings().applySubscription(parseSubscription(url, link()))
        val before = current.copy()
        for (body in listOf("<html>Error</html>", "", link() + "\nhttps://untrusted.example/", link() + "\n" + link(), "X".repeat(1024 * 1024 + 1))) {
            assertTrue(runCatching { current.applySubscription(parseSubscription(url, body)) }.isFailure)
            assertEquals(before, current)
        }
    }

    @Test fun sourceSerializationAndUrlCanonicalization() {
        val source = parseSubscription("HTTPS://PANEL.EXAMPLE.COM:443/sub/a%2Fb?x=a%2Bb", link(), nowMillis = 42).source
        assertEquals("https://panel.example.com/sub/a%2Fb?x=a%2Bb", source.url)
        assertEquals(source, SubscriptionSource.fromJson(source.toJson()))
        assertNull(SubscriptionSource.fromJson(null))
        for (address in listOf("file:///etc/passwd", "https://user:password@example.org/sub/a", "https://example.org/sub/a#fragment", "https://example.org:0/sub/a")) {
            assertTrue(runCatching { canonicalSubscriptionUrl(address) }.isFailure)
        }
    }

    @Test fun fetchRequiresHttpConsentAndHandlesRedirectsLimitsAndRevocation() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun response(path: String, status: Int, text: String) {
            server.createContext(path) { exchange ->
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        response("/ok", 200, link())
        response("/revoked", 404, "not found")
        response("/inactive", 403, "disabled")
        response("/oversize", 200, "X".repeat(1024 * 1024 + 1))
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            assertTrue(runCatching { fetchSubscription("$root/ok") }.isFailure)
            assertEquals(1, fetchSubscription("$root/ok", true).entries.size)
            assertEquals("$root/redirect", fetchSubscription("$root/redirect", true).source.url)
            for (path in listOf("/revoked", "/inactive", "/oversize", "/loop")) {
                val failure = runCatching { fetchSubscription(root + path, true) }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure!!.message.orEmpty().contains(root))
            }
        } finally { server.stop(0) }
    }
}
