package org.navalny.vpn.core

import org.json.JSONArray
import org.json.JSONObject

enum class Protocol(val title: String) {
    HYSTERIA2("Hysteria2"),
    VLESS("VLESS"),
}

/**
 * One proxy server. Covers both Hysteria2 and VLESS (+REALITY / TLS) — fields that do not
 * apply to the protocol are simply left empty.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val subscriptionId: String? = null,
    // TLS (common)
    val sni: String = "",
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    // Hysteria2
    val password: String = "",
    val obfsPassword: String = "",
    /** Port hopping, e.g. "20000-30000,40000" */
    val ports: String = "",
    val upMbps: Int = 0,
    val downMbps: Int = 0,
    // VLESS
    val uuid: String = "",
    val flow: String = "",
    /** none | tls | reality */
    val security: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    /** tcp | grpc | ws | http | httpupgrade */
    val transport: String = "tcp",
    /** ws/http/httpupgrade path or grpc serviceName */
    val path: String = "",
    val hostHeader: String = "",
    /** Original share link (used for "copy link"). */
    val link: String = "",
) {
    val subtitle: String
        get() = buildString {
            append(protocol.title)
            if (protocol == Protocol.VLESS && security == "reality") append(" + REALITY")
            if (protocol == Protocol.VLESS && transport != "tcp") append(" · ").append(transport)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("protocol", protocol.name)
        put("host", host)
        put("port", port)
        subscriptionId?.let { put("subscriptionId", it) }
        put("sni", sni)
        put("insecure", insecure)
        put("alpn", JSONArray(alpn))
        put("fingerprint", fingerprint)
        put("password", password)
        put("obfsPassword", obfsPassword)
        put("ports", ports)
        put("upMbps", upMbps)
        put("downMbps", downMbps)
        put("uuid", uuid)
        put("flow", flow)
        put("security", security)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("transport", transport)
        put("path", path)
        put("hostHeader", hostHeader)
        put("link", link)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerProfile = ServerProfile(
            id = o.getString("id"),
            name = o.optString("name"),
            protocol = Protocol.valueOf(o.getString("protocol")),
            host = o.getString("host"),
            port = o.getInt("port"),
            subscriptionId = o.optString("subscriptionId").ifEmpty { null },
            sni = o.optString("sni"),
            insecure = o.optBoolean("insecure"),
            alpn = o.optJSONArray("alpn")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            fingerprint = o.optString("fingerprint"),
            password = o.optString("password"),
            obfsPassword = o.optString("obfsPassword"),
            ports = o.optString("ports"),
            upMbps = o.optInt("upMbps"),
            downMbps = o.optInt("downMbps"),
            uuid = o.optString("uuid"),
            flow = o.optString("flow"),
            security = o.optString("security"),
            publicKey = o.optString("publicKey"),
            shortId = o.optString("shortId"),
            transport = o.optString("transport", "tcp"),
            path = o.optString("path"),
            hostHeader = o.optString("hostHeader"),
            link = o.optString("link"),
        )
    }
}

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = 0,
    /** Parsed from the `subscription-userinfo` header. */
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Unix seconds, 0 = never. */
    val expireAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("updatedAt", updatedAt)
        put("upload", uploadBytes)
        put("download", downloadBytes)
        put("total", totalBytes)
        put("expire", expireAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Subscription(
            id = o.getString("id"),
            name = o.optString("name"),
            url = o.getString("url"),
            updatedAt = o.optLong("updatedAt"),
            uploadBytes = o.optLong("upload"),
            downloadBytes = o.optLong("download"),
            totalBytes = o.optLong("total"),
            expireAt = o.optLong("expire"),
        )
    }
}
