package app.openshelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.openshelf.R
import app.openshelf.core.browse.FileKind
import app.openshelf.core.browse.Listing
import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.browse.SortDirection
import app.openshelf.core.browse.SortKey
import app.openshelf.core.text.TextFormat
import app.openshelf.share.BrowserLoad
import app.openshelf.share.BrowserRow
import app.openshelf.share.DownloadPhase
import app.openshelf.share.ZipPhase

@Composable
fun BrowserScreen(
    title: String,
    load: BrowserLoad,
    options: ListingOptions,
    selecting: Boolean,
    selection: Set<String>,
    fileTools: Boolean,
    zip: ZipPhase,
    download: DownloadPhase,
    mountId: String?,
    folder: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (BrowserRow) -> Unit,
    onOptions: (ListingOptions) -> Unit,
    onPage: (Int) -> Unit,
    onStartSelect: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: () -> Unit,
    onCopyPaths: () -> Unit,
    onSharePaths: () -> Unit,
    onZipSelected: () -> Unit,
    onZipFolder: () -> Unit,
    onDownload: (String) -> Unit,
    onDownloadSelected: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, selection.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.delete_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        }
        ListingControls(options, fileTools, onOptions, onZipFolder)
        if (zip is ZipPhase.Running) {
            val text = if (zip.total <= 0) {
                stringResource(R.string.zip_preparing)
            } else {
                stringResource(R.string.zip_running, zip.done, zip.total)
            }
            JobBanner(text)
        }
        if (download is DownloadPhase.Running) {
            val text = if (download.total <= 0) {
                stringResource(R.string.download_preparing)
            } else {
                stringResource(R.string.download_running, download.done, download.total) + " " + download.name
            }
            JobBanner(text)
        }
        if (selecting) {
            SelectionBar(
                count = selection.size,
                onSelectAll = onSelectAll,
                onClear = onClearSelection,
                onDelete = { if (selection.isNotEmpty()) confirmDelete = true },
                onCopy = onCopyPaths,
                onShare = onSharePaths,
                onZip = onZipSelected,
                onDownload = onDownloadSelected,
            )
        }
        when (load) {
            BrowserLoad.Loading -> {
                CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            }
            is BrowserLoad.Failed -> {
                Text(
                    load.message,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is BrowserLoad.Ready -> {
                PageBar(load, onPage)
                when {
                    load.rows.isEmpty() && load.total == 0 -> {
                        Text(stringResource(R.string.empty_folder), modifier = Modifier.padding(20.dp))
                    }
                    load.rows.isEmpty() -> {
                        Text(stringResource(R.string.no_matches), modifier = Modifier.padding(20.dp))
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(load.rows, key = { rowKey(it) }) { row ->
                                BrowserLine(
                                    row = row,
                                    selecting = selecting,
                                    selected = row is BrowserRow.EntryRow && row.entry.name in selection,
                                    onOpen = onOpen,
                                    onStartSelect = onStartSelect,
                                    onToggleSelect = onToggleSelect,
                                    onDownload = onDownload,
                                    mountId = mountId,
                                    folder = folder,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListingControls(
    options: ListingOptions,
    fileTools: Boolean,
    onOptions: (ListingOptions) -> Unit,
    onZipFolder: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Choice(stringResource(R.string.sort_name), options.sort == SortKey.NAME) {
                onOptions(options.copy(sort = SortKey.NAME))
            }
            if (fileTools) {
                Choice(stringResource(R.string.sort_size), options.sort == SortKey.SIZE) {
                    onOptions(options.copy(sort = SortKey.SIZE))
                }
                Choice(stringResource(R.string.sort_modified), options.sort == SortKey.MODIFIED) {
                    onOptions(options.copy(sort = SortKey.MODIFIED))
                }
            }
            val descending = options.direction == SortDirection.DESC
            Choice(stringResource(if (descending) R.string.sort_desc else R.string.sort_asc), true) {
                onOptions(
                    options.copy(direction = if (descending) SortDirection.ASC else SortDirection.DESC),
                )
            }
        }
        if (fileTools) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Choice(stringResource(R.string.filter_all), options.kind == null) {
                    onOptions(options.copy(kind = null))
                }
                Choice(stringResource(R.string.filter_image), options.kind == FileKind.IMAGE) {
                    onOptions(options.copy(kind = FileKind.IMAGE))
                }
                Choice(stringResource(R.string.filter_video), options.kind == FileKind.VIDEO) {
                    onOptions(options.copy(kind = FileKind.VIDEO))
                }
                Choice(stringResource(R.string.filter_doc), options.kind == FileKind.DOCUMENT) {
                    onOptions(options.copy(kind = FileKind.DOCUMENT))
                }
                Choice(stringResource(R.string.filter_other), options.kind == FileKind.OTHER) {
                    onOptions(options.copy(kind = FileKind.OTHER))
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (size in intArrayOf(20, 50, 100)) {
                Choice(size.toString(), Listing.normalizePageSize(options.pageSize) == size) {
                    onOptions(options.copy(pageSize = size))
                }
            }
        }
        if (fileTools) {
            TextButton(onClick = onZipFolder) { Text(stringResource(R.string.zip_folder)) }
        }
        OutlinedTextField(
            value = options.query,
            onValueChange = { onOptions(options.copy(query = it)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.search_hint)) },
        )
    }
}

@Composable
private fun PageBar(load: BrowserLoad.Ready, onPage: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { onPage(load.page - 1) }, enabled = load.page > 1) {
            Text(stringResource(R.string.page_prev))
        }
        Text(
            stringResource(R.string.page_status, load.page, load.pages, load.filtered),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { onPage(load.page + 1) }, enabled = load.page < load.pages) {
            Text(stringResource(R.string.page_next))
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun JobBanner(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onZip: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.selected_count, count), modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.clear_selection)) }
        TextButton(onClick = onDelete, enabled = count > 0) { Text(stringResource(R.string.delete_selected)) }
        TextButton(onClick = onDownload, enabled = count > 0) { Text(stringResource(R.string.download_selected)) }
        TextButton(onClick = onZip, enabled = count > 0) { Text(stringResource(R.string.zip_selected)) }
        TextButton(onClick = onCopy, enabled = count > 0) { Text(stringResource(R.string.copy_paths)) }
        TextButton(onClick = onShare, enabled = count > 0) { Text(stringResource(R.string.share_paths)) }
    }
}

@Composable
private fun BrowserLine(
    row: BrowserRow,
    selecting: Boolean,
    selected: Boolean,
    onOpen: (BrowserRow) -> Unit,
    onStartSelect: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    mountId: String?,
    folder: String,
) {
    val name: String
    val detail: String
    when (row) {
        is BrowserRow.MountRow -> {
            name = row.name
            detail = stringResource(R.string.folder)
        }
        is BrowserRow.EntryRow -> {
            name = row.entry.name
            val whenText = TextFormat.clock(row.entry.modifiedEpochMs)
            detail = if (row.entry.directory) {
                if (whenText.isEmpty()) stringResource(R.string.folder) else stringResource(R.string.folder) + " · " + whenText
            } else {
                val size = TextFormat.size(row.entry.size)
                if (whenText.isEmpty()) size else "$size · $whenText"
            }
        }
    }
    val fileName = (row as? BrowserRow.EntryRow)?.takeIf { !it.entry.directory }?.entry?.name
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting && row is BrowserRow.EntryRow) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect(row.entry.name) },
            )
        }
        FileThumb(mountId = mountId, folder = folder, row = row)
        val label = if (row is BrowserRow.EntryRow && row.entry.directory) "$name/" else name
        Column(
            modifier = Modifier
                .weight(1f)
                .pointerInput(row, selecting) {
                    detectTapGestures(
                        onLongPress = {
                            if (row is BrowserRow.EntryRow) {
                                onStartSelect(row.entry.name)
                            }
                        },
                        onTap = {
                            when (row) {
                                is BrowserRow.MountRow -> onOpen(row)
                                is BrowserRow.EntryRow -> if (selecting) {
                                    onToggleSelect(row.entry.name)
                                } else if (row.entry.directory) {
                                    onOpen(row)
                                } else {
                                    onStartSelect(row.entry.name)
                                }
                            }
                        },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (fileName != null) {
            TextButton(onClick = { onDownload(fileName) }) {
                Text(stringResource(R.string.download_file))
            }
        }
    }
}

private fun rowKey(row: BrowserRow): String = when (row) {
    is BrowserRow.MountRow -> "m:${row.id}"
    is BrowserRow.EntryRow -> "e:${row.entry.name}:${row.entry.directory}"
}
