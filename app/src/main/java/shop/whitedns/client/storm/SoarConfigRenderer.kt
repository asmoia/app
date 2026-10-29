package shop.whitedns.client.storm

import org.json.JSONObject
import org.json.JSONArray
import shop.whitedns.client.model.StormDnsServerProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.isValidSoarServer
import shop.whitedns.client.model.resolve

object SoarConfigRenderer {
    fun render(profile: StormDnsServerProfile, settings: WhiteDnsSettings, systemResolvers: List<String> = emptyList()): String {
        require(isValidSoarServer(profile.domain, profile.encryptionKey)) {
            "Soar needs one ASCII tunnel domain (max 45 characters) and a base64 32-byte SERVER PUBLIC KEY"
        }
        val resolved = settings.resolve()
        return JSONObject()
            .put("zone", profile.domain.trim().trimEnd('.').lowercase())
            .put("server_public_key", profile.encryptionKey.trim())
            .put("system_resolvers", JSONArray(systemResolvers.distinct().take(8)))
            .put("listen", "127.0.0.1:${resolved.listenPort}")
            .put("username", if (resolved.socks5Authentication) resolved.socksUsername else "")
            .put("password", if (resolved.socks5Authentication) resolved.socksPassword else "")
            .put("soar", profile.soarSettings.toJson())
            .toString(2)
    }
}
