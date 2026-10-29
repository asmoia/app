package shop.whitedns.client.model

import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** A subscription URL is a credential. Never include it in diagnostics or exported profile links. */
data class SubscriptionSource(
    val url: String,
    val remoteId: String,
    val allowHttp: Boolean = false,
    val updatedAt: Long = 0,
    val intervalHours: Int = 12,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expiresAt: Long = 0,
) : Serializable {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("remoteId", remoteId)
        .put("allowHttp", allowHttp).put("updatedAt", updatedAt).put("intervalHours", intervalHours)
        .put("upload", upload).put("download", download).put("total", total).put("expiresAt", expiresAt)

    companion object {
        fun fromJson(json: JSONObject?): SubscriptionSource? = json?.let {
            runCatching {
                SubscriptionSource(
                    canonicalSubscriptionUrl(it.getString("url")), it.getString("remoteId"),
                    it.optBoolean("allowHttp"), it.optLong("updatedAt"), it.optInt("intervalHours", 12).coerceIn(1, 168),
                    it.optLong("upload"), it.optLong("download"), it.optLong("total"), it.optLong("expiresAt"),
                )
            }.getOrNull()
        }
    }
}

data class SubscriptionEntry(val remoteId: String, val profile: ConnectionProfile, val resolvers: ResolverProfile?)
data class SubscriptionDocument(val source: SubscriptionSource, val entries: List<SubscriptionEntry>)

fun canonicalSubscriptionUrl(value: String): String {
    require(value.length <= 4096) { "Subscription address is too long" }
    val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Invalid subscription address") }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Use an HTTP or HTTPS subscription address" }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) {
        "Invalid subscription address"
    }
    val port = if (uri.port == (if (scheme == "https") 443 else 80)) -1 else uri.port
    // Preserve the escaped path and query: decoding and encoding again can change signed URLs.
    val host = uri.host.lowercase().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
    return "$scheme://$host${if (port == -1) "" else ":$port"}${uri.rawPath.ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}

private const val SubscriptionMaxBytes = 1024 * 1024
private fun decodeSubscriptionBase64(value: String): String = runCatching {
    Base64.getDecoder().decode(value)
}.recoverCatching { Base64.getUrlDecoder().decode(value) }.getOrElse {
    throw IllegalArgumentException("Subscription is neither a profile list nor valid base64")
}.toString(Charsets.UTF_8)

fun parseSubscription(
    url: String, body: String, headers: Map<String, String> = emptyMap(), allowHttp: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
): SubscriptionDocument {
    val canonical = canonicalSubscriptionUrl(url)
    require(body.toByteArray(Charsets.UTF_8).size <= SubscriptionMaxBytes) { "Subscription exceeds the 1 MiB limit" }
    val trimmed = body.trim().removePrefix("\uFEFF")
    val text = if (Regex("^(soar|masterdns|stormdns|cottendns)://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed
        else decodeSubscriptionBase64(trimmed.filterNot(Char::isWhitespace))
    val links = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    require(links.size in 1..128) { "Subscription must contain between 1 and 128 profiles" }
    val entries = links.mapIndexed { index, link ->
        // The existing importer validates schema, engine, server keys, DNS resolvers and settings.
        val settings = WhiteDnsSettings().importStormDnsProfileLink(link, nowMillis = index.toLong() + 1)
        val profile = settings.selectedConnectionProfile()
        val payload = link.substringAfter("://").substringBefore('#').substringBefore('?')
        val remote = JSONObject(decodeSubscriptionBase64(payload)).getJSONObject("profile").optString("id")
            .takeIf { it.isNotBlank() && it.length <= 128 }
            ?: if (links.size == 1) "single" else "${profile.engine}:${profile.customServerDomain}:${profile.name}"
        SubscriptionEntry(remote, profile, settings.resolverProfiles.firstOrNull { it.id == profile.resolverProfileId })
    }
    require(entries.map { it.remoteId }.distinct().size == entries.size) { "Subscription contains duplicate profile identities" }
    val normalizedHeaders = headers.mapKeys { it.key.lowercase() }
    val usage = normalizedHeaders["subscription-userinfo"].orEmpty().split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2) parts[0].lowercase() to (parts[1].toLongOrNull()?.coerceAtLeast(0) ?: 0) else null
    }.toMap()
    val expirySeconds = usage["expire"] ?: 0
    val source = SubscriptionSource(
        canonical, "", allowHttp, nowMillis,
        normalizedHeaders["profile-update-interval"]?.trim()?.toIntOrNull()?.coerceIn(1, 168) ?: 12,
        usage["upload"] ?: 0, usage["download"] ?: 0, usage["total"] ?: 0,
        if (expirySeconds <= Long.MAX_VALUE / 1000) expirySeconds * 1000 else 0,
    )
    return SubscriptionDocument(source, entries)
}

