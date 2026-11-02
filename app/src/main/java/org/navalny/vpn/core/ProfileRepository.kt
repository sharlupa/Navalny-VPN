package org.navalny.vpn.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ProfilesState(
    val servers: List<ServerProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedId: String? = null,
    val loaded: Boolean = false,
) {
    val selected: ServerProfile? get() = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
}

/**
 * Servers + subscriptions stored as one small JSON file. Everything is kept in memory as a
 * StateFlow; the file is only touched on change (no database, no background work).
 */
class ProfileRepository(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (_state.value.loaded) return@withLock
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject(file.readText())
                ProfilesState(
                    servers = root.optJSONArray("servers").map { ServerProfile.fromJson(it) },
                    subscriptions = root.optJSONArray("subscriptions").map { Subscription.fromJson(it) },
                    selectedId = root.optString("selected").ifEmpty { null },
                    loaded = true,
                )
            }.getOrElse { ProfilesState(loaded = true) }
        }
        _state.value = loaded
    }

    private suspend fun mutate(block: (ProfilesState) -> ProfilesState) {
        load()
        mutex.withLock {
            _state.update(block)
            val snapshot = _state.value
            withContext(Dispatchers.IO) { persist(snapshot) }
        }
    }

    private fun persist(s: ProfilesState) {
        val root = JSONObject().apply {
            put("servers", JSONArray(s.servers.map { it.toJson() }))
            put("subscriptions", JSONArray(s.subscriptions.map { it.toJson() }))
            s.selectedId?.let { put("selected", it) }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    suspend fun select(id: String) = mutate { it.copy(selectedId = id) }

    suspend fun addServers(servers: List<ServerProfile>) = mutate {
        it.copy(servers = it.servers + servers, selectedId = it.selectedId ?: servers.firstOrNull()?.id)
    }

    suspend fun updateServer(server: ServerProfile) = mutate { s ->
        s.copy(servers = s.servers.map { if (it.id == server.id) server else it })
    }

    suspend fun deleteServer(id: String) = mutate { s ->
        s.copy(servers = s.servers.filterNot { it.id == id }, selectedId = s.selectedId.takeIf { it != id })
    }

    suspend fun addSubscription(name: String, url: String): Subscription {
        val sub = Subscription(id = UUID.randomUUID().toString(), name = name, url = url)
        mutate { it.copy(subscriptions = it.subscriptions + sub) }
        return sub
    }

    suspend fun deleteSubscription(id: String) = mutate { s ->
        val remaining = s.servers.filterNot { it.subscriptionId == id }
        s.copy(
            subscriptions = s.subscriptions.filterNot { it.id == id },
            servers = remaining,
            selectedId = s.selectedId.takeIf { sel -> remaining.any { it.id == sel } },
        )
    }

    /**
     * Replaces the servers of a subscription, trying to keep the current selection
     * (matched by name + host + port) so an update does not silently switch the server.
     */
    suspend fun replaceSubscriptionServers(sub: Subscription, servers: List<ServerProfile>) = mutate { s ->
        val oldSelected = s.servers.firstOrNull { it.id == s.selectedId }
        val others = s.servers.filterNot { it.subscriptionId == sub.id }
        var newSelected = s.selectedId
        if (oldSelected?.subscriptionId == sub.id) {
            newSelected = servers.firstOrNull { it.name == oldSelected.name && it.host == oldSelected.host && it.port == oldSelected.port }?.id
                ?: servers.firstOrNull()?.id
        }
        s.copy(
            servers = others + servers,
            subscriptions = s.subscriptions.map { if (it.id == sub.id) sub else it },
            selectedId = newSelected ?: others.firstOrNull()?.id,
        )
    }

    private inline fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> runCatching { transform(getJSONObject(i)) }.getOrNull() }
    }
}
