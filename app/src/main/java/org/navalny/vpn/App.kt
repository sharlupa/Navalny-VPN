package org.navalny.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.navalny.vpn.core.ProfileRepository
import org.navalny.vpn.core.SettingsRepository
import java.io.File

class App : Application() {

    val profiles by lazy { ProfileRepository(this) }
    val settings by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private var libboxReady = false

    /**
     * The Go runtime is loaded lazily — only when the VPN is actually started — so simply
     * opening the app to look at settings costs nothing extra.
     */
    @Synchronized
    fun ensureLibbox() {
        if (libboxReady) return
        val working = File(filesDir, "box").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = working.path
                it.tempPath = cacheDir.path
                // https://github.com/golang/go/issues/68760
                it.fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                it.logMaxLines = 0
                it.debug = false
                it.crashReportSource = "Application"
                it.appVersion = BuildConfig.VERSION_CODE.toString()
                it.appMarketingVersion = BuildConfig.VERSION_NAME
                it.oomKillerEnabled = false
                it.powerReportEnabled = false
            },
        )
        libboxReady = true
    }

    companion object {
        const val CHANNEL_VPN = "vpn"
        lateinit var instance: App
            private set
    }
}
