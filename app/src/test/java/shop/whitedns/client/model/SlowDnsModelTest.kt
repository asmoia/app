package shop.whitedns.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlowDnsModelTest {
    @Test fun dnsttAliasUsesSlowDnsEngine() {
        assertEquals(DnsClientEngine.SlowDns, DnsClientEngine.normalize("dnstt"))
        assertEquals(DnsClientEngine.SlowDns, DnsClientEngine.normalize("SLOWDNS"))
    }

    @Test fun slowDnsMultiServerDomainsSurviveNormalization() {
        val profile = ConnectionProfile(
            id = "pool", name = "SlowDNS pool", engine = DnsClientEngine.SlowDns,
            customServerDomain = "sd-de1.liftmeon.org, sd-de20.liftmeon.org",
            customServerEncryptionKey = "prince",
        )
        val settings = WhiteDnsSettings(selectedConnectionProfileId = profile.id, connectionProfiles = listOf(profile))
            .runtimeConnectionSettings()
        assertEquals(2, ServerDomains.split(settings.selectedConnectionProfile().customServerDomain).size)
    }

    @Test fun slowDnsProxyRuntimeIsLoopbackAndDoesNotClaimUdpDns() {
        val profile = ConnectionProfile(
            id = "slowdns-test", name = "SlowDNS test", engine = DnsClientEngine.SlowDns,
            customServerDomain = "sd-de1.liftmeon.org", customServerEncryptionKey = "prince",
        )
        val result = WhiteDnsSettings(
            selectedConnectionProfileId = profile.id,
            connectionProfiles = listOf(profile),
            listenIp = "0.0.0.0", localDnsEnabled = true,
        ).runtimeConnectionSettings()
        assertEquals("127.0.0.1", result.listenIp)
        assertEquals("SOCKS5", result.protocolType)
        assertFalse(result.localDnsEnabled)
    }
}
