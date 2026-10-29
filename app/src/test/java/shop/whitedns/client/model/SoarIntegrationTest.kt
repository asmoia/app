package shop.whitedns.client.model

import java.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import shop.whitedns.client.storm.SoarConfigRenderer
import shop.whitedns.client.storm.StormDnsConfigRenderer
import shop.whitedns.client.runtime.RuntimeLaunchRequest
import shop.whitedns.client.runtime.RuntimeLaunchRequestStore

class SoarIntegrationTest {
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun profile() = ConnectionProfile(
        id = "soar-test", name = "Soar test", engine = DnsClientEngine.Soar,
        customServerDomain = "t.example.com", customServerEncryptionKey = publicKey,
        soarSettings = SoarProfileSettings(transport = "only", maxInflight = 64, discovery = false),
    )
    private fun settings() = WhiteDnsSettings(
        selectedConnectionProfileId = "soar-test", connectionProfiles = listOf(profile()),
        resolverText = "1.1.1.1", listenIp = "0.0.0.0", protocolType = "TCP",
    ).syncSelectedConnectionProfileFields()

    @Test fun engineSelectionPreservesLegacyAndSoar() {
        assertEquals(DnsClientEngine.Soar, DnsClientEngine.normalize("Soar"))
        assertEquals(DnsClientEngine.CottenDns, DnsClientEngine.normalize("cotton"))
        assertEquals(DnsClientEngine.StormDns, DnsClientEngine.normalize("masterdns"))
    }

    @Test fun publicKeyAndSingleZoneAreRequired() {
        assertTrue(isValidSoarServer("t.example.com", publicKey))
        assertFalse(isValidSoarServer("t.example.com,other.example.com", publicKey))
        assertFalse(isValidSoarServer("https://t.example.com", publicKey))
        assertFalse(isValidSoarServer("t.example.com", Base64.getEncoder().encodeToString(ByteArray(64))))
    }

    @Test fun advancedSettingsRoundTripAndBounds() {
        val value = SoarProfileSettings.preset("conservative").copy(transport = "only", country = "")
        assertEquals(value, soarProfileSettingsFromJson(value.toJson()))
        val clamped = soarProfileSettingsFromJson(JSONObject().put("sessionIdleSeconds", 10000).put("maxInflight", 100000))
        assertEquals(110, clamped.sessionIdleSeconds)
        assertEquals(1024, clamped.maxInflight)
        assertEquals(SoarProfileSettings(), soarProfileSettingsFromJson(null))
    }

    @Test fun linkRoundTripRetainsSoarSettingsAndPublicKey() {
        val settings = settings()
        val link = settings.exportStormDnsProfileLink(profile())
        assertTrue(link.startsWith("soar://"))
        val payload = JSONObject(String(Base64.getUrlDecoder().decode(link.substringAfter("://"))))
        assertTrue(payload.getJSONObject("profile").getJSONObject("server").has("public_key"))
        assertFalse(payload.getJSONObject("profile").getJSONObject("server").has("encryption_key"))
        val imported = WhiteDnsSettings().importStormDnsProfileLink(link).selectedConnectionProfile()
        assertEquals(DnsClientEngine.Soar, imported.engine)
        assertEquals(publicKey, imported.customServerEncryptionKey)
        assertEquals(profile().soarSettings, imported.soarSettings)
    }

    @Test fun runtimeForcesSocksAndLoopbackWithoutChangingStoredPreferences() {
        val settings = settings()
        val runtime = settings.runtimeConnectionSettings()
        assertEquals("SOCKS5", runtime.protocolType)
        assertEquals("127.0.0.1", runtime.listenIp)
        assertFalse(runtime.localDnsEnabled)
        assertEquals("0.0.0.0", settings.listenIp)
        assertEquals(profile().soarSettings, runtime.selectedConnectionProfile().soarSettings)
    }

