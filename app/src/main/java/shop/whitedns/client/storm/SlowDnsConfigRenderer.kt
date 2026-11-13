package shop.whitedns.client.storm

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.StormDnsServerProfile
import shop.whitedns.client.model.ServerDomains
import shop.whitedns.client.model.ResolverProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.resolve

/** BD NET compatibility mode, with public service credentials in its asset.
 * No claim is made that those credentials are private or universally stable.
 */
object SlowDnsConfigRenderer {
    private fun defaults(context: Context): JSONObject = context.assets.open("slowdns-defaults.json")
        .bufferedReader().use { JSONObject(it.readText()) }

    fun profiles(context: Context): List<ConnectionProfile> {
        val items = defaults(context).getJSONArray("servers")
        val singleServerProfiles = (0 until items.length()).map { i ->
            val entry = items.getJSONObject(i)
            val zone = entry.getString("zone")
            ConnectionProfile(
                id = "slowdns-bd-${i + 1}", name = "SlowDNS ${entry.getString("name")}",
                serverMode = "custom", customServerDomain = zone,
                customServerEncryptionKey = "prince", engine = DnsClientEngine.SlowDns,
            )
        }
        if (singleServerProfiles.isEmpty()) return emptyList()
        val automatic = ConnectionProfile(
            id = "slowdns-bd-auto", name = "SlowDNS Auto (two servers)",
            customServerDomain = singleServerProfiles.joinToString(", ") { it.customServerDomain },
            customServerEncryptionKey = "prince", engine = DnsClientEngine.SlowDns,
        )
        return listOf(automatic) + singleServerProfiles
    }

    fun render(context: Context, profile: StormDnsServerProfile, settings: WhiteDnsSettings, systemResolvers: List<String> = emptyList()): String {
        val data = defaults(context)
        val rawZones = ServerDomains.split(profile.domain).map { it.lowercase() }
        val servers = data.getJSONArray("servers")
        val isSuperDns = rawZones.isNotEmpty() && rawZones.size <= 50 && rawZones.all(SuperDnsCatalog::isZone)
        val isBd = rawZones.isNotEmpty() && rawZones.size <= 20 && rawZones.all { zone ->
            (0 until servers.length()).any { servers.getJSONObject(it).getString("zone").equals(zone, ignoreCase = true) }
        }
        require(isSuperDns || isBd) { "Only built-in AM SuperDNS or BD NET server domains are supported; mixed pools are not supported" }
        val zones = if (isSuperDns && rawZones.size > 1) SuperDnsScanRankStore(context).order(settings, rawZones) else rawZones
        require(profile.encryptionKey == "prince") { "This compatible client requires its key label" }
        val resolved = settings.resolve()
        // Only the built-in default profile adds system/public bootstrap DNS and
        // the known server IP. Explicit custom resolver lists remain exact.
        val useDefaultResolvers = settings.selectedResolverProfileId == ResolverProfile.DefaultId
        val bootstrap = if (useDefaultResolvers) {
            (systemResolvers.filterNot { '%' in it } + listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")).distinct()
        } else emptyList()
        val serverAddresses = JSONObject()
        if (useDefaultResolvers && !isSuperDns) {
            for (i in 0 until servers.length()) {
                val server = servers.getJSONObject(i)
                serverAddresses.put(server.getString("zone").lowercase(), server.getString("address"))
            }
        }
        val client = java.io.File(context.applicationInfo.nativeLibraryDir, "libxdnstt.so")
        require(client.isFile && client.canExecute()) { "Compatible SlowDNS client is missing for this device ABI" }
        return JSONObject()
            .put("domain", zones.first())
            .put("domains", JSONArray(zones))
            .put("poolSize", 2)
            .put("bootstrapResolvers", JSONArray(bootstrap))
            .put("serverAddresses", serverAddresses)
            .put("resolveDirectHost", isSuperDns && useDefaultResolvers)
            .put("publicKey", profile.encryptionKey)
            .put("upstreamMode", if (isSuperDns) "shadowsocks-none" else "socks5")
            .put("report", data.getString("report"))
            .put("clientBinary", client.absolutePath)
            .put("username", if (isSuperDns) "" else data.getString("username"))
            .put("password", if (isSuperDns) "" else data.getString("password"))
            .put("listenHost", "127.0.0.1")
            .put("listenPort", resolved.listenPort)
            .put("localUsername", if (resolved.socks5Authentication) resolved.socksUsername else "")
            .put("localPassword", if (resolved.socks5Authentication) resolved.socksPassword else "")
            .put("startupTimeoutSec", 35)
            .toString()
    }
}
