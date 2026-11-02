package org.navalny.vpn.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the underlying (non-VPN) default network and reports it to sing-box.
 *
 * Only onAvailable / onLinkPropertiesChanged / onLost are overridden: since Android 13 the
 * system delivers only the callbacks an app actually declares, so we do not get woken up on
 * every signal-strength change (onCapabilitiesChanged).
 */
class DefaultNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetwork: (Network?) -> Unit,
) {
    @Volatile
    var network: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null
    private var thread: HandlerThread? = null
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
            publish()
        }

        override fun onLinkPropertiesChanged(n: Network, linkProperties: LinkProperties) {
            if (n == network) publish()
        }

        override fun onLost(n: Network) {
            if (n == network) {
                network = null
                publish()
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val t = HandlerThread("net-monitor").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        network = connectivity.activeNetwork
        when {
            // "Best matching" = the network the system would use for us, never the VPN itself.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                connectivity.requestNetwork(request, callback, handler)
            else -> connectivity.registerDefaultNetworkCallback(callback, handler)
        }
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        thread?.quitSafely()
        thread = null
        registered = false
        listener = null
        network = null
    }

    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        if (l != null) publish()
    }

    private fun publish() {
        val n = network
        onUnderlyingNetwork(n)
        val l = listener ?: return
        if (n == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        repeat(5) {
            val lp = connectivity.getLinkProperties(n)
            val name = lp?.interfaceName
            val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
            if (name != null && index != null) {
                val caps = connectivity.getNetworkCapabilities(n)
                val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                l.updateDefaultInterface(name, index, metered, false)
                return
            }
            Thread.sleep(100)
        }
    }
}
