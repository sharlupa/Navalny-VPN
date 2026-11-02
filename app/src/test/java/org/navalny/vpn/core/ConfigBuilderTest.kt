package org.navalny.vpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {

    private val hy2 = LinkParser.parse("hy2://pw@example.com:443?sni=s.example.com&obfs=salamander&obfs-password=o&mport=1000-2000#h")
    private val vless = LinkParser.parse(
        "vless://uuid-1@example.org:443?security=reality&sni=www.apple.com&fp=firefox&pbk=PK&sid=ab&flow=xtls-rprx-vision#v",
    )

    @Test
    fun hysteria2_batterySaver() {
        val cfg = JSONObject(ConfigBuilder.build(hy2, AppSettings(batterySaver = true), resolvedIp = "9.9.9.9"))
        val out = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", out.getString("type"))
        assertEquals("9.9.9.9", out.getString("server"))
        assertEquals("1000:2000", out.getJSONArray("server_ports").getString(0))
        assertFalse(out.has("server_port"))
        assertEquals("55s", out.getString("keep_alive_period"))
        assertEquals("salamander", out.getJSONObject("obfs").getString("type"))
        assertEquals("s.example.com", out.getJSONObject("tls").getString("server_name"))
        assertTrue(cfg.getJSONObject("log").getBoolean("disabled"))
    }

    @Test
    fun hysteria2_noBatterySaver_keepsDefaults() {
        val out = JSONObject(ConfigBuilder.build(hy2, AppSettings(batterySaver = false)))
            .getJSONArray("outbounds").getJSONObject(0)
        assertFalse(out.has("keep_alive_period"))
        assertEquals("example.com", out.getString("server"))
    }

    @Test
    fun vless_reality() {
        val cfg = JSONObject(ConfigBuilder.build(vless, AppSettings()))
        val out = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", out.getString("type"))
        assertEquals("xtls-rprx-vision", out.getString("flow"))
        val tls = out.getJSONObject("tls")
        assertEquals("www.apple.com", tls.getString("server_name"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("PK", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("ab", tls.getJSONObject("reality").getString("short_id"))
    }

    @Test
    fun tun_and_dns() {
        val cfg = JSONObject(ConfigBuilder.build(vless, AppSettings(ipv6 = false, bypassLan = true, stack = TunStack.SYSTEM)))
        val tun = cfg.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("system", tun.getString("stack"))
        assertEquals(1, tun.getJSONArray("address").length())
        assertTrue(tun.has("route_exclude_address"))
        val dns = cfg.getJSONObject("dns")
        assertEquals("ipv4_only", dns.getString("strategy"))
        val remote = dns.getJSONArray("servers").getJSONObject(0)
        assertEquals("https", remote.getString("type"))
        assertEquals("1.1.1.1", remote.getString("server"))
        assertEquals("/dns-query", remote.getString("path"))
        assertEquals("proxy", remote.getString("detour"))
    }

    @Test
    fun dnsParsing() {
        assertEquals("udp", ConfigBuilder.remoteDnsServer("8.8.8.8").getString("type"))
        val tls = ConfigBuilder.remoteDnsServer("tls://9.9.9.9:853")
        assertEquals("tls", tls.getString("type"))
        assertEquals(853, tls.getInt("server_port"))
    }
}
