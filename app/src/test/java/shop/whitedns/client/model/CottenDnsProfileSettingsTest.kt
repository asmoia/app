package shop.whitedns.client.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CottenDnsProfileSettingsTest {

    @Test
    fun normalizeFallsBackToPresetForUnknownOverrides() {
        val settings = CottenDnsProfileSettings(
            configPreset = "nonsense",
            transportMode = "carrier-pigeon",
            deliveryMode = "smoke-signal",
            qnameMode = "loud",
        ).normalized()

        assertEquals("default", settings.configPreset)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.transportMode)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.deliveryMode)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.qnameMode)
    }

    @Test
    fun normalizeAcceptsThePresetAliasesTheEngineUses() {
        assertEquals("tcp-survival", CottenDnsProfileSettings(configPreset = "TCP").normalized().configPreset)
        assertEquals("master-storm", CottenDnsProfileSettings(configPreset = "storm").normalized().configPreset)
    }

    @Test
    fun normalizeRejectsOutOfRangePorts() {
        val settings = CottenDnsProfileSettings(
            resolverDoTPort = "0",
            resolverDoHPort = "70000",
            resolverDoHPath = "   ",
        ).normalized()

        assertEquals("853", settings.resolverDoTPort)
        assertEquals("443", settings.resolverDoHPort)
        assertEquals("/dns-query", settings.resolverDoHPath)
    }

    /** The preset is the only thing that declares a legacy target now. */
    @Test
    fun compatibilityFollowsTheMasterStormPreset() {
        assertEquals(true, CottenDnsProfileSettings(configPreset = "master-storm").isCompatibility)
        assertEquals(true, CottenDnsProfileSettings(configPreset = "storm").isCompatibility)
        assertEquals(false, CottenDnsProfileSettings().isCompatibility)
        assertEquals(false, CottenDnsProfileSettings(configPreset = "speed").isCompatibility)
    }

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val original = CottenDnsProfileSettings(
            configPreset = "speed",
            transportMode = "dot",
            deliveryMode = "txt-https",
            qnameMode = "moderate",
            resolverTlsServerName = "dns.example.com",
            resolverTlsPin = "pin123",
            resolverDoTPort = "8853",
            resolverDoHPort = "8443",
            resolverDoHPath = "/q",
        )

        assertEquals(original, cottenDnsProfileSettingsFromJson(original.toJson()))
    }

    /** Profiles saved before this feature must still load. */
    @Test
    fun missingOrEmptyJsonYieldsDefaults() {
        assertEquals(CottenDnsProfileSettings(), cottenDnsProfileSettingsFromJson(null))
        assertEquals(CottenDnsProfileSettings(), cottenDnsProfileSettingsFromJson(JSONObject()))
    }

    @Test
    fun maxOptionsAndModeSurviveSettingsRoundTrip() {
        val original = CottenDnsProfileSettings(tunnelMode = "cottenmax", transportMode = "doq",
            cottenMaxOptions = CottenMaxOptions(standbyDomains = "b.example", discoveryEnabled = true,
                discoveryCandidates = "1.1.1.1:5353", dnsPaddingBlockSize = 256, qnameLossGuard = true))
        assertEquals(original.normalized(), cottenDnsProfileSettingsFromJson(original.toJson()))
        assertEquals(true, original.usesCottenMax)
        assertEquals("legacy", original.copy(configPreset = "master-storm").normalized().tunnelMode)
        assertEquals(false, original.copy(configPreset = "master-storm").usesCottenMax)
    }

    @Test
    fun discoveryBootstrapIsRestrictedToNativeMaxAndBlankResolvers() {
        val cotten = CottenDnsProfileSettings(tunnelMode = "cottenmax", cottenMaxOptions = CottenMaxOptions(discoveryEnabled = true))
        val profile = ConnectionProfile(id = "max", name = "Max", engine = DnsClientEngine.CottenDns, cottenSettings = cotten)
        fun settings(p: ConnectionProfile) = WhiteDnsSettings(selectedConnectionProfileId = p.id, connectionProfiles = listOf(p))
        assertEquals(true, settings(profile).canBootstrapResolversWithCottenMax())
        assertEquals(false, settings(profile).copy(resolverText = "invalid").canBootstrapResolversWithCottenMax())
        assertEquals(false, settings(profile.copy(engine = DnsClientEngine.Soar)).canBootstrapResolversWithCottenMax())
        assertEquals(false, settings(profile.copy(engine = DnsClientEngine.StormDns)).canBootstrapResolversWithCottenMax())
        assertEquals(false, settings(profile.copy(cottenSettings = cotten.copy(configPreset = "master-storm"))).canBootstrapResolversWithCottenMax())
    }

    @Test
    fun maxNeverSilentlyDisablesAnExplicitHardQueryBudget() {
        val normal = CottenDnsProfileSettings(queryRateLimit = "2")
        assertEquals(false, normal.hasUnsupportedMaxRateBudget)
        val max = normal.copy(tunnelMode = "cottenmax").normalized()
        assertEquals("2", max.queryRateLimit)
        assertEquals(true, max.hasUnsupportedMaxRateBudget)
        assertEquals(false, max.copy(queryRateLimit = "off").hasUnsupportedMaxRateBudget)
    }
}
