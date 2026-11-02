package org.navalny.vpn.service

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class TrafficSample(
    /** bytes/s */
    val downSpeed: Long,
    val upSpeed: Long,
    /** bytes since the tunnel started */
    val downTotal: Long,
    val upTotal: Long,
)

/**
 * Traffic of the tunnel, read from kernel per-UID counters (TrafficStats) — the sing-box core
 * is the only thing in our UID that talks to the network, so its counters are exactly the
 * VPN traffic (with protocol overhead).
 *
 * This is a cold Flow: it only ticks while the UI collects it (screen on + app visible).
 * With the screen off there is zero polling — nothing wakes the CPU.
 */
object TrafficMonitor {
    private val uid = Process.myUid()

    @Volatile private var baseRx = -1L
    @Volatile private var baseTx = -1L

    /** Called when a session starts so totals are counted from zero. */
    fun resetBaseline() {
        baseRx = TrafficStats.getUidRxBytes(uid)
        baseTx = TrafficStats.getUidTxBytes(uid)
    }

    val isSupported: Boolean get() = TrafficStats.getUidRxBytes(uid) != TrafficStats.UNSUPPORTED.toLong()

    fun samples(periodMs: Long = 1000): Flow<TrafficSample> = flow {
        if (baseRx < 0) resetBaseline()
        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        var lastT = SystemClock.elapsedRealtime()
        emit(TrafficSample(0, 0, (lastRx - baseRx).coerceAtLeast(0), (lastTx - baseTx).coerceAtLeast(0)))
        while (true) {
            delay(periodMs)
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastT).coerceAtLeast(1)
            emit(
                TrafficSample(
                    downSpeed = ((rx - lastRx).coerceAtLeast(0) * 1000 / dt),
                    upSpeed = ((tx - lastTx).coerceAtLeast(0) * 1000 / dt),
                    downTotal = (rx - baseRx).coerceAtLeast(0),
                    upTotal = (tx - baseTx).coerceAtLeast(0),
                ),
            )
            lastRx = rx; lastTx = tx; lastT = now
        }
    }
}
