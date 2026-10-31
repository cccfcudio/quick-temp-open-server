package app.openshelf

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openshelf.share.BrowserRow
import app.openshelf.share.DownloadPhase
import app.openshelf.share.SafIntents
import app.openshelf.share.ShelfScreen
import app.openshelf.share.ShelfViewModel
import app.openshelf.share.UpdateStatus
import app.openshelf.share.ZipPhase
import app.openshelf.ui.AboutScreen
import app.openshelf.ui.BrowserScreen
import app.openshelf.ui.HomeScreen
import app.openshelf.ui.MpcImportDialog
import app.openshelf.ui.OpenShelfTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenShelfTheme {
                val vm: ShelfViewModel = viewModel()
                val screen by vm.screen.collectAsStateWithLifecycle()
                val mounts by vm.mounts.collectAsStateWithLifecycle()
                val username by vm.username.collectAsStateWithLifecycle()
                val password by vm.password.collectAsStateWithLifecycle()
                val port by vm.port.collectAsStateWithLifecycle()
                val share by vm.share.collectAsStateWithLifecycle()
                val browser by vm.browser.collectAsStateWithLifecycle()
                val listing by vm.listing.collectAsStateWithLifecycle()
                val selecting by vm.selecting.collectAsStateWithLifecycle()
                val selection by vm.selection.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val zip by vm.zip.collectAsStateWithLifecycle()
                val download by vm.download.collectAsStateWithLifecycle()
                val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var showKeepAlive by remember { mutableStateOf(false) }
                var batteryExempt by remember { mutableStateOf(isBatteryExempt()) }
                var dismissedUpdateTag by remember { mutableStateOf<String?>(null) }
                val uriHandler = LocalUriHandler.current
                val availableUpdate = updateStatus as? UpdateStatus.Available
                val showUpdatePrompt =
                    availableUpdate != null &&
                        availableUpdate.latestTag != dismissedUpdateTag &&
                        !showKeepAlive
                val notifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    showKeepAlive = true
                }
                val batterySettings = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    batteryExempt = isBatteryExempt()
                }
                val mpcImport by vm.mpcImport.collectAsStateWithLifecycle()
                val mpcPick by vm.mpcPick.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val uri = result.data?.data ?: return@rememberLauncherForActivityResult
                    vm.addTree(uri, result.data)
                }
                val jsonPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        vm.importMpc(uri)
                    }
                }
                val importPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    vm.finishMpcPick(result.data?.data, result.data)
                }
                LaunchedEffect(mpcPick?.token) {
                    val pick = mpcPick ?: return@LaunchedEffect
                    if (pick.launched) {
                        return@LaunchedEffect
                    }
                    vm.markMpcPickLaunched()
                    val initial = pick.initialUri?.let { raw ->
                        runCatching { Uri.parse(raw) }.getOrNull()
                    }
                    try {
                        importPicker.launch(SafIntents.openTree(pick.relative, initial))
                    } catch (error: Exception) {
                        vm.finishMpcPick(null, null)
                    }
                }
                LaunchedEffect(Unit) {
                    batteryExempt = isBatteryExempt()
                }
                LaunchedEffect(message) {
                    val text = message ?: return@LaunchedEffect
                    snackbar.showSnackbar(text)
                    vm.consumeMessage()
                }
                LaunchedEffect(zip) {
                    when (val phase = zip) {
                        is ZipPhase.Done -> {
                            snackbar.showSnackbar(getString(R.string.zip_saved, phase.label))
                            vm.consumeZip()
                        }
                        is ZipPhase.Failed -> {
                            snackbar.showSnackbar(phase.message)
                            vm.consumeZip()
                        }
                        ZipPhase.Idle, is ZipPhase.Running -> Unit
                    }
                }
                LaunchedEffect(download) {
                    when (val phase = download) {
                        is DownloadPhase.Done -> {
                            snackbar.showSnackbar(phase.message)
                            vm.consumeDownload()
                        }
                        is DownloadPhase.Failed -> {
                            snackbar.showSnackbar(phase.message)
                            vm.consumeDownload()
                        }
                        DownloadPhase.Idle, is DownloadPhase.Running -> Unit
                    }
                }
                BackHandler(enabled = screen != ShelfScreen.Home || mpcImport != null) {
                    if (mpcImport != null) {
                        vm.dismissMpcImport()
                    } else {
                        vm.goBack()
                    }
                }
                fun copy(text: String) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Quick Temp Open Server", text))
                    scope.launch { snackbar.showSnackbar(getString(R.string.copied)) }
                }
                fun beginStartShare() {
                    val need = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (need) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        showKeepAlive = true
                    }
                }
                fun proceedStartShare() {
                    showKeepAlive = false
                    batteryExempt = isBatteryExempt()
                    vm.startSharing()
                }
                fun openBatterySettings() {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                            Uri.parse("package:$packageName"),
                        )
                        batterySettings.launch(intent)
                    } catch (_: Exception) {
                        try {
                            batterySettings.launch(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        } catch (_: Exception) {
                            Toast.makeText(this, getString(R.string.battery_unavailable), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val importing = mpcImport
                if (importing != null) {
                    MpcImportDialog(
                        rows = importing,
                        onDismiss = vm::dismissMpcImport,
                        onConfirm = vm::confirmMpcImport,
                    )
                }
                if (showKeepAlive) {
                    AlertDialog(
                        onDismissRequest = { showKeepAlive = false },
                        title = { Text(getString(R.string.keepalive_title)) },
                        text = {
                            Text(
                                buildString {
                                    append(getString(R.string.keepalive_body))
                                    if (!batteryExempt) {
                                        append("\n\n")
                                        append(getString(R.string.keepalive_need_battery))
                                    }
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (!isBatteryExempt()) {
                                        openBatterySettings()
                                    } else {
                                        proceedStartShare()
                                    }
                                },
                            ) {
                                Text(
                                    if (batteryExempt) {
                                        getString(R.string.keepalive_continue)
                                    } else {
                                        getString(R.string.keepalive_open_battery)
                                    },
                                )
                            }
                        },
                        dismissButton = {
                            if (batteryExempt) {
                                TextButton(onClick = { showKeepAlive = false }) {
                                    Text(getString(R.string.cancel))
                                }
                            } else {
                                TextButton(onClick = { proceedStartShare() }) {
                                    Text(getString(R.string.keepalive_continue))
                                }
                            }
                        },
                    )
                }
                if (showUpdatePrompt && availableUpdate != null) {
                    AlertDialog(
                        onDismissRequest = { dismissedUpdateTag = availableUpdate.latestTag },
                        title = { Text(getString(R.string.update_prompt_title)) },
                        text = {
                            Text(
                                getString(
                                    R.string.update_prompt_body,
                                    availableUpdate.latestTag,
                                    BuildConfig.VERSION_NAME,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    uriHandler.openUri(availableUpdate.releaseUrl)
                                    dismissedUpdateTag = availableUpdate.latestTag
                                },
                            ) {
                                Text(getString(R.string.update_open_release))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { dismissedUpdateTag = availableUpdate.latestTag }) {
                                Text(getString(R.string.update_prompt_later))
                            }
                        },
                    )
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                            when (val current = screen) {
                                ShelfScreen.Home -> HomeScreen(
                                    mounts = mounts,
                                    username = username,
                                    password = password,
                                    port = port,
                                    share = share,
                                    batteryExempt = batteryExempt,
                                    onUsername = vm::setUsername,
                                    onPassword = vm::setPassword,
                                    onGeneratePassword = vm::generatePassword,
                                    onPort = vm::setPort,
                                    onPick = { relative -> picker.launch(SafIntents.openTree(relative)) },
                                    onImportMpc = {
                                        jsonPicker.launch(
                                            arrayOf(
                                                "application/json",
                                                "text/json",
                                                "text/plain",
                                                "application/octet-stream",
                                            ),
                                        )
                                    },
                                    onAppShare = vm::mountAppShare,
                                    onRemove = vm::remove,
                                    onStart = { beginStartShare() },
                                    onStop = vm::stopSharing,
                                    onStartTunnel = vm::startTunnel,
                                    onStopTunnel = vm::stopTunnel,
                                    onRefreshLan = vm::refreshLan,
                                    onBrowse = vm::openBrowser,
                                    onAbout = vm::openAbout,
                                    onBattery = { openBatterySettings() },
                                    onCopy = { copy(it) },
                                    loginLink = vm::loginLink,
                                    updateStatus = updateStatus,
                                    onCheckUpdates = vm::checkForUpdates,
                                )
                                ShelfScreen.About -> AboutScreen(
                                    onBack = vm::goBack,
                                    updateStatus = updateStatus,
                                    onCheckUpdates = vm::checkForUpdates,
                                )
                                is ShelfScreen.Browser -> {
                                    val title = when {
                                        current.mountId == null -> getString(R.string.browse)
                                        current.relative.isEmpty() -> mounts.firstOrNull { it.id == current.mountId }?.displayName
                                            ?: current.mountId
                                        else -> current.relative.substringAfterLast('/')
                                    }
                                    BrowserScreen(
                                        title = title,
                                        load = browser,
                                        options = listing,
                                        selecting = selecting,
                                        selection = selection,
                                        fileTools = current.mountId != null,
                                        zip = zip,
                                        download = download,
                                        mountId = current.mountId,
                                        folder = current.relative,
                                        onBack = vm::goBack,
                                        onRefresh = vm::refreshBrowser,
                                        onOpen = { row ->
                                            when (row) {
                                                is BrowserRow.MountRow -> vm.openMount(row.id)
                                                is BrowserRow.EntryRow -> if (row.entry.directory) {
                                                    vm.openDirectory(row.entry.name)
                                                }
                                            }
                                        },
                                        onOptions = vm::setListing,
                                        onPage = vm::setPage,
                                        onStartSelect = vm::startSelect,
                                        onToggleSelect = vm::toggleSelect,
                                        onSelectAll = vm::selectAll,
                                        onClearSelection = vm::clearSelection,
                                        onDelete = vm::deleteSelected,
                                        onCopyPaths = { copy(vm.selectedPathText()) },
                                        onSharePaths = vm::shareSelectedPaths,
                                        onZipSelected = vm::zipSelected,
                                        onZipFolder = vm::zipCurrentFolder,
                                        onDownload = vm::downloadFile,
                                        onDownloadSelected = vm::downloadSelected,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun isBatteryExempt(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(packageName)
    }
}
