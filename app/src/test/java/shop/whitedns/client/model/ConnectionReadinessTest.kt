package shop.whitedns.client.model

import org.junit.Assert.*
import org.junit.Test

class ConnectionReadinessTest {
    private fun settings(profile: ConnectionProfile, mode: String) = WhiteDnsSettings(
        selectedConnectionProfileId = profile.id,
        connectionProfiles = listOf(profile.copy(connectionMode = mode)),
        connectionMode = mode,
    ).runtimeConnectionSettings()

    @Test
    fun appAndTileResolverGateAllowsMaxDiscoveryInBothModes() {
        val max = ConnectionProfile(
            id = "max", name = "Max", engine = DnsClientEngine.CottenDns,
            cottenSettings = CottenDnsProfileSettings(
                tunnelMode = "cottenmax",
                cottenMaxOptions = CottenMaxOptions(discoveryEnabled = true),
            ),
        )
        for (mode in listOf("proxy", "vpn")) {
            assertTrue(settings(max, mode).hasConnectionResolverSource())
            assertFalse(settings(max.copy(cottenSettings = max.cottenSettings.copy(tunnelMode = "normal")), mode).hasConnectionResolverSource())
            assertFalse(settings(max.copy(cottenSettings = max.cottenSettings.copy(cottenMaxOptions = CottenMaxOptions(discoveryEnabled = false))), mode).hasConnectionResolverSource())
            assertFalse(settings(max, mode).copy(resolverText = "invalid").hasConnectionResolverSource())
        }
    }

    @Test
    fun manualResolversAndSoarDiscoveryRemainSupported() {
        val normal = ConnectionProfile(id = "normal", name = "Normal", engine = DnsClientEngine.CottenDns)
        val soar = normal.copy(id = "soar", engine = DnsClientEngine.Soar, soarSettings = SoarProfileSettings(discovery = true))
        for (mode in listOf("proxy", "vpn")) {
            assertFalse(settings(normal, mode).hasConnectionResolverSource())
            assertTrue(settings(normal, mode).copy(resolverText = "192.0.2.53").hasConnectionResolverSource())
            assertTrue(settings(soar, mode).hasConnectionResolverSource())
            assertFalse(settings(soar.copy(soarSettings = soar.soarSettings.copy(discovery = false)), mode).hasConnectionResolverSource())
        }
    }
}
