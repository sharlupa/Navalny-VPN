package org.navalny.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.navalny.vpn.R
import org.navalny.vpn.core.LinkParser
import org.navalny.vpn.ui.components.NavItem
import org.navalny.vpn.ui.components.SlidingNavigationBar
import org.navalny.vpn.ui.exclusions.ExclusionsScreen
import org.navalny.vpn.ui.home.HomeScreen
import org.navalny.vpn.ui.settings.AboutScreen
import org.navalny.vpn.ui.settings.SettingsScreen
import org.navalny.vpn.ui.theme.NavalnyTheme

private enum class Tab(val title: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Rounded.Home, Icons.Outlined.Home),
    EXCLUSIONS(R.string.tab_exclusions, Icons.Rounded.Apps, Icons.Outlined.Apps),
    SETTINGS(R.string.tab_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            NavalnyTheme(themeMode = settings.theme, palette = settings.palette) {
                MainScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** hysteria2:// hy2:// vless:// links opened from a browser / messenger. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW && LinkParser.isSupported(data)) {
            vm.importText(data)
            intent.data = null
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.connect()
    }
    val onConnect: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = org.navalny.vpn.service.VpnController.prepare(context)
        if (prepare != null) vpnPermission.launch(prepare) else vm.connect()
    }

    // Exclusions / settings changes are applied to a running tunnel when the user leaves the tab
    // (one reconnect instead of one per toggle).
    LaunchedEffect(tab) {
        if (tab == Tab.HOME) vm.applyPendingChanges()
    }

    // ...or when the app goes to background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.applyPendingChanges() }

    BackHandler(enabled = showAbout || tab != Tab.HOME) {
        if (showAbout) showAbout = false else tab = Tab.HOME
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            val items = Tab.entries.map { NavItem(stringResource(it.title), it.icon, it.selectedIcon) }
            SlidingNavigationBar(
                items = items,
                selectedIndex = tab.ordinal,
                onSelect = { i ->
                    val t = Tab.entries[i]
                    if (t != tab) tab = t
                    if (t != Tab.SETTINGS) showAbout = false
                },
            )
        },
    ) { padding ->
        val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = if (tab == Tab.SETTINGS && showAbout) null else tab,
            transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
            label = "tabs",
        ) { target ->
            when (target) {
                Tab.HOME -> HomeScreen(vm, onConnect, padding)
                Tab.EXCLUSIONS -> ExclusionsScreen(vm, padding)
                Tab.SETTINGS -> SettingsScreen(vm, padding, onAbout = { showAbout = true })
                null -> AboutScreen(onBack = { showAbout = false }, contentPadding = padding)
            }
        }
    }
}
