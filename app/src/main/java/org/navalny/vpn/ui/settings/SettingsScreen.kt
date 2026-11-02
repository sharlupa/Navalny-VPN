package org.navalny.vpn.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.navalny.vpn.R
import org.navalny.vpn.core.AppSettings
import org.navalny.vpn.core.ThemeMode
import org.navalny.vpn.core.TunStack
import org.navalny.vpn.ui.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel, contentPadding: PaddingValues, onAbout: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dnsDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier
            .padding(bottom = contentPadding.calculateBottomPadding())
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                SettingsGroup(stringResource(R.string.settings_group_battery)) {
                    SwitchItem(
                        0, 1, Icons.Rounded.BatterySaver,
                        stringResource(R.string.settings_battery_saver),
                        stringResource(R.string.settings_battery_saver_desc),
                        s.batterySaver, vm::setBatterySaver,
                    )
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_connection)) {
                    ChoiceItem(
                        0, 5, Icons.Rounded.Layers,
                        stringResource(R.string.settings_stack),
                        stringResource(R.string.settings_stack_desc),
                        TunStack.entries, s.stack, { it.title }, vm::setStack,
                    )
                    ClickItem(
                        1, 5, Icons.Rounded.Dns,
                        stringResource(R.string.settings_dns),
                        s.remoteDns,
                        onClick = { dnsDialog = true },
                    )
                    SwitchItem(
                        2, 5, Icons.Rounded.Lan,
                        stringResource(R.string.settings_bypass_lan),
                        stringResource(R.string.settings_bypass_lan_desc),
                        s.bypassLan, vm::setBypassLan,
                    )
                    SwitchItem(
                        3, 5, Icons.Rounded.Tune,
                        stringResource(R.string.settings_ipv6),
                        stringResource(R.string.settings_ipv6_desc),
                        s.ipv6, vm::setIpv6,
                    )
                    ChoiceItem(
                        4, 5, Icons.Rounded.Straighten,
                        stringResource(R.string.settings_mtu),
                        null,
                        listOf(1500, 9000), s.mtu, { it.toString() }, vm::setMtu,
                    )
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_subscriptions)) {
                    SwitchItem(
                        0, 1, Icons.Rounded.Sync,
                        stringResource(R.string.settings_auto_update),
                        stringResource(R.string.settings_auto_update_desc),
                        s.autoUpdateSubscriptions, vm::setAutoUpdate,
                    )
                }
            }
            item {
                val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                    ChoiceItem(
                        0, if (dynamicAvailable) 2 else 1, Icons.Rounded.Palette,
                        stringResource(R.string.settings_theme),
                        null,
                        ThemeMode.entries, s.theme,
                        {
                            when (it) {
                                ThemeMode.SYSTEM -> context.getString(R.string.theme_system)
                                ThemeMode.LIGHT -> context.getString(R.string.theme_light)
                                ThemeMode.DARK -> context.getString(R.string.theme_dark)
                            }
                        },
                        vm::setTheme,
                    )
                    if (dynamicAvailable) {
                        SwitchItem(
                            1, 2, Icons.Rounded.Wallpaper,
                            stringResource(R.string.settings_dynamic_color),
                            stringResource(R.string.settings_dynamic_color_desc),
                            s.dynamicColor, vm::setDynamicColor,
                        )
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_group_system)) {
                    ClickItem(
                        0, 1, Icons.Rounded.VpnLock,
                        stringResource(R.string.settings_always_on),
                        stringResource(R.string.settings_always_on_desc),
                        onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    )
                }
            }
            // "About" — always the last item of the settings
            item {
                SettingsGroup(stringResource(R.string.settings_group_about)) {
                    ClickItem(
                        0, 1, Icons.Rounded.Info,
                        stringResource(R.string.about_title),
                        stringResource(R.string.about_subtitle),
                        onClick = onAbout,
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    )
                }
            }
        }
    }

    if (dnsDialog) {
        var value by remember { mutableStateOf(s.remoteDns) }
        AlertDialog(
            onDismissRequest = { dnsDialog = false },
            title = { Text(stringResource(R.string.settings_dns)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.settings_dns_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.setRemoteDns(value); dnsDialog = false }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { value = AppSettings.DEFAULT_DNS }) { Text(stringResource(R.string.reset)) }
            },
        )
    }
}
