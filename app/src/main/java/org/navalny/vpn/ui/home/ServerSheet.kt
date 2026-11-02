package org.navalny.vpn.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.navalny.vpn.R
import org.navalny.vpn.core.ServerProfile
import org.navalny.vpn.core.Subscription
import org.navalny.vpn.ui.MainViewModel
import org.navalny.vpn.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun ServerSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val state by vm.profiles.collectAsStateWithLifecycle()
    val updating by vm.updatingSubscriptions.collectAsStateWithLifecycle()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDeleteSub by remember { mutableStateOf<Subscription?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.servers_title), style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val text = context.readClipboard()
                        if (text.isNullOrBlank()) showAddDialog = true else vm.importText(text)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.import_clipboard))
                }
                FilledTonalButton(onClick = { showAddDialog = true }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.import_manual))
                }
                Spacer(Modifier.weight(1f))
                if (state.subscriptions.isNotEmpty()) {
                    IconButton(onClick = vm::updateAllSubscriptions) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.subscription_update_all))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.servers.isEmpty()) {
            Text(
                stringResource(R.string.servers_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.subscriptions, key = { it.id }) { sub ->
                ServerGroup(
                    header = { SubscriptionHeader(sub, sub.id in updating, vm, onDelete = { confirmDeleteSub = sub }) },
                    servers = state.servers.filter { it.subscriptionId == sub.id },
                    selectedId = state.selected?.id,
                    onSelect = { vm.select(it); onDismiss() },
                    onDelete = null,
                )
            }
            val own = state.servers.filter { it.subscriptionId == null }
            if (own.isNotEmpty()) {
                item(key = "own") {
                    ServerGroup(
                        header = {
                            Text(
                                stringResource(R.string.servers_own),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        },
                        servers = own,
                        selectedId = state.selected?.id,
                        onSelect = { vm.select(it); onDismiss() },
                        onDelete = { vm.deleteServer(it) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { text, name ->
                showAddDialog = false
                val t = text.trim()
                if (t.startsWith("http://", true) || t.startsWith("https://", true)) vm.addSubscription(t, name) else vm.importText(t)
            },
        )
    }

    confirmDeleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSub = null },
            title = { Text(stringResource(R.string.subscription_delete_title)) },
            text = { Text(stringResource(R.string.subscription_delete_text, sub.name)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteSubscription(sub); confirmDeleteSub = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSub = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SubscriptionHeader(sub: Subscription, updating: Boolean, vm: MainViewModel, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.RssFeed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = buildList {
                if (sub.totalBytes > 0) {
                    add(formatBytes(sub.uploadBytes + sub.downloadBytes) + " / " + formatBytes(sub.totalBytes))
                }
                if (sub.expireAt > 0) {
                    add(stringResource(R.string.subscription_expires, DateFormat.getDateInstance().format(Date(sub.expireAt * 1000))))
                }
            }
            if (info.isNotEmpty()) {
                Text(
                    info.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (updating) {
            LoadingIndicator(Modifier.size(40.dp))
        } else {
            IconButton(onClick = { vm.updateSubscription(sub) }) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.subscription_update))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
        }
    }
}

@Composable
private fun ServerGroup(
    header: @Composable () -> Unit,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (ServerProfile) -> Unit,
    onDelete: ((ServerProfile) -> Unit)?,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        header()
        servers.forEachIndexed { index, server ->
            val selected = server.id == selectedId
            var menu by remember { mutableStateOf(false) }
            SegmentedListItem(
                selected = selected,
                onClick = { onSelect(server) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = servers.size),
                leadingContent = {
                    if (selected) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        ProtocolBadge(server)
                    }
                },
                supportingContent = { Text(server.subtitle, maxLines = 1) },
                trailingContent = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (server.link.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_link)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = { context.writeClipboard(server.link); menu = false },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { onDelete(server); menu = false },
                            )
                        }
                    }
                },
            ) {
                Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProtocolBadge(server: ServerProfile) {
    Text(
        if (server.protocol.name == "HYSTERIA2") "HY2" else "VL",
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.width(28.dp),
    )
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (text: String, name: String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val isUrl = text.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_manual_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.import_field)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isUrl) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.subscription_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text, name) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun Context.readClipboard(): String? {
    val cm = getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
}

private fun Context.writeClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", text))
}