/** Blocking I/O; call on Dispatchers.IO. All error messages intentionally omit credential URLs. */
fun fetchSubscription(url: String, allowHttp: Boolean = false): SubscriptionDocument {
    val canonical = canonicalSubscriptionUrl(url)
    var target = URI(canonical)
    val deadline = System.nanoTime() + 60_000_000_000L
    require(target.scheme == "https" || allowHttp) { "HTTP is unencrypted. Explicit permission is required" }
    repeat(4) { attempt ->
        require(System.nanoTime() < deadline) { "Subscription download timed out" }
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "WhiteDNS-Subscription/1")
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                require(attempt < 3) { "Too many subscription redirects" }
                val location = connection.getHeaderField("Location") ?: error("Subscription redirect has no address")
                val next = URI(canonicalSubscriptionUrl(target.resolve(location).toString()))
                require(!(target.scheme == "https" && next.scheme == "http")) { "An HTTPS subscription cannot redirect to HTTP" }
                require(next.scheme == "https" || allowHttp) { "HTTP subscription requires explicit permission" }
                target = next
            } else {
                require(code == 200) {
                    if (code == 403) "Subscription account is disabled, expired or out of data"
                    else if (code == 404) "Subscription link was revoked or does not exist"
                    else "Subscription server returned HTTP $code"
                }
                require(connection.contentLengthLong <= SubscriptionMaxBytes) { "Subscription exceeds the 1 MiB limit" }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= SubscriptionMaxBytes) {
                        require(System.nanoTime() < deadline) { "Subscription download timed out" }
                        val count = input.read(buffer, 0, minOf(buffer.size, SubscriptionMaxBytes + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size <= SubscriptionMaxBytes) { "Subscription exceeds the 1 MiB limit" }
                val headers = listOf("Subscription-Userinfo", "Profile-Update-Interval").associateWith { connection.getHeaderField(it).orEmpty() }
                return parseSubscription(canonical, bytes.toString(Charsets.UTF_8), headers, allowHttp)
            }
        } catch (error: java.io.IOException) {
            throw IllegalStateException("Could not fetch subscription. Check the connection and HTTPS certificate", error)
        } finally {
            connection.disconnect()
        }
    }
    error("Too many subscription redirects")
}

private fun subscriptionId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }.take(24)

/** Merge only after the entire response is validated. Retain local selection, mode and resolver overrides. */
fun WhiteDnsSettings.applySubscription(document: SubscriptionDocument, selectImported: Boolean = false): WhiteDnsSettings {
    val current = syncSelectedConnectionProfileFields()
    val url = document.source.url
    val existing = current.normalizedConnectionProfiles()
    val owned = existing.filter { it.subscription?.url == url }
    val existingByRemote = owned.associateBy { it.subscription!!.remoteId }
    val group = subscriptionId(url)
    val newResolvers = mutableListOf<ResolverProfile>()
    val profiles = document.entries.map { entry ->
        val old = existingByRemote[entry.remoteId]
        val id = old?.id ?: "sub-$group-${subscriptionId(entry.remoteId)}"
        val resolverId = "$id-dns"
        entry.resolvers?.let { newResolvers += it.copy(id = resolverId) }
        entry.profile.copy(
            id = id,
            connectionMode = old?.connectionMode ?: current.connectionMode,
            resolverProfileId = old?.resolverProfileId?.takeIf { it != resolverId }
                ?: if (entry.resolvers == null) "" else resolverId,
            subscription = document.source.copy(remoteId = entry.remoteId),
        )
    }
    val removedResolverIds = owned.map { "${it.id}-dns" }.toSet()
    val nextProfiles = existing.filter { it.subscription?.url != url } + profiles
    // Keep a resolver if another local profile explicitly references it.
    val referencedResolvers = nextProfiles.map { it.resolverProfileId }.toSet()
    val nextResolvers = current.resolverProfiles.filter { it.id !in removedResolverIds || (it.id in referencedResolvers && newResolvers.none { n -> n.id == it.id }) } + newResolvers
    val selection = if (selectImported) profiles.first().id else current.selectedConnectionProfileId.takeIf { selected -> nextProfiles.any { it.id == selected } } ?: profiles.first().id
    return current.copy(connectionProfiles = nextProfiles, resolverProfiles = nextResolvers, selectedConnectionProfileId = selection)
        .syncSelectedConnectionProfileFields()
}
