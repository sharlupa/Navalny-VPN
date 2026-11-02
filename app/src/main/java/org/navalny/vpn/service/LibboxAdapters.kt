package org.navalny.vpn.service

import android.net.Network
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class StringArray(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = items.size
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
}

class InterfaceArray(private val iterator: Iterator<BoxNetworkInterface>) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): BoxNetworkInterface = iterator.next()
}

/**
 * "local" DNS for sing-box: resolves through the real (underlying) network using the
 * system resolver. Used only as a fallback for the proxy server's own hostname — normally
 * that is resolved once before the tunnel starts.
 */
class LocalResolver(private val networkProvider: () -> Network?) : LocalDNSTransport {
    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        error("raw DNS exchange is not supported")
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val answer = try {
            val n = networkProvider()
            n?.getAllByName(domain) ?: java.net.InetAddress.getAllByName(domain)
        } catch (e: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        val filtered = answer.filter {
            when {
                network.endsWith("4") -> it is Inet4Address
                network.endsWith("6") -> it is Inet6Address
                else -> true
            }
        }
        ctx.success(filtered.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
    }
}
