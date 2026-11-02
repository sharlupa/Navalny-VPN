package org.navalny.vpn.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.navalny.vpn.App
import org.navalny.vpn.R
import org.navalny.vpn.core.AppSettings
import org.navalny.vpn.core.LinkParser
import org.navalny.vpn.core.ProfilesState
import org.navalny.vpn.core.ServerProfile
import org.navalny.vpn.core.Subscription
import org.navalny.vpn.core.SubscriptionFetcher
import org.navalny.vpn.core.ThemeMode
import org.navalny.vpn.core.TunStack
import org.navalny.vpn.service.TrafficMonitor
import org.navalny.vpn.service.TrafficSample
import org.navalny.vpn.service.VpnController
import org.navalny.vpn.service.VpnStatus

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

data class TrafficUi(
    val sample: TrafficSample,
    /** Last [HISTORY] samples of download / upload speed for the chart. */
    val down: List<Long>,
    val up: List<Long>,
) {
    companion object {
        const val HISTORY = 60
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val profilesRepo = app.profiles
    private val settingsRepo = app.settings

    val profiles: StateFlow<ProfilesState> = profilesRepo.state
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val status: StateFlow<VpnStatus> = VpnController.status

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubscriptions: StateFlow<Set<String>> = _updating.asStateFlow()

    /** Set when something that affects the running tunnel changed; applied on leaving the tab. */
    private val _pendingReload = MutableStateFlow(false)
    val pendingReload: StateFlow<Boolean> = _pendingReload.asStateFlow()

    init {
        viewModelScope.launch {
            profilesRepo.load()
            if (settingsRepo.current().autoUpdateSubscriptions) autoUpdateSubscriptions()
        }
    }

    // region traffic & timer — cold flows, run only while the UI collects them

    private val downHistory = ArrayDeque<Long>()
    private val upHistory = ArrayDeque<Long>()
    private var lastSampleAt = 0L

    /**
     * Collected with collectAsStateWithLifecycle → stops when the app goes to background or
     * the screen turns off. While not collected nothing polls the counters.
     */
    val traffic: Flow<TrafficUi?> = status.map { it is VpnStatus.Connected }
        .flatMapLatest { connected ->
            if (!connected) {
                downHistory.clear(); upHistory.clear()
                flowOf(null)
            } else {
                TrafficMonitor.samples().onEach {
                    val now = SystemClock.elapsedRealtime()
                    // after a long gap (screen was off) the old curve is meaningless
                    if (now - lastSampleAt > TrafficUi.HISTORY * 1000L) { downHistory.clear(); upHistory.clear() }
                    lastSampleAt = now
                    downHistory.addLast(it.downSpeed); upHistory.addLast(it.upSpeed)
                    while (downHistory.size > TrafficUi.HISTORY) downHistory.removeFirst()
                    while (upHistory.size > TrafficUi.HISTORY) upHistory.removeFirst()
                }.map { TrafficUi(it, downHistory.toList(), upHistory.toList()) }
            }
        }

    /** Session duration in ms, ticking once per second only while visible. */
    val sessionDuration: Flow<Long?> = status.flatMapLatest { s ->
        if (s !is VpnStatus.Connected) {
            flowOf(null)
        } else {
            flow {
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - s.startedAt
                    emit(elapsed)
                    delay(1000 - elapsed % 1000)
                }
            }
        }
    }

    // endregion

    // region connection

    fun connect() {
        if (profiles.value.selected == null) {
            toast(R.string.error_no_server)
            return
        }
        VpnController.clearError()
        VpnController.start(app)
    }

    fun disconnect() = VpnController.stop(app)

    fun applyPendingChanges() {
        if (_pendingReload.value) {
            _pendingReload.value = false
            VpnController.reloadIfRunning()
        }
    }

    private fun markChanged() {
        if (status.value is VpnStatus.Connected) _pendingReload.value = true
    }

    // endregion

    // region servers & subscriptions

    /** Import from clipboard / deep link / text field: share links or a subscription URL. */
    fun importText(text: String) {
        val t = text.trim()
        viewModelScope.launch {
            when {
                t.startsWith("http://", true) || t.startsWith("https://", true) -> addSubscription(t, "")
                else -> {
                    val servers = LinkParser.parseMany(t)
                    if (servers.isEmpty()) {
                        toast(R.string.import_nothing)
                    } else {
                        profilesRepo.addServers(servers)
                        toast(app.getString(R.string.import_done, servers.size))
                    }
                }
            }
        }
    }

    fun addSubscription(url: String, name: String) {
        viewModelScope.launch {
            val sub = profilesRepo.addSubscription(name.ifBlank { hostOf(url) }, url.trim())
            refresh(sub, userInitiated = true)
        }
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch { refresh(sub, userInitiated = true) }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch { profiles.value.subscriptions.forEach { refresh(it, userInitiated = true) } }
    }

    private suspend fun autoUpdateSubscriptions() {
        val now = System.currentTimeMillis()
        profiles.value.subscriptions
            .filter { now - it.updatedAt > AUTO_UPDATE_INTERVAL_MS }
            .forEach { refresh(it, userInitiated = false) }
    }

    private suspend fun refresh(sub: Subscription, userInitiated: Boolean) {
        if (sub.id in _updating.value) return
        _updating.value += sub.id
        try {
            val selectedBefore = profiles.value.selected
            val fetched = SubscriptionFetcher.fetch(sub.url, sub.id)
            val updated = sub.copy(
                name = if (sub.name.isBlank() || sub.name == hostOf(sub.url)) fetched.title ?: sub.name else sub.name,
                updatedAt = System.currentTimeMillis(),
                uploadBytes = fetched.upload,
                downloadBytes = fetched.download,
                totalBytes = fetched.total,
                expireAt = fetched.expire,
            )
            profilesRepo.replaceSubscriptionServers(updated, fetched.servers)
            if (userInitiated) toast(app.getString(R.string.subscription_updated, fetched.servers.size))
            val selectedAfter = profiles.value.selected
            if (selectedBefore?.subscriptionId == sub.id && selectedAfter != null &&
                (selectedBefore.link != selectedAfter.link)
            ) {
                markChanged()
                applyPendingChanges()
            }
        } catch (e: Exception) {
            if (userInitiated) toast(app.getString(R.string.subscription_error, e.message ?: ""))
        } finally {
            _updating.value -= sub.id
        }
    }

    fun select(server: ServerProfile) {
        viewModelScope.launch {
            val changed = profiles.value.selected?.id != server.id
            profilesRepo.select(server.id)
            if (changed && status.value is VpnStatus.Connected) VpnController.reloadIfRunning()
        }
    }

    fun deleteServer(server: ServerProfile) = viewModelScope.launch { profilesRepo.deleteServer(server.id) }
    fun deleteSubscription(sub: Subscription) = viewModelScope.launch { profilesRepo.deleteSubscription(sub.id) }

    // endregion

    // region exclusions

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val installedApps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    /** Loaded once, on first visit of the Exclusions tab. */
    fun loadAppsIfNeeded() {
        if (_apps.value != null) return
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                    .asSequence()
                    .filter { it.packageName != app.packageName }
                    // only apps that can use the network at all
                    .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
                    .mapNotNull { pi ->
                        val ai = pi.applicationInfo ?: return@mapNotNull null
                        InstalledApp(
                            packageName = pi.packageName,
                            label = ai.loadLabel(pm).toString(),
                            isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                                ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
        }
    }

    fun setExcluded(pkg: String, excluded: Boolean) = viewModelScope.launch {
        settingsRepo.setExcluded(pkg, excluded)
        markChanged()
    }

    fun clearExclusions() = viewModelScope.launch {
        settingsRepo.setExcludedApps(emptySet())
        markChanged()
    }

    // endregion

    // region settings

    fun setBatterySaver(v: Boolean) = edit { settingsRepo.setBatterySaver(v) }
    fun setStack(v: TunStack) = edit { settingsRepo.setStack(v) }
    fun setRemoteDns(v: String) = edit { settingsRepo.setRemoteDns(v) }
    fun setIpv6(v: Boolean) = edit { settingsRepo.setIpv6(v) }
    fun setBypassLan(v: Boolean) = edit { settingsRepo.setBypassLan(v) }
    fun setMtu(v: Int) = edit { settingsRepo.setMtu(v) }
    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoUpdate(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { settingsRepo.setTheme(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(v) }

    private fun edit(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        markChanged()
    }

    // endregion

    private fun toast(res: Int) = toast(app.getString(res))
    private fun toast(text: String) {
        _messages.tryEmit(text)
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private companion object {
        const val AUTO_UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }
}
