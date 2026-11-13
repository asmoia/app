package shop.whitedns.client.storm

import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.DnsClientEngine

/** Catalogued AM SuperDNS zones; not a claim that every server is always alive. */
object SuperDnsCatalog {
    const val AutoId = "superdns-am-auto"
    private val zonePattern = Regex("^slsa([1-9]|[1-4][0-9]|50)\\.supaghost\\.cc$", RegexOption.IGNORE_CASE)

    fun zone(number: Int): String {
        require(number in 1..50)
        return "slsa$number.supaghost.cc"
    }

    fun isZone(value: String): Boolean = zonePattern.matches(value.trim().trimEnd('.'))
    fun singleId(number: Int) = "superdns-am-$number"

    fun profiles(): List<ConnectionProfile> {
        val singles = (1..50).map { n ->
            ConnectionProfile(
                id = singleId(n), name = "AM SuperDNS sa$n",
                serverMode = "custom", customServerDomain = zone(n),
                customServerEncryptionKey = "prince", engine = DnsClientEngine.SlowDns,
            )
        }
        val auto = ConnectionProfile(
            id = AutoId, name = "AM SuperDNS Auto (2 servers)", serverMode = "custom",
            customServerDomain = singles.joinToString(", ") { it.customServerDomain },
            customServerEncryptionKey = "prince", engine = DnsClientEngine.SlowDns,
        )
        return listOf(auto) + singles
    }
}
