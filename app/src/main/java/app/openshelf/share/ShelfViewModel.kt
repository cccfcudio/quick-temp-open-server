package app.openshelf.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.openshelf.OpenShelfApp
import app.openshelf.R
import app.openshelf.core.auth.IdGenerator
import app.openshelf.core.auth.PasswordGenerator
import app.openshelf.core.browse.Listing
import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.browse.SortDirection
import app.openshelf.core.browse.SortKey
import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.ShareDefaults
import app.openshelf.core.model.StoredMount
import app.openshelf.core.model.StoredMountKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ShelfScreen {
    data object Home : ShelfScreen
    data object About : ShelfScreen
    data class Browser(val mountId: String?, val relative: String) : ShelfScreen
}

sealed interface BrowserRow {
    data class MountRow(val id: String, val name: String) : BrowserRow
    data class EntryRow(val entry: FileEntry) : BrowserRow
}

sealed interface BrowserLoad {
    data object Loading : BrowserLoad
    data class Ready(
        val rows: List<BrowserRow>,
        val total: Int,
        val filtered: Int,
        val page: Int,
        val pages: Int,
        val pageSize: Int,
    ) : BrowserLoad
    data class Failed(val message: String) : BrowserLoad
}

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = (app as OpenShelfApp).prefs
    val mounts = prefs.mounts
    val username = prefs.username
    val password = prefs.password
    val port = prefs.port
    val share = ShareHub.state

    private val screenState = MutableStateFlow<ShelfScreen>(ShelfScreen.Home)
    val screen: StateFlow<ShelfScreen> = screenState.asStateFlow()

    private val browserState = MutableStateFlow<BrowserLoad>(BrowserLoad.Ready(emptyList(), 0, 0, 1, 1, 50))
    val browser: StateFlow<BrowserLoad> = browserState.asStateFlow()

    private val listingState = MutableStateFlow(prefs.currentListing())
    val listing: StateFlow<ListingOptions> = listingState.asStateFlow()

    private val selectingState = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = selectingState.asStateFlow()

    private val selectionState = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = selectionState.asStateFlow()

    private var cachedLocation: ShelfScreen.Browser? = null
    private var cachedRows: List<BrowserRow> = emptyList()

    private val pageState = MutableStateFlow(1)
    val page: StateFlow<Int> = pageState.asStateFlow()

    private val updateState = MutableStateFlow<UpdateStatus>(UpdateStatus.Checking)
    val updateStatus: StateFlow<UpdateStatus> = updateState.asStateFlow()

    val zip: StateFlow<ZipPhase> = ZipHub.phase
    val download: StateFlow<DownloadPhase> = DownloadHub.phase

    private val messageState = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messageState.asStateFlow()

    fun consumeMessage() {
        messageState.value = null
    }

    init {
        checkForUpdates()
    }

    fun checkForUpdates() {
        updateState.value = UpdateStatus.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.checkLatest() }
            updateState.value = result
        }
    }


    fun setUsername(value: String) {
        prefs.setUsername(value)
    }

    fun setPassword(value: String) {
        val tunnel = ShareHub.state.value.tunnelPhase
        val tunnelUp = tunnel is TunnelPhase.Up || tunnel is TunnelPhase.Starting || tunnel is TunnelPhase.Unreachable
        if (value.isEmpty() && tunnelUp) {
            return
        }
        prefs.setPassword(value)
    }

    fun setPort(text: String) {
        val value = text.toIntOrNull() ?: return
        prefs.setPort(value)
    }

    fun generatePassword() {
        prefs.setPassword(PasswordGenerator.generate())
    }

    fun addTree(uri: Uri, result: Intent?) {
        val context = getApplication<Application>()
        val flags = result?.flags ?: 0
        val requested = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val granted = if (requested != 0) {
            requested
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, granted)
        } catch (error: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: SecurityException) {
                messageState.value = "無法保留資料夾權限"
                return
            }
        }
        prefs.add(StoredMount(IdGenerator.newMountId(), treeName(uri), uri.toString(), StoredMountKind.SAF_TREE))
    }

    fun mountAppShare() {
        val context = getApplication<Application>()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(base, "share")
        directory.mkdirs()
        val note = File(directory, "請放檔案到呢度.txt")
        if (!note.exists()) {
            note.writeText("呢個係快速臨時開放伺服器嘅應用程式分享夾。\n將檔案放喺呢度，就可以用本機網址或者 Try Cloudflare 分享。\n")
        }
        prefs.add(StoredMount("share", "分享夾", directory.absolutePath, StoredMountKind.APP_DIRECTORY))
    }

    fun remove(mount: StoredMount) {
        if (mount.kind == StoredMountKind.SAF_TREE) {
            try {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(mount.location),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (error: SecurityException) {
                messageState.value = null
            }
        }
        prefs.remove(mount.id)
    }

    fun startSharing() = command(ShareService.ACTION_START)

    fun stopSharing() = command(ShareService.ACTION_STOP)

    fun startTunnel() = command(ShareService.ACTION_START_TUNNEL)

    fun stopTunnel() = command(ShareService.ACTION_STOP_TUNNEL)

    fun refreshLan() {
        ShareHub.refreshLan()
    }

    fun setListing(options: ListingOptions) {
        val previous = listingState.value
        val normalized = options.copy(pageSize = Listing.normalizePageSize(options.pageSize), query = options.query.take(200))
        listingState.value = normalized
        val viewChanged = previous.sort != normalized.sort ||
            previous.direction != normalized.direction ||
            previous.kind != normalized.kind ||
            previous.pageSize != normalized.pageSize ||
            previous.query != normalized.query
        if (viewChanged) {
            pageState.value = 1
        }
        if (previous.sort != normalized.sort ||
            previous.direction != normalized.direction ||
            previous.kind != normalized.kind ||
            previous.pageSize != normalized.pageSize
        ) {
            prefs.setListing(normalized)
        }
        publishArranged()
    }

    fun setPage(page: Int) {
        pageState.value = page.coerceAtLeast(1)
        publishArranged()
    }

    fun startSelect(name: String) {
        selectingState.value = true
        selectionState.value = selectionState.value + name
    }

    fun toggleSelect(name: String) {
        val next = if (name in selectionState.value) {
            selectionState.value - name
        } else {
            selectionState.value + name
        }
        selectionState.value = next
        if (next.isEmpty()) {
            selectingState.value = false
        }
    }

    fun selectAll() {
        val names = visibleNames()
        if (names.isEmpty()) {
            return
        }
        selectionState.value = selectionState.value + names
        selectingState.value = true
    }

    fun clearSelection() {
        selectionState.value = emptySet()
        selectingState.value = false
    }

    fun selectedPathText(): String {
        val location = screenState.value as? ShelfScreen.Browser ?: return ""
        val mountId = location.mountId ?: return ""
        val mountName = prefs.currentMounts().firstOrNull { it.id == mountId }?.displayName ?: mountId
        return selectionState.value.sorted().joinToString("\n") { name ->
            val relative = if (location.relative.isEmpty()) name else "${location.relative}/$name"
            "$mountName/$relative"
        }
    }

    fun shareSelectedPaths() {
        val text = selectedPathText()
        if (text.isEmpty()) {
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, getApplication<Application>().getString(R.string.share_paths))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(chooser)
    }

    fun deleteSelected() {
        val location = screenState.value as? ShelfScreen.Browser ?: return
        val mountId = location.mountId ?: return
        val names = selectionState.value.toList()
        if (names.isEmpty()) {
            return
        }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val store = AndroidFileStore(getApplication()) { prefs.currentMounts() }
                var count = 0
                var failure: String? = null
                for (name in names) {
                    val relative = if (location.relative.isEmpty()) name else "${location.relative}/$name"
                    try {
                        store.delete(mountId, relative)
                        count += 1
                    } catch (error: Exception) {
                        failure = error.message ?: getApplication<Application>().getString(R.string.delete_failed)
                    }
                }
                count to failure
            }
            if (screenState.value == location) {
                clearSelection()
                val (count, failure) = deleted
                messageState.value = failure
                    ?: getApplication<Application>().getString(R.string.deleted_count, count)
                refreshBrowser()
            }
        }
    }

    fun zipSelected() {
        val location = screenState.value as? ShelfScreen.Browser ?: return
        val mountId = location.mountId ?: return
        val names = selectionState.value.toList()
        if (names.isEmpty()) {
            messageState.value = getApplication<Application>().getString(R.string.download_none)
            return
        }
        startZip(ZipRequest(mountId, location.relative, names, folderLabel(location)))
    }

    fun downloadFile(name: String) {
        startDownload(listOf(name))
    }

    fun downloadSelected() {
        startDownload(selectionState.value.toList())
    }

    fun consumeDownload() {
        DownloadHub.acknowledge()
    }

    fun zipCurrentFolder() {
        val location = screenState.value as? ShelfScreen.Browser ?: return
        val mountId = location.mountId ?: return
        startZip(ZipRequest(mountId, location.relative, emptyList(), folderLabel(location)))
    }

    fun consumeZip() {
        ZipHub.acknowledge()
    }

    fun openBrowser() {
        clearSelection()
        pageState.value = 1
        screenState.value = ShelfScreen.Browser(null, "")
        refreshBrowser()
    }

    fun openAbout() {
        screenState.value = ShelfScreen.About
    }

    fun openMount(id: String) {
        clearSelection()
        pageState.value = 1
        screenState.value = ShelfScreen.Browser(id, "")
        refreshBrowser()
    }

    fun openDirectory(name: String) {
        val current = screenState.value as? ShelfScreen.Browser ?: return
        clearSelection()
        pageState.value = 1
        val relative = if (current.relative.isEmpty()) name else "${current.relative}/$name"
        screenState.value = current.copy(relative = relative)
        refreshBrowser()
    }

    fun goBack() {
        if (selectingState.value) {
            clearSelection()
            return
        }
        when (val current = screenState.value) {
            ShelfScreen.Home -> Unit
            ShelfScreen.About -> screenState.value = ShelfScreen.Home
            is ShelfScreen.Browser -> {
                if (current.relative.isNotEmpty()) {
                    val parent = if (current.relative.contains('/')) {
                        current.relative.substringBeforeLast('/')
                    } else {
                        ""
                    }
                    pageState.value = 1
                    screenState.value = current.copy(relative = parent)
                    refreshBrowser()
                } else if (current.mountId != null) {
                    pageState.value = 1
                    screenState.value = ShelfScreen.Browser(null, "")
                    refreshBrowser()
                } else {
                    screenState.value = ShelfScreen.Home
                }
            }
        }
    }

    fun refreshBrowser() {
        val location = screenState.value as? ShelfScreen.Browser ?: return
        browserState.value = BrowserLoad.Loading
        viewModelScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) {
                    val store = AndroidFileStore(getApplication()) { prefs.currentMounts() }
                    if (location.mountId == null) {
                        store.mounts().map { BrowserRow.MountRow(it.id, it.displayName) }
                    } else {
                        store.list(location.mountId, location.relative).map { BrowserRow.EntryRow(it) }
                    }
                }
            } catch (error: Exception) {
                if (screenState.value == location) {
                    browserState.value = BrowserLoad.Failed(error.message ?: "無法讀取")
                }
                return@launch
            }
            if (screenState.value != location) {
                return@launch
            }
            cachedRows = rows
            cachedLocation = location
            publishArranged()
        }
    }

    private fun publishArranged() {
        val location = cachedLocation ?: return
        if (screenState.value != location) {
            return
        }
        val rows = arrangeRows(location, cachedRows, listingState.value)
        val allowed = rows.mapNotNull { row ->
            (row as? BrowserRow.EntryRow)?.entry?.name
        }.toSet()
        selectionState.value = selectionState.value.intersect(allowed)
        if (selectionState.value.isEmpty()) {
            selectingState.value = false
        }
        val window = Listing.window(rows, pageState.value, listingState.value.pageSize)
        if (pageState.value != window.page) {
            pageState.value = window.page
        }
        browserState.value = BrowserLoad.Ready(
            rows = window.items,
            total = cachedRows.size,
            filtered = rows.size,
            page = window.page,
            pages = window.pages,
            pageSize = listingState.value.pageSize,
        )
    }

    private fun arrangeRows(
        location: ShelfScreen.Browser,
        rows: List<BrowserRow>,
        options: ListingOptions,
    ): List<BrowserRow> {
        if (location.mountId == null) {
            val mounts = rows.filterIsInstance<BrowserRow.MountRow>().filter { row ->
                options.query.isBlank() || row.name.contains(options.query, ignoreCase = true)
            }
            val sorted = mounts.sortedBy { it.name.lowercase() }
            return if (options.direction == SortDirection.DESC && options.sort == SortKey.NAME) {
                sorted.reversed()
            } else {
                sorted
            }
        }
        val entries = rows.filterIsInstance<BrowserRow.EntryRow>().map { it.entry }
        return Listing.arrange(entries, options).map { BrowserRow.EntryRow(it) }
    }

    private fun visibleNames(): Set<String> {
        val ready = browserState.value as? BrowserLoad.Ready ?: return emptySet()
        return ready.rows.mapNotNull { row -> (row as? BrowserRow.EntryRow)?.entry?.name }.toSet()
    }

    private fun startDownload(names: List<String>) {
        val location = screenState.value as? ShelfScreen.Browser ?: return
        val mountId = location.mountId ?: return
        val app = getApplication<Application>()
        if (names.isEmpty()) {
            messageState.value = app.getString(R.string.download_none)
            return
        }
        if (!DownloadHub.begin(DownloadRequest(mountId, location.relative, names))) {
            messageState.value = app.getString(R.string.download_busy)
            return
        }
        try {
            val intent = Intent(getApplication(), DownloadService::class.java)
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (error: Exception) {
            DownloadHub.fail(app.getString(R.string.download_failed))
        }
    }

    private fun startZip(request: ZipRequest) {
        if (!ZipHub.begin(request)) {
            messageState.value = getApplication<Application>().getString(R.string.zip_busy)
            return
        }
        try {
            val intent = Intent(getApplication(), ZipService::class.java)
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (_: Exception) {
            ZipHub.fail(getApplication<Application>().getString(R.string.zip_failed))
        }
    }

    private fun folderLabel(location: ShelfScreen.Browser): String {
        if (location.relative.isNotEmpty()) {
            return location.relative.substringAfterLast('/')
        }
        val mountId = location.mountId ?: return "openshelf"
        return prefs.currentMounts().firstOrNull { it.id == mountId }?.displayName ?: mountId
    }

    private fun command(action: String) {
        val intent = Intent(getApplication(), ShareService::class.java).setAction(action)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    private fun treeName(uri: Uri): String {
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (error: IllegalArgumentException) {
            return "資料夾"
        }
        val path = documentId.substringAfter(':', documentId)
        return path.substringAfterLast('/').ifBlank { path }.ifBlank { "資料夾" }.take(80)
    }

    fun loginLink(url: String): String {
        val secret = prefs.currentPassword()
        if (secret.isEmpty()) {
            return url
        }
        val scheme = if (url.startsWith("https://")) "https://" else "http://"
        val rest = url.removePrefix(scheme)
        val user = Uri.encode(prefs.currentUsername())
        return scheme + user + ":" + Uri.encode(secret) + "@" + rest
    }
}
