package shop.whitedns.client.storm

import android.content.Context
import android.net.ConnectivityManager
import java.security.MessageDigest
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.resolve

/** Best-effort, short-lived hints from an explicit user-initiated scan.
 * Network handle and resolver configuration must still match; no background scan. */
class SuperDnsScanRankStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("superdns_scan_rank_v1", Context.MODE_PRIVATE)

    fun networkKey(settings: WhiteDnsSettings): String? = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return null
        val names = manager.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.sorted().joinToString(",")
        val resolverList = settings.resolve().resolverEntries.joinToString(",")
        val raw = "$network|$names|${settings.selectedResolverProfileId}|$resolverList"
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    fun save(settings: WhiteDnsSettings, expectedKey: String?, orderedZones: List<String>): Boolean {
        val key = networkKey(settings) ?: return false
        if (key != expectedKey) return false
        val zones = orderedZones.filter(SuperDnsCatalog::isZone).distinct().take(50)
        if (zones.isEmpty()) return false
        prefs.edit().putString("network", key).putLong("at", System.currentTimeMillis())
            .putString("zones", zones.joinToString(",")).apply()
        return true
    }

    fun order(settings: WhiteDnsSettings, zones: List<String>): List<String> {
        val key = networkKey(settings) ?: return zones
        val savedAt = prefs.getLong("at", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (prefs.getString("network", "") != key || age < 0 || age > 2 * 60 * 60 * 1000L) return zones
        val ranked = prefs.getString("zones", "").orEmpty().split(',')
            .filter { it in zones && SuperDnsCatalog.isZone(it) }.distinct()
        return ranked + zones.filterNot { it in ranked }
    }
}
