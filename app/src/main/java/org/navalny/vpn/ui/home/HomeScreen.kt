package org.navalny.vpn.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.navalny.vpn.R
import org.navalny.vpn.core.ServerProfile
import org.navalny.vpn.service.VpnStatus
import org.navalny.vpn.ui.MainViewModel
import org.navalny.vpn.ui.components.formatBytes
import org.navalny.vpn.ui.components.formatDuration
import org.navalny.vpn.ui.components.formatSpeed

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onConnectClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    // Both flows below stop ticking as soon as the app is not visible.
    val traffic by vm.traffic.collectAsStateWithLifecycle(initialValue = null)
    val duration by vm.sessionDuration.collectAsStateWithLifecycle(initialValue = null)
    var showServers by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        // 1. Server / subscription picker — above the connect button
        ServerSelector(
            server = profiles.selected,
            onClick = { showServers = true },
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Spacer(Modifier.height(28.dp))

        // 2. Connect button
        ConnectButton(
            status = status,
            onClick = {
                if (status is VpnStatus.Connected) vm.disconnect() else onConnectClick()
            },
        )

        Spacer(Modifier.height(12.dp))
        StatusLine(status)
        Spacer(Modifier.height(24.dp))

        // 3. Session time, traffic, chart — below the button
        SessionPanel(
            durationMs = duration,
            downSpeed = traffic?.sample?.downSpeed ?: 0,
            upSpeed = traffic?.sample?.upSpeed ?: 0,
            downTotal = traffic?.sample?.downTotal ?: 0,
            upTotal = traffic?.sample?.upTotal ?: 0,
            down = traffic?.down.orEmpty(),
            up = traffic?.up.orEmpty(),
            connected = status is VpnStatus.Connected,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showServers) {
        ServerSheet(vm = vm, onDismiss = { showServers = false })
    }
}

@Composable
private fun StatusLine(status: VpnStatus) {
    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it::class },
        label = "status",
    ) { s ->
        val (text, color) = when (s) {
            is VpnStatus.Connected -> stringResource(R.string.status_connected) to MaterialTheme.colorScheme.primary
            VpnStatus.Connecting -> stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
            VpnStatus.Stopping -> stringResource(R.string.status_stopping) to MaterialTheme.colorScheme.onSurfaceVariant
            is VpnStatus.Error -> stringResource(R.string.status_error, s.message) to MaterialTheme.colorScheme.error
            VpnStatus.Idle -> stringResource(R.string.status_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ServerSelector(server: ServerProfile?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (server == null) Icons.Rounded.Add else Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server?.name ?: stringResource(R.string.server_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server?.subtitle ?: stringResource(R.string.server_add_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.UnfoldMore,
                contentDescription = stringResource(R.string.servers_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionPanel(
    durationMs: Long?,
    downSpeed: Long,
    upSpeed: Long,
    downTotal: Long,
    upTotal: Long,
    down: List<Long>,
    up: List<Long>,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Session time
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.session_time),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDuration(durationMs ?: 0),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Current traffic
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrafficTile(
                icon = { Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.traffic_download),
                speed = formatSpeed(downSpeed),
                total = formatBytes(downTotal),
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TrafficTile(
                icon = { Icon(Icons.Rounded.ArrowUpward, null, tint = MaterialTheme.colorScheme.tertiary) },
                title = stringResource(R.string.traffic_upload),
                speed = formatSpeed(upSpeed),
                total = formatBytes(upTotal),
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }

        // Chart
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.traffic_chart),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    TrafficChart(down = down, up = up, modifier = Modifier.fillMaxSize())
                    val hintAlpha by animateFloatAsState(
                        if (connected) 0f else 1f,
                        MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "hint",
                    )
                    Text(
                        stringResource(R.string.traffic_chart_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.graphicsLayer { alpha = hintAlpha },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.traffic_download), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(16.dp))
                    LegendDot(MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.traffic_upload), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(10.dp)
            .clip(MaterialShapes.Circle.toShape())
            .background(color),
    )
}

@Composable
private fun TrafficTile(
    icon: @Composable () -> Unit,
    title: String,
    speed: String,
    total: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(speed, style = MaterialTheme.typography.titleLargeEmphasized, color = accent, maxLines = 1)
            Text(
                stringResource(R.string.traffic_total, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
