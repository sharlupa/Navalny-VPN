package org.navalny.vpn.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a minimal sing-box (1.14) config for one server.
 *
 * Battery-related choices:
 *  - logging disabled (no string formatting / buffer churn per connection);
 *  - no sniffing and no rule sets: every packet is routed by a tiny rule list, no per-connection
 *    protocol detection;
 *  - DNS cached inside sing-box, IPv4-only by default (half the DNS queries);
 *  - LAN traffic excluded from the tunnel at route level (never enters the TUN at all);
 *  - "battery saver": longer QUIC/TCP keep-alive periods so an idle tunnel does not wake the
 *    cellular radio every 10 seconds (Hysteria2 default).
 */
object ConfigBuilder {

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"

    /** [resolvedIp] — server address resolved before start (avoids bootstrap DNS inside the core). */
    fun build(server: ServerProfile, settings: AppSettings, resolvedIp: String? = null): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("disabled", true))
        root.put("dns", dns(settings))
        root.put("inbounds", JSONArray().put(tun(settings)))
        root.put(
            "outbounds",
            JSONArray()
                .put(proxyOutbound(server, settings, resolvedIp))
                .put(JSONObject().put("type", "direct").put("tag", TAG_DIRECT)),
        )
        root.put("route", route(settings))
        return root.toString()
    }

    private fun tun(s: AppSettings) = JSONObject().apply {
        put("type", "tun")
        put("tag", "tun-in")
        val address = JSONArray().put("172.19.0.1/30")
        if (s.ipv6) address.put("fdfe:dcba:9876::1/126")
        put("address", address)
        put("mtu", s.mtu)
        put("auto_route", true)
        put("stack", s.stack.value)
        if (s.bypassLan) {
            put(
                "route_exclude_address",
                JSONArray(
                    listOf(
                        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                        "169.254.0.0/16", "100.64.0.0/10", "224.0.0.0/4",
                        "fc00::/7", "fe80::/10", "ff00::/8",
                    ),
                ),
            )
        }
        // Release idle UDP mappings sooner — fewer live sessions to keep.
        put("udp_timeout", if (s.batterySaver) "1m" else "5m")
    }

    private fun dns(s: AppSettings) = JSONObject().apply {
        put(
            "servers",
            JSONArray()
                .put(remoteDnsServer(s.remoteDns).put("tag", "remote").put("detour", TAG_PROXY))
                .put(JSONObject().put("type", "local").put("tag", "local")),
        )
        put("final", "remote")
        put("strategy", if (s.ipv6) "prefer_ipv4" else "ipv4_only")
        put("cache_capacity", 4096)
    }

    /** Accepts `1.1.1.1`, `tcp://`, `tls://`, `quic://`, `https://host/path`, `h3://host/path`. */
    internal fun remoteDnsServer(address: String): JSONObject {
        val a = address.trim()
        val scheme = a.substringBefore("://", "").lowercase()
        val rest = if (scheme.isEmpty()) a else a.substringAfter("://")
        val hostPort = rest.substringBefore('/')
        val path = rest.substring(hostPort.length).ifEmpty { "/dns-query" }
        val (host, port) = splitHostPort(hostPort)
        val o = JSONObject()
        when (scheme) {
            "", "udp" -> o.put("type", "udp")
            "tcp" -> o.put("type", "tcp")
            "tls" -> o.put("type", "tls")
            "quic" -> o.put("type", "quic")
            "h3" -> o.put("type", "h3").put("path", path)
            else -> o.put("type", "https").put("path", path)
        }
        o.put("server", host)
        port?.let { o.put("server_port", it) }
        return o
    }

    private fun splitHostPort(s: String): Pair<String, Int?> {
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            return s.substring(1, end) to s.substring(end + 1).removePrefix(":").toIntOrNull()
        }
        val parts = s.split(':')
        return if (parts.size == 2) parts[0] to parts[1].toIntOrNull() else s to null
    }

    private fun route(s: AppSettings) = JSONObject().apply {
        put("auto_detect_interface", true)
        put("default_domain_resolver", "local")
        put("final", TAG_PROXY)
        val rules = JSONArray()
        rules.put(JSONObject().put("port", 53).put("action", "hijack-dns"))
        if (s.bypassLan) rules.put(JSONObject().put("ip_is_private", true).put("outbound", TAG_DIRECT))
        put("rules", rules)
    }

    private fun proxyOutbound(p: ServerProfile, s: AppSettings, resolvedIp: String?): JSONObject {
        val o = JSONObject()
        o.put("tag", TAG_PROXY)
        o.put("server", resolvedIp ?: p.host)
        o.put("server_port", p.port)
        when (p.protocol) {
            Protocol.HYSTERIA2 -> hysteria2(o, p, s)
            Protocol.VLESS -> vless(o, p, s)
        }
        return o
    }

    private fun hysteria2(o: JSONObject, p: ServerProfile, s: AppSettings) {
        o.put("type", "hysteria2")
        o.put("password", p.password)
        if (p.ports.isNotBlank()) {
            // "20000-30000,40000" -> ["20000:30000", "40000:40000"]
            val ranges = p.ports.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                if (it.contains('-')) it.replace('-', ':') else "$it:$it"
            }
            o.put("server_ports", JSONArray(ranges))
            o.remove("server_port")
            // Hop rarely: every hop is a new UDP socket + NAT mapping.
            o.put("hop_interval", if (s.batterySaver) "5m" else "30s")
        }
        if (p.upMbps > 0) o.put("up_mbps", p.upMbps)
        if (p.downMbps > 0) o.put("down_mbps", p.downMbps)
        if (p.obfsPassword.isNotEmpty()) {
            o.put("obfs", JSONObject().put("type", "salamander").put("password", p.obfsPassword))
        }
        if (s.batterySaver) {
            // Default is a PING every 10 s which keeps the modem awake permanently.
            // quic-go caps keep-alive at idle_timeout / 2.
            o.put("idle_timeout", "2m")
            o.put("keep_alive_period", "55s")
        }
        o.put(
            "tls",
            JSONObject().apply {
                put("enabled", true)
                put("server_name", p.sni.ifEmpty { p.host })
                if (p.insecure) put("insecure", true)
                put("alpn", JSONArray(p.alpn.ifEmpty { listOf("h3") }))
            },
        )
    }

    private fun vless(o: JSONObject, p: ServerProfile, s: AppSettings) {
        o.put("type", "vless")
        o.put("uuid", p.uuid)
        // xtls-rprx-vision avoids double encryption of inner TLS — less CPU per byte.
        if (p.flow.isNotEmpty() && p.transport == "tcp") o.put("flow", p.flow)
        o.put("packet_encoding", "xudp")
        if (s.batterySaver) {
            o.put("tcp_keep_alive", "10m")
            o.put("tcp_keep_alive_interval", "2m")
        }
        if (p.security == "tls" || p.security == "reality") {
            o.put(
                "tls",
                JSONObject().apply {
                    put("enabled", true)
                    put("server_name", p.sni.ifEmpty { p.host })
                    if (p.insecure) put("insecure", true)
                    if (p.alpn.isNotEmpty()) put("alpn", JSONArray(p.alpn))
                    put(
                        "utls",
                        JSONObject().put("enabled", true).put("fingerprint", p.fingerprint.ifEmpty { "chrome" }),
                    )
                    if (p.security == "reality") {
                        put(
                            "reality",
                            JSONObject()
                                .put("enabled", true)
                                .put("public_key", p.publicKey)
                                .put("short_id", p.shortId),
                        )
                    }
                },
            )
        }
        transport(p)?.let { o.put("transport", it) }
    }

    private fun transport(p: ServerProfile): JSONObject? = when (p.transport) {
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", p.path)
        "ws" -> JSONObject().apply {
            put("type", "ws")
            put("path", p.path.ifEmpty { "/" })
            if (p.hostHeader.isNotEmpty()) put("headers", JSONObject().put("Host", p.hostHeader))
        }
        "http" -> JSONObject().apply {
            put("type", "http")
            if (p.path.isNotEmpty()) put("path", p.path)
            if (p.hostHeader.isNotEmpty()) put("host", JSONArray(p.hostHeader.split(',')))
        }
        "httpupgrade" -> JSONObject().apply {
            put("type", "httpupgrade")
            put("path", p.path.ifEmpty { "/" })
            if (p.hostHeader.isNotEmpty()) put("host", p.hostHeader)
        }
        else -> null
    }
}
