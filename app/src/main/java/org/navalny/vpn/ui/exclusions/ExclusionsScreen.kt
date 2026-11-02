package org.navalny.vpn.ui.exclusions

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.navalny.vpn.R
import org.navalny.vpn.service.VpnStatus
import org.navalny.vpn.ui.InstalledApp
import org.navalny.vpn.ui.MainViewModel

@Composable
fun ExclusionsScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    LaunchedEffect(Unit) { vm.loadAppsIfNeeded() }
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pending by vm.pendingReload.collectAsStateWithLifecycle()
    val excluded = settings.excludedApps

    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Selected apps go first — order is fixed when the list loads so rows do not jump on tap.
    val ordered = remember(apps) {
        apps?.sortedWith(compareByDescending<InstalledApp> { it.packageName in excluded }.thenBy { it.label.lowercase() })
    }
    val visible = remember(ordered, query, showSystem, excluded) {
        ordered.orEmpty().filter { app ->
            (showSystem || !app.isSystem || app.packageName in excluded) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier
            .padding(bottom = contentPadding.calculateBottomPadding())
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_exclusions)) },
                subtitle = { Text(stringResource(R.string.exclusions_subtitle, excluded.size)) },
                actions = {
                    if (excluded.isNotEmpty()) {
                        TextButton(onClick = vm::clearExclusions) { Text(stringResource(R.string.exclusions_clear)) }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = pending && status is VpnStatus.Connected,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = vm::applyPendingChanges,
                    icon = { Icon(Icons.Rounded.Check, null) },
                    text = { Text(stringResource(R.string.apply)) },
                )
            }
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding(),
                bottom = inner.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, null) }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.exclusions_search)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "filters") {
                Row(Modifier.padding(vertical = 4.dp)) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text(stringResource(R.string.exclusions_show_system)) },
                        leadingIcon = if (showSystem) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (apps == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            }
            itemsIndexed(visible, key = { _, a -> a.packageName }) { index, app ->
                val checked = app.packageName in excluded
                SegmentedListItem(
                    checked = checked,
                    onCheckedChange = { vm.setExcluded(app.packageName, it) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = visible.size),
                    leadingContent = { AppIcon(app.packageName) },
                    supportingContent = {
                        Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    modifier = Modifier.animateItem(),
                ) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (apps != null && visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.exclusions_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private object IconCache {
    // ~ 4 MB of 40dp icons; icons are decoded lazily, only for rows on screen.
    val cache = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap by produceState(IconCache.cache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadIcon(context, packageName, sizePx) }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(Icons.Rounded.Android, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

private fun loadIcon(context: Context, pkg: String, size: Int): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(size, size).asImageBitmap()
}.getOrNull()?.also { IconCache.cache.put(pkg, it) }