    @Test fun engineJsonContainsOnlySoarTransportKnobs() {
        val p = profile()
        val json = JSONObject(SoarConfigRenderer.render(StormDnsServerProfile(
            id = p.id, label = p.name, domain = p.customServerDomain,
            encryptionKey = publicKey, encryptionMethod = 0, engine = DnsClientEngine.Soar,
            soarSettings = p.soarSettings,
        ), settings()))
        assertEquals(publicKey, json.getString("server_public_key"))
        assertEquals("only", json.getJSONObject("soar").getString("transport"))
        assertFalse(json.has("ENCRYPTION_KEY"))
        assertFalse(json.has("DATA_ENCRYPTION_METHOD"))
        assertTrue(json.getString("listen").startsWith("127.0.0.1:"))
    }

    @Test fun scanWorkersProbeOneResolverEachWithoutChangingConnectionDiscovery() {
        for (concurrency in listOf(2, 16)) {
            val p = profile()
            val options = p.soarSettings.copy(discoveryConcurrency = concurrency)
            val server = StormDnsServerProfile(
                p.id, p.name, p.customServerDomain, publicKey, 0,
                engine = DnsClientEngine.Soar, soarSettings = options,
            )
            val scan = JSONObject(StormDnsConfigRenderer.renderScanClientToml(server, settings()))
            val connection = JSONObject(StormDnsConfigRenderer.renderClientToml(server, settings()))

            assertEquals(1, scan.getJSONObject("soar").getInt("discoveryConcurrency"))
            assertEquals(options.copy(discoveryConcurrency = 1),
                soarProfileSettingsFromJson(scan.getJSONObject("soar")))
            assertEquals(options, soarProfileSettingsFromJson(connection.getJSONObject("soar")))
            assertEquals(options, server.soarSettings)
            assertEquals(publicKey, scan.getString("server_public_key"))
        }
    }

    @Test fun soarImportBindsPortableResolversWithoutReplacingLegacyList() {
        val previous = WhiteDnsSettings(
            resolverProfiles = listOf(ResolverProfile.defaultProfile("192.0.2.1")),
            selectedResolverProfileId = ResolverProfile.DefaultId,
            resolverText = "192.0.2.1",
        )
        val link = settings().copy(resolverText = "1.1.1.1\n8.8.8.8").exportStormDnsProfileLink(profile())
        val imported = previous.importStormDnsProfileLink(link)
        assertEquals("1.1.1.1\n8.8.8.8", imported.resolverText)
        assertEquals(imported.selectedResolverProfileId, imported.selectedConnectionProfile().resolverProfileId)
        assertEquals("192.0.2.1", imported.resolverProfiles.first { it.id == ResolverProfile.DefaultId }.resolverText)
    }

    @Test fun olderSoarLinkGetsSmallBootstrapInsteadOfInheritingLargeList() {
        val link = settings().exportStormDnsProfileLink(profile())
        val root = JSONObject(String(Base64.getUrlDecoder().decode(link.substringAfter("://"))))
        root.getJSONObject("profile").remove("resolvers")
        val oldLink = "soar://" + Base64.getUrlEncoder().withoutPadding().encodeToString(root.toString().toByteArray())
        val imported = WhiteDnsSettings(resolverText = "192.0.2.1").importStormDnsProfileLink(oldLink)
        assertEquals("1.1.1.1", imported.resolverText)
        assertFalse(imported.selectedConnectionProfile().resolverProfileId.isBlank())
    }

    @Test fun malformedPortableResolversAreRejected() {
        val link = settings().exportStormDnsProfileLink(profile())
        val root = JSONObject(String(Base64.getUrlDecoder().decode(link.substringAfter("://"))))
        root.getJSONObject("profile").put("resolvers", JSONArray(listOf("not-a-resolver")))
        val bad = "soar://" + Base64.getUrlEncoder().withoutPadding().encodeToString(root.toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) { WhiteDnsSettings().importStormDnsProfileLink(bad) }
        assertEquals("IR", SoarProfileSettings().country)
    }

