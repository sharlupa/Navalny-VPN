package org.navalny.vpn.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.navalny.vpn.ui.MainActivity

/**
 * Quick Settings tile. Declared as ACTIVE_TILE: the system binds it only when we call
 * requestListeningState() on a status change — it never polls.
 */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        when (val s = VpnController.status.value) {
            is VpnStatus.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = s.serverName
            }
            VpnStatus.Connecting, VpnStatus.Stopping -> tile.state = Tile.STATE_UNAVAILABLE
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = null
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        when (VpnController.status.value) {
            is VpnStatus.Connected -> VpnController.stop(this)
            VpnStatus.Idle, is VpnStatus.Error -> {
                if (VpnController.prepare(this) != null) {
                    openApp() // VPN permission dialog needs an Activity
                } else {
                    VpnController.start(this)
                }
            }
            else -> Unit
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
