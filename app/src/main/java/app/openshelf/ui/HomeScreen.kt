package app.openshelf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.openshelf.R
import app.openshelf.core.driver.DriverCatalog
import app.openshelf.core.model.DriverKind
import app.openshelf.core.model.ShareDefaults
import app.openshelf.core.model.StoredMount
import app.openshelf.core.model.StoredMountKind
import app.openshelf.share.ServerPhase
import app.openshelf.share.ShareUiState
import app.openshelf.share.TunnelPhase

@Composable
fun HomeScreen(
    mounts: List<StoredMount>,
    password: String,
    port: Int,
    share: ShareUiState,
    onPassword: (String) -> Unit,
    onGeneratePassword: () -> Unit,
    onPort: (String) -> Unit,
    onPick: (String?) -> Unit,
    onAppShare: () -> Unit,
    onRemove: (StoredMount) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onRefreshLan: () -> Unit,
    onBrowse: () -> Unit,
    onAbout: () -> Unit,
    onBattery: () -> Unit,
    onCopy: (String) -> Unit,
    loginLink: (String) -> String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.browse))
        }
        MountCard(mounts, onPick, onAppShare, onRemove)
        PasswordCard(password, onPassword, onGeneratePassword, onCopy)
        TunnelCard(share, password, onStartTunnel, onStopTunnel, onCopy, loginLink)
        ShareCard(share, port, onPort, onStart, onStop, onRefreshLan, onCopy, password, loginLink)
        DriverCard()
        TextButton(onClick = onBattery) { Text(stringResource(R.string.allow_background)) }
        Text(
            stringResource(R.string.battery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about)) }
    }
}

@Composable
private fun MountCard(
    mounts: List<StoredMount>,
    onPick: (String?) -> Unit,
    onAppShare: () -> Unit,
    onRemove: (StoredMount) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.section_mounts), style = MaterialTheme.typography.titleMedium)
            if (mounts.isEmpty()) {
                Text(stringResource(R.string.empty_mounts))
            }
            for (mount in mounts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mount.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (mount.kind) {
                                StoredMountKind.SAF_TREE -> stringResource(R.string.kind_saf)
                                StoredMountKind.APP_DIRECTORY -> stringResource(R.string.kind_app)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onRemove(mount) }) { Text(stringResource(R.string.unmount)) }
                }
            }
            Button(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_folder))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPick("DCIM") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shortcut_dcim))
                }
                OutlinedButton(onClick = { onPick("Pictures") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shortcut_pictures))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onPick("Download") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shortcut_downloads))
                }
                OutlinedButton(onClick = onAppShare, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.shortcut_app_share))
                }
            }
            Text(
                stringResource(R.string.shortcut_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ShareCard(
    share: ShareUiState,
    port: Int,
    onPort: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshLan: () -> Unit,
    onCopy: (String) -> Unit,
    password: String,
    loginLink: (String) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.section_share), style = MaterialTheme.typography.titleMedium)
            val running = share.phase == ServerPhase.RUNNING
            Text(
                if (running) {
                    stringResource(R.string.server_running_at, share.port)
                } else {
                    stringResource(R.string.server_stopped)
                },
            )
            if (share.error != null) {
                Text(share.error, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = port.toString(),
                onValueChange = onPort,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                supportingText = {
                    if (running && port != share.port) {
                        Text(stringResource(R.string.port_applied_next_start))
                    }
                },
            )
            Button(
                onClick = if (running) onStop else onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (running) stringResource(R.string.stop_share) else stringResource(R.string.start_share))
            }
            if (running) {
                if (share.lanHosts.isEmpty()) {
                    Text(stringResource(R.string.no_lan))
                }
                for (host in share.lanHosts) {
                    val url = "http://$host:${share.port}/"
                    CopyLine(stringResource(R.string.lan_address), url, onCopy)
                    CopyLine(stringResource(R.string.webdav_address), "http://$host:${share.port}/dav/", onCopy)
                    if (password.isNotEmpty()) {
                        TextButton(onClick = { onCopy(loginLink(url)) }) {
                            Text(stringResource(R.string.copy_with_password))
                        }
                    }
                }
                TextButton(onClick = onRefreshLan) { Text(stringResource(R.string.refresh_lan)) }
                Text(
                    stringResource(R.string.username_hint, ShareDefaults.USERNAME),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PasswordCard(
    password: String,
    onPassword: (String) -> Unit,
    onGenerate: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.password), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGenerate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.generate_password))
                }
                OutlinedButton(
                    onClick = { if (password.isNotEmpty()) onCopy(password) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.copy))
                }
            }
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) stringResource(R.string.hide_password) else stringResource(R.string.show_password))
            }
        }
    }
}

@Composable
private fun TunnelCard(
    share: ShareUiState,
    password: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    loginLink: (String) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.section_tunnel), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tunnel_body))
            Text(
                stringResource(R.string.quick_tunnel_warning),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            when (val phase = share.tunnelPhase) {
                TunnelPhase.Idle -> Text(stringResource(R.string.tunnel_idle))
                TunnelPhase.Starting -> {
                    Text(stringResource(R.string.tunnel_starting))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TunnelPhase.Up -> Text(stringResource(R.string.tunnel_up))
                TunnelPhase.BinaryMissing -> Text(
                    stringResource(R.string.tunnel_missing_binary),
                    color = MaterialTheme.colorScheme.error,
                )
                is TunnelPhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            }
            val busy = share.tunnelPhase is TunnelPhase.Starting || share.tunnelPhase is TunnelPhase.Up
            Button(
                onClick = if (busy) onStop else onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (busy) stringResource(R.string.stop_tunnel) else stringResource(R.string.start_tunnel))
            }
            val url = share.publicUrl
            if (url != null) {
                CopyLine(stringResource(R.string.public_url), url, onCopy)
                if (password.isNotEmpty()) {
                    TextButton(onClick = { onCopy(loginLink(url)) }) {
                        Text(stringResource(R.string.copy_with_password))
                    }
                }
                val bitmap = remember(url) { qrBitmap(url) }
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.qr_content_description),
                    modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit,
                )
            }
            val log = share.tunnelLog.lineSequence().lastOrNull { it.isNotBlank() }
            if (!log.isNullOrBlank()) {
                Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DriverCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.section_drivers), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.driver_todo), style = MaterialTheme.typography.bodySmall)
            for (driver in DriverCatalog.upcoming) {
                val label = when (driver.kind) {
                    DriverKind.S3 -> stringResource(R.string.driver_s3)
                    DriverKind.WEBDAV_REMOTE -> stringResource(R.string.driver_webdav)
                    DriverKind.ALIYUN_DRIVE -> stringResource(R.string.driver_aliyun)
                    DriverKind.ONEDRIVE -> stringResource(R.string.driver_onedrive)
                    DriverKind.LOCAL_FOLDER -> stringResource(R.string.driver_local)
                }
                Text("· $label")
            }
        }
    }
}

@Composable
private fun CopyLine(label: String, value: String, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onCopy(value) }) { Text(stringResource(R.string.copy)) }
        }
    }
}
