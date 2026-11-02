package org.navalny.vpn.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Parses share links:
 *  - hysteria2://auth@host:port/?sni=..&obfs=salamander&obfs-password=..&insecure=1&mport=..#name
 *  - hy2://...
 *  - vless://uuid@host:port?security=reality&pbk=..&sid=..&sni=..&fp=chrome&flow=xtls-rprx-vision&type=tcp#name
 *
 * Written by hand (not java.net.URI) because real-world links contain things URI rejects:
 * port ranges for port hopping, unescaped characters in names, etc.
 */
object LinkParser {

    private val SCHEMES = listOf("hysteria2://", "hy2://", "vless://")

    fun isSupported(text: String): Boolean = SCHEMES.any { text.trim().startsWith(it, ignoreCase = true) }

    /** Extracts every supported link from a text blob (subscription body, clipboard...). */
    fun parseMany(text: String, subscriptionId: String? = null): List<ServerProfile> {
        val body = decodeSubscriptionBody(text)
        return body.lineSequence()
            .map { it.trim() }
            .filter { isSupported(it) }
            .mapNotNull { runCatching { parse(it, subscriptionId) }.getOrNull() }
            .toList()
    }

    fun parse(link: String, subscriptionId: String? = null): ServerProfile {
        val raw = link.trim()
        val schemeEnd = raw.indexOf("://")
        require(schemeEnd > 0) { "Не ссылка" }
        val scheme = raw.substring(0, schemeEnd).lowercase()
        var rest = raw.substring(schemeEnd + 3)

        var fragment = ""
        rest.indexOf('#').takeIf { it >= 0 }?.let {
            fragment = percentDecode(rest.substring(it + 1))
            rest = rest.substring(0, it)
        }
        var query = ""
        rest.indexOf('?').takeIf { it >= 0 }?.let {
            query = rest.substring(it + 1)
            rest = rest.substring(0, it)
        }
        rest = rest.trimEnd('/')
        var userInfo = ""
        rest.lastIndexOf('@').takeIf { it >= 0 }?.let {
            userInfo = percentDecode(rest.substring(0, it))
            rest = rest.substring(it + 1)
        }
        val (host, portSpec) = splitHostPort(rest)
        val params = parseQuery(query)

        return when (scheme) {
            "hysteria2", "hy2" -> parseHysteria2(raw, host, portSpec, userInfo, params, fragment, subscriptionId)
            "vless" -> parseVless(raw, host, portSpec, userInfo, params, fragment, subscriptionId)
            else -> throw IllegalArgumentException("Неподдерживаемый протокол: $scheme")
        }
    }

    private fun parseHysteria2(
        raw: String,
        host: String,
        portSpec: String,
        auth: String,
        p: Map<String, String>,
        name: String,
        subscriptionId: String?,
    ): ServerProfile {
        // "443" or "443,20000-30000" (multi-port = port hopping)
        val firstPort = portSpec.split(',').first().substringBefore('-').toIntOrNull() ?: 443
        val hopping = p["mport"] ?: portSpec.takeIf { it.contains(',') || it.contains('-') }.orEmpty()
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { host },
            protocol = Protocol.HYSTERIA2,
            host = host,
            port = firstPort,
            subscriptionId = subscriptionId,
            sni = p["sni"] ?: p["peer"].orEmpty(),
            insecure = p["insecure"] == "1" || p["insecure"] == "true" || p["allowInsecure"] == "1",
            alpn = p["alpn"]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            password = auth,
            obfsPassword = if (p["obfs"].isNullOrEmpty() || p["obfs"] == "none") "" else p["obfs-password"].orEmpty(),
            ports = hopping,
            upMbps = p["upmbps"]?.toIntOrNull() ?: 0,
            downMbps = p["downmbps"]?.toIntOrNull() ?: 0,
            link = raw,
        )
    }

    private fun parseVless(
        raw: String,
        host: String,
        portSpec: String,
        uuid: String,
        p: Map<String, String>,
        name: String,
        subscriptionId: String?,
    ): ServerProfile {
        require(uuid.isNotBlank()) { "В ссылке VLESS нет UUID" }
        val type = when (val t = p["type"]?.lowercase()) {
            null, "", "raw", "tcp" -> "tcp"
            "h2" -> "http"
            else -> t
        }
        val security = p["security"]?.lowercase().orEmpty().ifEmpty { "none" }
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { host },
            protocol = Protocol.VLESS,
            host = host,
            port = portSpec.toIntOrNull() ?: 443,
            subscriptionId = subscriptionId,
            sni = p["sni"] ?: p["peer"].orEmpty(),
            insecure = p["allowInsecure"] == "1" || p["insecure"] == "1",
            alpn = p["alpn"]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            fingerprint = p["fp"].orEmpty(),
            uuid = uuid,
            flow = p["flow"].orEmpty(),
            security = security,
            publicKey = p["pbk"].orEmpty(),
            shortId = p["sid"].orEmpty(),
            transport = type,
            path = when (type) {
                "grpc" -> p["serviceName"] ?: p["path"].orEmpty()
                else -> p["path"].orEmpty()
            },
            hostHeader = p["host"].orEmpty(),
            link = raw,
        )
    }

    private fun splitHostPort(s: String): Pair<String, String> {
        if (s.startsWith("[")) { // IPv6
            val end = s.indexOf(']')
            require(end > 0) { "Неверный IPv6-адрес" }
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":")
            return host to port
        }
        val idx = s.indexOf(':')
        return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val i = pair.indexOf('=')
            if (i < 0) percentDecode(pair) to "" else percentDecode(pair.substring(0, i)) to percentDecode(pair.substring(i + 1))
        }
    }

    /** RFC 3986 percent-decoding ('+' is kept as is — it is valid in base64 passwords). */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Subscriptions are usually base64 of a newline separated list; sometimes plain text. */
    fun decodeSubscriptionBody(body: String): String {
        val trimmed = body.trim()
        if (SCHEMES.any { trimmed.contains(it, ignoreCase = true) }) return trimmed
        val compact = trimmed.filterNot { it.isWhitespace() }
        val decoded = runCatching { String(Base64.getDecoder().decode(padBase64(compact)), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.getUrlDecoder().decode(padBase64(compact)), Charsets.UTF_8) }.getOrNull()
        return decoded ?: trimmed
    }

    private fun padBase64(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
