package org.navalny.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnStatus {
    data object Idle : VpnStatus
    data object Connecting : VpnStatus
    /** [startedAt] — SystemClock.elapsedRealtime() at the moment the tunnel came up. */
    data class Connected(val startedAt: Long, val serverName: String) : VpnStatus
    data object Stopping : VpnStatus
    data class Error(val message: String) : VpnStatus
}

/** Process-wide VPN state. The service and the UI live in one process, so a StateFlow is enough. */
object VpnController {
    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Idle)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    internal fun update(context: Context, status: VpnStatus) {
        _status.value = status
        // ACTIVE_TILE: the tile is only bound when we ask for it — no polling.
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
    }

    /** Returns an Intent that must be launched first if VPN permission was not granted yet. */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        BoxVpnService.running?.stopVpn() ?: run {
            if (_status.value is VpnStatus.Error) _status.value = VpnStatus.Idle
        }
    }

    /** Re-applies config (server switch, exclusions, settings) if the tunnel is up. */
    fun reloadIfRunning() {
        BoxVpnService.running?.reload()
    }

    fun clearError() {
        if (_status.value is VpnStatus.Error) _status.value = VpnStatus.Idle
    }
}
