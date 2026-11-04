package io.github.iamr0s.carvex.ui.page

import io.github.iamr0s.carvex.ui.component.*
import io.github.iamr0s.carvex.ui.model.*
import io.github.iamr0s.carvex.ui.screen.Nav
import io.github.iamr0s.carvex.ui.theme.AppColors

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.iamr0s.carvex.BuildConfig
import io.github.iamr0s.carvex.FirstRun
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.Permissions
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.ui.model.Device
import io.github.iamr0s.carvex.ui.model.DeviceViewModel
import io.github.iamr0s.carvex.ui.model.Link
import io.github.iamr0s.carvex.ui.model.UiState
import io.github.iamr0s.carvex.ui.tool.LocalAppInsets
import io.github.iamr0s.carvex.ui.tool.toPaddingValues

@Composable
fun DevicesPage(
    state: UiState,
    vm: DeviceViewModel,
    nav: Nav,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val missingPermissions = remember(permissionRevision) { Permissions.missing(context) }
    LaunchedEffect(missingPermissions.isEmpty()) {
        if (missingPermissions.isEmpty()) vm.refresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRevision++ }
    FirstRunEffect(vm)

    val now = rememberNow()
    val connecting = state.link as? Link.Connecting
    val connectedId = (state.link as? Link.Connected)?.deviceId
    val visibleDevices = state.devices.filter { it.id != connectedId }
    val cs = MaterialTheme.colorScheme

    val safe = LocalAppInsets.current.content.toPaddingValues()
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = safe.calculateLeftPadding(direction),
            top = safe.calculateTopPadding() + 12.dp,
            end = safe.calculateRightPadding(direction),
            bottom = safe.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item(key = "devices-title") {
            ScreenTitle(stringResource(R.string.nav_devices))
        }
        if (missingPermissions.isNotEmpty()) {
            item(key = "permission-card") {
                PermissionCard(
                    modifier = Modifier.animateItem(),
                    onRequest = { permissionLauncher.launch(Permissions.requested()) },
                )
            }
        }
        item(key = "status-card") {
            StatusCard(
                state,
                onClick = nav.openManage,
                onDisconnect = vm::disconnect,
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "device-limit") {
            Text(
                stringResource(R.string.one_device_limit),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.animateItem().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        item(key = "nearby-label") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.nearby_devices),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.devices_last_scan, agoText(state.lastScan, now), state.settings.deviceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                RefreshButton(scanning = state.scanning, onClick = vm::refresh)
            }
        }
        if (visibleDevices.isEmpty()) {
            val placeholderKey = when {
                missingPermissions.isNotEmpty() -> "placeholder-permissions"
                state.scanning -> "placeholder-scanning"
                else -> "placeholder-empty"
            }
            item(key = placeholderKey) {
                DevicePlaceholder(
                    modifier = Modifier.animateItem(),
                    title = stringResource(
                        when {
                            missingPermissions.isNotEmpty() -> R.string.devices_missing_permission_title
                            state.scanning -> R.string.devices_scanning
                            else -> R.string.devices_empty_title
                        },
                    ),
                    body = stringResource(
                        when {
                            missingPermissions.isNotEmpty() -> R.string.devices_missing_permission_body
                            state.scanning -> R.string.devices_scanning_body
                            else -> R.string.devices_empty_body
                        },
                    ),
                )
            }
        }
        items(visibleDevices, key = { "device-${it.id}" }) { device ->
            DeviceRow(
                device = device,
                busy = connecting != null,
                progress = state.progress.detail.takeIf { connecting?.deviceId == device.id },
                onConnect = { nav.askMethod(device.id, false) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun PermissionCard(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = cs.tertiaryContainer,
        contentColor = cs.onTertiaryContainer,
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.permissions_needed), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.permissions_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onRequest) { Text(stringResource(R.string.grant_permissions)) }
        }
    }
}

@Composable
private fun DevicePlaceholder(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Connection state for the Devices page. The detail page reuses this card without the action.
 */
@Composable
fun StatusCard(
    state: UiState,
    onClick: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val x = AppColors.extra
    val link = state.link
    val connected = link is Link.Connected

    val (container, content) = when (link) {
        is Link.Connected -> x.okContainer to x.ok
        is Link.Connecting -> cs.secondaryContainer to cs.onSecondaryContainer
        Link.Idle -> cs.surfaceContainerLow to cs.onSurfaceVariant
    }
    val bg by animateColorAsState(container, tween(350), label = "statusBg")
    val fg by animateColorAsState(content, tween(350), label = "statusFg")

    val pop = remember { Animatable(1f) }
    LaunchedEffect(connected) {
        if (connected) {
            pop.snapTo(0.96f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
    }

    val device = when (link) {
        is Link.Connected -> state.device(link.deviceId)
        is Link.Connecting -> state.device(link.deviceId)
        Link.Idle -> null
    }

    Surface(
        onClick = { onClick?.invoke() },
        enabled = connected && onClick != null,
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.large,
        border = if (link is Link.Idle) BorderStroke(1.dp, cs.outlineVariant) else null,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val progress = state.progress
                    val status = if (progress.stage.connected) {
                        stringResource(R.string.status_connected_progress, progress.stage.title)
                    } else progress.stage.title
                    val big = device?.name ?: stringResource(R.string.status_no_device)
                    Text(
                        stringResource(R.string.status_version_progress, BuildConfig.VERSION_NAME, status),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        big,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (link is Link.Idle) cs.onSurface else fg,
                    )
                    Text(state.progress.detail, style = MaterialTheme.typography.bodyMedium)
                }
                if ((link is Link.Connected || link is Link.Connecting) && onDisconnect != null) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        border = BorderStroke(1.5.dp, cs.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    ) {
                        Text(
                            stringResource(
                                if (link is Link.Connecting) R.string.action_stop
                                else R.string.action_disconnect,
                            ),
                        )
                    }
                }
            }
            if (state.progress.stage !in setOf(Stage.IDLE, Stage.FAILED, Stage.STREAMING)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = fg,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun RefreshButton(scanning: Boolean, onClick: () -> Unit) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(scanning) {
        if (scanning) {
            angle.snapTo(0f)
            angle.animateTo(360f, tween(900, easing = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)))
        }
    }
    FilledTonalIconButton(onClick = onClick, enabled = !scanning) {
        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_devices), modifier = Modifier.rotate(angle.value))
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    busy: Boolean,
    progress: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onConnect,
        enabled = !busy,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    progress ?: stringResource(device.signal.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FirstRunEffect(vm: DeviceViewModel) {
    val context = LocalContext.current
    val pending = remember { FirstRun.pending(context) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.finishFirstRun() }
    val uninstall = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val missing = Permissions.missing(context) + Permissions.optionalMissing(context)
        if (missing.isNotEmpty()) permissions.launch(Permissions.requested())
        else vm.finishFirstRun()
    }
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        if (FirstRun.officialInstalled(context)) uninstall.launch(FirstRun.uninstallOfficial())
        else {
            val missing = Permissions.missing(context) + Permissions.optionalMissing(context)
            if (missing.isNotEmpty()) permissions.launch(Permissions.requested())
            else vm.finishFirstRun()
        }
    }
}
