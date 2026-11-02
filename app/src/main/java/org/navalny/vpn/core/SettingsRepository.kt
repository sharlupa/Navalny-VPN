package org.navalny.vpn.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** TUN network stack. `system` is the cheapest on CPU (kernel TCP), hence the default. */
enum class TunStack(val value: String, val title: String) {
    SYSTEM("system", "System"),
    MIXED("mixed", "Mixed"),
    GVISOR("gvisor", "gVisor"),
}

data class AppSettings(
    val excludedApps: Set<String> = emptySet(),
    val batterySaver: Boolean = true,
    val stack: TunStack = TunStack.SYSTEM,
    val remoteDns: String = DEFAULT_DNS,
    val ipv6: Boolean = false,
    val bypassLan: Boolean = true,
    val mtu: Int = 9000,
    val autoUpdateSubscriptions: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
) {
    companion object {
        const val DEFAULT_DNS = "https://1.1.1.1/dns-query"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val excluded = stringSetPreferencesKey("excluded_apps")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val stack = stringPreferencesKey("stack")
        val dns = stringPreferencesKey("remote_dns")
        val ipv6 = booleanPreferencesKey("ipv6")
        val bypassLan = booleanPreferencesKey("bypass_lan")
        val mtu = intPreferencesKey("mtu")
        val autoUpdate = booleanPreferencesKey("auto_update_subs")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            excludedApps = this[Keys.excluded] ?: d.excludedApps,
            batterySaver = this[Keys.batterySaver] ?: d.batterySaver,
            stack = TunStack.entries.firstOrNull { it.value == this[Keys.stack] } ?: d.stack,
            remoteDns = this[Keys.dns] ?: d.remoteDns,
            ipv6 = this[Keys.ipv6] ?: d.ipv6,
            bypassLan = this[Keys.bypassLan] ?: d.bypassLan,
            mtu = this[Keys.mtu] ?: d.mtu,
            autoUpdateSubscriptions = this[Keys.autoUpdate] ?: d.autoUpdateSubscriptions,
            theme = ThemeMode.entries.firstOrNull { it.name == this[Keys.theme] } ?: d.theme,
            dynamicColor = this[Keys.dynamicColor] ?: d.dynamicColor,
        )
    }

    suspend fun setExcluded(pkg: String, excluded: Boolean) = context.dataStore.edit {
        val cur = it[Keys.excluded].orEmpty()
        it[Keys.excluded] = if (excluded) cur + pkg else cur - pkg
    }

    suspend fun setExcludedApps(apps: Set<String>) = context.dataStore.edit { it[Keys.excluded] = apps }
    suspend fun setBatterySaver(v: Boolean) = context.dataStore.edit { it[Keys.batterySaver] = v }
    suspend fun setStack(v: TunStack) = context.dataStore.edit { it[Keys.stack] = v.value }
    suspend fun setRemoteDns(v: String) = context.dataStore.edit { it[Keys.dns] = v.trim().ifEmpty { AppSettings.DEFAULT_DNS } }
    suspend fun setIpv6(v: Boolean) = context.dataStore.edit { it[Keys.ipv6] = v }
    suspend fun setBypassLan(v: Boolean) = context.dataStore.edit { it[Keys.bypassLan] = v }
    suspend fun setMtu(v: Int) = context.dataStore.edit { it[Keys.mtu] = v }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[Keys.autoUpdate] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = v }
}
