package shop.whitedns.client.model

import java.io.Serializable
import java.util.Base64
import org.json.JSONObject

/** Soar parameters are independent of the Master/Cotten transport settings. */
data class SoarProfileSettings(
    val transport: String = "auto",
    val discovery: Boolean = true,
    val country: String = "IR",
    val discoveryWant: Int = 2,
    val discoveryConcurrency: Int = 2,
    val discoveryPaceMs: Int = 1000,
    val safeQName: Int = 101,
    val maxQName: Int = 253,
    val maxAnswer: Int = 1232,
    val holdMs: Int = 500,
    val maxInflight: Int = 128,
    val maxPathWindow: Int = 32,
    val maxStreams: Int = 128,
    val streamWindowKiB: Int = 256,
    val sendBufferKiB: Int = 64,
    val sessionIdleSeconds: Int = 90,
    val dialTimeoutSeconds: Int = 90,
) : Serializable {
    fun normalized(): SoarProfileSettings = copy(
        transport = transport.takeIf { it in listOf("auto", "on", "only", "off") } ?: "auto",
        country = country.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
        discoveryWant = discoveryWant.coerceIn(1, 32),
        discoveryConcurrency = discoveryConcurrency.coerceIn(1, 16),
        discoveryPaceMs = discoveryPaceMs.coerceIn(100, 5000),
        maxQName = maxQName.coerceIn(101, 253),
        safeQName = safeQName.coerceIn(101, maxQName.coerceIn(101, 253)),
        maxAnswer = maxAnswer.coerceIn(512, 1232),
        holdMs = holdMs.coerceIn(100, 1500),
        maxInflight = maxInflight.coerceIn(8, 1024),
        maxPathWindow = maxPathWindow.coerceIn(2, 128),
        maxStreams = maxStreams.coerceIn(8, 256),
        streamWindowKiB = streamWindowKiB.coerceIn(64, 1024),
        sendBufferKiB = sendBufferKiB.coerceIn(32, 1024),
        // Keep below the reference server's two minute idle expiry.
        sessionIdleSeconds = sessionIdleSeconds.coerceIn(15, 110),
        dialTimeoutSeconds = dialTimeoutSeconds.coerceIn(5, 120),
    )

    fun toJson(): JSONObject = normalized().let { n ->
        JSONObject()
            .put("transport", n.transport).put("discovery", n.discovery).put("country", n.country)
            .put("discoveryWant", n.discoveryWant).put("discoveryConcurrency", n.discoveryConcurrency)
            .put("discoveryPaceMs", n.discoveryPaceMs).put("safeQName", n.safeQName)
            .put("maxQName", n.maxQName).put("maxAnswer", n.maxAnswer).put("holdMs", n.holdMs)
            .put("maxInflight", n.maxInflight).put("maxPathWindow", n.maxPathWindow)
            .put("maxStreams", n.maxStreams).put("streamWindowKiB", n.streamWindowKiB)
            .put("sendBufferKiB", n.sendBufferKiB).put("sessionIdleSeconds", n.sessionIdleSeconds)
            .put("dialTimeoutSeconds", n.dialTimeoutSeconds)
    }

    companion object {
        fun preset(name: String): SoarProfileSettings = when (name) {
            "conservative" -> SoarProfileSettings(maxInflight = 32, maxPathWindow = 8, maxAnswer = 512, maxQName = 101, maxStreams = 16)
            "fast" -> SoarProfileSettings(maxInflight = 256, maxPathWindow = 64)
            else -> SoarProfileSettings()
        }
    }
}

fun soarProfileSettingsFromJson(json: JSONObject?): SoarProfileSettings {
    val d = SoarProfileSettings()
    if (json == null) return d
    return SoarProfileSettings(
        transport = json.optString("transport", d.transport), discovery = json.optBoolean("discovery", d.discovery),
        country = json.optString("country", d.country), discoveryWant = json.optInt("discoveryWant", d.discoveryWant),
        discoveryConcurrency = json.optInt("discoveryConcurrency", d.discoveryConcurrency),
        discoveryPaceMs = json.optInt("discoveryPaceMs", d.discoveryPaceMs),
        safeQName = json.optInt("safeQName", d.safeQName), maxQName = json.optInt("maxQName", d.maxQName),
        maxAnswer = json.optInt("maxAnswer", d.maxAnswer), holdMs = json.optInt("holdMs", d.holdMs),
        maxInflight = json.optInt("maxInflight", d.maxInflight), maxPathWindow = json.optInt("maxPathWindow", d.maxPathWindow),
        maxStreams = json.optInt("maxStreams", d.maxStreams), streamWindowKiB = json.optInt("streamWindowKiB", d.streamWindowKiB),
        sendBufferKiB = json.optInt("sendBufferKiB", d.sendBufferKiB),
        sessionIdleSeconds = json.optInt("sessionIdleSeconds", d.sessionIdleSeconds),
        dialTimeoutSeconds = json.optInt("dialTimeoutSeconds", d.dialTimeoutSeconds),
    ).normalized()
}

fun isValidSoarServer(domain: String, publicKey: String): Boolean {
    val zone = domain.trim().trimEnd('.').lowercase()
    if (zone.length !in 3..45 || !zone.contains('.') || !zone.matches(Regex("[a-z0-9.-]+"))) return false
    if (zone.split('.').any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
    return runCatching { Base64.getDecoder().decode(publicKey.trim()).size == 32 }.getOrDefault(false)
}

fun WhiteDnsSettings.canDiscoverSoarResolvers(): Boolean = selectedConnectionProfile().let {
    it.engine == DnsClientEngine.Soar && it.soarSettings.discovery
}
