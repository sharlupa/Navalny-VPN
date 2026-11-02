package org.navalny.vpn.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.navalny.vpn.App
import org.navalny.vpn.R
import org.navalny.vpn.core.ConfigBuilder
import org.navalny.vpn.core.ServerProfile
import org.navalny.vpn.ui.MainActivity
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface
import io.nekohasekai.libbox.Notification as BoxNotification

/**
 * The VPN service. Runs the sing-box core in-process via libbox.
 *
 * What this class deliberately does NOT do (battery):
 *  - no periodic timers / tickers of its own;
 *  - the notification is static: the session timer is rendered by the system chronometer,
 *    traffic is not pushed into the notification every second;
 *  - the libbox gRPC command server is never started (no extra socket and goroutines) —
 *    traffic for the UI is read from kernel counters only while the screen is visible.
 */
class BoxVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "BoxVpnService"
        const val ACTION_START = "org.navalny.vpn.START"
        const val ACTION_STOP = "org.navalny.vpn.STOP"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running: BoxVpnService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val networkMonitor by lazy {
        DefaultNetworkMonitor(connectivity) { network ->
            // Lets the system attribute metered state / data usage correctly.
            runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        }
    }
    private val localResolver by lazy { LocalResolver { networkMonitor.network } }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var currentServer: ServerProfile? = null
    private var receiverRegistered = false
    private var connectedAtWallClock = 0L

    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Doze: let sing-box drop idle connections and pause its internal timers.
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val idle = powerManager.isDeviceIdleMode
                    scope.launch {
                        val cs = commandServer ?: return@launch
                        if (idle) cs.pause() else cs.wake()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            // ACTION_START, or "android.net.VpnService" from Always-on VPN
            else -> startVpn()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission in system settings.
        stopVpn()
    }

    override fun onDestroy() {
        running = null
        scope.cancel()
        super.onDestroy()
    }

    // region lifecycle

    private fun startVpn() {
        // startForegroundService() obliges us to call startForeground() every time.
        when (val status = VpnController.status.value) {
            is VpnStatus.Connected -> {
                startForegroundCompat(buildNotification(status.serverName, connectedAtWallClock))
                return
            }
            VpnStatus.Connecting, VpnStatus.Stopping -> {
                startForegroundCompat(buildNotification(getString(R.string.status_connecting), null))
                return
            }
            else -> Unit
        }
        running = this
        VpnController.update(this, VpnStatus.Connecting)
        startForegroundCompat(buildNotification(getString(R.string.status_connecting), null))

        scope.launch {
            lifecycle.withLock {
                try {
                    val app = App.instance
                    app.ensureLibbox()
                    app.profiles.load()
                    val server = app.profiles.state.value.selected
                        ?: error(getString(R.string.error_no_server))
                    val config = buildConfig(server)

                    networkMonitor.start()
                    val cs = commandServer ?: CommandServer(this@BoxVpnService, this@BoxVpnService).also {
                        commandServer = it
                    }
                    cs.startOrReloadService(config, overrideOptions())
                    currentServer = server
                    registerIdleReceiver()

                    TrafficMonitor.resetBaseline()
                    connectedAtWallClock = System.currentTimeMillis()
                    VpnController.update(this@BoxVpnService, VpnStatus.Connected(SystemClock.elapsedRealtime(), server.name))
                    notify(buildNotification(server.name, connectedAtWallClock))
                } catch (e: Throwable) {
                    Log.e(TAG, "start failed", e)
                    shutdown(VpnStatus.Error(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Hot-reload (server switch, new exclusions or settings) without tearing down the service. */
    fun reload() {
        scope.launch {
            lifecycle.withLock {
                val cs = commandServer ?: return@withLock
                if (VpnController.status.value !is VpnStatus.Connected) return@withLock
                try {
                    val server = App.instance.profiles.state.value.selected ?: return@withLock
                    VpnController.update(this@BoxVpnService, VpnStatus.Connecting)
                    cs.startOrReloadService(buildConfig(server), overrideOptions())
                    currentServer = server
                    TrafficMonitor.resetBaseline()
                    connectedAtWallClock = System.currentTimeMillis()
                    VpnController.update(this@BoxVpnService, VpnStatus.Connected(SystemClock.elapsedRealtime(), server.name))
                    notify(buildNotification(server.name, connectedAtWallClock))
                } catch (e: Throwable) {
                    Log.e(TAG, "reload failed", e)
                    shutdown(VpnStatus.Error(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun stopVpn() {
        VpnController.update(this, VpnStatus.Stopping)
        scope.launch { lifecycle.withLock { shutdown(VpnStatus.Idle) } }
    }

    private fun shutdown(finalStatus: VpnStatus) {
        unregisterIdleReceiver()
        commandServer?.let { cs ->
            runCatching { cs.closeService() }
            runCatching { cs.close() }
        }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        networkMonitor.stop()
        currentServer = null
        VpnController.update(this, finalStatus)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running = null
        stopSelf()
    }

    private suspend fun buildConfig(server: ServerProfile): String {
        val settings = App.instance.settings.current()
        // Resolve the server once, here, via the system resolver — the core then never needs a
        // bootstrap DNS round-trip and reconnects instantly after network changes.
        val ip = withTimeoutOrNull(5_000) {
            runCatching { InetAddress.getByName(server.host).hostAddress }.getOrNull()
        }
        return ConfigBuilder.build(server, settings, ip)
    }

    private suspend fun overrideOptions(): OverrideOptions {
        val excluded = App.instance.settings.current().excludedApps - packageName
        return OverrideOptions().apply {
            autoRedirect = false
            if (excluded.isNotEmpty()) excludePackage = StringArray(excluded.toList())
        }
    }

    private fun registerIdleReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            idleReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterIdleReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(idleReceiver) }
        receiverRegistered = false
    }

    // endregion

    // region notification

    private fun buildNotification(text: String, chronometerFrom: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BoxVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (chronometerFrom != null) {
                    // The system UI ticks this timer; our process stays asleep.
                    setWhen(chronometerFrom)
                    setUsesChronometer(true)
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    // endregion

    // region PlatformInterface

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // Keep the core's own sockets out of the tunnel.
        if (!protect(fd)) error("protect failed")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error(getString(R.string.error_no_permission))

        val builder = Builder()
            .setSession(currentServer?.name ?: getString(R.string.app_name))
            .setMtu(options.getMTU())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4 = options.getInet4Address()
        while (inet4.hasNext()) {
            val a = inet4.next()
            builder.addAddress(a.address(), a.prefix())
        }
        val inet6 = options.getInet6Address()
        while (inet6.hasNext()) {
            val a = inet6.next()
            builder.addAddress(a.address(), a.prefix())
        }

        if (options.getAutoRoute()) {
            if (options.getDNSMode().value != Libbox.DNSModeDisabled) {
                val dns = options.getDNSServerAddress()
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.getInet4RouteAddress()
                if (r4.hasNext()) {
                    while (r4.hasNext()) builder.addRoute(r4.next().toIpPrefix())
                } else if (options.getInet4Address().hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val r6 = options.getInet6RouteAddress()
                if (r6.hasNext()) {
                    while (r6.hasNext()) builder.addRoute(r6.next().toIpPrefix())
                } else if (options.getInet6Address().hasNext()) {
                    builder.addRoute("::", 0)
                }
                val e4 = options.getInet4RouteExcludeAddress()
                while (e4.hasNext()) builder.excludeRoute(e4.next().toIpPrefix())
                val e6 = options.getInet6RouteExcludeAddress()
                while (e6.hasNext()) builder.excludeRoute(e6.next().toIpPrefix())
            } else {
                val r4 = options.getInet4RouteRange()
                while (r4.hasNext()) {
                    val a = r4.next()
                    builder.addRoute(a.address(), a.prefix())
                }
                val r6 = options.getInet6RouteRange()
                while (r6.hasNext()) {
                    val a = r6.next()
                    builder.addRoute(a.address(), a.prefix())
                }
            }

            // Exclusions tab: these apps bypass the VPN entirely (kernel-level, zero cost).
            val exclude = options.getExcludePackage()
            while (exclude.hasNext()) {
                val pkg = exclude.next()
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    // app was uninstalled — ignore
                }
            }
        }

        val pfd = builder.establish() ?: error(getString(R.string.error_no_permission))
        // On reload the old instance has already been closed (it held a dup of the fd).
        val old = tunFd
        tunFd = pfd
        runCatching { old?.close() }
        return pfd.fd
    }

    private fun io.nekohasekai.libbox.RoutePrefix.toIpPrefix() =
        android.net.IpPrefix(InetAddress.getByName(address()), prefix())

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = error("not supported")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<BoxNetworkInterface>()
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val lp = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ni = javaInterfaces.find { it.name == lp.interfaceName } ?: continue
            val box = BoxNetworkInterface()
            box.name = lp.interfaceName
            box.index = ni.index
            runCatching { box.mtu = ni.mtu }
            box.addresses = StringArray(ni.interfaceAddresses.map { it.toPrefix() })
            box.dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress })
            box.gateway = StringArray(
                lp.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress },
            )
            box.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            box.flags = flags
            box.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(box)
        }
        return InterfaceArray(result.iterator())
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun localDNSTransport(): LocalDNSTransport = localResolver

    override fun sendNotification(notification: BoxNotification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("not supported")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")
    override fun lookupSFTPServer(): String = error("not supported")
    override fun readSystemSSHHostKey(): String = error("not supported")
    override fun tailscaleHostname(): String = Build.MODEL
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    // endregion

    // region CommandServerHandler

    override fun serviceStop() = stopVpn()
    override fun serviceReload() = reload()
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {}
    override fun connectSSHAgent(): Int = -1

    // endregion
}
