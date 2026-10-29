package shop.whitedns.client.storm

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.StormDnsServerProfile
import shop.whitedns.client.model.ServerDomains
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

    fun render(context: Context, profile: StormDnsServerProfile, settings: WhiteDnsSettings): String {
        val data = defaults(context)
        val zones = ServerDomains.split(profile.domain).map { it.lowercase() }
        val servers = data.getJSONArray("servers")
        require(zones.isNotEmpty() && zones.size <= 20 && zones.all { zone ->
            (0 until servers.length()).any { servers.getJSONObject(it).getString("zone").equals(zone, ignoreCase = true) }
        }) {
            "SlowDNS compatibility mode currently supports the built-in BD NET servers only"
        }
        require(profile.encryptionKey == "prince") { "This BD NET client requires its compatible key label" }
        val resolved = settings.resolve()
        val client = java.io.File(context.applicationInfo.nativeLibraryDir, "libxdnstt.so")
        require(client.isFile && client.canExecute()) { "Compatible SlowDNS client is missing for this device ABI" }
        return JSONObject()
            .put("domain", zones.first())
            .put("domains", JSONArray(zones))
            .put("poolSize", 2)
            .put("publicKey", profile.encryptionKey)
            .put("report", data.getString("report"))
            .put("clientBinary", client.absolutePath)
            .put("username", data.getString("username"))
            .put("password", data.getString("password"))
            .put("listenHost", "127.0.0.1")
            .put("listenPort", resolved.listenPort)
            .put("localUsername", if (resolved.socks5Authentication) resolved.socksUsername else "")
            .put("localPassword", if (resolved.socks5Authentication) resolved.socksPassword else "")
            .put("startupTimeoutSec", 90)
            .toString()
    }
}
