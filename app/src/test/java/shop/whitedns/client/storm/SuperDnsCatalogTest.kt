package shop.whitedns.client.storm

import org.junit.Assert.*
import org.junit.Test
import shop.whitedns.client.model.ServerDomains
import shop.whitedns.client.model.DnsClientEngine

class SuperDnsCatalogTest {
    @Test fun catalogHas50UniqueZonesAndOneAutoProfile() {
        val profiles = SuperDnsCatalog.profiles()
        assertEquals(51, profiles.size)
        assertEquals(SuperDnsCatalog.AutoId, profiles.first().id)
        val zones = ServerDomains.split(profiles.first().customServerDomain)
        assertEquals(50, zones.size)
        assertEquals(50, zones.toSet().size)
        assertTrue(zones.all(SuperDnsCatalog::isZone))
        assertEquals(zones, profiles.drop(1).map { it.customServerDomain })
        assertTrue(profiles.all { it.engine == DnsClientEngine.SlowDns })
    }

    @Test fun validatesOnlyCataloguedZones() {
        assertTrue(SuperDnsCatalog.isZone("SLSA50.SUPAGHOST.CC."))
        for (bad in listOf("slsa0.supaghost.cc", "slsa51.supaghost.cc", "slsa01.supaghost.cc", "slsa1.supaghost.cc.evil", "sa1.supaghost.cc", "slsa-1.supaghost.cc")) {
            assertFalse(bad, SuperDnsCatalog.isZone(bad))
        }
    }
}
