package org.navalny.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkParserTest {

    @Test
    fun hysteria2_full() {
        val p = LinkParser.parse(
            "hysteria2://p%40ss:word@example.com:443/?sni=real.example.com&obfs=salamander&obfs-password=ob%2Bfs&insecure=1#My%20Server%20%F0%9F%87%B3%F0%9F%87%B1",
        )
        assertEquals(Protocol.HYSTERIA2, p.protocol)
        assertEquals("example.com", p.host)
        assertEquals(443, p.port)
        assertEquals("p@ss:word", p.password)
        assertEquals("real.example.com", p.sni)
        assertEquals("ob+fs", p.obfsPassword)
        assertTrue(p.insecure)
        assertEquals("My Server 🇳🇱", p.name)
    }

    @Test
    fun hy2_portHopping_and_ipv6() {
        val p = LinkParser.parse("hy2://secret@[2001:db8::1]:20000-30000?sni=a.b#x")
        assertEquals("2001:db8::1", p.host)
        assertEquals(20000, p.port)
        assertEquals("20000-30000", p.ports)
        assertEquals("", p.obfsPassword)
    }

    @Test
    fun vless_reality() {
        val p = LinkParser.parse(
            "vless://0f8a1b2c-1111-2222-3333-444455556666@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision" +
                "&security=reality&sni=www.microsoft.com&fp=chrome&pbk=AbCdEf123&sid=6ba85179e30d4fc2&type=tcp#Reality",
        )
        assertEquals(Protocol.VLESS, p.protocol)
        assertEquals("0f8a1b2c-1111-2222-3333-444455556666", p.uuid)
        assertEquals("reality", p.security)
        assertEquals("AbCdEf123", p.publicKey)
        assertEquals("6ba85179e30d4fc2", p.shortId)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("www.microsoft.com", p.sni)
        assertEquals("tcp", p.transport)
        assertEquals("Reality", p.name)
    }

    @Test
    fun vless_grpc() {
        val p = LinkParser.parse("vless://id@h.com:8443?security=tls&type=grpc&serviceName=svc#g")
        assertEquals("grpc", p.transport)
        assertEquals("svc", p.path)
        assertEquals("tls", p.security)
    }

    @Test
    fun subscription_base64() {
        val body = "hy2://a@h1:443#one\nvless://id@h2:443?security=reality&pbk=k#two\nss://unsupported\n"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray()).trimEnd('=')
        val servers = LinkParser.parseMany(encoded, "sub1")
        assertEquals(2, servers.size)
        assertEquals("one", servers[0].name)
        assertEquals("sub1", servers[1].subscriptionId)
    }

    @Test
    fun unsupported() {
        assertFalse(LinkParser.isSupported("vmess://abc"))
        assertTrue(LinkParser.parseMany("trojan://x@y:1").isEmpty())
    }
}