    @Test fun selectingOlderProfileAfterImportRestoresItsServerAndLinkedResolvers() {
        val legacy = ConnectionProfile(id = "legacy", name = "Old connection",
            customServerDomain = "old.example.com", customServerEncryptionKey = "legacy-key",
            resolverProfileId = "legacy-resolvers")
        val original = WhiteDnsSettings(
            connectionProfiles = listOf(legacy), selectedConnectionProfileId = legacy.id,
            resolverProfiles = listOf(ResolverProfile(id = "legacy-resolvers", name = "Old DNS", resolverText = "192.0.2.1")),
        ).selectConnectionProfile(legacy.id)
        val imported = original.importStormDnsProfileLink(settings().exportStormDnsProfileLink(profile()))
        val newProfile = imported.selectedConnectionProfile()
        val oldSelection = imported.selectConnectionProfile(legacy.id)
        assertEquals(legacy.id, oldSelection.selectedConnectionProfileId)
        assertEquals(DnsClientEngine.StormDns, oldSelection.selectedConnectionProfile().engine)
        assertEquals("old.example.com", oldSelection.customServerDomain)
        assertEquals("legacy-key", oldSelection.customServerEncryptionKey)
        assertEquals("192.0.2.1", oldSelection.resolverText)
        val reselected = oldSelection.selectConnectionProfile(newProfile.id)
        assertEquals(newProfile, reselected.selectedConnectionProfile())
        assertEquals(publicKey, reselected.customServerEncryptionKey)
        assertEquals("1.1.1.1", reselected.resolverText)
        assertEquals(2, reselected.connectionProfiles.size)
    }

    @Test fun discoveryOnlyIsAvailableWithoutWeakeningLegacyValidation() {
        val discovering = settings().copy(connectionProfiles = listOf(profile().copy(
            soarSettings = SoarProfileSettings(),
        )), resolverText = "")
        assertTrue(discovering.canDiscoverSoarResolvers())
        assertFalse(settings().canDiscoverSoarResolvers())
        assertFalse(WhiteDnsSettings().canDiscoverSoarResolvers())
        assertEquals(90, SoarProfileSettings().dialTimeoutSeconds)
    }

    @Test fun rendererIncludesOnlyTransientNetworkDNSWhenSupplied() {
        val p = profile()
        val server = StormDnsServerProfile(p.id, p.name, p.customServerDomain, publicKey, 0,
            engine = DnsClientEngine.Soar, soarSettings = p.soarSettings)
        val json = JSONObject(SoarConfigRenderer.render(server, settings(), listOf("192.168.1.1", "192.168.1.1")))
        assertEquals(1, json.getJSONArray("system_resolvers").length())
        assertEquals("192.168.1.1", json.getJSONArray("system_resolvers").getString(0))
        val link = settings().exportStormDnsProfileLink(p)
        assertFalse(String(Base64.getUrlDecoder().decode(link.substringAfter("://"))).contains("system_resolvers"))
    }

    @Test fun runtimeLaunchRoundTripKeepsSoarOptionsResolversAndProxyAuth() {
        val options = SoarProfileSettings.preset("conservative").copy(country = "IR", discoveryConcurrency = 6, dialTimeoutSeconds = 120)
        val p = profile().copy(soarSettings = options)
        val original = settings().copy(connectionProfiles = listOf(p), resolverText = "192.0.2.2\n198.51.100.3",
            socks5Authentication = true, socksUsername = "test-user", socksPassword = "test-password").runtimeConnectionSettings()
        val server = StormDnsServerProfile(p.id, p.name, p.customServerDomain, publicKey, 0,
            engine = DnsClientEngine.Soar, soarSettings = options)
        val request = RuntimeLaunchRequest("roundtrip", server, original)
        val decoded = RuntimeLaunchRequestStore.decode(RuntimeLaunchRequestStore.encode(request))
        assertEquals(options, decoded.serverProfile.soarSettings)
        assertEquals(options, decoded.settings.selectedConnectionProfile().soarSettings)
        assertEquals(original.resolverText, decoded.settings.resolverText)
        assertEquals("SOCKS5", decoded.settings.protocolType)
        assertTrue(decoded.settings.socks5Authentication)
        assertEquals("test-password", decoded.settings.socksPassword)
    }
}
